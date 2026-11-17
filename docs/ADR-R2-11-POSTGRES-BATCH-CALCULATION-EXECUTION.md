# ADR-R2-11 — Governed PostgreSQL Batch Calculation, Not Executable SQL Stored in Tables

**Decision:** RECOMMENDED DESIGN / ARCHITECTURE ACCEPTANCE PENDING / NOT IMPLEMENTED
**Date:** 2026-11-17 · **Project:** FIN Analyzer · **Branch:** `feat/fin-r0-r1a`
**Parent authority:** [FIN-HTAP-CONSOLIDATED-TZ.md](FIN-HTAP-CONSOLIDATED-TZ.md), [FIN-HTAP-F166](FIN-HTAP-FORM-166-INCREMENTAL-RECALCULATION-AND-PARTITION-LIFECYCLE-TZ.md), [ADR-R2-05](ADR-R2-05-METHOD-MODULE-DUAL-ENGINE-IMMUTABLE-RESULTS.md).
**Reviewed baseline:** `345e516d369dccb2e5036edeb47e89c74dd0f135`; existing `MethodModule`/registry, `Fin01RevenueMomSqlModule`, `SingleExecutorScheduler`, immutable FactSet/outbox, `fin_workspace` design, catalog 166 classification.
**Goal:** make set-based calculations fast and reliable without a second scheduler, unrestricted SQL execution, or a single long-running transaction that monopolizes PostgreSQL.

## 1. Explicit architectural decision

**YES** to PostgreSQL-resident **bounded calculation routines** and batch `INSERT ... SELECT` / window aggregation / CTE jobs when measured and qualified. **NO** to a production **database table containing arbitrary executable SQL text** that a generic executor reads and runs using `EXECUTE`, JDBC Statement or `DO` with broad privileges.

The **Java orchestrator** remains the sole authority for: change event/outbox intake, exact financial scope/SourceKey, canonical ImpactSet/DAG, user and server permissions, independent ExecutionPermit, tenant and global admission budgets, task attempts/fences, version resolution, stage/routine execution, bounded retries, timeouts, immutable result receipts, generation CAS promotion, and durable recovery.

**PostgreSQL** owns: trusted set-based reads, qualification of exact sealed input, bulk grouping/windows/aggregates, versioned allowlisted SQL functions/procedures, writing attempt-local derived stage rows inside short bounded transactions, computing input/output count/hash checks and returning status/counters. PostgreSQL never starts a new generic scheduler, discovers unknown client SQL, grants ACTUAL or promotes a result without the Java-controlled permit/fence transaction.

High-level:
```text
new trusted published source / form update intent
 -> one Java planner / canonical dependency DAG
 -> PREPARE admission / sealed workspace / exact source snapshot
 -> versioned SQL method dispatch (known method+version -> known routine)
 -> 1 bounded SQL batch in 1 short transaction
 -> stage row counts + deterministic checksums + current fence validation
 -> next admitted batch or Java method
 -> final validation -> 1 fenced immutable receipt transaction
 -> generation CAS -> deferred retired cohort GC
```

The existing `Fin01RevenueMomSqlModule` is **SYNTHETIC, read-only and standalone**. A new database routine is a possible optimization *within that same SQL MethodModule*, not an alternative production authority.

## 2. Registry: metadata in DB, executable logic in reviewed artifacts

Use a **versioned immutable routine manifest** whose registered methods MUST also exist in code/CI. Suggested:
```text
fin_calc.sql_routine_manifest(
  routine_manifest_id uuid,
  method_code, variant_version, routine_version,
  engine = SQL,
  routine_schema = 'fin_calc',
  routine_signature = 'fin_calc.rev_net_mom_batch_v1(uuid,uuid,integer)',
  source_artifact_sha256 char(64),
  parameter_contract_sha256 char(64),
  input_schema_version, output_schema_version,
  calculation_policy_hash, period_calendar_version,
  supported_unit_currency_policy,
  qualification_state, approval_reference,
  activation_epoch, release_flag,
  owner_role, created_at, ... immutable ...
)
```

Only a **schema-qualified allowlisted routine signature** from server-approved registry may be dispatched. **Never concatenate an SQL statement stored in a row**; never accept a free-form table/routine name or body from HTTP, form data, JSON or tenant metadata. Bind typed values (`workspace_id, attempt_id, fence, batch boundary`) with JDBC `PreparedStatement` or static SQL per registered method; reject extra parameters. SQL artifacts live in Git and additive Flyway migrations, are independently reviewed, tested and deployed; manifest stores canonical artifact SHA-256 and separately checks actual DB routine signature, owner/ACL and expected deployment version. Do not assume `pg_get_functiondef` or raw SQL formatting is byte-stable across upgrades. Existing v1 manifest/plan results never reinterpret a changed function body.

**Immutable versioning:** favor `rev_net_mom_batch_v1(...)` and a new `_v2` for changed calculation semantics; do not change a routine referenced by historical immutable results. A signature/hash/privilege mismatch blocks dispatch `SQL_ROUTINE_VERSION_DRIFT`; never fall back to “latest method”. Keep approved business method variants separate from the 166 forms: do not create a SQL function per form unless one specifically qualified rule truly requires it.

**Optional future audited SQL source vault?** Not a first implementation requirement. If a controlled authoring workflow later requires storing SQL text in DB, it may keep text *as non-executable audited artifact*, not runtime code. A separate admin-reviewed deployment service could validate, migrate and register a new function version only via normal change-control, migration/security review and CI. Raw SQL from tenant/editor must never be executed.

## 3. SQL batch execution contract

The **batch unit** is a bounded, admitted slice of **a sealed and independently source-verified FinancialSeriesKey / aggregation stage**, not “all 166 forms” and not “all tenants”.
- Parameters: `job_id`, `attempt_id`, `workspace_id`, `generation`, DB-issued `fencing_token`, `batch_cursor` or `batch_from/to`, `batch_limit`, expected `source_hash`/`input_hash`, `method_variant/version` from server plan. Derive exact tenant, form, PeriodType, Period, Currency, dataSpace, basis, scope and calendar from the sealed server-owned plan, **not** separately trusted client text.
- SQL must include exact tenant/source/series predicates, correct period ordinal and currency, and for windows ensure **halo history** (prior periods needed for the first batch output). Segmenting rows into arbitrary chunks must never lose lag/SMA context at chunk boundaries. Prefer **chunk by complete series or output-period range with explicitly computed read halo**; write only the intended target output anchors.
- Stage writes carry `(tenant, plan_key, workspace_id, generation, stage_code, attempt_id/fence, batch_id, output_key)` and an idempotency UNIQUE key; identical retry does not duplicate rows, stale attempt cannot promote stage or result. Intermediate writes are not a financial result, report or release approval.
- Each batch: verify SEALED input and current attempt/fence at entry, calculate via set-based SQL, validate coverage/status/row count, write only attempt-local staging in **one bounded transaction**, recheck fence before committing stage. Race-safe final seal/result receipt checks the fence in the **same transaction as final commit**. A worker can compute after its lease expires but must never publish a stale result; cancellation/quarantine preserves physical SQL capacity until termination is confirmed.
- Batch-size limit is chosen by **measured row/byte/time budgets**, not a guessed universal count; max rows, temp bytes, memory, WAL, lock wait, statement timeout, DB connections, retries and backlog are subject to W2 admission. If an operation is one giant indivisible query, it must still have bounded timeout and resource class. Avoid serial PL/pgSQL loops over each financial cell if a set-based grouped query is possible.
- Default execution uses a standard **invoker-security** role with the minimum `SELECT` on approved read views and `INSERT` on controlled attempt staging, no DDL/owner/`BYPASSRLS`, plus explicit scoped composite FKs and rechecked tenant semantics. Runtime scripts cannot issue `CREATE/DROP/ALTER`, `COPY PROGRAM`, extension installation, unqualified dynamic function calls, arbitrary `search_path`, or mutate `fin_fact`/durable receipt history.
- Prefer direct static SQL or a reviewed `SECURITY INVOKER` routine. A narrow `SECURITY DEFINER` boundary is an independently reviewed exception, with immutable qualified search_path, strictly scoped permissions/inputs and proof of no RLS bypass and no user-created object shadowing. Never assume a definer procedure can use `COMMIT` internally.

**Transaction choice:** A `FUNCTION` called in `SELECT` / insert-select runs in the caller transaction and cannot issue transaction control; a `PROCEDURE` via `CALL` can use transaction control only when the invocation context satisfies PostgreSQL restrictions. **Recommended:** Java invokes a bounded unit of work per transaction with Spring transaction management. SQL routines must **not** internally `COMMIT` or `ROLLBACK`. This gives one end-to-end lease/attempt/barrier transaction contract without a giant monolithic multi-batch `CALL`.

## 4. Why not “a table containing 60 executable SQL scripts”?

| Risk | Consequence | Better rule |
|---|---|---|
| Arbitrary dynamic SQL text in mutable DB rows | SQL injection, accidental DDL/DML, privilege abuse, wrong tenant joins, hard-to-review semantics | Immutable reviewed SQL artifact/function in Flyway; DB table stores manifest and checksum only |
| 60 mutable scripts changed independently of Java releases | historic results cannot prove which SQL ran; parity/rollbacks break | per-variant version + SHA-256 + golden + manifest and pinned ResultKey |
| one `CALL` executes all SQL until finish | long transactions/locks, WAL/temp growth, OLTP starvation, impossible fine-grained retries | bounded stage-per-batch transactions, Java controls admission and cancellation |
| same routine used across tenants without source guard | cross-tenant/form/currency contamination | sealed typed context, explicit server-owned tenant scope, SQL predicates and scoped composite FKs |
| ad-hoc `CREATE/DROP PARTITION` from scripts | wrong-tenants dropped, catalog lock pressure, interrupted detach with no recovery | dedicated operator/DDL reaper and cohort ledger, never inside SQL method |
| multiple internal job schedulers | races with immutable outbox and stale generations | exactly one Java scheduler and one source-publication outbox |
| DB-only orchestration hides DAG/metrics | difficult to enforce method permit, quotas, end-user status and de-dup | Java owns state; SQL returns typed batch stats and reason codes |

## 5. Candidate types of PostgreSQL work and exclusions

**Good SQL candidates:** exact trusted fact selection; filtered aggregations and rollups by qualified `Tenant+Form+PeriodType+Period+Currency`; rolling `LAG`/SMA/variance with calendar completeness; category/pivot/segment aggregates; set-based price/volume/margin components; Pareto ranking from preserved SKU populations; bounded aggregate cache materialization; row counts/content hashes.

**Java or separately qualified algorithms:** interactive scenario/state machines, graph and complex orchestration, deeply iterative financial models (IRR/NPV or advanced forecasts as method-reviewed), external FX source integration, rule approval, statistical inference requiring independently verified numeric/precision parity. Do not assume every formula is a good SQL stored procedure. The actual engine per variant is pinned, qualified and bench tested.

**Important window trap:** Do not split a three-month SMA by batches of two months without fetching the two preceding qualified periods as read-only halo input. The output period list is bounded; the qualifying input window is larger. For a YoY downstream invalidation, one changed month p can affect its next-year counterpart. PeriodType, Currency and exact trusted ComparisonSet must agree throughout.

## 6. PostgreSQL RED acceptance (W3.SQLB and W6.SQLB)

| ID | Scenario | Expected |
|---|---|---|
| SQLB-01 | Repository/Flyway registered `routine_v1`, exact manifest digest and parameter contract | Approved routine resolves by immutable variant, executes on same SYNTHETIC published source, deterministic envelope |
| SQLB-02 | Manifest points to unknown signature / modified body / wrong owner ACL | BLOCKED_VERSION_DRIFT; no execution |
| SQLB-03 | Tenant A supplies B workspace ID, or input contains two currencies/forms | No unauthorized reads/writes or mixed-calculation result |
| SQLB-04 | Window N=3 split into 1-2-period batches with start-boundary halo | Equal to full-series Java reference; no missing/duplicate anchors or gap concealment |
| SQLB-05 | Interrupt transaction midway through batch staging | Either whole batch committed under correct fence or rolled back; retry idempotent |
| SQLB-06 | Old worker lease expires while query still runs, new attempt retries | Old cannot promote; SQL admission capacity retained/quarantined until physical stop |
| SQLB-07 | Change routine version/SQL checksum but not form data | New PlanKey/ResultKey and golden, historical receipt unchanged |
| SQLB-08 | Force `statement_timeout`, `lock_timeout`, temp-file limit, connection pressure | Heavy work cancels/sheds; OLTP reserve and controlled retries |
| SQLB-09 | Attempt SQL stored as mutable table `script_body` or user-provided query in plan | Rejected: no generic `EXECUTE(sql_text)` endpoint/dispatcher |
| SQLB-10 | Database worker role attempts DDL, schema owner privilege escalation or `search_path` hijack | Denied, no source/partition tampering |
| SQLB-11 | Large full-batch vs micro-batches on realistic dataset | Compare end-to-end total time (including staging/retries), p95/p99, WAL, temp, `pg_stat_statements`, OLTP latency |
| SQLB-12 | `CALL` with own COMMIT inside Spring transaction, or definer/SET-bound procedure attempts COMMIT | Tested rejection per PostgreSQL semantics; chosen routines have no internal transaction control |
| SQLB-13 | Crash between stage commit and final receipt, or version drift during rollout | Deterministic recovery/checkpoint; new function version activated only after all participating app workers compatible |
| SQLB-14 | Qualified form update fan-out to multiple SQL-method DAG nodes and multiple tenants | One Java dispatcher with global+tenant quotas; no new DB-level scheduler or cross-tenant result |

**Gate:** real PostgreSQL 16 Testcontainers RED→GREEN, same SHA backend regression, SQL/Java decimal/status/lineage parity, independent security/database review; no ACTUAL enabled. Performance must include p95/p99 and DB pressure, not only count of SQL script invocations.

## 7. Delivery sequence and recommendation

- **W0.SQLB:** register ADR, finalize manifest/ACL/transaction contract and benchmark fixture. Keep execution OFF.
- **W1.SQLB:** additive immutable SQL routine registry and version-check tests (if needed), without introducing a SQL text execution table.
- **W2.SQLB:** implement one batch task under the **same** tenant capacity/fence control; no internal procedure COMMIT.
- **W3.SQLB:** run **one qualified SYNTHETIC pilot** (FIN-01 / REV_NET MoM and optionally monthly aggregate materialization) with a server-selected PostgreSQL `FUNCTION`/static SQL and compare its result to existing Java golden and current direct SQL pilot. Explicitly measure whether a routine is materially better than existing `JdbcTemplate` prepared SQL.
- **W4.SQLB:** per-batch checkpoint, recovery, immutable result commit, source generation CAS and stale worker rejection.
- **W6.SQLB:** compare direct parameterized SQL, vetted `FUNCTION`, vetted `PROCEDURE` without internal commits, and optional batch staging; adopt only if faster/correct at realistic concurrency and safe to OLTP.

The first implementation should **not** be a database stored-procedure framework with arbitrary scripts; it should be **one audited SQL batch worker with exactly one method variant and true PostgreSQL evidence**. No W1/Flyway implementation is claimed by this ADR.

## 8. Official PostgreSQL 16 references

- https://www.postgresql.org/docs/16/sql-createprocedure.html — CALL, procedure security and transaction restrictions
- https://www.postgresql.org/docs/16/xproc.html — function/procedure semantics
- https://www.postgresql.org/docs/16/sql-createfunction.html — SECURITY DEFINER and search_path
- https://www.postgresql.org/docs/16/plpgsql-statements.html — dynamic SQL EXECUTE and identifier/value binding
- https://www.postgresql.org/docs/16/pgstatstatements.html — planning, execution and WAL metrics
