# FIN Project Marketplace — отраслевые пилоты: торговля, услуги, производство

**FIN-SECTOR-PILOT-GMC v1.0** · 2026-11-15
**Branch:** docs/fin-marketplace-requirements-baseline
**Engineering state:** 9 form calculations IMPLEMENTED; automated CI tests available.
**Product state:** NOT UAT ACCEPTED, NOT INDEPENDENTLY METHODOLOGY APPROVED, NOT RELEASED.

## 1. Что реализовано и что остаётся

Реализовано самостоятельное расчётное ядро: packages/sector-pilot/src/index.mjs. Оно вычисляет девять отраслевых KPI/показателей для SF-G01, SF-G02, SF-G03, SF-M01, SF-M02, SF-S01, SF-C01, SF-C02, SF-C03, возвращает sourceRevision и статус расчёта. Тесты: packages/sector-pilot/tests/pilot.test.mjs, fixture: packages/sector-pilot/examples/pilot.json, CI: .github/workflows/sector-pilot.yml. Node.js 22+ без внешних runtime dependencies.

В существующем FIN-R1A отдельный React/Vite F01. **Отраслевое ядро в React не интегрировано**, нет экранов, master data, PostgreSQL, Spring Boot, OAuth/Keycloak, Redis, RustFS, KumoMTA, Excel/CSV import, настоящих tenant grants. Установленный в тестовых данных флаг approved=true не подтверждает юридически полномочия и не заменяет серверную проверку.

Из 63 описанных SF-форм код реализован для девяти. Остальные 54 — только SPECIFIED. Исполняемые математические реализации не являются независимым утверждением отраслевой методологии.

## 2. Методические контракты девяти формул

| Форма | Код результата | Формула | Единица | Правило за квартал / полугодие / год |
|---|---|---|---|---|
| SF-G01 | SKU_GROSS_PROFIT | netRevenue − cogs | currency | sum(netRevenue) − sum(cogs) |
| SF-G01 | SKU_GROSS_MARGIN_PCT | 100 × (netRevenue − cogs) / netRevenue | percent | 100 × (sum(netRevenue) − sum(cogs)) / sum(netRevenue) |
| SF-G02 | SKU_STOCK_TURN_PERIOD | periodCogs / avgStockValue | turns/window | sum(periodCogs) / day-weighted mean(avgStockValue) |
| SF-G03 | LANDED_UNIT_COST | (purchasePrice × receivedQty + freight) / receivedQty | currency/unit | sum(purchasePrice × quantity + freight) / sum(quantity) |
| SF-M01 | BILLABLE_UTILIZATION_PCT | 100 × billableHours / availableHours | percent | 100 × sum(billableHours)/sum(availableHours) |
| SF-M02 | ENGAGEMENT_CONTRIBUTION | recognizedFee − (directHours × hourCost) − otherCost | currency | суммировать отдельно рассчитанные месячные contributions |
| SF-S01 | SERVICE_UNIT_MARGIN | (netRevenue − directLaborCost − materialsCost)/completedJobs | currency/job | sum(contribution)/sum(completedJobs) |
| SF-C01 | GOOD_YIELD_PCT | 100 × goodUnits / unitsStarted | percent | 100 × sum(goodUnits)/sum(unitsStarted) |
| SF-C02 | UNIT_MFG_COST | (materialUsed + directLabor + overhead)/goodUnits | currency/unit | sum(totalManufacturingCost)/sum(goodUnits) |
| SF-C03 | EQUIPMENT_UTILIZATION_PCT | 100 × runHours / plannedHours | percent | 100 × sum(runHours)/sum(plannedHours) |

Итого 9 форм, 10 расчётных значений (SF-G01 даёт два). Все числовые значения — decimal strings, фиксированная BigInt шкала 6 разрядов, вывод округляется до 2 знаков half-away-from-zero. Нет потери точности из-за JavaScript Number при финансовом расчёте. Для zero denominator — NOT_COMPUTABLE без NaN; для missing/null — INCOMPLETE, а не 0.

**Отраслевые ограничения:**
- Торговля: netRevenue и COGS должны иметь один scope и currency; возвраты не дублируются. SF-G02 принимает именно СРЕДНИЙ остаток запасов за месяц, не конечный; квартальный оборот не annualized.
- Услуги: billableHours <= availableHours; признанный fee ≠ поступление денег. В SF-M02 laborCost рассчитывается по каждой месячной ставке, а не как произведение суммарных часов и ставок. SERVICE_UNIT_MARGIN — денежный вклад на completed job, не коэффициент прибыли предприятия.
- Производство: goodUnits <= unitsStarted; загрузка runHours <= plannedHours; reworkedUnits не суммируются с good как отдельный выпуск, накладные требуют единой policy. UTILIZATION не является полноценным OEE.

## 3. Периодичность и восстановление значений

| Период | Источник | Coverage gate |
|---|---|---|
| MONTH | один утверждённый снимок за YYYY-MM по businessId/form/dimension/currency | все обязательные поля известны или explicit zero |
| QUARTER | 3 календарных месяца | иначе INCOMPLETE, missingPeriods перечисляются |
| HALF_YEAR | 6 месяцев | иначе INCOMPLETE |
| YEAR | 12 месяцев | иначе INCOMPLETE |

Ratios вычисляются из суммы числителей и знаменателей, а НЕ средним процентов. Годовая и полугодовая сводки не требуют нового ручного ввода, если есть месячные данные. Окно не смешивает организации, dimension или currencies. Calendar/fiscal-period rules за пределами календарного года, FX conversions, late-arrival revisions и управляемое хранение исторических источников остаются отдельными требованиями.

## 4. Реальные golden fixtures (синтетические, не данные бизнеса)

| ID | Условия | Ожидаемый результат |
|---|---|---|
| RET-01 | январь revenue 100, COGS 90 | gross margin 10.00% |
| RET-02 | февраль revenue 900, COGS 450 | gross margin 50.00% |
| RET-03 | март revenue 0, COGS 0 | monthly ratio NOT_COMPUTABLE |
| RET-04 | Q1 совокупно revenue 1000, COGS 540 | gross margin 46.00%, gross profit 460.00 |
| RET-05 | месячный COGS 100/200/300; average inventory 50/100/150 | Q1 turnover 6.00/window |
| RET-06 | 10 шт. ×12 + freight20, затем 90 шт. ×10 | Jan landed 14.00; Q1 10.40 |
| SRV-01 | billable/available 60/100, 180/300, 0/100 | Q1 48.00% |
| SRV-02 | fee 1000/1500, труд 10×20/15×30, прочие 100/200 | Q1 contribution 1550.00 |
| SRV-03 | доход 1000, труд 200, материалы 300, 10 работ | unit margin 50.00 |
| MFG-01 | годная продукция 90/450 при запуске 100/900 | Q1 yield 54.00% |
| MFG-02 | суммарные затраты 1100+5000, годных 90+450 | Q1 unit cost 11.30 |
| MFG-03 | run/planned 80/100,180/300,0/0 | Q1 utilization 65.00% |
| YEAR-01 | 12 месяцев revenue100, COGS50 | year gross profit 600.00, margin 50.00% |
| YEAR-02 | 12 месяцев billable3/available10 | year utilization 30.00% |
| YEAR-03 | 12 месяцев manufacturing cost20, good4 | year unit cost 5.00 |

Важный числовой пример: gross margin 10% при revenue100 и 50% при revenue900 нельзя усреднять в 30%. Корректная квартальная маржа = 46%.

## 5. Запуск, доказательства и DoD

Из каталога packages/sector-pilot: npm test; npm run demo. Node.js 22+, без npm install. Workflow публикует отдельный job для библиотеки.

**Подтверждение первых 9 тестов:** GitHub Actions run #43698444427 — SUCCESS на SHA fcda42a7b2a27502362b3bb5eccfddf7f2ea2777. Дополнительно введены 2 теста half-year/year, проверка должна быть привязана к их новому SHA и отдельному run.

| Gate | Критерий | Состояние |
|---|---|---|
| PILOT-R0 | 9 форм, fixed point, базовые golden/negative tests | IMPLEMENTED; initial 9 tests CI PASS |
| PILOT-R1 | 6/12 months, missing periods, zero denominator | IMPLEMENTED; new CI evidence required |
| PILOT-R2 | RU/KK/EN имена и MethodRegistry с независимым review | NOT DONE |
| PILOT-R3 | React UI Wizard → Form data → Diagnosis | NOT DONE |
| PILOT-R4 | Controlled Excel/CSV, provenance, revisions, duplicate staging | NOT DONE |
| PILOT-R5 | Spring Boot/PostgreSQL/Keycloak/Access Center | NOT DONE |
| PILOT-R6 | независимый S2, пользователи, Desktop UAT | NOT DONE |

**Методический статус:** METHOD_CANDIDATE_IMPLEMENTED. Для APPROVED_METHOD нужен independent reviewer, formula definition AST, validity scope, unit, precision, source/sector evidence, negative fixtures и RU/KK/EN перевод «что считает / как считает / что показывает». Пилот не должен отображать новый KPI как одобренный, отраслевую норму или финансово-аудиторское заключение до этого.

**Следующий технический шаг:** совместить этот независимый exact-decimal domain code с FND-04 Canonical Facts, FND-05 FormRegistry и FND-08 Methodology Registry. Затем React пилот на согласованной интеграционной ветке после ревью F01, а не механический перенос кода в документационную ветку.

## 6. Полный воспроизводимый квартальный пример

Файл packages/sector-pilot/examples/full-quarter.json содержит **27 утверждённых только в тестовом смысле строк**: по три календарных месяца и девять отраслевых форм. Команда npm run demo:quarter выводит все расчёты с указанием компании, ресурса, версии источника, периода, CALCULATED или INCOMPLETE. Данные вымышленные и не должны использоваться для реального бизнеса.

