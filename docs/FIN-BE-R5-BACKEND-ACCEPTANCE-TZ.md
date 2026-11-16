# FIN-BE-R5 — ТЗ: интеграционная и регрессионная квалификация backend

**Приоритет:** P0, выполняется с начала R1. **Статус:** SPECIFIED / NEW E2E NOT YET EXECUTED. **Ветка:** `feat/fin-r0-r1a`.  
**Цель:** на реальной PostgreSQL доказать путь source→fact→orchestration→diagnostic, воспроизводимость методик, корректные отказы, восстановление после ошибок и изоляцию optional ML от CORE.

## 1. Состав proof layers

| Layer | Инструмент | Что доказывает |
|---|---|---|
| L0 Pure | JUnit 5/BigDecimal | Arithmetic, stock/flow/ratio, edge/negative, independent goldens |
| L1 XLSX | Apache POI+JUnit | Parse identity/period, malicious workbook, reupload, original hash |
| L2 Persistence | PostgreSQL 16 Testcontainers | Migration clean+upgrade, DB constraints, immutable facts, CAS |
| L3 Orchestrator | JUnit + concurrent PostgreSQL workers | Outbox duplicate, lease/fencing, retry, failure DAG |
| L4 Diagnoses | Java services+PostgreSQL | Published source → method → trace → immutable CORE |
| L5 ML | Synthetic and qualifying time-series | Point-in-time backtest, baseline comparison, ML failure isolation |
| L6 Recovery | Restart/replay/old revision | Old snapshots unchanged; restatement produces new revision |

**Ни mocks PostgreSQL, ни H2, ни frontend screenshots не заменяют proof для L2–L6.** Existing frontend CI не развивать, но не допускать regressions. Security/Keycloak development запрещена, существующие запреты сохраняются.

## 2. Сквозной acceptance набор

| ID | Реальный Java/PostgreSQL сценарий | Приёмка |
|---|---|---|
| BE-R5-E2E-01 | Issue real supported XLSX → fill → submit → independent review → published FactSet | All exact fields/period/provenance and immutable hash |
| BE-R5-E2E-02 | Foreign month/currency/scale template | Reject before persistence, no run |
| BE-R5-E2E-03 | F02 summary=1000; details 600+400 | One canonical revenue=1000 |
| BE-R5-E2E-04 | Published FactSet → outbox → `tick()` → applicable FIN method → CORE | Correct numeric, qualified/provisional statuses, source trace |
| BE-R5-E2E-05 | Current liabilities 0/missing | NOT_COMPUTABLE, independent tasks continue |
| BE-R5-E2E-06 | Two consumers + repeated outbox + worker crash/restart | One logical result, stale fencing blocked |
| BE-R5-E2E-07 | Reupload/restatement after original report | New immutable fact/result, original unchanged |
| BE-R5-E2E-08 | Unapproved method/unsupported form | No qualified ACTUAL promotion, reason visible |
| BE-R5-E2E-09 | 13 synthetic months REV/COGS/CASH/AR | Reviewed trend and noncausal diagnostic evidence |
| BE-R5-E2E-10 | ML beats/loses baseline or times out | Explicit model status; CORE not altered |
| BE-R5-E2E-11 | Mixed basis/period/currency/entity | Reject cross-form comparison |
| BE-R5-E2E-12 | Old PostgreSQL at Flyway 20261116.08 upgraded | No checksum rewrite/lost marker; idempotent migrate |

**Negative/fault matrix:** malformed XLSX, ZIP resource budget, source raw SHA mismatch, repeated submission, stale confirmation, concurrent approve, duplicate outbox, planning crash after transaction, dead-letter retry, expired task lease, stale worker, wrong dependency, zero denominator, sparse history, missing label, manipulated expected manifest, unsigned/unqualified method. Ошибка не должна приводить к фальшивому `COMPLETE`.

## 3. Приёмочные свидетельства

Каждый test case должен сохранять: `requirementId,testClass#method,fixtureId,expected,actual,resultStatus,organizationId anonymized,sourceFingerprint,factSetRevisionId,methodVariantVersion,executionRunId,reportHash,gitSha,ciRunUrl,javaVersion,postgresVersion`. Нельзя получать expected от тестируемого production engine.

Новые fixtures: `backend/src/test/resources/fin/intake/`, `fin/methods/`, `fin/execution/`, `ml/`. Генерировать machine-readable backend evidence matrix в `backend/target/fin-evidence/`, upload CI artifact; отсутствие обязательного test или skipped test → release HOLD. Ручные PASS без GitHub Actions не засчитываются.

Расширять, а не заменять существующие: `FlywayMigrationRecoveryIntegrationTest`, `PilotXlsxIntakeIntegrationTest`, `ReviewedFinancialIntakeIntegrationTest`, `FormDraftRevisionV5IntegrationTest`, `PostgresInvariantTest`, `FactPublicationIntegrationTest`, `SingleExecutorGraphTest`, `ReviewedSkuSingleExecutorIntegrationTest`, `TrustedCrossFormDiagnosticIntegrationTest`, `TribuoForecastSmokeTest`.

## 4. Измерения и пределы

Снять baseline на объявленных CI/MacBook ресурсах: XLSX 2MB/512 pilot rows, compression/heap/time; 2 и 4 concurrent workers, throughput, p50/p95/p99, retry/failure frequency; DAG plan for 5/20/60 method candidates; ML training budget. **До измерения не выдумывать SLA**. Результат сохранять с OS/CPU/RAM/JDK/PostgreSQL и fixture cardinality.

Тесты на existing PostgreSQL означают миграционный **клон с историей и маркерами**, а не доступ к реальной production БД. Новая Flyway-миграция требует проверку fresh/upgrade; никогда не менять применённую историю и не делать бесконтрольный `flyway repair`.

## 5. Commands и same-SHA gate

    node scripts/check-flyway-versions.mjs
    mvn -B -ntp -f backend/pom.xml verify

`-Dtest=...` пригоден для RED/быстрого feedback, но не заменяет полный `verify`.

**Final acceptance:** FIN Backend Domain = SUCCESS; FIN CI существующего репозитория не регрессировал; обе проверки относятся к **одному exact SHA**; JUnit XML и Testcontainers reports настоящие, mandatory cases not skipped; SQL migration history и trace checks приложены. Методический S2 и ML real-world proof — отдельные gates. Технический GREEN не означает "все 166 форм/60 методик" и не открывает ACTUAL.

**DoD:** complete evidence matrix по реально принятому subset, zero hidden missing tests, rollback/recovery/immutability proven, independent method expected, reproducible ML metrics. Frontend, IAM и Keycloak **вне** работ.

