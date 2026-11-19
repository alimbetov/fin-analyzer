# FIN FACT-0.H — PostgreSQL Roles, Partition Operations, Pruning and Recovery
**Code-level/ops ТЗ · v1.0 · 2026-11-19 · PRE-IMPLEMENTATION**

**Baseline:** migrations `019-fin-calc`, `020-fin-fact`, `021-fin-fact-scope-source-immutability`; `FactMonthlyPartitionService`.
**Status:** fact storage and 27 prior PostgreSQL tests GREEN; **DB privilege separation / WAL restore / partition pruning performance NOT QUALIFIED**.
**Must not change:** Liquibase 001–021, immutable historical revisions, fact table data, existing F02 smoke. No TRUNCATE/DROP of production historical partitions.

## 1. P0 role boundary: owner is not an application role

Current app Testcontainers and standalone service typically connect with the same datasource used for Liquibase. Even with `BEFORE UPDATE/DELETE/TRUNCATE` triggers, a privileged table owner can `ALTER/DROP/DETACH`, bypass integrity controls and manage child partitions. The present success of PostgreSQL fixture tests uses a privileged test DB role and does **not** prove a hardened runtime.

Target roles and duties:

| Role (logical) | Needs | Must NOT have |
|---|---|---|
| `fin_schema_owner` (NOLOGIN) | Own `fin_intake`, `fin_fact`, `fin_calc` objects; administrative DDL via deployment pipeline | Used by web app or worker |
| `fin_migrator` | Deployment-only trusted migrations, controlled schema owner access | A connection in normal application pool |
| `fin_runtime` | Read immutable source/facts; carefully scoped INSERT immutable facts/receipt/outbox, UPDATE pointer/job **only after corresponding trusted Java gate** | Table ownership, CREATE/ALTER/DROP/DETACH/TRUNCATE, BYPASSRLS, SUPERUSER, arbitrary UPDATE/DELETE on immutable tables |
| `fin_partition_maint` | Provision preapproved monthly partitions via a separate maintenance process or allowlisted audited `SECURITY DEFINER` function | Broad schema owner privilege in long-running web service; arbitrary parent names/retention DROP |
| `fin_readonly` | Read allowed catalog/facts/qualified result through scoped API | Fact writes, DDL, ability to select other tenants outside authorized API |

**Mandatory default:** no self-created SQL roles with hardcoded passwords in migrations or Java code; connection credentials from secret manager. A PostgreSQL role being a table owner is incompatible with a strong immutable runtime boundary.

**Partition ownership caveat:** `CREATE TABLE ... PARTITION OF` requires appropriate ownership of the parent. A role given only `CREATE` on the schema is not sufficient. Thus either:
- **Recommended initial approach:** deployment/operations migration preprovisions historical/current/lookahead partitions through a separate authorized job and credentials. Normal `FactMonthlyPartitionService` in the application is **read-only inspect**; DDL interface is not wired to runtime.
- Future approach: one allowlisted `SECURITY DEFINER` function with fixed `search_path`, fully validated `YearMonth` input and fixed parent identity, controlled EXECUTE grant only to maintenance role; no raw identifiers. Independent SQL review and tests necessary.

## 2. Idempotent privilege and DDL plan

**Proposed additive 022+ migration** after review, no edits to 001–021:
1. Inventory present object ownership/privileges, child partition ACL, default privileges for future partitions and `PUBLIC` grants; abort on unexpected unsafe topology.
2. Prepare roles out-of-band, transfer ownership to `fin_schema_owner` only in a planned maintenance window with migration privileges and rollback procedure; do not silently reassign production ownership.
3. Explicit `REVOKE` of `CREATE` on schemas, `TRUNCATE` on fact/source parents/children, `ALTER/DELETE/UPDATE` where immutable; ensure future child tables inherit/reapply explicit ACL via maintenance job.
4. Runtime grants: `SELECT` on verified source and eligible fact tables; `INSERT` fact headers/observations/outbox only through trusted internal writer; scoped `UPDATE` on `factset_control`, job lease and CURRENT reserved to authority/worker adapter roles. Column-level privileges where practical. A single runtime SQL role with wide DML is a weaker interim shape and must be documented.
5. Ensure schema functions used in security context do not grant unsafe `EXECUTE` to `PUBLIC`; `SECURITY DEFINER SET search_path` includes trusted schemas only, qualified names, no unvalidated dynamic SQL.
6. Deny `CREATE EXTENSION`, `SET ROLE` to privileged roles, `ALTER TABLE ... DISABLE TRIGGER` and unauthorized partition detach/drop from runtime.
7. Add migration rehearsal 018→022+, checksum and restored-DB tests. No deleting archived financial partitions as an automated “cleanup”.

## 3. Controlled partition provisioning

- Explicit `LocalDate/YearMonth` based on **business period_start**, not ingestion instant or Java system default timezone.
- One bounded provisioning command covering configured historical minimum through current month + 2 months of lookahead (defaults proposed, not current settings). Enforce max 60 months per operation, independent short transactions with advisory lock and fixed whitelist parent `fin_fact.fact_observation`.
- Before any FACT-1 TX-Q call `inspectRequiredCoverage(min,max)` and reject if any month is missing, malformed or wrong parent/bounds; never run implicit DDL from inside a qualification, intake-save or worker transaction.
- Child naming `fact_observation_YYYY_MM` and exact `FOR VALUES FROM (firstDay) TO (nextFirstDay)`; no DEFAULT child; check `pg_inherits`, `pg_partitioned_table`, `pg_get_expr(relpartbound)`.
- Maintenance retries only bounded SQLSTATE 40001/40P01/55P03 and advisory lock busy; timeouts explicit; do not treat unauthorized DDL error as transient.
- Metrics: `fin_fact_partition_available{month}`, `provision_failures_total`, `maintenance_lock_wait_ms`, `missing_partition_months`, `retries_total`, `ddl_duration_ms`. Labels bounded; no tenant/project UUID or document names in metrics.

## 4. Query and operational acceptance

**Qualified fact reader** always supplies a business period range. Verify EXPLAIN:
```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT period_start, observation_id, metric_code, numeric_value
FROM fin_fact.fact_observation
WHERE factset_id = :pinned_factset_id
  AND period_start >= :min_inclusive
  AND period_start <  :max_exclusive
ORDER BY period_start, observation_id;
```

Acceptance: explain `Subplans Removed` / `Append` child count indicates nonrequired partitions pruned; explicit SQL plan/log with fixture sizes and PostgreSQL version. **Do not** claim target p95/p99 improvement until measured. Include nonpruning negative control query by `factset_id` alone. Verify 36-month fixture, multi-tenant consistency, index usage, missing-period failure and no implicit all-partitions scan.

**Backup/restore:** rehearse `pg_dump/pg_restore` or PITR/WAL on a representative disposable database at same schema version, compare source/fact SHA and row counts, exact pointers, Liquibase history and partition bounds before/after; report RPO/RTO as observed, not invented. A migration upgrade 018→021 does NOT substitute for restore testing.

## 5. Mandatory PostgreSQL 16 negative tests (OPS-01..OPS-16)

| ID | Contract |
|---|---|
| OPS-01 | Runtime role is NOT owner/superuser/CREATEROLE/BYPASSRLS |
| OPS-02 | Runtime INSERT permitted only for explicitly granted paths; no ALTER/CREATE/DROP schema |
| OPS-03 | Runtime UPDATE/DELETE/TRUNCATE immutable `fin_intake` revisions/rows/evidence rejected |
| OPS-04 | Runtime UPDATE/DELETE/TRUNCATE `fact_set`, `fact_observation` parent and child rejected |
| OPS-05 | Runtime DETACH/DROP PARTITION and DISABLE TRIGGER denied |
| OPS-06 | App role cannot invoke privileged maintenance/provision DDL |
| OPS-07 | Maintenance role cannot create arbitrary schema/table/parent or drop historical partitions |
| OPS-08 | Newly created month inherits proper ownership, indexes, ACL and immutable trigger coverage |
| OPS-09 | Wrong-parent/same-named fake partition rejected |
| OPS-10 | Two independent concurrent provisioners create one correct month; no deadlock |
| OPS-11 | 60-month bound, dry-run and missing-month health behavior |
| OPS-12 | F02 TX-Q fails cleanly on missing month; no header/event remains |
| OPS-13 | EXPLAIN partition pruning on 36-month fixture and negative nonpruning control |
| OPS-14 | Existing 018→new migration upgrade preserves old checksums and source SHA |
| OPS-15 | Restore snapshot/PITR preserves fact rows, pointers, ledger and historical revisions |
| OPS-16 | Startup fails closed if prod runtime role has owner/DDL grants; smoke uses explicit test role |

**DoD FACT-0.H (security):** OPS-01..12 + OPS-16 PASS at single SHA, CI must use distinct PostgreSQL roles; first production-safe committer cannot deploy before this. **Operational qualification:** OPS-13..15 separately GREEN; no false claim of backup/pruning readiness.

Keep `scripts/smoke-fin-fact.sh --quick` as a fast isolated technical gate; it uses privileged Testcontainers fixtures and is NOT a substitute for role/restore tests.
