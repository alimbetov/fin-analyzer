# ADR-R2-06 — Tenant-Aware Calculation Workspace, Governed SQL/JAVA ETL Execution

**Decision:** ACCEPTED TARGET DESIGN / IMPLEMENTATION PENDING
**Date:** 2026-11-17
**Repository:** `alimbetov/fin-analyzer`, **sole branch:** `feat/fin-r0-r1a`
**Code-reviewed baseline:** `8f63a335630185cad364b34f75f554b2f0993ec7`.
**Normative detailed specification:** [FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ.md](FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ.md).
**Supersession rules:** [FIN-BE-R2-EXECUTION-SUPERSESSION-LEDGER.md](FIN-BE-R2-EXECUTION-SUPERSESSION-LEDGER.md).
**Previous ADR:** [ADR-R2-05](ADR-R2-05-METHOD-MODULE-DUAL-ENGINE-IMMUTABLE-RESULTS.md), preserved except superseded execution topology and delivery sequencing.
**Status:** This is an architecture decision only, not deployed feature, DB migration, CI proof or S2 approval.

## Decision

FIN adopts **one tenant-aware, capacity-governed calculation plane** to execute applicable versioned business `MethodModule` variants via **SQL** or **JAVA** engines. A **single server-controlled orchestrator**, extending the current R2 planner and `SingleExecutorScheduler` execution boundary, coordinates all modules using a persisted DAG, tenant admission, bounded worker pools, shared trusted calculation workspace, immutable result receipts and resource observability.

The execution plane is a **modular monolith + PostgreSQL**, not 60 microservices, not an independent ETL scheduler, not a second outbox. Source truth stays in immutable published `fin_fact` and `fin_exec.fact_publication_outbox`. Workspace is a reproducible **derived cache**, not a source of financial truth or a method execution permit. SQL/Java runtime is chosen per versioned module; a Java method may consume checked SQL-prepared inputs without creating a third execution engine.

**Mandatory invariant:** task scheduling, preparation, compute, and result publication are independently gated; new capacity controls may **reduce**, never expand, financial permissions. `ACTUAL` remains HOLD absent separate financial S2 / execution authority; `SYNTHETIC` never becomes ACTUAL through workspace materialization. No automatic report `COMPLETE` or public output.

## Verified starting constraints and migration considerations

Current `SingleExecutorScheduler` performs planner intake and at most one task claim per `tick()`; automatic polling is off unless `fin.execution.enabled=true`. It currently orders candidates globally by `next_attempt_at,id`, uses `FOR UPDATE SKIP LOCKED`, a fixed 60-second lease, fencing token, transactional completion, basic retry, and task status columns; it has no per-tenant admission/fairness/active CPU/DB budget or resumable workspace. `analysis_run.event_id` and `outbox_delivery.event_id` are unique: do not repurpose historical event identity to manufacture versioned reruns. New immutable plan identity must preserve old runs and outbox.

The FIN-01 SQL pilot is currently separate from legacy execution and must not be treated as production-wired or financially qualified. Prior `MethodModule` read-only legacy bridges exist. All existing R1-X6 import and snapshot/report behavior must remain intact.

## Explicit architecture choices

1. **Tenant identity:** an authenticated organization/tenant is the primary scheduling quota and isolation dimension. `economic_entity_id`, `business_scope_id`, `fact_set_revision_id`, `snapshot_hash`, `data_space`, `reporting_basis`, `analysis_run_id`, and canonical `plan_fingerprint` are pinned before workspace admission. A tenant is not an arbitrary HTTP-provided organization ID. Idempotent runs only reference server-verified published facts.
2. **Separation of control/data planes:** Java-only controller/planner/permits/lease/fencing/quota/telemetry; SQL/Java engines only compute trusted scoped inputs; immutable result publisher validates source, authority, schema and final fence. No SQL resources or table identifiers from client JSON.
3. **Fairness:** durable per-tenant queued/running counters, admission tokens, resource classes, global and per-tenant caps. Bounded deficit round-robin / weighted fair selection across tenants, FIFO/aging within classes; upper-bounded premium weights and starvation cap. Never use globally FIFO-only claim or unlimited executor threads. Tenant scheduling is **not** PostgreSQL-native query scheduling; enforce workload by application admission, database roles/pools, query timeouts and measurements.
4. **Backpressure:** reject/defer task activation when no tenant/global/engine tokens; concurrency and DB connection quotas must be coordinated; keep worker capacity below datasource max pool and PostgreSQL connection budget with reserve for OLTP/HTTP/migrations. Bulkhead for SQL and Java, separate read/compute and commit connections if demonstrated necessary. Use bounded queue, memory & CPU budgets, statement_timeout, lock_timeout, bounded row/materialization size and cancellation.
5. **Workspace:** committed, scoped `fin_workspace.workspace_run`, `workspace_population`, `workspace_fact`, `workspace_intermediate` with status, sealed fingerprint, retention and reclaim state. Materialize a stable verified population once per compatible source/contract; allow read-sharing only within identical tenant/source/policy/variant input semantics and explicit reference counts. Concurrent writers synchronize via canonical uniqueness + short transactional seal, not TOCTOU. No mutable input after SEALED.
6. **Partition strategy:** **fixed HASH partitions** for fact/intermediate workspace by stable workspace ID (initial hypothesis e.g. 8 or 16, not a performance claim), assigned to all tasks from that workspace. Partition count is deployment configuration + Flyway migration, **not** physical per-task/per-tenant DDL and **not** a partition per method. Range or subpartitioning only when measured row volume and partition pruning justify it. Full partition-key predicates on SQL scans; validate through EXPLAIN (ANALYZE, BUFFERS), query logs, pg_stat_statements if enabled. Partition creation and maintenance are separate deployment operations, never executed per user request.
7. **Durability:** initially LOGGED PostgreSQL workspace tables. Do not use session `TEMP TABLE` for cross-worker data; no `UNLOGGED` for irreplaceable or non-rebuildable workflow state. Intermediate materialization can be rebuilt from immutable published source; replay input fingerprint must match. Long SQL processing uses short read-only snapshot transaction per stage or staged sealed materialization; do **not** hold snapshot transactions open during large Java compute.
8. **Retries and crash:** reusable workspace prepared/SEALED with content hash; a task may retry, but only one fenced attempt can commit an immutable logical result. Expired attempt's SQL statement must be bounded and explicitly cancelled/reaped; a worker that lost lease may not mutate workspace seals or publish results. Structured reason codes and dead-letter. On restart recover orphaned leases, reconcile/seal/refcounts and permit decisions.
9. **Results:** one append-only result store with unique logical result key and all tenant/source/method/SQL-checksum/parameters/assumptions/permit/provenance fingerprints. Workspace expiration never deletes published result receipts or immutable source audit; status COMPUTED != METHOD_S2_APPROVED != PRODUCTION_ENABLED.
10. **Operations:** workspace TTL only when all users terminal, no live lease, no pinned retention/legal hold; cleanup should be idempotent, incremental, cursor-batched and globally rate-limited. HASH partitions are **not** dropped as TTL strategy. Monitor bloat, autovacuum, dead tuples and WAL; set disk capacity emergency brakes.
11. **Security:** defense in depth with app-level tenant predicates, database privileges/RLS if feasible against existing schemas, strict workspace joins, read-only SQL worker privileges, no SQL injection or search_path shadowing, deny cross-tenant joins, no leakage via scheduler reason codes or traces. NO dynamic SQL from API.
12. **Migration:** maintain one existing outbox and one scheduler implementation name. Introduce versioned plan/task identities additively via forward-only Flyway, old run rows unchanged, rollback via disabled feature flag `fin.workspace.enabled=false` and no destruction of historical receipts. Existing method adapters run through compatibility bridges; enable SQL pilot only through new guarded plans and narrow synthetic scope after independent proof.

## Tradeoffs and non-goals

Pros: workspace reuse, set-based PostgreSQL execution, bounded parallelism, tenant fairness, lineage, deterministic replay. Costs: extra storage/WAL/cleanup, admission complexity, cache invalidation, sophisticated concurrency tests and potential PostgreSQL contention. PostgreSQL partitioning alone **does not ensure fairness or performance**; measure and tune. Avoid automatic migration of all 60 method families before each variant's semantics, inputs and golden results are qualified. Do not introduce Kafka, Kubernetes, Redis, distributed cluster, ML, frontend or new auth as prerequisites.

## Acceptance before enablement

M0 architectural / DB review and risk ledger; M1 schema + sealed derived workspace with real PostgreSQL tests; M2 tenant admission/fairness/load-shedding with 1/2/4/8 workers; M3 FIN-01 SQL vs Java same-source parity; M4 immutable receipts, fencing, crash/cleanup; M5 sequential/parallel DAG integration; M6 security, full regressions, performance evidence, independent S2. Detailed requirements and disambiguated milestone labels are in the linked specification.

**Release decision:** `ENGINEERING_DESIGN_ACCEPTED` does not imply `ENGINEERING_GREEN`, `METHOD_S2_APPROVED`, `ACTUAL_EXECUTION_PERMITTED` or `PRODUCTION_READY`. `RELEASE_HOLD` remains.
