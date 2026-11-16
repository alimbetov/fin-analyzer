# FIN-DIAG-60 — детализированное ТЗ внедрения и тестовой квалификации

**Версия:** 1.0 · **Дата:** 2026-11-16 · **Статус:** `PROPOSED / NOT IMPLEMENTED BY THIS DOCUMENT`  
**Репозиторий:** `alimbetov/fin-analyzer` · **единственная рабочая ветка:** `feat/fin-r0-r1a` · `main` не изменять.  
**Назначение:** исполняемое задание для разработчика/Codex, экономиста-методолога, независимого S2 Reviewer и QA.

> Это ТЗ, а не свидетельство готовности. Все примеры ниже **полностью синтетические**, не отражают реальные компании, финансовое заключение или утверждение банковских нормативов. Mock fixtures предназначены исключительно для unit/integration/E2E и должны быть явно помечены `SYNTHETIC`. Использование их для production-анализов запрещено.

## 1. Исходные факты и ограничения внедрения

На момент подготовки проверены `backend/pom.xml`, `.github/workflows/ci.yml`, `docs/FIN-FORM-CATALOG-FOUNDATION-V2.md`, `docs/FIN-DIAGNOSTIC-COMPASS-R1.md`, `docs/FIN-IND-R0-R1-STATUS.md` и `backend/DIAGNOSTICS-R2-SCENARIOS.md`. Backend уже **Java 21**, **Spring Boot 3.5.7**, PostgreSQL/Flyway/JPA/Testcontainers; Tribuo **4.3.2 test scope**. Каталог: **166** верхних форм = 44 F/C/P/T/D + 59 DF + 63 SF; **30** дополнительных EX-листов, **42** ранее определённые модели, **21** сектор, **64** бизнес-модели и **10** сценариев. Требуется инвентаризация текущих версий каждого ресурса перед изменением.

Существующие `ScenarioEconomicsEngine`, `CrossFormDiagnosticEngine`, `DiagnosticCompass`, `DiagnosticSheetCompass`, React `SectorStudio`, локальный `.fin.json` и связанный XLSX-процесс **переиспользовать**, не заменять вторыми конкурирующими реализациями. Отдельно фиксировать их статус и ограничения; наличие кода/каталожного smoke не равно `METHOD_APPROVED`. Нельзя доверять client-supplied `APPROVED`, `organizationId` и источникам `ACTUAL` без server-side verification.

## 2. Цель и пользовательские сценарии

Целевой процесс: **пользовательская проблема → выбор бизнес-модели → применимость методов → минимальные запрошенные формы → Excel или React → проверка/сверка → утверждение ревизии → расчёт → diagnostic findings → проверка гипотез → сценарные альтернативы → отчёт/проверка эффекта**.

SC-01 ИП: где деньги? SC-02 ТОО: почему снизилась прибыль? SC-03 CFO: изменения за 3–5 лет; SC-04 торговля: продажи/запасы/ABC–XYZ; SC-05 производство: НЗП/брак/отклонения; SC-06 строительство: BoQ/EVM/отклонения; SC-07 бюджет: plan/actual и rolling cash; SC-08 инвестиции: NPV/DSCR/чувствительность; SC-09 кредитный пакет: данные для кредитора, без «одобрения»; SC-10 прогноз: compare baseline vs ML. Во всех сценариях отразить источники, границы применимости и `NOT_COMPUTABLE`.

## 3. Архитектурные требования

1. **Модульный монолит Java 21**, не 60 сервисов. Сохранять существующие Maven/pom и package boundaries; предложенные названия пакетов не являются предписанием массового рефакторинга.
2. `Canonical EconomicFact`: `organizationId, legalEntityId, factCode, dimension, periodStart/End, stockOrFlow, reportingBasis, currency, unit, scale, value(BigDecimal string), valueState, sourceForm, sourceField, sourceRevision, approvalRevision, timestamps`. `MISSING != EXPLICIT_ZERO != NOT_APPLICABLE != ESTIMATED`.
3. `SourcePriority + ReconciliationRule + FactSetRevision` обязаны предотвращать повторное суммирование F02/DF-SAL/SF-G01, F03/DF-CSH, бюджетных итогов и детализации. Исправления только новой ревизией. Старые AnalysisSnapshot не переименовываются в актуальные.
4. `MethodologyDefinition`: code/version, group, methodType, RU/KK/EN/ZH names/descriptions, required/optional facts, grain, min periods, units, currency policy, formula/algorithm version, assumptions, applicability, finding rules, golden fixture IDs, qualification status, expert sign-off.
5. `MethodologyExecutor` контракт: `validate(context)`, `requiredEvidence(context)`, `execute(context)`, `explain(result)`. Для качественных методов `execute` возвращает структурированные экспертные свидетельства/оценки, **не выдуманную числовую формулу**.
6. `MethodologyContext` должен ссылаться на immutable `FactSetRevision`, trust context и ABAC scope; `MethodologyResult` — status, metrics, warnings, missing inputs, source refs, calculation trace, versions, snapshot ID.
7. Статусы `DESIGN_ONLY → IMPLEMENTED_UNQUALIFIED → TESTED → METHOD_APPROVED → RELEASE_QUALIFIED`, дополнительно `SUSPENDED`. `COMPUTED` отдельный технический статус, не заменяющий квалификацию.
8. Методы расчёта: `DETERMINISTIC_NUMERIC`, `STATISTICAL`, `FORECAST`, `ML_PREDICTION`, `OPTIMIZATION`, `STRUCTURED_QUALITATIVE`, `COMPOSITE`. Financial arithmetic = `BigDecimal` + явно утверждённый `MathContext`; статистические double и sampling только в отдельном обозначенном контуре.
9. Derived rows/EX-листы не предлагаются пользователю как обязательный ручной ввод. Компас группирует нужные поля по одной родительской форме, объясняет `PROVIDE, APPROVE_DRAFT, CORRECT, REFRESH, DERIVE`.
10. Multi-tenant: Keycloak JWT/roles — только идентификация, разрешения server-owned ABAC по организации, полю, периоду, действию, версии, документу; проверки PostgreSQL tenancy, отсутствие утечки через кэши/ошибки/модели.
11. React: выбор цели, методика и её данные, черновик/подтверждение, объяснение, 4 языка RU/KK/EN/ZH, графики + доступная таблица, отчёт с теми же числами; `DESIGN_ONLY` не выдаётся за доступный калькулятор.
12. Offline локальный `.fin.json` сохраняется как отдельный явно локальный сценарий; серверное утверждение ACTUAL не эквивалентно локальному пользовательскому подтверждению.

## 4. Полный реестр 60 методик с привязкой данных и обязательными проверками

Приведённые пути к формам — **кандидаты на сопоставление**, а не утверждённые коды полей. Для каждого метода реализовать `MethodologyApplicability` и точный `FormField → EconomicFact` mapping, утвердить используемый granularity и исключить двойной учёт. `DF-SAL`, `DF-OPS` и `EX-*` здесь обозначают семейства, точные коды должны быть взяты из текущего реестра, а не созданы по догадке.

| Код | Метод | Источники-кандидаты | Нужные данные | Обязательная проверка |
|---|---|---|---|---|
| FIN-01 | Horizontal | F01/F02 | Периоды, сумма | разность и процент; нулевая база NOT_COMPUTABLE |
| FIN-02 | Vertical | F01/F02 | Статьи, итог | сумма долей и сверка с агрегатами |
| FIN-03 | Trend | F02/DF-CTX-03 | 3–5 сопоставимых лет | CAGR только при допустимой базе |
| FIN-04 | Ratios | F01/F02/F03 | Активы, обязательства, прибыль | положительные знаменатели; period/basis |
| FIN-05 | Factor | F02/DF-SAL | цена, объем, ассортимент | сходимость факторного разложения |
| FIN-06 | Index | F02/DF-SAL | цены и объемы по периодам | база, агрегирование, состав |
| FIN-07 | DuPont | F01/F02 | прибыль, продажи, средние активы, капитал | ROE decomposition equality |
| FIN-08 | Cash Flow | F03/DF-CSH | поступления, платежи, остатки | opening+net=closing |
| CON-01 | Pareto | DF-SAL/SF-G01 | положительный вклад по объектам | stable sort, cumulative 100% |
| CON-02 | ABC | DF-SAL/SF-G01 | Pareto доли | versioned boundaries; ties |
| CON-03 | XYZ | DF-SAL/SF-G01 | сопоставимый месячный ряд | CV, sample/population policy, missing |
| CON-04 | ABC–XYZ | DF-SAL/SF-G01 | ABC и XYZ одного scope | 9 классов и lineage |
| CON-05 | HHI | DF-SAL/C01 | положительные доли объектов | сумма долей=1, HHI 0..1 |
| CON-06 | Gini/Lorenz | DF-SAL/C01 | неотрицательное распределение | инвариант к перестановке |
| PRO-01 | CVP | F02/C08 | цена, переменные и постоянные затраты | сопоставимость объема и маржи |
| PRO-02 | Break-even | F02/C08 | CM/unit, fixed costs | округление вверх для дискретных единиц |
| PRO-03 | Contribution Margin | F02/DF-SAL | net sales, variable cost | CM=net sales−variable cost |
| PRO-04 | Unit Economics | DF-SAL/SF-G01 | выручка/единица, полные затраты | граница cost allocation |
| PRO-05 | ABC Costing | SF-C02/EX-MFG | cost pools, drivers | 100% allocation reconciliation |
| PRO-06 | Standard Cost Variance | SF-C02/EX-MFG | standard/actual qty, rates | sign policy and bridge |
| CASH-01 | CCC | F01/F02/C01/C02 | avg AR/inventory/AP, revenue/COGS | day-count basis and cash cycle |
| CASH-02 | Aging | C01/C02 | срок, долг, оплаты | bucket partition and overdue date |
| CASH-03 | 13-week Forecast | DF-CSH/C01/C02 | opening, due dates, commitments | weekly balance rollforward |
| CASH-04 | Working Capital | F01/C01/C02 | current items | double-count and non-operating exclusions |
| CASH-05 | Cash Stress | F03/P03 | shock assumptions, cash flow | scenario isolation |
| CASH-06 | Liquidity Gap | F03/C01/C02 | dated flows, available liquidity | time bucket gap |
| BUD-01 | Plan/Actual | C09/EX-BUD-02 | approved budget and actual | amount %, variance sign |
| BUD-02 | Flexible Budget | C09/EX-BUD | volume-adjusted driver rates | reconciliation at actual volume |
| BUD-03 | Rolling Forecast | P01-P03/C09 | actuals, horizon, forecast versions | no future leakage |
| BUD-04 | Driver-Based | P01-P03/DF-OPS | approved business drivers | driver lineage |
| BUD-05 | Zero-Based | P01/C09 | approved activities, justification | human approval; no fake KPI |
| BUD-06 | Cost Centers | C09/EX-BUD | centers, allocation keys | sum center totals equals whole |
| INV-01 | NPV/XNPV | P07/EX-INV | dated cashflows, rates | date basis, capital basis, zero-rate |
| INV-02 | IRR/MIRR | P07 | flows, reinvestment/funding rates | multiple IRR roots => warning |
| INV-03 | DCF | P07/P08 | FCFF/FCFE, discount rate, terminal | cashflow/discount matching |
| INV-04 | WACC | P04/P07 | capital weights, rates, tax policy | weight sum and debt/eq cost |
| INV-05 | Sensitivity | P07/P08 | base scenario, parameters | one-at-time and ceteris paribus |
| INV-06 | Scenarios | P03/P07/P08 | isolated alternative assumptions | no historical mutation |
| INV-07 | Monte Carlo | P07/P08 | distributions, dependency, seed | reproducible seeded runs |
| INV-08 | DSCR | P09 | CFADS, interest, principal | bank-policy profile; DS >0 |
| OPS-01 | EOQ | SF-G03/DF-OPS | annual demand, order/storage cost | cost assumptions and units |
| OPS-02 | Safety Stock | SF-G03/DF-OPS | lead time and demand distribution | service level assumptions |
| OPS-03 | OEE | SF-C03/DF-OPS | availability, performance, quality | 0..1 component bounds |
| OPS-04 | TOC | SF-C01/SF-C02 | capacities, throughput, constraints | validated bottleneck |
| OPS-05 | Value Stream Map | DF-OPS | events, step durations | human-validated process map |
| OPS-06 | Yield/Scrap | SF-C02/EX-MFG | input, good, scrap, rework | conservation of physical units |
| QLT-01 | Ishikawa | DF-OPS | hypotheses, evidence | no automatic causal conclusion |
| QLT-02 | 5 Whys | DF-OPS | investigation steps | reviewed explanation chain |
| QLT-03 | FMEA | DF-OPS | failure modes, severity, occurrence | configurable risk method; review |
| QLT-04 | SPC | DF-OPS | subgroups, measurement cadence | control vs specification limits |
| QLT-05 | PDCA | C09/DF-OPS | action plan, before-after KPI | baseline and outcome lineage |
| QLT-06 | Benchmarking | F01/F02 | comparable reference cohorts | basis, period, provenance |
| STR-01 | SWOT | P08/T* | reviewed qualitative evidence | no fabricated numeric score |
| STR-02 | PESTEL | P08/T* | dated external evidence | validity and citation |
| STR-03 | Porter 5 Forces | P08/T* | competitive landscape evidence | human judgement audit trail |
| STR-04 | BSC | P01/P08 | goals, KPI, owner, targets | target vs observed status |
| ADV-01 | Time-Series Forecast | F02/DF-SAL | ordered time series | rolling-origin backtest |
| ADV-02 | Regression/Drivers | F02/C09/DF-SAL | observations, features | leakage and collinearity |
| ADV-03 | Optimization | P01/DF-OPS | objective, constraints, costs | feasibility and optimality gap |
| ADV-04 | MCDA | P08 | criteria, weights, alternatives | normalized weights, sensitivity |

**Принцип 60/60:** код считается реализованным только при подтверждённом исполнительном контракте, применимости, тестах, trace, UI и проверке методолога. Для составных семей (Ratios, NPV/XNPV, IRR/MIRR, SPC, forecasting) необходимы отдельные дочерние алгоритмы и их тесты. QUALITATIVE (SWOT/PESTEL/Porter/Ishikawa/PDCA) требует workflow и происхождения доказательств, а не фиктивного KPI.

## 5. Структуры тестовых данных

Тестовые fixtures хранить под `backend/src/test/resources/fin/method60/`; браузерные — под `tests/fixtures/method60/` или аналогичным существующим каталогом. Ожидаемые результаты задать **независимыми** от production-расчётчика файлами `expected.json`/CSV и ручной спецификацией формулы; запрещено генерировать expected тем же Java-кодом, который тестируется.

Обязательные поля fixture manifest:

```json
{
  "fixtureId": "M60-CON-001",
  "dataClass": "SYNTHETIC",
  "organizationId": "synthetic-tenant-a",
  "reportingBasis": "SEPARATE",
  "currency": "KZT",
  "scale": "ONE",
  "periods": ["2026-01", "2026-02"],
  "approvalState": "APPROVED_TEST_ONLY",
  "sourceRevisions": ["fixture-rev-001"],
  "methodologyVersions": {"CON-01": "1.0.0"},
  "expectedFile": "M60-CON-001.expected.json"
}
```

`APPROVED_TEST_ONLY` является исключительно тестовым типом/фикстурной меткой и **не должен** приниматься публичным API как разрешение на ACTUAL. Во всех моках синтетический tenant, страна/валюта, дата, scale, единица и источник заданы явно.

### F-01. Balance / P&L / Cash — финансовые тождества

Синтетический баланс (тыс. KZT, SEPARATE, 2025-12-31):
- currentAssets=400, nonCurrentAssets=600, assets=1000;
- currentLiabilities=200, longTermLiabilities=300, liabilities=500, equity=500.
- **Expected:** assets=liabilities+equity=1000; currentRatio=400/200=**2.0000**.

Отрицательная копия: assets=1001, liabilities=500, equity=500 → **BLOCKER** (расхождение 1 выше фикстурного нулевого tolerance); `APPROVED` запрещён. Другие негативные: currentLiabilities=0 → Current Ratio `NOT_COMPUTABLE`, а не Infinity; NULL numerator → missing, а не 0; KZT vs THOUSANDS_KZT без конверсии → BLOCKER.

### F-02. Pareto / ABC / HHI

Синтетическая выручка по четырём SKU одного периода, KZT: `A=500, B=300, C=150, D=50`; сумма **1000**. Expected sorted shares: **50%, 30%, 15%, 5%**; cumulative **50%, 80%, 95%, 100%**. При политике `ABC_BOUNDARY_V1` (A включает минимальный префикс, достигающий 80%, B — минимальный следующий префикс до 95%, C — остаток): классы **A,A,B,C**. HHI долей 0..1: `0.5²+0.3²+0.15²+0.05²=0.365` (или 3650 при шкале 10000, явно указывать шкалу).

Negative: отрицательные/нулевые чистые вклады не должны автоматически участвовать в неотрицательной классификации; возвраты моделируются отдельной политикой; all-zero denominator → `NOT_COMPUTABLE`; tie ordering детерминированный по `objectCode`.

### F-03. XYZ — временной ряд

12 месяцев потребления (шт.) `SKU-X: [100,100,100,100,100,100,100,100,100,100,100,100]`. Mean=100, population sd=0, CV=0%, class X при пилотных порогах X≤10%, Y≤25%, Z>25%. Сохранить явно выбранную политику `POPULATION_SD` и годовую полноту 12/12.

Два иных искусственных ряда:
- `SKU-Y: [80,120,80,120,80,120,80,120,80,120,80,120]` → mean=100; population sd=20; CV=20%; **Y**.
- `SKU-Z: [50,150,50,150,50,150,50,150,50,150,50,150]` → mean=100; sd=50; CV=50%; **Z**.

Negative: 11 из 12 месяцев при требовании 12 → `INSUFFICIENT_DATA`, не усреднять молча; mean=0 → `NOT_COMPUTABLE`; тренд/сезонность должны показываться как ограничения интерпретации. Численные пороги — **предлагаемая пилотная политика**, не норматив.

### F-04. CVP / Contribution / Break-even

Цена 1000 KZT/шт, variable cost 600 KZT/шт, fixed costs 80000 KZT/мес. Expected contribution/unit=400, break-even=**200 шт**, revenue at break-even=**200000 KZT**. При 300 шт: contribution=120000; operating contribution after fixed=**40000 KZT**. Negative: price<=variable cost при positive fixed => `NO_FINITE_BREAK_EVEN`; non-comparable units/periods BLOCKER. Изменение цены/объёма в scenario не меняет historical facts.

### F-05. Working capital / CCC

Синтетика за **360-дневный расчётный год** (day-count policy FIXED_360): average AR=120, revenue=720, average inventory=90, COGS=540, average AP=60, purchases denominator=540 **как явное пилотное допущение**. Expected DSO=60 days; DIO=60; DPO=40; CCC=**80 days**. Для реального отчёта валидировать источник cost/purchases и политику средних остатков. Negative: annual revenue=0 → DSO `NOT_COMPUTABLE`; mixed stock/flow periods → BLOCKER.

### F-06. 13-week cash roll-forward

Opening available cash=100 KZT, week1 receipts=70, week1 payments=90 → week1 closing=**80**. Week2 receipts=40, payments=100 → closing=**20**. Week3 receipts=20, payments=50 → closing=**−10**, liquidity gap **10** до минимального нуля. Weeks 4–13 содержат отдельные явно заданные сценарные строки (можно нулевые только если `EXPLICIT_ZERO`, но не отсутствующие). Negative: restricted cash нельзя включать в available; неподтверждённая оплата не становится guaranteed cash.

### F-07. Budget vs actual

Budget cost 100 KZT, actual cost 115 KZT → absolute variance `Actual−Budget=+15`, relative variance=**15%**. Знак `+15` не означает «хорошо»: для expense превышение бюджета является кандидатом неблагоприятного сигнала; для revenue та же арифметика обычно интерпретируется иначе. Negative: budget=0 → относительное `NOT_COMPUTABLE`; unapproved budget → reject.

### F-08. Investment NPV (annual periods)

Initial outflow −1000 KZT at t0, inflow 600 KZT at t1 and 600 KZT at t2, rate 10% annual. Expected NPV = `−1000 + 600/1.1 + 600/(1.1^2)` = **41.3223140495868 KZT** (comparison within documented scale/rounding tolerance). This is equal-period NPV fixture; **not** XNPV and not dated ACT/365. Negative: 10% nominal cashflows with real rate → reject; FCFF/CostOfEquity mismatch → reject; multi-root IRR → explicit warning.

### F-09. Manufacturing physical yield

Input equivalent units 1000, good output 900, scrap 100, rework 0 → yield **90%**, scrap **10%**; conservation 900+100+0=1000. Negative: output=1100 without recorded approved addition => BLOCKER. Wrong kg/piece conversion => BLOCKER until conversion policy exists.

### F-10. Cross-form double count

Approved F02 revenue=1000 KZT, DF-SAL detailed lines=600+400 KZT, SF-G01 retail revenue=1000 KZT referencing same transactions. Expected canonical revenue **1000**, not 3000. On DF-SAL=990, F02=1000 => `SOURCE_CONFLICT` or documented reconciliation, not silent override.

### F-11. Access and revision

Tenant A factSet version1 revenue=1000; tenant B revenue=777; A user requests B analysis => **403**, no details in payload, timing and logs. Tenant A edits draft to 1100 but old approved version1 remains 1000; until approval run against version1 = 1000. After authoritative approval, version2 = 1100, new run new snapshot; historical snapshot unchanged, stale marking explicit. Forged client `approved=true` => reject.

### F-12. ML time-series synthetic proof

Generate fixed, source-controlled **36 monthly observations**, e.g. `y(t)=100+2t+s(t)`, t=0..35, `s(t)=[0,10,0,-10]` repeated. First 24 months training, next 6 validation, last 6 **untouched** holdout, with rolling-origin evaluation also required; never shuffle timestamps. Source code of generator, CSV, seed, version and expected baseline values committed. Compare last-value naive, seasonal naive, simple baseline and Tribuo; XGBoost optional gated. **No fixed accuracy superiority claim** from this artificially linear pattern. Expected: no leakage, repeatable predictions within numeric tolerance, all metrics finite, automatic promotion prohibited if ML is not better on documented decision-relevant loss. Show sample size and uncertainty limitations.

### F-13. Quality / strategy qualitative mocks

`Ishikawa`: synthetic problem «увеличился брак», 3 hypotheses with links to observed defects, one `UNVERIFIED`, one `REVIEWED`, one `REJECTED`; no causal conclusion without supporting evidence. `SWOT`: 4 categories, references, dated reviewer and provenance; no numerical business-health score. `FMEA`: severity/occurrence/detectability scales and risk scoring defined by versioned local policy, no universal threshold asserted.

### F-14. Property/metamorphic tests

- Permutation of SKU rows does not affect Pareto shares/HHI (except stable deterministic tie order).
- Multiplying all monetary stock and flow amounts by 1000 with consistent scale does not change dimensionless ratios.
- Aggregating properly approved detailed rows equals the canonical total once, not twice.
- Adding an unrelated tenant does not affect another tenant's report.
- React/Excel/API same approved inputs produce identical canonical fingerprints and numerically equivalent results (with explicitly allowed rendering rounding).
- Future period facts are not visible to models predicting earlier periods.
- Missing is not coerced to zero in any method.
- Changes in method version create a new analysis snapshot; previous result remains reproducible.

## 6. ML/optimization — технология и контракты

**Primary Java ML:** Tribuo 4.3.2 currently test-only; move to production scope **only after** separate ML pilot and approved benchmark. **Candidates:** XGBoost4J for tabular boosting, ONNX Runtime for approved imported inference, Commons Math/EJML for statistics/matrix operations, Timefold for bounded constraint optimization (not a predictive ML model). Confirm Java21, Spring Boot 3.5.7, Linux x64 and macOS ARM64 compatibility, licenses, native dependencies, BOM conflicts, memory and reproducibility. Do not pin newer versions without actual dependency test.

Suggested `FeatureRegistry`, `DatasetManifest`, `TrainingRun`, `ModelRegistry`, `PredictionRun`, `ModelEvaluation`, `DriftAssessment` contain versioned lineage, `availableAt` to prevent future leakage, and tenant scope. States `EXPERIMENTAL → BACKTESTED → SHADOW → ADVISORY`, with `SUSPENDED` on drift or insufficient quality.

Tasks: 1 revenue/demand forecast, 2 cash forecast, 3 unusual cost/sales detection, 4 overdue probability **only if labeled outcomes exist**, 5 segmentation, 6 cost drivers (statistical association not causal proof), 7 separate operations optimization. Metrics: MAE/RMSE/MASE/WAPE for forecasts (protect zero denominators); bias/interval coverage; precision/recall/PR-AUC/calibration for classification; false alarm rate and review cost for anomalies. Use rolling-origin temporal backtesting and untouched holdout; compare simple baselines; no automatic user-facing `ACTUAL` model recommendation without evaluation and qualification.

## 7. API and React journey (target, check existing routes before implementing)

Target API (reuse established routes if equivalent):
- `GET /api/v1/organizations/{orgId}/methodologies` — applicability, per-method status and missing evidence.
- `GET /api/v1/methodologies/{code}/versions/{version}` — passport (no sensitive source data).
- `POST /api/v1/organizations/{orgId}/analysis-runs` — request with approved source revision, selected methods, idempotency key.
- `GET /api/v1/organizations/{orgId}/analysis-runs/{runId}` — immutable result, findings, provenance, warnings and quality.
- `GET /api/v1/organizations/{orgId}/analysis-runs/{runId}/report` — versioned report snapshot.
- `GET /api/v1/organizations/{orgId}/compass` — ranked minimal next evidence; without duplicate form prompts.

Frontend: choose objective, show required evidence and form download under report type, editable React fields, upload controlled XLSX, validation, explicit user confirmation, method selection/applicability, results charts + accessible table, diagnostics, missingness radar, side-by-side scenarios, RU/KK/EN/ZH report. Desktop Chromium/WebKit acceptance; never invent screenshots or present mock data as actual company metrics.

## 8. Delivery plan and dependencies

| Stage | Scope | Acceptance gate |
|---|---|---|
| R0 — Baseline audit | Inventory 42 models, 60 methods, 166/30, duplicate code, runtime, existing tests | Matrix with exact classes/files/current status; no invented IMPLEMENTED |
| R1 — Methodology registry | 60 definitions, version, passport, applicability, mapping placeholders marked DESIGN_ONLY | Contract and negative registry tests; codes resolve |
| R2 — Trusted facts | Form→Fact, approvals, anti-duplication, unit/currency/period, revisions | F-01/F-10/F-11 PostgreSQL tests PASS |
| R3 — Finance + concentration | FIN-01..08, CON-01..06 | F-01/F-02/F-03/F-14 + manual review + React |
| R4 — Profit/cash/budget | PRO-01..06, CASH-01..06, BUD-01..06 | F-04..F-07, 13wk, approved budget and time-series E2E |
| R5 — Investment/operations | INV-01..08, OPS-01..06 | F-08/F-09 and independent quantitative/currency tests |
| R6 — Quality/strategy/advanced | QLT-01..06, STR-01..04, ADV-01..04 | Structured reviews + F-12/F-13 + optimization feasibility |
| R7 — ML proof | Baselines, Tribuo, optional library spikes, holdout, shadow | No ML promotion without measured value |
| R8 — Full qualification | All 60 completed, explainability, XSLX/React parity, reports, language, ABAC | One SHA GREEN + independent S2 + manual MacBook UAT + release gates |

R3–R6 comprise **14 + 18 + 14 + 14 = 60**, not an extra 60 plus ML. Some families require more than one executable algorithm. Work in **existing branch only**, no merge `main` until explicitly authorized.

## 9. Required test matrix per method

Each method has at least: `METH-OK` synthetic golden case; `METH-ZERO` numerator/denominator boundary; `METH-MISSING` missing/NA/explicit zero; `METH-NEG` negative and sign convention; `METH-UNITS` incompatible units/currency/scale; `METH-PERIOD` inconsistent stock/flow or calendar; `METH-REV` immutable revision/version; `METH-AUTH` other tenant/field/period access; `METH-TRACE` source rows and formula lineage; `METH-UI` React/Excel/report equality. For qualitative methods, replace irrelevant arithmetic cases with versioned review/provenance/contradiction tests and record exemption with reason. More granular children need separate golden fixtures; do not declare tests «PASS» until executed.

Tests:
- Java JUnit 5 + Testcontainers/PostgreSQL **real DB**, migrations + tenant-isolation tests;
- Vitest + fixed-point `sector-pilot` regression;
- Playwright Chromium Linux, macOS Chromium/WebKit + screenshots/traces;
- XLSX tamper/macro/external formulas/ZIP budget, no stale draft override;
- Internationalization labels, precision, accessibility;
- ML temporal walk-forward with independent holdout, seeded reproducibility, resource limits;
- documentation lint and registry reconciliation (60 methods, 166 forms, 30 sheets; do not confuse registration with execution).

CI evidence: **one commit SHA**, exact GitHub Actions run URLs, all relevant job conclusions, independent reviewer signature. `README` claims must match results. No «GREEN» by assumption. If a dependent component is missing, mark `BLOCKED` and record precise gap and reproducer.

## 10. Definition of Done

- 60/60 method passports registered; for each code explicit `executionType`, approved field mapping and applicability.
- 60/60 methods and required child variants **functionally implemented and independently qualified**, not just catalogued.
- Each method has positive/negative mock datasets, zero/missing/unit/period/revision/security tests or documented justified exemptions for qualitative methods.
- Core finance, concentration, profitability, cash, budget, investment, manufacturing, quality and strategy workflows produce accurate explainable results from approved data.
- ML models never silently overwrite facts; prediction shows baseline and evaluation; optional algorithms stay disabled until qualified.
- React and XLSX provide consistent values; available methods not falsely advertised.
- Generated reports include source/period/version/assumptions, distinguish historical actual from scenarios and predictions.
- Independent S2 finance/security review, functional UAT and all required CI jobs GREEN **on the same SHA**.
- Production rollout only with feature flags and verified authoritative access controls; if not achieved, release remains `HOLD`.

## 11. Exact deliverables in repository

1. `docs/FIN-DIAG-60-IMPLEMENTATION-TZ.md` (this document).
2. `docs/FIN-DIAG-60-IMPLEMENTATION-MATRIX.md` — audited 60× forms/methods, source class path, supported grain, status, gap and PR/CI references.
3. `backend/src/main/resources/fin/methodology/methodology-registry-v1.*` — machine-readable registry, exact codes and schema.
4. `backend/src/test/resources/fin/method60/` — manifests, synthetic fixtures, independent expected results, negative fixtures.
5. Java contract and integration tests with exact traceability to METH cases.
6. React/E2E fixtures and browser evidence.
7. `docs/FIN-DIAG-60-QUALIFICATION-REPORT.md` — SHA, method coverage, tests and remaining HOLD conditions.

**Ограничение настоящего коммита:** этот файл фиксирует детальное ТЗ с примерами mock datasets и ожидаемых результатов; он **не создаёт сами JSON/CSV-fixtures, исходный код, тесты или CI-подтверждение**. Их создание относится к R0/R1 и последующим этапам.


## 12. Автоматический оркестратор и совокупный анализ

Обязательное дополнение к программе FIN-DIAG-60: [FIN-DIAG-60-AUTO-ORCHESTRATION-TZ.md](FIN-DIAG-60-AUTO-ORCHESTRATION-TZ.md). Документ определяет автоматическую применимость методик, dependency DAG, инкрементальный пересчёт по утверждённым ревизиям, идемпотентные задания, единый совокупный отчёт, ML/симуляции по политике ресурсоёмкости, 30-дневный TTL для временных данных и 18 синтетических приёмочных сценариев. Публикация отчётов вовне — только после отдельного подтверждения владельцем. До реализации и квалификации — `DESIGN_ONLY`.
