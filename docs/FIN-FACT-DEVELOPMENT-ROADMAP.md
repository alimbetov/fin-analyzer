# FIN FACT — Canonical Development Roadmap (166 Form Ingestion First)

**Branch:** `feat/fin-form-library-standalone`. **Scope:** single Spring Boot modular monolith / PostgreSQL 16.  
**Review baseline:** HEAD `38ea42877abd37df15335d4e4729242959d887be` (2026-11-19).  
**Baseline:** 166 form profiles: **3 SINGLE_RECORD, 151 ROWSET, 12 EVIDENCE**; 794 R6 field candidates, **400** numeric candidate fields; **0 approved mapping contracts proved**.  
**Status (2026-11-19):** INGEST-0/1/2 **GREEN**; **FACT-0 PostgreSQL 16 storage + monthly partitions + 021 source immutability + 018→021 upgrade rehearsal GREEN**; **FACT-1 F02 synthetic test-only TX-Q GREEN**. **Production S2/IAM, fact writer, DAG/Worker and results are HOLD/NOT IMPLEMENTED**. [S1 evidence](audits/FIN-FACT-R0-FACT1-F02-S1-PG16-REVIEW-2026-11-19.md), code SHA `4ae44975941bf7639f180f0b82ad600c793b28e3`, 27 PostgreSQL tests/0 errors.

## 1. Architecture boundaries

`fin_intake immutable form revision → source integrity re-verification → versioned extractor by contentMode → typed UNQUALIFIED candidates → per-formVersion reviewed mapping + exact qualified decision → immutable fin_fact (multi-source header + partitioned observations) + transactional outbox → incremental DAG → PostgreSQL queue → worker → immutable calc_result → guarded CURRENT`.

Never promote a numeric candidate into a financial metric merely by fieldType. Never let React save / READY_FOR_REVIEW self-approve. `fin_fact` is immutable, not updated in place with SQL MERGE. Partitioning is monthly by **business `period_start`**, never ingestion timestamp; no automatic DROP. Exact replay is idempotent, not an update to history. Preserve organization/entity/businessScope/project/dataSpace/basis/coverage/currency and source revision/row IDs.

## 2. Development stages, change budget and gates

| Stage | Goal | Deliverables | Acceptance |
|---|---|---|---|
| **INGEST-0** | Complete, accurate registry of **166 forms** | **UNIT GREEN:** registry + all 166 profiles, 3/151/12, 877 physical bootstrap fields (793 source + 84 evidence), [per-field review worklist](FIN-FACT-166-FORM-FIELD-MAPPING-WORKLIST.tsv) | Actual current CatalogBootstrap seeds cross-checked with R6; 794 R6 candidates = 793 editable source + 1 excluded derived F02.netProfit; all mappings still UNREVIEWED |
| **INGEST-1** | Real **read-only JSON→typed candidate** transformation | **UNIT GREEN on `3401bbda`**: exact scoped SourceReader + generic extractor; **665 per-form actual-field mock tests** (4×166+1), 9 extractor, 5 reader, 1 older mode-route tests | Java21/H2 [GREEN](https://github.com/alimbetov/fin-analyzer/actions/runs/44467526181) (863 total /0 failed /10 skipped), [React/backend + existing PG16 fin_intake GREEN](https://github.com/alimbetov/fin-analyzer/actions/runs/44467526183). Still `UNQUALIFIED_SOURCE_PROJECTION` / `SOURCE_SHA_NOT_REVERIFIED`; no fin_fact writes or real S2 |
| **INGEST-2** | Prove exact immutable source identity | **CODE + PG16 GREEN**: `FormContentFingerprint` shared with writer, `FormFactSourceIntegrityVerifier`, independent `REPEATABLE_READ` source transaction, exact content+order SHA, attachment file bytes verification, strict JSON duplicate rejection | **166/166 physically persisted forms reverified in PG16**, 3 mode-specific negative tests, 4 compatibility unit tests; [final PostgreSQL CI](https://github.com/alimbetov/fin-analyzer/actions/runs/44470613145) GREEN on `59dbb02`; output `SOURCE_SHA_REVERIFIED` but remains `UNQUALIFIED_SOURCE_PROJECTION` |
| **FACT-0** | Physical source→fact storage (PG16) | **CODE + PG16 GREEN:** 019 (5 calc tables), 020 (3 fact tables), **021 scope FK + immutable source/parent guards**, monthly partition manager and upgrade rehearsal | **9/9** storage/SQL invariant tests + **1/1** real 018→021 upgrade test GREEN; privilege separation, WAL restore and partition performance remain HOLD |
| **FACT-1** | F02 trusted source→FactSet TX-Q pilot | **TEST-ONLY PostgreSQL pilot GREEN:** F02 reverified INGEST-2, exactly 3 input MONEY facts, synthetic mapping, transactional TX-Q/epoch/outbox; qualifier code exists exclusively under `src/test` | **4/4** PG tests replay, new qualified revision/history, forced rollback and local revocation. **Full runtime qualified writer, independent S2, live authority and DAG/worker STILL HOLD** |
| **INGEST-3** | Generic ROWSET and EVIDENCE persistence | Full per-formVersion mapping registry, reviewed dimensional grain/units/missing/sign/period; source membership for multi-form datasets, evidence-only references | Each of 151 ROWSET and 12 EVIDENCE separately proven or BLOCKED; no false metric emission; duplicate source conflict explicit |
| **INGEST-4** | Complete 166-form qualification matrix | Per-form+version approved/blocked field mapping, source contract SHA, accepted semantics and tests; staged industry review | 166/166 catalog accounted; actual supported/qualified counts and forms disclosed, no automatic approval |
| **CALC-R0** | Trusted incremental calculation | DAG, dual fingerprints, job queue, multithreaded worker, Caffeine descriptors, immutable result/pointer, TX-E guard | All A01–A34, STF-01–16, PFT-01–18 on one commit SHA; real external S2 remains separate gate |
| **CALC-R1** | Efficient node reuse | `CalculationCache`, node-value Caffeine L1, optional Redis L2 OFF | No output changes, cross-tenant isolation, fail-open only for cache availability (never auth) |
| **CALC-R2** | Measure optional Redis benefit | Repeatable OFF/L1/L1+L2 PG workload benchmark | Redis ON only after verified latency/DB improvement, zero correctness regression |

**Implementation freeze:** INGEST-0/1/2 and FACT-0 **PG16 GREEN**; FACT-1 isolated F02 test-only TX-Q **GREEN**. **NEXT:** FACT-0.H runtime vs migration/partition-maintenance role separation, restore rehearsal and pruning benchmark; FACT-1.A independently verifiable qualification binding, fail-closed production FactSetCommitter, tenant IAM/ABAC and revocation linearization; then CALC-R0 dispatcher/DAG/Workers/immutable results. Existing 001–018 untouched; no public approve endpoint.

## 2.1 INGEST-1 implementation delivery and boundaries (2026-11-19)

- **Java:** `form-library/src/main/java/kz/fin/formlib/calc/ingest/`: `FormFactIngestionRegistry` (166 exact source modes, manifest SHA), `FormFactSourceReader` (DB joins by **exact** revision UUID + organizationId + projectId, rejects foreign scope, strict JSON **string** leaves), `FormFactCandidateExtractor` (3 strategies), `FormFactCandidateService` (read-only transactional boundary).
- **Tests:** `FormFactCandidateExtractorTest` (nine positive/negative scenarios), `FormFactSourceReaderTest` (exact historical read, foreign organization/project, invalid JSON, unqualified façade), `FormFact166ModeRoutingTest` (all **166 synthetic mode routing** cases). These prove structural candidate extraction, **NOT** per-form qualified mappings or DB fin_fact.
- **Field-level worklist:** [FIN-FACT-166-FORM-FIELD-MAPPING-WORKLIST.tsv](FIN-FACT-166-FORM-FIELD-MAPPING-WORKLIST.tsv) has **806 worklist rows:** 793 R6 candidate source fields + 1 `F02.netProfit` derived exclusion + 12 evidence-mode placeholders. Of 400 R6 numeric entries, **399** are provisional source numeric candidates, one is a derived candidate. Runtime still has additional evidence metadata fields needing versioned contract mapping; the placeholders are NOT full evidence field contracts.
- **Historical INGEST-1 limitation (resolved in INGEST-2):** candidate extraction initially trusted the stored SHA and returned `SOURCE_SHA_NOT_REVERIFIED`. The new `FormFactCandidateService.extractExact` now performs an independent source rehash and yields `SOURCE_SHA_REVERIFIED` **without changing financial qualification**. `fin_fact` writes remain unavailable.
- **Delivery policy:** no new external REST endpoint; candidate attributes may include confidential source metadata and must not be logged or returned to unauthorized callers. Do not cache tenant values in public catalog Caffeine.
- **CI evidence (code SHA `3401bbda096c0f34ddea66f7145878cfdef5752e`):** [Java 21/H2](https://github.com/alimbetov/fin-analyzer/actions/runs/44467526181) and [React/backend/PG16 existing intake](https://github.com/alimbetov/fin-analyzer/actions/runs/44467526183) completed SUCCESS. Java logs show **FormFact166CatalogMocksTest 665/665 PASS**, full Maven suite **863 tests, 0 failures, 0 errors, 10 skipped**. [Formal technical S1 audit](audits/FIN-FACT-INGEST-0-1-166-MOCK-REVIEW-2026-11-19.md). First iteration of field-mock test had 81 incorrect assertions based on UUID ordering; fixed test to compare values by rowId and reran exact SHA GREEN. No new fin_fact PostgreSQL or financial S2 results are implied.
- **Suggested focused run:** `mvn -f form-library/pom.xml -Dtest=FormFactCandidateExtractorTest,FormFactSourceReaderTest,FormFact166ModeRoutingTest test`. CI workflow executes on branch push; capture exact head SHA before claiming GREEN.

## 2.2 New evidence — per-form mock test execution

- `form-library/src/test/java/kz/fin/formlib/calc/ingest/FormFact166CatalogMocksTest.java` — JUnit `@TestFactory`: **four named cases per form**, 166 forms, plus 877/794 seed contract assertion (**665 total**). Factual field code/types reconstructed from `CatalogBootstrap`'s exact source catalog inputs, not fabricated `fixtureAmount` for all profiles.
- The prior `FormFact166ModeRoutingTest` only checks mode routing with one artificial field; the new suite is the stronger input-shape proof.
- **Limits:** per-form financial metric semantics and source canonical SHA validation are still unapproved; mocks are deterministic synthetic values, not real bank/user reports. No `fact_observation` partitions or source-to-fact publication.

## 2.3 INGEST-2 implementation and S1 source-integrity gate (2026-11-19)

- **One canonical encoder:** `kz.fin.formlib.service.FormContentFingerprint` shared by `FormDraftService.saveCommand` and `FormFactSourceIntegrityVerifier`, byte-compatible with original `FIN-LIB-CONTENT-V1|` and `FIN-LIB-ROW-ORDER-V1|` prefixes. Hash includes source full tenant scope, pinned form `versionLabel`, period, sorted fields, rowId-sorted ROWSET and optional nested attachment identity; order SHA separately includes row positions.
- **Exact source:** `FormFactSourceReader.readExact` reads the pinned `revisionId`, not current latest. `FormFactCandidateService` uses an **independent `REPEATABLE_READ` transaction** (`REQUIRES_NEW`), independently rehashes persisted values/rows and, for EVIDENCE, verifies file name/mediaType/SHA plus physical attachment bytes. Corrupted hash/row order/evidence, duplicate JSON keys or trailing JSON root fail closed.
- **Acceptance:** [Java 21/H2](https://github.com/alimbetov/fin-analyzer/actions/runs/44470613053) **873 tests, 0 failures, 0 errors, 14 skipped**; [PostgreSQL 16/Backend](https://github.com/alimbetov/fin-analyzer/actions/runs/44470613145) **13 PostgreSQL tests PASS, 0 skipped**, including `Ingest2PostgresqlIntegrityTest` **4/4** with real database saves and source SHA reconstruction for **all 166 forms** (3 SINGLE_RECORD / 151 ROWSET / 12 EVIDENCE); [Chromium](https://github.com/alimbetov/fin-analyzer/actions/runs/44470613318) **SUCCESS**, all on **code SHA `59dbb02fd03140c912747b01f94e7452eb38ee64`**.
- **S1 report:** [INGEST-2 source SHA audit](audits/FIN-FACT-INGEST-2-S1-SOURCE-REHASH-REVIEW-2026-11-19.md). 
- **Boundaries:** all persisted test inputs are synthetic but use real current form field schemas; not actual customer data or financial S2. No new Liquibase migrations or `fin_fact` tables in this stage. `SOURCE_SHA_REVERIFIED` is orthogonal to `UNQUALIFIED_SOURCE_PROJECTION`; field-to-metric mapping, authorization principal, signed qualification, immutable factset/outbox remain future stages.

## 2.4 FACT-0/FACT-1 implementation snapshot (2026-11-19)

- **Actual additive migrations:** `form-library/src/main/resources/db/changelog/019-fin-calc-r0-postgresql.sql`, `020-fin-fact-r0-pilot-postgresql.sql`, and `021-fin-fact-scope-source-immutability-postgresql.sql`, included at the **end** of `db.changelog-master.yaml`. Five calc structures + three fact parent/control structures, scoped calc-plan FK, immutable `fin_intake` revision/row/evidence triggers and named-table TRUNCATE protection. Historical 001–018 checksums unchanged. PostgreSQL-only migrations leave H2 regression bootable.
- **Immutable and partitioned storage:** `fact_set` and `factset_control` unpartitioned; `fact_observation PARTITION BY RANGE(period_start)`; composite key and unique, exact revision + formVersion FK, cross-tenant active pointer FK; UPDATE/DELETE rejection triggers for accepted immutable rows. No default partition or automatic deletion.
- **Maintenance:** `FactMonthlyPartitionService` in `calc/partition` pre-creates month partitions in short Spring transactions with advisory locking and bound verification, 60-month maximum per invocation, idempotence, bounded retry and dry-run. Does not run in a financial save transaction.
- **FACT-1 local pilot:** `src/test/java/kz/fin/formlib/calc/fact/Fact1F02TestOnlyQualificationTest.java` performs actual PostgreSQL TX-Q, exact F02 source SHA, 3 typed observations, active epoch, outbox, replay, history, rollback and revoke. **No production qualifier/dispatcher**: synthetic issuer exists only in test Java source; no real user can approve through this fixture.
- **Exact test evidence:** [GitHub Actions](https://github.com/alimbetov/fin-analyzer/actions/runs/44490380111), source SHA `4ae44975941bf7639f180f0b82ad600c793b28e3`: **27 PostgreSQL 16 tests / 0 failures / 0 errors / 0 skipped** (FACT-0 9, upgrade 1, FACT-1 4, INGEST-2 4, HTTP 5, R16B 4); backend/H2, React, PowerShell and inventory jobs SUCCESS. [Detailed S1 review](audits/FIN-FACT-R0-FACT1-F02-S1-PG16-REVIEW-2026-11-19.md).
- **Remaining blockers:** no production S2/IAM/ABAC or independent revocation protocol; source SQL UPDATE/DELETE/TRUNCATE triggers exist but dedicated least-privilege runtime vs owner/DDL roles not yet qualified; no WAL backup/restore or high-history partition-pruning benchmark; no runtime FACT-1 qualified committer or CALC dispatcher/worker/result; no 166 approved field-to-metric mappings.

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
