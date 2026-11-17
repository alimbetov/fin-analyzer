# FIN HTAP — Form-166 Change Propagation, Incremental Recalculation & Partition Lifecycle

**Specification:** FIN-HTAP-F166 v1.0 · 2026-11-17  
**Decision:** DESIGN READY FOR INDEPENDENT S2 / IMPLEMENTATION HOLD  
**Repository:** `alimbetov/fin-analyzer` · **Only branch:** `feat/fin-r0-r1a`  
**Normative parent:** [FIN-HTAP-CONSOLIDATED-TZ.md](FIN-HTAP-CONSOLIDATED-TZ.md) v1.1. In case of conflict, this document governs *catalog/form change detection and selective recalculation*, while the parent and preceding ADRs govern trust/security/release. Stricter safety invariants always prevail.  
**Code reviewed at design:** HEAD `e5155afcd963c61b967ec9d5ea9e15fc629b1a08`; revalidate current SHA before implementing.  
**Applicable database:** PostgreSQL 16; backend Java/Spring Boot/Flyway. No new frontend, IAM service, task scheduler, branch or ACTUAL execution permit.

## 0. Goal and exact scope

When **a tenant changes one of the 166 catalog forms** or **an administrator activates a new version of a catalog form definition**, FIN must automatically identify the *affected tenant(s), financial slices, time windows, aggregation cells, dependent methods and report projections*, schedule only safe work, compute a new verified generation and publish immutable results **if and only if** the original trusted source and method are qualified and independently permitted.

The exact business key is:

```
Tenant + FormCode + PeriodType + Period + Currency
```

The physical partition is **not** that 5-axis key. The full trusted row/series identity additionally pins economic entity, business scope, project when applicable, reporting basis, dataSpace, selected published FactSet/source fingerprints, form version, metric/version, unit, dimension/population, fiscal calendar, assumptions and methodological versions. Time comparison changes `Period` while keeping the other context fixed.

**Support all 166** means *each form can enter change detection, lineage, impact classification and freshness status*. It does **not** mean that all 166 form codes are numeric, approved or executable. The current `CatalogCaptureRegistry` contains **122 TYPED_DRAFT, 12 EVIDENCE_ONLY and 32 SCHEMA_NOT_APPROVED**. A document/evidence-only or unqualified form may invalidate a preview or be reported `WAITING_QUALIFICATION`, but it must **not** generate authorized ACTUAL financial results.

## 0.A. SHA-256 form fingerprint as a first-class recalculation contract

**Normative extension:** [ADR-R2-10 — Versioned SHA-256 Form Fingerprints](ADR-R2-10-FORM-FINGERPRINTS-AND-INCREMENTAL-RECALCULATION.md). **MD5 is not permitted** for authoritative content, schema, source or result identity. Existing `schema_fingerprint`, `normalized_sha`, `original_sha`, `document_sha`, `fact_set_publication.snapshot_hash` already use SHA-256 and retain original historical semantics.

A form is represented by **distinct, versioned** `schema_hash_v2` (fields/requiredness/cardinality/validation/type/period rules), `mapping_hash_v2` (field→metric/version and financial transformation policy), `presentation_hash_v2` (only display/language), `canonical_content_hash_v2` (ordered, typed normalized form rows + explicit value states and evidence descriptor), `original_file_sha256` (raw bytes) and a tenant- and period-bound `context_form_hash_v2`. The complete source/result hashes additionally include published FactSet and exact calculation policy. Never use a bare form digest as a security token, approval or permission to run ACTUAL.

**P0 migration compatibility:** `CatalogXlsxRevisionService.confirm` currently compares legacy `normalized_sha` PLUS ordered `normalized_rows` PLUS optional `document_sha`; the legacy `normalized_sha` sorts row IDs, and row permutations may leave it unchanged. **Do not replace or silently reinterpret legacy columns**. Add a versioned canonical v2 manifest alongside them, with explicit schema/algorithm/canonicalization versions, tenant/slot/revision FKs and same-transaction journal. `content_hash_v2` must account for order whenever order affects semantics. No-op detection must retain the existing complete comparison; new hash equality is a fast prefilter, not independent proof.

**Recalculation:** unchanged structural+mapping+semantic content hashes with same source context -> `NO_OP`; changed `presentation_hash` only -> presentation refresh, **no numeric recompute**; changed `mapping_hash` -> impacted method bindings review and requalification; changed semantic content -> tenant form change journal and draft impact; **only a separate approved `FACT_SET_PUBLISHED`** can activate qualified financial calculations. Hashes are not physical partition identifiers: lifecycle cohorts are server-owned and `DROP` remains barrier-protected.

**New mandatory RED matrix:** `F166-HASH-01..14` in ADR-R2-10 (order-preserving canonicalization, same values across tenants/currencies, schema-vs-mapping-vs-presentation, changed XLSX bytes but unchanged normalized data, SHA/lineage consistency, race/rollback, ABA, Java/PostgreSQL reference vectors and migration coexistence). P0 acceptance of first v2 hash manifest requires real PostgreSQL 16 Testcontainers and independent S2.

## 1. Verified source-system behavior (implementation baseline, not target behavior)

| Existing component | Observed behavior | Design implication |
|---|---|---|
| `CatalogCaptureRegistry` with `/fin/form-compatibility.tsv` | Static, read-only catalog of 166 codes; version `FIN-CATALOG-CAPTURE-1`; three applicability classes | There is **no durable global catalog-definition change event** or automatic 166-form recalculation on descriptor deployment |
| `CatalogXlsxRevisionService.confirm` | Tenant/project/slot-bound, `@Transactional`; locks `catalog_xlsx_slot` row `FOR UPDATE`; increments `latest_revision` and appends immutable `catalog_xlsx_revision` as `CONFIRMED_UNQUALIFIED_DRAFT`. Duplicate canonical+ordered row+document hash returns existing revision without increment | No-op confirmation must NOT schedule recalc; real confirmation can atomically append a **draft change signal**, never directly authorize financial ACTUAL execution |
| `fin_intake.catalog_xlsx_slot` | Unique `(project_id, form_code, reporting_basis, period_type, period_code, coverage, currency, amount_scale)`; project binds tenant/entity/scope | A slot is a precise tenant period currency address. Do not assume currency/form version can be edited in place; updates to identity require a different slot |
| `fin_fact.fact_assertion`, `approved_source_revision`, `fact_selection`, `fact_set_publication` | Strict immutable approved financial source; form provenance via approved source revision | Trusted method computation starts **after source approval and published FactSet**, not after a draft confirmation |
| `fin_exec.fact_publication_outbox` | Immutable same-transaction `FACT_SET_PUBLISHED` event, one event per tenant+factset publication, typed event constraint | Existing outbox remains sole publication trigger; do **not** inject catalog/draft messages into its `FACT_SET_PUBLISHED` event type |
| `SingleExecutorScheduler` / `analysis_run` | Existing legacy one-event/one-task tick, one `event_id` per run and `(run_id,method_code)` uniqueness | New versioned recalculation plan identity and safe mode gate required; do not reuse historic event/run IDs to force an automatic rerun |
| `IndicatorRelationshipRegistry` and `MethodVariantRegistry` | Explicit (limited/unqualified) form→metric bindings and seven legacy module descriptors, no universal qualified dependency registry | Impact graph is versioned and **fail-closed**; no guessing formulas from form title or global “run all 60” fallback |
| `WorkspacePartitionMaintenanceUtility` | Read-only inspector; all retirement previews blocked | Automatic destructive DROP is not implemented and must remain disabled until catalog/membership/reaper proof exists |

**P0: distinguish TWO independent change channels.** Tenant form-data revision changes source content; global form-definition change changes parsing/mapping/schema policy. Their authority, fan-out and replay consequences are different.

## 2. Events and the trust boundary

### 2.1. Tenant form draft confirmed — `TENANT_FORM_REVISION_CONFIRMED`

**Source:** existing `CatalogXlsxRevisionService.confirm()` (and independently reviewed V5 F02 path). Only when confirmation creates a **new immutable revision**, append a **durable, tenant-scoped recalculation-intent journal row in the same database transaction**; exact event key includes `organization_id, project_id, slot_id, revision_id, normalized_sha, form_code, period_type, period_code, currency, revision_number`. A no-op identical confirmation emits no new event. A rolled-back confirmation emits none. Do not rely on an in-memory Spring event, post-commit-only listener, or a best-effort HTTP callback as the sole guarantee.

The event may automatically run **draft safety work** (schema validation, normalization/shape check, version comparison, draft freshness, impact preview, blocking explanations) under PREPARE budget. It cannot manufacture `approved_source_revision`, `fact_set_publication`, trusted `MethodInput` or permitted ACTUAL. Mark dependent production results only as **CANDIDATE_STALE / UNPUBLISHED_CHANGE**; previously published receipts stay valid for their original pinned source, even if no longer current. An optional visible draft financial preview must be segregated and labelled `UNQUALIFIED_PREVIEW`, with no immutable approved result promotion.

### 2.2. Trusted FactSet published — `FACT_SET_PUBLISHED`

**Source:** pre-existing immutable `fin_exec.fact_publication_outbox` in the source-publication transaction. This is the sole trusted computation trigger. On consumption, one existing Java orchestrator derives changed approved assertions/forms/periods vs the **parent/current trusted population**, checks eligibility and execution authority, builds a versioned `RecalculationPlan` and admits only qualified tasks. Do not equate publication with execution permit: **ACTUAL HOLD remains** until independently approved source/method/authority.

If a published FactSet includes multiple forms, compute differences **per canonical selected assertion identity** and build one unioned minimal affected set; do not execute a separate full 60-method run for every form. If contents/policies are canonically unchanged, no new derived result or rewrite occurs (audit the no-op decision).

### 2.3. Catalog form definition activated — `CATALOG_FORM_DEFINITION_ACTIVATED`

The current catalog is **versioned static deployment content**, not a mutable production admin form table. Introduce a server-controlled `form_definition_manifest(form_code, definition_version, schema_fingerprint, mapping_fingerprint, compatibility_class, activation_epoch, approval_state)` populated from reviewed registry artifacts. The deployment's **administratively approved activation transaction** records the immutable `definition_activation` and a change-journal signal. Do not dynamically modify 166 schemas from the public API or silently reinterpret prior issued XLSX and confirmed revisions under new definitions.

A schema fingerprint change is first classified `PRESENTATION_ONLY`, `BACKWARD_COMPATIBLE_NUMERIC`, `MAPPING_CHANGED`, `BREAKING`, `QUALIFICATION_CHANGED`. Text/translation-only changes may require only report presentation refresh (if its snapshot contract allows) and not numeric recompute. Breaking/mapping/qualification changes require explicit review, versioned new method/selector and revalidation; **never** auto-migrate old approved data to a new form version without an authorized mapping and financial S2. Old historical reports use the prior pinned definition.

Fan-out is **lazy/paged only over existing tenants with an active source/slot for the changed form**, not all tenants and not 166×tenants blanket scheduling. On activation create one durable global-impact cursor; a bounded scanner queries source provenance/slot indexes, emits deduplicated tenant-specific impact intents, stores cursor watermark, and resumes after crashes. A newly registered tenant is not assigned 166 partitions/tasks. Affected tenants still need source requalification and permission checks before new ACTUAL calculations.

### 2.4. Single dispatcher, two durable inputs, no second generic scheduler

Keep **existing FactSet publication outbox unchanged** and create an additive `fin_exec.form_change_journal` / `recalc_intent` for **non-financial** catalog/draft/impact lifecycle. The same single orchestrator consumes both inputs with separate event semantics; the new journal is not another independently enabled scheduler or a bypass around the published fact outbox. A worker never executes directly from a draft change journal record. Versioned plan identity distinguishes `SOURCE_RECALCULATION`, `CATALOG_REVALIDATION`, `METHOD_IMPLEMENTATION_CHANGE`, `DRAFT_VALIDATION_ONLY`.

## 3. Change scope and dependency-based impact graph

Maintain a **versioned, server-owned and independently validated reverse dependency index**. Source nodes: `(FormCode, formVersion, source field, metricCode+version, PeriodType, Currency/unit, dimensions, calendar, tenant/source)`. Derived nodes: financial slice → aggregate cell → derived indicator → method variant (SQL/JAVA) → report component. Edge contract specifies `DIRECT`, `ROLLING_N`, `PRIOR_PERIOD`, `YOY_SAME_PERIOD`, `CROSS_FORM_BINDING`, `FX_CONVERSION_APPROVED`, `REPORT_ONLY` and affected output periods.

**Algorithm** (pure planner, no SQL compute):
1. Normalize and verify event, compare old vs new immutable source/policy fingerprints. If truly identical under ordered row/document semantics, journal `NO_OP`, do not allocate workspace.
2. Resolve `tenant + form + PeriodType + Period + Currency` and full trusted entity/scope/revision and selected metric/dimension keys. Reject cross-tenant, form-version, currency or reporting-basis leaks; never infer a changed numeric metric from an evidence-only form.
3. Use reverse index to find **only** directly dependent aggregates/indicators/methods and transitive descendants. If input provenance, dependency semantics or period alignment is ambiguous, report `BLOCKED_DEPENDENCY_UNQUALIFIED` rather than global recompute.
4. Expand affected anchor periods with a versioned `PeriodCalendar`, **bounded declared max lookback/lookahead**. Do not infer previous period from arbitrary `LAG`; invalidate calendar-appropriate windows.
5. Coalesce duplicate targets from multiple form/metric events into one deterministic `ImpactSet`; create one immutable `PlanKey` pinned to exact event, source(s), method descriptor versions, calendar and impact-index version. Persist independent plan for exact same source only if an explicit replay/version change requires it.
6. Preview is read-only and lists `AFFECTED / NOT_APPLICABLE / WAITING_PUBLICATION / WAITING_QUALIFICATION / BLOCKED / NO_OP` with reason codes. Commit persists plan without compute; Activation verifies publication+permit+quotas separately.
7. Reject any opaque client-supplied method/SQL identifier, undisclosed FX conversion, mixed `ACTUAL/SYNTHETIC` or incomplete period. Missing N-month history -> `INCOMPLETE`, not N-1 average.

### 3.1. Mathematical fan-out: one changed period p

Assume the changed point is in a properly qualified same-grain, same-currency series; ordinal `p` is from the pinned `PeriodCalendar`. **Affected output anchor periods**, not source partitions to DROP:

| Indicator | Impact of change at period `p` | Constraints |
|---|---|---|
| Current-period aggregate/value | `p` | Only changed metric/dimensions/form/source |
| `DELTA` / MoM / QoQ vs immediately previous period | `p, p+1` | Currency, PeriodType and calendar are unchanged |
| YoY (same month/quarter/year prior) | `p` and its exact next-year counterpart | `p+12` for calendar MONTH; `p+4` for calendar QUARTER; `p+1` for YEAR; fiscal calendars require explicit equivalent |
| SMA-N, rolling STDDEV-N / variance-N | `p .. p+(N-1)` | The window includes current point; 3-month SMA -> `p,p+1,p+2` |
| TTM12 monthly FLOW | `p .. p+11` | Closing STOCK never enters TTM sum |
| Prior-only N-period Z-score baseline | `p+1 .. p+N` | `p` is excluded from its own baseline; metric current point can separately affect `p` signal if `x_p` changed |
| Quarterly/yearly aggregates rolled up from monthly FLOW | exact containing quarter/year; downstream consumers then propagate transitively | Only full verified coverage; no mixed month/quarter arithmetic |
| Cross-form methods/report cells | dependent output periods defined by exact versioned binding | Never mutate or recompute unrelated form/tenant/currency |

Unbounded recursive/long-memory models are **not** silently restricted to these windows. They must declare finite dependency horizon or obtain explicitly budgeted `FULL_SERIES_REBUILD` admission; unknown horizon fails closed. An event affecting multiple periods is the **union** of affected anchors, deduplicated before task creation.

### 3.2. Form-definition compatibility and 166-code coverage

| Existing capture mode | On changed tenant revision | On global schema change | Financial compute |
|---|---|---|---|
| `TYPED_DRAFT` (122) | Auto validate/diff/freshness/impact preview; optionally typed draft-only diagnostics | Revalidate pinned schema/issued templates; create candidate impact intents for tenants with matching active data | **Only** after explicit approved source + published FactSet + approved method/permit |
| `EVIDENCE_ONLY` (12) | Store new immutable document evidence/digest; invalidate provenance/required evidence status as needed | Recheck evidence contract and downstream approved dependencies | No invented numeric metrics; reviewed evidence dependency may block a method |
| `SCHEMA_NOT_APPROVED` (32) | Register change/blocked status, no numeric interpretation | Hold until schema/model individually approved | No automatic numeric execution |

Full catalogue coverage must prove that every code produces **a deterministic impact classification**, possibly `NO_NUMERIC_METHOD` or `WAITING_QUALIFICATION`. It is **not** a contract that 166 distinct numeric calculations must run.

## 4. Data model: additive contracts, not migration scripts

Exact Flyway names and existing schema constraints must be preflighted before W1. Proposed stable tables and invariants:

| Logical table | Core columns / rules |
|---|---|
| `fin_htap.form_definition_manifest` | `form_code, definition_version, schema_sha, mapping_sha, compatibility, active_from_epoch, qualification_state`; immutable versions, one CAS current pointer per form |
| `fin_exec.form_change_journal` | `event_id, event_type, organization_id NULL only for catalog-global, form_code, slot_id/revision_id, definition_activation_id, old/new source hashes, occurred_at, state`; immutable event identity and audited delivery |
| `fin_htap.form_impact_binding` | `form_code + version + metric/field selector + method_variant/version + dependency_kind + period_policy + window_horizon + qualification`; server-owned only, validated acyclic DAG and referential compatibility |
| `fin_exec.recalc_intent` | tenant/source/event/impact fingerprint, reason, status, retry/backoff, exact payload hash, `UNIQUE(tenant,canonical_recalc_key)`; no duplicate jobs under concurrent fanout |
| `fin_exec.recalc_plan` | immutable plan/version/SourceKey/TrustedComparisonSet/canonical ImpactSet, descriptor checksums and release eligibility; **separate** from legacy `analysis_run.event_id UNIQUE` |
| `fin_htap.financial_slice_head` | one mutable current *pointer* per full tenant/form/grain/period/currency+scope+metric source context, `active_generation`, `source_sequence`, `descriptor_version`; CAS prevents stale job from replacing newer trusted result |
| `fin_workspace.workspace_generation` | `workspace_id,tenant_id,form_code,period_type,period,currency,source_hash,generation,cohort_id,reclaim_epoch,state,seal_hash`; children carry scoped FK including tenant/cohort/generation |
| `fin_workspace.partition_catalog` + `cohort_member` | physical parent/child allowlist, bound/strategy, retention class, cohort state, complete registered generations, drain fence, timestamps/DDL recovery journal |
| `fin_exec.method_result_receipt` | append-only logical key/complete source/period/currency/impl/rounding/lineage/fence/qualification; durable, **never** inside droppable workspace partition |

A globally published catalog activation is allowed to have `organization_id=NULL` only in the **change journal**, never in a financial calculation/task row. Production task/manifest/FK always requires nonnull verified tenant scope.

**Unique dedup** is designed around domain-event identity and full canonical fingerprints, not `NULL`-containing SQL UNIQUE keys that silently accept duplicates. Use `NULLS NOT DISTINCT` where proven, or separate partial unique indexes for tenant-global vs tenant-specific events. `ResultKey` uniqueness includes semantics, not attempt ID.

**Migrations:** forward-only Flyway, no rewriting `V20261116_01/02` original history; a form revision transaction emits new journal row atomically after adding schema. Existing `catalog_xlsx_slot.latest_revision` CAS continues unchanged. A transactionally committed journal event may be retried many times without duplicate logical plan or financial receipt.

## 5. Partition creation, generation replacement and DROP lifecycle

### 5.1. Physical architecture: which data can be dropped?

```text
persistent fin_fact published source + fin_intake immutable drafts
             |       unchanged by calculation GC
persistent fin_htap slice/definition/impact directory + lineage
             |
persistent optional fin_agg sealed shared aggregates (independent retention)
             |
ephemeral fin_workspace.stage_fact / stage_intermediate
          RANGE(reclaim_epoch) => bounded UTC lifecycle cohorts
             |
        tenant/form/period/currency slices mapped by registry
             |
       SEALED generation -> qualified compute -> immutable receipts
             |
        cohort DRAIN -> DETACH -> DROP only after every member retired
```

**Never physically partition by `166 forms × tenants × years/months × currencies × method variants`**: it causes unbounded table/DDL/plan overhead. The five-dimensional business key is a **logical slice, indexed in manifest**; the physical partition key for reconstructible staging is a bounded, server-controlled `reclaim_epoch`. Unpartitioned or fixed HASH versions remain measured alternatives for permanent shared aggregate cache. A dedicated physical partition for a very large isolated tenant/form/workspace is *exceptional* and requires a recorded size threshold and a bounded partition quota.

A `reclaim_epoch` is the epoch of **earliest allowed reclamation of a lifecycle cohort**, NOT the accounting/report Period. Assign new stage generations to precreated UTC daily/weekly cohorts by retention policy and size class. If one member has a legal hold, in-flight job or longer retention, **the entire physical cohort remains attached**. Long-retention and large/exceptional jobs should be placed in separate retention classes **at allocation**. Never reassign sealed rows to arbitrary other cohorts simply to bypass a hold.

### 5.2. Creation protocol (privileged precreator, not HTTP)

1. Independent maintenance service owns allowlisted two ephemeral parent tables, partition strategies, `partition_catalog`, daily/weekly bounds, max physical partition count, role and DDL timeouts. W1 S2 approves exact schema and `PRIMARY KEY/UNIQUE` keys containing the RANGE partition key. No DEFAULT partition when using concurrent DETACH.
2. Precreator receives **only server-owned cohort request** from a bounded resource planner; verifies total partition budget/global and per-tenant reserved footprint, target retention class and UTC bounds; serializes competing maintenance nodes by durable catalog/leader lock, not only JVM state.
3. Prefer creating empty standalone child with reviewed CHECK bounds and then `ATTACH PARTITION` under maintenance control where it reduces parent lock; alternatively precreate `CREATE TABLE PARTITION OF` during a low-load window (this may take `ACCESS EXCLUSIVE` on the parent). **Never DDL per newly confirmed form, per tenant, per method or inside an OLTP form-confirmation transaction.**
4. Verify PostgreSQL catalog (`pg_inherits`, `pg_partitioned_table`, child bounds and parent OID), register `OPEN` cohort and a stable server-generated identifier. Missing cohort -> `WAITING_PARTITION_CAPACITY` and no stage writes; a privileged replenisher schedules creation. Do not route to DEFAULT, random time buckets or unpartitioned shadow silently.
5. Resource quota includes DDL slots, expected WAL/temp/disk bytes and OLTP connection reserves. `CREATE` and `ATTACH` failure is retriable/reconciled but must not leave a usable catalog pointing to an absent/incorrect partition.
6. A task opens no heavy workspace query until the physical OPEN cohort and PREPARE capacity are confirmed.

### 5.3. Targeted recalculation protocol (no destructive delete on update)

1. New trusted event or definition-compatible revalidation gives an ordered **source/change sequence** for its exact tenant/slice. On draft confirm, `slot.latest_revision` is existing monotonic input; on trusted publication use server-controlled `current_fact_set.version` / explicit source lineage mapping, **not** global event timestamp order. A catalog activation has separate monotonic definition epoch. Compare compound source + catalog + method version vectors, never assume lexicographic UUID order.
2. Freeze `PlanKey` and `ImpactSet` from trusted old/new selected fact hashes and descriptor dependency graph. Deduplicate bursts, coalesce multiple revisions to latest active slot **only for current-view recompute**, while keeping pinned historical replay work separate.
3. Acquire PREPARE global+tenant quota; allocate `workspace_generation=N+1` in an already OPEN cohort. Materialize ONLY changed canonical slice/metric cells and affected window inputs, **unless** the reviewable cost model selects a bounded full rebuild. Unchanged sealed source/aggregate manifests may be safely reused under exact matching hashes and permissions.
4. Load stage in idempotent batches under generation+attempt fence. Validate source publication, complete selected assertion IDs, type/grain/currency, periods, row count and deterministic content hash, then CAS seal. Until `SEALED`, old N remains visible and no method consumes N+1 as trusted.
5. Execute only admitted dependency DAG descendants (SQL or Java) using exact slice/series keys and calendar; store separately immutable receipts. Do not conflate recalculation completion with financial S2 approval.
6. **Atomically promote current pointer** only if source/catalog/method version vector still equals the intended newest authorized head; a late finished worker for older event cannot overwrite newer results. In-flight readers pinned to N keep N and its cohort ref, no partial new outputs. A multi-method report/head requires a manifest for the **complete required result set** so a partly recomputed report cannot be displayed as fully current.
7. Mark old generation N `RETIRED`; do not DELETE on each update. Preserve source and old receipts, pinned replay and report snapshots. When all members in N's cohort are retired, unreferenced and physically quiescent after TTL/hold, cleanup can operate on the whole physical cohort.

**Critical:** `DROP` is not what triggers recalculation and is not synchronous with publication. The fast user-facing operation is new-generation CAS promotion; DROP is deferred GC and may be delayed by another tenant/form in the cohort.

### 5.4. Safe physical removal: DETACH → verified DROP

```text
OPEN -> CLOSED_TO_NEW_WRITES -> DRAINING -> DETACH_REQUESTED
  -> DETACHED -> DROP_REQUESTED -> DROPPED
                       \-> RECOVERY_REQUIRED
```

Short transactional drain barrier: lock cohort metadata; prove **all** cohort members fully terminal, all historical/active plans reference-safe, zero pinned reader refs, zero live or uncertain running SQL statements/leasing, no legal hold, no required lineage or replay payload; block all future allocation, new reads and task claims on DRAINING cohort. Release a physical capacity lease only after statement cancellation/termination is confirmed; late worker fence can never reattach or write to sealed cohort.

A **separate trusted maintenance role** with exact allowlisted parent+child identifiers (from `pg_catalog` and durable catalog, never HTTP input) performs `ALTER TABLE ... DETACH PARTITION ... CONCURRENTLY` **outside any transaction block**, confirms status, then `DROP TABLE` the detached child without `CASCADE`. PostgreSQL 16 restrictions: parent with DEFAULT child forbids concurrent DETACH; one pending concurrent DETACH per parent; interrupted detach may require `DETACH PARTITION ... FINALIZE`; referenced FKs/constraints can block or lock DDL. Persist step-by-step audit so crash between metadata commit, DETACH and DROP is recoverable. No fallback to unsafe attached-child DROP under busy OLTP.

The existing read-only `WorkspacePartitionMaintenanceUtility` remains an inspector. The user-supplied generic daily `TablePartitionService` can contribute **dry-run/metrics/backoff** patterns only; never transfer its date-only deletion criteria or arbitrary table name DDL to FIN. Destructive execution only after W4/W7 tests, independent S2 and a separate OFF-by-default operator approval.

## 5.5. PostgreSQL batch execution within controlled form recalculation

[ADR-R2-11](ADR-R2-11-POSTGRES-BATCH-CALCULATION-EXECUTION.md) governs SQL execution for impacted form/period/currency slices. Form update may enqueue a **versioned, qualified** SQL batch task only after trusted FactSet publication, plan approval, tenant/global admission, and SEALED input. Existing Java orchestrator selects immutable method→routine signature and passes typed workspace/generation/attempt/fence parameters; approved SQL procedure/function runs a bounded batch, never arbitrary SQL text read from a configuration table. PostgreSQL does not poll 166 forms or start another scheduler. Cross-period methods must include verified read-only historical halo even if their target output anchor range is small. On any error, old generation remains current; immutable source/receipt survives. A routine may not create/drop physical partitions; maintenance remains separate. Required `SQLB-01..14` PostgreSQL 16 negative and performance tests.

## 6. Queue, admission, idempotency and failure semantics

**One Java dispatcher with durable stage states**; old `fin.execution.enabled` and new modes cannot both execute the same plan. `fin.execution.mode=OFF|LEGACY_TEST|WORKSPACE_SHADOW|WORKSPACE`, default OFF, startup fails on conflict. `WORKSPACE_SHADOW` performs only permitted SYNTHETIC shadow calculations and cannot publish approved ACTUAL/CORE.

Source-change state: `RECORDED -> CLASSIFIED -> IMPACT_RESOLVED -> WAITING_PUBLICATION/WAITING_QUALIFICATION/READY -> ADMITTED -> PREPARING -> SEALED -> CALCULATING -> RESULT_COMMITTED -> PROMOTED -> OLD_GENERATION_RETIRED`. `NO_OP, BLOCKED, SUPERSEDED, FAILED_RETRYABLE, FAILED_FINAL, DEAD_LETTER` must be terminal/recoverable as indicated, always with stable reason codes.

Admission is **atomic with task claim** across global+tenant+stage (`PREPARE, SQL, JAVA, CLEANUP`) buckets. Workload fairness is a durable bounded weighted tenant scheduler, not simple global FIFO. Source, method permit, dependencies and resource claims are checked before opening expensive PostgreSQL queries; no unbounded Java thread or DB connections. Separate maintenance DDL budget and API/OLTP reserved connections. Retry exponential backoff+jitter with max attempts; predictable tenant and cohort DLQ status, operator intervention and replay without duplicate effects.

**Coalescing:** repeated draft confirmation of same canonical content emits no event; repeated event ID/PlanKey -> one intent; multiple pending changes to the same tenant/form/slice may be subsumed by the newest *current-view* plan with a recorded `SUPERSEDED` audit, **but** never drop a pinned immutable history result requested explicitly. If two revisions race (r2 then r3), r2 may compute but cannot promote after r3 becomes source head. Changing an unrelated form or currency does not invalidate an entire tenant.

**Permit semantics:** `PLAN_COMMITTED` and `WORKSPACE_SEALED` are NOT `EXECUTION_PERMITTED`. Source published and permitted variant still require R2 execution authority; do not treat `TYPED_DRAFT` or catalog activation as financial qualification. `ACTUAL` continues HOLD.

## 7. Notifications and observable API (backend only, future design)

Expose existing authenticated tenant-scoped read routes after W-stage acceptance; no public DDL or SQL input endpoints. Optional future status API may return `formCode,tenant,periodType,period,currency,sourceRevision,analysisGeneration,impactSummary,queued/running/blocked counts,staleReason,lastQualifiedReceipt`. Return 404/denied for cross-tenant IDs without leaking whether the target exists. Do not expose other tenants' queue sizes, physical child table names or SQL statement text.

Operators require status/metrics `catalog_form_changes,impacts_identified,recalc_noop,recalc_ready,recalc_wait_publication,recalc_wait_qualification,tenant_fair_wait,old_generation_pins,cohort_drain_blockers,partition_create_errors,detach_pending,drop_failures,recompute_p95,oltp_p95`. Low-cardinality tags only; tenant UUID and form+period combinations belong in structured access-controlled logs, not high-cardinality metric labels. Trace `eventId -> planKey -> ImpactSet -> generation -> partition cohort -> resultKey -> report pointer` end-to-end.

## 8. PostgreSQL RED test matrix before GREEN

**Every test against real PostgreSQL 16 Testcontainers** with deterministic fixtures, assertions on persisted state, same-SHA CI, independent S2 for security/schema and Java/SQL financial golden when relevant. No mock-only success claims.

| Case | Fixture/action | Acceptance |
|---|---|---|
| F166-EVT-01 | Confirm a tenant typed draft with new content | Exactly one `TENANT_FORM_REVISION_CONFIRMED` journal row in same transaction; no financial publication |
| F166-EVT-02 | Repeat same fingerprint + row order/document SHA | No new revision, no new event/plan |
| F166-EVT-03 | Roll back confirm transaction midway | Neither revision nor change journal row persists |
| F166-EVT-04 | Two concurrent confirmations with stale expectedRevision | Exactly one monotonic slot head; other conflict; one successful event |
| F166-EVT-05 | Confirm EVIDENCE_ONLY document with changed digest | Evidence freshness changes, no numeric ACTUAL method |
| F166-CAT-01 | Change one static catalog form schema fingerprint | One reviewed activation; no unapproved automatic schema coercion |
| F166-CAT-02 | Change display text only | No needless numeric recalculation; historical report semantics remain pinned |
| F166-CAT-03 | Change mapping/qualification without S2 | Affected tenants `WAITING_QUALIFICATION`, no ACTUAL execution |
| F166-CAT-04 | Restart global impact scanner after 1st page | Cursor resumes; each affected tenant exactly one canonical impact intent |
| F166-CAT-05 | Activate change for form with no tenant usage | No tenant jobs, no workspace/partition creation |
| F166-CAT-06 | Exercise 166 catalog codes incl 122/12/32 modes | Every form deterministically classified, no invented numeric method |
| F166-SRC-01 | Confirm draft, no approved source or published FactSet | Draft validation only; `WAITING_PUBLICATION`, zero financial result |
| F166-SRC-02 | Publish qualified SYNTHETIC FactSet with changed selected assertion | One trusted source event and minimum ImpactSet; old snapshot remains |
| F166-SRC-03 | Publish same source values but different unrelated form | Changed form's unrelated methods not recomputed |
| F166-SRC-04 | Form A and B contribute to one reviewed cross-form method | Both dependencies preserved; method invalidated only when relevant input changes |
| F166-ISO-01 | Same form/period KZT USD, tenants A/B | No cross tenant/currency rows or window; 0 mixed results |
| F166-ISO-02 | MONTH vs QUARTER with same start day, revised source | Different slices/results; approved calendar required |
| F166-ISO-03 | One form source revision changed, another form revision unchanged | Exact selected assertion lineage, no unintended source mixing |
| F166-IMPACT-01 | Change MONTH p in a 3-month SMA series | Only anchors p,p+1,p+2 invalidated |
| F166-IMPACT-02 | Change MONTH p in MoM/YoY/TTM | MoM p,p+1; YoY p and matching next-year month; TTM p..p+11 |
| F166-IMPACT-03 | Change QUARTER p in QoQ/YoY | QoQ p,p+1; YoY p and matching next-year quarter |
| F166-IMPACT-04 | One changed point in prior-only Z-score baseline | Prior-only N downstream anchor range; current observation separate |
| F166-IMPACT-05 | Duplicate old/new metric changes, 2 forms | One deterministic ImpactSet; no duplicate method tasks |
| F166-IMPACT-06 | Unbounded/unknown dependency horizon | Explicit BLOCKED or separately admitted FULL_SERIES_REBUILD, not truncate |
| F166-HEAD-01 | r2 compute delayed; r3 already current and committed | r2 cannot overwrite r3 current pointer; immutable historical r2 output preserved if requested |
| F166-HEAD-02 | Partial method DAG succeeds, one required method blocked | No report marked wholly current; exact per-method statuses visible |
| F166-HEAD-03 | Crash before/after seal or pointer CAS | Resumable without partial read, duplicate receipt or lost old result |
| F166-PART-01 | Many small tenant/form updates in same UTC cohort | Zero new physical child per update; partition count bounded |
| F166-PART-02 | Parent partition missing; worker preparing | Wait capacity or explicit block, no on-request DDL or default spillover |
| F166-PART-03 | Two tenants share cohort, one still pinned | No DETACH/DROP; both historical outputs remain correct |
| F166-PART-04 | Cohort fully retired; DETACH interrupted | Catalog reconciliation/FINALIZE; no unsafe foreign DROP |
| F166-PART-05 | Forged child SQL name or unauthorized worker DDL | Rejected, no table affected |
| F166-PART-06 | Heavy dedicated cohort allocation vs quota | Only under reviewed size/partition budget, otherwise shared safe lifecycle |
| F166-OPS-01 | 100 tenants × 166 form slot changes, no qualified numeric methods | Bounded journal+classification without 16,600 physical partitions or invalid method executions |
| F166-OPS-02 | Heavy PREPARE/SQL/DROP plus real OLTP traffic | Global+tenant+class budget respected; OLTP reserve and shed behavior measured |
| F166-OPS-03 | 2 JVM nodes claim same event and recalc target | One active logical task/fence and receipt; no over-admission |
| F166-MIG-01 | Fresh DB and upgrade from existing V20261116_13 | Original data/old migration checksums remain; new source and journal constraints enforced |
| F166-REL-01 | ACTUAL published source, method not qualified; automatic trigger enabled | No unauthorized calculation; release HOLD remains |

**Absolute safety gates (zero tolerance):** cross-tenant/source/form/period/grain/currency contamination; unapproved financial publication or ACTUAL execution; wrong period expansion; duplicate/overwritten receipts; stale head promotion; DROP of any active or pinned cohort; unbounded number of physical partitions/jobs; catalog changes silently mutating historical form meanings. A true `NO_OP` must create zero costly tasks; a failed DB event write must roll back the originating update.

## 9. Delivery milestones under the existing W0–W8 plan

| Stage | Minimal delivery | Evidence and release restrictions |
|---|---|---|
| **W0.F166 — schema/event/impact design freeze** | Owner-approved two change-event paths, 166-mode classification, exact form/slot/source identity and reversal graph, physical cohort quotas | Independent S2 design review; no unresolved P0 or misleading “auto ACTUAL” claim |
| **W1.F166 — reliable detection/plan preview** | Additive journal/manifest, confirm atomic event, registry-diff activation, impacted-tenant index and paged cursor, pure `ImpactSet` planner | PostgreSQL RED→GREEN F166-EVT/CAT/SRC/ISO; mode OFF; no calculation or DROP |
| **W2.F166 — capacity admission** | Durable tenant fairness and class/global quotas for change scanning, PREPARE/SQL/JAVA/CLEANUP; same dispatcher | Multi-node no-oversubscription and bounded global catalog fanout |
| **W3.F166 — first numeric execution** | Qualify one SYNTHETIC published form/metric (e.g. FIN-01 REV_NET) and period fanout; exact same-source Java golden/SQL parity | No ACTUAL, no broad 166 auto-compute; no SQL without sealed trusted input |
| **W4.F166 — generation and GC** | Immutable plan/result receipts, atomic current head, physical precreate and coordinated cohort retirement, guarded reaper | F166-HEAD/PART with interrupted DETACH recovery and no cross-tenant loss |
| **W5.F166 — cross-form DAG and catalog upgrades** | Versioned reviewed source-field→method binding, cross-form source selection, qualified catalog rollout, individual tenant status | Eligible dependency graph only, no guessing from 166 names |
| **W6.F166 — actual performance proof** | Compare event batching and materialization/direct, shared/dedicated cohort economics, DELETE vs DETACH/DROP, 1/2/4/8 workers/tenant skew | End-to-end build + recompute + GC + OLTP p95/p99; publish exact hardware and predeclared thresholds |
| **W7.F166 — operations/security** | Role isolation, delivery cursors, SQL/DDL audit, quota controls, legal hold and reaper incident drills | Only maintenance role can DDL, zero cross-tenant access |
| **W8.F166 — final acceptance** | Exact HEAD full regression, 166 classification tests, independent S2 method/security/DB, synthetic integrated E2E | `RELEASE_HOLD` and `ACTUAL` separately enforced until explicitly authorized |

**First implementation unit:** W1.F166 **RED tests first** for the existing `CatalogXlsxRevisionService.confirm()` transaction (same-TX event, no-op and optimistic concurrency), followed by additive journal + a **read-only impact preview** for one form. Do **not** begin by making `WorkspacePartitionMaintenanceUtility` destructive or generating 166×tenant physical partitions.

## 10. Proof boundaries and external database references

This document is an **engineering design**, not a Flyway migration, implemented trigger, active automatic 166-form financial recalculation, production performance measurement or independent S2 sign-off. The current `CatalogXlsxRevisionService`, `fact_publication_outbox`, `SingleExecutorScheduler`, seven metadata adapters and read-only partition inspector remain as described until actual code changes pass PostgreSQL and CI.

PostgreSQL 16 official docs: [Table Partitioning](https://www.postgresql.org/docs/16/ddl-partitioning.html), [ALTER TABLE, DETACH CONCURRENTLY/FINALIZE](https://www.postgresql.org/docs/16/sql-altertable.html), [CREATE TABLE and partition locks](https://www.postgresql.org/docs/16/sql-createtable.html).

**Owner's business intent preserved:** every form update in a tenant results in an **automatic, traceable readiness/impact decision**, and every *approved and admitted published* change results in **precise incremental recalculation of applicable, qualified financial indicators** without mixing tenants, FormCodes, PeriodTypes, Periods or Currencies.
