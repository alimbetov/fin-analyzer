# FIN HTAP v2 — Partitioned fin_fact + JSON→Fact Contract for 166 Forms

**Date:** 2026-11-19 · **Status:** FACT-0 PHYSICAL STORAGE + MONTHLY MAINTENANCE + 021 SCOPE/SOURCE HARDENING + PG16 018→021 UPGRADE **GREEN (27 tests)**; FACT-1 F02 TEST-ONLY TX-Q GREEN; production S2/166 approved mappings HOLD. **Branch:** `feat/fin-form-library-standalone`. [S1 evidence](../audits/FIN-FACT-R0-FACT1-F02-S1-PG16-REVIEW-2026-11-19.md).
**Sources:** exact source catalog in this branch; `FormDraftService`, `ReportGridService`, `PeriodResolver`, `FieldMetricContractBootstrap`; attached user `TablePartitionService` (daily partition maintenance).
**Scope:** redesign of `docs/temporary/FIN-CALC-R0-SOURCE-TO-FACT-PIPELINE-TZ.md`. Supersedes **physical 020 DDL** there when statements conflict. Does not supersede its TEST_ONLY financial qualification restrictions.

## 1. Design decision

1. `fin_fact.fact_set` = **unpartitioned**, globally UUID-addressable immutable **multisource** qualified snapshot header. A method may consume F01, F02, C01, P07, multiple periods and rowsets; never force one formCode/formVersion per FactSet.
2. `fin_fact.fact_observation` = one **partitioned** immutable typed **numeric fact** per source row/field/period/dimensions, `PARTITION BY RANGE (period_start)` **monthly**. The observation's period is **business/reporting time**, never `created_at`, and precision rules are versioned.
3. `fin_fact.factset_control` = **unpartitioned** mutable authority epoch/active binding per stable scope and dataset type (TEST_ONLY R0), with full scope equality guard. Does not replace production external S2 authority.
4. F02 R0 remains an exact **3-PRESENT-numeric** single-record pilot; for the other 163 forms extraction is designed but **not automatically financially qualified**. A few forms may generate **zero** numeric facts: evidence/docs are links/metadata, not money metrics.
5. `fin_calc` PostgreSQL Job Queue, DAG, node/execution fingerprint and Caffeine/optional Redis architecture is unchanged; **workers may read only pinned persisted qualified facts**, never a raw JSON fallback.

**Why monthly:** 166 form identities do not imply 166 physical tables. Monthly business periods are a useful common pruning key across YEAR, MONTH, WEEK and day reports. Daily partitions cause unnecessary catalogs/indexes and DDL. For YEAR/Q/H periods, the fact is stored in partition containing its `period_start`, but the **original interval and period_semantics remain mandatory**. A single qualified FactSet can reference observations in multiple monthly partitions. Partition pruning works only when SQL includes the actual partition key/range; lookup by FactSet ID alone may scan all partitions.

## 2. 166-form inventory, evidence, and maturity

From `FIN-CALC-R0-COVERAGE-166-BASELINE.tsv` and `FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv`:
- 166 forms: 3 SINGLE_RECORD, **151 ROWSET**, **12 EVIDENCE**. Total 794 R6 candidate descriptions covering 154 forms: **212 MONEY, 144 DECIMAL, 44 INTEGER = 400 numeric candidates**; 394 other candidate fields. These are **not verified/approved metrics**.
- Runtime baseline: 877 physical fields = 793 bound source fields + 84 evidence metadata; 794 R6 descriptions include `F02.netProfit`, an unbound FORM_DERIVED_CANDIDATE, not editable source. Confirm actual DB fields and formVersion UUIDs before execution.
- 42 model catalog entries are DESIGN_ONLY/CALC_PILOT declarations, not proof of executable calculation logic. Form-matrix all 166 `formula_contract_state=NOT_AUTHORED` and field-level `field_metric_binding_state=NOT_VERIFIED`.
- Never say "166 forms transformed" just because three source adapters compile; strict qualification and field-level reviewed mappings require proof for each version and form.

### Adapter matrix

| Source content mode | Adapter | Output and grain | Status/gate |
|---|---|---|---|
| SINGLE_RECORD (F01/F02/F03) | `SingleRecordFactExtractor` | Numeric source fields with `source_row_key=SINGLE`; typed context | R0 only F02 test-only; F01/F03 blocked pending semantics |
| ROWSET (151) | `RowsetFactExtractor` | One observation per numeric field **per source row UUID**; other row fields become versioned dimensions/attributes (not individual numeric observations) | R1-INGEST adapters, then per-form/grain/metric review; never aggregate rows prematurely |
| EVIDENCE (12) | `EvidenceReferenceExtractor` | 0 numeric facts; verified attachment refs, document SHA, status/completeness links in source membership/evidence contract | No artificial numeric facts; cannot grant financial S2 |
| Report Grid (parallel path) | `ReportGridFactExtractor` | Explicit `lib_report_revision_cell` source identity + period+metric; same fact contract | Separate pilot after source conflict precedence rules |

### Universal mapping descriptor

```java
record FormFactMappingDescriptor(
  UUID formVersionId,
  String sourceSchemaSha256,
  String sourceMappingSha256,
  String factMappingVersion,
  String mappingArtifactSha256,
  String contentMode, String sourceGrain,
  String periodSemantics,
  List<FieldRule> fields,
  String financialReviewState
) {}

record FieldRule(
  String fieldCode, String fieldRole,      // MEASURE, DIMENSION, EVENT_DATE, EVIDENCE_REF, SKIP
  String metricCode, String metricContractVersion,
  String typedValueKind, String unitCode,  // MONEY/DECIMAL/INTEGER, no implicit conversion
  String aggregationPolicy, String missingPolicy,
  String scalePolicy, String signPolicy,
  String dimensionCode, String transformationId
) {}
```

The assembler **MUST** reject descriptor `UNREVIEWED` for production; R0 uses an exact signed **TEST_ONLY F02** mapping built from the three existing source fields. Do not dynamically use non-allowlisted script/SQL transformations. Different versions of same form must pin their own descriptors. F01 BALANCE/STOCK and F02 FLOW must not share a SUM-by-period default.

## 3. Physical PostgreSQL 16 schema — key and partition constraints

### 3.1 fact_set (unpartitioned)

Proposed columns: `id UUID PK`; complete organization/entity/businessScope/project/dataSpace/coverage/basis/currency/datasetCode/targetPeriod scope; `source_revisions_jsonb` ordered references to **multiple** form versions and exact revisions (including formCode, contentMode, source SHA, mapping SHA and optional evidence SHA); `source_set_sha256`, `mapping_bundle_sha256`, `factset_sha256`, `observation_count`, `qualification_binding_sha256`, `qualification_epoch`, `qualification_mode` and `created_at`. Historical full snapshot header immutable, even when active pointer changes. `factsetSha256` canonical over **sorted typed observation records + source membership + full scope and mapping bundle**, excluding randomly generated IDs/timestamps.

Header must not have a mandatory single `source_form_code` / `form_version_id` as its identity. Those belong in each immutable source revision reference and observation.

**Next scaling gate for full 166 forms:** JSONB membership is sufficient to prototype the single-source F02 FactSet, but **not sufficient by itself to prove referential integrity of every multi-source/evidence-only member**. Before calling multi-form production READY, evaluate an immutable relational `fin_fact.fact_set_source_member` table (FactSet ID, source kind, typed source revision FK/identity, formVersion, source content SHA, period range, role and membership sequence) with source-oriented indexes; compare with JSONB membership for query cost and repeatable digest. If needed, introduce as additive R1-INGEST migration, not hidden within 020. Do not claim 166-form lineage complete until this is decided and PG-negative tests pass.

### 3.2 fact_observation (partitioned by period_start)

**PostgreSQL rule:** a PRIMARY KEY or UNIQUE constraint on a partitioned table must include the partition key. Therefore `PRIMARY KEY (id)` **is invalid**. Proposed:
- `PRIMARY KEY (period_start, observation_id)`
- `UNIQUE (period_start, factset_id, observation_key_sha256)`, with non-null business key and source row identity
- `factset_id UUID NOT NULL REFERENCES fin_fact.fact_set(id)`; do not introduce a foreign key to observation UUID alone.
- `period_start DATE NOT NULL` and `period_end_exclusive DATE NOT NULL` plus `period_type` / `period_semantics`. For AS_OF, assign the true reference date and a policy-defined interval (e.g. [asOf,asOf+1d)), not a fabricated financial monthly flow.
- `source_form_code`, `source_revision_id`, `source_row_key`, `source_form_version_id`, `source_field_code`, `metric_code` and `metric_contract_version` (not just metric ID).
- `dimension_digest` + safe `dimensions_jsonb` for allowlisted non-PII dimension codes; do not persist raw PII or entire ROWSET in facts.
- `value_state` (R0 only PRESENT) and `numeric_value NUMERIC(38,12) NOT NULL`, `unit_code`, optional `currency_code` (no implicit FX).
- `canonical_value_sha256` and `observation_key_sha256` plus bound `mapping_sha256`.
- `CHECK(period_start < period_end_exclusive)`, positive precision/range and nonblank typed codes. Avoid SQL NULL inside composite business unique key; stable sourceRowKey=`SINGLE` for SINGLE_RECORD.

**Important for cross-period FactSets:** querying by `factset_id` alone will not prune partitions. `QualifiedFactReader` must know allowed period ranges from pinned membership/plan, filter `period_start >= ... AND period_start < ...`, read sorted observations, verify expected count + canonical SHA and exact scope; never assume header target period is always the only source period.

### 3.3 factset_control (unpartitioned)

Full stable scope digest includes organization/economicEntity/businessScope/project/dataSpace/coverage/basis/currency, **datasetCode and target period**, but excludes random factSet/revision ID. Unique exact stable scope key. Atomic row-lock epoch/counter guard controls TEST_ONLY active membership and prevents stale current. Require active FactSet same scope and organization (transactional validated join, PG negative tests or composite FK). Out-of-order trusted events cannot advance a superseded epoch.

**R0 admission remains TEST_ONLY.** Production S2 authority is independent; no externally delivered approval boolean can bypass it.

**DB privilege boundary:** a row-level UPDATE/DELETE rejection trigger does **not** protect against `TRUNCATE`, an authorized `DROP TABLE`/`DETACH PARTITION`, table ownership, superuser bypass or filesystem corruption. Finance application/worker DB roles must **not own** fact tables/partitions and must lack `TRUNCATE`, `DROP/ALTER`/schema DDL privileges; partition maintenance uses a separate least-privilege maintenance role. Add negative SQL-role tests and backups/WAL/restore exercises to production release gates. The proposed DDL trigger alone is NOT a complete immutability guarantee.

## 4. SQL migration ordering and partition lifecycle

**Migrations (IMPLEMENTED):** `form-library/src/main/resources/db/changelog/019-fin-calc-r0-postgresql.sql` creates 5 calc tables; `020-fin-fact-r0-pilot-postgresql.sql` creates unpartitioned factset/control and partitioned observation parent, plus initial FK from calc_plan to factset; `021-fin-fact-scope-source-immutability-postgresql.sql` **replaces that FK with an organization+scope-qualified composite FK and prevents source revision/row/evidence mutation**. All included in Liquibase master and **validated with PostgreSQL 16 Testcontainers including 018→021 upgrade**. Historical 001–018 remain unchanged. The earlier file `docs/temporary/sql/FIN-FACT-PARTITIONED-PG16-DDL-DRAFT.sql` is a **non-normative historical draft**: runtime DDL source of truth is now migrations 019/020, and maintenance creates monthly child partitions rather than hard-coded DDL. [CI 25 PG tests](https://github.com/alimbetov/fin-analyzer/actions/runs/44488324841).

- Partition naming `fin_fact.fact_observation_YYYY_MM` and bounds `FROM ('YYYY-MM-01') TO ('YYYY-MM+1-01')`, strictly ISO civil DATE; do **not** use `Asia/Almaty` wall-clock instants to derive financial reporting period if the source already has an explicit LocalDate.
- Default partition **not enabled**. Missing historical partition is a controlled `FACT_PARTITION_MISSING`, never silently stored in unexpected bucket or created during a financial transaction.
- Pre-create 12–36 **historical** months (policy based on known reporting data) and 6–12 forward months; on-demand historical backfill uses an **operator-controlled preflight**, then emits a chunked ingest job.
- Never automatically `DROP TABLE` for financial history; period retention is a separate legal/archive/backup/restore policy. Archiving may use DETACH with documented approval, copy/checksum and access constraints; not part of R0.
- High-cardinality partitioning by organization, 166 formCode, exact SHA, daily date or multi-level HASH is **OUT** until benchmark proves otherwise. Don't create 166×months partitions.
- Prefix create lock per `schema+parent+partitionYearMonth`, avoid running DDL in TX-A/TX-Q. Multi-node maintenance is idempotent, bounded and monitored.
- Validate not just catalog `partstrat='r'` but **actual partition key column `period_start`, type DATE, child bounds, child indexes and absence of overlapping/incorrect partitions**; if wrong existing child exists, STOP, not SKIPPED.
- Compare before/after database catalog state. Do not trust `CREATE TABLE IF NOT EXISTS` alone when an existing table has wrong bounds.
- DDL should use `lock_timeout` and `statement_timeout` in a separate short transaction, advisory transaction locks, bounded SQLSTATE retry; no unbounded connection pinning or long block on hot parent.
- PostgreSQL 16 `CREATE TABLE ... PARTITION OF` takes stronger parent locking than ATTACH approach; initial low-scale R0 may use direct create; for high-traffic schedule prepare+check+ATTACH out of peak. Partitions inherit CHECK/indexes; verify index count and query plans.

## 5. Adaptation of user's TablePartitionService

**Reusable patterns from uploaded Java service:**
`pg_try_advisory_xact_lock`; allowlisted validated identifiers; partition discovery using `pg_inherits`; short transaction/SET LOCAL lock_timeout + statement_timeout; bounded retry on 40001/40P01/55P03 (57014 cautiously); dry run, multi-node concurrency counters, bounded max partitions per run, Micrometer and health.

**Must CHANGE for FIN:**
1. `daily/yyyyMMdd` → `monthly/yyyy_MM`; `LocalDate` month-boundary normalization, `YearMonth` API.
2. `cleanDailyPartitions` / `DROP TABLE` → **NO automatic cleanup/drop method**; finance immutable retention.
3. `checkHealth(todayPartition)` → coverage across required historical min-month, current month and configured lookahead, and check `pg_get_partkeydef` = RANGE(period_start). Fail on a gap for any required pinned FactSet.
4. Parent whitelist only `fin_fact.fact_observation`; do not expose arbitrary schema/parent strings to controllers.
5. Reuse `@Transactional(NOT_SUPPORTED)` intent, but prefer Spring `TransactionTemplate(REQUIRES_NEW)` for each DDL so transaction ownership/commit aligns with Spring; avoid manually committing a pooled JDBC connection inside arbitrary managed transactions.
6. Check **actual bound definitions** as well as child name; a same-named child with wrong bounds is not SKIPPED. Replace `DROP` retry semantics with operator-approved archive flow outside R0.
7. For actual shared reader performance, verify `EXPLAIN (ANALYZE, BUFFERS)` shows partition pruning on interval predicate; a WHERE factset_id alone is not sufficient.

### New Java package/class suggestions

```text
kz.fin.formlib.calc.ingest:
  SourceRevisionReader / SingleRecordFactExtractor
  RowsetFactExtractor / EvidenceReferenceExtractor
  FactMappingRegistry / SourceFactAssembler
  CandidateFactSetCanonicalizer / FactSetCommitter
  QualifiedFactReader / FactSetCompletenessValidator
kz.fin.formlib.calc.partition:
  FactMonthlyPartitionService
  FactPartitionSchedule
  FactPartitionHealth
  FactPartitionStats
  FactPartitionCatalogInspector
```

```java
public interface FactMonthlyPartitionService {
  PartitionStats ensureMonthlyRange(YearMonth first, YearMonth last, boolean dryRun);
  PartitionHealth inspectRequiredCoverage(YearMonth first, YearMonth last);
}
// NO clean/drop method; no dynamic schema or table arguments supplied by HTTP.
// Must not run from the form-save or fact-commit transaction.
```

Partition scheduler safety: independent limited execution, feature flag OFF by default until PG qualification, leader not necessary if advisory lock and idempotence verified; bounded gap+retry alerts.

## 6. Merge semantics / revision correction

**Do not SQL MERGE overwrite old financial facts.** A new qualified revision produces a new immutable `fact_set` and new set of observations. Idempotent exact replays use `INSERT ... ON CONFLICT` / unique header fingerprint plus exact payload validation under TX-Q. A draft change alone does not create a qualified new snapshot.

```text
F02 revision 1 qualified → FactSet A (3 fact rows) → calc result A
F02 revision 2 draft only → FactSet A remains authoritative
F02 revision 2 newly TEST_QUALIFIED → FactSet B (3 NEW rows)
                                        → executionFingerprint B
                                        → compare nodeFingerprint:
                                            GP / margin REUSED
                                            operatingResult RECOMPUTED
                                        → calc result B (new lineage)
                                        → guarded CURRENT=B
Old FactSet A and immutable result A remain queryable as history.
```

For 166 forms, a project-wide **composite** FactSet is a version-pinned ordered membership of multiple qualified source revisions. Its construction **must** check metric identity/dimensions, period comparability and source conflicts (e.g. duplicate Revenue in F02 and ReportGrid). A sum/rollup is performed by a separately versioned approved methodology, NOT implicitly by the ingestion merge. A source row may yield several numeric observations but cannot lose its row UUID or dimension identity.

**ROWSET delta:** if stable row IDs persist, compare prior vs new row value digests, only dirty source row/field keys propagate to impact graph. If row UUIDs are regenerated on every save, fine-grained incremental matching is not proven; fallback to full affected source-scope recompute or require reviewed business-key match. For future partial updates, staged diff may avoid parsing unchanged rows, but published immutable FactSet still has complete expected observation count and lineage.

## 7. Pipeline controls and missing/failure states

| Barrier | Fail condition | Required state |
|---|---|---|
| Revision read | missing UUID, source SHA/canonical mismatch, wrong formVersion | SOURCE_INTEGRITY_BLOCKED |
| Mapper | absent reviewed contract; unknown field; schema/mapping SHA drift | MAPPING_UNQUALIFIED |
| Extraction | duplicate row IDs, incompatible dimension grain, wrong period | FACT_SHAPE_INVALID |
| Numeric typing | invalid BigDecimal, overflow/scale mismatch, silent FX | FACT_VALUE_INVALID |
| FactSet assembling | missing required form, conflicting metric, 0 expected numeric unexpectedly | FACTSET_INCOMPLETE / SOURCE_CONFLICT |
| Partition preflight | required month child missing/wrong bounds | FACT_PARTITION_MISSING / FACT_PARTITION_CORRUPT |
| TX-Q | event/facts not atomically committed | rollback all, no job |
| Queue/worker | event duplicated, wrong FactSet SHA, partial rows, stale fence | no accepted result or CURRENT |
| Publication | active authority epoch revoked/superseded | BLOCKED_STALE_APPROVAL |
| Retention | operator attempts drop linked history | BLOCK and escalate to policy review |

No raw source JSON/financial values in Redis, outbox, logs or Micrometer tags.

## 8. Acceptance plan (beyond existing A01–A31 and STF-01–16)

| ID | Required PostgreSQL/Java proof |
|---|---|
| PFT-01 | PG16 DDL parent actually RANGE(period_start); missing partition write fails |
| PFT-02 | Composite `PRIMARY KEY(period_start, observation_id)`, UNIQUE includes period_start; invalid single-column PK rejected |
| PFT-03 | Insert numeric fact with 2026-10-01 and 2026-11-01 routes to different monthly partitions, exact PK and FK |
| PFT-04 | EXPLAIN with period_start predicate prunes unrelated month partitions; without range predicate document worst case |
| PFT-05 | Multi-node ensure same partition, one CREATE, others SKIPPED/CONCURRENT; correct bounds/catalog |
| PFT-06 | Existing wrong-bound/incorrect-key partition causes BLOCK, not false SKIPPED |
| PFT-07 | Missing historical partition during backfill fails closed; operator-controlled creation then idempotent replay works |
| PFT-08 | No automatic DELETE/DROP/cleanup entrypoint, protected historical results and fact rows |
| PFT-09 | Period DAY/WEEK/DEC/M/Q/H/Y and AS_OF/INTERVAL mapping preserves original interval; no wrong SUM of balances |
| PFT-10 | F02 three source values become three persisted observations; netProfit never inserted as source |
| PFT-11 | ROWSET stable row keys/dimensions with 2+ rows; no unexpected sum/dedup |
| PFT-12 | EVIDENCE reference produces zero numeric facts and validated document SHA/ref |
| PFT-13 | One composite FactSet built from F01+F02, input source list and output digest deterministic regardless of ordering |
| PFT-14 | Duplicate source metric from Form vs ReportGrid ⇒ explicit SOURCE_CONFLICT until reviewed precedence |
| PFT-15 | New revision qualified vs unqualified: append-only new observations, exact source lineage, dirty DAG nodes only |
| PFT-16 | Multiple concurrent factset commits, late worker, revocation and old approval event cannot publish wrong CURRENT |
| PFT-17 | 166-form discovery matrix coverage: every form has declared extractor strategy and review state; no automatic S2 approval |
| PFT-18 | Clean/upgrade migrations 001–020, schema hash, indexes/privileges; negative app role TRUNCATE/DDL tests; no changed 001–018 checksums |

### Hard acceptance definitions

- **Form catalog coverage:** 166/166 mapped to one supported source strategy and versioned contract review state. **Runtime extraction qualification** must be reported separately per form; only F02 is a TEST_ONLY pilot in R0.
- **Source-to-fact integrity:** for a qualified snapshot, verified observation count and SHA, zero duplicate business keys, all source revisions pinned, no accidental period/currency crossover.
- **Financial safety:** zero unapproved input→fact promotions, zero stale CURRENT after revoke, zero silently mutated facts or source rows.
- **Performance:** compare unpartitioned baseline vs monthly partitions with the **same** data volume, realistic retention/history, exact period-filter predicates, p95/p99 and `EXPLAIN BUFFERS`; do not assert partitioning always faster.

## 9. Implementation sequence — unchanged priority of pure math

**R0.B1** pure F02 kernel; **R0.B2** preview; **R0.B3** calc outbox/queue; **R0.B3F.1** partitioned fact schema+safe partition manager+PostgreSQL PFT-01..08; **R0.B3F.2** F02 mapping+factset commit/test-only qualification (PFT-09/10/15); **R0.B4** multithreaded worker reads only persisted facts; **R0.C** incremental fingerprint/reuse/guards; **R0.D** full same-SHA E2E. **R1-INGEST** reusable ROWSET, EVIDENCE, ReportGrid adapters + 166-form contract reconciliation; **R1-CACHE** optional Redis adapter; **R2** benchmarks. Production financial S2 remains a separate gate.

**Implementation evidence:** FACT-0 physical migrations 019/020/021 and `FactMonthlyPartitionService` **passed 9 PG16 tests**; 018→021 historical checksum/data preservation passed **1 PG16 upgrade test**. FACT-1 exact F02 **synthetic test-only** 3-row/Outbox TX-Q passed 4 PG16 methods; qualifier source lives exclusively in `src/test`, not production. Remaining 166 form semantic transformations/field mappings, actual independent financial S2, runtime qualified writer, workers and results are **not implemented**. [Detailed audit](../audits/FIN-FACT-R0-FACT1-F02-S1-PG16-REVIEW-2026-11-19.md).
