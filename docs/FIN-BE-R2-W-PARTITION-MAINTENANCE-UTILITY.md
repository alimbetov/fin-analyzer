# FIN-BE-R2-W — Workspace Partition Maintenance Utility (W1D prototype)

**Status:** READ-ONLY INSPECTOR IMPLEMENTED / DETACH-DROP EXECUTOR BLOCKED / S2 PENDING  
**Branch:** `feat/fin-r0-r1a`. **Normative:** [ADR-R2-08](ADR-R2-08-LIFECYCLE-PARTITION-RECALCULATION.md).

## Utility and scope

- Java class: `backend/src/main/java/kz/fin/backend/maintenance/WorkspacePartitionMaintenanceUtility.java`
- PostgreSQL 16 tests: `backend/src/test/java/kz/fin/backend/maintenance/WorkspacePartitionMaintenanceUtilityTest.java`
- Scope: exactly `fin_workspace.workspace_fact_stage` and `fin_workspace.workspace_intermediate_stage`; child names require server-owned prefix and a valid YYYYMMDD lifecycle date. Does not scan `fin_fact`, `fin_agg`, reports or immutable receipt tables.
- `inspect(Parent)` uses `pg_catalog.pg_inherits`, `pg_class`, `pg_namespace` and `pg_partitioned_table` to report attached child relation names, bounds, total bytes and interrupted-detach state.
- `inspectAll()` reports both known payload parents. The absence of a workspace schema or partition is returned as an empty collection.
- `previewRetirement(Parent, childName)` checks a known parent and strict generated child identifier. It returns either `NOT_FOUND` or `BLOCKED_UNTIL_LIFECYCLE_BARRIER` with a reason code and catalog evidence. **It never returns APPROVED** and never generates/executes destructive DDL.
- No HTTP endpoint, scheduled job, command-line destructive mode, new data source, migration, or auto-initialization of partitioned tables. Spring bean creation has no DDL or catalog-query side effect.

## Using the diagnostic internally

```java
var parts = utility.inspectAll();
var preview = utility.previewRetirement(
    WorkspacePartitionMaintenanceUtility.Parent.FACT_STAGE,
    "workspace_fact_stage_20261117");
assert preview.decision() ==
    WorkspacePartitionMaintenanceUtility.RetirementDecision.BLOCKED_UNTIL_LIFECYCLE_BARRIER;
```

The name is illustrative: no `fin_workspace` tables exist until separately approved W1D Flyway implementation. **A partition name matching the pattern proves nothing about retention eligibility**.

## Why destructive execution is not available yet

PostgreSQL 16 `DETACH PARTITION ... CONCURRENTLY` runs outside a transaction block and can leave an interrupted detach requiring `FINALIZE`. Catalog existence or name validation alone cannot prove tenant/workspace liveness. The privileged reaper must additionally enforce:
1. Persisted `workspace_partition_catalog` + complete cohort membership and source/generation identity.
2. DRAINING barrier preventing new references, pending runs, replay pins, legal hold and active SQL/JAVA leases.
3. Fencing, confirmation that physical SQL statements have stopped, and atomic evidence of all member retirement.
4. Separate dedicated DDL connection with `autocommit=true`, least-privileged maintenance role, bounded `lock_timeout`, no `CASCADE`, idempotent DETACH/FINALIZE/DROP reconciliation.
5. Independent schema/security S2 and real PostgreSQL Testcontainers proving that unrelated tenants' active partitions are never removed.

Once W1D/W4D are complete, extend via **a separate audited maintenance executor**. Do not turn this inspector into a public/tenant-accessible drop endpoint or bypass the lifecycle barrier.

## Validation

Test class creates only disposable PostgreSQL 16 Testcontainers schema/partition fixtures. It tests missing schema, two allowed range parents, actual inserted data surviving preview, DEFAULT/unmanaged names and forged SQL identifiers. No production tables are created by the utility. The implementation is **not** proof of accelerated cleanup; benchmark W6D against batch DELETE under real concurrency and WAL/OLTP load.

**Suggested targeted command:** `cd backend && mvn -Dtest=WorkspacePartitionMaintenanceUtilityTest test`. CI result and exact HEAD SHA must be recorded separately. `RELEASE_HOLD` remains.
