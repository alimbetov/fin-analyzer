# FIN CALC-A05-R1 — Loan Contract & Payment Schedule Studio
## Техническое задание на интеграцию с FIN DATA-CALC-R2

**Дата:** 2026-11-20  
**Ветка:** `feat/fin-form-library-standalone`  
**Статус:** `SPEC_PROPOSED_FOR_APPROVAL`; это ТЗ, не отчёт о выполненной реализации  
**Приоритет:** после фактической приёмки `DC-R2.4` и `DC-R2.5`; финансовое S2/production admission остаются закрыты  
**Версия:** `A05-R1-TZ-V1`  
**Владелец предметной области:** C05, связь с A01, P04, P09 и F01/F02/F03  
**Технологический контур:** Spring Boot / Java 21 / BigDecimal / PostgreSQL 16 / JDBC / Liquibase / React + TypeScript / Testcontainers / Playwright.

### 0. Решение и границы

FIN получает **одно расчётное приложение CALC-A05** поверх 166 существующих форм, а не новую главную форму, второе финансовое ядро или независимый набор финансовых данных. A05 обслуживает кредитный договор / заём и формирует согласованные *договорный денежный график*, *бухгалтерский график эффективной ставки* и *план-факт*; при необходимости — самостоятельный локализованный расчёт *ГЭСВ РК*, **не равный IFRS EIR**.

**Единый источник истины:** C05 — экономический смысл, стабильная ссылка на договор, исходная версия условий и факты; вложенные типизированные регистры A05 — точные даты, правила, комиссии и события, принадлежащие **той же авторитетной ревизии** `fin_intake`. Новый источник должен записываться в том же transactional save boundary; **запрещено** создавать отдельный параллельный writer, который может разойтись с C05. `P04/P09` — вычисляемые read-only проекции и сверки; их ручные `ROWSET`-данные, если они существуют, нельзя незаметно перезаписывать.

**Разделить три процентных понятия:**

1. `NOMINAL_CONTRACT_RATE` — договорная ставка для начисления денежных процентов и построения графика по day-count/accrual policy.
2. `IFRS9_EIR` — бухгалтерская эффективная ставка для амортизированной стоимости финансового инструмента при применимости IFRS 9; состав денежных потоков и комиссии зависят от квалифицированной учётной политики.
3. `KZ_GESV` — регуляторная годовая эффективная ставка вознаграждения для **подпадающих под соответствующие правила РК** договоров, с отдельными действующей редакцией НПА, датой применимости, перечнем платежей и алгоритмом. Реализация после независимой правовой/финансовой квалификации, а не как синоним IFRS9 EIR.

`ANNUITY / EQUAL_PRINCIPAL / CUSTOM` — способы **погашения**, а не альтернативы номинальной/EIR/ГЭСВ. Их выбирать в отдельных измерениях, а не в одном enum.

### 1. Основание и проверка соответствия проекту

| Существующий объект | Фактическое состояние на аудируемой ветке | Что делает A05 |
|---|---|---|
| Каталог `C05` | `ROWSET`; `creditor, agreementRef, currency, outstanding, interestRate, rateType, dueAt, collateralRef` (schema candidate) | Сохраняет текущий код формы/поля и связывает каждую строку с `loanBusinessKey`; расширяет **дочерние** контракты условий, не меняет смысл `dueAt` |
| `P04` | `ROWSET`; `agreementRef, period, openingPrincipal, drawdown, principalRepayment, interest, fees, closingPrincipal` | Получает подтверждённую исходным графиком проекцию; отличие manual vs derived явно показывать |
| `P09` | `ROWSET`; `period, cfadsSourceRef, debtServiceSourceRef, methodologyRef, exclusionsRef` | Получает version-pinned debt service из A05; CFADS/ковенант определяются отдельно |
| `F01/F02/F03` | Канонические формы баланса, результата и денежных потоков | Различать долг, EIR-расходы и **фактический** cash flow; плановые платежи не зачислять в F03 как проведённые |
| `DF-CSH-06` / `DF-CSH-02` | Детализированные обязательства / платёжные события | Источники для связи договорного графика и фактических платежей |
| `CALC-A05` migration `054` | `SCHEMA_CANDIDATE`: набор возможных входов; не executable методика | Новые schema revisions и зависимости добавляются **additive**; не менять задним числом `054` |
| `P0-5` / `P0-7` migration `055` | Редакционные решения: договорные денежные платежи отдельно от IFRS EIR; covenant DSCR по договору | Применить через version-pinned policy binding; редакционное согласование НЕ равно финансовому S2 |
| `FinancialMethodologyMath.annualEirSynthetic` | Работает только с равными ежегодными интервалами; исследовательский UAT helper | **Не использовать** для нерегулярного графика/плавающей ставки/модификаций; разработать датированный EIR solver |
| `FinIntakeUatSourceAdapter` / `INGEST-2` | Точное чтение ревизии с повторной SHA проверкой | Тот же source pin, schema hash, row/census hash, project/organization isolation |
| `DC-R2.4` / `DC-R2.5` | PG16 `C01/C02` E2E присутствует; общий backend `UatCalculationPreview`; `UatCalculationPanel` существует, но ещё не подключён в `App.tsx` по аудиту | Старт A05 только после реального зелёного UI gate R2.5; не выдавать наличие компонента за готовый путь |

**Ссылки внутри репозитория:** `docs/FIN-DATA-CALC-R2-CROSS-FORM-CALCULATION-ROADMAP.md`, `docs/FIN-CALC-APPS-R0-SIX-APPLICATION-CONTRACTS-AND-ROADMAP.md`, `docs/FIN-CALC154-P0-P1-EDITORIAL-POLICIES-AND-IMPLEMENTATION-V1.md`, миграции `051`/`054`/`055`.

### 2. Пользовательские сценарии

- **UC-01 — Банк/кредитор:** экономист создаёт договор на базе C05, вводит сумму/срок/ставку, видит черновик датированного графика без сохранения результата в `fin_fact`.
- **UC-02 — Комиссии:** пользователь добавляет несколько независимых `LoanFeeRule` с фиксированной суммой, процентом от выдачи/лимита/остатка, единовременной или периодической частотой, выбором `UPFRONT_PAID / WITHHELD_FROM_DISBURSEMENT / FINANCED / PAID_ON_SCHEDULE`.
- **UC-03 — Варианты:** сравнивает аннуитет, равные доли основного долга, произвольный график, не смешивая разные параметры/календарь/дату среза.
- **UC-04 — Досрочное погашение:** создаёт событие на дату; выбирает сокращение срока или суммы платежа; исходная редакция графика остаётся доступной.
- **UC-05 — Изменение ставки и отсрочка:** фиксирует `RateResetEvent` / `GracePeriodEvent`, получает **новую** редакцию расчёта с указанной датой вступления.
- **UC-06 — IFRS9:** при допустимости и подтверждённой политике рассчитывает отдельную амортизированную стоимость и EIR; сервисные комиссии не попадают туда без соответствующей классификации.
- **UC-07 — План-факт:** импортирует/привязывает подтверждённый `PaymentEvent`; проверяет остаток и просрочку через A01, не вычитая платёж из готового остатка C05 второй раз.
- **UC-08 — Отчётность:** раскрывает P04 и P09 с прямыми ссылками на расчётный график, проверяет F01/F02/F03 по соответствующей учётной базе, экспортирует паспорт расчёта.
- **UC-09 — Устойчивость:** закрывает браузер, открывает старую ревизию, воспроизводит *точно тот же* расчёт без подмены новой ставкой или комиссией.

### 3. Модель и версии данных

В реализации использовать типизированные PostgreSQL-строки для сумм/дат/связей; JSONB допускается только для ограниченных параметров конкретного метода.

| Сущность / проекция | Минимальные поля и смысл |
|---|---|
| `LoanIdentity` | `organizationId, economicEntityId, projectId, businessScopeId, borrowerRef, creditorRef, agreementRef, loanBusinessKey`; уникальность в точном business scope; код C05/row UUID не подменяет юридический ключ договора |
| `LoanContractRevision` | `id, parentSourceRevisionId, agreementRef, effectiveFrom, currency, principalOrLimit, contractDate, firstAccrualDate, maturityDate, repaymentMethod, frequency, revisionReason, evidenceRef, sourceSha` |
| `LoanDrawdownEvent` | `loanContractRevisionId, eventId, disbursementDate, amount, actual/planned, cashDirection, externalEventKey`; R1.1 — одна выдача, несколько траншей — расширение с теми же контрактами |
| `LoanRateRuleRevision` | `rateKind FIXED/FLOATING/STEP, nominalAnnualRate, referenceIndexRevisionId, spread, floor/cap, resetPolicy, effectiveRange, accrualDayCount, compoundingRule` |
| `LoanFeeRuleRevision` | `feeCode, feeType, amount/rate, calculationBasis, paymentFrequency, eventDateRule, withheld/financed flag, payer/payee, taxTreatmentRef, ifrsEirClassification, kzGesvClassification, policyRevisionId` |
| `LoanCalendarRuleRevision` | `paymentFrequency, dueDay, EOM-policy, shiftRule, calendarRevisionId, accrualDateEffect, interestRoundingScale, paymentRoundingMode, finalResidualRule` |
| `LoanScheduleRevision` | immutable `contractRevisionId + calculationPlanPin + variantSHA + inputRevisionSha + algorithmBuildSha + asOf + resultSha`; один договор может иметь много вариантов, изменение не переписывает исходный |
| `LoanScheduleLine` | `lineId, scheduleRevisionId, installmentNo, accrualFrom, accrualTo, contractualDueAt, actualAdjustedDueAt, openingPrincipal, drawdowns, contractualInterest, principalDue, feeDueByType, closingPrincipal, totalCashDue, raw/rounded values` |
| `LoanModificationEvent` | `eventId, eventType, effectiveDate, priorRevisionId, changedTerms, eventSourceSha, reason, evidenceRef`; модификация не меняет прошлые строки и требует отдельного правила пересчёта |
| `LoanPaymentEvent` | `paymentId, externalEventKey, paymentEffectiveAt, amount, currency, valueDate, confirmationState, bankEventRef, sourceRevisionId`; дубликаты по внешнему идентификатору отсекаются |
| `LoanPaymentAllocation` | `paymentId, installmentId, componentCode, allocatedAmount, allocationPolicyRevisionId, allocationSha`; решение A01, не часть формулы EIR |
| `LoanEirScheduleRevision` | `eirPolicyRevisionId, eligibleFeeSetRevisionId, amortizedCostOpening, eir, accruedEirExpense, amortizedCostClosing, modificationGainLossCandidate, sourceSha` |
| `LoanCovenantBinding` | `loanRevisionId, covenantDefinitionRevisionId, cfadsSourceRevisionId, debtServiceComponents, threshold, testPeriod, exclusions`; без этого covenant PASS запрещён |
| `LoanGesvRevision` | отдельная **будущая** таблица/проекция: `jurisdiction, regulatoryRuleRevision, effectiveAt, eligiblePayments, annualizedRate, evidenceSha`; не входить автоматически в IFRS расчёт |

Сущности выше — **целевые контракты**, физические таблицы на текущем этапе НЕ созданы. В `A05-R1.0` нужно предложить additive Liquibase migration после `055`, разрешив только новые версии schema и UAT. Исторические миграции не редактировать.

**Атомарность:** строки `LoanContractRevision/LoanFeeRule...` являются частью канонической `fin_intake`-ревизии (или её typed sidecar по тому же revisionId) и сохраняются транзакционно с C05; никаких «сохранить договор отдельно, потом обновить C05» без общего receipt. `fin_calc` хранит только планы, результаты и представления; `fin_fact` продолжает самостоятельную финансовую квалификацию.

### 4. Контракты вводимых полей, правила обязательности

Минимальные поля `REQUIRED` для базового графика: юрисдикция/тип договора, `loanBusinessKey`, валюта, точная сумма выдачи, дата выдачи, дата начала начисления, дата/условие окончания, частота и порядок дат платежей, `repaymentMethod`, вид ставки, значение ставки, `dayCountConvention`, `businessDayAdjustment`, `roundingPolicyRevision`, `reportingBasis`, точный `sourceRevisionId`.

Поля `CONDITIONAL`:
- `FLOATING` → индекс, ревизия наблюдаемых значений, spread/reset и правила недоступного индекса.
- `CUSTOM` → полный список договорных платежей с датами/компонентами и остатком.
- `FINANCED_FEE` → правило добавления суммы к основному долгу/момент капитализации; `WITHHELD` → net drawdown.
- `EIR` → подтверждённая IFRS 9 классификация, eligible fees/transaction costs, датированные cash flows, basis/perimeter и `EirPolicyRevision`.
- `KZ_GESV` → применимость закона по типу продукта/лицу/дате, список включаемых платежей и нормативная редакция.
- `COVENANT_DSCR` → утверждённое договорное CFADS/debt service, exclusions и threshold.

Поля `OPTIONAL`: залог, комментарии, доказательства, прочие платёжные расходы, аналитическая сравнимая ставка. `NULL`, `BLANK`, 0 и `NOT_APPLICABLE` различаются. Отсутствующая ставка/дата/тип комиссии никогда не предполагается равной 0.

### 5. Параметры расчётов и версии методик

Не делать один enum `METHOD=A05` со значениями «аннуитет» и «EIR». Создать независимые узлы выбора:

- `RepaymentMethod`: `ANNUITY, EQUAL_PRINCIPAL, CUSTOM`. `BULLET` — расширение, не требование для MVP.
- `RateBasis`: `FIXED, FLOATING, STEP`; базовый MVP — FIXED, остальные по условиям R1.2.
- `AccrualConvention`: `ACT_365_FIXED, ACT_360, THIRTY_E_360, NOMINAL_PERIODIC`. Последнее разрешено **только** при зафиксированной договором периодической ставке/частоте; не подменять им ACT/365.
- `Compounding`: simple/compound только по точному договору; non-default `COMPOUND` не применять автоматически к просрочке или к обычному проценту.
- `FeeRuleType`: `FIXED, PERCENT_OF_DISBURSEMENT, PERCENT_OF_ORIGINAL, PERCENT_OF_OUTSTANDING, PERCENT_OF_LIMIT, RECURRING_FIXED`.
- `FeeSettlement`: `UPFRONT_PAID, WITHHELD, FINANCED, RECURRING, AT_MATURITY`; значение базы и дата оценки обязательны.
- `PrepaymentStrategy`: `REDUCE_TERM, REDUCE_INSTALLMENT, CUSTOM_RESCHEDULE`; контрактное право/штраф — отдельные правила.
- `GracePolicy`: `PRINCIPAL_ONLY, INTEREST_DEFERRED, FULL_DEFERRED` с отдельной политикой капитализации процентов.
- `AccountingBasis`: `CONTRACTUAL_CASH, IFRS_AMORTISED_COST, MANAGEMENT` — разные виды результата; не смешивать ledger accrual с cashflow.
- `DscrPurpose`: `ANALYTICAL, CONTRACT_COVENANT`. Значения второго недоступны без версии подтверждённого определения.
- `GesvRegime`: `NOT_APPLICABLE` или идентификатор нормативной редакции РК; регулируемая оценка отдельная.

Параметры выбираются по `SYSTEM -> ORGANIZATION -> PROJECT -> LOAN_CONTRACT -> SCENARIO` только в области применимости; **правила обязательной бухгалтерской классификации и применимости закона не могут быть отменены нижним уровнем**. Запрещать resolve latest во время исполнения: фиксировать конкретные `policyRevisionId, variantRevisionId, feeRuleRevisionId, rateRuleRevisionId, calendarRevisionId`, SHA параметров и build SHA в immutable `CalcPlan`.

### 6. Математические алгоритмы и инварианты

**Договорный график:**
- `accruedInterest_k = openingPrincipal_k × nominalAnnualRateApplicable_k × yearFraction(accrualFrom, accrualTo, dayCountRule)` — для простого начисления, без капитализации. Для фиксированной `NOMINAL_PERIODIC` отдельный `periodRate`, не выводить автоматически dayCount из календаря.
- Для фиксированной равнопериодной аннуитетной модели с однозначной `periodRate=r` и `n` платежами: `PMT=P·r/(1−(1+r)^(−n))`, при `r=0` → `PMT=P/n`. При нестандартных датах, плавающей ставке, комиссиях в составе платежа и изменениях условий использовать **датированный cash-flow solver** и явную политику перерасчёта, а не эту короткую формулу.
- Равные доли основного долга: `principalDue_k=initialPrincipal/n` с корректировкой финального остатка; `interestDue_k` из дневной базы.
- `openingPrincipal + drawdowns + explicitlyFinancedAmounts − principalDue − extraPrincipal = closingPrincipal`. Все суммы и строки — без отрицательных амортизационных остатков, если договор не допускает соответствующий overdraft/credit line.
- `totalContractualCashDue = principalDue + contractualInterestDue + independentlyScheduledFeeDue + otherContractualCashDue`. Включение каждой комиссии в `debtService` является **выбором договора/ковенанта**, а не универсальной формулой.
- `raw BigDecimal` и rounded display/payment rows раздельно; FX, scale, rounding mode и final adjustment берутся **из ревизии политики**; сумма начисленного principal и финального остатка сверяется до минимальной допустимой единицы валюты.

**Договорная просрочка:**  
`plannedDueAt`/`adjustedDueAt` + `PaymentAllocation` → непогашенная сумма по компонентам и дата среза; `daysPastDue=max(0, asOf−effectiveDueAt)` — **дни**, не сумма денег. `overdueAmount` суммирует только непогашенные наступившие обязательства и не переиспользует исходный `C05.outstanding` как ещё один долг. Случай `dueAt=asOf` трактуется ровно по версии граничного правила, не по скрытой системной константе.

**Бухгалтерский EIR:**  
Строить отдельный поток IFRS-relevant `initialNetCarrying` + **датированные** будущие cashflows с классифицированными интегральными комиссиями/затратами, решать DCF equation в высокой точности по зафиксированной временной базе и признаку направления потока. Проверять существование/единственность допустимого корня, ограничения итераций, residual и численную устойчивость; `MULTIPLE_ROOTS/NO_ROOT/NON_CONVERGENCE` — диагностические состояния. `annualEirSynthetic` из текущего Java-кода **не подходит** для нерегулярных дат, rate reset и пересмотра условий. Модификация задолженности и прекращение признания — отдельное бухгалтерское решение, не просто новый график с новой EIR.

**ГЭСВ РК:**  
Может использовать другой состав платежей, сроки и правила округления, чем IFRS EIR; до выбора нормативной редакции и applicability заявлять «ГЭСВ по законодательству» запрещено. В качестве исследованной нормативной основы для применимых банковских продуктов присутствует постановление АРРФР № 44 от 03.04.2026: https://adilet.zan.kz/rus/docs/V2600038333 ; старое постановление № 137 отмечено утратившим силу: https://adilet.zan.kz/rus/docs/V1200007663/info . На 2026-11-20 также существует **проект** последующих правил: https://www.gov.kz/memleket/entities/ardfm/documents/details/1069636?lang=ru — проект **не** исполнять как действующее правило. Профиль KZ_GESV в MVP — `NOT_IMPLEMENTED/REVIEW_REQUIRED`.

### 7. Взаимосвязи форм и контроль финансовой непротиворечивости

| Контроль | Источник → назначение | Обязательная проверка |
|---|---|---|
| `A05-REC-01` | C05 source ↔ LoanContractRevision | agreementRef, principal/limit, outstanding, sourceRow UUID, валюта, reportingBasis и точный revision SHA; отличать лимит/выдано/остаток |
| `A05-REC-02` | A05 schedule ↔ P04 | `opening + drawdowns + capitalized fees − principal paid = closing` по каждому периоду и нарастающе; ручной P04 сравнивать как отдельный источник, не затирать |
| `A05-REC-03` | A05 interest ↔ F02 | `contractualInterestCash` ≠ `IFRS EIR expense` по умолчанию; отдельный basis-specific bridge |
| `A05-REC-04` | A05 booked liability ↔ F01 | бухгалтерский остаток на дату и начисления/модификации из EIR roll-forward, а не автоматическое равенство principal outstanding |
| `A05-REC-05` | A05 scheduled cash ↔ F03/P03 | график = план, **не** фактический `F03`; в F03 только подтверждённые реальные cash events с правильной классификацией |
| `A05-REC-06` | A05 installments ↔ A01 ↔ C01/C02 | event allocation, просрочка, частичное погашение и исправления; не смешивать кредитное обязательство с торговой кредиторской задолженностью |
| `A05-REC-07` | A05 contractual debt service ↔ P09 | debt service определяется договором; CFADS — версия отдельного источника, `COVENANT_PASS` только после квалификации определения |
| `A05-REC-08` | fee rules ↔ funding/cash/EIR | комиссия не должна попасть дважды в основной долг, денежный поток или EIR; financed vs withheld vs paid проверяются раздельно |
| `A05-REC-09` | A05 revisions | все перерасчёты имеют отдельную `scheduleRevision`, исходная ревизия воспроизводима bit-for-bit по decimal/model SHA |

Никакая C01/C02 просрочка не считается независимо доказанной, пока A01 не связал факт оплаты с конкретной срочной обязанностью. Модель A05 должна обеспечивать эти ключи, **не** дублировать сам settlement engine A01.

### 8. API и обработка, без нового независимого writer

**Командный ввод:** расширить существующий workflow сохранения редакции `FormDraftService` / `fin_intake` типизированной прикладной секцией A05; требуются `schemaRevision`, `expectedRevision` и idempotency token. В пределах одной транзакции сохраняются C05 и её A05-детализация, затем подтверждается exact `revisionId`. **Нельзя** открывать внешнюю запись договора по отдельному `POST /loans` с жизненным циклом, независимым от C05.

**Предлагаемые UAT read/preview контракты (новые, ещё не реализованы):**

`POST /api/v1/uat/calculations/a05/schedule/preview`  
`{organizationId, projectId, formCode:"C05", exactSourceRevisionId, loanBusinessKey, requestedVariantRevisionId, policyRevisionIds[], scenarioRevisionId?}`

`GET /api/v1/uat/calculations/a05/schedules/{scheduleRevisionId}` — вернуть только сохранённый immutable UAT result, если такой persistence gate одобрен; иначе `NOT_IMPLEMENTED`, не подменять latest.

`POST /api/v1/uat/calculations/a05/reconcile/preview` — exact P04/P09/F01/F02/F03/A01 references and result per check. При отсутствии источников честное `MISSING_SOURCE`/`NOT_COMPARABLE`.

Возвращаемый контракт: `sourceRevisionId, sourceContentSha256, scheduleRevisionId?, methodVariantRevisionId, policyRevisions, resultSha256, unit, currency, reportingBasis, period/asOf, scheduleLines[], feeBreakdown[], totals, diagnostics[], reconciliation[], executionState, financiallyApproved:false, productionAdmitted:false`. Отдельные DTO `RateRules/FeeRules` не передавать произвольными SQL/Java expression.

Backend: bounded PostgreSQL aggregation/joins/lineage + Java21 BigDecimal / MathContext для датированных графиков, процентных и EIR решателей; при наличии двух реализаций — SQL/Java parity. UI **не** рассчитывает сумму платежа своими формулами.

### 9. Экран React «C05 / Кредит»

Основная карточка C05 по существующему route, дополнительный контекст `Loan Business Key`. Вложенные вкладки:
- **Договор**: стороны, сумма/лимит/транши, сроки, валюта, принадлежность к проекту/сценарию.
- **Ставки и календарь**: фикс./плавающая/ступенчатая, day-count, даты, праздники, EOM, rounding policy.
- **Комиссии и расходы**: таблица правил с классификацией денежного и бухгалтерского влияния, признаки удержания/финансирования/периодичности.
- **График платежей**: редактируемые *входные условия* и read-only результат, график по каждой дате с principal/interest/fees/total/closing balance.
- **Изменения и досрочное погашение**: amendment event, предыдущая и новая ревизии, влияние на срок/платёж.
- **EIR / стоимость финансирования**: отдельный график IFRS EIR, итоговые затраты и отдельный блок будущей ГЭСВ (статус `NOT_IMPLEMENTED` пока не квалифицирован).
- **План-факт / Просрочка**: A01 payment matching; при отсутствии фактических проводок — `NO_PAYMENT_FACTS`, не выдумывать исполнение.
- **Сверки / Explain / История**: source IDs/SHA, method/policy build, residual, reconciliation results, сравнение двух ревизий с идентичной базой.

Мобильная компоновка, клавиатура, ошибки несохранённых данных, валюта, локализация RU/KZ/EN (ZH при наличии каталожной локализации) должны следовать существующему Form Studio. Кнопка «Пересчитать» отключается при незаписанной/непроверенной ревизии и неопределённых обязательных полях.

### 10. Этапы и инженерные задачи

| Этап | Объём | Минимальный критерий GO |
|---|---|---|
| **A05-R1.0 — Domain & Input** | Атомарное сохранение C05 + typed contract/rate/fee/calendar rules; versioned metadata, SHA, source readback, UI вкладки ввода | 1 договор, 2 комиссии (разовая и ежемесячная), contract revision, day-count, повторное чтение SHA, duplicate agreement key rejected |
| **A05-R1.1 — Contract Schedule** | ANNUITY, EQUAL_PRINCIPAL, CUSTOM, date-aware accrual, нетиповые даты, source/output SHA, row persistence/read | 3 независимых golden, нулевая ставка, остаток 0 при окончании, сумма principal = drawdowns + financed fee, exact reproduction |
| **A05-R1.2 — Modifications** | Досрочный платёж, снижение платежа/срока, отсрочка, reset плавающей ставки и календарь; immutable versions | Одна исходная и две изменённые ревизии, replay старой без drift, correct outstanding/cash for modification date |
| **A05-R1.3 — Accounting EIR** | IFRS EIR solver по датированным cash flows; классификация комиссии, отдельный accounting schedule и проверка изменения условий | Независимая проверка PV residual, correct EIR vs nominal vs cash, no-root/nonconvergence cases, no false financial S2 |
| **A05-R1.4 — PLAN/FACT + Reports** | A01 actual settlement, P04 projection, P09 analytic/covenant split, F01/F02/F03 ties, cross-form explain | 9 controls A05-REC, no double count, covenants cannot PASS without agreement, planned != booked |
| **A05-R1.5 — PG16 & Browser** | Testcontainers PG16, two revision replay, negative tests, snapshot/plan SHA, Playwright real backend and responsive UX | All mandatory tests GREEN, 0 skipped, code SHA and evidence run pinned, signed/immutable UAT receipt and rollout separately gated |

**Dependency entry:** `DC-R2.4 PG16` и `DC-R2.5 React/Chromium` должны быть GREEN **на соответствующем commit**; `CALC-A01` может реализовываться параллельно, но `A05-R1.4` блокируется до его интеграции. Не назначать финансово-квалифицированный EIR / covenant на основании технического теста.

**Разделение задач:**
- `A05-JAVA-01` date+dayCount+interest; `A05-JAVA-02` repayment strategies; `A05-JAVA-03` fee generation and priority; `A05-JAVA-04` event modifications; `A05-JAVA-05` dated EIR numerical solver.
- `A05-DB-01` canonical input sidecar; `A05-DB-02` immutable schedule/line; `A05-DB-03` payment event+allocation references; `A05-DB-04` negative constraints and SHA readback.
- `A05-API-01` preview; `A05-API-02` explanation/history; `A05-UI-01` terms/fees/calendar; `A05-UI-02` schedule and compare; `A05-INT-01` P04/P09; `A05-INT-02` A01 and F01/F02/F03.
- `A05-QA-01` independent golden; `A05-QA-02` PG16 concurrency/idempotency; `A05-QA-03` Playwright and export; `A05-QA-04` methodology evidence matrix.

### 11. Контрольные примеры и отрицательная приёмка

**GOLDEN-01 (нулевая ставка, остаток):** 1 000.00 KZT, 0%, три платежа, EQUAL_PRINCIPAL, последовательно 333.33 / 333.33 / 333.34 KZT при принятой политике округления 2 знака/последней корректировки; комиссия 10.00 KZT отдельным разовым `UPFRONT_PAID` не меняет principal. Сумма principal = 1 000.00; closing principal = 0.00. Этот пример **технический UAT**, не универсальное правило округления для KZT.

**GOLDEN-02 (аннуитет):** 1 000.00 KZT, два **равных договорных периода** с `periodRate=1%`, ANNUITY, без комиссий и изменений. Не применять к ACT/365, пока не задан набор точных дат. Контроль: PMT из формулы, каждая строка roll-forward, последний residual выравнивается договорной политикой; независимые контрольные значения хранить в TSV с expected raw/rounded.

**GOLDEN-03 (IFRS EIR, исследовательский):** initial carrying 980.00, пять годовых cashflows 80,80,80,80,1080; EIR ≈ 8.5076328113%. Датированный EIR solver принимает эту серию как граничный равногодовой вариант; EIR interest expense ≠ contractual cash coupon, коэффициенты и корень проверяются независимо. Полная корректность IFRS classification не выводится из совпадения числа.

**GOLDEN-04 (досрочное погашение):** ранняя выплата на exact date; два mode `REDUCE_TERM` и `REDUCE_INSTALLMENT`; оба имеют независимые golden графики, фиксированные fee consequences и source SHA; старый результат не изменяется.

**GOLDEN-05 (плавающая ставка):** rate reset в середине начислительного интервала; сумма процентов разделяется по effective subperiods, индекс берётся из точной pinned revision; при отсутствии индекса `MISSING_REFERENCE_RATE`.

**GOLDEN-06 (комиссии):** upfront withheld 100.00, financed 100.00, recurring 10.00; при одинаковой сумме кредита разный opening cash, principal и EIR classification; запрещено «вычесть/добавить» комиссию дважды.

**GOLDEN-07 (интеграция):** planned payment 100, confirmed cash payment 60, remaining due 40, one installment with valid due date. `A01` ledger assigns 60 only once; overdue monetary amount after agreed boundary = 40, daysPastDue separately; P04 planned and F03 actual remain 100 vs 60.

**Проверки с ожидаемым отказом:** missing mandatory source, duplicate loan/event/fee IDs, wrong project/org, negative/oversize monetary values, rate period incompatible with dates, unsupported day-count/calendar, multiple/zero EIR roots, inconsistent currency or reporting basis, fee base missing, reschedule without revision, replay/hash drift, double settlement, date cutoff drift, F03 posting from unconfirmed plan, unauthorized covenant PASS, IFRS EIR mistaken for KZ_GESV. Тесты должны проверять коды отказа, а не только HTTP 500.

### 12. Performance и реализация без лишних подсистем

Профиль UAT: `1, 12, 36, 120, 360` периодов; `0, 1, 10` комиссионных правил; `1–10` rate changes; один договор в одной команде. Цель — детерминированный memory-bounded расчет и benchmark на 10k договоров только **после** E2E. Нельзя обещать p95 без измерений. PostgreSQL получает bounded batch persist/read + индекс по `(org,loanKey,revision)` / `(scheduleRevision,dueDate)`, Java — BigDecimal math. Redis/Caffeine/HTAP/Keycloak не являются prerequisites данного UAT. При этом basic organization/project isolation и защита API от произвольного SQL обязательны.

### 13. Этапы согласования до реализации

ТЗ может быть принято как **архитектурный контракт**, но для финансового исполнения нужны следующие точные решения:

| ID | Предлагаемое решение | Что требуется утвердить |
|---|---|---|
| `DEC-A05-01` | В MVP расчёт **со стороны заёмщика**, с договорным денежным потоком и отдельными accounting adjustments | Поддерживать ли уже в R1 зеркальную роль кредитора или отложить? |
| `DEC-A05-02` | Старт: KZT, одна выдача, monthly, FIXED; интерфейс сразу проектируется на multicurrency / multiple drawdowns | Нужны ли валюты USD/EUR и несколько траншей в первом E2E? |
| `DEC-A05-03` | DATE/ACT_365_FIXED + configurable NOMINAL_PERIODIC; календарь и shift rule обязательны как явные входы | Правила дат, выходных, ACT/360 и 30/360 для пилотного договора |
| `DEC-A05-04` | IFRS 9 EIR отдельно, KZ_GESV = будущий юридически квалифицированный профиль | Нужен ли ранний standalone-regulatory ГЭСВ для РК и какой тип заёмщика/договора? |
| `DEC-A05-05` | Непредоставленный `CFADS` не заменяется EBITDA/прибылью; covenant P09 = NOT_VERIFIED | Подтвердить договорный состав CFADS/debt service/лимит и периоды |
| `DEC-A05-06` | А05 генерирует план, A01 распределяет факт и считает просрочку; в C05 не складывать торговую и кредитную задолженности | Какие payment allocation priorities применять по договору (штрафы/комиссии/проценты/principal)? |

Пока решения не приняты, соответствующие варианты отмечаются `REVIEW_REQUIRED`, **не** получают автоматически заданных финансовых defaults.

### 14. Итоговые условия приёмки

1. Нет 167-й главной формы: код C05 и существующие связи каталога не нарушены.
2. Любой итоговый платеж объясним по датам, сумме principal, ставке, day-count, комиссиям, FX/rounding и source SHA.
3. Отдельно и без смешения вычисляются contract cash, EIR accounting и (после отдельного допуска) KZ_GESV.
4. Предыдущие графики воспроизводятся после изменения ставки или досрочного погашения; immutable SHA/CalcPlan ревизии.
5. P04/P09 согласованы, план/факт различены, двойной учёт платежа исключён.
6. Расчёты проходят реальные Java21/PG16 и Chromium E2E на exact `fin_intake` user-saved revisions, включая негативные случаи и повторное открытие.
7. Финансовая S2 и production admission остаются `CLOSED`, пока не состоялось отдельное решение с финансовыми golden vectors.

**Решение по ТЗ:** `PROPOSED_FOR_APPROVAL`. Первая задача реализации после R2.5 — `A05-R1.0 / typed C05 input contract and LoanFeeRuleRevision`, а не попытка сразу вывести IFRS EIR/DSCR из одного поля `interestRate`.
