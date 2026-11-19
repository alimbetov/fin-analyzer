# FIN CALC-R0 — S1 Data Architecture & Source-to-Fact Flow Review

**Date:** 2026-11-19
**Repository:** `alimbetov/fin-analyzer`
**Branch:** `feat/fin-form-library-standalone`
**Scope:** master CALC-R0 ТЗ, R0/R1/R2 temporary workpacks, 166-form/794-field matrices and real source: `FormDraftService`, `FormWorkflowService`, `FieldMetricContractGuard`, `FieldMetricContractBootstrap`, `ReportGridService`, `CatalogBootstrap`, Liquibase 001–018.
**Review type:** independent technical S1 (architecture, data modeling, data engineering, concurrent calculation). NOT financial S2. **No source compilation or live PostgreSQL tests were executed** in this document review.

## Verdict

**Original specification: P0 HOLD for source→fact→qualified calculation end-to-end.**
Reason: an undefined `FACTSET_QUALIFIED` producer and missing physical source→fact mapping could cause the worker to compute directly from unqualified JSON. This is a **material trust-boundary and data-integrity defect**, not just a missing optimization.

**After documentation remediation: DESIGN-CONDITIONALLY ACCEPTED for TEST_ONLY implementation.** R0.B1 pure kernel can begin; R0 end-to-end **must fail** acceptance until explicit typed fact persistence, epoch-safe qualification and PostgreSQL STF-01–STF-16 negative tests are implemented. Real production S2/IAM remains HOLD.

## 1. Evidence from code — what exists and what does not

| Stage | Proven existing code/schema | Missing implementation |
|---|---|---|
| React save | `FormLibraryController POST /api/v1/drafts` → `FormDraftService.save` | calc outbox hook not implemented |
| Source persistence | `fin_intake.lib_form_instance` and `lib_form_revision`; `values_json TEXT`; version and source SHA | DB-enforced immutable UPDATE/DELETE guarantee for all historical source tables not demonstrated |
| Source normalization | `FormDraftService.canonical()` with `BigDecimal`, currency minor units, FORM version and content hash | separate ingestion reader verifying full existing canonical hash and pinned context |
| Mapping | 877 physical fields; 793 source bindings, 84 evidence metadata; `fin_lib.lib_field_binding_contract` | approved typed mapping and exact source metric contract version |
| Qualification | `READY_FOR_REVIEW` UI workflow marker; versioned policy candidates `CANDIDATE_UNQUALIFIED` | independent S2 issuer, persistent validated qualified binding and revocation authority |
| FactSet | **No fin_fact schema/tables** on branch | `fact_set` + `fact_observation` + control epoch (TEST_ONLY pilot) |
| DAG/queue | master and code-level specs only | no calculation module, migrations 019/020 or worker |
| Immutable result | proposed `fin_calc.calc_result` only | DB result immutability, CAS publication, read-time active binding check |
| Other source store | `fin_intake.lib_report_revision_cell` in `ReportGridService` | no common source conflict policy or report-grid adapter |

**Important:** source form's `F02.netProfit` is a historical unbound `FORM_DERIVED_CANDIDATE`. It is not a fourth editable/qualified input. R0 F02 exactly consumes `netRevenue`, `cogs` and `operatingExpenses`.

## 2. Findings, severity and disposition

| Severity | ID | Defect / omitted invariant | Documentation resolution / remaining gate |
|---|---|---|---|
| **P0** | DF-01 | No defined flow from revision JSON into fact rows | **SPEC FIXED** in `FIN-CALC-R0-SOURCE-TO-FACT-PIPELINE-TZ.md`; implementation R0.B3F required |
| **P0** | DF-02 | `FACTSET_QUALIFIED` event has no existing trusted producer | **SPEC FIXED**: test-only `TrustedQualificationGateway`, exact candidate digest, TX-Q writes facts+event atomically; production S2 still HOLD |
| **P0** | DF-03 | No physical immutable typed observation or FactSet SHA | **SPEC FIXED**: +3 `fin_fact` tables in additive 020; exact 3 F02 numeric observation rows; PG integrity + SHA proof required |
| **P0** | DF-04 | Worker could read mutable/latest JSON instead of pinned qualified values | **SPEC FIXED**: worker uses `QualifiedFactReader(factsetId,sha,binding)`; forbidden raw fallback; implementation still absent |
| **P0** | DF-05 | No approved field-to-metric/unit/grain/time policy; `FieldMetricContractGuard` may be mistaken for S2 | **SPEC FIXED** as explicit test-only F02 mapping + policy, production fail-closed; financial qualification remains HOLD |
| **P0** | DF-06 | Source canonical SHA is not hash of bare values_json; replay could be incorrectly verified | **SPEC FIXED**: verify FIN-LIB-CONTENT-V1 with scope/version/exact rows/evidence, plus negative tests |
| **P0** | DF-07 | Revocation/supersession could race with plan creation, CURRENT publication and current reads | **SPEC FIXED** for local TEST_ONLY DB guard row/epoch; external production authority unresolved (HOLD) |
| **P0** | DF-08 | New draft revision incorrectly treated as reason to auto-reapprove/recalculate | **SPEC FIXED**: source draft events staging only; only new qualified binding triggers DAG |
| **P0** | DF-09 | Historical source revisions are not proven DB append-only | **GATE OPEN**: DB privilege/trigger and PG negative tests before real qualified release; JAVA append-only is insufficient |
| **P1** | DF-10 | Existing 166 form catalog spans SINGLE_RECORD, ROWSET, EVIDENCE; F02 mapping cannot generalize safely | **SPEC LIMIT**: F02 only. Separate adapters for 151 ROWSET + 12 EVIDENCE, financial review and grain required |
| **P1** | DF-11 | Separate ReportGrid input path may double-count facts or choose wrong source | **GATE OPEN**: source precedence/identity and ReportGrid adapter contract before integration |
| **P1** | DF-12 | Missing business grain/period dependency policies for later windows, YTD, AS_OF, currency | **SPEC FIXED**: metadata & blocked policy until reviewed, rolling window invalidation must be declared |
| **P1** | DF-13 | Pre-outbox existing revisions would never appear in new queue | **SPEC FIXED AS HOLD**: idempotent read-only discovery/backfill, no automatic qualification |
| **P1** | DF-14 | Excessive claims about Caffeine/Redis making extraction faster | **SPEC CLARIFIED**: cache only pure node outputs; source→fact verification/authority never skipped |
| **P1** | DF-15 | Full schema migration / test-only fixture / H2 and PG parity not proven | **GATE OPEN**: PostgreSQL 16 001–020 clean/upgrade, lossless exact source SHA and PostgreSQL negatives |
| **P1** | DF-16 | R0 5-table claim contradicted required fact-level ingestion | **SPEC FIXED**: 5 `fin_calc` + 3 `fin_fact` = 8 new tables; no extra DWH service |

**Counts:** 9 P0 and 7 P1; documentation fixes are **not** code fixes.

## 3. End-to-end scenario review (F02, 2026-10, TEST_ONLY)

| Step | Example content / referent | Must be true before next |
|---|---|---|
| 1. User saves | Three typed F02 money strings in `values_json`, immutable revision N | transaction COMMIT, exact content/source SHA, idempotency receipt |
| 2. Draft outbox | `DRAFT_REVISION_SAVED(revisionId,contentSha)` | same TX as revision; **no qualified calc job** |
| 3. Candidate extraction | source row values + scope + version + SHA | period M, compatible currency, exact 3 fields, complete and reproducible |
| 4. Metric mapping | versioned F02 field mapping + allowed test-only policy | no unreviewed S2 assertion, no source conflict |
| 5. Qualification | trusted local fixture confirms exact candidate digest | no user-provided `approved`, exact issuer/epoch |
| 6. Fact commit | `fin_fact.fact_set` + 3 typed `fact_observation` + epoch + trusted outbox | one TX; count=3, unique sources, canonical factset SHA |
| 7. Plan | qualified event → read persisted facts and verify SHA → DAG plan | event dedup, active epoch and exact binding |
| 8. Worker | load only factset ID and 3 observations; 3-node F02 DAG | no latest JSON fallback, no missing or mixed scope |
| 9. Recalculation | change operatingExpenses in **new independently qualified** revision | new executionFingerprint, only operatingResult dirty, GP/margin reused |
| 10. Result | complete new result snapshot, immutable new lineage | outputValuesSha same across cache modes if same inputs; resultSha captures provenance |
| 11. Current | TX-E guard active factset/epoch and CAS pointer | old FactSet cannot be re-promoted by late job |
| 12. Read / revoke | GET current checks active authority; historical lookup explicit | revoked result never returned as current |

**Two critical distinctions:** old approved FactSet can remain current after a new *draft* is saved; after a new *qualification* becomes active, current becomes pending/stale until newly bound result is available. Old immutable results remain in history.

## 4. Required PostgreSQL 16 acceptance and decision rule

The baseline CALC tests A01–A28 are insufficient for this new seam. Add **STF-01–STF-16** from source-to-fact contract and acceptance **A29–A31** to the master specification.

Minimum proofs: 3 exact fact rows per F02 FactSet, no row on rollback, no qualified job from draft-only event, no S2 from READY_FOR_REVIEW, canonical source+fact SHA reconstruction, idempotent qualifier and outbox replay, no cross-tenant/currency/period merging, no unqualified F02.netProfit, correct source after a new draft, correct dirty-node count after qualified update, revoke between TX-Q/TX-E/GET CURRENT, and failure to reuse stale owner results.

**Release gates:**

1. **R0.B1 pure calculation kernel:** may proceed on Java unit tests without fact storage.
2. **R0.B3F Source-to-Fact:** cannot be marked GREEN without PostgreSQL 16 migrations 001–020 and STF-01–STF-16.
3. **R0.B4 worker:** MUST read persisted immutable `fin_fact.fact_observation` for qualified execution.
4. **R0.D:** full browser+PG+concurrency on identical git SHA. Functional proof is not financial S2 approval.
5. **Production:** HOLD until independent real S2, verifiable issuer/revocation, trusted principal/project ABAC, source immutability and distributed publication safety.

**The main operational risk is not Redis speed or DAG mathematics; it is using data at the wrong trust level or without an exact typed source mapping.** Only after the boundary is verified does caching/multithreaded execution make results both fast and correct.
