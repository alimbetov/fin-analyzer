# FIN FACT — FACT-0 physical storage + FACT-1 F02 synthetic TX-Q technical review

**Date:** 2026-11-19  
**Repository / branch:** `alimbetov/fin-analyzer` / `feat/fin-form-library-standalone`  
**Review level:** technical S1; **NOT** independent financial S2.  
**Exact FACT-0/FACT-1 integration CI source SHA:** `c2ba8178f369e222e8fb511426922f4963c1dbc0`.  
**Scope:** PostgreSQL 16 physical fact foundation and **test-sources-only** F02 Test-Qualified TX-Q demonstration.

## 1. Decision matrix

| Slice | Technical status | Proof / limitation |
|---|---|---|
| INGEST-0/1/2 | GREEN | 166 exact source forms, canonical content and ROWSET order SHA, no S2 |
| **FACT-0 schema 019/020** | **PG16 GREEN (physical foundation)** | 5 fin_calc structures, 3 fin_fact parent/control tables, PG partition routing/constraints |
| **FACT-0 maintenance** | **PG16 GREEN** | YearMonth partitions, advisory xact lock, controlled creation, dry-run, wrong-child failure, concurrent claims |
| **FACT-1 F02 synthetic qualification prototype** | **TEST_ONLY PG16 GREEN** | 3 typed facts + active local epoch + outbox in one TX; duplicate and rollback tests |
| FACT-1 main application trusted qualifier / production S2 | **HOLD** | Qualifier code is in `src/test` only; NO public endpoint, no deployed financial approval |
| CALC-R0 actual dispatcher/DAG/Worker/Result | **NOT IMPLEMENTED** | 019 has storage structures but no runtime execution or trusted publication |
| Full 166-form metric mapping / S2 | **HOLD** | Numeric source candidates are not financially approved measurements |

## 2. Actual repository changes

- `form-library/src/main/resources/db/changelog/019-fin-calc-r0-postgresql.sql` — 5 additive fin_calc tables: `calc_outbox_event`, `calc_plan`, `calc_job`, `calc_result`, `calc_current_result`. Outbox unique by producer+producer_event_id. Plan, result identity, scope and FKs; job lease/fencing storage ready, **worker runtime not yet implemented**.
- `form-library/src/main/resources/db/changelog/020-fin-fact-r0-pilot-postgresql.sql` — unpartitioned `fact_set` and `factset_control`, partitioned `fact_observation RANGE(period_start)` with `PRIMARY KEY(period_start,observation_id)` and `UNIQUE(period_start,factset_id,observation_key_sha256)`; exact source and version FKs; guarded same-tenant/scope current factset FK; append-only row triggers for fact_set/observation/calc_plan/calc_result. CHECK only permits `TEST_QUALIFIED` factset mode.
- Liquibase master includes 019 and 020 after 018; **001–018 unchanged**. PostgreSQL-only changesets do not execute on H2.
- `calc/partition/FactMonthlyPartitionService.java`: fixed parent whitelist, PostgreSQL Date-based monthly partitions, max 60 months per call, no automatic DROP/DEFAULT, per-partition Spring-owned transaction, transaction-scoped advisory lock, catalog parent/key/bounds inspection, bounded retries and timeouts.
- `FactR0PostgresqlPartitionTest`: 8 real PG16 tests.
- `Ingest2PostgresqlIntegrityTest`: amended old assertion: source draft saves **do not** insert a factset, observations or calc_job; the new tables do exist.
- `Fact1F02TestOnlyQualificationTest`: 4 real PG16 methods, but synthetic local trusted qualifier is **only in test tree**, not bundled in executable Spring Boot JAR.
- CI `fin-form-studio-local-ci.yml` runs both new test classes along with existing INGEST-2, R16B and HTTP integration regressions.

## 3. Repeatable integration evidence

All from [FIN Form Studio React/Backend run](https://github.com/alimbetov/fin-analyzer/actions/runs/44488324841) on **exact source SHA** `c2ba8178f369e222e8fb511426922f4963c1dbc0`:

| Unit/test group | Result |
|---|---|
| FactR0PostgresqlPartitionTest | 8 tests / 0 failures / 0 errors |
| Fact1F02TestOnlyQualificationTest | 4 tests / 0 failures / 0 errors |
| Ingest2PostgresqlIntegrityTest | 4 tests / 0 failures / 0 errors |
| ReactLocalPostgresIntegrationTest | 5 tests / 0 failures / 0 errors |
| R16BPostgresqlContractTest | 4 tests / 0 failures / 0 errors |
| **Total real PostgreSQL 16** | **25 tests / 0 failures / 0 errors / 0 skipped; BUILD SUCCESS** |

Existing backend Spring Boot/H2 regression, React check, contract inventory and PowerShell syntax also GREEN in that workflow. Previous FACT-0 CI [8/8 positive](https://github.com/alimbetov/fin-analyzer/actions/runs/44487416919) at earlier storage SHA. No performance SLA or financial S2 is inferred from test counts.

## 4. Invariants proved

1. PostgreSQL declarative monthly partition routing for 2026-10/11, no DEFAULT partition. Missing historical month is a hard error until explicit maintenance; dry-run is side-effect free.
2. Partition manager is idempotent and rejects same-named non-partition/wrong-parent objects. Two concurrent maintainers produce one child and one EXISTS response. It never invokes DROP.
3. Composite observation PK / business UNIQUE, matching source revision/form version FKs, active pointer composite tenant+scope FK, immutable fact/plan/result row-level SQL triggers.
4. A test-inserted fact header, observation and trusted outbox in one local TX **all roll back** on forced abort.
5. Ordinary `fin_intake` draft save never becomes qualified fact or job. No production implicit approval.
6. F02 test-only qualified revision: exactly three `MONEY` source fields, full scope, reverified content SHA, test-only mapping digest, 3 immutable observations, exact source revision lineage, one outbox event, active epoch=1. Replayed same source revision returns identical historical FactSet and no duplicate observations.
7. Corrected draft alone does not replace current qualified factset. A subsequent synthetic TEST_ONLY qualification produces a second immutable FactSet and epoch=2; original stays historical. Replaying an older qualified revision does NOT rewind active pointer.
8. Revocation in test-local `factset_control` removes active reference and increments epoch while preserving all three old immutable rows.
9. No calc_job is created by FACT-1 test-only event: dispatcher, multiworker DAG, immutable result publication remain later CALC-R0 work.

## 5. Remaining P0 / HOLD

| Risk | Required next implementation |
|---|---|
| **No independently trusted financial S2** | Immutable signed exact decision / issuer identity / external revocation protocol; zero public self-approval |
| **No production FactSetCommitter** | Main-source API restricted to independently verified decision, EXACT source re-verification in same trust protocol and fail-closed storage TX-Q |
| **Source DB append-only not proven** | PostgreSQL privileges/UPDATE/DELETE/TRUNCATE/DDL guard for `fin_intake`, versioned evidence and stable source identity |
| **No qualified-result publication authority** | `fin_calc` CAS reader/epoch guard, fencing lease proof, cross-system revoke race tests |
| **No app/maintenance DB role split** | Least-privilege runtime role must not own `fin_fact`/partitions or hold TRUNCATE/DDL; `BEFORE UPDATE/DELETE` trigger is insufficient against owner DROP/TRUNCATE |
| **Missing full 166 form financial mapping** | Per-formVersion MEASURE/DIMENSION/unit/sign/period/ROWSET grain/evidence policy, independently reviewed |
| **No true multi-source membership FK** | Evaluate `fact_set_source_member` relational integrity for multi-form methods; JSONB-only header is a prototype |
| **No upgrade/restore evidence** | Separate 018→020 persistent DB migration rehearsal and immutable history restore, not just clean Testcontainers boot |
| **Partition performance unmeasured** | EXPLAIN ANALYZE BUFFERS/pruning, high-history p95/p99, DDL lock impact; 8 tests do not replace PFT-01..18 full qualification |

**No production banking output is authorized by this integration run.** Postgres tests use a synthetic issuer and synthetic money values only.

## 6. Recommended subsequent exact development slices

1. **FACT-0.H hardening**: source SQL immutability + permission split, bounded partition history/lookahead operational provision, migration upgrade rehearsal. Keep 019/020 checksums unchanged; new changesets only.
2. **FACT-1.A production-safe contract**: typed reviewed F02 mapping, immutable FactSet digest and source member binding, restricted `TrustedQualificationGateway` with independent issuer/epoch/scope, fail-closed default; test-only fixture stays in test tree.
3. **FACT-1.B transactional committer**: validated source completeness, unique idempotency, local test control lock, 3 fact rows and trusted outbox in one SQL commit; PostgreSQL negative concurrent tests.
4. **CALC-R0 next**: consume trusted outbox, pinned DAG, bounded workers, lease/fence and immutable result; then enable guarded CURRENT. Financial production remains HOLD until real S2/IAM.

**Release verdict:** FACT-0 storage foundation = **PG16 integration GREEN**. FACT-1 = **synthetic local contract/transaction proof GREEN**, **production feature HOLD**. There is no Redis/Kafka/Flink/ClickHouse work in these slices.
