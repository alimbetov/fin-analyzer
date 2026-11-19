# FIN FACT — Canonical Development Roadmap (166 Form Ingestion First)

**Branch:** `feat/fin-form-library-standalone`. **Scope:** single Spring Boot modular monolith / PostgreSQL 16.  
**Review baseline:** HEAD `38ea42877abd37df15335d4e4729242959d887be` (2026-11-19).  
**Baseline:** 166 form profiles: **3 SINGLE_RECORD, 151 ROWSET, 12 EVIDENCE**; 794 R6 field candidates, **400** numeric candidate fields; **0 approved mapping contracts proved**.  
**Status:** authoritative implementation roadmap. **INGEST-0/INGEST-1 implementation started 2026-11-19** (read-only candidates, no physical fin_fact). No real S2, no qualified fact persistence or CI qualification beyond runs expressly linked below.

## 1. Architecture boundaries

`fin_intake immutable form revision → source integrity re-verification → versioned extractor by contentMode → typed UNQUALIFIED candidates → per-formVersion reviewed mapping + exact qualified decision → immutable fin_fact (multi-source header + partitioned observations) + transactional outbox → incremental DAG → PostgreSQL queue → worker → immutable calc_result → guarded CURRENT`.

Never promote a numeric candidate into a financial metric merely by fieldType. Never let React save / READY_FOR_REVIEW self-approve. `fin_fact` is immutable, not updated in place with SQL MERGE. Partitioning is monthly by **business `period_start`**, never ingestion timestamp; no automatic DROP. Exact replay is idempotent, not an update to history. Preserve organization/entity/businessScope/project/dataSpace/basis/coverage/currency and source revision/row IDs.

## 2. Development stages, change budget and gates

| Stage | Goal | Deliverables | Acceptance |
|---|---|---|---|
| **INGEST-0** | Complete, accurate registry of **166 forms** | **CODE COMMITTED:** `catalog/fin-fact-ingestion-strategy-r0.tsv`, registry, 166-profile matrix and new [per-field review worklist](FIN-FACT-166-FORM-FIELD-MAPPING-WORKLIST.tsv) | Registry 166/166 (3/151/12) checked; synthetic exact-mode routing test added; all mappings still UNREVIEWED |
| **INGEST-1** | Real **read-only JSON→typed candidate** transformation | **CODE COMMITTED:** `calc/ingest/FormFactSourceReader`, `FormFactCandidateExtractor`, `FormFactIngestionRegistry`, `FormFactCandidateService`; exact scoped revision ID, BigDecimal, absence/blank/zero, row UUID, evidence SHA/refs | Java/H2 + backend regression GREEN on `8df2468a`; 166 synthetic routing test added later (check its own SHA CI). Outputs remain `UNQUALIFIED_SOURCE_PROJECTION` / `SOURCE_SHA_NOT_REVERIFIED`; no financial metricCode or fin_fact writes |
| **INGEST-2** | Prove exact immutable source identity | Deterministic `FIN-LIB-CONTENT-V1` canonical rehash shared with FormDraftService; verify row order/evidence; raw canonical DB/source mismatches BLOCK | Test against actual `FormDraftService.save` on PG16 for all 3 modes incl attachment; no fallback to latest revision |
| **FACT-0** | Physical source→fact storage (PG16) | Additive migrations 019+020, `fin_fact.fact_set` and control unpartitioned, `fact_observation PARTITION BY RANGE(period_start)` monthly composite PK; `FactMonthlyPartitionService` | PG PFT-01..08, append-only/privilege checks, catalog bounds, 001–020 clean & upgrade; no DROP |
| **FACT-1** | First qualified end-to-end F02 | Exact test-only reviewed F02 3-input mapping, pinned candidate FactSet, trusted issuer, TX-Q facts+event, worker reads exact FactSet, SHA and lineage | F02 three fact rows, replay, rollback, revoke, out-of-order epoch, single-field delta, clean PG & concurrency GREEN |
| **INGEST-3** | Generic ROWSET and EVIDENCE persistence | Full per-formVersion mapping registry, reviewed dimensional grain/units/missing/sign/period; source membership for multi-form datasets, evidence-only references | Each of 151 ROWSET and 12 EVIDENCE separately proven or BLOCKED; no false metric emission; duplicate source conflict explicit |
| **INGEST-4** | Complete 166-form qualification matrix | Per-form+version approved/blocked field mapping, source contract SHA, accepted semantics and tests; staged industry review | 166/166 catalog accounted; actual supported/qualified counts and forms disclosed, no automatic approval |
| **CALC-R0** | Trusted incremental calculation | DAG, dual fingerprints, job queue, multithreaded worker, Caffeine descriptors, immutable result/pointer, TX-E guard | All A01–A34, STF-01–16, PFT-01–18 on one commit SHA; real external S2 remains separate gate |
| **CALC-R1** | Efficient node reuse | `CalculationCache`, node-value Caffeine L1, optional Redis L2 OFF | No output changes, cross-tenant isolation, fail-open only for cache availability (never auth) |
| **CALC-R2** | Measure optional Redis benefit | Repeatable OFF/L1/L1+L2 PG workload benchmark | Redis ON only after verified latency/DB improvement, zero correctness regression |

**Implementation freeze:** start **INGEST-1 now** without touching 001–018, database tables, public APIs, S2, financial methods or unreviewed mapping statuses. This is a real implementation of **unqualified source projection**, not completion of 166 financial transformations.

## 2.1 INGEST-1 implementation delivery and boundaries (2026-11-19)

- **Java:** `form-library/src/main/java/kz/fin/formlib/calc/ingest/`: `FormFactIngestionRegistry` (166 exact source modes, manifest SHA), `FormFactSourceReader` (DB joins by **exact** revision UUID + organizationId + projectId, rejects foreign scope, strict JSON **string** leaves), `FormFactCandidateExtractor` (3 strategies), `FormFactCandidateService` (read-only transactional boundary).
- **Tests:** `FormFactCandidateExtractorTest` (nine positive/negative scenarios), `FormFactSourceReaderTest` (exact historical read, foreign organization/project, invalid JSON, unqualified façade), `FormFact166ModeRoutingTest` (all **166 synthetic mode routing** cases). These prove structural candidate extraction, **NOT** per-form qualified mappings or DB fin_fact.
- **Field-level worklist:** [FIN-FACT-166-FORM-FIELD-MAPPING-WORKLIST.tsv](FIN-FACT-166-FORM-FIELD-MAPPING-WORKLIST.tsv) has **806 worklist rows:** 793 R6 candidate source fields + 1 `F02.netProfit` derived exclusion + 12 evidence-mode placeholders. Of 400 R6 numeric entries, **399** are provisional source numeric candidates, one is a derived candidate. Runtime still has additional evidence metadata fields needing versioned contract mapping; the placeholders are NOT full evidence field contracts.
- **Known limitation:** source canonical SHA is a **stored claim**, not recalculated from the full `FIN-LIB-CONTENT-V1` source identity. Ingestion stage deliberately returns `SOURCE_SHA_NOT_REVERIFIED`, cannot write `fin_fact` and cannot qualify. **INGEST-2 MUST** share canonical hash implementation with `FormDraftService`, including original `version_label`, rows and evidence fileName/mediaType/SHA.
- **Delivery policy:** no new external REST endpoint; candidate attributes may include confidential source metadata and must not be logged or returned to unauthorized callers. Do not cache tenant values in public catalog Caffeine.
- **CI evidence:** [Java 21/H2 run on `8df2468a`](https://github.com/alimbetov/fin-analyzer/actions/runs/44465949899) and [backend regression on `8df2468a`](https://github.com/alimbetov/fin-analyzer/actions/runs/44465949933) completed GREEN. Earlier Spring proxy failure was corrected by making `@Transactional` services non-final. Tests added **after** that commit require their own GREEN before INGEST-1 acceptance.
- **Suggested focused run:** `mvn -f form-library/pom.xml -Dtest=FormFactCandidateExtractorTest,FormFactSourceReaderTest,FormFact166ModeRoutingTest test`. CI workflow executes on branch push; capture exact head SHA before claiming GREEN.

## 3. Mandatory INPUT/OUTPUT contract for INGEST-1

**Input:** exact `revisionId` fetched from `fin_intake` (not latest), full tenant/project scope; source version, field definitions with `input_mode`, JSON values, ordered row items and EVIDENCE refs. `FormDraftService` uses FIN-LIB-CONTENT-V1 hash of context+normalized values+row identities/attachments, so **do not compare SHA(values_json) to canonical_content_sha256**. A later INGEST-2 does full rehash.

**Output:** `UNQUALIFIED_SOURCE_PROJECTION` with sorted typed candidates and source identity. It can identify **numeric candidate** MONEY/DECIMAL/INTEGER vs nonnumeric dimensional metadata, but **must not assign a financial metricCode, financial S2 approval, aggregationPolicy, formula or derived value**. Blank vs absent vs zero stay distinct. ROWSET preserves row UUID. EVIDENCE yields **zero** numeric candidates, retains document IDs+SHA/metadata refs only. Field `DERIVED/READ_ONLY` is not a source input.

**Failure policy:** reject wrong form mode/registry, cross-tenant reader access, unknown/derived field in JSON, malformed decimal/overflow, duplicate row IDs/positions, invalid SHA, mixed content mode. All failures read-only; zero INSERT into `fin_fact`.

## 4. One-form-at-a-time financial mapping proof

Per form and version: identify exact source fields from `fin_lib.lib_form_field`; describe `MEASURE/DIMENSION/EVENT_DATE/EVIDENCE_REF/SKIP`, numeric type, unit/currency, sign, scale, missing policy, row/business grain, period semantics, source precedence, optional formula/consumer model, independent review state and SHA. Record `PENDING_REVIEW` explicitly until proven. The existing 166-profile and 794-field matrices are **candidates**. Production real S2 + IAM/ABAC and external revocation is not delivered by test fixtures.

## 5. Required delivery report for each slice

```text
Before SHA / After SHA
Changed source files / migration IDs / impact on 001–018
Scope: source projection vs qualified fact vs current result
Test commands and exact PASS/FAIL/NOT_RUN
PG16 Testcontainers + browser E2E (if relevant) and evidence URLs
Negative test coverage, unsupported forms and missing contracts
NEXT authorized slice and remaining HOLD
```

## 6. Supporting contracts

- [FIN FACT partitioned 166 forms](temporary/FIN-FACT-R0-PARTITIONED-166-FORM-INGESTION-TZ.md)
- [166-form ingestion coverage](FIN-FACT-R0-166-FORM-INGESTION-COVERAGE.tsv)
- [CALC-R0 Source→Fact](temporary/FIN-CALC-R0-SOURCE-TO-FACT-PIPELINE-TZ.md)
- [CALC R0/R1/R2 index](temporary/FIN-CALC-IMPLEMENTATION-WORKPACK-INDEX.md)
- [PG16 physical DDL proposal](temporary/sql/FIN-FACT-PARTITIONED-PG16-DDL-DRAFT.sql)
- [S1B technical review](audits/FIN-CALC-R0-S1B-PARTITIONED-166-INGESTION-REVIEW-2026-11-19.md)
