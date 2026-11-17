# FIN HTAP — W0 Physical Freeze / W1.2.A implementation review and W2/W3/W6 readiness

**Date:** 2026-11-17  
**Branch:** `feat/fin-r0-r1a` only. Backend Java 21, Spring Boot, PostgreSQL 16, Flyway.  
**Status:** **W0 PHYSICAL DEFAULT DOCUMENTED / W1.2.A PARTIAL CODE / W2-W3-W6 RELEASE HOLD**. Independent architecture/security/financial S2 and same-HEAD CI must be checked separately; this document is internal S1, not their approval.
**Controlling design:** [FIN-HTAP-CONSOLIDATED-TZ](FIN-HTAP-CONSOLIDATED-TZ.md), [ADR-R2-13](ADR-R2-13-HTAP-PHYSICAL-LAYOUT-PERFORMANCE-FIRST.md), [W0–W8 implementation strategy](FIN-HTAP-IMPLEMENTATION-STRATEGY-W0-W8.md). Earlier physical options remain historical only when conflicting.

## 1. Code actually delivered in this incremental W0/W1.2.A slice

| Component | Path / evidence | Result / LIMIT |
|---|---|---|
| Exact logical identity | `backend/src/main/java/kz/fin/backend/intake/HtapFinancialSliceKey.java` | Immutable record pins tenant/entity/scope, published revision/hash, source revision, form/version, dataSpace/basis, metric/version, period type/coverage/timeKind, ISO calendar/version, unit/currency, dimension, mapping version/SHA. Construction validates shape only, **not** approval or security |
| Calendar pilot | `HtapIsoFinancialCalendar.java`, existing `ReportingPeriodV5` / `HtapCalculationPeriodPolicy` | Strict ISO calendar MONTH/PERIOD_ONLY/INTERVAL, contiguous months, AS_OF date kept separate. YTD/LTD, arbitrary fiscal/4-4-5, AS_OF→MONTH STOCK arithmetic **not qualified** |
| Mapping candidates | `V20261117_13__htap_mapping_candidate_and_published_slice_readmodel.sql` | Immutable proposed mapping versions with server-generated domain-separated PG16 JSONB SHA-256 and unique tenant/form/version/mappingVersion. `review_state=PROPOSED_UNREVIEWED` only; no approval, activation, catalog epoch or execution |
| Exact published facts | same migration: `fin_calc.htap_published_slice_candidate_v1` | Read-only `security_invoker` view joins published FactSet, committed outbox, selected assertions and approved source revisions; scope and lineage remain visible. **View is not ABAC** and carries constant `method_execution_permitted=false` |
| Indexed read path | existing `fact_selection` primary key and new `htap_mapping_candidate_version` unique/index keys | No new unmeasured index on populated source. Exact source query uses existing PK/joins; new large-table index requires EXPLAIN and concurrent deployment proof |
| Authorized resolver | `HtapPublishedSliceCandidateResolver.java` | Exact read-only query: FIN_READ on signed JWT boundary, matching publication/source/form/period/metric/currency/dimension, up to two matches to block ambiguity. Missing mapping blocked; present proposed mapping always `MAPPING_NOT_QUALIFIED` and `executable=false`. No REST, scheduler or ACTUAL route |
| Tests authored | `HtapFinancialSliceKeyCalendarTest`; `HtapPublishedSliceCandidateResolverPostgresTest` | Calendar/identity negatives, leap February, Jan→Mar gap, YTD/LTD, AS_OF, currency/hash; PostgreSQL16 publication+outbox, exact lookup, signed-claim mismatch, mapping SHA, immutable rows, no report write. **Green status only if recorded from workflows at final SHA** |

**Not implemented by this slice:** actual operator-approved `mapping_sha256` activation and catalog epoch, multi-revision TrustedComparisonSet, approved financial-calendar registry, method-specific form metric mapping, completed FinancialSeriesKey and aggregation policy, authoritative ResultKey or SEALED aggregate/physical stage manifests.

## 2. W0 financial correctness and physical design decision

The eight requested dimensions — organization, economic entity, business scope, form, accounting period, currency, mapping version, published revision — are a useful **logical routing subset** but insufficient for financial identity. Must additionally bind exact source selection and snapshot SHA, form version/source revision, dataSpace/reportingBasis, timeKind/PeriodType/Coverage and versioned calendar bounds, metric+version, dimension/population, unit/value state and aggregation/precision policy. One published FactSet may include multiple form revisions. Never map a SOURCE_AS_OF financial balance to interval FLOW implicitly; no ordinary SUM of closing STOCK or percent/ratio.

**Physical default (ADR-R2-13):** preserve historical `fin_fact`; unpartitioned persistent manifests; optional unpartitioned indexed `fin_agg` with DIRECT fallback; bounded `RANGE(reclaim_epoch)` only for high-volume disposable `fin_workspace` staging, with weekly cohort as *benchmark starting candidate*, not normative speed claim. No partition per tenant×form×period×currency×mapping×revision. No DDL/reaper or worker execution granted at W0. Current maintenance utility remains inspect-only.

**Performance uncertainty:** target data distribution, tenant skew, input cardinality, hot/cold ratio, PostgreSQL hardware and p95/p99 budgets are not yet supplied/owner-approved. Therefore neither a speedup nor definitive production partition interval can be claimed.

## 3. Open blockers BEFORE allowing W2 / runtime plans

| Priority | Blocker | Exact closure proof / owner |
|---|---|---|
| P0 | R2.2 trusted review actor, revocation, effective config method S2 and approved algorithm artifact SHA | Authenticated moderator identity, independent author/reviewer, CAS/revoke races; security+finance S2 |
| P0 | W1.2.A mapping activation epoch and exact semantic field/period/form mapping | Source-linked `schema_hash_v2`, `mapping_hash_v2`, monotonic `catalog_epoch`, compatibility protocol, source/version snapshots and negative same-values-different-mapping proof |
| P0 | Qualified `PeriodCalendar` / `FinancialSeriesKey` beyond pilot | complete PeriodType×Coverage×timeKind matrix with calendar policy; no leap/ISO week/fiscal cross-grain leakage; monthly close STOCK separately approved |
| P0 | Durable trusted source history | R2.B single-published-FactSet PLAN_ONLY first; composite multi-source history blocked pending W1.3 reviewed TrustedComparisonSet with exact per-point selected revisions |
| P0 | R2.B plan binding | deterministic PlanPreview/immutable PlanCommit pins config contract/effective SHA, deployment artifact SHA, source and S2; plan commit no tasks/reports, no legacy write bypass |
| P0 | W1.2.A PostgreSQL security and rollout | same SHA CI, fresh AND populated migration, least-privileged read/write grants, test two organizations/currencies/forms, no mapping forgery; current candidate resolver is **not** production input authority |
| P0 | W0 technical freeze and S2 | independent review of physical ADR, owner-defined budgets, source/tenant key, mapping identity and release HOLD; no unresolved P0 schema semantics |

### W2 readiness result: **NO-GO for governed execution now**

Legacy `SingleExecutorScheduler` remains production deny-all. There is no operational single mode `OFF|PLAN_ONLY|SYNTHETIC_SHADOW|WORKSPACE`, persistent global+tenant+class PREPARE/SQL/JAVA/CLEANUP admission, atomic lease/fence/quota claim, fair scheduling, query termination confirmation or OLTP reserve. Existing `TrustedAutoReportService` and `CompositeCoreReportPublisher` are independent write paths and MUST NOT be assumed permit-gated for new HTAP plans.

**W2 RED→GREEN:** 2 independent JVMs, 2 tenants, limit global SQL=2 / tenant SQL=1 *as synthetic test parameters, not production SLO*, no overclaim on lease timeout, stale fence cannot write/commit, quota rollback atomic, cancel unresponsive SQL leaves resources quarantined, HTTP/report writers remain isolated, deterministic recoverability. No ACTUAL yet.

### W3 readiness result: **CONDITIONAL for research-only SYNTHETIC; NO-GO for full controlled stage**

`Fin01RevenueMomSqlModule` already provides one read-only SYNTHETIC prototype, but selects using `LAG` over raw source and validates period/source consistency in Java after the SQL window. It is not bound to a reviewed multi-FactSet calendar, tenant scheduler or qualified method permit; `MethodModuleRegistry` does not provide production dispatch. W3 can start independent SQL-vs-Java synthetic golden experiments only without persisting trusted new output.

**W3 RED→GREEN:** exact contiguous period series in SQL pre-window, gaps/duplicates prevent `CALCULATED`; separate FLOW/STOCK/LEVEL/RATIO algebra; `DELTA, MoM, YoY, SMA, TTM, STDEV` only with versioned unit/calendar/precision policies and available inputs; p50/p95/p99 and SQL/Java result parity; no report/result receipt or ACTUAL. 16+ negatives in consolidated TZ, explicit financial reviewed goldens.

## 4. W6 comprehensive benchmark — engineering design, not yet results

**W6 implementation readiness: CONDITIONAL design GO for fixture preparation; NO-GO for acceptance conclusions or permanent physical optimization.** W2 admission and W3 baseline must function before end-to-end throughput comparisons have meaning.

**A/B alternatives on identical source fixtures and hardware:**
1. DIRECT_PUBLISHED no cache.
2. `fin_agg` unpartitioned vs fixed HASH(organization_id) vs RANGE(accounting_period) where legal financial semantics and volume justify materialization.
3. unpartitioned stage + bounded row cleanup vs `RANGE(reclaim_epoch)` weekly and daily cohorts with identical stage indexes.
4. optional nested RANGE→HASH and dedicated heavy-run child ONLY after observed bottleneck/partition-budget proof.
5. 1/2/4/8 worker concurrency, one/two JVMs, realistic tenant skew, multi-form/multi-currency, 1/2/N revisions, hot/cold input and mixed readers/cleanup/legal holds.

**Pre-registered workload manifest required** — owners (DBA, finance, architect, performance owner) sign before measurement: PostgreSQL CPU/RAM/storage/connection config and target load; source rows and distinct tenant/form/metric/period/version cardinalities; hot tenant share; source revision frequency; lifecycle/retention classes; cash/stock/flow ratio and actual supported 166-form coverage; sample sizes/runs and randomization; owner-defined OLTP p95/p99, analytics p95/p99, queue wait, build+seal+recompute+GC, WAL/disk/index bytes and max partition budget. Missing thresholds => descriptive experiment, **not PASS**.

**Instrumentation:** `EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)` for each query, `pg_stat_statements` including planning, `pg_stat_activity`, `pg_locks`, `pg_total_relation_size`, WAL/temp usage, memory pressure, read pruning, cold/warm cache and distribution across repeated randomized trials. Measure source read→admission→build→SEALED→method→CAS→re-read→targeted recompute→safe GC as one outcome.

**Decision vetoes:** wrong finance value, missing/duplicate period misclassified, cross-tenant/form/source/currency mix, ACTUAL without S2, mutable/premature SEALED result, stale-fence publish, any pinned/shared-cohort DROP, regression beyond signed OLTP envelope. Reject physical complexity if end-to-end improvement is not statistically/repeatably material. No fabricated 10× or arbitrary target.

## 5. Ordered exit criteria

- **W0 exit:** ADR-R2-13 adopted; key semantics and initial cohort/default design frozen; signed performance budgets and independent architecture S2 still needed.
- **W1.2.A exit:** complete trusted mapping epoch/activation and calendar policy; indexed exact source+series read; negative security/period tests, fresh + populated PostgreSQL 16 CI on one SHA, no ACTUAL.
- **R2.B/W1.3 exit:** immutable PLAN_ONLY with pinned exact verified source/config/algorithm, then multi-period trusted comparison selection, independent S2.
- **W2 exit:** fenced multi-worker admission, database concurrency/timeout/recovery and OLTP reserve proven.
- **W3 exit:** synthetic financial golden/parity and no trusted output side effects.
- **W4 exit:** immutable result/complete report head CAS; safe lifecycle partition operations after DBA gate.
- **W6 exit:** owner-signed measured physical design comparison, reproducible raw evidence and acceptance or explicit rejected alternatives.
- **W8:** only separate independent method/security/design S2 and explicit release authorization can allow ACTUAL.

**Decision:** continue W0/W1.2.A backend hardening now; **do not** start new persistent HTAP calculation/ACTUAL or destructive workspace maintenance. Code added in this slice is an internal candidate read path, not full finance qualification.
