# FIN HTAP v2 — Repeated S1 Data-Flow Audit: 166 Forms / Partitioned fin_fact

**Date:** 2026-11-19  
**Branch:** `feat/fin-form-library-standalone`; original review HEAD `447dd7bff6ef5c6da76ad333065a48cdf7bcd584`.  
**Scope:** revision save → JSON/ROWSET extraction → canonicalization → factset membership → qualified admission → PostgreSQL outbox+queue → incremental calculation → guarded publication. Uploaded `TablePartitionService` source reviewed in detail.  
**Review class:** S1 TECHNICAL / DATA ARCHITECTURE / DATA ENGINEERING, **not independent financial S2**. Documentation/static inspection only; no PostgreSQL server or CI executed.

## 1. Conclusion

**P0 HOLD remains on implementation.** Current branch has source revisions in `fin_intake`, candidate contract catalogs, but **no implemented `fin_fact`, typed 166-form ingestion, worker or approved binding**. Earlier `Source-to-Fact` workpack designed F02 only and assumed one sourceFormCode/formVersion per FactSet; it does **not** support full 166-form/multi-form calculation or PostgreSQL partitioning.

**Architecture redesign documented:** monthly partitioned **numeric observation table only**; unpartitioned global FactSet header/control; generic source-mode strategy registry; append-only qualified snapshot revisions. The new draft is `docs/temporary/FIN-FACT-R0-PARTITIONED-166-FORM-INGESTION-TZ.md` with PG16 SQL at `docs/temporary/sql/FIN-FACT-PARTITIONED-PG16-DDL-DRAFT.sql`. No runtime migration has been applied.

## 2. Exact source inventory and quantified readiness

- Catalog 166 forms: 3 SINGLE_RECORD (F01/F02/F03), 151 ROWSET, 12 EVIDENCE.
- R6 candidate field descriptors: 794 across 154 forms; 212 MONEY +144 DECIMAL +44 INTEGER = **400 numeric CANDIDATES**, not qualified facts; other 394 candidates are dimensional/contextual/documentary.
- `FieldMetricContractBootstrap` confirms 877 physical source/evidence fields on clean bootstrap: **793 SOURCE_FIELD, 84 EVIDENCE_METADATA**. R6 has one extra `F02.netProfit` derived candidate, which cannot be read as an editable numeric fact. This is a **different population and must be reconciled**, not equated with 794 runtime fields.
- `FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv` has field_metric_binding_state=NOT_VERIFIED for all 794 fields. `FIN-CALC-R0-COVERAGE-166-BASELINE.tsv` has formula_contract_state=NOT_AUTHORED for all 166 forms.
- Code still has `FormDraftService` for immutable logical revisions and separate `ReportGridService` for cell revisions. `FormWorkflowService.READY_FOR_REVIEW` is not S2.
- New `docs/FIN-FACT-R0-166-FORM-INGESTION-COVERAGE.tsv` classifies **all 166 forms** with adapter, candidate counts, review state and `calc_runtime_implemented=NO`.
- **Financially qualified extraction from all 166 forms: 0 forms proven**. **Pilot F02: code-level specification only**, not an executed fact pipeline.

## 3. S1 findings — P0

| ID | Problem | Exact resolution / release requirement |
|---|---|---|
| DF2-P0-01 | PostgreSQL partitioned table cannot have old `id UUID PRIMARY KEY` | PK `(period_start, observation_id)`; every UNIQUE includes partition key; PG16 negative DDL test |
| DF2-P0-02 | Old FactSet header assumes one formVersion/formCode | Multisource `source_revisions_jsonb` and per-observation source lineage; header has dataset code+target scope |
| DF2-P0-03 | "166 forms" confused catalog discovery with implementation | 166-form adapter matrix with explicit NOT_QUALIFIED and NOT_IMPLEMENTED; code gates per source contract |
| DF2-P0-04 | R0 only extracts F02 SINGLE_RECORD; 151 ROWSET lack row grain | Typed Rowset extractor contract preserving row UUID and dimensions; separate implementation and PG E2E |
| DF2-P0-05 | 12 EVIDENCE forms incorrectly treated as potential numeric facts | EVIDENCE extractor emits **zero numeric observations**, document/evidence refs and integrity only |
| DF2-P0-06 | No safe partition coverage / writer could fail on unseen period | Month partition preflight, no DEFAULT silent route, controlled historic backfill and health |
| DF2-P0-07 | `SELECT ... WHERE factset_id=?` alone does not prune month partitions | QualifiedFactReader bounded period predicate, observed row count/hash; EXPLAIN test |
| DF2-P0-08 | SQL MERGE/UPDATE could mutate financial fact history | Exact replay dedup+INSERT; new qualified snapshot appends new factset+observations, old rows immutable |
| DF2-P0-09 | Wrong identity scope could publish another organization's FactSet | Active control composite FK/guard against same org and full stable scope; PFT tests |
| DF2-P0-10 | Candidate mapping/rules not qualified; could invent metrics | Per-form-version reviewed field role (MEASURE/DIMENSION/etc), temporal/grain/sign/units, no auto S2 |
| DF2-P0-11 | Partial/incomplete composite set or duplicate source metric could feed DAG | Snapshot completeness count+canonical source/fact SHA; SOURCE_CONFLICT unless approved precedence |
| DF2-P0-12 | Partition cleanup utility can DROP immutable financial history | **No automatic DROP method in FIN**; retention/archive distinct from maintenance |

## 4. S1 findings — P1

| ID | Risk | Resolution |
|---|---|---|
| DF2-P1-01 | Uploaded utility creates DAILY partitions | Adapt to YearMonth; fewer partitions, business time key |
| DF2-P1-02 | Parent inspection verifies only RANGE, not key column/bounds | Validate `pg_get_partkeydef`, DATE type, `pg_get_expr(relpartbound,oid)` and child index health |
| DF2-P1-03 | Existing same-named child might have wrong bounds | Check bounds before declaring SKIPPED |
| DF2-P1-04 | Manually `commit()` inside JdbcTemplate connection is fragile with Spring pool/Tx | Use short Spring TransactionTemplate/REQUIRES_NEW per partition, no DDL inside TX-Q |
| DF2-P1-05 | Original checkHealth assumes today's partition | Require coverage historical+current+future and no gap where pinned data exists |
| DF2-P1-06 | Header/observation business period semantics can differ | Exact report period start/end and explicit AS_OF vs INTERVAL; no naive SUM of stocks |
| DF2-P1-07 | Old ROWSET row IDs may be regenerated on resave | Stable row UUID or explicit versioned business-key match; otherwise full affected-scope recompute |
| DF2-P1-08 | No plan for old revisions predating Outbox | Discovery/backfill stage, never auto qualification |
| DF2-P1-09 | No realistic value benchmark comparing partitioned vs non-partitioned | EXPLAIN BUFFERS/p95/p99, same dataset, observability of scanning all partitions |
| DF2-P1-10 | No policy for 166×periods source conflict and multi-currency | Dataset membership, business scope, FX only via explicit approved versioned rule |

## 5. Walkthrough of critical cases

### Case A — new F02 draft

1. `FormDraftService.save` creates pinned revision + outbox in one TX-A **once per committed new revision**.
2. Candidate source reader reassembles canonical FIN-LIB-CONTENT-V1 source SHA using form+scope+row order policies; simple SHA(values_json) is incorrect.
3. `SingleRecordFactExtractor` emits exactly three candidate typed MONEY values. Fourth R6 `F02.netProfit` is excluded.
4. Only independent test issuer may qualify exact digest. TX-Q appends header + 3 observations routed to month partition + updates test-only epoch + emits trusted event atomically.
5. Worker consumes only `QualifiedFactReader` observations, verified by source/mapping/FactSet digest, no direct current JSON.

### Case B — change one field / month

Revision N for 2026-10 already TEST_QUALIFIED → factset A in `fact_observation_2026_10`. User saves new F02 draft N+1 and changes only operatingExpenses → no new qualified job. If it later qualifies, append **factset B + three NEW observations to same month partition**, preserving factset A and its source lineage. executionFingerprint changes; node fingerprints of GP/margin remain same and can be reused, operatingResult recomputes. New complete immutable result B publishes through epoch/fence CAS.

**Never SQL MERGE update FactSet A rows or physically overwrite original October.** Partition existence/age does not decide financial CURRENT; control epoch does.

### Case C — 151 ROWSET forms

A row with row_id=X and fields `quantity=7, unit=kg, unitPrice=100` is not necessarily one fact of 700 monetary units: that multiplication requires approved method semantics. Extract each **reviewed numeric measure** as its own typed observation with row X + dimension digest; other fields remain typed dimensions or row references; preserve rowId and exact sourceRev. In multi-row import, verify count, uniqueness, unit and allowed dimensions. If rows reordered without changing content, avoid falsely dirtying financial nodes when row order is not semantic.

### Case D — multiple forms, mixed periods and duplicate metric

A financial model may consume F01 AS_OF and F02 monthly INTERVAL plus C07 ROWSET. FactSet header lists each source and exact version+period, observations retain individual periods. No implicit aggregation/consolidation/FX. If ReportGrid also provides a source revenue metric, **BLOCK SOURCE_CONFLICT**, unless a reviewed source-priority contract explicitly resolves it.

### Case E — history and retention

F02 annual 2022 backfill targets `fact_observation_2022_01` (period_start=2022-01-01), not the partition for import date 2026. If missing, status `FACT_PARTITION_MISSING` and controlled ensure preflight; no unbounded DDL in finance transaction. Once created, backfill may continue but still needs separate qualification. Never DROP historical partition based only on current date.

## 6. Review of user's TablePartitionService utility

**Transfer:** transaction-scoped advisory lock keyed by fully qualified partition identity; bounded retry/backoff, DDL lock/statement timeouts, identifier validation, dry-run, inspection via pg_catalog/pg_inherits, Micrometer, failure accounting and multi-node idempotency.

**Change/block:** day → month; never auto DROP; verify partition column/type/bounds rather than only partstrat='r'; do not base financial period on server timezone; prefer Spring-owned short per-partition transaction rather than manual Connection.commit on pooled connection; fail closed on malformed child bounds; pre-create during maintenance, **not on HTTP form save or TX-Q**.

Also the source utility uses `IF NOT EXISTS`, which alone does not prove the existing relation has correct partition bounds. PostgreSQL parent/child DDL locking under load must be profiled; for high-write load consider prepared child + ATTACH workflow.

## 7. Required acceptance

- Extend existing A01–A31 + STF-01..16 with **PFT-01..18** in partitioned ingestion contract.
- R0 only qualifies F02 local TEST_ONLY 3-field case after real PG16 tests, not all 166.
- R1 ingestion adapters for ROWSET/EVIDENCE/ReportGrid and reviewed contracts form-by-form, with per-form E2E ledger.
- Independent S2, real IAM/ABAC and authority revocation before real APPROVED facts and bank-facing/current results.
- Redis/Caffeine can reuse pure node values but never bypass QualifiedFactReader, source/FactSet SHA verification or authority gate.

## 8. Status after review

- **DESIGN IMPROVED**: source→fact pipeline, multi-form FactSet, monthly partitioning, physical composite PK, no-auto-drop and per-form coverage ledger specified.
- **NOT IMPLEMENTED**: `fin_fact` schema and partition maintenance, reusable field extraction across 166 forms, S2 approval, multi-form qualification, worker, load tests.
- **RELEASE STATUS:** HOLD for R0 E2E until code+PostgreSQL tests; production HOLD separately.
