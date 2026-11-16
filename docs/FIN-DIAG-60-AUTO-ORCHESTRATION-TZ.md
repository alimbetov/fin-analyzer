# FIN-DIAG-60 — AUTO-RUN + COMPOSITE ANALYSIS + REPORT ORCHESTRATION

**Дата:** 2026-11-16 · **Версия:** 1.0 · **Статус:** PROPOSED / NOT IMPLEMENTED  
**Базовое ТЗ:** [FIN-DIAG-60-IMPLEMENTATION-TZ.md](FIN-DIAG-60-IMPLEMENTATION-TZ.md)  
**Ветка:** `feat/fin-r0-r1a`; не создавать новую ветку, не делать merge в main.

## 1. Бизнес-цель

После появления **новой доверенной ревизии** финансовых/операционных фактов FIN автоматически определяет применимые методики из существующего реестра 60 методов, строит ациклический план вычислений с учётом зависимостей, рассчитывает только изменившиеся показатели, исследует проверяемые межпоказательные связи и формирует **единый совокупный аналитический отчёт**. Одна загруженная форма не приводит к запуску всех 60 методик без условий применимости. Отчёт можно сформировать вручную по запросу, по расписанию и после утверждения новой ревизии; публикация внешнему получателю и отправка никогда не выполняются без отдельного разрешения.

Основной принцип: `FORM/EX-SHEET → TRUSTED FACT SET → APPLICABILITY → DEPENDENCY DAG → CALCULATION → RECONCILIATION → DIAGNOSTICS → COMPOSITE REPORT SNAPSHOT`.

Режимы запуска: `ON_APPROVAL` (лёгкие детерминированные методы), `ON_DEMAND`, `SCHEDULED`, `RECOMPUTE_STALE`, `SHADOW_ML`. ML, bootstrap, Monte Carlo, тяжёлая оптимизация не запускаются без явно включённой политики затрат/прав. Не запускать аналитику по неподтверждённому Excel/черновику. Должна сохраняться отдельная возможность локального offline-расчёта с явной пометкой источников, не эквивалентная hosted ACTUAL.

## 2. Каноническая архитектура

Развивать существующие `ScenarioEconomicsEngine`, `CrossFormDiagnosticEngine`, `DiagnosticCompass`, `DiagnosticSheetCompass` и `EconomicFactSetRevision`. Не создавать второе хранилище тех же экономических значений.

Логические компоненты:
- `AnalysisTriggerService`: проверенный сигнал публикации fact-set версии; transactional outbox / идемпотентная доставка; не доверять client-supplied APPROVED.
- `MethodologyApplicabilityResolver`: отрасль, бизнес-модель, права, required facts, reporting basis, валюта, минимальный период, квалификация и включённые политики.
- `MethodDependencyPlanner`: typed DAG по требуемым фактам и derived metrics; detect unknown method, circular references, unknown unit, prohibited scope. Календарная агрегация STOCK/FLOW/RATIO раздельная.
- `AnalysisRunCoordinator`: составной run, individual method tasks, retry-safe state machine, bounded concurrency и quotas.
- `CalculationCache`: ключ `tenantId + entityScope + factSetRevisionFingerprint + methodologyCode/version + assumptionsFingerprint + period/basis/currency/units + access-scope`; read never bypass ABAC. Старые snapshots immutable; invalidation по изменениям зависимостей.
- `CrossFormEvidenceResolver`: priority, reconciliation, deduplication, conflict/uncertainty. Не складывать F02 + DF-SAL + SF-G01 как три независимые выручки.
- `CompositeDiagnosticAggregator`: собирает observations, hypotheses, NOT_COMPUTABLE, source conflicts, warnings, scenarios; не суммирует несопоставимые KPI и не формирует невалидный единый «балл здоровья».
- `ReportSnapshotBuilder`: immutable versioned JSON snapshot; визуализация React/ECharts и проверенные HTML/PDF/XLSX рендереры после их реализации. Блоки и таблицы на RU/KK/EN/ZH; перевод не меняет числа.
- `DataLifecycleManager`: TEMP uploads 7 дней; simulation working files / temp feature sets 30 дней; exported temp artifacts 7–30 дней; утверждённые факты/исторические отчёты по отдельным retention policies. `expiresAt`, `legalHold`, retries, dedup deletion. Никакой гарантии автоматического удаления из backup без отдельной политики.

## 3. Оркестрация методов и зависимости

Пример графа, не объявляющий готовность кода:
```text
F01/F02/F03/DF-SAL/C01/C02 approved revisions
  ├─ CrossForm Reconciliation → canonical revenue/AR/AP/cash
  ├─ FIN-01..04 → trends/liquidity/margins
  ├─ CON-01 → CON-02 → CON-04 ← CON-03 (12+ monthly SKU rows)
  ├─ CASH-02 + FIN-08 → CASH-03 (13 week cash)
  ├─ FIN-05/FIN-07 → explanations
  └─ ADV-01 (opt-in ML) → backtested forecast + baseline
All → CompositeDiagnostics → ReportSnapshot
```
Модель DAG должна иметь `required | optional | evidence-only` edges. При отсутствии обязательных данных метод `INSUFFICIENT_DATA`, зависимые вычисления `NOT_COMPUTABLE`, остальные независимые методы продолжают расчёт. При `SOURCE_CONFLICT` блокируются только затронутые вычисления, общий отчёт показывает ограничение. Утверждение новой ревизии запускает **incremental recompute**, не изменение исходного snapshot.

Границы расчётов: одно юрлицо, несколько юрлиц (без несанкционированной консолидации), бизнес-подразделение, товар/SKU, проект, ЦФО, год/квартал/месяц/неделя и источник. Перекрёстные показатели сравниваются только при сопоставимой scope/basis/валюте/времени. Консолидация групп компаний разрешается после отдельного квалифицированного процесса элиминаций.

## 4. Планирование заданий, состояние, хранение

Статусы parent run: `QUEUED, PLANNING, RUNNING, PARTIAL, SUCCEEDED, FAILED, CANCELLED, STALE`. Task: `PENDING, READY, RUNNING, COMPUTED, SKIPPED_NOT_APPLICABLE, BLOCKED_MISSING, BLOCKED_CONFLICT, FAILED_RETRYABLE, FAILED_FINAL`. `PARTIAL` не считается финальным `SUCCEEDED`, а report обязан перечислить недоступные методы. Запрещено выдавать unqualified методы за официальную консультацию.

В PostgreSQL по необходимости добавить или расширить существующие таблицы `analysis_trigger`, `analysis_run`, `analysis_method_task`, `analysis_dependency`, `analysis_snapshot`, `analysis_report_snapshot`, `cleanup_task`; перед миграцией проверить текущие сущности. Для многопроцессной обработки использовать PostgreSQL leasing/`SKIP LOCKED`, idempotency keys, `attemptCount`, `nextRetryAt`, lock expiry, backoff + dead-letter/FAILED_FINAL. Соблюдать isolation и avoid lost updates. Максимум конкурентных задач, CPU/memory/time/cost budget на tenant/method — конфигурируемые. Java 21 virtual threads допускаются для I/O, но не увеличивают CPU throughput для Monte Carlo.

Триггеры: commit подтверждённой ревизии → outbox → scheduler; периодические пересчёты только при изменившихся данных/сроке годности модели. Пользователь на React видит очередность, прогресс, частичные результаты, blocked reasons, возможность отмены и повторного запуска.

## 5. Контракты API (проектные; перед внедрением сравнить с существующими)

- `POST /api/v1/organizations/{orgId}/analysis-plans/preview` — dry-run граф, required inputs, стоимость/число задач, квалификация, scope и методы; ничего не исполняет.
- `POST /api/v1/organizations/{orgId}/analysis-runs` — создать run по immutable approved revision с policy, idempotency key.
- `GET /api/v1/organizations/{orgId}/analysis-runs/{runId}` — прогресс, методы, warnings, reasons.
- `POST /api/v1/organizations/{orgId}/analysis-runs/{runId}/cancel` — отмена без удаления исторических результатов.
- `GET /api/v1/organizations/{orgId}/analysis-runs/{runId}/report` — immutable composite snapshot, пометки полноты/версий.
- `GET /api/v1/organizations/{orgId}/analysis-runs/{runId}/method-results` — детальные trace и evidence в рамках ABAC.
- `PUT /api/v1/organizations/{orgId}/analysis-policies` — authorized настройки авторасчётов, ML, TTL, расписания, расходов.

Ответ preview должен отдельно показывать `ELIGIBLE, SKIPPED_NOT_APPLICABLE, BLOCKED_MISSING, BLOCKED_CONFLICT, DISABLED_POLICY, UNQUALIFIED`, estimated resources и следующий нужный источник.

## 6. Совокупный отчёт

Главы: цель/профиль; доступность и качество данных; доверенная финансовая база; динамика 3–5 лет; структура и Парето; ABC–XYZ (если есть SKU history); прибыль и факторные изменения; ликвидность/CCC/13-week cash; бюджет; инвестиции и DSCR (если применимы); отраслевые KPI; статистические связи (наблюдения, не причины); прогнозы/интервалы (если ML квалифицирован); сценарные альтернативы; список гипотез; приоритеты дальнейших проверок; список недоступных расчётов; ограничения; полный audit trail (периоды, формулы, версии, source fields).
Нельзя автоматически публиковать отчёт банку/инвестору; нужен explicit owner approval + ABAC grants.
`ComprehensiveReportReadiness`: `COMPLETE` если все обязательные применимые методы в требуемом scope квалифицированы и вычислимы; `PARTIAL` если доступен ограниченный набор; `BLOCKED` при критическом конфликте базовых фактов. Число вызванных методов не является оценкой качества бизнеса.

## 7. Синтетические mock данные и независимые expected results

Использовать имеющиеся `FIN-DIAG-60-IMPLEMENTATION-TZ.md` F-01…F-14, расширить следующими тестовыми сценариями. Fixture = отдельная синтетическая организация `tenant-demo-a`, `currency=KZT`, `basis=SEPARATE`, `reportingPolicy=MONTHLY`, immutable revisions и seed. Отдельно сохранять JSON inputs, manually verified expected outputs и проверку trace.

| Test | Synthetic setup | Expected |
|---|---|---|
| AUTO-01 Auto Approval | F01 balanced: CA 400, CL 200, approved revision r1 | Eligible FIN-04 current ratio 2; only relevant methods queued; no arbitrary 60 |
| AUTO-02 Pending Source | Excel staged but not approved | No ACTUAL analysis task; preview shows APPROVE_DRAFT |
| AUTO-03 Pareto/XYZ | 12 monthly SKU observations and approved category revenue (F-02/F-03 fixtures) | CON-01/02/03/04 DAG runs once each; stable ABC–XYZ |
| AUTO-04 Missing History | One month SKU instead of 12 | CON-01 may run; CON-03 missing; CON-04 NOT_COMPUTABLE; overall PARTIAL |
| AUTO-05 Cross-Form Duplicate | Revenue F02=1000, DF-SAL=600+400, SF-G01=1000 same source transactions | canonicalRevenue=1000; no triple count |
| AUTO-06 Conflicting Forms | F02 revenue 1000, detail 990 | only dependent calculations blocked; conflict visible in report |
| AUTO-07 Incremental | r2 only changes receivables while sales unchanged | affected cash/receivable nodes recomputed; unrelated Pareto result reused |
| AUTO-08 Idempotent Retry | same approval event delivered 3 times | one logical parent run (per policy), no duplicated result/notification |
| AUTO-09 Worker Crash | task leased then worker exits before completion | lease expires, idempotent resumption, no double-counted results |
| AUTO-10 Tenant A/B | tenant A request B run/report/cache | 403, no data leakage, report and cache tenant-isolated |
| AUTO-11 Qualitative | SWOT available, no reviewed evidence | not treated as numeric method; UNQUALIFIED or MISSING_EVIDENCE |
| AUTO-12 Composite Cash | opening 100, receipts 70, payments 90 (week1) | closing 80; report cash block and source trace |
| AUTO-13 Scenario vs Actual | scenario revenue+10%, baseline actual 1000 | scenario=1100, immutable actual remains 1000 |
| AUTO-14 ML Disabled | policy opt-in=false for ML and Monte Carlo | neither training nor simulations run automatically |
| AUTO-15 Simulation TTL | temp simulation object created 2026-01-01, TTL=30d | expire 2026-01-31 (UTC policy), idempotent cleanup; approved facts untouched |
| AUTO-16 Output Locales | same snapshot rendered in RU/KK/EN/ZH | numeric fingerprint equal in all languages; text localized |
| AUTO-17 Historical Restatement | year 2024 restated in new revision | old report remains reproducible; new run references new revision |
| AUTO-18 Incomplete Composite | 8 eligible, 5 computed, 3 missing | status PARTIAL and explicit excluded sections; no invented overall score |

Mandatory negative scenarios: cycle/unknown method, malicious formula in XLSX, metadata tampering, currency mismatch KZT/USD, period mismatch yearly/monthly, source conflict, missing vs explicit zero, concurrent approval vs run, false `approved` client flag, stale model version, large task budget, workers competing, cleanup fail/retry, legal hold, noncomparable group consolidation, unauthorized report export.

## 8. Тесты, performance и release gates

Unit/property/golden: dependency topological order, no cycles, unique cache fingerprint, BigDecimal, dimension analysis, no false FIN grade, deterministic method results, report no ghost values. PostgreSQL Testcontainers: outbox, lease, idempotency, crashes, retries, stale revisions, tenant data isolation, TTL cleanup. Node/Vitest + Playwright: preview, live progress, partial warnings, report consistency, error state, cancellation; Chromium Linux and Chromium/WebKit macOS. ML: rolling-origin backtest, baseline dominance evaluated before advisory activation, resource cap; simulation reproducibility by seed + version. Load smoke: configurable (e.g. 50 concurrent runs / 4 workers / 60 configured methods) and measure p95 latency/CPU/memory/queue backlog; no arbitrary performance claim before baseline.

`DoD`: exact same SHA GREEN for Java/JUnit+PostgreSQL, Node/Vitest, React/Playwright, full source trace for every report number, independent financial method S2, security S2, real manual UAT. Report publication to third parties remains human-approved. Mark `NOT IMPLEMENTED` until evidenced.

## 9. Delivery sequence

`AUTO-R0`: inventory existing orchestration, revision/entity/analysis and report classes, architecture ADR.  
`AUTO-R1`: preview planner / applicability / DAG / dry-run + golden tests.  
`AUTO-R2`: outbox/worker/leases/idempotency/incremental calculation + PostgreSQL tests.  
`AUTO-R3`: composite report snapshot + React progress/preview + golden consistency.  
`AUTO-R4`: simulation ML on-demand and resource budgets, TTL 30-day cleanup.  
`AUTO-R5`: independent method/reports/security qualification, full E2E on one SHA, feature-flagged rollout.

**Коммит с этим файлом — только утверждаемое проектное требование, а не выполненная реализация или тестовое доказательство.**

## 10. Утверждённый контракт SingleExecutor / optional ML (2026-11-16)

**Нормативное дополнение:** любой расчёт, включая 60 аналитических методик, статистику, симуляции, ML и оптимизацию, инициируется через **единый фасад `SingleExecutor`**. Это **одна логическая точка входа, а НЕ один Java thread, одна очередь или один физический worker**. SingleExecutor использует существующий MethodologyRegistry, проверку доверенных фактов, серверный auth/ABAC, ApplicabilityResolver, dependency DAG, task scheduling, calculation trace, immutable snapshots и quotas.

### 10.1. Режимы отчёта

| Mode | Содержимое | Правило готовности |
|---|---|---|
| `CORE` | Применимые и квалифицированные финансовые/статистические/отраслевые/сценарные методы, в том числе немашинные прогнозы и расчёты | Независим от ML; ML не планируется по умолчанию |
| `CORE_WITH_ML` | Тот же CORE **плюс** только квалифицированные ML-выводы (прогнозы, anomaly/segmentation, интервалы, baseline/error) | ML opt-in/policy, разрешения, соответствие данных, `ADVISORY` model status |

`CORE` и `CORE_WITH_ML` — **профили состава отчёта**, а не два разных движка, версии бизнес-фактов или расходящиеся финансовые показатели. Числа и квалификация CORE-секций идентичны для одного `FactSetRevision`, methodologyVersion и assumptions fingerprint. ML не переписывает CORE и не подменяет историю.

Если запрошен `CORE_WITH_ML`, но ML отключён, не обучен, не прошёл baseline/holdout или закончился с ошибкой — отчёт CORE **успешно формируется** и выдаётся, а ML-раздел помечается `DISABLED / NOT_AVAILABLE / INSUFFICIENT_DATA / UNQUALIFIED / FAILED / PENDING`; итоговый статус `CORE_COMPLETE_ML_UNAVAILABLE` или `CORE_COMPLETE_ML_PENDING`, без ложного объявления `CORE_WITH_ML_COMPLETE`. При недостатке обязательных CORE-фактов действует прежнее правило `PARTIAL/BLOCKED` независимо от ML.

**Опциональное последующее обогащение:** если CORE отчёт создан до окончания ML, после квалифицированного ML запуска создаётся **новая immutable revision отчёта** с `parentReportId` и ссылкой на ровно тот же `FactSetRevision`/assumptions; прежняя CORE-ревизия остаётся доступна и неизменна. ML из другой версии фактов не может «дополнить» отчёт без нового согласованного CORE запуска.

### 10.2. Интерфейсы и разделение ответственности

Предлагаемые контракты (выверить с реальными типами перед кодированием):

```java
enum ReportMode { CORE, CORE_WITH_ML }
enum ExecutionKind { DETERMINISTIC, STATISTICAL, ML_PREDICTION, OPTIMIZATION, QUALITATIVE }
record AnalysisRequest(
    String organizationId, String factSetRevisionId,
    ReportMode reportMode, String businessQuestion,
    java.util.Set<String> requestedMethods, String idempotencyKey) {}
interface SingleExecutor {
    AnalysisPlan preview(AnalysisRequest request, AuthContext trustedAuth);
    AnalysisRunId submit(AnalysisRequest request, AuthContext trustedAuth);
    AnalysisStatus status(AnalysisRunId runId, AuthContext trustedAuth);
}
interface SpecializedExecutionAdapter {
    ExecutionKind kind();
    MethodologyResult execute(VerifiedMethodologyContext context);
}
```

`organizationId` и любые идентификаторы в request являются **только запрошенным scope**; права и approval получают только server-side. Не использовать client-provided `approvalState`, `ML_ADVISORY` и `reportQualified`. Пользователь может включить ML в рамках права, но не повысить его статус квалификации. Предварительный `preview` не сохраняет и не исполняет ML-задачи. SingleExecutor планирует worker tasks, управляет зависимостями и повторными попытками; адаптеры не публикуют внешние отчёты самостоятельно.

### 10.3. Опциональные ML-выполнения

- Default `reportMode=CORE`, `mlPolicy=DISABLED`, пока пользователь/организация явно не включили ML.
- `CORE`-запуск никогда не ждёт ML training/inference; ошибочный ML adapter не влияет на статус CORE.
- `CORE_WITH_ML`: сначала зафиксировать CORE snapshot; затем запускать только доступные, сертифицированные в проекте ML-advisory модели в рамках quotas.
- Требования по данным, временная валидация и baseline обязательны. `EXPERIMENTAL`, `SHADOW`, `UNQUALIFIED` не попадают в официальный ML-раздел как готовые выводы.
- Сводный отчёт различает `OBSERVED FACT`, `DETERMINISTIC RESULT`, `SCENARIO ASSUMPTION`, `ML PREDICTION`, `INTERPRETATION`. Показывать `modelVersion`, feature-set, horizon, backtest metrics и interval при наличии подтверждённой калибровки.
- Результаты ML не влияют на исторические финансовые тождества и исходный `FactSetRevision`.
- Optimization и Monte Carlo также делегируются специализированным адаптерам через SingleExecutor и запускаются по отдельной политике затрат; они не считаются ML по умолчанию.

### 10.4. Report snapshot и API

Рекомендуемые поля `UnifiedReportSnapshot`:

```json
{
  "reportModeRequested": "CORE_WITH_ML",
  "reportModeDelivered": "CORE",
  "coreStatus": "COMPLETE",
  "mlStatus": "PENDING",
  "factSetRevisionId": "synthetic-rev-001",
  "methodologyVersions": {},
  "coreSnapshotId": "synthetic-core-001",
  "mlSnapshotId": null,
  "parentReportId": null,
  "dataClassification": "SYNTHETIC"
}
```

Это illustrative schema, а не актуальный production response. В API `analysis-plans/preview`, `analysis-runs` и `report` добавить `reportMode`, `coreStatus`, `mlStatus`, `reportRevision`, `parentReportId` и набор выполненных/пропущенных методик с причинами. `reportModeDelivered=CORE_WITH_ML` допустим **только** при выполненных квалифицированных ML-секциях. Если нет пригодных ML-моделей, не создавать пустую секцию, которая выглядит как результат ML.

### 10.5. Дополнительные синтетические сценарии приёмки

| Test | Input / event | Expected |
|---|---|---|
| SINGLE-01 | Approved fixture F01 current assets=400, current liabilities=200, mode CORE, ml disabled | Current ratio=2; CORE report success; no ML tasks created |
| SINGLE-02 | Same revision and context, CORE_WITH_ML requested but no qualified model | CORE success; explicit ML `UNQUALIFIED/NOT_AVAILABLE`; no fake predictions |
| SINGLE-03 | CORE_WITH_ML qualified ML model, 36-month synthetic series, authorized user | CORE numbers identical to SINGLE-01 if fact scope same; ML outputs separately labeled with version/holdout |
| SINGLE-04 | CORE already published, ML later completes for same revision | New immutable report revision with `parentReportId`; original CORE report byte-for-byte stable |
| SINGLE-05 | ML worker throws / runs out of memory | CORE remains available; mlStatus FAILED; resource cleanup/retry policy used |
| SINGLE-06 | User submits forged client ML_ADVISORY status | Server rejects escalation; source and model status server-authoritative |
| SINGLE-07 | ML run from revision r2 attempted to enrich CORE r1 | Rejected/version conflict; requires consistent rerun |
| SINGLE-08 | Two identical requests with same tenant/key/fingerprint | One logical report/run, idempotent task scheduling |
| SINGLE-09 | User denied ML access but has core access | CORE allowed, ML portion denied without tenant/model disclosure |
| SINGLE-10 | 4 report languages | Same CORE numeric fingerprint and ML prediction fingerprint, localized labels only |

В JUnit/Testcontainers/Playwright проверить переключение режима, отсутствие зависимостей CORE от ML, late enrichment, restatement, ABAC, failure isolation, source trace, report/export consistency. Golden expected фиксировать независимо от production executor. Ни один тест не отмечать PASS до реального запуска.

### 10.6. План внедрения

`SINGLE-R0` — аудит текущих точек входа, унификация контракта SingleExecutor (без создания второго расчётного ядра).  
`SINGLE-R1` — CORE через один фасад + все применимые методы, preview/DAG/idempotency.  
`SINGLE-R2` — `UnifiedReportSnapshot` для CORE + React/PDF/XLSX parity по мере готовности экспортов.  
`SINGLE-R3` — ML adapter / `CORE_WITH_ML` по feature flag; failure isolation, позднее дополнение отчёта новой immutable ревизией.  
`SINGLE-R4` — 10 synthetic golden E2E, S2 review, единый SHA CI.

**Критерий:** без ML пользователь всегда может получить полноценный CORE-отчёт из доступных квалифицированных расчётов; при наличии ML — расширенную версию с явно маркированными ML-выводами и неизменными базовыми цифрами. Вся логика маршрутизации расчётов проходит через SingleExecutor.
