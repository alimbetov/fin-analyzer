# FIN HTAP — Consolidation Technical Audit (2026-11-17)

**Status:** INTERNAL ARCHITECTURE + CODE-BACKED REVIEW / INDEPENDENT S2 PENDING  
**Working branch:** `feat/fin-r0-r1a` only. **Baseline SHA:** `eef1360aee1b39fa4ba590fb1e472592732c238e`.  
**Normative resolution:** [FIN-HTAP-CONSOLIDATED-TZ.md](FIN-HTAP-CONSOLIDATED-TZ.md).  
**Scope:** ADR-R2-05..09; FIN-BE-R2-W v1.2; R2 old acceptance and supersession ledger; live PostgreSQL V1/V2 and fin_exec migrations; AutoIndicatorEngine, Fin01RevenueMomSqlModule, SingleExecutorScheduler, MethodModuleRegistry, WorkspacePartitionMaintenanceUtility; supplied reference `TablePartitionService` for design comparison. Audit records observed facts separately from design hypotheses. It is not independent S2 or measured performance proof.

## Baseline and limitations

- Current `fin_fact.fact_assertion` stores `time_kind=AS_OF|INTERVAL`, `start_inclusive/end_exclusive`, `metric_code/version`, unit/currency, entity/scope and dimensions, **but no canonical `PeriodType=MONTH|QUARTER|YEAR` column**. A period-grain/fiscal-calendar mapping and completeness proof are required before calculating grouped windows.
- Trusted `form_code/form_version` is attached to `approved_source_revision` and joined via `fact_assertion.source_revision_id`; it is not a native field in `fact_assertion`. One published FactSet may include selected facts from different forms. Form-based slicing must not destroy legitimate separately-qualified cross-form methods.
- `AutoIndicatorEngine.compare`, `movingAverage`, `aggregate`, `shareByDimension`, `ratioOf` are existing Java prototypes with typed source keys and safety checks. Existing tests cover multiple negative cases. They are not a SQL window backend, licensed source, method S2 or full HTAP orchestration. Moving average currently scans observations repeatedly per anchor/window and is not pre-aggregated or incremental.
- `Fin01RevenueMomSqlModule` is read-only, REPEATABLE_READ and SYNTHETIC-only, guarded against unknown source; currently runs directly over published FactSet joins and checks currency/form/period in Java **after** SQL `lag`. It does **not** yet use partitioned financial slices, `fin_agg`, immutable workspace or the new scheduler. SQL `lag ORDER BY start_inclusive,id` alone is not a strict series partitioning generalizable to other forms, tenants or currencies.
- `MethodModuleRegistry` is a legacy metadata read-only bridge for seven adapters; it does not route the FIN-01 SQL candidate to production execution. `SingleExecutorScheduler.tick` runs max one task and uses the old queue and feature flag, not new fair capacity admission.
- `WorkspacePartitionMaintenanceUtility` is a **read-only inspector** for two allowlisted future `fin_workspace` parent names; `previewRetirement` never approves or executes DROP, and no W1D physical schema/GC catalogue is deployed.
- Existing `FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ` has accumulated v1.1 sections 0.A..0.F and historical sections with different physical candidates (`HASH(workspace_id)` vs `RANGE(reclaim_epoch)`) and cleanup mechanisms (`DELETE` vs `DETACH/DROP`). They are not interchangeable.
- PostgreSQL 16: partitioned parent UNIQUE/PK must contain every partition key; `DETACH ... CONCURRENTLY` cannot be inside an explicit transaction block or used with a DEFAULT partition and an interrupted detach may need `FINALIZE`. These requirements match the official PostgreSQL 16 DDL docs; no measured performance advantage is implied.

## Review findings and resolutions

| ID | Priority | Evidence/defect | Consolidated requirement / closure |
|---|---|---|---|
| HTAP-REV-01 | P0 | Five-axis business key appears alongside a different DB physical partition key. Could be misread as 1 table per tenant/form/period/currency. | Canonical point key **Tenant + FormCode + PeriodType + Period + Currency**. Rich `SourceKey` and sealed `SeriesKey`; physical `RANGE(reclaim_epoch)` only for disposable lifecycle cohorts, fixed HASH/unpartitioned as measured alternatives. No physical DDL per tuple. |
| HTAP-REV-02 | P0 | `time_kind` exists, canonical `PeriodType` absent from V1/V2. Mixing MONTH, QUARTER, AS_OF and fiscal buckets is possible if derivation is unqualified. | Versioned `PeriodCalendar` with explicit grain, exact ordinal, start/end, calendar_id/version, completeness; no inferred monthly frequency from row order. Unit tests on 4-4-5/53-week only after separately reviewed semantics. |
| HTAP-REV-03 | P0 | Requested isolation includes Period while SQL windows must span periods. | Period in **point** identity, omitted from **series grouping**; every other invariant coordinate retained in SQL `PARTITION BY`, Java comparator and population hash. SQL `LAG/ROWS` require adjacency/duplicate/missing checks; a physical child partition cannot authorize a row. |
| HTAP-REV-04 | P0 | Currency often regarded as a display dimension; nonmoney uses NULL. `PCT` output can lose source currency. | Currency equality required for all money inputs, even if output dimensionless; SQL and Java enforce same currency, dataSpace, basis and exact source. No implicit FX. `currency_code IS NULL` is only typed nonmoney, never wildcard. Explicit FX is a separate versioned rated-input transform. |
| HTAP-REV-05 | P0 | Form provenance is external to assertion. A form-only derived aggregate can silently lose source version or cross-form method provenance. | Join `approved_source_revision`, pin source/form versions, qualified source and metric identities. Ordinary indicators never mix forms; cross-form financial methods have explicit reviewed multi-input bindings and output lineage. |
| HTAP-REV-06 | P0 | Workspace-derived rows have no schema/manifest yet; proposed shared cache risks partial population or cross-tenant contamination. | Tenant-scoped composite FK, source+selection+policy hash, CAS SEALED generation, immutable sealed input, independent count/content/coverage proof; read-time ABAC. Missing/mixed/estimated ACTUAL fail closed. |
| HTAP-REV-07 | P0 | Proposed `DETACH/DROP` contradicted historical “never DROP” and some designs use shared HASH partition. | DROP only complete disposable `reclaim_epoch` cohort after **all** members retire and SQL readers terminate; immutable `fin_fact` / results / pinned `fin_agg` excluded. Atomic generation switch before deferred GC; interrupted DETACH recovery; no DEFAULT parent if CONCURRENTLY. |
| HTAP-REV-08 | P0 | Provided `TablePartitionService` example does `DROP TABLE` of attached daily child inside short transactions; includes dynamic schema/table arguments and date-only cutoff, and intentionally omits CONCURRENTLY. | **DO NOT copy or wire as-is** to FIN. Reuse ideas of finite retry/backoff, metadata inspection, metrics/dry-run only; destructive FIN reaper requires allowlisted parent, durable membership/drain barrier, confirmed SQL liveness, dedicated admin role and autocommit `DETACH CONCURRENTLY`/FINALIZE then verified DROP. |
| HTAP-REV-09 | P0 | Old scheduler `autoEligible()` and new W execution mode have separate rules; flag collision could run tasks twice. | One effective `OFF|LEGACY_TEST|WORKSPACE_SHADOW|WORKSPACE` mode, OFF by default; hard fail on conflicting old flags; single outbox and scheduler. ExecutionPermit separately gates every ACTUAL candidate. |
| HTAP-REV-10 | P0 | Multiple materialization/SQL/cleanup workers could exhaust DB without shared quotas. | Transactional capacity/admission across PREPARE/SQL/JAVA/CLEANUP, global+tenant budgets and pool reserve; no release until worker physically terminated; fencing stale commits, fair scheduling and multi-node tests. |
| HTAP-REV-11 | P0 | SQL `avg` and `stddev` may not match BigDecimal rounding, SQL ROWS can skip missing periods, ratio/stddev zero denominators need explicit policy. | Per-indicator mathematical descriptor with window length, sample/pop N, HALF_EVEN to exact scale, 0/missing/negative/ESTIMATED semantics, prior-only z-score; independent SQL/Java golden tests. |
| HTAP-REV-12 | P1 | Several docs name different implementable phases / M0–M6 and W0–W8, W1A, W1D, W3A, W6D. | Single W0–W8 implementation roadmap; suffixes A/D become acceptance suites only, not independent schedulers/releases; old M1–M3 acceptance gates retained. |
| HTAP-REV-13 | P1 | No hard threshold for “aggregation accelerates computation”, shared fin_agg could worsen write amplification. | Benchmark DIRECT, unpartitioned AGG, fixed HASH AGG, lifecycle RANGE staging. Include setup + reuse count + cleanup + WAL + locking + OLTP p95, not only warm SQL query time. Permit direct mode for small/one-off workloads. |
| HTAP-REV-14 | P1 | Partition inspector only checks child name/catalog. A matching date suffix could be incorrectly interpreted as permission to DROP. | Inspector remains non-destructive. Separate dedicated reaper in W4D after durable ledger and S2, no client-controlled table name/DDL. |
| HTAP-REV-15 | P1 | No single entrypoint making prior ADR/TZ precedence explicit. | Consolidated TZ is **normative implementation entrypoint**; ADR-05..09 remain historical rationale, stricter safety prevails, outdated execution cleanup/physical choices explicitly superseded, old docs are retained. |
| HTAP-REV-16 | P1 | R2 safety gates and financial readiness are separate, but terminology HTAP could imply finished product. | Correctness/engineering vs financial S2 vs ACTUAL authorization vs release are independent; `RELEASE_HOLD` remains. |

## Additional cross-period correctness findings (re-review of consolidated v1.0)

The initial 16-finding audit was extended after validating the data model and existing Java/SQL pilot:

| ID | Priority | Additional correctness defect | v1.1 resolution and required RED |
|---|---|---|---|
| HTAP-REV-17 | **P0** | A report period can use a different approved `source_revision_id` than the prior month. The first consolidated text inadvertently pinned the per-point revision in the **series grouping**, which would reject valid monthly history or tempt unsafe bypass. Several months may be within one published FactSet; a separately approved history can span multiple published FactSets. Current `Fin01RevenueMomSqlModule` explicitly requires sourceRevisionId equality, so its old pilot must **not** silently be generalized. | `TrustedComparisonSet` (single published snapshot first; multi-published mode separately gated) fixes selected-point lineage and one canonical value per period; series pins complete comparison-set hash, not individual source-revision IDs. Tests `HTAP-HISTORY-01..05`; FIN-01 widening requires a separately versioned method and Java parity. |
| HTAP-REV-18 | **P0** | Existing `time_kind=AS_OF` stock snapshots can be monthly end-of-month observations. If `PeriodType=AS_OF_DATE` is treated as the only stock type, a legitimate monthly closing-balance moving average becomes ill-defined. | A `MONTH/AS_OF` qualified closing sample retains its month cadence and exact AS_OF closing date. Never treat it as FLOW or sum STOCK; arbitrary AS_OF_DATE requires a separate alignment policy. Tests `HTAP-CLOSE-01..04`. |

**Current review count:** **18 issues (13 P0, 5 P1)**. All have **specification-level dispositions** in [Consolidated TZ v1.1](FIN-HTAP-CONSOLIDATED-TZ.md). This does not mean any P0 **runtime defect is fixed**: schema/code implementation, test evidence and independent S2 remain open. In particular, existing FIN-01 pilot's source-revision constraint is preserved pending method-specific versioned proof.

## Design closure matrix

**Resolved at specification level:** logical vs physical partition, five-axis key and per-point lineage vs versioned comparison-set identity, monthly AS_OF closing cadence, period-aware math, currency, ordinary vs multi-form calculations, exclusive-cohort DROP model, one scheduler/admission, stage ordering and tests.  
**Open until independently tested:** actual PeriodCalendar mapping/DDL, archive/replay retention and date bucket size, tenant/DB privileges RLS, physical table design and choice by benchmark, pooled-connection security, capacity claims, autoindicator SQL/Java parity, DETACH reaper, real loads, independent financial S2.  
**No claim:** code implementation of the consolidated design, finished 60 methodologies, production enablement, any observed speedup or completion of independent S2.

## Mandatory release evidence

| Gate | Evidence |
|---|---|
| W0 | Approved consolidated spec, negative threat model, schema privilege policy, versioned calendar/source/row hash contracts, independent architecture S2 |
| W1 | PostgreSQL 16 Testcontainers migration fresh+upgrade, `fin_workspace`/optional `fin_agg` sealed source, exact tenant and five-axis slice isolation, no partial data |
| W2 | Multiple worker/JVM tests, atomic capacity claims, quotas never oversubscribed, no-starvation within admitted capacity, SQL cancellation |
| W3 | Same published SYNTHETIC FIN-01 input, SQL/Java exact canonical parity, DELTA/MoM/SMA/standard deviation negative/positive goldens |
| W4 | One immutable result per logical result key, stale fence blocked, generation switch atomic, safe reaper with interrupted DETACH recovery |
| W5 | One permitted DAG over qualified method variants, no duplicate outbox/task path, cross-form reviewed joins only |
| W6 | Realistic workloads cold/warm, 1/2/4/8 SQL workers, tenant skew, aggregate ROI and DROP-vs-DELETE including OLTP p95 and WAL |
| W7 | Runtime DB roles, ABAC/RLS, operator audit, pressure shed, legal hold, retention and reaper quotas |
| W8 | Same SHA, FIN CI + Backend Domain green, negative PostgreSQL Testcontainers, independent architecture and method S2, separate ACTUAL/release approvals |

**Test baseline observed:** `FIN Backend Domain` run 44016323811 at SHA `da2d479f...` succeeded after partition inspector addition. `FIN CI` run 44016658295 at SHA `b37911e47...` succeeded for documentation/code baseline. Neither run proves the new consolidated specification has been implemented. For the next commit re-check exact HEAD and independent CI.

**PostgreSQL official docs:** https://www.postgresql.org/docs/16/ddl-partitioning.html ; https://www.postgresql.org/docs/16/sql-altertable.html ; https://www.postgresql.org/docs/16/functions-window.html .
