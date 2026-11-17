# FIN HTAP — Independent-Pending Technical Review of Implementation Strategy W0–W8

**Date:** 2026-11-17 · **Status:** S1 INTERNAL ARCHITECTURE REVIEW COMPLETE / INDEPENDENT S2 NOT PERFORMED  
**Reviewed GitHub HEAD:** `4963b76813a5154a70b738ff54d398b12619d251` · **Sole working branch:** `feat/fin-r0-r1a`  
**Normative amended sequence:** [FIN-HTAP-IMPLEMENTATION-STRATEGY-W0-W8.md](FIN-HTAP-IMPLEMENTATION-STRATEGY-W0-W8.md). **Parent specification:** [FIN-HTAP-CONSOLIDATED-TZ.md](FIN-HTAP-CONSOLIDATED-TZ.md).  
**Source evidence:** actually inspected Java, PostgreSQL migrations, immutable outbox, period model, synthetic FIN-01, legacy scheduler and partition inspector. Do not mistake a proposed contract for implemented code.
**CI evidence at reviewed HEAD:** [FIN CI run 44021438326](https://github.com/alimbetov/fin-analyzer/actions/runs/44021438326) = SUCCESS; [Backend Domain run 44021254124](https://github.com/alimbetov/fin-analyzer/actions/runs/44021254124) = SUCCESS on code SHA `0ccab3e69352f6d883cbfbd8f15c0f979ceb64b4` (ancestor of reviewed HEAD). Tests exercised PostgreSQL 16 Testcontainers and 166 catalog-form workflows. This **does not** establish dedicated upgrade-from-existing-data fixture, S2, W1.2 consumer, aggregate tables, SQL batch executor, or partition reaper.
**Release:** HOLD / no unqualified ACTUAL calculation.

## A. Review decision

**Architecture is coherent and worth continuing, with targeted corrections; do not restart the project or introduce new components.** `Java Orchestrator + PostgreSQL set-based SQL/JAVA dual engine + immutable source/result + logical five-axis financial slice + bounded lifecycle partition cohorts` is the right direction for this codebase. W1.1's implementation is GREEN in the observed CI. **W1.2 should not commence without the explicit contract fixes below.** The greatest immediate risk is *incorrect scope or semantics of recalculation*, not SQL throughput.

## B. Findings — blockers for the relevant future gates

| ID | Severity | What actual source/spec establishes | Required change and test |
|---|---|---|---|
| HTAP-STR-01 | **P0 / before W1.2** | `ReportingPeriodV5.Type` has `WEEK, MONTH, QUARTER, HALF_YEAR, YEAR, AS_OF`; `Coverage` has `PERIOD_ONLY, YEAR_TO_DATE, LIFE_TO_DATE, AS_OF`. Current consolidated indicator spec mainly discusses MONTH/QUARTER/YEAR/AS_OF_DATE/FISCAL_PERIOD; treating Year-to-date flow as single-period month is dangerous. | Define total, lossless **InputPeriodType+Coverage → CalculationPeriodPolicy** mapping. **MONTH/PERIOD_ONLY** SYNTHETIC pilot first; other source kinds return `BLOCKED_UNSUPPORTED_PERIOD_POLICY` until dedicated prior-year calendar/coverage and metric method approval. No silent recoding of `AS_OF` to interval. `PERIOD-WEEK/HALF/YTD/LTD` negative tests. |
| HTAP-STR-02 | **P0 / before global catalog activation** | Existing `CatalogXlsxRevisionService.confirm` NO_OP checks legacy `normalized_sha`, **ordered** `normalized_rows` and `document_sha`; it does **not** compare prior/current `schema_fingerprint`, global schema activation version or `mapping_hash`. `schemaFingerprint` hashes fields/types/grain but not all future accounting policy. | New global catalog epoch and versioned `schema_hash/mapping_hash` are required before saying that a matching form body is safe. On changed schema/mapping: **block old issue/upload/confirm until version-compatibility decision** or record a separately qualified new revision; no silent NO_OP. RED tests: unchanged values with changed schema; mapping changed with unchanged rows; mixed-version application nodes. Do **not** rewrite old v1 hashes. |
| HTAP-STR-03 | **P0 / scope decision before W1.2** | W1.1 event emission exists only in `CatalogXlsxRevisionService.confirm`; separate `FormDraftRevisionV5Service.confirm` writes `fin_intake.draft_revision_v5` for F02 and has no `fin_exec.form_change_journal` insert. | Explicitly distinguish 166 catalog XLSX flow and F02/V5 flow. **Do not claim every project form-edit event is captured**. Determine whether V5/F02 must feed same impact ledger; if yes, use an **additive separately typed event** with exact provenance and idempotency (not false `TENANT_FORM_REVISION_CONFIRMED` with nonexistent catalog slot FK). Test positive/no-op/concurrency/rollback for both paths. |
| HTAP-STR-04 | **P0 / before W1.3** | FIN-01 current `Fin01RevenueMomSqlModule` and applicability test `conflictingApprovedSourceRevisionsMustNotBecomeComparableRevenueInput` strictly reject adjacent values from different approved source revision IDs. Consolidated `TrustedComparisonSet` as designed may allow reviewed multi-source histories. | Preserve existing FIN-01 v1 semantics and tests. A new explicitly versioned FIN-01/multi-source variant may use a **SEALED trusted comparison set with one approved selected assertion per period** only after dedicated RED/Java/SQL golden and financial S2. Never weaken old test in place. |
| HTAP-STR-05 | **P0 / before W1.2 consumer** | W1.1 new `fin_exec.form_change_journal` is append-only with `UNIQUE(revision_id)` and composite slot FK, but has no consumer, delivery state, retry cursor, dead-letter process or tenant-safe impact planner. The existing immutable publication outbox is `FACT_SET_PUBLISHED`-only. | Build **read-only impact-preview consumer** with a **separate** `form_change_delivery` checkpoint keyed by event ID and explicit tenant+slot+revision join, at-least-once processing and idempotent impact fingerprint. Under concurrent 2-node scans prevent duplicate logical impact writes. Keep draft events incapable of creating `analysis_run`/approved result. No second scheduler. |
| HTAP-STR-06 | **P0 / before W3 persistent results** | Strategy W3.1 SQL pilot precedes W4.1 generation/results/leases; W3 pilot could accidentally write “trusted” outputs before required immutable/fencing path exists. | W3.1 is **SYNTHETIC read-only/SHADOW golden only** (or attempt-local disposable test result, not CORE/current). Persisted and promoted method results are prohibited until W4.1 source pins, CAS generation and immutable receipt pass. FIN-01 must remain ACTUAL HOLD. |
| HTAP-STR-07 | **P0 / before partition DROP** | `WorkspacePartitionMaintenanceUtility` is a read-only `pg_catalog` inspector, not a cohort-membership/reader-liveness proof; `RANGE(reclaim_epoch)` design can contain many tenants/slices. | Partition lifetime cohorts MUST have complete membership, referenced generations, reader/lease drain barrier and confirmed physical SQL stop. Only approved privileged reaper `DETACH CONCURRENTLY` on autocommit connection → audited DROP detached child after catalog reconciliation. No `DEFAULT`, no DDL from worker; mixed-cohort legal-hold test. PostgreSQL 16 parent PK/UNIQUE includes partition key; FK/DDL parent locks analyzed. |
| HTAP-STR-08 | **P0 / before W2 or W3 activation** | Existing `SingleExecutorScheduler` is not tenant-aware and legacy `analysis_run.event_id`/`analysis_task(run_id,method_code)` uniqueness constrains reanalysis. The new form journal has no execution authority. | Require one effective execution mode OFF/SHADOW/WORKSPACE with legacy flag collision startup hard-fail; separate versioned `PlanKey/ResultKey` instead of recycling event IDs; atomic task claim+global/tenant/stage capacity and fencing. SQL/PLpgSQL routine is only an allowlisted calculation worker, never a second scheduler or dynamic SQL text executor. |
| HTAP-STR-09 | **P1 / before W1.2** | `FinancialReportContentIdentity.VERSION=FIN-SNAPSHOT-CONTENT-V2` already implements tenant/source-context-bound order-sensitive SHA-256 for snapshots, while old catalog `normalized_sha` is order-insensitive. Separate proposed v2 canonical forms can fragment identity and generate false recomputation. | Treat existing **report snapshot** V2 as reference vectors, not automatically identical to **form semantic** hash v2; specify exact inclusion/exclusion of `sourceInputLocale`, document SHA, form mapping version and normalization. Dedicated cross-language canonical encoding and migration/coexistence tests. |
| HTAP-STR-10 | **P1 / before production W1.2** | W1.1 tests exercise fresh PostgreSQL with Flyway. An **upgrade-from-populated prior schema** and explicit application/maintenance role grant/deny matrix were not separately evidenced by reviewed runs. | Additional fixture: populate V20261116_13 schema, migrate additively, verify immutable history and exact permissions; deny ordinary worker role UPDATE/DELETE on journal and DDL on `fin_workspace`. Do not retroactively label unrun tests PASS. |
| HTAP-STR-11 | **P1 / before W4 DDL freeze** | `WorkspacePartitionMaintenanceUtility.isManagedName` accepts only child names with `_YYYYMMDD`, while design discusses daily/weekly cohorts and retention classes; no child naming contract is frozen. | Define single versioned physical catalog/partition name and bound policy with size class, epoch, parent OID and retention bucket; validate actual `pg_catalog` bounds rather than suffix. Inspector can remain read-only; reject unsupported layout. |
| HTAP-STR-12 | **P1 / before W6** | Strategy lists 1/2/4/8 workers and compare DIRECT/HASH/RANGE, but baseline dataset sizes, HOT/WARM cache, target OLTP service SLO and break-even ROI thresholds are not frozen. | W6 measurement plan before optimizing: seed multiple tenants and 166-form distribution, use realistic skew, end-to-end build+compute+recompute+retire including WAL/locks; record OLTP p95/p99, queue fairness, DB resources and blocked-cohort retained bytes. Direct execution remains option when aggregate preparation is not beneficial. |
| HTAP-STR-13 | **P1 / before W1.2** | One form can carry several metrics/dimensions and a report may join multiple approved form sources. A bare 5-axis key is not an authorized unique metric/series identifier. | In every impact lookup and ResultKey retain metric/version, economic entity/scope, basis/dataSpace, form version, dimension, unit, trusted selection/calendars and permitted cross-form binding. `Period` is **point key**, not SQL window grouping. Add negative cross-form/mixed currency tests. |
| HTAP-STR-14 | **P1 / before W4 production head** | One output method could finish before other required DAG nodes; promoting a “current report” pointer too early exposes mixed generations. | Promote **complete required-result-set manifest** for report head atomically, after all mandatory nodes SEALED/receipt validated; optional nodes carry individual statuses. No partial cross-generation report publication. |
| HTAP-STR-15 | **P1 / before W1.2** | Draft event references `revision_id` and `slot_id` without copying all form fields, which is sound only while both referenced records remain immutable and joins are scoped; current migrations preserve those rows. | At event read time verify `organization_id + revision_id + slot_id`, immutable draft and project ownership, exact form/period/coverage/currency/schema provenance. Treat any orphan/mismatch as quarantined integrity breach. Avoid exposing private source in general metrics. |
| HTAP-STR-16 | **P1 / before W8** | Existing CI success validates W1.1 code and regression on HEAD, not the future SQL batch, partition GC, financial S2, ACTUAL or full upgrade testing. | Per-stage signed-like evidence manifest: exact SHA, PostgreSQL version, migration head, test IDs, CI URLs, S2 approver, method approval, pressure results, blocked tests. Do not conflate workflow GREEN with approval to release. |

**Review count:** **8 P0 / 8 P1**, where **P0 refers to an unresolved release/next-stage precondition**, not necessarily a presently exploitable runtime bug. **Current W1.1 functionality is GREEN** in reviewed GitHub CI; there is no code evidence that a destructive partition reaper or new SQL batch execution is live.

## C. Required strategy changes by gate (no parallel uncontrolled features)

### Gate W1.1 — retain GREEN, closure not proof of entire HTAP
- Keep `V20261117_01`, service emission and 166-source Testcontainers regression unchanged. Journal schema is append-only and event is `CONFIRMED_UNQUALIFIED_DRAFT`, not trusted FactSet.
- Preserve no-op checks using **both** legacy SHA and ordered normalized rows and document hash. Do not rewrite old migrations or add a second event for duplicate confirmation.
- Add independently evidenced **upgrade-from-populated-old-schema** test in W1.2 hardening; no retroactive failure of W1.1 code just because a new review proposes a stricter operational gate.

### Gate W1.2.A — source and period contract freeze (before impact code)
- Inventory both catalog XLSX and F02/V5 modification channels; decide if F02 events enter HTAP and specify a typed adapter if so.
- Lock supported `(PeriodType,Coverage,TimeKind,CalendarVersion)` matrix. First supported method case: `MONTH/PERIOD_ONLY/INTERVAL` same calendar and currency; `WEEK/HALF_YEAR/YTD/LTD/AS_OF` are validated as source but **not treated as monthly flow** until separately approved method contracts.
- Define schema/mapping/catalog-epoch and form-source uniqueness; zero change in rows does **not** mean no change in mapping.
- Define event read DTO with immutable revision/slot/project provenance, source status, tenant and exact 5-axis coordinates plus complete context.

### Gate W1.2.B — safe read-only change-impact preview
- RED Testcontainers: tenant A/B, same form-period/currency, slot/revision forgery, duplicate event replay, stale cursor/2 consumers, and no extra `analysis_run`.
- Add **delivery/checkpoint table**, typed index-only versioned method bindings and read-only ImpactPreview `NO_OP, DRAFT_CHANGED, WAITING_PUBLICATION, WAITING_QUALIFICATION, BLOCKED`; no calls to SQL calculator or trusted result promoter.
- Resolve dependency target periods only with a qualified calendar and explicit bounded window policy; unknown horizon → BLOCKED/FULL_SERIES_REBUILD admission, never guessed historical `LAG` adjacency.
- Keep `ACTUAL` and old execution mode OFF/HOLD.

### Gate W1.3 — source and mathematical safety
- Exact trusted published FactSet and qualified source selection; per-period immutable approval lineage vs whole-series sealed comparison hash.
- No silent relaxation of FIN-01's existing strict sourceRevision equality; any multi-source history is separate descriptor/version and signed off.
- Calculation policy distinguishes FLOW interval, STOCK `AS_OF`, YTD/LTD totals, mixed currency/FX and period granularity.
- Seal and CAS generation with explicit full result status before considering persistent results.

### Gates W2–W4 — execution, storage, GC
- W2 resource admission, physical query cancellation and fencing **before** any automatic method execution.
- W3 SQL experiment remains synthetic-only **shadow/read-only**; new SQL result persists only after W4.1 immutable receipt/generation path. PostgreSQL routine code in reviewed Flyway and immutable manifest **metadata**, no raw dynamic SQL table.
- W4.2 physical partition DDL **after** S2-reviewed catalog, liveness and crash/recovery RED suite. Reuse current inspector for preflight only. No on-demand DDL on form HTTP writes.

### Gates W5–W8 — acceptance
- Cross-form DAG dependency contracts are versioned, tenant/scoped, coverage-aware and source-qualified.
- W6 predeclare latency/throughput/OLTP impact and cleanup thresholds; results include confidence/repetition.
- W7 least-privileged DB accounts before production modes and any DDL permissions; W8 full same-SHA CI + independent S2 and separate ACTUAL permit release.

## D. Concrete next engineering ticket (do not start database reaper)

**W1.2.A1 — Source Contract and Coverage Matrix:**
1. Add immutable read-model contract for `CatalogXlsxRevisionService` event, including `organization_id`, project, slot/revision, exact form/periodType/period/coverage/currency, source schema SHA and `CONFIRMED_UNQUALIFIED_DRAFT`.
2. Make `PeriodType`/Coverage semantics explicit and return `BLOCKED_UNSUPPORTED_PERIOD_POLICY` for unsupported dynamic calculation cases. Demonstrate ISO WEEK/HALF_YEAR/YTD/LTD/AS_OF in unit + PostgreSQL fixtures.
3. Add catalog definition-version compatibility and schema fingerprint test: old version changed mapping, same numeric cells -> not an unsafe numeric NO_OP. An old draft may be returned as existing historical source but must be marked `INCOMPATIBLE_CATALOG_EPOCH` for new current calculation.
4. Resolve F02/V5 second path scope before claiming “every form update” in delivery dashboards.
5. RED first, then small GREEN, independent S2. For now **no destructive SQL, no form-driven ACTUAL execution and no new scheduler**.

**Decision:** **GO for W1.2.A design/RED tests**; **HOLD** for active W1.2 consumers until trusted period/form mapping validated, and **HOLD** for W2/W3 method activation, W4 DDL reaper and all ACTUAL.

## E. References

- [PostgreSQL 16 partitioning](https://www.postgresql.org/docs/16/ddl-partitioning.html)
- [PostgreSQL 16 ALTER TABLE DETACH/FINALIZE](https://www.postgresql.org/docs/16/sql-altertable.html)
- [PostgreSQL 16 CREATE TABLE constraints](https://www.postgresql.org/docs/16/sql-createtable.html)
