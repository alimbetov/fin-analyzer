# FIN-BE-R2-W — Tenant-Aware Calculation Workspace & Controlled SQL/JAVA ETL

**Specification:** v1.1 (technical hardening), 2026-11-17. **Status:** INTERNAL REVIEW APPLIED / W1 ENTRY HOLD / INDEPENDENT S2 PENDING / ENGINEERING IMPLEMENTATION NOT ACCEPTED.
**Repository:** `alimbetov/fin-analyzer`; **sole branch:** `feat/fin-r0-r1a`; backend-only Java 21 / Spring Boot / PostgreSQL 16 / Flyway.
**Normative decision:** [ADR-R2-06](ADR-R2-06-TENANT-AWARE-CALCULATION-WORKSPACE.md).
**Supersession and historic requirements:** [FIN-BE-R2-EXECUTION-SUPERSESSION-LEDGER](FIN-BE-R2-EXECUTION-SUPERSESSION-LEDGER.md).
**Unchanged contract:** [ADR-R2-05](ADR-R2-05-METHOD-MODULE-DUAL-ENGINE-IMMUTABLE-RESULTS.md).
**Code reviewed at planning:** `8f63a335630185cad364b34f75f554b2f0993ec7`. Revalidate HEAD, migrations, CI and code before any implementation.


## 0.A. Mandatory review corrections — v1.1 (normative, before all W-stage code)

The [technical review W-REV-01..16](FIN-BE-R2-W-TECHNICAL-REVIEW-2026-11-17.md) identified P0 admission/tenant/fencing/source/rollout gaps. **This section is controlling whenever earlier wording in this document is under-specified or inconsistent.** Status is **design-hardening recorded**, not implementation / test / independent S2. The old runtime's `autoEligible()` must not be treated as the new authority; `ACTUAL` execution HOLD remains.

### A. Tenant authority, identity and RLS

For v1, **one scheduling tenant = one server-verified organization_id**. Do not introduce arbitrary account mappings without a separate ADR/schema approval. `tenantId` is derived from an exact published SourceKey and the validated, organization-scoped `fin_access` claim, NEVER from client JSON or a free-form header. API read and analyze operations must separately require `FIN_READ` and `FIN_ANALYZE` as appropriate; background jobs use a narrowly-scoped server-authored execution principal, not a cached user JWT. An organization may contain multiple economic entities/scopes; their source identities are never interchangeable.

Every workspace, task, stage, capacity lease and result receipt contains tenant/organization scope, exact source identifiers and verifiable foreign-key relationships; composite keys must reject cross-tenant row attachment even when UUID values are guessed. Shared workspace is permitted **only within the same tenant** and under identical immutable SourceKey + population selector/version/hash + accounting/unit/calendar/normalization policies; per-run permissions are rechecked before reading results. No cross-tenant deduplication, even for coincidentally equal numeric values.

**Database defense-in-depth:** separate migration/owner and runtime identities; SQL worker cannot own tables, have `BYPASSRLS`, create extensions, change role/search_path freely or execute arbitrary functions. For tables with RLS, evaluate `ENABLE` + `FORCE ROW LEVEL SECURITY`, explicit `USING` and `WITH CHECK`, and non-owner worker roles; use `SET LOCAL` scoped tenant context *inside every transaction*, reset automatically at commit, and prove no pooled-connection tenant-context carryover. RLS does not replace server-side joins and ABAC. No privileged `SECURITY DEFINER` backdoor or caller-controlled schema name. RLS/role DDL must be checked against current JDBC/Flyway privileges and existing migrations.

### B. One runtime mode and strict execution boundary

Introduce a **single effective execution mode** with server configuration `fin.execution.mode=OFF|LEGACY_TEST|WORKSPACE_SHADOW|WORKSPACE`, default `OFF`. Retain legacy `fin.execution.enabled` for compatibility; fail application startup when its value conflicts with workspace mode/feature flag or allows simultaneous old and new claims on the same logical plan. `fin.workspace.enabled` by itself cannot authorize execution. Document compatibility truth table before changing code:

| Effective mode | Legacy scheduler tasks | Workspace preparation | SQL/JAVA method computation | Immutable production result publication |
|---|---|---|---|---|
| `OFF` | Disabled | Disabled except controlled test setup | Disabled | Disabled |
| `LEGACY_TEST` | Explicit isolated legacy regression only | Disabled | Existing test-path only | No new ACTUAL promotion |
| `WORKSPACE_SHADOW` | No duplicate active claim for same logical key | Allowed only with resource admission, SYNTHETIC | Comparison-only, no production receipt or CORE side effect | Disabled |
| `WORKSPACE` | Replaced by **the same** orchestrator's workspace-controlled claim path | Allowed after source and resource admission | Only source+method+permit+fence allowed | Guarded immutable commit; ACTUAL requires independent permit |

**No parallel scheduler/outbox** is introduced. Existing immutable outbox is consumed once for its original event semantics. `PlanPreview` reads without side effects; `PlanCommit` persists immutable plan without compute; Activation checks permit and capacity separately. Mode rollout must be idempotent, same-SHA auditable, rollbackable to OFF without deleting receipts. A single legacy tick calling `autoEligible()` must not be activated in production as a shortcut.

### C. Materialization is an admitted task, not a free prerequisite

Resource consumption is governed across **PREPARE, SQL, JAVA, CLEANUP** stages; staging and population loading can exhaust DB just as compute can. A workspace is eligible for reuse only after `SEALED`. Preparation must acquire global+tenant+PREPARE capacity **before opening its expensive DB query**, with finite row/bytes/query-time budgets. `CLEANUP` has low-priority I/O and transaction batch budget; it can be delayed under OLTP pressure but not run unlimited in parallel.

Task DAG must represent preparation as a typed prerequisite to methods that need it; an eligible method with small population may instead choose `DIRECT_PUBLISHED` (read-only, still capacity-governed) when measured reuse benefit does not justify materialization. These are **input access plans**, not third calculation engines. Method descriptor, source and permission controls remain identical.

### D. Atomic fair admission and lease invariants (W2 design contract)

Use **PostgreSQL as the sole authority** for reservations across all app instances; in-memory counters are advisory metrics only. Required state:
- `resource_bucket(resource_scope, tenant_id/null, class, limit_units, used_units, policy_version)` with a uniqueness scheme that handles global-vs-tenant keys without NULL duplicates;
- `capacity_lease(lease_id, task_id, attempt_id, tenant, class, units, fence, state, expires_at, worker_id, resource_policy_version)`, unique active task/fence guard;
- `tenant_dispatch_state(tenant_id, weight, deficit, round_epoch, last_served, queued_count)` and one durable scheduler cursor or equivalently proven concurrent fair-selection primitive.

**Single short claim transaction:** (1) verify plan/source/permit and SEALED workspace or PREPARE stage; (2) choose eligible tenant using a persisted weighted deficit round-robin policy with strictly bounded weights and deterministic tie-breaking; (3) lock scheduler cursor, then global and tenant capacity rows in a fixed order, then candidate task row; (4) atomically `used_units + required_units <= limit_units` for all buckets, and insert lease with new fence; (5) CAS task to RUNNING, commit; (6) only **after commit**, open bounded calculation resources. Failure at any step **rolls back every capacity reservation and claim**. Do not claim a task and only later discover that tenant slots are unavailable. Do not hold this DB transaction while computing. A worker gets a task only after durable lease reservation.

The fairness algorithm must maintain deficit across polls, add bounded quantum per eligible tenant, charge estimated server-owned resource units and cap credit accumulation; within a tenant use class-based FIFO/aging, with anti-starvation bound expressed in *dispatch rounds under available capacity* (not an impossible real-time guarantee during overload). Test multiple instances and unequal weights (e.g. weights 1:2:4) with heavy/light tasks, no starvation, 0 oversubscription and queue growth bounded by policy. SQL worker/admission limit is **cluster-wide** and must be lower than the shared PostgreSQL connection budget (across all app replicas, plus reserved API/OLTP/admin/migration connections).

**Expiry does not imply safe release of a physical execution slot:** first mark attempt `REVOKING`, request cancellation/timeout, fence all stage writes and commits; only release SQL/JAVA capacity when the worker/DB query has confirmed termination or a separately proven bounded timeout+process reaper completed. An unconfirmed orphan retains quarantined capacity; watchdog alarms and stops new heavy admissions instead of oversubscribing the DB. Leases are reconciled after restart using DB-owned timestamps and fencing, not unsynchronized JVM clocks.

### E. Workspace transactional sealing, reuse and immutability

`LOADING` may perform incremental idempotent batches in multiple short transactions because the published source and selection rows are immutable. Every batch is keyed by `workspace_id + generation + stage_attempt + assertion_id` and bound to exact published source; data is private to its builder. Multiple builders for one workspace key either coalesce on a unique key or only one generation wins via CAS; never mix batches between generations.

**Seal is a separate short transaction** that locks the workspace control row and validates: verified published FactSet and original outbox; exact scoped source/basis/dataSpace; expected fact identifiers, row count, metric qualification and unit contract; duplicate/period/currency constraints; deterministic content hash (not just count), reference population policy; current builder generation/fence. Only then publish one `SEALED` generation and input fingerprint. Nothing is readable as trusted before that. A failure leaves `FAILED`/`QUARANTINED`, never partial READY. After seal, prohibit UPDATE/DELETE/INSERT to its fact rows through DB-granted writes/guarded append-only procedure or triggers; source values never copied as approval evidence.

For HASH-partitioned fact/intermediate tables, composite primary/unique keys **must include partition key** (`workspace_id`); enforce tenant binding through composite FK to unpartitioned `workspace(workspace_id,organization_id)` or equivalently reviewed constraint. Primary keys including just `assertion_id` cannot provide global uniqueness across HASH partitions. No physical partition per tenant/run/method. Tenant-foreign results cannot join by workspace UUID alone.

### F. Stale attempts, immutable result receipts and safe cleanup

All attempt-local writes carry a monotonic DB-issued fence + generation; only current lease and stage fence can transition staging to SEALED or publish a result. A stale worker may finish local arithmetic but **cannot** commit an intermediate population, alter immutable input, or publish CORE. Use server statement/transaction timeouts, query cancellation, bounded work_mem/temp_file_limit and immutable SQL resources. An expired lease must not let retries overlap destructively with previous workers.

Result receipts are **append-only** with globally canonical encoding, version tag, exact source/method/input/SQL implementation hash/assumptions/parameters/policy, a tenant-scoped UNIQUE logical result key, and separate computation vs method-S2 vs release state. If a UNIQUE conflict occurs, re-read and compare complete canonical source/result payload hashes: identical -> idempotent success, mismatch -> BLOCKED_COLLISION, never last-writer-wins. Persist result + task terminal state **atomically under the live fence**. No deletion/rewrite of previous results when workspace is purged.

Cleanup obtains an exclusive reclaim generation, refuses any live lease, reference, incomplete plan, active SQL statement, legal hold or pending recovery; writes a tombstone, deletes in short indexed batches (never DROP HASH PARTITION per workspace), and rechecks references before EXPIRED. Interrupted cleanup resumes idempotently. Cleanup priority is below serving/report API and active computation. Disk/WAL pressure engages load shedding and alerts; never purge active data to meet throughput goals.

### G. Performance evidence and minimum objective gates

Do **not** assume partitioning is faster. Before freezing the Flyway partition count, run a *measured representative* unpartitioned vs HASH 8/16/32 experiment with realistic tenant skew, 1/2/4/8 SQL workers, 1/2/10/100 tenants, and both short and heavy query mixes; record `EXPLAIN (ANALYZE, BUFFERS)`, pruning, planning time, temp usage, WAL, autovacuum impact, SQL p95/p99 and OLTP p95. Fixed partitions are a **migration-time physical choice**, never a request-time setting. Partition count can change only by a separately reviewed migration/rebuild plan. Small datasets may deliberately stay unpartitioned when faster.

**Non-negotiable correctness criteria** for all W acceptance: **zero** cross-tenant reads/writes, **zero** capacity over-admissions across replicas, **zero** wrong-population seals, **zero** duplicate immutable logical results, **zero** unfenced result publication, **zero** ACTUAL execution permits without independent authorization. For throughput and SLO thresholds (e.g. OLTP p95 regression cap, queue max wait, disk/connection headroom), register concrete numeric thresholds plus hardware details **before** running W6; no moving success thresholds after seeing data. P0 correctness failures block the entire workspace rollout even when p95 is excellent.

### H. Blocking implementation order and evidence

- **W0:** finish this design, confirm organization-to-tenant identity, versioned plan identity, runtime mode truth table, roles/RLS strategy, row/bytes sizing and resource budget. Write targeted RED tests and have independent architecture/security S2 review; record open decisions explicitly. **W1 code is HOLD** while a P0 design choice remains unresolved.
- **W1:** make **minimal additive workspace schema and sealed population** using reviewed Flyway, prove fresh-install+upgrade and cross-tenant/immutability/seal concurrency in PostgreSQL 16 Testcontainers. An unpartitioned control table and HASH candidate may coexist only as an explicitly feature-gated benchmark, not silent production physical selection.
- **W2:** atomic capacity buckets + lease + fair scheduling + PREPARE/SQL/JAVA/CLEANUP global/tenant quotas, multi-node correctness, cancellation/quarantine and pressure response. Resource limits are not verified solely by JUnit mocks.
- **W3:** FIN-01 exact-source SQL vs existing Java on the same SYNTHETIC published FactSet with status/rounding/gap/zero/negative golden cases. Preserve its no-ACTUAL, no-CORE-write isolation until W4 permit path.
- **W4/W5:** versioned plan and immutable result, then further independent MethodModules and DAG. Explicitly prove the legacy-to-workspace cutover mode without duplicate source delivery.
- **W6–W8:** representative load, operational controls, security S2, same-SHA CI and human UAT. All new implementations disabled by default; no blanket 60-method auto-enablement.

**Primary acceptance inventory (implement RED before GREEN):** `W0-MODE-01..05`, `W1-ISOL-01..08`, `W1-SEAL-01..08`, `W1-PART-01..04`, `W2-ADMIT-01..06`, `W2-STAGE-01..04`, `W2-BULK-01..05`, `W3-PARITY-01..10`, `W4-PLAN-01..07`, `W4-FENCE-01..06`, `W4-RESULT-01..08`, `W6-SLO-01..09`, `W7-SEC-01..08`, `W7-GC-01..06`. Each ID needs a concrete test, expected DB state, evidence SHA and CI job; a named scenario alone is not PASS.

## 0. Goal, non-negotiable safety boundaries, acceptance state

Provide a **single efficient governed calculation platform** for all qualified FIN method variants, with independent per-tenant capacity controls, repeatable high-volume SQL ETL, deterministic sequential/parallel method DAG, Java finance algorithms, materialized reusable trusted populations, immutable results, crash resilience and auditable trace. **Not** 60 parallel auto-enabled services, **not** automatic approval of all 60 method families.

Mandatory: one server-controlled `MethodModule` per executable variant; one central orchestrator extending existing `MethodVariantRegistry`, `MethodApplicabilityEvaluator`, `MethodTaskAdapter`, `SingleExecutorScheduler`, existing publication outbox, execution permits and CORE publisher; two method engines SQL and JAVA. Preserve immutable source and historic report snapshots. Use Java `BigDecimal` and SQL `numeric` for money, explicit scale and HALF_EVEN policy; do not coerce missing to zero, mix currencies, periods, scopes, reporting basis, actual and synthetic. Client parameters cannot change trusted source, authority, engine or SQL.

**R2 historic M1/M2/M3 are acceptance milestones** (plan-only; controlled synthetic execution; read/API/S2), not delivery stage labels below; to avoid naming collisions, the workspace implementation stages are called **W0–W8**. Historic acceptance guards remain mandatory, even when detailed execution mechanism is superseded.

**Default flags:** `fin.execution.mode=OFF` (new effective mode), `fin.execution.enabled=false` (legacy compatibility), `fin.workspace.enabled=false` (new proposed), method-specific flags remain off where currently off. Enabling a scheduler flag is neither a financial execution permit nor a release approval. `ACTUAL` execution remains blocked pending independent method S2 + approved source + server authority; `RELEASE_HOLD` persists. Do not automatically alter catalog 166 forms, `CONFIRMED_UNQUALIFIED_DRAFT`, `releaseQualified=false`, `CORE_PILOT_V1` PARTIAL/BLOCKED, React/Flutter/Keycloak/IAM. No new branch, main merge, force push or rewriting old Flyway.

## 1. Code-backed limitations and intended replacement

| Observed component | Present behavior on baseline | Required evolution |
|---|---|---|
| `SingleExecutorScheduler.tick()` | One event and at most one compute claim per invocation; scheduler disabled by default | Same orchestrator, event planning/activation physically separated; bounded multi-worker claim; admission before claim |
| `planOne()` | `autoEligible()/applicable()` adapter scan | Canonical signed/versioned registry, exact source, separate applicability and permit; immutable DAG plan |
| Task claim | Global `next_attempt_at,id`, `SKIP LOCKED`, token+lease 60 s | Tenant fair queues and resource class scheduling; global+tenant+engine tokens; configurable bounded lease+heartbeat |
| DB schema | `analysis_run.event_id UNIQUE`, `outbox_delivery.event_id PRIMARY KEY`, `analysis_task UNIQUE(run_id,method_code)` | New immutable plan identity + versioned task bindings via forward-only migration; do not overload historic event rows |
| Method modules | 7 legacy adapter descriptors; new read-only module bridge; FIN-01 isolated SQL pilot | Typed `MethodInput`/result contract bound to approved population and exact variant/engine checksum, adapter bridge; no bypass |
| `fact_set_publication` + outbox | Trusted published source and immutable event | One reusable sealed, scoped, derived workspace with own fingerprint, never replaces original source |
| result_json on analysis_task | Operational mutable lifecycle | Separate append-only logical result receipts; operational task row references receipt after commit and fencing |
| Release and qualification | Independently held | No new authorization from workspace availability or SQL success |

### Architectural path

```text
FactSetPublication + immutable outbox
    -> trusted source / tenant + ABAC / plan fingerprint
    -> Applicability + ExecutionPermit + ModuleRegistry
    -> read-only PlanPreview (NO writes, NO tasks)
    -> immutable PlanCommit (NO compute)
    -> separately permitted Activation + global/tenant admission
    -> WorkspaceResolver -> materialize -> validate -> SEALED
    -> tenant-fair ready task selection over single durable DAG
         | SQL worker: prepared SQL + data-space enforcement + timeout
         | JAVA worker: typed BigDecimal models + bounded execution
    -> fenced, schema-validated immutable result commit
    -> existing CORE/report publisher and authorized read-only API
    -> safe TTL cleanup of derived workspace only
```

## 2. Terminology and source identity

- **Tenant** = server-authenticated `organization_id` and policy-based capacity account; user-supplied org UUID cannot grant visibility or capacity.
- **SourceKey** = organization, entity, scope, revision, snapshot hash, data space, reporting basis, publication/outbox IDs, approved selected source population checksum.
- **WorkspaceKey** = canonical hash(SourceKey, input population contract version, population selector/policy, materialization schema version, normalization policy, currency/time policy). Key never uses wall-clock, worker UUID, connection ID or client locale. Multiple runs may **reuse** immutable sealed workspace only if exact key and authorization/retention requirements match.
- **PlanKey** = canonical hash(SourceKey, profile, exact ordered method versions, descriptor fingerprints, assumption/policy fingerprints, DAG version); changing method code/SQL changes plan.
- **ResultKey** = canonical hash(SourceKey, PlanKey, method/variant/implementation fingerprint, exact selected input facts, parameters, assumption and rounding versions, result schema version).
- **ExecutionAttempt** = ephemeral lease, token, worker, retry count and timestamps; never part of logical result identity.

Everything that affects numeric outputs must be versioned and included in deterministic hashes. A staged workspace is not an approved FactSet and is never an ACTUAL permit.

## 3. Proposed PostgreSQL logical schema (contract, not executed DDL)

**Additive schema** `fin_workspace` after preflight of existing Flyway namespace. Exact SQL/columns require review and PostgreSQL RED fixtures before GREEN implementation.

| Table | Identity and contract | Access/immutability |
|---|---|---|
| `workspace` | workspace_id, tenant, entity, scope, revision/hash, dataSpace/basis, population contract+hash, state, generation, size budget, sealed_at, expires_at | Metadata state transitions CAS; lineage immutable after SEAL |
| `workspace_population` | workspace_id + population_code + schema_version, selected IDs, count, checksum, totals/control metrics | Append-only then SEAL; immutable thereafter |
| `workspace_fact` | workspace_id, org, assertion_id, metric version, period, currency, unit, basis, numeric/value state, origin, source revision, row fingerprint | Fixed HASH partitions by workspace_id; deny insert/update/delete after SEAL |
| `workspace_intermediate` | workspace_id + computation_stage + method identity + run plan id + row key, typed scalar/numeric/json, stage checksum | Bounded, versioned; attempt-fenced staging; promote via atomic SEAL |
| `workspace_reference` | workspace_id + analysis_run_id, pinned_until, purpose and required retention | Guards cleanup; no cross-tenant sharing |
| `workspace_stage` | workspace_id, stage code/version, state, prepared/count/hash, stage fence and error | Transactional stage checkpoint/recovery |
| `tenant_resource_policy` | tenant, weight, max_active_runs, max_sql_workers, max_java_workers, queued bytes/jobs, SQL statement budget, memory estimate, global policy version | Server-authorized, no client update |
| `tenant_capacity_lease` | tenant, engine, run/task, reserved class/units, lease expiry/fence | Atomic admission, released on completion and recovered on expiry |
| `method_result` in reviewed `fin_exec` or `fin_calc` | one immutable semantic receipt per ResultKey, source/method/version/engine/checksum/output+provenance | UNIQUE tenant + ResultKey; reject UPDATE/DELETE, receipt must survive workspace cleanup |

Existing `analysis_run`, `analysis_task`, `outbox_delivery`, `analysis_dependency`, `core_report_snapshot` and historical event IDs keep their original meaning. New versioned re-evaluation contract must avoid `analysis_run.event_id UNIQUE` collision. New foreign keys, integrity checks, scope indexes and permissions required.

**Reference SQL sketch only (not migration):**

```sql
CREATE TABLE fin_workspace.workspace_fact (
    workspace_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    assertion_id uuid NOT NULL,
    metric_code text NOT NULL,
    metric_version text NOT NULL,
    period_start date NOT NULL,
    period_end_exclusive date NOT NULL,
    currency_code char(3),
    value_state text NOT NULL,
    amount numeric(38,12),
    PRIMARY KEY (workspace_id, assertion_id)
) PARTITION BY HASH (workspace_id);
-- Build a FIXED set of 8/16 hash partitions with forward-only Flyway.
-- Full column set, constraints, origin and immutability gating are pending review.
```

Use **fixed hash partition count**, selected by proof (initial comparisons 0/no partition vs 8/16/32, subject to dataset size); no per-tenant, per-run, per-method runtime DDL. Each query carries `workspace_id` (and tenant scope where applicable) to enable pruning. Partition boundaries are deploy-time reviewed migrations. TTL by indexed batch row cleanup with short transactions **not** dropping hash partitions. Snapshot-based source queries may not need materialization: choose direct published FactSet vs shared workspace by measured repeated-read cost.

Initially use **LOGGED** workspace to survive PostgreSQL crash. `TEMP TABLE` is connection-scoped and invalid for worker sharing. `UNLOGGED` only optionally for fully reconstructible stages after proven crash recovery and explicit risk acceptance; no use for durable source, plan, permit, task, result or audit. Avoid oversized JSON-only staging: use typed fields for hot filters and numeric aggregates.

### Workspace state machine

`CREATED -> LOADING -> VALIDATING -> SEALED -> ACTIVE -> RELEASABLE -> EXPIRED`; `FAILED -> RECOVERING -> LOADING` on permitted retries; `QUARANTINED` for hash mismatch/revoked source. No task compute on workspace until SEALED. Atomic seal transaction must validate total row count, source key, selected fact identities, normalized measure/unit, currency/time/population coverage and deterministic hash. No partial population exposed as complete.

**Workspace reuse:** only identical SourceKey + selection policy and exact derived input semantics; tenant must match; referenced run cannot change workspace rows; cross-tenant cache forbidden even with identical fact amounts. Workspace may be reused across method variants only when their declared population contract and assumption schema are identical; otherwise create independent sealed workspace/stages. No global cache keyed only by metric code.

### Cleanup

No cleanup while live task lease, in-flight SQL statement, active run, nonterminal stage, referenced report materialization, legal hold or pinned replay dependency. After transition to RELEASABLE: bounded batched deletion with `SKIP LOCKED` where appropriate, retention evidence, refcount validation, metrics and dead-letter. Initial TTL hypothesis 24h **after final stage + no references**, subject to actual recovery/S2 requirements. No `DROP PARTITION` for per-workspace cleanup. Pressure-driven eviction still respects these conditions.

## 4. Tenant admission, fairness, priorities and resource budgets

**Mandatory two-level resource admission:** global (protect PostgreSQL/host) and tenant (isolate noisy neighbors); additional per-engine SQL/JAVA and per-method resource class. A task is runnable only if: source, method permit, DAG dependencies, workspace seal, fair turn, resource budget, backend pool and cancellation state are satisfied. A committed plan that is not admitted remains queued; no implicit execution.

**Baseline configurable policy (hypotheses, NOT measured defaults):**
- Global max active analysis runs, SQL workers, Java workers, queued jobs, per-stage input rows, workspace bytes, estimated cost, daily tenant consumption and expensive-class slots.
- Per-tenant max active runs, parallel SQL/JAVA tasks, queued tasks, workspace bytes, CPU-time budget, task deadline; default safe limit **1 SQL worker per tenant** until load testing; no infinite retry loops.
- Reserved PostgreSQL connections for API/OLTP and migration/recovery; separate bounded SQL worker pool; pool sizing must satisfy `max_sql_worker_db_connections + all other pool maxima < db max_connections - reserves` under worst case across all application instances.
- SQL guardrails: server-set `statement_timeout`, `lock_timeout`, bounded results, join cardinality checks, memory-related `work_mem` tuning per resource class (do not set unbounded per session); PostgreSQL cannot enforce exact per-query CPU by tenant using ordinary connection pools.
- Java guardrails: fixed/limited executor, memory/heap budgets and timeout/cancellation, no DB transaction held while running long Java model, queue/latency SLO.
- Global pressure thresholds (CPU, DB active connections, free disk/WAL, temp files, query p95, replication lag if applicable) => reduce admission and enter SHED/DEGRADED; fail closed on unknown resource state for heavy tasks. OLTP and trusted publication must retain capacity.

**Scheduling policy:** persisted tenant-active counters plus atomic capacity leases, deterministic tenant grouping, deficit round-robin or weighted fair scheduling, per-tenant starvation aging and bounded priority. All instances must acquire quotas transactionally (one DB source of truth), not local JVM counters only. Avoid hold-and-wait: acquire all required capacity atomically in short transaction, otherwise requeue without claim; release on terminal/fenced completion; expired capacity leases reconciled without oversubscription. `SKIP LOCKED` plus tenant fair admission should be verified under at least two competing worker processes.

**Idempotent claim:** tenant fairness and capacity reservations must be done atomically with task claim; avoid competing workers first claiming then discovering tenant quota. Stage side effects must be fenced and idempotent, unique result key and immutable receipt. A stuck SQL statement after lost lease may continue using CPU unless server timeout/cancel works; test it.

**Resource classes:** `LIGHT` (simple ratios), `MEDIUM` (monthly aggregates), `HEAVY_SQL` (large population aggregation), `HEAVY_JAVA` (scenario valuation). These are examples, not pre-classification of all 60 methods. Dynamic SQL query budgets must be fixed by reviewed descriptor+policy; no generic arbitrary SQL execution.

## 5. SQL/JAVA execution contracts and staging

**SQL module:** immutable server-shipped SQL version+SHA, prepared parameters, trusted and scoped typed input relation, deterministic ORDER BY / tie-break for windows, explicit NULL/zero/negative and gaps, one-to-many fanout and duplicate metrics checks, DECIMAL scale/rounding, statement timeout, row cap, EXPLAIN review. SQL may materialize intermediate stage output only under lease/fence with stage_id + attempt_id and server-controlled write rights. Prefer read-only SQL work and separate controlled stage writer. No CREATE TABLE per task.

**JAVA module:** typed `MethodInput` from same sealed workspace or published snapshot, stable input fingerprint, BigDecimal and explicit MathContext, bounded CPU/memory/time, no arbitrary external network/SQL writes, independent golden oracle. Results only through the shared `MethodResultEnvelope` + immutable store.

FIN-01 acceptance explicitly compares SQL percent change with `QualifiedRevenueHorizontalAdapter` on the **same published, qualified, two consecutive months REV_NET source**, matching scope, metric version, currency, reporting basis and exact source hash; compare numeric BigDecimal and value status/reason codes, not JSON formatting. Include previous month 0, negative, explicit zero, missing, out-of-order and duplicate months, January/December, large numbers, repeating decimal HALF_EVEN rounding, mixed currency and tenant. Current isolated SQL pilot success by itself is not proof of production authorization. FIN-02 and CON-01/02 only after FIN-01 parity passes.

**Versioning:** any changed SQL logic or window/tie-break/rounding creates a new implementation version and checksum. Existing receipts never recomputed in place. Canonical full input fingerprint plus policy version required. Multilingual rendering must not alter numeric result hash.

## 6. Work breakdown: incremental delivery and executable gates

| Stage | Deliverables (backend only) | Blocking evidence |
|---|---|---|
| **W0 — design/preflight** | Current SHA, ADR-R2-06, supersession ledger, schema checks, tenant identity and quota threat model, storage sizing, FAIL-CLOSED RED acceptance fixtures | No new plan execution; architectural S2 design review separate from financial method review |
| **W1 — immutable workspace** | Additive Flyway `fin_workspace` schema, fixed partitions, source-pinned population builder/validator/sealer, retention metadata; explicit prepared/failed states | PostgreSQL 16 Testcontainers fresh+upgrade, tenant separation, mis-scoped source, crash in LOADING, seal contention, exactly one reproducible population |
| **W2 — governance/admission** | Server-only tenant policies, global/tenant/engine quotas, transactional capacity lease, fair admission, bounded worker pool, metrics; preserve existing adapter path behind OFF flag | Tests: two tenants 1 heavy+N light, starvation bounds, quota contention 2+ workers, 100x replay, no over-admit, DB pool reserve |
| **W3 — FIN-01 SQL pilot** | Finish typed SPI, SQL resource/version/hash, sealed-source FIN-01 module, Java independent golden/legacy parity, safe synthetic opt-in | 2-month published FactSet, PostgreSQL vs Java bitwise canonical decimal equivalence at policy scale, all negative cases; legacy ACTUAL unaffected |
| **W4 — task & immutable receipts** | Versioned plan binding and guarded activation; new immutable result store, exactly-once logical key, fencing/heartbeat/cancel/retry/DLQ | Crash before/after result commit; duplicate SQL workers; stale fence cannot publish; no history mutation; old run readable |
| **W5 — DAG & other method pilots** | SQL FIN-02/CON-01/CON-02, Java FIN-04/profit/cash pilots only as qualified variants; shared workspace reuse with explicit population contract | Parallel vs sequential equivalence; dependency semantics; poison task isolation; 7 baseline adapters preserved |
| **W6 — load/capacity calibration** | Quantified p50/p95/p99 per method/class/tenant; 1/2/4/8 workers + 1/2/10/100 tenants; partition/no-partition comparison; index/EXPLAIN | Measured throughput, fairness, OLTP interference, PostgreSQL CPU/memory/WAL/temp/disk; no unverified p95 targets |
| **W7 — operations & security** | Idempotent TTL, interruption/recovery, RLS/DB grants review, no arbitrary SQL, ops dashboard/structured audit, safe rollout/rollback | Cross-tenant abuse, cleanup lease race, restart, revocation, disk emergency, production disable |
| **W8 — final acceptance** | Full same-SHA CI, PostgreSQL Testcontainers, FIN-BE-R1-X6 166 forms regression, immutable CORE, independent S2 for architecture/security and each method, MacBook smoke | `ENGINEERING_GREEN` only for evidenced scope; ACTUAL and `RELEASE_HOLD` separately controlled |

**Priorities:** W0 (this specification) -> W1 (schemas/fixtures) -> W2 (governance) -> W3 (SQL parity) -> W4/W5. W3 may continue as **isolated nonproduction pilot** in parallel with W1/W2, but must not be scheduler-wired without admissions and permits. Do not bundle W1–W8 in one unreviewable PR/commit. Preserve sole branch.

## 7. Concrete proof matrix and SLO policy

- **Security:** tenant A cannot see/modify workspace or read results of B, including bad join, cache collision, guessed UUID, logs, API and concurrent claim. Reject mismatched source revision, hash, dataSpace, basis and metric version; reject client SQL/engine/class selection.
- **Correctness:** MISSING vs EXPLICIT_ZERO; negative baseline, denominator zero, period holes/overlap, mismatched currency, stale source, duplicate source rows, incomplete approved population; FIN-01 SQL = Java golden; do not infer S2 qualification from math.
- **Isolation/concurrency:** duplicate workspace build -> one SEALED; stale stage writer rejected; two workers competing; multiple app instances; capacity tokens never negative or oversubscribed; cancel can reclaim stalled SQL; no tenant starves when within quota.
- **Recovery:** restart of Java process / PG during LOADING, SEALED, RUNNING, after result written before ACK; no double logical result or source mutation; safe retry and DLQ.
- **TTL:** active lease or legal hold blocks purge; expired workspace purges in bounded batches and results/revisions remain; repeated cleanup is no-op; rate limiting avoids sustained OLTP degradation.
- **SQL/DB:** no seq scan across all hash partitions when key bound (unless optimizer deliberately chooses efficient scan on tiny table, record plan evidence); indexes on (workspace_id,metric_code,period...) and task readiness only when measured; `EXPLAIN (ANALYZE,BUFFERS)` on seeded realistic dataset, compare static partition 0/8/16/32.
- **Performance:** do not assert arbitrary gains; establish baseline throughput, p95/p99 queue and compute latency, tenant wait time, fairness (max wait), PostgreSQL connection headroom, CPU, memory, temp file, WAL, bloat/disk and OLTP HTTP p95. Define pass/fail thresholds from measured baseline and explicit product SLA/available hardware **before** performance acceptance. Tenant load tests: 1,2,10,100 tenants; 1,2,4,8 SQL workers; heavy+light mixed workload, including overload and recovery.
- **Release:** green code tests and separate same-SHA independent S2 evidence, no unfenced ACTUAL, no preemptive production enable. `CORE_PILOT_V1` remains PARTIAL/BLOCKED.

## 8. Deliverables for the next engineering task (W1)

Codex/team must: inspect existing migration namespace; choose next valid Flyway version; write RED PostgreSQL fixtures for workspace source consistency, duplicate seal, cross tenant, concurrent lease and immutable sealed population; have independent S2 review schema + fixture requirements; implement minimum additive schema and one population builder from a real published FactSet; prove `CREATE/LOADING/SEALED` via PostgreSQL 16 Testcontainers. Do not implement dynamic per-task partitions, third executor, ACTUAL authorization, result promotion or customer-visible report endpoints.

**Every W-stage closeout** reports baseline and HEAD SHA, exact files changed, test names/counts, GitHub Actions URLs, remaining HOLD/P0, reproducibility instructions. “IMPLEMENTED” alone is not acceptance.
