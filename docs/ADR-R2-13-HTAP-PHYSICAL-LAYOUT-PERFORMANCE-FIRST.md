# ADR-R2-13 — FIN HTAP Physical Data Layout: Performance-First Logical Slices and Lifecycle Cohorts

**Date:** 2026-11-17  
**Status:** **ACCEPTED TARGET DESIGN / NOT IMPLEMENTED / BENCHMARK AND INDEPENDENT S2 PENDING**  
**Repository:** `alimbetov/fin-analyzer`; only `feat/fin-r0-r1a`. Backend Java 21 / Spring Boot / PostgreSQL 16 / Flyway.  
**Normative parent:** [FIN-HTAP-CONSOLIDATED-TZ](FIN-HTAP-CONSOLIDATED-TZ.md), §4 and W0–W8. This ADR **clarifies the concrete default physical strategy**; it does not supersede the strict source-trust, tenant authority, immutable history, CAS, Method S2, or release HOLD requirements of the parent. Historic ADR-R2-07/R2-08 remain architecture rationale; any conflicting physical recommendation must defer to this ADR for the *initial* HTAP implementation.  
**Evidence type:** code/spec review + PostgreSQL 16 documented behavior; **no measured application latency, real data cardinality, cache benefit or benchmark PASS is claimed**.

## Delivery timing (ADR-R2-14)

[ADR-R2-14](ADR-R2-14-FIRST-VERTICAL-SLICE-DELIVERY-FREEZE.md) freezes the immediate product delivery priority: complete the SYNTHETIC FIN-01 vertical with trusted source, PLAN_ONLY, controlled execution, immutable result and report **before** optimizing physical layouts. This ADR-R2-13 **remains** the default partition/index policy and W6 experiment contract; it does not itself make additional partitions, caches or large-scale benchmarks prerequisites to the first financial end-to-end acceptance. Genuine measured P0 regressions remain exceptions.

## 1. Decision in one page

**A. Immutable published finance source — preserve existing `fin_fact`.** No repartition, bulk rebuild, new aggregate child or index proliferation on existing production tables as a prerequisite to W1. Their isolation, temporal semantics and immutable revision/publication model are authoritative. Any later `fin_fact` partitioning is a separately benchmarked populated-database migration.

**B. FinancialSliceKey — a *logical exact source-selection identity*, not a physical partition tuple.** Requested `organization + entity + scope + form + period + currency + mappingVersion + publishedRevision` are useful *dimensions*, but are not the complete identity. The canonical source-selection vector must additionally pin formVersion, source-revision identity and snapshot/content SHA, dataSpace, reportingBasis, periodType/coverage/timeKind, interval bounds, calendar version, metric+metric version, dimension/population and unit/value-state policy; the exact actual field set is frozen in W0/W1.2.A. A selected `Published FactSet` can legitimately contain multiple approved form revisions; preserving per-point provenance is mandatory. Use deterministic canonical encoding/version and a compact server-computed fingerprint for routing; do not rely on a client-supplied hash, nullable composite columns, or SHA alone to grant authorization.

**C. Persistent directory/control — ordinary *unpartitioned* small indexed relations** (`slice_manifest`, `workspace_manifest`, `generation`, `reference`, `partition_catalog`, `partition_membership`, `cleanup_event`). They own tenant-scoped uniqueness, provenance, exact `slice_key_hash` plus human-reviewable constituent keys, workspace-to-cohort mapping, active/SEALED state, pin counts and durable replay/GC audit. Avoid a huge eight-column physical partition key or one table per form/tenant/method.

**D. Reusable sealed `fin_agg` — unpartitioned B-tree-indexed baseline, conditional materialization.** Start with only financially qualified, reusable algebra on a selected tenant/source/metric/population. A small/one-off input uses `DIRECT_PUBLISHED`, not forced cache building. `fin_agg` is *not* assumed disposable, and does not inherit workspace GC. Candidate fixed `HASH(organization_id)` or `RANGE(period)` layouts stay in W6 A/B experiments and require an evidenced query+retention advantage before adoption. A full logical key lookup is implemented through exact tenant-scoped manifest equality and workload-derived indexes, not by one universal wide index.

**E. Ephemeral high-volume `fin_workspace` — `RANGE(reclaim_epoch)` by retention-class UTC cohort, precreated and bounded.** `reclaim_epoch` denotes the *earliest eligible reclaim time* of a server-owned cohort, **not** accounting period. Use calendar-week buckets as the **first engineering benchmark candidate**, not a hard-coded immutable interval; daily buckets are considered if measured volume, reclamation latency and operational cost justify them. Separate long-lived/hot and exceptional retention classes **at admission**; no hidden migration of SEALED rows to another cohort. Use at most two original stage parent tables in the baseline, with bounded child count and index budget established during W0. No DEFAULT child if `DETACH CONCURRENTLY` is required. No child creation per HTTP request, form, tenant, currency, revision or method. Do not enable subpartitioning by tenant until measured.

**F. Read path:** resolve exact `SEALED` generation and `workspace_id` from tenant-scoped directory using the canonical key; return `reclaim_epoch/cohort`; execute typed SQL binding `reclaim_epoch + organization_id + workspace_id + generation + exact source/scope/metric/period/currency`. The explicit partition-pruning predicate is **mandatory** (verify `EXPLAIN` prunes). The physical cohort is not a security boundary. Use composite tenant FKs and server-side authorization/RLS and defend against pooled-session tenant leakage.

**G. Rebuild and reclaim:** new generation N+1 builds and seals without overwriting N, active head CAS switches only complete qualified result set, pinned readers keep N. Deferred privileged GC may `DETACH CONCURRENTLY`, `FINALIZE` after interruption, and `DROP` **only if all cohort members are reclaimable** and none has readers, leases, live SQL, receipts/retention/replay pins, legal hold or an unconfirmed cancellation. Metadata and audit survive the payload DROP. Never DROP immutable `fin_fact`, published reports/results or pinned `fin_agg`.

**Conclusion:** this is the **implementation default** selected for financial correctness, minimal DDL/partition counts, query-locality through directory+indexes, fast *safe cohort* GC and high tenant cardinality. It is **not** a proved universally fastest layout; W6 physical optimization is conditional on benchmark superiority.

## 2. Financial/access patterns before physical design

| Workload | Query shape / financial requirement | Path | Latency hazard |
|---|---|---|---|
| Single-company dashboard | one tenant, form, month/quarter, currency, metric, active published generation | exact manifest → candidate sealed agg/direct | eight-key index bloat, unresolved tenant/source lineage |
| Financial MoM/YoY or moving window | consecutive compatible intervals and exact fiscal calendar, usually one entity/metric/currency | series resolver then bounded scans; conditional aggregate | `lag` over missing months or mixed coverage; scans across all lifecycle cohorts |
| Cross-form financial ratio | two or more independently reviewed source bindings, matched STOCK closing dates and currency | multiple exact input manifests, method DAG | pretending one FactSet/form equals all sources |
| CON-01/02 SKU distribution | complete reviewed SKU population, deterministic tie policy and exact upstream receipt | DETAIL or SKU-grain aggregate; never company total alone | incorrect ranking after early aggregation |
| DCF/IRR/forecast | dated cashflows, curve/rate/assumption versions | DIRECT/qualified specialized cell | collapsing dated events into period SUM destroys value |
| New revision / amended mapping | rerun impacted dependent windows, old report still readable | immutable new generation + CAS + selective DAG | global invalidation, recalc all 166 forms |
| Many tenants simultaneously | mixed hot/cold tenants with different row counts | bounded admission, tenant predicates, shared cohorts | tenant skew, connection/worker saturation, memory/WAL stalls |
| Retire old stage generation | full cohort membership/hold check | detached physical child by operator | one retained slice blocks an entire mixed-tenant cohort |

**Financial invariants override speed:** `FLOW_SUM` only exact covered non-overlapping flow intervals; `STOCK_CLOSING` never SUM; `LEVEL` appropriately day-weighted; `RATIO` derived from qualified components; percent values are unit-tagged; missing and explicit zero distinct; source revisions, foreign exchange conversion and synthetic/actual scenarios cannot be silently mixed. A plan that is faster but breaks any invariant is rejected irrespective of p95.

## 3. Alternatives and architectural decision matrix

Scores are **qualitative hypotheses**, not benchmark measurements.

| Option | Selective financial read | High-cardinality tenants/forms | Safe fast stage GC | Writes/DDL/maintenance | Decision |
|---|---|---|---|---|---|
| 8-column partition `tenant×entity×scope×form×period×currency×mapping×revision` | narrow child, but huge routing/metadata | unbounded physical relations | very fragmented | unacceptable DDL/planner/catalog overhead | **REJECT** |
| `RANGE(accounting_period)` for every finance table | good pruning for date windows | bounded if bucketed | wrong retention semantics for mixed age/pins | repartitioning and long-lived revisions | **W6 CANDIDATE for a specific durable large table only** |
| fixed `HASH(organization_id)` for all data | may aid tenant equality and hot-spot locality | bounded children | cannot detach a single finished workspace | skew/routing remains; extra index copies | **W6 CANDIDATE for fin_agg** |
| unpartitioned everything | simplest and often best for small tables | no partition explosion | large DELETE/VACUUM for stage | poor big-stage cleanup | **BASELINE for metadata and fin_agg** |
| `RANGE(reclaim_epoch)` on disposable stage + indexed metadata | two-step lookup/pruning mandatory | physical count independent of tenant/form cardinality | fast only once *whole* cohort safe | precreation, catalog+GC barrier | **ACCEPT as workspace default** |
| `RANGE(reclaim_epoch)→HASH(organization)` | possible tenant locality improvement | bounded only with strict limit | same whole-cohort GC | multiplicative partition count | **HOLD pending W6 proof** |
| dedicated per-heavy-run partition | local reads and isolated DROP | risky at scale | isolated GC | admission/DDL cost | **EXCEPTION only, owner-approved thresholds and quota** |

Why not a universal calendar RANGE? Accounting period governs *query semantics*, while reclaim time governs *physical lifetime*. The same March 2026 facts may have several approved revisions, financial years and legal retention pins. Reclaim must not delete historical/active receipts. A physical cohort may contain multiple reporting periods but cannot be dropped if any member remains live.

## 4. Index, statistics and cache policy (performance control)

1. **Directory:** `(organization_id, slice_key_hash, generation)` equality/ordered index (exact key and SHA collision confirmation remain required), and a small filtered/indexed active pointer path as justified by observed cardinality. `UNIQUE(organization_id,workspace_id)` at the unpartitioned control relation; separate scoped manifest/reference FKs. No universal `UNIQUE` on a partitioned payload without its real partition keys.
2. **Workspace payload:** candidate minimal per-cohort B-tree `(organization_id,workspace_id,generation,metric_code,period_start)` where the actual query needs it; bound columns/INCLUDE by measured index bytes, insertion WAL and `EXPLAIN ANALYZE`. Always bind `reclaim_epoch` for pruning. If point lookup by a different order dominates, test revised index order rather than duplicating every combination.
3. **Aggregate cache:** candidate `(organization_id,manifest_id,metric_code,period_start)` plus a separate key on cache manifest `(organization_id,source_selection_hash,aggregation_policy_hash)`; `currency/unit/form/basis` must be verified by manifest and cell even if not all appear in one index. Store narrow typed numeric components; avoid copying huge original JSON into every aggregate cell.
4. **Specialized access:** `BRIN` only when large physical tables show correlation with time or another key; `GIN` on JSONB only when actual selective containment searches justify cost; wide INCLUDE indexes require measured heap-visit reduction. Avoid indexing each of 259 unapproved parameter proposals.
5. **Statistics:** `ANALYZE` after bulk loads and before measured benchmarks; targeted extended statistics for correlated organization/form/period/metric when planner estimates demonstrate error. Enforce plan-shape regression for relevant prepared vs literal parameters, cold vs warm cache, changing cohort count and empty-cohort pruning.
6. **Cache admission:** `cost = build+seal+read_reuse+invalidation+storage+GC`; only materialize if expected reuse and approved financial algebra beat repeated direct published reads within the owner-approved cost budget. Do not preaggregate incompatible STOCK/RATIO, unreconciled SKU/cashflow series or cross-currency amounts.
7. **No hidden write amplification:** measure index bytes per input row, WAL bytes, memory/temp spill, autovacuum pressure, locks and disk headroom, not only SELECT latency. Keep OLTP reservation from W2 separate from analytics concurrency.

## 5. Exact retention and partition safety requirements

- `reclaim_epoch` is immutable **cohort assignment** at stage creation; separate `retention_class`, `legal_hold`, `expiry`, `generation/reader/receipt` state in durable catalog. Assignment is not a promise to reclaim on that date.
- Precreate bounded named children with exact catalog OIDs and reviewed constraints. Missing cohort -> `WAITING_PARTITION_CAPACITY`, never auto-DDL in a business transaction or silent DEFAULT routing.
- For PostgreSQL 16 partitioned PK/UNIQUE, include `reclaim_epoch` (and all partition keys). Prove global workspace identity separately in unpartitioned control rows and tenant-scoped composite FKs. Partitioned payload cannot be its own cross-cohort identity authority.
- DRAIN barrier must prohibit new readers, memberships or writes; verify a live SQL session has actually terminated after lease loss/cancel. No `DROP CASCADE`. `DETACH CONCURRENTLY` outside a transaction; no DEFAULT partition; recovery via `FINALIZE`; DDL role separate from application roles.
- If a hold prevents reclaim of a shared cohort, defer drop, record retained bytes and alert. A dedicated cohort for an identified long-retention/large request must be selected *before* loading, with independent approval; no destructive row migration after SEALED.
- Do not assume `RANGE(reclaim_epoch)` improves read performance automatically. It is a **lifecycle and data-safety choice**; read-locality is supplied by manifests, exact predicates, suitable indexes and optional admitted materializations.

## 6. Reproducible benchmark BEFORE approving permanent physical optimizations

### 6.1 Pre-registration / dataset

Before running W6, the product + financial owner, DBA, performance engineer and architecture reviewer **sign a benchmark manifest** with:
- machine CPU cores, RAM, disk class/IOPS, PostgreSQL minor version/config, connection pool and worker limits, existing OLTP traffic envelope, DB roles, statistics settings;
- real or privacy-safe representative tenant skew (small/large customers), 166-form coverage **only where implemented**, number/size of rows per form/metric, intervals/AS_OF, currency mix, time-series depth, revisions/retention, hot:cold read distribution and source updates;
- at least small/medium/large row-count scaling points **as explicit scenario parameters rather than invented production volumes**, plus a worst-case skew and legal-hold cohort;
- queries and expected financial values (business correctness goldens), exact snapshot/config/source SHA and reproducible fixtures;
- **owner-approved numeric budgets before trials** for OLTP p95/p99, analytics p95/p99, build+seal, recompute N→N+1, GC/DETACH, max WAL/disk, max child count, idle/pressure behavior and queue wait.

**No numerical thresholds are asserted as observed, nor invented on behalf of the business owner.** Benchmark without signed targets is descriptive, not a performance acceptance PASS.

### 6.2 Competing layouts (same hardware, same fixtures)

Compare side-by-side:
1. `DIRECT_PUBLISHED` no materialization, existing source access.
2. Unpartitioned `fin_agg` vs (when sized appropriately) fixed `HASH(organization_id)` and `RANGE(accounting_period)` aggregate alternatives.
3. Unpartitioned staging with indexed `DELETE`/cleanup vs `RANGE(reclaim_epoch)` weekly and daily candidates; optional fixed HASH workspace as read-only comparison (not treated as equivalent to cohort drop).
4. Physical `RANGE→HASH` and dedicated heavy cohort only **if** the preceding stages show a clear demonstrated read/GC bottleneck.
5. 1/2/4/8 admitted workers **under** controlled OLTP traffic, 1/2 instances, many tenants, hot-tenant skew, active readers and interrupted cleanup.

For each compare **full end-to-end** cost, not only isolated SELECT: source fetch, admission, plan, read, build/SEALED, method result if qualified, CAS, re-read, selective recompute and GC. Use `EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)`, `pg_stat_statements` with planning tracking, `pg_stat_activity`, relation/index sizes, WAL, locks, temp I/O, table statistics, partition pruning and p50/p95/p99 with confidence/variance. Warm and cold caches separately. Run sufficient randomized repeated trials to distinguish noise from benefit; publish raw samples and plans.

### 6.3 Decision and fail gates

- Financial correctness, immutability, tenant/scope/currency/form/calendar isolation, permit fail-closed and no active/held-cohort DROP are **absolute vetoes**, even if a layout is faster.
- Candidate partitioning is selected for a particular table **only if** it meets every approved OLTP budget, increases end-to-end throughput/recompute/GC outcomes on the target workload by a material, repeatable margin relative to the simplest correct baseline, and does not create a disproportionate operational burden (child count, planning time, WAL, disk/retention).
- If there is no significant demonstrated gain, retain **unpartitioned `fin_agg`** and **cohort-partitioned large ephemeral staging** only where deletion/retention economics justify it; small workspaces can use a bounded unpartitioned staging path if separately reviewed and safe.
- Any severe tenant skew, absence of partition pruning, planning p95 regression, high retained-byte accumulation due to shared holds or increased OLTP latency triggers design review—not unbounded partition growth or relaxed guards.
- Design/benchmark outcome is recorded in new versioned ADR/benchmark evidence with query plans, exact SHA and rollout/rollback; no hot mutation of published identities or child layout at W0.

## 7. W0, W1–W8 concrete gates

| Gate | Artifact and proof |
|---|---|
| **W0 freeze** | accept this ADR, logical keys + source security + retention class + physical defaults; approve benchmark owners and test scenarios; independent S2 architecture still required |
| **W1.2.A** | exact calendar/mapping/source key, tenant-scoped manifest/series resolver; no cross-grain joins; schema and immutable-key tests |
| **R2.B PLAN_ONLY** | pinned input/config/algorithm/period/source identities, no execution or stage writes from preview |
| **W2** | bounded scheduling and SQL/worker admission; multi-instance no-overload with query cancel/fence confirmation |
| **W3** | synthetic shadow/direct vs cached parity, small/large cohorts, no ACTUAL |
| **W4.1** | durable SEALED/result receipts, N+1 CAS with N readers; schema upgrades and collision tests |
| **W4.2** | operator-only precreator and safe GC; actual DDL test under holds, readers, crashes, multi-tenant cohort |
| **W6** | signed benchmark matrix and explicit per-table physical design choice, cold/warm + build/recompute/GC/OLTP impact; no fabricated 10× claim |
| **W7–W8** | least-privilege/RLS tests, populated upgrade, failover/cleanup runbooks, independent S2, RELEASE_HOLD until acceptance |

**Existing code truth:** current partition utility is inspection-only, and `fin_agg` SEALED cache + `fin_workspace` lifecycle partitions are target architecture, not an already measured or deployed result.

## 8. Documentation and upstream references

- [FIN HTAP Consolidated TZ](FIN-HTAP-CONSOLIDATED-TZ.md), [R2-07 Aggregate Layer](ADR-R2-07-VERSIONED-FINANCIAL-AGGREGATE-LAYER.md), [R2-08 Partition Lifecycle](ADR-R2-08-LIFECYCLE-PARTITION-RECALCULATION.md), [Form-166 Impact and GC](FIN-HTAP-FORM-166-INCREMENTAL-RECALCULATION-AND-PARTITION-LIFECYCLE-TZ.md).
- PostgreSQL 16: https://www.postgresql.org/docs/16/ddl-partitioning.html ; https://www.postgresql.org/docs/16/sql-altertable.html ; https://www.postgresql.org/docs/16/indexes-multicolumn.html ; https://www.postgresql.org/docs/16/planner-stats.html ; https://www.postgresql.org/docs/16/using-explain.html ; https://www.postgresql.org/docs/16/pgstatstatements.html .

**Never call architectural selection “benchmark-proven” or “production ready” before experimental evidence and an independent S2 decision.**
