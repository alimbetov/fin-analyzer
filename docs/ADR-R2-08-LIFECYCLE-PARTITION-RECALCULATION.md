# ADR-R2-08 — Lifecycle-Partitioned Recalculation and DETACH/DROP Garbage Collection

**Decision:** ACCEPTED TARGET ARCHITECTURE / NOT IMPLEMENTED / MEASUREMENT AND INDEPENDENT S2 PENDING  
**Date:** 2026-11-17. **Repository:** `alimbetov/fin-analyzer` **sole branch:** `feat/fin-r0-r1a`.  
**Supersedes:** `ADR-R2-06` and `FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ` **only** where they prescribe bulk per-workspace `DELETE` as the normal cleanup of derived data; prior prohibitions on dropping **shared HASH partitions containing other active workspaces** still apply.  
**Related:** [ADR-R2-05](ADR-R2-05-METHOD-MODULE-DUAL-ENGINE-IMMUTABLE-RESULTS.md), [ADR-R2-06](ADR-R2-06-TENANT-AWARE-CALCULATION-WORKSPACE.md), [ADR-R2-07](ADR-R2-07-VERSIONED-FINANCIAL-AGGREGATE-LAYER.md), [W-TZ v1.2](FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ.md), [W technical review](FIN-BE-R2-W-TECHNICAL-REVIEW-2026-11-17.md).  
**Code baseline:** `6019fe4a7c049d0b4dfd6165cc14cbd70ef52d03`. This ADR is documentation, not an implemented migration or performance proof.

## 1. Problem and decision

FIN needs frequent recalculation of method layers for up to 60 business method families across many tenants. Deleting millions of expired intermediate rows from shared HASH partitions one row at a time causes WAL, index churn, autovacuum pressure and long cleanup, and does not match the desired ability to discard a whole stale generation quickly. PostgreSQL 16 explicitly supports inexpensive bulk removal via dropping a child partition or detaching it first, unlike `DELETE` against each row. **The unit of physical DROP must equal a complete, provably reclaimable dataset**, not an arbitrary tenant or method selector.

**Target:** keep `fin_fact` published source, audit/outbox, immutable method receipts, published reports, tenant capacity leases and active workspace metadata outside the drop path. Store **only reconstructible, temporary derived detail and intermediate outputs** in bounded **lifecycle cohort partitions**. Discard a retired physical cohort through a dedicated privileged maintenance process after all pinned generations and tasks within it have finished. Large, exceptional dedicated workspace partitions are an opt-in tier, not the default for every method/run/tenant.

**No arbitrary client DDL, no 60 partitions per run, no partition per tenant/currency/metric.** One scheduler and existing outbox, two computational engines, ExecutionPermit/ACTUAL HOLD and source lineage rules stay unchanged.

## 2. Physical layout and partitioning key

Recommended first candidate:
- Persistent unpartitioned control/audit: `fin_workspace.workspace` (source key, owner tenant, generation, stable workspace key, active generation pointer, state), `workspace_reference`, `workspace_stage`, `workspace_partition_catalog`, `workspace_partition_membership` and `workspace_cleanup_event`.
- Physically droppable reconstruction cache: `fin_workspace.workspace_fact_stage` and `workspace_intermediate_stage`, partitioned by **RANGE (reclaim_epoch)** (e.g. UTC calendar-day or week cohort, selected by measured size/retention). Each row also carries `organization_id`, `workspace_id`, `generation`, `attempt_fence`/stage, exact source assertion IDs and content hash. A cohort contains many runs/tenants **only when all rows have compatible earliest reclaim dates and the entire cohort can be retired together**. The application binds **both** `reclaim_epoch` and `workspace_id` for partition pruning; a lookup by workspace_id alone may scan every cohort.
- A cohort's boundaries represent an **eligibility/retention bucket**, not `date_of_fact`, `reporting_period` or `currency`; those are typed query/index fields. Two generations of the same workspace can reside in different cohorts.
- Optional secondary hash subpartitions or dedicated per-large-run partitions require independent measured approval. A fixed `HASH(workspace_id)` layout remains an A/B **query-performance candidate**, but shared HASH partitions cannot be dropped per workspace: do not promise O(1) per-workspace garbage collection there.
- Partition counts are bounded by an operational budget and retention window: design/pre-create time buckets through a privileged maintenance job, not a Flyway per run, not DDL from a JDBC worker. Example planning estimate `partitions ≈ buckets_per_retention_window × stage_parents` (plus precreated/recovering buckets), not `tenant_count × method_count`.
- `PRIMARY KEY/UNIQUE` on a RANGE-partitioned parent must include `reclaim_epoch`; `organization_id` scoping must be in composite FKs to stable control tables and explicit query predicates. Never infer global uniqueness of `workspace_id` just from a partition-local unique index. All retention/partition identifiers are server-owned and immutable after sealing.

**Cohort readiness limitation:** a single live reader, legal hold or late task can block dropping the entire cohort. This is deliberate: retention cannot override safety. Long-lived/pinned exceptions should be placed in a separately sized retention class **at admission**; never silently reassign or move sealed data across cohorts mid-query. If an unexpected hold arises, defer dropping the cohort and account its retained bytes; do not fall back to deleting other active rows. Operations should alert on blocked cohorts.

## 3. Atomic generation switch; fast recalculation without unsafe DROP

1. User/server requests new calculation on a specific already-published FactSet; the **new plan and method implementation version** is pinned. Never mutate existing source or canonical results.
2. After tenant/global PREPARE admission, allocate `generation=N+1` and a compatible not-yet-expired cohort; build rows in bounded, isolated batches under workspace+generation+fencing token. Prior generation N remains fully readable.
3. Validate complete selected source population (IDs, count, value states, period/currency/unit, input hash), metric/aggregation policy, and deterministic content hash; `SEALED` is a short CAS transaction. No unsealed generation can serve method calculations.
4. In one small control-plane transaction switch the **server-owned active generation pointer** from N to N+1 after validation and authorized plan readiness. In-flight tasks pinned to N keep their references and must not be transparently switched. Method results must identify exact generation and source fingerprints.
5. Mark N `RETIRED` and later `RECLAIMABLE` only when there are no active/pending task or plan references, live/uncertain leases, database statements, legal holds, replay dependencies, or retention obligations. Use a positive inventory of all members of its physical cohort; **N reclaimable does not imply cohort reclaimable**.
6. Entire cohort can be detached/dropped only when **every member workspace/generation** is reclaimable, all reader references are prevented from being newly acquired, and the safety barrier is atomically recorded. Rebuild, plan and computation admission must reject `DRAINING` cohorts and cannot attach new members thereafter.

The **pointer switch is the fast recalculation result activation**. Partition DROP is deferred physical garbage collection and must not be in the user's HTTP request latency path. A new generation may reuse the prior immutable source or verified aggregates; changing SQL/parameter policy creates a new result key and cannot overwrite old receipts.

## 4. Partition-garbage-collection state machine and privileges

```text
OPEN -> CLOSED_TO_NEW_WRITES -> DRAINING -> DETACH_REQUESTED
     -> DETACHED -> DROP_REQUESTED -> DROPPED
        \-> RECOVERY_REQUIRED (recoverable without loss of live data)
```

**Transition barrier** in a short DB transaction: lock catalog/cohort control row, validate every member is terminal and reclaimable, source/output retention pins and physical query liveness have been reconciled, then mark `DRAINING`; all new references, members and leases must check that state under compatible transactional locking. A worker denied by the barrier must redirect to a new cohort rather than opportunistically recreate one.

**DDL authority:** a dedicated trusted partition-maintenance service/role, distinct from method SQL/JAVA worker and app API; no arbitrary SQL, schema or table name from clients. Resolve an allowlisted partition parent and child using a server-owned `workspace_partition_catalog` and verify catalog parent/child relationship, bounds, generation, row/liveness inventory and fixed identifiers. Never use `CASCADE`. No direct SQL module DDL grants.

**PostgreSQL 16 preferred sequence for an eligible ordinary leaf partition**:

```sql
-- Maintenance connection, autocommit: NOT inside an explicit transaction block.
ALTER TABLE fin_workspace.workspace_fact_stage
  DETACH PARTITION fin_workspace.workspace_fact_stage_20261117
  CONCURRENTLY;
-- Only after verified detach and safe catalog state, on a separate maintenance step:
DROP TABLE fin_workspace.workspace_fact_stage_20261117;
```

Same process separately applies to other physical stage parents; all their partition memberships must pass the same readiness checks. The child name in this code block is an **illustrative precreated, server-owned table**, not a string to accept from HTTP and not executable Flyway code. `DROP TABLE partition_child` while still attached is an alternate fast path but takes `ACCESS EXCLUSIVE` on the parent; prefer `DETACH ... CONCURRENTLY` for a busy shared parent when preconditions permit.

**PostgreSQL 16 constraints:** `DETACH ... CONCURRENTLY` uses a reduced parent lock but still waits for transactions using the partition and is **not permitted in an explicit transaction block**; it is **not permitted when the parent has a DEFAULT partition**; only one concurrent detach can be pending for a parent. A canceled/interrupted detach can require `ALTER TABLE ... DETACH PARTITION ... FINALIZE` before retry. Review foreign keys and dependencies: referencing parent/child may require locks or prevent DROP. Application uses small metadata transactions *around* the independent DDL, never assumes DDL and metadata commit are one atomic transaction. Recovery reconciles catalog state (`pg_inherits`, relation presence, pending detach) idempotently after crash. DDL operation locks and connection quota are globally limited; set finite lock_timeout/statement_timeout in maintenance sessions.

**Critical:** app-side flags/refcounts alone cannot prove there are no database queries. The barrier must coordinate transaction-scoped reader reference acquisition and termination, cancellation/fencing, and DB lock completion. An uncertain worker blocks DROP; cannot sacrifice correctness for speed.

## 5. What must NEVER be dropped

- `fin_fact.fact_assertion`, `fact_selection`, `fact_set_revision`, `fact_set_publication`, `approved_source_revision`, original source forms/raw import and immutable outbox;
- `fin_exec.analysis_run/task`, plan/permit/access audit, capacity leases, receipt/result store, CORE/report snapshots;
- `fin_agg` sealed aggregate manifests/cells still referenced by another method/run or retained for a released result's reproducibility. Aggregate storage can use an **independent retention-cohort DROP policy** only if **every aggregate manifest in the physical partition** is released, independently proven, and no retained lineage needs those rows. Do not confuse aggregate's reporting-month with its expiration bucket.

Cleanup is for ephemeral **derived** workspace payload only; small control-plane tombstone and reference rows may be updated/deleted under auditable policies. Never drop a shared HASH child simply to recalculate one method or tenant.

## 6. Acceptance tests (PostgreSQL 16 Testcontainers; RED before GREEN)

| Case | Action | Required result |
|---|---|---|
| W1D-PART-01 | Seed two active tenants within same cohort; expire only one | DROP is blocked, other tenant data remains accessible |
| W1D-PART-02 | Seed a cohort with 10 million rows in disposable fixture; all members expired | DETACH/DROP removes whole data payload with no per-row DELETE; metadata/immutable receipts survive |
| W1D-PART-03 | Start reader on N, SEALED generation N+1 and switch pointer | Reader remains pinned to N; new plan receives N+1; no stale result overwrite |
| W1D-PART-04 | Live SQL worker or unknown cancellation outcome after lease expiration | Cohort cannot detach; physical capacity not prematurely released |
| W1D-PART-05 | Kill maintainer between DETACH and DROP; rerun | Recovery detects detached orphan and drops only the verified eligible child |
| W1D-PART-06 | Interrupt `DETACH CONCURRENTLY`; retry with FINALIZE | No unsafe DROP; recovery to reconciled detached/dropped state |
| W1D-PART-07 | Attempt `DETACH CONCURRENTLY` in a transaction or with DEFAULT partition | Preflight prohibits unsupported setup (no unsafe fallback to live DROP) |
| W1D-PART-08 | Attempt worker DDL or forged child name | Denied by DB privileges and allowlist; other partitions unchanged |
| W1D-PART-09 | Tenant B with guessed workspace/partition ID requests N+1 or DROP | Denied by API/ABAC; no cross-tenant data/metadata leak |
| W1D-PART-10 | Large backlog of 1000 small runs vs a few heavy ones | Operational partition budget respected; no DDL-per-method explosion |
| W6D-CLEAN-01 | Benchmark DELETE vs DETACH/DROP by size, partitions, concurrent OLTP | Record total wall time, parent lock wait, query p95/p99, WAL, autovacuum, storage and blocked-retention bytes; no fabricated speedup |
| W6D-CLEAN-02 | Time rebuilding generation N+1 while N remains readable | Prove query/read continuity, atomic switch and bounded resource admission |

**Hard fail criteria:** any cross-tenant loss, loss of a referenced generation, schema/immutable result deletion, unauthorized drop, absent recoverable catalog state, DDL-induced unacceptable OLTP stalls, or uncontrolled partition count. The expected performance win applies to large bulk cleanup and must be benchmarked; small cohorts may cost more to manage than a row-level delete.

## 7. Delivery and change control

- New **W1D — Partition Lifecycle Prototype** (design benchmark and RED tests) must precede physical Flyway freeze; W1 standard source sealing remains.
- New **W4D — Generation Switch & Partition Reaper** integrates with W2 capacity governance and W4 fenced immutable results.
- New **W6D — DDL/GC Performance Acceptance** compares bulk DELETE, detach/drop, partitions per retention, lock contention, OLTP interference and partition budget.
- Legacy batch `DELETE` of **large ephemeral payload** is **SUPERSEDED as the preferred cleanup** where dedicated/cohort-drop partitions are proven. `DELETE` may remain for metadata/small exceptional leftovers, and is required for designs that mix active and expired data in a nondroppable partition (which is precisely why such layouts should be avoided for heavy workspace).
- Flyway migrations are additive and executed only after independent S2 review; dynamic DDL maintenance (partition creation/DETACH/DROP) is a controlled, audited operations API/job, not stored in old Flyway checksums.
- One existing orchestrator, no new task queue/outbox, no auto-ACTUAL. Feature flags default OFF. No new branches; no `main` merge.
- **Status:** Documentation decision recorded. No physical partitions have been created or dropped, no runtime reaper exists, and no PostgreSQL workload benchmark is claimed.

## 8. References

- PostgreSQL 16, [5.11 Partitioning](https://www.postgresql.org/docs/16/ddl-partitioning.html) — dropping/detaching partitions for fast bulk removal, locks and partition-count tradeoffs.
- PostgreSQL 16, [ALTER TABLE](https://www.postgresql.org/docs/16/sql-altertable.html) — `DETACH PARTITION CONCURRENTLY` restrictions, intermediate pending detach and `FINALIZE`.
