# FIN MG-R0.4 — Immutable Metric-Led UAT Execution

**Дата:** 2026-11-20  
**Репозиторий:** `alimbetov/fin-analyzer`  
**Ветка:** `feat/fin-form-library-standalone`  
**Статус:** `EXECUTABLE_UAT_VERTICAL_IMPLEMENTED`, financial S2 **NO**, production admission **NO**.

## Архитектурное решение

Не создавать второй универсальный расчётный движок.

- `MetricGraphResolver` использует неизменяемые `fin_calc.formula_contract_revision`, проверяет точный `formulaRevisionId`, поднимает все зависимости и компилирует типизированный `Calc4DTypedDagCompiler` DAG. `NET_PROFIT`, DSCR и неавторизованные цели **не** сопоставляются автоматически с другими показателями и не выполняются.
- `FinIntakeUatSourceAdapter` через INGEST-2 перепроверяет исходную редакцию `fin_intake`, её SHA, org/project, валюту, период, schema и reporting/data-space.
- `UatCalculationPreviewService` переиспользует уже существующий `F02CalculationKernel` (Java BigDecimal). Для A05 `LoanScheduleService` / `LoanAmendmentService` повторно проверяют уже рассчитанную и сохранённую immutable версию, никакого повторного изобретения графика/договора.
- `MetricGraphUatExecutionService` связывает эти существующие контракты и исполнения с минимальным `fin_calc.mg_r04_uat_plan` и `mg_r04_uat_result`. Это *новая UAT-проекция*, а не изменение существующих `fin_calc.calc_plan`, `calc_job`, `calc_result` и не подмена старого CALC-4D FactSet plan compiler.
- Для source-based F02 полностью пройдены формула → DAG → исходные физические поля → точная редакция → действующий kernel → сохраняемый результат. Для A05 это пока *specialized result node*, а не универсальный DAG-компилятор всех loan calculations.

## Поддержанные численные маршруты

| Показатель | Контракт | Ядро | Условие |
|---|---|---|---|
| `F02.grossProfit` | `F02_TEST_ONLY_V1`, точная версия метода | `F02CalculationKernel` | F02 netRevenue/cogs/opex |
| `F02.grossMargin` | Точная `formula_contract_revision` и DAG | `F02CalculationKernel` | Gross profit / revenue, zero → INVALID |
| `F02.operatingResult` | Версионированный DAG | `F02CalculationKernel` | Gross profit − operating expenses |
| `CALC-A05.CONTRACT_CASH` | `CALC-A05` application contract revision 1 | `LoanScheduleService` | Exact saved C05 + schedule UUID |
| `CALC-A05.AMENDED_CONTRACT_CASH` | `CALC-A05` revision 1 | `LoanAmendmentService` | Exact saved C05 + amended scenario UUID + parent SHA |

`NET_PROFIT` нельзя автоматически подменять `F02.operatingResult`. `C01/C02` имеют настоящие рабочие UAT kernels, но ещё не включены в *MG-R0.4 immutable plan* до авторинга pin-compatible методических контрактов AS_OF. Не заявлять подключение этих двух форм или всех 154 методик на данном этапе.

## Миграции и финансовая безопасность

- `062-fin-mg-r04-immutable-uat-plans-postgresql.sql`: append-only `mg_r04_uat_plan`, `mg_r04_uat_result`, `mg_r04_uat_command_receipt`; SHA256 exact source, projection, formula/application method, engine, DAG/graph и result; versioned source UUID; UAT-only и `financially_approved=false`, `production_admitted=false` CHECK на уровне PostgreSQL.
- `063-fin-mg-r04-database-pins-postgresql.sql`: отдельная additive-hardening миграция (062 не переписывается). PostgreSQL validation trigger проверяет exact source revision org/project, form code, period, currency, reporting basis, dataSpace, SHA content и schema; F02 method exact revision + version/form version + binding SHA; A05 parent/source/result engine pin; result и idempotency receipt owner+lineage.
- Запись `fin_fact.fact_set` и production `fin_calc.calc_plan/calc_job/calc_result` отсутствует; проверяется отрицательными тестами.
- Advisory transaction locks на `(org, Idempotency-Key)` и `(org, planSha256)`; same-key repeat → `IDEMPOTENT_REPLAY`, different key/same plan → `EXISTING_PLAN_REPLAY`; key+changed command → HTTP 409. Повторное открытие плана пересчитывает узлы и сверяет digest физических PostgreSQL-строк/плана.
- `engineSha256` — зафиксированный SHA *algorithm/contract descriptor* существующего kernel, **не** незалежно verified build manifest/поставленный JAR. Immutable fingerprint защищает повторяемость UAT, но не даёт production qualification.

## API

Все маршруты **выключены по умолчанию**. Только локальный/изолированный synthetic UAT при одновременных:

- Backend `fin.calc.intake-uat.enabled=true`
- Backend `fin.calc.mg-r04-uat.enabled=true`
- Frontend `VITE_FIN_INTAKE_UAT=true`, `VITE_FIN_MG_R04_UAT=true`.

`POST /api/v1/uat/metric-graph/execute` + `Idempotency-Key`: точные `organizationId`, `projectId`, `targetMetricCode`, `exactSourceRevisionId`, `exactMethodRevisionId` (F02) либо `exactCalculationRevisionId` (A05) и `expectedPeriodType/Code/Currency/ReportingBasis/DataSpace`.

`GET /api/v1/uat/metric-graph/plans/{exactPlanId}?organizationId=...&projectId=...` перепроверяет источник, метод и результат. В React F02 есть панель явного выбора метрики и метода, сохранение и повторное открытие по UUID с SHA/Explain. Остальные формы не получают ложную универсальную кнопку Calc.

## Квалификационная матрица

| Проверка | Контроль |
|---|---|
| F02 grossProfit 200 − 100 = 100 KZT | `MetricGraphUatExecutionPostgresqlTest` |
| F02 grossMargin = 0.50000000, operatingResult = 50 KZT | Тот же реальный PG16 |
| F02 zero revenue → INVALID/DIVISION_BY_ZERO | Тот же реальный PG16 |
| F02 revision 1→2, две независимые immutable plan/result | Тот же реальный PG16 |
| A05 saved schedule + amended cash as exact graph nodes | Тот же реальный PG16 |
| Сохранение, повтор, same-key, altered-key, cross-org/project/method | Тот же реальный PG16 |
| Два конкурентных исполнителя, один ключ → один результат | Тот же реальный PG16 |
| Нельзя UPDATE/DELETE UAT plan/result/receipt | PostgreSQL immutability negative tests |
| Direct SQL forged org/source/formula SHA and foreign/mismatched receipt | PostgreSQL 063 negative tests |
| React real Chromium: source → explicit formula → plan + SHA replay → new revision | `mg-r04-immutable-metric-uat.spec.ts` |

**Проверенный базовый ядровой gate:** [MG-R0.4 11/11 PostgreSQL16 PASS](https://github.com/alimbetov/fin-analyzer/actions/runs/44732665214) (SHA `723fbc38170a0b09b682fbcc324e74e7aa2532b2`). Дополнительная security-квалификация 13/13 и Chromium после UI/063 требуют отдельного зелёного run. **Не** приписывать первому 11/11 результатам проверку 063 или браузера до фактической приёмки.

## Незакрытые работы

1. **PG 13 и browser 1 green** на актуальном коде, включая исправление зависимости Rollup Linux в CI, если имеет место.
2. Independent build manifest/artifact SHA и manifest pin; сейчас есть только engine descriptor, не квалифицированный binary.
3. Explicit goal/indicator binding → executable F02 outputs без догадок. `NET_PROFIT` остаётся blocked.
4. C01/C02 настоящие version-pinned AS_OF calculation method contracts, затем общий plan adapter; C01 overdue включена в gross, не прибавляется как отдельный актив.
5. MG-R0.5: source row grain, period alignment, multiform formulas; production admission — отдельно только после S2 и security-review.
6. A05-R1.3 IFRS9 EIR, A05-R1.4 A01 payment allocation, P04/P09 DSCR — после финансовой квалификации, без ложной проводки в F03.

