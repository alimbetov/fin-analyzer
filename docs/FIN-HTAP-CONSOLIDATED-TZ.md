# FIN HTAP — Consolidated Technical Specification v1.2

**Project:** FIN Analyzer · `alimbetov/fin-analyzer` · only branch `feat/fin-r0-r1a`  
**Date:** 2026-11-17 · **Revision:** v1.2 (post FIN-MR-R2.1 readiness and sequencing hardening) · **Decision status:** CONSOLIDATED DESIGN BASELINE / R2.2 + W1.2.A CONTRACT GO / INDEPENDENT S2 AND HTAP RELEASE HOLD  
**Technology:** Java 21, Spring Boot, PostgreSQL 16, Flyway; Backend only.  
**Audit:** [FIN-HTAP-CONSOLIDATION-TECHNICAL-AUDIT-2026-11-17](FIN-HTAP-CONSOLIDATION-TECHNICAL-AUDIT-2026-11-17.md). **Historical consolidation baseline:** `eef1360aee1b39fa4ba590fb1e472592732c238e`. **Post-R2.1 audit baseline:** `45d3f37a895ae73d3934ae9979495d28c1b1626b`.  
**Supersedes as implementation authority:** execution, workspace, aggregation, derived metrics and physical partition implementation strategies scattered across [ADR-R2-05](ADR-R2-05-METHOD-MODULE-DUAL-ENGINE-IMMUTABLE-RESULTS.md), [ADR-R2-06](ADR-R2-06-TENANT-AWARE-CALCULATION-WORKSPACE.md), [ADR-R2-07](ADR-R2-07-VERSIONED-FINANCIAL-AGGREGATE-LAYER.md), [ADR-R2-08](ADR-R2-08-LIFECYCLE-PARTITION-RECALCULATION.md), [ADR-R2-09](ADR-R2-09-HTAP-DERIVED-INDICATORS-PERIOD-CURRENCY-ISOLATION.md), and the old [FIN-BE-R2-W](FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ.md). They remain cited rationale and historical acceptance evidence. **Never weaken their stricter trust, permission, data-space, audit, financial S2, immutable history or RELEASE_HOLD controls.**  
**Terminology:** FIN HTAP is a proposed hybrid OLTP/OLAP *application architecture* on PostgreSQL, **not** a claim that PostgreSQL automatically isolates analytics or that 60 methodologies are production-qualified.

## S1 hard pre-implementation re-review — W0 code and additional safety findings

[Independent-pending hard review](FIN-HTAP-HARD-PREIMPLEMENTATION-TECHNICAL-REVIEW-2026-11-17.md) verifies the code entrypoints and records 16 gate-relative findings with executable negative/positive tests. **This is internal S1 work, not an independent S2 approval.** Work stays in `feat/fin-r0-r1a`; no production ACTUAL or HTAP feature flag is enabled.

**Code hardening after the R2.1 audit:** `MethodologyCatalogReadService` reads evidence-aware SQL view status rather than stale immutable family seed, and `MethodConfigVersionEntity.getParameters()` returns recursively immutable JSON (including null) on commit `6b543f2`. New forward-only `V20261117_08` blocks unaudited `DELETE` and `V20261117_09` blocks method/variant key rewriting of `fin_calc.method_active_config`; no existing method approval, review or activation row is rewritten. A future method-config **deactivation/revocation** requires an explicit authenticated CAS operation and immutable event, not silent pointer removal. Test proofs must be checked at the final SHA.

**Additional blocking interfaces:** the existing R1 `TrustedAutoReportService.calculateAndPublish()` and `CompositeCoreReportPublisher.publishReady()` are separate write-capable service entrypoints; the R2.0.1 legacy SingleExecutor deny-all is **not** a generic permit automatically inherited by either. Preserve the existing trusted R1 functionality, but block new HTAP plans from reaching these APIs before exact source/S2/config permit, fencing and regression proofs. `MethodVariantRegistry.requireExecutablePlanBinding()` remains fail-closed on `LEGACY_UNPINNED`; do not remove it to make PlanCommit appear ready. The code-supplied `algorithm_sha256` must match a deployed reviewed implementation manifest; a well-formed 64-character digest is not proof.

**R2.2 decisions still open:** authored/reviewed-by strings are not authenticated identities; config review has one decision per config and no general revision/revocation state machine; config parent links do not yet freeze branch-vs-linear policy. Freeze those rules before a moderator write API or new ExecutionPermit. The source PeriodCalendar/mapping epoch and populated-schema upgrade plus DB grants remain separate W1.2.A/W0 gates. Stage W6 performance SLO/OLTP reserve thresholds must be owner-defined **before** optimizing or freezing physical W4 partition DDL, not invented at acceptance.

## FIN-MR-R2.4 — обязательная полнота параметров и порогов (60/60)

[FIN-MR-R2.4 Parameter Completeness & Threshold Governance](FIN-MR-R2.4-PARAMETER-COMPLETENESS-AND-THRESHOLD-GOVERNANCE.md) — нормативная матрица **всех 60 семейств** с кандидатными смысловыми параметрами, доменными проверками, правилами квалификации, SHA/immutable lifecycle и тестовыми gates MR24-01…MR24-11. Она расширяет R2.1/R2.2/R2.3 **без** создания второго реестра: существующие 259 кандидатов остаются `PROPOSED_UNREVIEWED` до сверки реальных ID/ключей и типизированного метода. Предполагаемые слоты **не** являются подтверждёнными Java/SQL ключами или разрешёнными значениями. CON-01/CON-02 ограничены их реальными контрактами; произвольные варианты остальных 58 запрещены. Спецификация R2.4 сама по себе **не** означает code implementation, independent S2, ACTUAL или HTAP release; соответствующие HOLD сохраняются.

## Методические редакции и 10 вариантов — не исполняющая функция FIN HTAP

[FIN-MR-R2.3 редакционные комбинации](FIN-MR-R2.3-EDITORIAL-PRESETS-AND-METHODOLOGY-EDITIONS.md) позволяют фиксировать на каждую версию конкретной `methodCode/variantCode` от 1 до 10 предварительных наборов **реально поддерживаемых** параметров, с явной политикой шага, серверным SHA-256 каждого набора и полной `optionsSha256` редакции. Каждый набор является **кандидатом**, не новой финансовой методикой и не системой автоматического перебора 259 неподтверждённых параметров. Выбор опубликованной редакции может приводить к новой `method_config_version` только после отдельного R2.2 trust/moderation workflow. Этот каталог не разрешает ACTUAL и не создаёт PlanCommit.

## Post-R2.1 readiness decision and controlling references (2026-11-17)

**Normative audit:** [FIN HTAP — Technical Specification Readiness Audit after R2.1](FIN-HTAP-TZ-READINESS-AUDIT-2026-11-17-R21.md) is the current change-control and stop/go matrix. It reconciles this specification, the W0–W8 strategy, ADR-R2-12, FIN-BE-R2 and F166. Historical reviews remain evidence at their own earlier SHA, not evidence of current implementation.

**Proven engineering state on 45d3f37:** FIN CI and Backend Domain SUCCESS, 1,972 PostgreSQL-backed backend tests, 0 failures/errors/skips; FIN-MR-R2.0 JPA CHAR(64) hardening, R2.0.1 fail-closed legacy executor, and R2.1 typed CON-01/CON-02 contracts are **implemented**. Specifically, Flyway V20261117_07 introduces `CON01_PARETO_FIXED_V1` and `CON02_ABC_TYPED_V2` alongside historic `CON02_ABC_BOUNDARIES_V1`. `ConcentrationTypedParameters` is a pure decoder, not an execution binding. `sourceParetoVersion` is trusted upstream **result lineage**, never client JSON config. `reportCutoffPercent` is a non-executable candidate. The entire 60-family/259-candidate read model is still unqualified for new production ACTUAL execution.

**Next engineering gates:** FIN-MR-R2.2 (trusted moderator identity, immutable authority and config-to-S2 binding) and HTAP-W1.2.A (source event/PeriodType/Coverage/catalog epoch contract and RED tests). Separate FIN-BE-R2.A read-only applicability and F166 impact preview from FIN-BE-R2.B immutable PlanCommit. PlanCommit is a **predecessor** of W2 activation; W4.1 introduces immutable result receipts and report-head CAS, **not** the first plan. W3 SQL pilots remain SYNTHETIC SHADOW until W4.1 trusted receipt path exists. No S2 or ACTUAL authority is inferred from CI.

## MR-R1 — methodological entity and parameter association

**Implemented database/model baseline (subject to CI):** [ADR-R2-12](ADR-R2-12-VERSIONED-METHODOLOGY-REGISTRY-MODERATION-AND-PARAMETERS.md#5a-mr-r1--persistent-java-methodologyentity-with-all-259-proposed-parameters) now maps the 60 methodologies as Java `MethodologyEntity`, each with its `@OneToMany` immutable `MethodParameterCandidateEntity` records. Migration V20261117_05 seeds 259 proposal-name rows and V20261117_06 embeds the names in the existing 60-row view `fin_calc.methodology_catalog_view.parameter_candidates`. Actual configuration **values** stay in immutable `method_config_version.parameters` JSONB with `params_sha256/effective_sha256`. No proposal is an approved method or a validated configuration; all 259 candidates remain unreviewed. This replaces any expectation that an Excel worksheet is a source of truth for runtime metadata.

## Methodology Registry (MR-R0) — one unified 60-family read table

**Normative design and initial implementation:** [ADR-R2-12 — Versioned Methodology Registry, Moderation & SHA-256 Parameters](ADR-R2-12-VERSIONED-METHODOLOGY-REGISTRY-MODERATION-AND-PARAMETERS.md). PostgreSQL migrations `V20261117_02`/ `V20261117_03` seed all 60 real method-family codes and provide immutable method-specific parameter contract, config version, independent review, CAS active pointer and activation audit, plus one `fin_calc.methodology_catalog_view` row per family with nested active/latest variant configs and evidence. These are **configuration and engineering-status controls, not method implementation or financial S2/ACTUAL approval**.

A parameter change (e.g. CON-02 ABC A/B thresholds 80/95→75/90) creates a distinct effective SHA-256 without requiring a changed Java algorithm SHA. A formula/tie policy change instead requires a new independently reviewed implementation version. The runtime ResultKey will pin exact algorithm+contract+config+tenant/form/PeriodType/Period/Currency+published source. Historic V02/V04 validated **only CON-02 ABC boundary numeric parameters**. Additive V07 now also validates explicit fixed CON-01 policy and CON-02 typed V2 parameters, without approving either variant or providing runtime adapter binding. Other families have NO invented approved defaults or executables. No activation event yet triggers calculation; no new scheduler or SQL text vault is introduced. MR-R0 PostgreSQL Testcontainers + independent S2 required before integrating read/write APIs or enabling runtime use.

## Methodology implementation truth and P0 remediation

[Machine-readable methodology and P0 recovery audit](FIN-HTAP-METHODOLOGY-REALITY-AND-P0-RECOVERY-PLAN.md) distinguishes **60 catalogue families** from **five wired, unqualified business variants**, **13 code candidates**, **42 unaudited families**, **zero released/auto-run families**. Do not use catalogue coverage as an implementation or S2 metric. Initial W1.2.A code now supplies an explicit source PeriodType/Coverage fail-closed candidate policy and rejects an obsolete structural form schema fingerprint at confirmation; an independently versioned `mapping_hash` + activation epoch and full method result pipeline remain future P0 prerequisites. The old normalized SHA already includes structural `schemaFingerprint`, but not complete financial mapping. W3 is SYNTHETIC shadow-only, FIN-01 existing same-source pilot remains unchanged, and partition inspector never DROP.

## S1 Technical review of W0–W8 implementation strategy

**New review:** [FIN-HTAP-IMPLEMENTATION-STRATEGY-TECHNICAL-REVIEW-2026-11-17.md](FIN-HTAP-IMPLEMENTATION-STRATEGY-TECHNICAL-REVIEW-2026-11-17.md) examined real W1.1 code, period model, distinct F02/V5 form path, FIN-01 source constraints, outbox and PostgreSQL partition lifecycle. It records **8 P0 + 8 P1 stage-specific dispositions**. **The [W0–W8 strategy v1.1](FIN-HTAP-IMPLEMENTATION-STRATEGY-W0-W8.md) now gates W1.2.A source period/coverage and global catalog epoch/compatibility before W1.2.B read-only event consumption.** Existing `WEEK/HALF_YEAR/AS_OF` source periods and `YEAR_TO_DATE/LIFE_TO_DATE` coverage must not be treated as month FLOW; first SYNTHETIC pilot accepts explicitly qualified `MONTH/PERIOD_ONLY/INTERVAL` only. Current FIN-01 still rejects heterogeneous source revisions until a new qualified variant exists. SQL Batch W3.1 is SHADOW only until W4.1 immutable results. W4.2 DROP requires positive physical cohort drain proof; inspector remains read-only. W1.1 success in CI does not imply S2/ACTUAL production approval.

## Delivery strategy and first implemented W1.1 foundation

**Normative implementation order:** [FIN-HTAP-IMPLEMENTATION-STRATEGY-W0-W8.md](FIN-HTAP-IMPLEMENTATION-STRATEGY-W0-W8.md) maps each project safety gate to small PostgreSQL/Java delivery increments and same-SHA acceptance evidence. The first code slice adds a persistent immutable **unqualified tenant form-change journal** and same-transaction emit in existing `CatalogXlsxRevisionService.confirm`; it is **not** a calculation scheduler, approved financial publication, SQL Batch Engine or physical workspace reaper. Current implementation must be verified by real FIN Backend Domain CI on the exact branch SHA before marking W1.1 GREEN. See W1.1 assertions for all 166 catalog codes, no-op, concurrency and rollback. `fin.execution.mode` and ACTUAL/release remain OFF/HOLD.

## 0. Architecture commitments and scope

Goal: one governed, safe, reproducible **financial calculation plane** for approximately 60 independently qualified methodologies and reusable auto-indicators, serving multiple tenants without allowing analytical workloads to exhaust OLTP capacity. Ensure deterministic outcomes on immutable sources, avoid repeated full scans where aggregation demonstrably saves work, and discard fully retired temporary calculation payload rapidly via `DETACH/DROP`.

Mandatory architecture:
```text
    OLTP intake -> reviewed SourceRevision -> immutable Published FactSet
                                           |              |
                                    trusted publication   existing outbox
                                           \              /
                                        server-owned Planner
                         Preview (read-only) -> Commit (not execute)
                            -> permit + capacity admission (Java)
                                           |
                         optional governed PREPARE / fin_agg SEALED
                                           |
                    FinancialSlice / Series Directory (tenant and source scoped)
                                           |
                        fin_workspace: staged generation -> SEALED
                                     |                |
                            SQL MethodModule      JAVA MethodModule
                                     \                /
                         fenced immutable MethodResultEnvelope
                            -> authorized CORE/report read side
                                           |
                                CLEANUP: generation retirement
                         DRAIN -> DETACH CONCURRENTLY -> DROP
```

There must be exactly **one existing orchestrator and one existing trusted source outbox**. A new generic ETL scheduler, new unrestricted SQL execution endpoint, distributed cluster or one service per method is out of scope. Financial method approval and `ACTUAL` authority remain separate and held. Existing import/report snapshots, catalog/forms and historical runs remain readable.

**Acceptance state is multi-dimensional**: `DESIGN_REVIEWED` != `IMPLEMENTED` != `TESTED` != `PERF_ACCEPTED` != `METHOD_S2_APPROVED` != `ACTUAL_EXECUTION_PERMITTED` != `PRODUCTION_READY`. `RELEASE_HOLD` stays; source publication never automatically launches unqualified methods.

## 1. Financial identity, immutable source and tenant authority (P0)

### 1.1. Exact five-axis business key

```text
Tenant + FormCode + PeriodType + Period + Currency
```

This **FinancialSliceKey** is the business address, in the specified order; it is not a sufficient primary key for a unique financial value nor automatically one PostgreSQL child table. A form can contain multiple indicators, entities, dimensions and source versions. Therefore each permitted slice includes **TrustedSliceContext**:
- `organization_id` (tenant v1, from signed scope authorization), `economic_entity_id`, `business_scope_id`;
- exact published `fact_set_revision_id`, `snapshot_hash`, publication/outbox evidence, `data_space`, `reporting_basis`;
- `form_code`, `form_version`, `source_revision_id` from `fin_fact.approved_source_revision`; not a fictitious form column in `fact_assertion`;
- `metric_code`, `metric_version`, `measure_kind`, `unit_code`, canonical `dimension_hash`/dimensions and selected population policy;
- `time_kind` (`INTERVAL` vs `AS_OF`), `period_type` (`MONTH`, `QUARTER`, `YEAR`, qualified `AS_OF_DATE` or `FISCAL_PERIOD`), `period_start`, `period_end_exclusive`, `calendar_id/version`, `aggregation_policy_version`.

**Point vs series:** `Period` is included in FinancialSliceKey for a single observation. For DELTA, MoM, QoQ, YoY, moving average and volatility, `FinancialSeriesKey` includes all the above *comparability dimensions except Period*; ordered Period identifies each observation within that one series. SQL window `PARTITION BY` and Java comparator MUST include every invariant series dimension, and MUST NOT group by Period. Missing a period does not cause the prior available row to be treated as the prior period. Duplicate periods, overlaps, mixed calendar versions and partial coverage block results.

**Cross-form/multi-metric** methodologies may use multiple separately qualified input series under their own versioned `MultiInputMethodContract`: declare exact source/form/metric binding and aggregation/alignment logic, verify every input tenant and publication, do not pretend that cross-form computations are ordinary single-series rolling indicators. A published FactSet may validly select multiple approved source revisions/forms.

### 1.2. TrustedComparisonSet — allow verified multi-source history without mixed revisions (P0)

**Why:** Current `AutoIndicatorEngine.SeriesKey` pins one `factSetRevisionId`, and `fact_assertion.source_revision_id` can legitimately differ between two reporting periods. Equal `source_revision_id` for every point would incorrectly forbid valid monthly history; dropping source pinning would introduce silent mixing of fact versions. **Distinguish each point's immutable source provenance from the whole series' deterministic selection identity**.

- **Single-published-snapshot mode (first pilot):** one trusted `fact_set_revision_id + snapshot_hash` containing all monthly points. Individual `source_revision_id`/form code/ver are retained **per point**, not forced equal between adjacent months. Require exact same approved `FormCode/FormVersion` for an ordinary series, same metric/unit/currency/entity/scope/dimension/dataSpace/basis/calendar and complete periods.
- **Multi-published-snapshot mode (separately approved extension):** immutable `TrustedComparisonSet(id, tenant, entity, scope, basis, dataSpace, ordered{fact_set_revision_id,snapshot_hash,publication_id,source_approval}, effective_selector_policy_version, per_period_selected_assertion_id, ordered_lineage_hash, sealed_at)`. A server-controlled builder verifies every publication, source authorization and complete canonical timeline, then resolves **exactly one authoritative assertion per metric/dimension/period** by a reviewed precedence policy (e.g. a newer correction explicitly supersedes an older selected assertion). Any ambiguity, conflicting versions or incomplete period is BLOCKED, **not** last-write-wins.
- **Series grouping:** `FinancialSeriesKey` pins the **comparison_set_hash** (or the single FactSet source hash), not the individual `source_revision_id` of each point; each point carries its own exact revision, form version and audit evidence. Cross-tenant, currency, form/version, PeriodType, calendar, basis, metric, dimension and unit mismatches still fail closed. No assumption that two different FactSet IDs automatically constitute a consistent series. Output ResultKey includes full comparison set hash.
- **Retrospective corrections:** publication/FactSet remains immutable; a changed month creates a *new* comparison set and new result identity. Never change existing historical MoM/YoY/mean/STDDEV receipts in place.
- **W3 gate:** first prove single-published-snapshot history with separate approved form SourceRevisions; a second composite-history path stays disabled until W1 HistorySet schema, reviewer policy and dedicated RED/golden tests exist.

**RED tests:** `HTAP-HISTORY-01` same tenant/form/version Jan–Mar selected from distinct approved SourceRevision IDs in one published FactSet -> valid consecutive series; `02` same period appears twice in unqualified multi-FactSet candidate -> BLOCKED_AMBIGUOUS_PERIOD; `03` tenant/form/currency mismatch across published FactSets -> reject at ComparisonSet seal; `04` new restatement changes only Feb and produces a new history/result fingerprint, old history remains readable; `05` missing approved source revision or incorrect snapshot hash blocks the entire series. Existing Java `SeriesKey` is not retroactively widened without its own versioned adapter and tests.

### 1.3. AS_OF closing values and PeriodType are separate dimensions (P0)

A stock point may be `time_kind=AS_OF` at the exact last day of a calendar/fiscal month while `PeriodType=MONTH` describes its **sampling cadence**. It is not an `INTERVAL` flow. A strict calendar mapping records the `AS_OF` date + its owning `MONTH/QUARTER/YEAR` period and verifies the correct closing day. An `AS_OF_DATE` PeriodType is reserved for a qualified arbitrary date without inferred monthly cadence. A `MONTH/AS_OF` stock series may support a descriptive moving average of N monthly **closing values** if specifically qualified; it must never be transformed into monthly STOCK_SUM or a day-weighted average by default. Two points with different `time_kind` are not ordinary comparable observations without an explicitly approved method. These rules preserve existing `STOCK_CLOSING` semantics and avoid conflating a stock valuation date with a flow reporting interval.

**RED tests:** `HTAP-CLOSE-01` end-of-February stock and end-of-March stock are two valid `MONTH/AS_OF` points with correctly resolved calendar ordinals; `02` midmonth stock cannot masquerade as month closing; `03` FLOW interval + STOCK closing inside one SMA/TTM stream is blocked; `04` approved monthly closing-value SMA cannot sum balances.

### 1.2. Trust and security

The code currently defines the FACT authority in `fin_fact.fact_assertion`, `fact_selection`, `fact_set_publication` and publication outbox. Exactly matching source, revision, entity/scope, basis and `data_space` are mandatory throughout planning, aggregate building, workspace sealing and results. `MISSING`, `EXPLICIT_ZERO`, `ESTIMATED`, `NOT_APPLICABLE` remain separate; prohibited ACTUAL estimated values are blocked. Historical source rows and published results are append-only, never mutated during recalculation.

HTTP `FinanceAccessPolicy` must validate organization-scoped `FIN_ANALYZE`/`FIN_READ` as appropriate. No client JSON, URL parameter or unverified token claim can authorize a tenant. Background workers use a server-controlled narrow principal and *recheck* source/method/permit, not a stale user JWT. Composite tenant FKs and explicit SQL predicates are mandatory; worker DB role is non-owner/non-BYPASSRLS. RLS is optional defense-in-depth only with audited `FORCE ROW LEVEL SECURITY`, safe functions and `SET LOCAL` tenant context transaction-reset test. Cross-tenant reuse is prohibited even for numerically identical data.

### 1.3. Stable hashes and idempotency

Define a versioned canonical encoding (typed fields, explicit null marker, unambiguous length prefix, sorted canonical dimensions and selected assertion identities) for `SliceKey`, `SeriesKey`, `SourceKey`, `WorkspaceKey`, `PopulationHash`, `PlanKey` and `ResultKey`. Hashes are integrity fingerprints **not digital signatures**. Method code/implementation checksum, calendar, policy, precision and population are pinned. A unique result collision requires exact payload comparison, not a silent overwrite. Plan retries must not change logical result identity; attempt/fence never enters ResultKey.

## 1.F166. Automatic 166-form change propagation and hash-driven recalculation

**Normative sub-specifications:** [FIN-HTAP-F166 — tenant form change, impact graph and partition lifecycle](FIN-HTAP-FORM-166-INCREMENTAL-RECALCULATION-AND-PARTITION-LIFECYCLE-TZ.md) and [ADR-R2-10 — SHA-256 fingerprint contract](ADR-R2-10-FORM-FINGERPRINTS-AND-INCREMENTAL-RECALCULATION.md). **F166 internal review:** [F166 architecture review](FIN-HTAP-F166-ARCHITECTURE-REVIEW-2026-11-17.md). Parent data/source authority, PeriodType, Currency, permitted financial results and release HOLD remain controlling.

For **every** one of 166 catalogue codes, a new *tenant draft revision* causes a **durable same-transaction change-intent** and bounded automatic validation/impact classification; `CONFIRMED_UNQUALIFIED_DRAFT` must **not** create an approved financial result. An actual source publication uses the existing `FACT_SET_PUBLISHED` outbox and permits only separately qualified dependency-based recalculation. A *global catalog definition activation* is a third, server-owned event whose tenant fan-out is paged, only for users of the affected form, with staged fingerprint/compatibility review; no extra independent scheduler.

**SHA-256 is mandatory for new authoritative hashes; MD5 and JVM `hashCode()` are forbidden** as immutable financial identifiers. Existing `schema_fingerprint`, `normalized_sha`, raw XLSX `original_sha`, `document_sha` and published `snapshot_hash` remain untouched. Use distinct **versioned** schema, mapping, presentation, canonical ordered content, raw-document, context-bound form and calculation hashes, with deterministic length-prefixed serialization. The existing normalized SHA does not alone capture meaningful row ordering; retain JSONB ordered equality checks and add v2 rather than rewriting history. Matching content hash does not allow cross-tenant, cross-form, cross-period or cross-currency reuse and never bypasses source approval/permit.

Changed metric at period `p` affects only reviewed downstream DAG consumers: MoM/QoQ `p,p+1`; YoY `p` and next-year exact equivalent; SMA-N and rolling standard deviation `p..p+N-1`; TTM12 FLOW `p..p+11`; prior-only Z-score baseline `p+1..p+N` (plus current point if its own observed value changes). Unknown/unbounded horizon requires separately admitted full-series rebuild or explicit BLOCKED status. New sealed generation is CAS-promoted only if source/catalog/method version vector remains current; old results survive. Physical lifecycle `DETACH/DROP` remains asynchronous and cannot delete any active cohort member. **No 166×tenants physical partitions or blanket 60-method run.**

**First implementation:** new F166-HASH and F166-EVT PostgreSQL RED fixtures, then an additive journal and pure impact-preview for one form behind OFF default; 166-classification and catalog activation follow under explicit S2. No new ACTUAL execution.

## 2. PeriodType and Currency as arithmetic policy (P0)

A **server-owned, versioned `PeriodCalendar`** maps each period type and calendar to an unambiguous ordinal, exact half-open boundaries and approved corresponding prior-year ordinal, including completeness rules. Do not silently infer MONTH from arbitrary interval duration, or compare fiscal Q1 with calendar Q1. For `AS_OF_DATE`, a stock closing value is a point-in-time value, not a monthly flow. Fiscal 4-4-5 / 53-week equivalence remains NOT_APPLICABLE until reviewed.

| Indicator comparison | Allowed PeriodType | Offset and prerequisites |
|---|---|---|
| MoM | MONTH | previous **calendar/fiscal** month, exact preceding ordinal |
| QoQ | QUARTER | prior complete quarter, same calendar/version |
| YoY | MONTH/QUARTER/YEAR | exact previous-year counterpart, not simply previous row |
| SMA N | qualified same grain | exactly N consecutive complete points, no missing samples |
| TTM12 | MONTH + FLOW | 12 fully covered nonoverlapping monthly flows; no STOCK sum |
| Closing stock | AS_OF/approved quarter end | exact approved closing date, no addition of balances |
| Day-weighted level | compatible complete INTERVAL | weight by exact covered days, not count of observations |

All monetary inputs for a calculation must be in the **same Currency** across time. `KZT` and `USD` cannot be added, averaged or compared by default; `MONEY` requires valid nonnull ISO currency, nonmoney requires a typed `currency=NULL` plus compatible unit. A percent result still retains input/source currency and basis in provenance, even when output unit is PCT. Explicit FX conversion is a **different versioned, independently qualified** transform with rate source/revision, period/date policy (FLOW vs STOCK), pair, target currency and rounding; no automatic “latest rate” conversion.

Standard financial percentage policy for FIN-01 remains `100*(current-prior)/prior` for prior>0, `ZERO_BASELINE` for prior=0 and `NEGATIVE_BASELINE_PERCENT_UNDEFINED` for prior<0. Alternate signed-base growth must be a distinct module/version. Every method declares exact zero/missing/negative semantics.

## 2.SQLB. PostgreSQL batch executor — approved optional calculation strategy

**Normative decision:** [ADR-R2-11 — Governed PostgreSQL Batch Calculation, Not SQL Stored in Tables](ADR-R2-11-POSTGRES-BATCH-CALCULATION-EXECUTION.md). **SQL set-based batch calculation inside PostgreSQL is allowed and encouraged where benchmarked**, using reviewed, versioned and statically allowlisted PostgreSQL routines or static parameterized SQL. Do **not** build a second DB scheduler, store mutable arbitrary SQL bodies in a table and dynamically `EXECUTE` them, or grant calculation workers schema/DDL rights. The DB routine registry stores routine signature, method/version, input/precision contract, SHA-256 and qualification state **as metadata only**; actual SQL artifacts are reviewed in Git and deployed through additive Flyway with tested DB routine/privilege version. Old routine semantics and result identities remain immutable.

**Single Java orchestrator** keeps the existing source outbox, DAG, tenant and global PREPARE/SQL/JAVA/CLEANUP capacity, explicit ExecutionPermit, stage fence, result receipt and current-generation CAS. **PostgreSQL workers only run bounded, exact-source/tenant/form/PeriodType/Period/Currency calculation batches** under a scoped invoker role, preserving necessary historical input halo for LAG/SMA/YoY. Stage rows are attempt-local, validated and committed transactionally; Java calls multiple short bounded transactions, **no internal procedure COMMIT/ROLLBACK** and no one enormous tenant/166-form `CALL`. Source trust, RLS/ABAC and immutable receipts are not replaced by a successful SQL routine.

**Required test suite:** `SQLB-01..14` from ADR-R2-11 for routine version drift, unauthorized tenant input, cross-boundary windows, timeout/cancellation, idempotent batches, stale-fence/DB liveness, per-method accuracy, DB role/SQL injection, PostgreSQL `CALL` transaction restrictions, performance and OLTP. Gate W0.SQLB -> W3.SQLB SYNTHETIC FIN-01 before expanding other methods. A runnable SQL framework is **not** implemented by this document.

## 3. Core computation modules and Derived Indicator Layer

**One qualified method variant = one versioned `MethodModule`** using SQL or Java engine, with exact input/period/currency/precision contract, server-owned SQL artifact hash and immutable result. Method variants are not auto-enabled because they exist in a catalog. All methods share a typed `MethodInput` and `MethodResultEnvelope`; math is `NUMERIC(38,12)`/Java `BigDecimal` unless an independently approved higher-precision policy requires it.

### 3.1. Reuse existing functionality, do not rewrite as greenfield

Current `AutoIndicatorEngine` already implements `aggregate` (FLOW_SUM, MONTHLY_FLOW_MEAN, DAY_WEIGHTED_LEVEL_MEAN, STOCK_CLOSING), `compare`, `movingAverage`, `shareByDimension`, `ratioOf`. The FIN-01 SQL module is a SYNTHETIC read-only pilot using `LAG`; neither is yet a governed HTAP worker. Harden and wrap existing algorithms; measure parity before migrating.

| Indicator (independent descriptor/version) | Core calculation | W-stage |
|---|---|---|
| DELTA_PREVIOUS | current - canonical prior | W3 |
| PCT_CHANGE_PREVIOUS / MoM/QoQ | strict compatible prior; versioned denominator policy | W3 |
| YOY_CHANGE | exact previous-year counterpart | W3 |
| SMA3 / SMA6 / SMA12 | N consecutive points / N | W3 |
| TTM12_FLOW | SUM 12 complete monthly FLOW | W3 |
| ROLLING_DELTA_TO_MEAN | current - SMA(N) | W3 |
| STDDEV_POP_N | sqrt(sum((x-mean)^2)/N) | W3 |
| STDDEV_SAMP_N | sqrt(sum((x-mean)^2)/(N-1)); N>=2 | W3 |
| CV_N, ZSCORE_PRIOR_N | variability and deviation vs N **prior** points, excluding current from baseline | W5 after financial review |
| CAGR, MEDIAN, QUARTILES, EWMA, seasonality | independently specified rules | W5+ |

For STDDEV/sqrt, PostgreSQL `stddev` and Java `BigDecimal` may differ in intermediate precision; define a **deterministic quantization and comparison policy**. Never silently coerce to `double` or use PostgreSQL default `round` as Java HALF_EVEN parity proof. Distinguish population vs sample deviation and RMSE from externally observed/forecast errors. Z-score with zero variance is NOT_COMPUTABLE; arbitrary anomaly thresholds are not approved financial facts, risks or causal explanations.

**SQL correctness invariant:** `PARTITION BY` all invariant SeriesKey fields (tenant, source, form/version, grain, currency, metric/version, unit, dimensions, entity/scope, basis, dataSpace, calendar), **ORDER BY canonical period ordinal**; separately verify calendar adjacency/complete coverage/duplicate period controls. `LAG/ROWS N PRECEDING` and `COUNT=N` alone cannot establish continuity. Staged SQL candidate is provisional until validator passes. Cross-form methods need explicit independently qualified joins.

### 3.2. Status and evidence

Result must include `status` CALCULATED/ESTIMATED/INCOMPLETE/NOT_COMPUTABLE, reason code if blocked, exact input observation/period IDs, schema/method/SQL checksum, source currency and unit, sample N, window bounds, calendar and policy versions, organization/source hashes, workspace generation and tenant. Never fill absent period with zero, silently resample or publish a status as causal/risk conclusion.

## International standards and typed semantic W1.2.A pilot (2026-11-17)

[Normative implementation notes — IFRS/XBRL, ISO 8601/4217, typed mapping](FIN-HTAP-W12A-INTERNATIONAL-STANDARDS-SEMANTIC-PILOT-2026-11-17.md). Only independently reviewed `fin_fact.pilot_field_mapping` + `fin_calc.metric_contract(QUALIFIED)` combinations can produce technical `VALIDATED_PILOT_NOT_S2` evidence, backed by immutable source field/version and `ISO_MONTH_FLOW_PERIOD_ONLY_V1`. The runtime uses a verified ISO 4217 currency code. No IFRS taxonomy tags are auto-assigned; commercial licensing needs separate evaluation. `HtapRuntimeClassDigest` provides only un-attested class-byte SHA, NOT a trusted shipped algorithm manifest. The blocked PLAN_ONLY snapshots do not become executable. **Full financial/calendar S2, ACTUAL and runnable PlanCommit remain HOLD** pending signed build/source and production security review.

## W1.2.A mapping editorial moderation and R2.B BLOCKED PLAN_ONLY implementation

[Implementation evidence + open P0 gates](FIN-HTAP-W12A-MAPPING-GOVERNANCE-R2B-BLOCKED-PLAN-IMPLEMENTATION.md) records the new append-only mapping review/authorship, signed-subject backend service, monotonic tenant `catalog_epoch`, and read-only R2.A2-derived, dependency-ordered blocked PlanPreview with immutable, idempotent `BLOCKED_PLAN_ONLY` snapshot. **Not a runnable PlanCommit**: no qualified source-field mapping, fiscal policy, pinned algorithm artifact/S2, production executor or ACTUAL. All previous release/permission holds remain mandatory. Same-HEAD CI and independent S2 are separate required gates.

## W0/W1.2.A incremental code and W2/W3/W6 readiness (2026-11-17)

[Implementation + technical gap audit](FIN-HTAP-W0-W12A-IMPLEMENTATION-REVIEW-W2-W3-W6.md) records actual new `HtapFinancialSliceKey`, `HtapIsoFinancialCalendar`, the immutable mapping **candidate** version/mapping SHA, read-only PostgreSQL publication view, scoped candidate resolver and tests. These are strictly W1.2.A **foundations**: mapping review/activation `catalog_epoch`, fiscal calendar, multi-source financial series, source+algorithm S2 and permit are NOT implemented. No new `fin_agg` storage, partitioned workspace, W2 scheduling, W3 controlled method execution or W6 performance results are claimed. The audit lists executable W2/W3 gates and a reproducible W6 workload/EXPLAIN benchmark. **Do not equate mapping SHA validity with approved mapping authority; ACTUAL and RELEASE_HOLD persist.**

## W0 physical performance decision — ADR-R2-13 (2026-11-17)

**Controlling physical layout decision:** [ADR-R2-13 — HTAP Physical Layout, Performance-First](ADR-R2-13-HTAP-PHYSICAL-LAYOUT-PERFORMANCE-FIRST.md) is the **normative initial per-storage-domain policy** for FIN HTAP. FinancialSliceKey is a complete source/metric/period/tenant **logical** identity, not a database partition key; the requested `organization + entity + scope + form + period + currency + mappingVersion + publishedRevision` is **insufficient alone** without form/source, dataSpace/basis, timeKind/grain/coverage/calendar, metric, dimension, unit/value state and immutable source evidence. Never create a physical partition per logical financial tuple.

The adopted **implementation default** is: preserve immutable `fin_fact` source schema, keep small transactional manifests/control/partition catalog **unpartitioned**, start optional reusable sealed `fin_agg` **unpartitioned** with selective tenant+source+metric indexes and `DIRECT_PUBLISHED` fallback, and put only **large reproducible `fin_workspace` stage payload** in bounded/precreated UTC retention-class `RANGE(reclaim_epoch)` cohorts (weekly is the initial test candidate, daily and fixed HASH are measured alternatives). `reclaim_epoch` is **not** accounting period and does not guarantee automatic removal; explicit `reclaim_epoch + organization_id + workspace_id + generation` predicates and trusted directory resolution are required for pruning and financial isolation. No per-form/tenant/method physical child, no business-transaction DDL, no unsafe DROP.

**Performance proof gate:** W0 identifies owner-approved **numeric** p95/p99/OLTP impact, queue/build/recompute/GC, WAL/disk/child-count budgets **before** tests; W6 compares `DIRECT`, unpartitioned `AGG`, `HASH` / accounting-`RANGE` aggregation candidates, and `RANGE(reclaim_epoch)` daily/weekly stage layouts on the same PostgreSQL 16 fixtures and hardware with actual `EXPLAIN ANALYZE BUFFERS WAL`, planning/pruning, 1/2/4/8 workers, cold/warm caches and tenant skew. Do not assert that physical partitioning *improves query latency* without repeatable results; complexity requires demonstrated end-to-end gain without OLTP regression. Safety invariants are absolute vetoes. **This ADR specifies architecture, not installed partitions, benchmark results, independent S2 approval or ACTUAL permission.** Earlier physical options in §4 and R2-07/R2-08 are rationale/alternatives only when they conflict with the adopted initial policy.

## 4. Storage, aggregation and physical partitions

### 4.1. Distinct durability domains

1. **OLTP source (`fin_fact`)**: published FactSet and source history, approvals, original outbox, method result/report history; **never touched by workspace DROP**.
2. **Reusable Aggregate Layer (`fin_agg`)**: optional tenant/source-version-scoped, immutable-after-SEALED reusable aggregate cache with manifest, cells, lineage, compatible aggregation policy and independent retention pins. Unknown algebra -> DIRECT_PUBLISHED. New revision/policy -> new manifest; no old-value UPDATE.
3. **Ephemeral Calculation Workspace (`fin_workspace`)**: durable metadata/manifest/references and generated stage payload in lifecycle-managed partitions. Only disposable, reproducible stage payload is physically droppable after full safety proof. The workspace is not a new approval authority and does not replace FIN fact source or immutable result receipts.

**Aggregation algebra:** FLOW_SUM only complete, nonoverlapping compatible intervals; STOCK_CLOSING exact AS_OF (no SUM); LEVEL_DAY_WEIGHTED AVG with complete day weights; RATIO uses independently qualified numerator/denominator components, not AVG(percent). Financial provenance must retain form, metric, policy, calendar and dimension selection. Never roll partial quarter into complete quarter. The “aggregate first” strategy is conditional: for small/one-use input, DIRECT_PUBLISHED may be faster overall.

### 4.2. Logical index vs physical partition

The five-axis FinancialSliceKey identifies a **logical financial segment**, not a PostgreSQL table. Indexed `financial_slice_manifest` maps exact trusted SliceKey+generation to sealed workspace ID, content hash, and physical cohort.

**Physical GC candidate for high-volume temporary stage:** `RANGE(reclaim_epoch)` partition cohorts (daily/weekly based on measured retention and size, not finance reporting Period). The partition may contain several tenants, forms, periods and currencies, requiring exact SQL predicates and composite FKs to maintain isolation. Runtime queries bind `reclaim_epoch`, `workspace_id`, tenant/source and series constraints so PostgreSQL prunes physical cohorts and cannot mix contents.

Do not create physical `Tenant × Form × PeriodType × Period × Currency` partitions: cardinality and DDL/lock overhead would explode. Alternative unpartitioned or fixed HASH(organization/workspace) are read-performance **benchmark candidates**; a shared HASH child with active rows cannot be dropped for one workspace. Dedicated per-heavy-run cohort requires explicit minimum size, tenant partition quota and operational authorization.

**PostgreSQL constraints:** partitioned-parent PK/UNIQUE must include every partition key; metadata table can own globally unique `workspace_id`, but payload keys include reclaim_epoch and tenant scope. Review cross-tenant composite FK and FK/trigger dependencies before any detach; a FK that pins individual child can obstruct detach/drop. Partition precreation and DDL are operator-maintained, never per HTTP calculation. No DEFAULT child if using CONCURRENTLY detach; test missing-cohort admission behavior (reject/defer rather than insert into DEFAULT).

### 4.3. Generations, sealing and invalidation

A source/policy is immutable, but derived *current-generation pointer* can switch:
`CREATED -> LOADING -> VALIDATING -> SEALED -> ACTIVE -> RETIRED -> RECLAIMABLE`; failures -> FAILED/QUARANTINED with auditable replays. Builders obtain admission and fenced generations, append idempotent staged batches, reconcile selected assertion counts/content hash/period/metric/currency coverage, then short CAS seal. **No SQL/Java method may read a non-SEALED input.** After sealing, prevent modification through DB privileges and guards. For recalculation, generation N+1 is built/SEALED while N stays readable; switch only the new pointer with one control transaction. Already-pinned readers continue using N. Changing source revision, form version, calendar, formula or source population creates new canonical identities, never overwrites immutable prior results.

Cache invalidation is versioned: new FactSet never alters old, and old references may survive with TTL/legal hold/replay rules. Expiry **by reference and full cohort membership**, not by assuming “older than 24h” means safe.

### 4.4. DROP-based cleanup and crash recovery (P0)

A cohort's lifecycle:
`OPEN -> CLOSED_TO_NEW_WRITES -> DRAINING -> DETACH_REQUESTED -> DETACHED -> DROP_REQUESTED -> DROPPED`; interruptions -> RECOVERY_REQUIRED.

**Safety barrier:** lock catalog/cohort in a short transaction; prove every member generation reclaimable, no active/pending plan, reader pin, live/unconfirmed SQL statement, unexpired/quarantined lease, reference, report/replay pin or legal hold; transition DRAINING prevents new references/members. A retiring slice alone never permits dropping an active shared cohort. Keep separate small persistent partition catalog, membership and cleanup audit after physical DROP.

**DDL:** dedicated privileged maintenance role and allowlisted parent/child from DB catalog, no arbitrary string or public endpoint, no `CASCADE`. Preferred sequence on an eligible child: autocommit `ALTER TABLE ... DETACH PARTITION ... CONCURRENTLY` outside explicit transaction -> verify catalog -> separate `DROP TABLE detached_child`. PostgreSQL 16 disallows CONCURRENTLY with a DEFAULT partition; only one pending detach per parent; a crash may need `DETACH PARTITION ... FINALIZE`. Concurrency still waits for running transactions and acquires locks; global DDL budget and timeouts are mandatory. Metadata barrier and actual DDL cannot be a single ACID transaction, so a journal/reconciler must restore consistency after crash.

**Worker liveness** is stronger than an expired lease: fence/cancel/query termination must be confirmed or capacity and cohort remain quarantined. Garbage collection is deferred, not on user HTTP request path. No `DROP` on `fin_fact`, durable `fin_agg` pinned data, plan/audit, immutable result/report. Small metadata may use bounded DELETE if legal.

**Current implementation:** `WorkspacePartitionMaintenanceUtility` only inspects and always returns non-approval for retirement; it does not create, detach or drop tables. A previously supplied generic `TablePartitionService` that drops attached daily children based on date-only cutoff **must not be copied as-is**: no cohort/drain proof, no safe concurrent detach and accepts arbitrary schema/table names. Its dry-run/observability/retry concepts may be adapted with tests.

## 5. Tenant-aware orchestration, quotas and predictable failure

**Target, not yet implemented:** one effective production `fin.execution.mode = OFF|PLAN_ONLY|SYNTHETIC_SHADOW|WORKSPACE`, default OFF. `LEGACY_TEST` is permitted ONLY as explicit `src/test` golden-fixture authorization; R2.0.1 production `DenyAllLegacyExecutionPermitGuard` blocks the old `SingleExecutorScheduler` even when `fin.execution.enabled=true`. Never enable a legacy production mode to bypass the new permit. Fail startup on conflicting legacy `fin.execution.enabled` / new feature mode; no dual task claim/result write. SHADOW is SYNTHETIC-only, read/compare, no production receipt/CORE side effects. Preview is read-only; Commit persists immutable plan without execution; Activation requires explicit server permit, source authority, DAG and resources.

Stages `PREPARE`, `SQL`, `JAVA`, `CLEANUP` all consume **global+tenant+class** capacity, plus PostgreSQL connection and disk/WAL/CPU pressure guardrails. Prevent heavy aggregate-building and DDL cleanup from bypassing worker quotas. Schedule with persisted bounded weighted-fair tenant dispatch, queue quotas and resource classes; current `SingleExecutorScheduler` is not yet that system.

**Atomic claim contract:** in ONE short PostgreSQL transaction choose eligible tenant/task, lock scheduler cursor/capacity buckets in deterministic order, verify source/method permit and prerequisites, atomically reserve global+tenant+class budget and durable capacity lease/fence, CAS task READY->RUNNING; commit *before* opening expensive work. If any reservation fails, no task is RUNNING and no partial capacity is allocated. Local JVM counters are metrics, not authority. Cross-instance 2+ worker test must prove zero quota oversubscription.

**Lease expiry:** mark REVOKING, revoke fence, cancel query, wait for confirmed termination or quarantine physical capacity; never allow two physical worker executions to exceed hard admission simply because a lease timestamp elapsed. Commit result+task terminal outcome under live fence atomically, UNIQUE logical ResultKey with payload/hash comparison, immutable output and no last-writer-wins. Crash at any boundary is replayable, idempotent, observable.

**Backpressure:** reserve connections for OLTP/HTTP, migrations and recovery across all replicas; bound SQL statement/lock/transaction timeout, temp files and `work_mem`; cap workspace rows/bytes and queued work. Shed heavy analytics on DB pressure (connections, CPU, WAL, disk, OLTP p95), never block critical source publication behind unbounded analytic transactions. Measure rather than claim per-tenant physical CPU isolation—PostgreSQL ordinary pools enforce concurrency/connection/query caps, not strict per-tenant CPU quotas.

**Legacy schema issue:** `fin_exec.analysis_run.event_id UNIQUE`, `outbox_delivery.event_id PK`, `analysis_task UNIQUE(run_id,method_code)` cannot directly store multi-version reanalysis without collisions; introduce a separate versioned plan and per-variant binding with forward-only Flyway. Preserve all old rows and API/CI behavior.

## 6. Implementable contracts and migration rules

Use reviewed value objects and explicit statuses. Proposed contracts (not yet classes):
- `FinancialSliceKey(tenant,formCode,periodType,period,currency)` + `TrustedSliceContext(...full point-specific source identity...)`; `FinancialSeriesKey` omits period and per-point revision IDs but pins an exact sealed `TrustedComparisonSet` hash (see §1.2).
- `PeriodCalendar(calendarId,version,periodType,ordinal,start,end,complete,previousYearEquivalent)`; qualified mapping from time_kind/form accounting period.
- `AggregationPolicy(metric+version,measureKind,allowedGrain,unit,dimensions,completion,methodVersion)`; server-only allowlist and source policy hash.
- `AggregateManifest(id,org,sourceKey,policyVersion,inputHash,outputHash,status=BUILDING|SEALED|...)`.
- `WorkspaceGeneration(id,workspace,tenant,sourceKey,reclaimEpoch,generation,fence,state,sealedHash)`; `SliceBinding(...,physicalCohort)`.
- `MethodVariantDescriptor(method,version,engine,sqlChecksum,periodTypes,currenciesOrUnitPolicy,inputs,assumptions,precision,releaseQualified)`; `MethodInput`, `MethodResultEnvelope` remain typed.
- `PlanPreview` (NO DB writes), `PlanCommit` (immutable plan NO compute), `ActivatePlan` (permit+capacity), `ResultReceipt` (append-only logical key, audit).
- `PartitionCatalog(cohort,parent,child,bounds,members,closedAt,reclaimGeneration,state)` + `RetirementAudit` and retryable `PartitionReaper` only after W4 proof.

DB migrations must be **additive, forward-only Flyway** with fresh DB and upgrade-from-existing fixtures. Do not change historical Flyway content or rewrite source/CORE histories. Data backfill/migration cannot silently manufacture `PeriodType`, fiscal calendars, form associations, approvals or impute missing data. Runtime mode OFF until security/qualification/CI/DB proof.

## 6.A. Mandatory cross-roadmap predecessor gates (new in v1.2)

1. **W0 DESIGN CONTRACT**: reconcile financial source/event classes and coverage/calendar semantics, reviewable moderator identity, exact config/S2 lineage, performance threshold owners; independent architecture S2 remains pending.
2. **R2.2 and W1.2.A:** may be implemented in separate small backend commits, but both are necessary before making a config/source-driven runnable plan.
3. **R2.A + F166 W1.2.B:** read-only, distinct Applicability and FormImpact previews. A draft never becomes a FactSet; neither preview grants an execution permit.
4. **R2.B PlanPreview/PlanCommit (PLAN_ONLY):** after exact trusted **single published FactSet**, config, contract SHA, implementation SHA, calendar and profile identity are frozen; no `analysis_task`, report or SQL computation from plan commits. **Composite multi-FactSet history is explicitly BLOCKED until W1.3** supplies independently reviewed TrustedComparisonSet and source selector.
5. **W1.3 + W2:** trusted selection and bounded admission/permit/lease/cancellation are necessary before execution.
6. **W3 read-only SYNTHETIC SHADOW:** allowed for independent SQL/Java parity and bounded performance experiments; no trusted result promotion.
7. **W4.1 result generation/receipt and complete report-set CAS:** before persistent trusted outputs; **W4.2** destructive cohort lifecycle has its own independent DBA/ops gate.
8. **W5→W8:** only qualified method DAG, measured capacity benefit, least-privileged operations and independently signed-off release.

If the legacy R2 method execution document, an older ADR or a W-stage table appears to allow earlier execution, **the stricter hold and this predecessor graph control**. Do not silently rename current implemented features into proposed runtime capabilities.

## 7. Work plan W0–W8 (one delivery order)

| Gate | Deliverable | Required proof |
|---|---|---|
| **W0 Design freeze** | Canonical spec and audit, PeriodCalendar/SourceKey decisions, security roles, physical options, test data/size, operating budget, baseline SHA | independent S2 architecture review, negative-model checks; no unresolved P0 schema ambiguity |
| **W1 Trusted data plane** | PeriodCalendar versioning including monthly AS_OF closing, slice/series resolution and sealed single-FactSet comparison history; composite `TrustedComparisonSet` only behind separate S2 gate, optional fin_agg and workspace schema | PostgreSQL 16 fresh+upgrade, no cross-tenant/form/currency/period/version mixing, independent per-period source revisions, concurrent seal race |
| **W2 Governed admission** | single mode, fair tenant scheduler, global/tenant/class capacity/leases and cancellation, bounded PREPARE/SQL/JAVA/CLEANUP | two-worker/two-node contention, no overadmission, no starving eligible tenant, OLTP reserve |
| **W3 Pilot indicators and FIN-01** | SQL typed indicator execution for DELTA/MoM/YoY/SMA/TTM/STDDEV and FIN-01 same source Java parity, SYNTHETIC shadow only | Golden exact numeric/status/period/currency/rounding and 16+ negative cases, no ACTUAL |
| **W4 Immutable receipts and GC** | immutable result receipts/fencing **after R2.B plan**, N+1 required-report-set CAS switch, cohort membership barrier, safe DETACH/FINALIZE/DROP reaper | crash replay, stale fences, orphan query, same cohort multiple tenants, no referenced DROP |
| **W5 DAG and methods** | other reviewed financial variants, qualified cross-form bindings, one ordered/parallel DAG, no duplicate scheduling | sequential vs parallel equality, source/module S2 per method, old adapters regression |
| **W6 Performance** | compare DIRECT / unpartitioned AGG / HASH AGG / RANGE workspace, 1/2/4/8 workers, tenant skew, high volume GC | measured p50/p95/p99, end-to-end including build+cleanup, WAL/temp/locks/disk, OLTP p95, numeric thresholds frozen before run |
| **W7 Security and operations** | runtime roles/RLS hardening, audit and telemetry, DDL/GC operator controls, quotas, legal holds, incident/runbooks | RLS pool reset, impersonation, denial, disk/DB pressure and recovery drills |
| **W8 Acceptance** | all same SHA regressions, independent design/security/method S2, evidence links and release matrix | no P0, no skipped blockers, explicit separate ACTUAL/release decisions |

**Engineering delivery discipline:** first PostgreSQL RED tests and negative fixtures, independent review, then minimal GREEN change; small incremental commits to the only branch. Existing backend/UI unrelated development is STOP; never weaken old tests, alter main or enable production flags as a shortcut. Documentation alone is NOT code completion.

## 8. Executable acceptance matrix and expected status

All scenarios require a test case ID, fixture SQL, expected persisted state, Java golden where applicable, exact implementation SHA and CI run. Do not describe named future scenarios as PASS.

| ID | Test | Must prove |
|---|---|---|
| HTAP-SRC-01 | Source not published / wrong hash / wrong scope | no trusted workspace or method result |
| HTAP-SRC-02 | Same five-axis key but different source revision/form version/metric/dimension | distinct point lineage and appropriate history fingerprint; no result/cache collision |
| HTAP-ISO-01 | Tenant A and B, identical form/period/currency | zero cross-tenant joins/rows, including pooled connection |
| HTAP-ISO-02 | Same tenant, same period, KZT and USD | no mixed currency delta/mean/stddev/rollup |
| HTAP-ISO-03 | Same tenant, form X and Y same month/currency | distinct series; reviewed cross-form method only |
| HTAP-ISO-04 | Same month vs quarter vs year/AS_OF | no cross-grain window/stock summation; MONTH/AS_OF closing cadence allowed only when qualified |
| HTAP-TIME-01 | Monthly Jan, Mar (missing Feb), `LAG(1)` sees Jan | INCOMPLETE/MISSING_PERIODS, not artificial MoM |
| HTAP-TIME-02 | Duplicate/overlap/partial month, fiscal policy mismatch | fail closed, no `CALCULATED` |
| HTAP-TIME-03 | Year-end month/quarter, leap year, 4-4-5/53-week case | only exact approved calendar equivalent selected |
| HTAP-HISTORY-01..05 | Distinct approved source revisions within same published history; ambiguous multi-FactSet candidate; restatement | trusted comparison-set identity, one authoritative point per period, no accidental history merge |
| HTAP-CLOSE-01..04 | Monthly stock closing dates vs interval flows and midmonth observations | calendar closing semantics, no STOCK summation or flow-stock mix |
| HTAP-CALC-01 | MONTH Jan=100 Feb=120 Mar=180 | Mar DELTA=60, PCT=50, SMA3=133.333333333333 |
| HTAP-CALC-02 | Prior 0/negative, missing, explicit zero, ESTIMATED ACTUAL | exact legacy blocked reasons and status; no imputed amount |
| HTAP-CALC-03 | STDDEV population vs sample, zero variance, tie HALF_EVEN | exact qualified decimal/reference, not float approximation |
| HTAP-CALC-04 | FLOW vs STOCK vs LEVEL vs RATIO | legal algebra only, complete coverage and unit semantics |
| HTAP-PART-01 | Two tenants in one physical lifecycle cohort, only one retired | DROP rejected, active tenant data readable |
| HTAP-PART-02 | N reader pinned, N+1 SEALED+active | old reads N, new reads N+1, no overwrite of results |
| HTAP-PART-03 | interrupted concurrent detach / pending detach / DEFAULT parent | recover via catalog/FINALIZE or safely block, no orphan drop |
| HTAP-PART-04 | forged partition name, worker role DDL request | rejected by allowlist + privileges |
| HTAP-ADM-01 | Global SQL=2, each tenant SQL=1, two JVM workers | never >2 global or >1 per tenant physical/running tasks |
| HTAP-ADM-02 | PREPARE/CLEANUP overload, SQL task and OLTP requests | admission limits all classes; OLTP reserve maintained |
| HTAP-ADM-03 | lost lease with SQL still executing | no release until cancellation confirmed; old fence cannot publish |
| HTAP-RESULT-01 | concurrent same result key, identical/different payload | one immutable result; conflict mismatch BLOCKED_COLLISION |
| HTAP-MIG-01 | fresh and upgraded Flyway from previous HEAD | old runs/reports remain readable, no schema version conflicts |
| HTAP-LOAD-01 | direct vs aggregate vs partition query and GC tests | measured throughput, p95/p99, WAL/lock/partition count and OLTP |
| HTAP-REL-01 | unapproved method or ACTUAL job in shadow/mode OFF | absolutely no execution or production publication |

**Hard correctness fail gates (zero tolerance):** cross-tenant/form/source/currency/grain contamination; unqualified ACTUAL execution; wrong-period comparison; any incorrect `CALCULATED` on incomplete data; over-admission; stale-fence publish; duplicate or mutable result; DROP of a pinned/active cohort; loss of source/report/audit; unauthorized SQL/DDL. All block W8 even when performance is excellent.

**Performance gates:** choose actual dataset shapes, hardware and concrete p95/p99/OLTP/queue/fairness/connection/WAL/retention numerical thresholds **before** measuring. Compare end-to-end build+recompute+cleanup rather than isolated warm SQL. Record cold/warm cache and repetitions. No fabricated “10x” or arbitrary throughput estimates.

## 9. Non-goals, blockers and release decision

Not delivered by this spec: migrated 60 methods, full HTAP infrastructure, approved PeriodCalendar/fiscal models, live aggregate table, physical cohort tables and reaper, tenant capacity scheduler, new SQL indicator implementation, independently reviewed FX or new sources. W1.1 journal's **engineering CI is GREEN** at the audited SHA; that status neither proves W1.2+ execution readiness nor independently approves architecture/method S2. `RELEASE_HOLD` and `ACTUAL` execution HOLD persist.

**Next correct engineering commits:** R2.2 moderator identity/authority RED fixtures and HTAP-W1.2.A source/period/catalog epoch contract RED fixtures; then minimal independently reviewed backend/schema changes. Freeze `FinancialSliceKey`, `FinancialSeriesKey`, calendar and tenant/source inheritance before R2.B PlanCommit. Keep `WorkspacePartitionMaintenanceUtility` read-only until a durable cohort barrier exists. Provide exact SHA, files, tests and CI for each claim.

## 10. External PostgreSQL constraints (official documentation)

PostgreSQL 16 partitioning: https://www.postgresql.org/docs/16/ddl-partitioning.html  
PostgreSQL 16 ALTER TABLE / DETACH CONCURRENTLY: https://www.postgresql.org/docs/16/sql-altertable.html  
PostgreSQL 16 window functions: https://www.postgresql.org/docs/16/functions-window.html  
PostgreSQL 16 row security: https://www.postgresql.org/docs/16/ddl-rowsecurity.html
