# FIN MG-R0.2 — Explicit Goal / Indicator / Metric Binding Foundation

**Дата:** 2026-11-20  
**Ветка:** `feat/fin-form-library-standalone`  
**Статус:** `READ_ONLY_EDITORIAL_BINDING_UAT_PASS`, не финансовый S2  
**Проверенный SHA кода:** `e407ff80bd468476bae9fa4219e589fbb9c47345`  
**PostgreSQL 16:** https://github.com/alimbetov/fin-analyzer/actions/runs/44687267435 — 5 tests, 0 errors, 0 failures, 0 skipped.

## Архитектура

- Существующий навигатор из 7 целей, 42 моделей и 46 показателей сохраняется неизменным.
- Истинные физические поля и формулы берутся из `fin_lib.lib_metric_definition`, `fin_lib.lib_field_binding_contract`, `fin_lib.lib_metric_binding_exception`, `fin_calc.formula_contract_revision` и `Calc4DTypedDagCompiler`.
- Новая таблица `fin_calc.metric_indicator_binding_revision` (migration 057) — **append-only связи**, не второй реестр метрик. Содержит `goal_code`, `indicator_code`, `revision_number`, `target_metric_code`, `binding_kind`, `formula_revision_id` (для формул), `evidence_ref`, SHA и неизменно непродукционные статусы.
- Все links — `EDITORIAL_CANDIDATE_NOT_S2`; нет автоматического `CalcPlan`, создания показателей, financial S2 или выполнения арифметики.

## Выбранные реальные связи

| Бизнес-цель | Показатель | Связь | Корректный статус |
|---|---|---|---|
| BANK_FINANCING | NET_PROFIT | F02.netProfit — `FORM_DERIVED_CANDIDATE`, **не физическое поле** | `DERIVED_METHOD_NOT_AUTHORED`, blocked |
| BANK_FINANCING | CASH_END | F03.closingCash — типизированное физическое поле | `DIRECT_SOURCE_CANDIDATE_NOT_CALCULATED` |
| BANK_FINANCING | BALANCE_CHECK | Нет явного связывания | Возвращается пустой список кандидатов |

Реальная F02 содержит физические `netRevenue`, `cogs`, `operatingExpenses`. Текст `netRevenue - cogs - operatingExpenses` в старом реестре `FORM_DERIVED_CANDIDATE` **не принимается автоматически за Net Profit** и не превращается в исполнимую формулу. Это обнаружено и защищено отрицательным тестом. Нет выдуманных доходов, налогов или финансовых корректировок.

## API и проверка

- `GET /api/v1/metric-graph/indicator-bindings?goalCode=BANK_FINANCING&indicatorCode=NET_PROFIT` — точные версии и SHA связей.
- `GET /api/v1/metric-graph/indicator-bindings/{exactBindingRevisionId}` — явная редакционная связь плюс `MetricGraphResolver` target.
- `MetricIndicatorBindingPostgresqlTest.java` 5 PG16 тестов: NET_PROFIT derived blocked, CASH_END physical, отсутствие у BALANCE_CHECK, REST/неправильная цель, неизменяемость и forged SHA.
- Доказательство: https://github.com/alimbetov/fin-analyzer/actions/runs/44687267435 — 5/5 PASS.

## Следующий шаг

**MG-R0.3:** входной source availability resolver по конкретным `organizationId, projectId, scenarioId` и точным `fin_intake` revision IDs; самостоятельное выявление `MISSING_SOURCE / REQUIRED_INPUT / STALE_SOURCE`, с сохранением period/AS_OF/currency/unit/row grain. Отдельное review формулы чистой прибыли `NET_PROFIT` и её бухгалтерского периметра — без подмены `operatingResult`. Затем связывание A05: расчётная версия условий C05 → граф обслуживания долга P04 → источники CFADS → DSCR.

**Ограничение:** 154 числовые формы не становятся автоматически исполнимыми только из-за новых связей.
