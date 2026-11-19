# FIN CALC-R0 — Independent S1 Architecture / Data Engineering Review

**Date:** 2026-11-19  
**Repository/branch:** `alimbetov/fin-analyzer` / `feat/fin-form-library-standalone`  
**Review scope:** `docs/FIN-CALC-R0-INCREMENTAL-CALCULATION-ARCHITECTURE-TZ.md`; `docs/FIN-CALC-R0-COVERAGE-166-BASELINE.tsv`; `docs/FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv`; evidence from `FormDraftService`, `FieldMetricContractGuard`, `CatalogBootstrap`, `ReportDerivedTotalService`, Liquibase and source TSVs.  
**Verdict:** **DESIGN ACCEPTED FOR RESTRICTED R0.B LOCAL TEST IMPLEMENTATION — PRODUCTION / FINANCIAL S2 HOLD**. This review is architectural S1, not independent financial S2. Neither JVM nor PostgreSQL runtime tests were executed as part of this documentation review.

## 1. Findings and decisions

| Severity | ID | Original specification defect / risk | Review disposition |
|---|---|---|---|
| **P0** | S1-01 | Whole-FactSet SHA in a single fingerprint invalidates all nodes when only operatingExpenses changes; A10 contradicts FR-08 | **SPEC FIXED**: separate binding-level executionFingerprint from node-level computational fingerprint using only consumed operands and upstream node hashes. |
| **P0** | S1-02 | Unique result per fingerprint + result.plan_id could reuse old result under new FactSet/plan, corrupting lineage | **SPEC FIXED**: exact binding dedups to original plan; new FactSet always produces a new complete immutable result snapshot, safely copying unchanged nodes with new lineage. |
| **P0** | S1-03 | TX-D combined result commit with publication, but crash test expects recoverable state between them | **SPEC FIXED**: separate TX-D result+SUCCEEDED and TX-E guarded publication; independent idempotent retry. |
| **P0** | S1-04 | `published_at` on a local PostgreSQL outbox misstates delivery; no atomic plan creation + event ACK | **SPEC FIXED**: `processed_at`, producer event identity, bounded retries, DB-local SKIP LOCKED and atomic enqueue+ACK in TX-B. |
| **P0** | S1-05 | Independent external approval service cannot be transactionally validated by one SELECT before pointer update | **SPEC FIXED FOR LOCAL TEST**: serialize local TEST_ONLY approval/revoke and pointer advancement in the same DB critical section; external production authority remains **HOLD** until a proven synchronized epoch/read gate exists. |
| **P0** | S1-06 | Summary 166×17 matrix lacks field-level semantics, formula mappings and approved input/metric edges | **BASELINE EXPANDED**: 794 unique field rows in `FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv`; missing semantics left UNREVIEWED, not inferred. Full approval not achieved. |
| **P0** | S1-07 | No proof that `fin_intake` historical revisions are protected from SQL UPDATE/DELETE at DB layer | **GATE ADDED, NOT IMPLEMENTED**: append-only DB guard or equivalent privilege isolation plus negative PG tests before real qualified release. |
| **P0** | S1-08 | F02 version label and existing FieldMetricContractGuard could be misread as S2 approval | **SPEC FIXED**: pin actual runtime `form_version_id`+SHA, guard confirms structural bindings only; financial approval separate. |
| **P1** | S1-09 | Missing precision policy for F02 outputs, zero denominator, signed cost convention | **SPEC CLARIFIED**: typed BigDecimal, explicit states, versioned rounding/sign rules; pilot-only proposed formulas. |
| **P1** | S1-10 | Missing exact source reader/projection boundary; risk of building parallel third ingestion scheme | **SPEC FIXED**: R0 reads pinned F02 immutable revision directly; no `fin_read` schema; ReportGrid adapter out-of-scope. |
| **P1** | S1-11 | Job queue lease may be checked in Java before compute and stale owner could write on return | **SPEC FIXED**: conditional fenced DB UPDATE+result insert; reaper/heartbeat and bounded retries. |
| **P1** | S1-12 | Preview can leak or compute on arbitrary tenant scope without IAM; source actor label is untrusted | **SPEC RESTRICTED**: bounded local-loopback preview with no production approval endpoint; IAM/ABAC remains release blocker. |
| **P1** | S1-13 | Full scope dimensions missing from unique current result identity | **SPEC FIXED**: include tenant, economic entity, business scope, project, period, variant, basis, currency, coverage, data space. |
| **P1** | S1-14 | `calc_result` called immutable without a database mechanism | **IMPLEMENTATION GATE**: PostgreSQL UPDATE/DELETE denial, reconciliation/negative tests on exact schema. |

## 2. Source and coverage evidence

- `form-navigation-r10.tsv`: **166** unique codes; `FINANCE=8`, `PLANNING=10`, `LEDGERS=14`, `EVIDENCE=12`, `DETAIL=59`, `SECTOR=63`.
- `form-field-presentation-review-r6.tsv`: **794** unique `(formCode,fieldCode)` candidate entries across **154** forms. Remaining 12 evidence document forms have no R6 entries, though their runtime bootstrap provides generic metadata; runtime DB must be separately reconciled.
- `analytics-model-catalog.tsv`: **42** design/pilot model declarations, 37 DESIGN_ONLY and 5 CALC_PILOT; not execution evidence.
- `CatalogBootstrap`: clean bootstrap content modes 3 SINGLE_RECORD, 151 ROWSET, 12 EVIDENCE; these counts are not assertions about arbitrary populated installations.
- `FormDraftService`: `fin_intake.lib_form_revision.values_json` (TEXT containing JSON) for F02, pinned form version after initial save, optimistic and idempotent revision semantics.
- `FieldMetricContractGuard`: checks structural field-to-metric binding, not formal S2 approval or tenant authorization.
- `ReportDerivedTotalService`: SUM/LAST_VALUE only, presentation-level UNQUALIFIED_DRAFT; cannot be presented as FIN HTAP calculation kernel.
- `lib_form_revision` / `lib_form_revision_row`: DB-level UPDATE/DELETE deny trigger not demonstrated by examined 001–018 migrations; absence of evidence is a release blocker, not a claim of production immutability.

## 3. Specific design decisions to preserve

### 3.1 Change impact and reuse

For F02, define three derived nodes:

```text
grossProfit      = netRevenue - cogs
grossMargin      = grossProfit / netRevenue
operatingResult  = grossProfit - operatingExpenses
```

After editing *only* `operatingExpenses`, **executionFingerprint changes** due to new qualified binding; only the `operatingResult` **nodeFingerprint** changes. The new immutable result must retain all 3 output fields and have new full lineage while reusing two verified old node values. No same result row + new plan FK. Validate missing/zero input states and policy signatures.

### 3.2 Queue and outbox

Outbox is a **durable event inbox/dispatch source**, not the worker queue. One committed revision produces one logical event; on replay it produces none. Local dispatch uses a single TX-B for exactly-one plan/job creation from a trusted event and ACK. Actual worker execution is at least once; accepted result is idempotent, fenced, unique by exact execution binding.

### 3.3 Publication safety

TX-C claim/commit; CPU compute outside transaction; TX-D conditional result+SUCCEEDED; TX-E guarded pointer publication. A stale worker cannot claim success by just passing a preflight check. Test-only admission revocation must serialize with TX-E. Cross-database S2 authority cannot be assumed atomic without a separately qualified integration strategy.

### 3.4 No premature analytical denormalization

R0 calculations use the existing pinned F02 input values. Do not build `fin_read`/Data Warehouse projection, Kafka, Redis or 30 operation DSL as part of the first proof. Proper typed read model and model matrix approval are subsequent roadmap stages.

## 4. Remaining critical release gates

1. **R0.B implementation:** minimal typed calculator/preview, 5 PostgreSQL tables, outbox wiring in existing save transaction, one bounded worker. Nothing here has been implemented by this documentation-only review.
2. **R0.C safety:** two-fingerprint reuse, conditional ownership, recovery, concurrent revocation and method version change. Must pass PostgreSQL 16 negatives, not H2-only.
3. **R0.D evidence:** tests A01–A21 on the same final HEAD (Java unit, PostgreSQL 16 Testcontainers, React/Chromium E2E); performance and data-integrity claims only from real measurements.
4. **Financial S2:** approved canonical inputs/metrics, independent qualified FactSet, authority revocation, immutable source guard, authorization/tenant isolation and external trustworthy publication. **NOT INCLUDED in local TEST_ONLY delivery.**
5. **166-form rollout:** full typed/temporal/unit aggregation/metric binding, methodology-specific dependency approval and per-form functional proof. R0 inventory alone is insufficient.

## 5. Reviewer decision

**Allow R0.B1 implementation in the existing branch** only as **LOCAL_TEST_ONLY** with F02 pinned inputs and three explicit pilot outputs. Do not label FIN HTAP v2 as production-ready, do not promote calculation candidates to `fin_fact`, and do not allow the pilot issuer from HTTP input. Keep source output, plan, job, fingerprint and lineage available for exact audit.

This report does not claim that 21 acceptance scenarios, CI builds, database migrations, financial qualification, or real user E2E have already passed.

## 6. Post-review architectural decision: bounded concurrency and cache phases (2026-11-19)

The user explicitly requires **multithreaded execution**. The updated TЗ `FIN-CALC-R0-INCREMENTAL-CALCULATION-ARCHITECTURE-TZ.md` adds CALC-FR-10/11 and acceptance A22–A28:

1. **CALC-R0:** bounded CPU worker pool executing distinct PostgreSQL calculation jobs concurrently; parallel independent DAG branches when measured work justifies it, deterministic topological barriers, fair per-tenant admission, separate interactive preview resources. `Caffeine` L1 for versioned immutable descriptors/graphs (already a Maven dependency). No Redis dependency; node-value reuse from verified PostgreSQL immutable results.
2. **CALC-R1:** `CalculationCache` port, optional Redis L2 adapter disabled by default, test for fallback and corruption/tenant isolation.
3. **CALC-R2:** measurement-driven Redis activation only if p95/p99 and PostgreSQL load improve on representative concurrent workloads without correctness regression.

**Additional S1 risks explicitly gated:** unbounded parallel streams/virtual threads, starvation of preview requests, CPU oversubscription, DB connection starvation, shared mutable DAG context, stale ownership after cancellation, cache-stampede, Redis outages, cache poisoning, and accidentally trusting cached admission/current pointers.

**Evidence:** documentation-only change, no Java worker/cache implementation or load-test result at this point. Before R0.B sign-off, demonstrate real concurrent worker execution with PostgreSQL 16 concurrency tests; before Redis enablement, demonstrate controlled OFF/L1/L1+L2 benchmarks.
