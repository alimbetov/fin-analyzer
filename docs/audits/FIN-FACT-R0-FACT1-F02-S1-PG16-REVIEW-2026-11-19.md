# FIN FACT — FACT-0 physical storage + FACT-1 F02 synthetic TX-Q technical review

**Date:** 2026-11-19  
**Repository / branch:** `alimbetov/fin-analyzer` / `feat/fin-form-library-standalone`  
**Review level:** technical S1; **NOT** independent financial S2.  
**Exact verified current code SHA:** `4ae44975941bf7639f180f0b82ad600c793b28e3`. **Final PostgreSQL 16 CI:** [run 44490380111](https://github.com/alimbetov/fin-analyzer/actions/runs/44490380111), 27 tests / 0 failed / 0 error / 0 skipped; backend/H2, React, PowerShell and inventory SUCCESS. Earlier 25-test evidence on `c2ba8178…` remains valid historical baseline.  
**Scope:** PostgreSQL 16 physical fact foundation and **test-sources-only** F02 Test-Qualified TX-Q demonstration.

## 1. Decision matrix

| Slice | Technical status | Proof / limitation |
|---|---|---|
| INGEST-0/1/2 | GREEN | 166 exact source forms, canonical content and ROWSET order SHA, no S2 |
| **FACT-0 schema 019/020/021** | **PG16 GREEN (physical foundation + hardening)** | 5 fin_calc structures, 3 fin_fact parent/control tables, monthly partition routing, source-immutability triggers and scoped plan FK |
| **FACT-0 maintenance** | **PG16 GREEN** | YearMonth partitions, advisory xact lock, controlled creation, dry-run, wrong-child failure, concurrent claims |
| **FACT-1 F02 synthetic qualification prototype** | **TEST_ONLY PG16 GREEN** | 3 typed facts + active local epoch + outbox in one TX; duplicate and rollback tests |
| FACT-1 main application trusted qualifier / production S2 | **HOLD** | Qualifier code is in `src/test` only; NO public endpoint, no deployed financial approval |
| CALC-R0 actual dispatcher/DAG/Worker/Result | **NOT IMPLEMENTED** | 019 has storage structures but no runtime execution or trusted publication |
| Full 166-form metric mapping / S2 | **HOLD** | Numeric source candidates are not financially approved measurements |

## 2. Actual repository changes

- `form-library/src/main/resources/db/changelog/019-fin-calc-r0-postgresql.sql` — 5 additive fin_calc tables: `calc_outbox_event`, `calc_plan`, `calc_job`, `calc_result`, `calc_current_result`. Outbox unique by producer+producer_event_id. Plan, result identity, scope and FKs; job lease/fencing storage ready, **worker runtime not yet implemented**.
- `form-library/src/main/resources/db/changelog/020-fin-fact-r0-pilot-postgresql.sql` — unpartitioned `fact_set` and `factset_control`, partitioned `fact_observation RANGE(period_start)` with `PRIMARY KEY(period_start,observation_id)` and `UNIQUE(period_start,factset_id,observation_key_sha256)`; exact source/version FKs; guarded same-tenant/scope current factset FK; append-only row triggers for fact_set/observation/calc_plan/calc_result. CHECK only permits `TEST_QUALIFIED` factset mode.
- `form-library/src/main/resources/db/changelog/021-fin-fact-scope-source-immutability-postgresql.sql` — **new additive** calc_plan composite factset+organization+scope FK; PostgreSQL source revision/row/evidence UPDATE/DELETE rejection; parent/source TRUNCATE statement triggers. Does not replace DB-owner/DDL role separation.
- Liquibase master includes **019, 020, 021** after 018; **001–018 unchanged**. A real PostgreSQL upgrade test now applies baseline 001–018, persists a historical `fin_intake` source revision, upgrades the same DB to 021 and verifies identical source values and historical Liquibase checksums. PostgreSQL-only changesets do not execute on H2.
- `calc/partition/FactMonthlyPartitionService.java`: fixed parent whitelist, PostgreSQL Date-based monthly partitions, max 60 months per call, no automatic DROP/DEFAULT, per-partition Spring-owned transaction, transaction-scoped advisory lock, catalog parent/key/bounds inspection, bounded retries and timeouts.
- `FactR0PostgresqlPartitionTest`: **9** real PG16 tests, including source immutability and cross-tenant calc plan negative writes.
- `FactR0PostgresqlUpgradeTest`: **1 real 018→021 PG16 upgrade test** preserving source revisions/checksums.
- `Ingest2PostgresqlIntegrityTest`: amended old assertion: source draft saves **do not** insert a factset, observations or calc_job; the new tables do exist.
- `Fact1F02TestOnlyQualificationTest`: 4 real PG16 methods, but synthetic local trusted qualifier is **only in test tree**, not bundled in executable Spring Boot JAR.
- CI `fin-form-studio-local-ci.yml` runs both new test classes along with existing INGEST-2, R16B and HTTP integration regressions.

## 3. Repeatable integration evidence

**Latest evidence:** [FIN Form Studio React/Backend run 44490380111](https://github.com/alimbetov/fin-analyzer/actions/runs/44490380111) on **code SHA** `4ae44975941bf7639f180f0b82ad600c793b28e3`:

| Unit/test group | Result |
|---|---|
| FactR0PostgresqlPartitionTest | **9 tests / 0 failures / 0 errors** |
| FactR0PostgresqlUpgradeTest | **1 test / 0 failures / 0 errors** — historical data and 001–018 checksums preserved |
| Fact1F02TestOnlyQualificationTest | 4 tests / 0 failures / 0 errors |
| Ingest2PostgresqlIntegrityTest | 4 tests / 0 failures / 0 errors |
| ReactLocalPostgresIntegrationTest | 5 tests / 0 failures / 0 errors |
| R16BPostgresqlContractTest | 4 tests / 0 failures / 0 errors |
| **Total real PostgreSQL 16** | **27 tests / 0 failures / 0 errors / 0 skipped; BUILD SUCCESS** |

Existing backend Spring Boot/H2 regression, React check, contract inventory and PowerShell syntax also GREEN in that workflow. Previous FACT-0 CI [8/8](https://github.com/alimbetov/fin-analyzer/actions/runs/44487416919) and FACT-1 [4/4](https://github.com/alimbetov/fin-analyzer/actions/runs/44488324841) remain prior evidence. No performance SLA, financial S2 or authorization guarantee is inferred from test counts.

## 4. Invariants proved

1. PostgreSQL declarative monthly partition routing for 2026-10/11, no DEFAULT partition. Missing historical month is a hard error until explicit maintenance; dry-run is side-effect free.
2. Partition manager is idempotent and rejects same-named non-partition/wrong-parent objects. Two concurrent maintainers produce one child and one EXISTS response. It never invokes DROP.
3. Composite observation PK / business UNIQUE, source revision/form version FKs, active pointer **and calc_plan** composite tenant+scope FKs, immutable fact/plan/result row-level SQL triggers; **fin_intake revision/row/evidence** UPDATE/DELETE guards and named-table TRUNCATE protection.
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
| **Source append-only / privileged DDL** | **UPDATE/DELETE/TRUNCATE guard now implemented and tested in 021**; still require least-privilege runtime vs migration/partition-owner roles, partition-child TRUNCATE/DDL restrictions, backup/restore qualification |
| **No qualified-result publication authority** | `fin_calc` CAS reader/epoch guard, fencing lease proof, cross-system revoke race tests |
| **No app/maintenance DB role split** | Least-privilege runtime role must not own `fin_fact`/partitions or hold TRUNCATE/DDL; `BEFORE UPDATE/DELETE` trigger is insufficient against owner DROP/TRUNCATE |
| **Missing full 166 form financial mapping** | Per-formVersion MEASURE/DIMENSION/unit/sign/period/ROWSET grain/evidence policy, independently reviewed |
| **No true multi-source membership FK** | Evaluate `fact_set_source_member` relational integrity for multi-form methods; JSONB-only header is a prototype |
| **No production restore drill** | **018→021 real PG16 upgrade rehearsal GREEN**, old `DATABASECHANGELOG` checksums and persisted source unchanged; still require real backup/WAL restore drill on representative data |
| **Partition performance unmeasured** | EXPLAIN ANALYZE BUFFERS/pruning, high-history p95/p99, DDL lock impact; 8 tests do not replace PFT-01..18 full qualification |

**No production banking output is authorized by this integration run.** Postgres tests use a synthetic issuer and synthetic money values only.

## 6. Recommended subsequent exact development slices

1. **FACT-0.H remaining operations**: 021 source SQL immutability and 018→021 upgrade rehearsal now GREEN. Next enforce least-privilege runtime vs schema owner/maintenance roles, schedule bounded history/lookahead provisioning and verify historical restore and partition pruning. Keep 019–021 checksums unchanged; new changesets only.
2. **FACT-1.A production-safe contract**: typed reviewed F02 mapping, immutable FactSet digest and source member binding, restricted `TrustedQualificationGateway` with independent issuer/epoch/scope, fail-closed default; test-only fixture stays in test tree.
3. **FACT-1.B transactional committer**: validated source completeness, unique idempotency, local test control lock, 3 fact rows and trusted outbox in one SQL commit; PostgreSQL negative concurrent tests.
4. **CALC-R0 next**: consume trusted outbox, pinned DAG, bounded workers, lease/fence and immutable result; then enable guarded CURRENT. Financial production remains HOLD until real S2/IAM.

**Release verdict:** FACT-0 storage foundation = **PG16 integration GREEN**. FACT-1 = **synthetic local contract/transaction proof GREEN**, **production feature HOLD**. There is no Redis/Kafka/Flink/ClickHouse work in these slices.
