# FIN — DATA-CALC-R2: технический аудит расчётного конвейера и методологий
**Дата:** 2026-11-20  
**Ветка:** `feat/fin-form-library-standalone`  
**Исходное ТЗ:** [FIN DATA-CALC-R1 — Calculation-First UAT TZ](../FIN-DATA-CALC-R1-CALCULATION-FIRST-UAT-TZ.md), коммит `9e5cdff3d8571bdcceb312857f29f3c21efd6d76`; фактический файл имеет 327 строк текста (328 строк с завершающим переводом).  
**Срез исходного аудита:** `5d2e5a640910483d47d8a76017324da1108a2b5d`; отдельная однострочная исправленная компиляция: `79c6e851f900a8eb91c05cc93600f1589f4327e8`.  
**Статус документа:** REVIEW / PLANNING EVIDENCE; не является результатом финансового E2E или S2.

## 1. Вывод
R1 верно определяет продуктовый результат: `React save → immutable intake revision → UAT calculate → explain → React`. Но новый финансовый материал (C01/C02, ECL, FIFO, EIR, IAS7, FCFF/FCFE, DSCR; A01–A06; 10 P0/P1 политик) требует пересобрать **порядок зависимостей**, а не множить SQL-функции или создавать параллельный расчётный движок.

Главный разрыв: зарегистрированная методика/тип поля/шифр SHA не доказывает способность рассчитать показатель **из реально сохранённой ревизии пользователя**. Потребуются типизированный snapshot, policy resolution, фактический граф входов/выходов и сверка между формами.

## 2. Подтверждено исходным кодом
| Предмет | Доказательство | Оценка |
|---|---|---|
| Формы и схемы | R1 baseline: 166 форм = 154 числовых/табличных + 12 EVIDENCE; `form-library/docs/FIN-R16-FIELD-METRIC-CONTRACT-COMPLETION.md`: 877 физических полей (793 исходных + 84 metadata) | Каталог существует; 154 financial E2E не доказаны |
| Канонический редактор | `FormDraftService.save/readRevision` (точная form revision, `organizationId/economicEntityId/businessScopeId/projectId/reportingBasis/dataSpace`) | Реальный writer/reader; нужен typed snapshot adapter |
| Другой writer | `ReportGridService` пишет `LibReportRevision` и `LibReportRevisionCell` в отдельную модель `fin_lib` | **Нельзя** утверждать, что всё уже пишется в `fin_intake`. Не добавлять третий writer; решить read-normalization и business ownership |
| F02 вычисления | `F02CalculationKernel`: 3 типа выходов, KZT/monthly/positive-масштабы/фиксированный synthetic scale | Есть математическое ядро; НЕ доказан frontend calculation from user's exact saved revision |
| TEST_ONLY assembly | `DefaultF02SourceFactAssembler` использует `F02_TEST_ONLY` | Pilot source boundary; не равен общей финансовой оценке |
| C01/C02 | `CalcAsOfDebtKernel`, `CalcC01C02AsOfJdbcService`, миграция `050` | AS_OF UAT logic есть, но executor требует `TEST_QUALIFIED`/`SYNTHETIC` FactSet |
| CALC-4D | `Calc4DUatPlanStore.lockActiveFactsets` и `Calc4DExactFactSetInputResolver`: `TEST_QUALIFIED` и `SYNTHETIC` | **Не** принимает произвольный `fin_intake` input. Нужен явно изолированный, pinned `FIN_INTAKE_UAT` source path без обхода production admission |
| Методики | Миграции `051–055`; `MethodologySelectionService`, `CalcApplicationContractRegistry`, `MethodologyPolicyDecisionRegistry` | Реестры/варианты/ограничения; отсутствует обязательная сквозная policy/variant/FactSet binding в пользовательском CalcPlan |
| React | `frontend/src/App.tsx`, `frontend/src/api.ts`: рабочее редактирование/сохранение; проверка не обнаружила Calculate/Explain API flow | Финансовый calculate UI/E2E не подтверждён |
| Baseline 154 | `docs/FIN-CALC154-FORM-STATUS.tsv` и `docs/FIN-CALC154-WAVE02-ALL154-EVIDENCE-WORKLIST.tsv` | Исторический статический реестр не равен вычисленным результатам; статусы должны стать evidence-driven per output |

## 3. Подтверждённый CI-blocker и corrective action
На примере workflows `44673857477` (Java21 H2), `44673857383` (policy PG16), `44673857464` (dynamic methods PG16) GitHub Actions logs содержат один и тот же Maven compiler failure:

`MethodologyPolicyDecisionRegistry.java:[81,7] 'var' is not allowed in a compound declaration`.

Вследствие отказа компиляции зависимые PostgreSQL/Chromium jobs не дошли до квалификационной проверки и имеют `failure`. Отдельно React typecheck/npm job в запуске `44673857408` завершился `success`, что **не** доказывает calculate browser E2E.

Исправление компиляционной ошибки внесено в `79c6e851f900a8eb91c05cc93600f1589f4327e8`: две Java local declarations разнесены на две строки. Любой новый запуск требует повторного просмотра Maven, Liquibase, PG16 и Playwright результатов; статус GREEN для HEAD не подразумевается.

**Следующий подтверждённый блокер после исправления main compilation:** в workflow `44674476290`/ `44674476402`/`44674476356` Maven уже прошёл основную компиляцию, но упал в `testCompile`: `CalcC01C02AsOfPostgresqlTest.java` незаконно вызывает package-private `Calc4DTypedDagCompiler.sha(String)` из другого пакета (7 usages). Fix `6143fda61bdfae60226c8d438e927f96ecba6220` заменил вызовы локальным SHA256-helper внутри теста, **не** расширяя production API. Новое CI с этим коммитом ещё требует подтверждения. Evidence: https://github.com/alimbetov/fin-analyzer/actions/runs/44674476290 .

Evidence: https://github.com/alimbetov/fin-analyzer/actions/runs/44673857477 ; https://github.com/alimbetov/fin-analyzer/actions/runs/44673857383 ; https://github.com/alimbetov/fin-analyzer/actions/runs/44673857464 .

## 4. Findings и приоритеты
| ID | Критичность | Подтверждение / риск | Требуемая работа |
|---|---|---|---|
| AUD-01 | P0 | На исходном срезе Java21 Maven не компилировался | Fix `79c6e851`; проверка **нового** HEAD в CI; не приписывать старый RED новой ревизии |
| AUD-02 | P0 | `ReportGridService` и `FormDraftService` используют разные revision модели | Contract-based read adapter, предельный owner одной source fact и immutable content pin |
| AUD-03 | P0 | CALC4D и AS_OF executors привязаны к `TEST_QUALIFIED/SYNTHETIC` | Разработать `fin_intake -> FIN_INTAKE_UAT` без записи unqualified в production `fin_fact` |
| AUD-04 | P0 | F02 пилот KZT/monthly/positive не универсален для 154 форм | Отдельные `period/currency/sign/decimal/missing` контракты с явными scope constraints |
| AUD-05 | P0 | React has no verified numeric calculation path | Reopen exact revision → calculate → explain → historical comparison, E2E real backend |
| AUD-06 | P0 | `051/054/055` store variants/policies/app candidates but not fully resolved execution binding | Policy precedence, immutable effective policy SHA and stale guards pinned in **UAT** plan |
| AUD-07 | P0 | Cross-form C01/C02/F03/P06, C05/P04/P09, C03/F02 and FCFF/FCFE relations not qualified | Typed `MetricDependency` edges with stock-vs-flow, grain, currency, timing, lineage and reconciliation |
| AUD-08 | P1 | No complete event allocation/loan cash flow/lot/component/revenue recognition/DCF source contracts | Implement appendices A01,A05,A03,A04,A02,A06 in dependency order; don't create duplicate forms |
| AUD-09 | P1 | Some policies are editorial; 0.5% materiality / 0.1% tolerance / 1000 amount examples are not normative | Require versioned explicit policy and proof; do not silently apply guessed defaults |
| AUD-10 | P1 | 154-form source and readiness matrices are catalog-oriented, potentially stale | Maintain form+metric scoped runtime/readiness matrix with status from test and evidence SHA, not prose |
| AUD-11 | P1 | Cross-form reconciliation must not silently invent missing data | Independent reconciliation metrics and typed `MISSING/INCOMPATIBLE/UNDEFINED/INVALID` diagnostics |
| AUD-12 | P2 | Performance and complex UI redesign compete with first cash-to-result E2E | Defer until functional baseline; no new HTAP engine, Atlas, cache writeback, IAM expansion as UAT prerequisites |

## 5. Financial method constraints to carry into every phase
- **C01/C02:** gross is post-settlement stock, before allowance, overdue is subset and amount is not days past due. Outstanding snapshot is not summed with reconstructed amount. ECL/write-off are separate accounting decisions.
- **A01/A05:** invoice/contract/installment+payment stable business key, changes, allocations, dated schedule; money vs time; contractual cash vs EIR accounting schedule never conflated.
- **C03/A03:** choose cost formula for same-nature-and-use class; FIFO/WAC vs specific identification according to eligibility, NRV independent evidence.
- **C04/A04:** component life, residual, impairment and revaluation events; no synthetic OCI assumption.
- **F02/C07:** recognized revenue not automatically equal cash/issued invoices; returns, discounts and variable consideration evidence.
- **F03/P03:** direct/indirect operating cashflow match for identical basis/perimeter/currency/period; include noncash and FX reconciliation.
- **P07/A06:** FCFF/WACC → Enterprise Value, FCFE/Ke → Equity Value; EV-to-equity bridge for comparability; date-aware discounting and scenario consistency.
- **P09:** covenant CFADS and cash debt service per *exact credit agreement*; analytic DSCR must never certify a covenant.
- **All:** financial output tracks applicability, reporting basis, asOf vs interval, data completeness, FX policy, rounding, policy revision, source and result SHA.
- Production S2 and qualified `fin_fact` remain separate; no UAT output may promote itself.

## 6. Audit disposition and owner decisions
**Техническое заключение:** текущий код — полезный framework/prototype, но **calculation-first from saved intake не доказан**. Следующая реализация должна сперва доказать F02, C01/C02 из пользовательских данных, не переписать 166 форм. Затем строить совместимые, проверяемые source domains и аналитические выходы. Формальный release gate — **financial correctness on pinned data**, а не число миграций/классов/покрытых экранов.

**Решения по умолчанию для разработки UAT:** 166 form identities unchanged; immutable source and result snapshots; IFRS и management раздельно; точные версионированные, явно выбранные методики; отсутствующее значение не равно нулю; missing не превращается в fabricated number. Это архитектурные ограничения UAT, не финансовая квалификация S2.

**Следующее:** [FIN DATA-CALC-R2 — Cross-form Calculation Roadmap](../FIN-DATA-CALC-R2-CROSS-FORM-CALCULATION-ROADMAP.md).
