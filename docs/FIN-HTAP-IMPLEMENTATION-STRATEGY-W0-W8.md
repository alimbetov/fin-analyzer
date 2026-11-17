# FIN HTAP — Execution Strategy and Iterative Implementation Plan (W0–W8), v1.2

**Date:** 2026-11-17 · **Version:** 1.2 / POST FIN-MR-R2.1 READINESS HARDENED · **Status:** W1.1 + MR-R2.0/R2.0.1/R2.1 engineering CI GREEN; R2.2 + W1.2.A RED NEXT; independent S2 / ACTUAL / HTAP release HOLD  
**Repository:** `alimbetov/fin-analyzer` · **sole branch:** `feat/fin-r0-r1a` · never merge to `main` automatically.  
**Contract hierarchy:** [FIN-HTAP-CONSOLIDATED-TZ.md](FIN-HTAP-CONSOLIDATED-TZ.md) is the overall business and technical normative source; [F166 change, impact and partition](FIN-HTAP-FORM-166-INCREMENTAL-RECALCULATION-AND-PARTITION-LIFECYCLE-TZ.md) governs tenant form/catalog change lifecycle; [ADR-R2-10](ADR-R2-10-FORM-FINGERPRINTS-AND-INCREMENTAL-RECALCULATION.md) versioned form SHA-256; [ADR-R2-11](ADR-R2-11-POSTGRES-BATCH-CALCULATION-EXECUTION.md) SQL routine execution; [ADR-R2-08](ADR-R2-08-LIFECYCLE-PARTITION-RECALCULATION.md) eligible cohort GC. Stricter source/trust, R2 execution-permit, immutable-report and independent financial S2 controls survive all precedence decisions.

## Normative priority override — HTAP-V1 complete vertical (2026-11-17)

[ADR-R2-14](ADR-R2-14-FIRST-VERTICAL-SLICE-DELIVERY-FREEZE.md) freezes the **first product delivery** as a real SYNTHETIC `FIN-01/REV_NET_MOM` end-to-end outcome; this roadmap's W0–W8 remains the architectural dependency graph, **not** permission to build broad W2–W6 frameworks before a tested slice. Execute smallest V1.0→V1.5 steps: two-month trusted F02 source → deterministic R2.A/B PLAN_ONLY → bounded synthetic W2 → exact W3 SQL/Java golden → immutable W4.1 result/report → restart/E2E. Only then V1.6 W6 workload/physical benchmarks and broader 60 methods. Historic `R2 M1` is still PLAN_ONLY (not the new product milestone `HTAP-V1`). Same-SHA CI, independent financial S2, strict tenant/source/currency/calendar and deny-all ACTUAL hold are unchanged. No optional platform initiative may be treated as a V1 predecessor without reproducible failing test or measured bottleneck.

## MR-R0 incremental delivery — shared configuration registry

[ADR-R2-12](ADR-R2-12-VERSIONED-METHODOLOGY-REGISTRY-MODERATION-AND-PARAMETERS.md) adds one PostgreSQL read view for all **60** method families and immutable `(methodCode,variantCode,algorithmVersion,parameterContractVersion,configVersion,params/effective SHA-256)` configuration semantics with independent moderation and audited CAS activation. This is **a safe parallel read-only catalogue foundation**, not automatic method execution. V20261117_02 creates the initial registry and 60 rows; V20261117_03 adds latest review/progress read status; financial S2/ExecutionPermit remains separate. First code-validated parameters: CON-02/ABC_SALES A/B thresholds (0 < A < B < 100). Other method families must acquire specific parameter contracts and independent financial goldens before activation. A generic JSONB configuration is not executable by default.

**Additional acceptance gate MR-R0:** PostgreSQL 16 Testcontainers prove 60 exact catalogue rows, duplicate and immutability protection, invalid thresholds, SHA-256 on input change, moderator separation of duties, rejection/no-review block, pointer CAS/activation audit, and zero method execution. Cross-tenant/form/period/currency ResultKey and regeneration remain W3/W4. S2 and code-level access control remain HOLD.

## Hard pre-implementation audit / W0 safety corrections

[HTAP S1 hard technical review](FIN-HTAP-HARD-PREIMPLEMENTATION-TECHNICAL-REVIEW-2026-11-17.md) is the controlling supplemental RED/GO/HOLD register after the post-R2.1 readiness audit. W0.A code now removes a Java/SQL method evidence status disagreement and returns deep immutable JSON configuration snapshots (`6b543f2`); W0.B forward-only V20261117_08/V20261117_09 close unaudited DELETE and scope-key rewriting of active-config pointers; full deactivation authority and owner/TRUNCATE privileges remain R2.2/W7 gates. Neither patch authorizes calculation or qualifies method S2.

**Before R2.B PlanCommit:** authenticated moderator actor, exact method-S2 effectiveConfig binding, vetted algorithm artifact manifest, branch/re-review/revocation policy, and published FactSet source pins. Current `MethodVariantRegistry.requireExecutablePlanBinding()` always blocks unpinned variants. The existing R1 report writer and CORE publisher are separate write paths and MUST NOT be used as generic HTAP result promoters. Only distinct `FormImpactPreview` from unqualified drafts and `MethodologyPlanPreview` from published facts are eligible for early read-only work.

**Before W4 physical design:** define numeric, owner-approved OLTP p95/p99 impact, WAL/bytes/retention thresholds and compare DIRECT/AGG/HASH/RANGE on a realistic fixture. W4.2 DDL remains independently HOLD even if a synthetic SQL method runs.

## Current engineering baseline and approved work boundary (2026-11-17)

**Readiness audit:** [FIN-HTAP-TZ-READINESS-AUDIT-2026-11-17-R21.md](FIN-HTAP-TZ-READINESS-AUDIT-2026-11-17-R21.md) supersedes outdated assumptions about method config validation, legacy execution and R2.A/B timing. Checked code SHA `45d3f37a895ae73d3934ae9979495d28c1b1626b`; FIN CI #44037588595 and FIN Backend Domain #44037588533 succeeded with 1,972 backend tests. This is **engineering** proof, not architecture S2 or financial qualification.

R2.0 fixed JPA/Flyway CHAR(64); R2.0.1 installed a production deny-all guard for `SingleExecutorScheduler` (also when legacy flag is enabled). R2.1 added versioned explicit `CON01_PARETO_FIXED_V1` / `CON02_ABC_TYPED_V2` PostgreSQL profiles and a pure Java decoder. R2.1 **did not** bind config to runtime adapters, permit new ACTUAL work, publish 60 methods or add a PlanPreview. The historic CON-02 numeric-only profile remains valid history, not a free pass for future execution.

**NEXT:** FIN-MR-R2.2 immutable moderator identity, authorization/approval and version/S2 binding; HTAP-W1.2.A source+period+catalog-epoch contracts and RED tests. Independent R2.A applicability and W1.2.B impact preview are read-only. R2.B immutable PlanCommit is blocked until exact config/source lineage is pinned.

## 0.0. Methodology coverage and actual P0 implementation snapshot

**Machine-readable evidence:** [FIN-HTAP-METHODOLOGY-REALITY-AND-P0-RECOVERY-PLAN.md](FIN-HTAP-METHODOLOGY-REALITY-AND-P0-RECOVERY-PLAN.md). The source catalog contains **60 families = 5 wired but unqualified single variants + 13 candidate algorithms + 42 unaudited**, **0 release-qualified**, **0 auto-run allowed**. The `MethodVariantRegistry`'s seven read-only descriptors are **five business variants + two SYS helpers**. Do not claim that HTAP or the 60 methods are delivered; one full qualified published-FactSet→immutable-result vertical must precede production claims.

**First P0 guards now coded:** `HtapCalculationPeriodPolicy` classifies all six real source PeriodTypes and four Coverages and admits only the explicit `MONTH/PERIOD_ONLY/INTERVAL` **candidate**, without granting calculation/ACTUAL; `CatalogXlsxRevisionService.confirm` rejects a draft whose schema SHA does not match current server schema and retains schema equality as part of NO_OP. `MethodologyCatalogContractTest` pins the 60/5/13/42 evidence count. New JUnit/PostgreSQL tests provide negative seams; they are not independently proven GREEN until current SHA CI completes.

**Correction to prior review:** existing `CatalogXlsxRoundTripService.normalizedFingerprint` **already hashes `schemaFingerprint`**, so a mere changed structural schema with correctly generated draft hash is **not** invisible in the old V1 normalized SHA. Remaining P0 is independently versioned **financial mapping/activation epoch** and multi-node schema compatibility; do not pretend the new schema guard solves that. W1.2.A2 adds mapped financial policy and separate versioned hashes under RED/S2. All computations retain previous FIN-01 v1 same-source constraints, W3 shadow-only, and read-only partition inspector.

## 0.1. Technical review disposition and sequence precedence

**Controlling S1 technical review:** [FIN-HTAP-IMPLEMENTATION-STRATEGY-TECHNICAL-REVIEW-2026-11-17.md](FIN-HTAP-IMPLEMENTATION-STRATEGY-TECHNICAL-REVIEW-2026-11-17.md). Reviewed GitHub HEAD `4963b76813a5154a70b738ff54d398b12619d251`; full [FIN CI](https://github.com/alimbetov/fin-analyzer/actions/runs/44021438326) and [Backend Domain](https://github.com/alimbetov/fin-analyzer/actions/runs/44021254124) succeeded for W1.1 code and related documentation. **8 P0 and 8 P1 stage-specific findings**: P0 are next-stage/release blockers, not a claim of failed current W1.1. Independent S2 has not been performed.

**Priority rules added by review:** W1.2 is split into **W1.2.A source/period/coverage/catalog epoch contract and RED tests**, then **W1.2.B read-only impact consumer and idempotent delivery checkpoint**. Do not implement an active consumer before A passes independent review. W1.3 must maintain a distinct per-point source revision vs complete TrustedComparisonSet hash. W3.1 is *read-only SYNTHETIC SHADOW* and cannot persist/publish new qualified current results before W4.1 immutable generation, fencing and receipt gates. PostgreSQL reaper stays disabled until W4.2 liveness/DDL recovery proof. `WEEK, HALF_YEAR, AS_OF, YEAR_TO_DATE, LIFE_TO_DATE` are **existing source period or coverage modes** which require explicit mapping; only approved source→calculation policies are executable.

**Source update coverage:** `CatalogXlsxRevisionService.confirm()` emits W1.1 events; separate `FormDraftRevisionV5Service.confirm()` (F02/V5) does not emit this event and is not automatically included in “all 166 catalog changes.” An explicit typed adapter/scope decision and tests are required before advertising universal form-update event capture.

## Current W1.2.A incremental code and next gates

The [W0/W1.2.A implementation and W2/W3/W6 audit](FIN-HTAP-W0-W12A-IMPLEMENTATION-REVIEW-W2-W3-W6.md) defines actual code paths, persisted mapping **proposals**, calendar and source negatives and the stop/go matrix. First source resolver is **read-only and mapping-unqualified by design**. To reach W1.2.A completion, add authenticated mapping approval and catalog epoch, source/form/calendar selection, generated revision compatibility, populated PG16 migration and same-SHA CI. Only after immutable PLAN_ONLY and trusted calendar/source qualification may W2 scheduling and then W3 synthetic experiments begin. W6 comparisons remain benchmark designs until owner-defined budgets and measured data. Do not retroactively qualify an existing adapter or activate ACTUAL because a new resolver finds a published assertion.

## W0 Physical Design Freeze — performance and data correctness

The normative initial storage-layout choice is [ADR-R2-13](ADR-R2-13-HTAP-PHYSICAL-LAYOUT-PERFORMANCE-FIRST.md), linked from the consolidated FIN HTAP specification. **Do not implement `RANGE(period)` across all domains or multiply child tables by tenant×form×currency×revision**. Keep `fin_fact` unchanged; unpartitioned durable control and optional `fin_agg` baseline; bounded retention-class `RANGE(reclaim_epoch)` only for qualifying large disposable `fin_workspace` stage payload. Directory resolution and exact scoped predicates are mandatory for pruning. Retention class, no-DEFAULT, privilege separation, safe cohort GC and physical resource caps are W0/W4.2 contracts.

W0 acceptance must name performance budget owners, dataset shape/tenant skew, index size/WAL/lock/OLTP reserve/partition-count envelopes and repeatable cold/warm/direct/cache/staging benchmark fixtures. **Numeric p95/p99 thresholds are owner-approved before experiments**, not assumed by code or guessed from previous CI. W6 compares unpartitioned vs HASH vs period-RANGE aggregate layout and daily vs weekly reclaim cohorts, keeping only independently proven beneficial alternatives. No measured speed-up claimed, no production DDL or ACTUAL granted by the ADR. Add negative tests for same financial period but different revisions/currencies/forms, stale generations, detached shared active cohort, no-prune query and cross-tenant accesses.

## 1. Adopted topology and hard prohibitions

**One Java 21 / Spring Boot backend orchestrator**, existing immutable `FACT_SET_PUBLISHED` outbox, plus a **separate durable nonfinancial form-change journal inside the same application and dispatcher**. PostgreSQL 16 is the single transactional source and set-based batch computation engine. Each SQL method is a **versioned, qualified `MethodModule`** implemented as allowlisted, Git/Flyway-reviewed static SQL or typed PostgreSQL `FUNCTION`/bounded `PROCEDURE` without internal commits, executed through JDBC with bound parameters. A catalog table holds immutable *metadata*, routine signature, parameter contract, schema/method version and SHA-256, **not executable arbitrary SQL bodies**.

No new scheduler, no unsandboxed `EXECUTE(sql_from_table)`, no dynamic DDL by SQL method, no one-function-per-tenant/form/period, no mixing of tenant/form/grain/currency, no rewriting sources/results, no implicit FX, no background ACTUAL from draft confirmation. No speculative automatic qualification of all 166 forms. No production activation until a separate signed method permit and independent S2.

**Global physical partition policy:** only large *reconstructible workspace stage payload* is eligible for lifecycle-cohort partitioning and late whole-cohort `DETACH/DROP`. The precise business key `Tenant + FormCode + PeriodType + Period + Currency` remains a logical financial slice identity, further scoped by source/metric/entity/basis/calendars. Default physical design candidates: unpartitioned vs fixed HASH for reusable aggregates, bounded `RANGE(reclaim_epoch)` for disposable workspace stage payload. No child partition per business slice. The existing read-only `WorkspacePartitionMaintenanceUtility` is reused for catalog inspection and dry-run diagnostics; **never enable DROP** until positive membership/drain/reader/lease proof and privileged DDL tests.

## 2. Source event matrix and boundary

| Origin | Trigger | Durable record | May calculate? |
|---|---|---|---|
| Tenant confirms new catalog-form draft | `CatalogXlsxRevisionService.confirm` succeeds with a new revision | New `fin_exec.form_change_journal` in same @Transactional revision+slot CAS | **No ACTUAL**. Automatic validation and future read-only impact preview only |
| Tenant repeats unchanged draft | Service returns prior confirmed revision | **No additional journal event** | No |
| Tenant publishes exact approved FactSet | Verified immutable `fin_fact.fact_set_publication` | Existing `fin_exec.fact_publication_outbox` in publication transaction | Only separately approved variant/ExecutionPermit |
| Global catalog form schema/mapping activation | Server-approved catalog release/definition epoch (future) | Proposed `form_definition_activation` and form-change journal event | Validation/fan-out; financial requalification before execution |
| Method/calendar/FX policy version changes | Independently reviewed version activation (future) | Version-pinned impact plan/registry event | Only new qualified method result identity |

The journal event MUST NOT claim that a tenant has published financial facts. The trustworthy `form_code`, `PeriodType`, `Period`, `Currency`, entity/scope/project/basis and content SHA are resolved from existing immutable draft revision joined with tenant-bound slot/project. A journal's unique immutable `revision_id` is a reference to a fact of *draft revision creation*, not an execution authorization.

## 3. Source-of-truth hashes and change detection

Retain existing `fin_intake.catalog_xlsx_draft.schema_fingerprint`, `original_sha`, `normalized_sha`, optional `document_sha`, and published `snapshot_hash` unchanged and backwards-compatible. Future v2 fingerprints are separate, domain-separated SHA-256 canonicalization version and typed digests for **schema**, financial **mapping**, **presentation**, ordered semantic **content**, raw document bytes, **context-bound form**, selected qualified **source** and method/input **result**. No MD5 or JVM `hashCode()` for authoritative finance identity.

New semantics:
- `same semantic ordered content + same evidence + same exact context` => NO_OP, zero heavy work;
- only presentation changes => no numeric recalculation;
- mapping/PeriodType/calendar/FX or method implementation changes => versioned dependency impact and possible requalification even with unchanged cell values;
- published source changes => newly sealed comparison set and recalculation of **qualified descendants**, never old result UPDATE.
- same content hash across two tenants or currencies cannot share access, event or result solely by digest equality.

## Редакционные варианты перед модерацией — FIN-MR-R2.3

[FIN-MR-R2.3](FIN-MR-R2.3-EDITORIAL-PRESETS-AND-METHODOLOGY-EDITIONS.md) — независимый **неисполняемый** редакционный слой, который можно проверить параллельно R2.2 и W1.2.A: typed `method_variant_edition`, максимум 10 уникальных вариантов на редакцию, явные шаги и server-computed SHA каждого варианта/набора. Это **не** отдельный этап автоматического расчёта. Выбранный вариант не становится `method_config_version` / active pointer / method S2 без R2.2; no R2.B PLAN_ONLY becomes runnable from preset presence. Начальные варианты поддерживаются только для CON-01 и CON-02.

## 3.A. Cross-roadmap sequence — method PlanCommit cannot wait until W4

```text
R2.0/R2.0.1/R2.1 GREEN -> W0
                       -> R2.2 + W1.2.A (independent safe contracts/RED)
                       -> R2.A + W1.2.B (read-only separate previews)
                       -> R2.B PlanPreview + immutable PlanCommit, NO EXECUTION
                       -> W1.3 trusted source + W2 permit/capacity/fencing
                       -> W3 SYNTHETIC SHADOW, read-only/no trusted receipt
                       -> W4.1 immutable result/report CAS -> W4.2 reaper gate
                       -> W5 qualified DAG -> W6 perf -> W7 ops -> W8 signoff
```

R2.B starts with the exact, verifiable existing **single-published-FactSet** path. Composite multi-FactSet history requires W1.3 TrustedComparisonSet sealing and an independently qualified selector **before** such a plan can be committed. W1.3 may follow the initial R2.B PLAN_ONLY prototype only for this restricted single-FactSet scope.

A **form-change impact preview is not a methodology PlanPreview**. A draft event cannot become a trusted `FACT_SET_PUBLISHED` event by reclassification. R2.B plan authority is a predecessor of W2, whereas W4.1 is the first production-grade receipt/report-head generation. `LEGACY_TEST` is a `src/test` fixture permission only and MUST NOT become a production `fin.execution.mode`; target modes, all default disabled, are `OFF|PLAN_ONLY|SYNTHETIC_SHADOW|WORKSPACE`. R2.0.1 deny-all legacy execution must stay effective until a separately accepted executor replaces it.

## 4. Work breakdown, with stop/go proof (do not skip predecessors)

| Gate | Build in one branch, small incremental commits | Required PASS before advancing |
|---|---|---|
| **W0 / Freeze** | Define trusted FinancialSliceKey/FinancialSeriesKey, PeriodCalendar, input status and hash policy; dependency/permission and DB role threat review; integrate R2.2 and R2.B prerequisites; mark S2 pending | Spec consistency, 166 readiness classifications and negative-model tests documented |
| **W1.1 / Form signal (FIRST EXECUTABLE INCREMENT)** | Additive Flyway `V20261117_01__htap_form_change_journal.sql`; append event ONLY for new confirmed `catalog_xlsx_revision` in SAME `CatalogXlsxRevisionService.confirm` DB transaction | `F166-EVT-01..04`, no-op, rollback, concurrent CAS, immutable event, cross-tenant FK, all 166 draft forms, no fact publication |
| **W1.2.A / Freeze Source, Period/Coverage & Catalog Epoch** | Confirm 166 catalog vs F02/V5 event scope; total mapping for `ReportingPeriodV5.Type` WEEK/MONTH/QUARTER/HALF_YEAR/YEAR/AS_OF and Coverage PERIOD_ONLY/YEAR_TO_DATE/LIFE_TO_DATE/AS_OF; schema+mapping hash and approved catalog epoch; immutable joined event read DTO | RED: unsupported source periods and YTD/LTD cannot become monthly flow, changed schema/mapping with same cells is not invisible NO_OP, wrong tenant/slot/revision is rejected, two catalog deploy versions cannot silently mix. S2 review of contract before consumer |
| **W1.2.B / Read-only Impact & Durable Delivery** | Exactly one Java dispatcher; separate delivery/checkpoint, idempotent tenant-scoped event reader and explicit versioned reverse dependency bindings; read-only **FormImpactPreview**, never MethodologyPlanPreview, with bounded period horizon | 166/166 deterministic classification, no ACTUAL, no extra fact publication, 2-node duplicate-safe impact replay; WAITING_QUALIFICATION for nonnumeric contracts |

| **W1.3 / Trusted source and history** | PeriodCalendar source completeness, versioned source selection, TrustedComparisonSet and sealed financial slice, source publication event wiring | PostgreSQL row/period/currency/tenant/source negatives and sealed count/hash concurrency tests |
| **W2 / Admission** | **After R2.B PLAN_ONLY**: one effective `fin.execution.mode` OFF by default, exact ExecutionPermit, global+tenant+resource SQL/JAVA/PREPARE/CLEANUP capacity, durable lease/fence, tenant fairness, physical SQL stop | 2 JVM competing tests: zero oversubscription or stale commit, OLTP connection reserve |
| **W3.1 / SQL Batch SHADOW pilot** | One FIN-01 REV_NET MoM SYNTHETIC **read-only/shadow** trial; static or versioned function; parameter allowlist and SHA; window completeness with batch halo; exact SQL/Java BigDecimal reason/value parity | SQLB-01..14 subset and full FIN-01 golden/negative, no ACTUAL, **no new trusted receipt/current-generation promotion before W4.1** |
| **W3.2 / Auto indicators** | DELTA, MoM/QoQ/YoY, SMA3/6/12, TTM12 FLOW, STDDEV separate versioned descriptors, same SeriesKey; exact period/currency | Java vs SQL deterministic status, windows, rounding, missing/negative and source |
| **W4.1 / Immutable calculation result** | **After R2.B immutable plan**: Gen N+1 attempts, fenced batch stage writes and seal, atomic per-method ResultKey and **complete required-report-set manifest** head CAS, old pinned generations safe | restart/retry/crash and out-of-order r2/r3 2-node tests, no lost immutable receipts, **no partially refreshed report marked current** |
| **W4.2 / Partition lifecycle** | Durable complete membership ledger, privileged precreate and allowlisted detached-child catalog, reader/lease drain barrier, PostgreSQL 16 `DETACH CONCURRENTLY`/FINALIZE/DROP journal | multi-tenant pinned cohort must not drop; crash between DDL steps; no DEFAULT; workers denied DDL; real PostgreSQL |
| **W5 / Qualified DAG** | Cross-form method dependency DAG, method migration, versioned catalog form definition activation and paged targeted fan-out | Only reviewed input bindings, actual S2 per variant and no launch of unqualified forms |
| **W6 / Performance** | Compare DIRECT, unpartitioned AGG, fixed HASH AGG, RANGE lifecycle stages; direct SQL vs reviewed FUNCTION vs bounded batches; DELETE vs DETACH/DROP | Real workloads, tenant skew, 1/2/4/8 SQL workers, WAL/temp, OLTP p95/p99, end-to-end including build+cleanup, thresholds frozen in advance |
| **W7 / Security+Ops** | Real least-privileged DB roles, audited RLS/pool reset, DDL maintenance role, metrics/tracing, DLQ, recovery and retention | Multi-tenant ABAC, SQL module no DDL, policy downgrade denied, incident drill |
| **W8 / Acceptance** | Same HEAD full CI/Backend Domain, PostgreSQL negative suite, FIN-01 SQL/Java parity, S2 reviews and operational release record | No P0, independent method S2, separate ACTUAL/release approval |

## 5. Current W1.1 implementation declaration (status must be read with CI)

As part of this initial engineering slice (not a claim of fully verified GREEN):
- `backend/src/main/resources/db/migration/V20261117_01__htap_form_change_journal.sql` creates `fin_exec.form_change_journal` with tenant+slot FK, revision+slot FK, unique revision event, immutable UPDATE/DELETE trigger, indexes for scoped and pending scan.
- `backend/src/main/java/kz/fin/backend/intake/CatalogXlsxRevisionService.java` appends `TENANT_FORM_REVISION_CONFIRMED` after CAS inside existing `@Transactional` method, without any second outbox or scheduler.
- `backend/src/test/java/kz/fin/backend/intake/CatalogXlsxRoundTripIntegrationTest.java` verifies one event per all 166 codes, zero event for duplicate, two revisions/two events on changed content or two concurrent success attempts, rollback of event+revision+slot, immutable/tenant FK and no `FACT_SET_PUBLISHED`/analysis job.
- Existing read-only partition inspector is unchanged and keeps `DETACH/DROP` unavailable.

**Not implemented:** a consumer of form-change journal, activation of global 166 form definitions, v2 SHA manifest, versioned impacted-period graph, actual calculation SQL/Java dispatcher, partitioned physical workspace, DROP reaper or auto financial ACTUAL. Mark those as planned and only turn ON with independent evidence.

## 6. Code and test safety practices

- **Never edit historical Flyway**; add only monotonic version scripts. For each migration test fresh PostgreSQL 16 and upgrade from prior committed schema, preserve data/indexes/checksums, then test rollback/retry and referential integrity.
- **RED before GREEN**, test actual PostgreSQL 16, not H2-only/mock success. A new P0 invariant must have both negative (reject) and positive (accepted exact source) test, and deterministic output. A test suite named PASS is not a proof until CI/SHA is checked.
- **Database worker privileges:** ordinary Spring app role may append its event while confirming; it must not perform partition DDL or be owner of `fin_fact` in production. Dedicated maintenance DDL role is separate and not inherited by SQL routine worker.
- **Financial safety:** SQL routines MUST operate on SEALED input, exact trusted selection, tenant, form, PeriodType, Period, Currency, metric+version+dimension, basis/dataSpace and calendar. Period is in point key but not in window grouping. Missing/partial periods must never be filled with zero automatically; currency conversion is a separately reviewed transform.
- **SQL routine registry:** DB stores metadata/digest, the vetted SQL is in Git/Flyway; never arbitrary user SQL from table and no second PostgreSQL scheduler. PostgreSQL routine executes bounded set-based work in caller-managed transactions, no internal `COMMIT`.
- **GC:** a single expired tenant/form slice cannot cause `DROP` of a shared cohort; only a full drained cohort can be detached/dropped. Physical partitions are server precreated by date/retention class, not named after source hash/tenant/currency.
- **Security:** event read/write is tenant-scoped with composite FKs and authenticated controller. Application-level `FIN_* ` ABAC remains authoritative; no SQL text/table name from a tenant user.
- **Ops:** record exact SHA for migration, tests, CI, S2, performance and release decisions; `RELEASE_HOLD` remains until separately removed by approved process.

## 6.A. Review-specific hardening before the first active impact consumer

- **Current-period input modes are NOT equivalent to calculation grains:** source `YEAR_TO_DATE` is cumulative, `LIFE_TO_DATE` is cumulative over a different origin, `AS_OF` is stock at an exact date; none can be substituted for a monthly interval FLOW. First eligible pilot is explicit `MONTH/PERIOD_ONLY/INTERVAL` on one verified calendar, currency and metric.
- **No-op is not merely the same cells:** original form-row and document semantics are respected by W1.1; a future catalog schema hash, approved mapping version or activation epoch also participates in interpretation. Older revisions remain readable with historical versions.
- **Two input paths:** 166 code catalog XLSX and special F02/V5 confirmation have different persistence; any universal update claim requires a deliberate event adapter, not blindly joining `draft_revision_v5` to `catalog_xlsx_slot`.
- **Journal delivery is not an approval:** separate delivery ledger; immutable tenant/slot/revision join, at-least-once idempotency and multi-node claim. A successful classified draft may only produce impact preview and WAITING_PUBLICATION, never `fin_exec.analysis_run` from this event alone.
- **Source history:** old FIN-01 source-revision equality remains normative for current variant. To allow different revisions across periods, seal a reviewed TrustedComparisonSet and add a different variant plus independent S2; never weaken the old pilot's negative tests.
- **Physical partition readiness:** use `WorkspacePartitionMaintenanceUtility` to inspect only. Reaper and cohort membership remain W4.2, SQL calculation scripts have no DDL permissions. PostgreSQL 16 `DETACH CONCURRENTLY` outside explicit transaction, needs crash/FINALIZE reconciliation, careful FK/lock review and no DEFAULT parent.
- **Production evidence:** current W1.1 has same-HEAD successful workflows, but a separate upgrade-from-populated-V20261116_13 fixture, explicit DB role deny checks, external S2 and all later numeric performance are unverified; do not infer those from FIN CI's GREEN.

## 7. W1.1 Definition of Done and immediate follow-up

To mark W1.1 GREEN, record evidence for:
1. PostgreSQL Flyway migration fresh database + from previous V20261116_13 state, correct FK/UNIQUE/immutable event trigger; verify no unexpected DDL.
2. For 166 forms, `CatalogXlsxRevisionService.confirm` adds exactly one event to **the same commit**, including documentary and unapproved schema forms, but no finance source publication or result.
3. Same-content idempotency, new ordered rows despite unchanged legacy SHA, document hash changes, simultaneous confirmations and external transaction rollback.
4. Cross-tenant forged event denied, irreversible/immutable journal row tested, no event for unsuccessful confirm.
5. Current HEAD's `FIN Backend Domain` and `FIN CI` outcomes explicitly known and attributed; no skipped test or stale run claims.

Alongside R2.2 moderator/contract authority hardening, implement W1.2: a **read-only, tenant-verified form-change impact preview** backed by the existing catalog form classification and explicit method-binding registry, not any financial SQL executor. Keep W2–W4 blocked until their own RED proofs exist.

## 8. Rollback and recovery

If release with W1.1 fails: disable any future *consumer* (currently none) and revert the Java event emission only with a separately reviewed migration/compatibility plan; **do not drop the immutable journal containing committed history** or rewrite an old migration. For a failed form confirmation, ordinary PostgreSQL transaction rollback removes slot-head change, revision and event atomically. On restart, an event with no delivery record remains durable and unprocessed until W1.2; duplicate scanning must use immutable event_id/revision_id and idempotent delivery in a **separate** table.

**SQL/partition functionality is intentionally absent from W1.1.** No on-demand physical partition creation and no background work that could affect tenant OLTP have been enabled.
