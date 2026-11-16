# FIN-BE — Backend programme R1–R5

**Дата:** 2026-11-16. **Статус:** SPECIFIED / NEW WORK NOT IMPLEMENTED.  
**Единственная рабочая ветка:** `feat/fin-r0-r1a`. **Baseline SHA:** `a9e592df552227030ab39467d204dbb2ca053494`. Базовый FIN CI и FIN Backend Domain на этом SHA были GREEN; это не является приёмкой задач ниже.

## Правила реализации

1. Изменять **только backend**: `backend/**` и связанные инженерные документы `docs/**`. Новые frontend, React, Flutter, Playwright, пользовательские интерфейсы и клиентские адаптеры не разрабатывать.
2. Не создавать ветки, не сливать в `main`, не внедрять Keycloak/OIDC и не заниматься дальнейшей разработкой IAM/безопасности. Существующие ограничения, fail-closed конфигурацию и проверки **не отключать**. Новые публичные endpoints не открывать, если существующая граница не обеспечивает их корректную работу; для приёмки использовать внутренние Java-сервисы.
3. Java 21, Spring Boot 3.5.7, JPA/JDBC, PostgreSQL, Flyway, JUnit 5, Testcontainers. `EconomicKernel` и FactSet — каноническая финансовая модель. Вторую БД фактов и параллельный независимый executor не создавать.
4. Только additive/versioned Flyway; не изменять исторические миграции. `node scripts/check-flyway-versions.mjs` и чистый/upgrade PostgreSQL — обязательные проверки.
5. Без доказательств не заявлять `IMPLEMENTED`, `QUALIFIED`, `60/60`, `166/166` или `ML_READY`. Классификация результата: `SPECIFIED → CODED → TESTED → INTEGRATED → FINANCE_REVIEWED → QUALIFIED`.
6. Сохранять default `fin.execution.enabled=false`, `fin.diagnostics.actual-preview-enabled=false`, методический `releaseGate=HOLD`. Нельзя превращать технический GREEN в разрешение выполнять неквалифицированную финансовую диагностику на ACTUAL.
7. Ожидаемые финансовые результаты определяются независимо от production-calculator. S2 — **независимая методическая/финансовая рецензия**, не новый IAM.

## Связанные ТЗ

| Этап | Цель | Документ | Gate |
|---|---|---|---|
| FIN-BE-R1 (P0) | Загрузка, валидация, preview, immutable source/FactSet | [R1](FIN-BE-R1-FORM-INGESTION-TZ.md) | Реальные XLSX→PostgreSQL acceptance и явный unsupported list |
| FIN-BE-R2 (P0) | Реестр вариантов методов, applicability, DAG, outbox, retries | [R2](FIN-BE-R2-METHODOLOGY-ORCHESTRATION-TZ.md) | One logical run / failover / correct blocked states |
| FIN-BE-R3 (P0/P1) | Независимые golden, source→result trace, diagnostic evidence | [R3](FIN-BE-R3-DIAGNOSTIC-PROOF-TZ.md) | Методический вариант квалифицирован отдельно |
| FIN-BE-R4 (P1) | Tribuo, point-in-time data, rolling backtest, baselines | [R4](FIN-BE-R4-ML-QUALIFICATION-TZ.md) | ML лучше baseline по зафиксированному критерию или HOLD |
| FIN-BE-R5 (P0) | Backend regression / PostgreSQL / crash recovery / acceptance | [R5](FIN-BE-R5-BACKEND-ACCEPTANCE-TZ.md) | GREEN на одном SHA + evidence matrix |

**Последовательность:** R1-A (audit) → R1-B (trusted intake) → R2-A (method passports/applicability) → R2-B (execution recovery) → R3 (financial proof) → R4 (ML shadow qualification) → R5 final. R5 тесты писать **параллельно с каждым этапом**, не откладывать. R3/R4 на synthetic data допускают исследовательское параллельное выполнение, без promotion в ACTUAL.

## Сквозные инварианты

`XLSX / manual → bounded parser → source + period/scope → immutable preview/confirmation → existing independent review → canonical FactSet + immutable source lineage → outbox → eligible method variant → typed DAG → deterministic calculation → evidence-backed diagnostic CORE → optional SHADOW_ML`.

**Отдельные статусы:** файл структурно принят ≠ source verified ≠ методика S2-approved ≠ задача computed ≠ результат qualified. Snapshot source/CORE нельзя переписывать. Missing ≠ 0; source conflict ≠ latest-timestamp-wins; observation ≠ причинно-следственный вывод; ML estimate ≠ фактическая проводка.

На исходном SHA реально существуют `PilotXlsxTemplateService` (5 форм), `FormDraftRevisionV5Service` (F02 draft), `CatalogCaptureRegistry` (166 schema entries с разными уровнями готовности), `SingleExecutorScheduler`, `MethodTaskAdapter`, `CompositeCoreReportPublisher`, диагностические Java engine/services и `TribuoForecastSmokeTest` (synthetic test-only). Наличие классов не означает приёмку нового функционала. JSON каталог 60 семейств находится в `backend/src/main/resources/fin/methodology/methodology-catalog-r1.json` и по умолчанию `HOLD`.

## Доказательства по каждому пункту

Каждый implementation slice фиксирует requirement ID, исходный RED, минимальное исправление, совместимость старых данных, independent expected, JUnit/Testcontainers GREEN, миграцию clean+upgrade, exact Git SHA и Actions URL. Для каждого этапа отдельно хранить список непокрытых форм/методик и ограничения. Финансовый S2 может не совпадать с техническим CI verdict.

**Проверка backend:** `mvn -B -ntp -f backend/pom.xml verify` плюс existing `FIN Backend Domain`. Existing `FIN CI` должен не регрессировать; его frontend проверка не означает возобновления frontend разработки. На последнем SHA сравнить результаты всех релевантных jobs, JUnit, Testcontainers и raw evidence.

**Связанные нормативные материалы:** [Execution contract](FIN-EXECUTION-CONTRACT-V1.md), [DIAG 60 P0](FIN-DIAG-60-P0-ACCEPTANCE.md), [Upload audit](FIN-V5-ALL-IMPLEMENTED-UPLOAD-PROCESSES-UNIT-AUDIT.md), [R8](FIN-R8-REGRESSION-RECOVERY.md). Их старые требования по React/IAM не входят в новую программу. При противоречии бизнес-правил оформить ADR, а не вносить скрытую несовместимость.

**Финальный статус сейчас:** R1–R5 SPECIFIED. `ACTUAL_DIAGNOSTIC_RELEASE=HOLD` до source/method qualification, даже при backend GREEN.

