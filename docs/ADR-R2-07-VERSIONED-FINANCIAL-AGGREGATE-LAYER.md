<!-- FIN-HTAP-CONSOLIDATED-ENTRYPOINT-V1 -->
> **FIN HTAP consolidation (2026-11-17):** [FIN-HTAP-CONSOLIDATED-TZ.md](FIN-HTAP-CONSOLIDATED-TZ.md) is now the **single normative implementation specification** for tenant/form/PeriodType/Period/Currency financial slices, calculations, aggregation, workload admission, workspace physical partitions, generation switching and DETACH/DROP. [Technical audit](FIN-HTAP-CONSOLIDATION-TECHNICAL-AUDIT-2026-11-17.md) records identified P0/P1 design defects and test gates. Sections below are **historical rationale and preserved acceptance details**; conflicting execution/physical-design prescriptions are superseded. Stricter immutable-source, authorization, independent method-S2, ACTUAL HOLD and RELEASE_HOLD safeguards are **retained**. This header does **not** assert implementation, CI GREEN or financial qualification.

# ADR-R2-07 — Versioned Financial Aggregate Layer (proposal for FIN-BE-R2-W)

**Status:** PROPOSED / BENCHMARK REQUIRED / NOT IMPLEMENTED / INDEPENDENT S2 PENDING  
**Date:** 2026-11-17 · **Repository:** `alimbetov/fin-analyzer` · **Sole branch:** `feat/fin-r0-r1a`  
**Extends:** [ADR-R2-06](ADR-R2-06-TENANT-AWARE-CALCULATION-WORKSPACE.md), [FIN-BE-R2-W v1.1](FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ.md), [ADR-R2-05](ADR-R2-05-METHOD-MODULE-DUAL-ENGINE-IMMUTABLE-RESULTS.md).  
**Code-backed source:** `fin_fact.fact_assertion`, `fact_selection`, `fact_set_publication`, `fin_calc.metric_contract`, `Fin01RevenueMomSqlModule`.  
**Purpose:** reusable, immutable-after-seal, versioned **derived aggregate cache** for finance methodology inputs; it is not financial source truth, not an ExecutionPermit and not a third computation engine.

## 1. Proposal and rationale

Insert an optional `fin_agg` layer between trusted published FactSet and calculation workspaces. One published source / verified aggregation policy can be grouped once into deterministic period-and-dimension buckets, then reused by multiple qualified SQL/JAVA `MethodModule` variants and analysis runs under the same tenant. Target benefits are fewer repeated scans and joins, lower repeated CPU/I/O, incremental per-revision build (not in-place update), and faster period comparisons. **Do not claim speed gains before measurement.**

A *logical aggregation key* is multi-dimensional, but **physical partitions must not be the Cartesian product** of tenant × currency × metric type × period grain × month. Keep those as equality/filter/index columns; start performance experiments with unpartitioned, and fixed `HASH(organization_id)` 8/16/32 partitions. Alternative HASH(organization_id,revision_id) and range/subpartitions only if workload/tenant skew and pruning evidence favor them. No tenant-specific/currency-specific runtime DDL. PostgreSQL 16 declarative partitions require all UNIQUE/PRIMARY KEY constraints on partitioned parent to contain partition keys; plan composite PK/FKs accordingly.

```text
immutable trusted fin_fact + source population/version contract
     ├─ DIRECT_PUBLISHED path for small / non-additive inputs
     └─ governed PREPARE task -> fin_agg.sealed_aggregate_manifest
                      -> fin_agg.aggregate_cell (+ lineage evidence)
                      -> reusable tenant-scoped input
     -> fin_workspace (direct, aggregate-backed, or detailed sealed workspace)
     -> SQL/JAVA MethodModule via one orchestrator
     -> immutable MethodResultEnvelope / receipt
```

SQL generation and materialization is a **PREPARE-class admitted task** with global+tenant quota, row budget, query timeout, memory/temp/WAL limits, and policy gates; no precomputation synchronously on the user upload/publish HTTP transaction. Legacy scheduler and outbox remain single. `fin.execution.mode=OFF` and release HOLD remain default.

## 1.A. Logical financial slice and physical partition are separate

The requested **tenant + form code + period + period type + currency** identifies a common financial **logical slice** used for routing, indexing and later generation replacement. The complete trusted key also pins entity/scope, published revision/hash, dataSpace, reportingBasis, form version/source revision, `metric_code/metric_version`, dimension and population contract, fiscal calendar, aggregation policy and value state. `form_code` is derived from `approved_source_revision`, not stored on every assertion; cross-form calculations must preserve metric identity. `time_kind` (`INTERVAL`/`AS_OF`) is distinct from `period_grain` (`MONTH`/`QUARTER`/`YEAR`).

For fast GC of *ephemeral* materialization, the physical data partition follows a bounded, server-owned **lifecycle cohort** and is DROP-eligible only when **every mapped slice/generation** is reclaimable. See [ADR-R2-08 §2.A](ADR-R2-08-LIFECYCLE-PARTITION-RECALCULATION.md) and [FIN-BE-R2-W §0.E](FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ.md). A persistent reusable aggregate manifest may need a different retention period than the ephemeral workspace; never drop referenced `fin_agg` cells simply because the prior computation generation has retired. The five-dimensional business selector is **not** a directive to create a child table per combination.

## 2. Two different notions of “type”

Do not conflate:
- `time_kind` from existing fact assertion: **INTERVAL** versus **AS_OF**.
- `period_grain`: `DAY`, `WEEK`, `MONTH`, `QUARTER`, `YEAR`, `FISCAL_PERIOD`, `AS_OF_DATE` (where supported). Time bucket boundaries, timezone/fiscal calendar version, start-inclusive/end-exclusive and applicability are explicit.
- `measure_kind`: **FLOW, STOCK, LEVEL, RATIO** (only approved metric contract semantics, not inferred from code/name). RATIO is **never summed/averaged directly**; ratio-of-sums (or other specified business formula) needs trusted component aggregates.
- `aggregation_kind`: **FLOW_SUM**, **STOCK_CLOSING**, **LEVEL_WEIGHTED_AVG**, **RATIO_COMPONENTS**, **DETAIL_ONLY**; a reviewed `AggregationPolicyRegistry` selects an allowlisted operation for exact metric+version, reporting basis, unit, population and time policy.

## 3. Canonical identities and proposed tables (DDL contract, not migrations)

1. `fin_agg.aggregate_manifest`: immutable source identity and expected population identity (`organization_id`, entity, scope, fact_set_revision_id, source snapshot_hash, publication/outbox, data_space, basis, metric selection fingerprint, dimension selector version, aggregation policy version, calendar/fiscal period rule version, implementation fingerprint, aggregate schema version), `manifest_key` UNIQUE by tenant, status `BUILDING/VALIDATING/SEALED/FAILED/QUARANTINED/RECLAIMABLE`, generation/fence, counts, input content hash, output content hash, created/sealed times and controlled expiry. State control may change until SEALED; sealed facts/identities do not.
2. `fin_agg.aggregate_cell`: tenant/org, manifest_id, economic entity/scope, metric_code+metric_version, measure_kind, aggregation_kind, time_kind, period_grain, period_start+end_exclusive, calendar_version, reporting_basis, data_space, unit_code, currency_code (nullable for non-money, never coalesce into currency), dimension_hash plus canonical dimensions/selector, status `CALCULATED/INCOMPLETE/NOT_COMPUTABLE/ESTIMATED` under policy, sum/count/weighted numerator+denominator or closing value as applicable, expected/actual source row counts, coverage fingerprint, content hash. Each cell has a canonical unique business key **including partition key if partitioned**. `NULLS NOT DISTINCT` or generated canonical null token may be needed for nullable currency; review key and PostgreSQL 16 syntax before migration.
3. `fin_agg.aggregate_lineage`: for every cell, source population fingerprint, selected assertion IDs or deterministic server-owned reconstruction query+identical source selection proof; trace every output back to immutable `fact_selection`. Choose the mapping/cardinality using measured storage cost without weakening audit.
4. `fin_agg.aggregate_reference`: sealed manifest + run/workspace + read-authorized tenant + retention pin, tombstones/generation for idempotent cleanup. No cross-tenant reuse.

Exact FK model: scoped manifest `UNIQUE(organization_id,manifest_id)`; cells/reference carry same tenant and composite FK; fact source identities reference exact publication and selected facts. If HASH by organization, partitions share parent constraints including `organization_id`. A cache row for a different revision is always a new logical row, never an UPDATE of an old revision's amount.

Avoid a single universal numeric amount for all kinds without status/units and aggregation algebra. The minimum pilot can store only FLOW_SUM of accepted REV_NET monthly values, with separate coverage/lineage columns. Other kinds must remain blocked/DETAIL_ONLY until their arithmetic and S2 review are proven.

## 4. Mathematical correctness rules (P0)

| Fact/metric | Allowed aggregate (if exact policy) | Forbidden shortcuts |
|---|---|---|
| `FLOW` (e.g. REV_NET) | SUM of **nonoverlapping, fully covered** compatible intervals for one metric/version, unit, currency, entity, scope, basis, dataSpace and dimensions; time-grain rollup only with approved calendar | Summing overlapping monthly and quarterly source figures, mixing currencies, partial-year as full year, guessing missing as zero |
| `STOCK` (e.g. assets/liabilities) | Approved closing `AS_OF` value at exact reporting date (or explicitly qualified carry policy) | Summing or averaging end-of-month balances |
| `LEVEL` (if qualified) | Day-weighted average with verified day coverage and numerator/denominator evidence | Simple average of unequal-period observations |
| `RATIO` / percentage | Store/aggregate approved numerator and denominator **separately**, compute ratio in method using explicit zero/negative policy | SUM(ratio) or AVG(ratio), averaging percentages across unequal bases |
| `CON-01/CON-02` Pareto/ABC | Keep SKU population or SKU-grain totals and correct exclusions/tie-breaking | Company total only, removing essential ranking detail |
| DCF/NPV/IRR | Maintain exact dated cashflows/assumptions and method version; bypass aggregate if aggregation destroys temporal detail | Replacing dated cashflow stream with a yearly sum without method acceptance |

All states `MISSING`, `EXPLICIT_ZERO`, `ESTIMATED`, `NOT_APPLICABLE` remain distinct. `ACTUAL` cannot use `ESTIMATED` without independent qualified authority. No mixed `ACTUAL/SYNTHETIC/PLAN/FORECAST/SCENARIO` data. Existing `fin_calc.metric_contract` `measure_kind` / `unit_code` alone is insufficient for all aggregation rules: require a separate **versioned reviewed AggregationPolicyRegistry** and reject unknown semantics, rather than broadening automatically.

Calendar YEAR/QUARTER rollups require canonical fiscal calendar, no missing periods, no duplicated source rows, no overlap, correct start/end boundaries. For revenue monthly MoM, select two exact consecutive fully controlled monthly cells; a company YEAR total cannot substitute for either.

## 5. Transactionality, invalidation and retention

1. PREPARE admission checks global/tenant resources; source is exact published `FactSetRevision` with immutable source+outbox fingerprints.
2. Builder loads rows in bounded idempotent batches, each containing canonical fact identity and attempt fence, without visibility as SEALED.
3. Short seal transaction locks manifest, validates exact selected input population, source qualification, calendar/dimension/coverage controls and independent expected content hash, CASes generation/fence, and atomically exposes **one** SEALED manifest. Other builders reuse matching sealed hash or fail without mixing generations.
4. Querying methods must require SEALED + immutable manifest/source/policy match + tenant authorization. A missing or invalid aggregate returns `NOT_AVAILABLE` and may trigger a guarded direct source query or new PREPARE task; **never silently use partial/stale cache**.
5. New FactSet revision or revised policy produces **new immutable manifest key**. Old version remains reproducible. No in-place refresh of prior sealed cells. Reuse unchanged source assertions between revisions only if explicit immutable selection equivalence is proven; not part of first pilot.
6. Background cleanup retains every referenced manifest (active tasks, reports/replay, legal hold) and never deletes trusted published facts, audit or immutable method results. Removal is bounded, resumable and separately admitted under CLEANUP quota.
7. No extra global materialized view owner refresh in production. PostgreSQL 16's standard `REFRESH MATERIALIZED VIEW` replaces the view contents, rather than offering built-in per-tenant incremental refresh; use governed append-only per-version tables to preserve historical revisions.

## 6. Comparison / acceptance before choosing physical design

**A/B/C paths** on identical, real PostgreSQL 16 synthetic fixtures:
- A: direct exact published source query, no aggregate (baseline);
- B: unpartitioned derived aggregate table + tailored indexes;
- C: fixed HASH(organization_id) 8/16/32 derived aggregate partitions + same logical indexes.
Optional D: shared detailed workspace without aggregate to isolate benefit of pre-aggregation.

Measure at different tenant count, tenant-size skew, months/years/metric cardinality, cold/warm cache, 1/2/4/8 SQL workers and light/heavy mix:
`EXPLAIN (ANALYZE,BUFFERS)`, p50/p95/p99, planning CPU, rows read, cache hits, total end-to-end time **including build + cleanup**, index/storage overhead, WAL and autovacuum, PostgreSQL connections, temp files and OLTP p95. Compare crossover threshold for one-use versus many-use aggregate. Same result hash and reason codes are P0; throughput gain is hypothesis, not a gate until measured.

**Initial pilot FIN-01:** two consecutive published qualified `REV_NET` MONTH FLOW cells, same tenant+entity+scope+metricVersion+reporting basis+currency+dimensions and exact sealed manifest. Compare SQL method result with legacy Java on the same source. Positive 100 -> 150 = 50%; zero baseline, negative baseline, gaps, overlap, missing vs explicit zero, cross-currency, cross-tenant, revised snapshot, fiscal boundary, overflow, precision/HALF_EVEN, duplicate values, stale cache and worker race must fail/agree correctly.

**Decision gate:** If B/C is not measurably faster at expected reuse counts and does not preserve no-overload OLTP capacity, keep direct/W1 workspace access without an aggregate table. Do not optimize an unverified demand pattern with physical DDL.

## 7. Implementation boundary and documentation change

- Register this as **proposed W1.A feasibility + W3.A FIN-01 aggregate parity** under existing W0–W8; do not create another orchestrator, outbox or execution engine.
- Until independently reviewed, no Flyway table, migration, runtime feature flag, and no claim of improved speed.
- Existing `FIN-BE-R2-EXECUTION-SUPERSESSION-LEDGER` does not deprecate workspace or method result requirements: aggregation is an **optional input optimization**, not a replacement for financial proof or versioned results.
- `RELEASE_HOLD`, method S2 and server ExecutionPermit still apply.
