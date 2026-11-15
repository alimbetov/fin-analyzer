# FIN Indicator Dictionary — справочник экономических показателей RU / KK / EN

**ID:** FIN-METRIC-REGISTRY · **Версия:** 1.0 · **Дата:** 2026-11-15 · **Статус:** SPECIFIED / NOT IMPLEMENTED. **Языки:** `ru` русский, `kk` қазақша, `en` English.
**Область:** общий словарь для FIN Analyzer, FIN Business Health, «Мой бизнес», Business Plan и отчётов. [SRS](FIN-REQUIREMENTS-CONSOLIDATED.md) · [FIN Business Health](FIN-BUSINESS-HEALTH.md).

## 1. Как работает каталог

Для каждого KPI неизменный `metricCode` идентифицирует **одну** формулу и методику, а три перевода отображают **название / что считает / как считает / что показывает**. Переключение языка меняет только представление: **значение, единица, период, источники и версия формулы остаются теми же**. Формулы ниже описывают нормативную бизнес-семантику; машинные AST и исполняемый код ещё не реализованы.

Обязательные поля будущего `MetricDefinition`: `metricCode`, `methodVersion`, `group`, `unitType`, `inputFactCodes`, `formulaAst`, `basis` (actual/forecast), `frequency`, `periodScope`, `applicability` (sector/country), `precision/rounding`, `zeroDenominatorPolicy`, `missingPolicy`, `caveats`, `sourceMethodRefs`, `translations.{ru,kk,en}.{name,what,how,shows}`, `effectiveFrom`, `effectiveTo`, `reviewStatus`.

Контракт `MetricResult`: `value: Decimal | null`, `status: COMPUTED | ESTIMATED | NOT_COMPUTABLE | NOT_APPLICABLE`, `formulaTrace`, `sourceFactRevisionIds`, `methodVersion`, `unit`, `currency`, `period`, `scenarioOrActual`, `assumptions`, `qualityFlags`. Статусы отсутствия исходных фактов должны сохранять различие `MISSING`, `EXPLICIT_ZERO`, `NOT_APPLICABLE`, `ESTIMATED`; неизвестное не заменяется нулём.

**Единые правила расчёта:** деньги внутри расчёта без float (decimal.js/точная десятичная арифметика), валюта одна или конверсия с датой и источником курса, валидные сопоставимые периоды, проверка единиц, стабильный порядок округления только при отображении, переводы не содержат самостоятельных формул. В блоках `gross/net` терминология и политика налогообложения/НДС выбираются версионированным методом; математическая сумма должна исключать двойной учёт.

**Правила применимости:** показатели запасов только при подтверждённом движении/оценке товаров; DSCR только при наличии CFADS и графика обслуживания долга; CAC/ROAS в будущем только при доказуемой атрибуции. Ни одно число не превращается в «плохо/хорошо» без отраслевой базы, порога с источником и применимости. При нехватке данных вместо фиктивной оценки — пояснение и next-best-question.

## 2. Определения и переводы

Ниже формулы используют **псевдокод** с английскими кодами входов. Все необходимые части финансовой модели должны быть подтверждены, иначе результат `NOT_COMPUTABLE`; необязательные отраслевые показатели могут быть `NOT_APPLICABLE`. Метрика с суффиксом `_END` относится к дате, потоки — к интервалу. `100 ×` означает процент, не коэффициент.

### Прибыльность / Profitability

#### `REV_NET` · money

- **Входы:** `sales/accrual ledger;returns and discounts`. **Формула:** `Σ approved net accrued sales − corrections already excluded by mapping`.
- **Ограничения:** Exclude VAT when outside revenue under selected policy; cash receipts ≠ revenue.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Выручка (нетто) | Сколько товаров и услуг признано проданными. | Суммирует продажи по начислению за период с учётом возвратов и скидок. | Показывает масштаб продаж, но не поступление денег. |
| `kk` | Таза түсім | Кезеңде қанша тауар мен қызмет сатылғанын көрсетеді. | Есептеу кезеңіндегі сатуды қайтарымдар мен жеңілдіктерді ескеріп жинақтайды. | Сату көлемін көрсетеді, бірақ ақша түскенін білдірмейді. |
| `en` | Net revenue | How much goods and services were recognized as sold. | Sums accrued sales for the period net of returns and discounts. | Shows sales scale, not cash collected. |

#### `COGS` · money

- **Входы:** `costed deliveries;inventory consumption`. **Формула:** `Σ cost of goods/services actually delivered in period`.
- **Ограничения:** Require approved inventory/service costing policy; purchases ≠ COGS.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Себестоимость продаж | Во сколько обошлись фактически проданные товары и оказанные услуги. | Складывает стоимость реализованных товаров или услуг, а не все закупки. | Помогает понять затраты на реализованный объём. |
| `kk` | Сатудың өзіндік құны | Нақты сатылған тауарлар мен көрсетілген қызметтердің құнын есептейді. | Барлық сатып алуды емес, сатылған өнім мен қызметтің өзіндік құнын жинақтайды. | Сатылған көлемге кеткен шығынды көрсетеді. |
| `en` | Cost of goods sold | What the delivered products and services cost. | Sums costs attributable to goods or services delivered, not all purchases. | Shows the cost attached to realized sales. |

#### `GROSS_PROFIT` · money

- **Входы:** `REV_NET;COGS`. **Формула:** `REV_NET − COGS`.
- **Ограничения:** Not equal to cash or net profit.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Валовая прибыль | Сколько остаётся после себестоимости продаж. | Вычитает себестоимость продаж из чистой выручки. | Показывает ресурс для покрытия других расходов и прибыли. |
| `kk` | Жалпы пайда | Сату өзіндік құнынан кейін қанша қаражат қалатынын есептейді. | Таза түсімнен сатудың өзіндік құнын шегереді. | Басқа шығындарды жабуға қалатын нәтижені көрсетеді. |
| `en` | Gross profit | How much remains after cost of sales. | Subtracts cost of goods sold from net revenue. | Shows the amount available to cover overheads and profit. |

#### `GROSS_MARGIN` · percent

- **Входы:** `GROSS_PROFIT;REV_NET`. **Формула:** `100 × GROSS_PROFIT / REV_NET`.
- **Ограничения:** REV_NET=0 => NOT_COMPUTABLE; industry-specific comparison.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Валовая маржа | Какую долю выручки составляет валовая прибыль. | Делит валовую прибыль на выручку и умножает на 100. | Показывает долю, остающуюся после себестоимости. |
| `kk` | Жалпы маржа | Жалпы пайданың түсімдегі үлесін есептейді. | Жалпы пайданы таза түсімге бөліп, 100-ге көбейтеді. | Өзіндік құннан кейінгі түсім үлесін көрсетеді. |
| `en` | Gross margin | What share of revenue remains as gross profit. | Divides gross profit by revenue and multiplies by 100. | Shows the share remaining after direct cost of sales. |

#### `CONTRIBUTION_PROFIT` · money

- **Входы:** `REV_NET;non-overlapping total variable costs`. **Формула:** `REV_NET − VARIABLE_COST_TOTAL`.
- **Ограничения:** VARIABLE_COST_TOTAL must include applicable variable COGS without duplicating costs.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Маржинальный доход | Сколько остаётся после всех переменных затрат. | Вычитает переменные затраты из выручки без повторного учёта себестоимости. | Показывает вклад продаж в покрытие постоянных затрат. |
| `kk` | Маржиналдық табыс | Барлық айнымалы шығыннан кейін қалған соманы есептейді. | Түсімнен айнымалы шығындарды екі рет есептемей шегереді. | Тұрақты шығындарды жабуға сатудың үлесін көрсетеді. |
| `en` | Contribution profit | What remains after all variable costs. | Subtracts non-overlapping variable costs from revenue. | Shows the amount available for fixed costs. |

#### `CONTRIBUTION_MARGIN` · percent

- **Входы:** `CONTRIBUTION_PROFIT;REV_NET`. **Формула:** `100 × CONTRIBUTION_PROFIT / REV_NET`.
- **Ограничения:** REV_NET=0 => NOT_COMPUTABLE; percent values can be negative.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Коэффициент маржинального дохода | Какая часть каждой единицы выручки покрывает постоянные затраты. | Делит маржинальный доход на выручку и умножает на 100. | Показывает чувствительность результата к объёму продаж. |
| `kk` | Маржиналдық табыс үлесі | Түсімнің қандай бөлігі тұрақты шығындарды жабатынын есептейді. | Маржиналдық табысты түсімге бөліп, 100-ге көбейтеді. | Пайданың сату көлеміне тәуелділігін көрсетеді. |
| `en` | Contribution margin ratio | The share of revenue available to cover fixed costs. | Divides contribution profit by net revenue and multiplies by 100. | Indicates sensitivity to sales volume. |

#### `OPERATING_PROFIT` · money

- **Входы:** `REV_NET;COGS;operating expenses excluding COGS`. **Формула:** `REV_NET − COGS − OPERATING_EXPENSES_EXCL_COGS`.
- **Ограничения:** Defined operating profit methodology; non-operating items excluded.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Операционная прибыль | Результат основной деятельности до процентов и налогов. | Из выручки вычитает себестоимость и операционные расходы без повторного учёта. | Показывает эффективность основной деятельности. |
| `kk` | Операциялық пайда | Пайыздар мен салықтарға дейінгі негізгі қызмет нәтижесін есептейді. | Түсімнен өзіндік құн мен қайталанбайтын операциялық шығындарды шегереді. | Негізгі қызмет тиімділігін көрсетеді. |
| `en` | Operating profit | Profit from core operations before interest and tax. | Subtracts COGS and operating expenses from revenue without duplication. | Shows the profitability of core operations. |

#### `NET_PROFIT` · money

- **Входы:** `operating profit;other P&L;interest;tax expense`. **Формула:** `OPERATING_PROFIT + OTHER_INCOME − OTHER_EXPENSE − INTEREST_EXPENSE − TAX_EXPENSE`.
- **Ограничения:** Owner draws and loan principal do not reduce P&L net profit.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Чистая управленческая прибыль | Сколько бизнес заработал после всех признанных расходов. | Корректирует операционную прибыль на прочие доходы/расходы, проценты и налог. | Показывает результат по начислению, а не деньги на счёте. |
| `kk` | Таза басқарушылық пайда | Барлық танылған шығыстан кейін бизнестің қанша пайда тапқанын есептейді. | Операциялық пайдаға өзге кіріс пен шығысты, пайыз бен салықты ескереді. | Есептеу әдісіндегі нәтижені көрсетеді, шоттағы ақшаны емес. |
| `en` | Management net profit | Profit after recognized expenses and income. | Adjusts operating profit for other items, interest and tax expense. | Shows accrual-based profit, not cash in the bank. |

#### `NET_MARGIN` · percent

- **Входы:** `NET_PROFIT;REV_NET`. **Формула:** `100 × NET_PROFIT / REV_NET`.
- **Ограничения:** REV_NET=0 => NOT_COMPUTABLE; no universal health threshold.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Чистая рентабельность продаж | Какую часть выручки составляет чистая прибыль. | Делит чистую прибыль на выручку и умножает на 100. | Показывает итоговую доходность продаж. |
| `kk` | Сатудың таза рентабельділігі | Таза пайданың түсімдегі үлесін есептейді. | Таза пайданы түсімге бөліп, 100-ге көбейтеді. | Сатудың қорытынды табыстылығын көрсетеді. |
| `en` | Net profit margin | What share of revenue is net profit. | Divides net profit by revenue and multiplies by 100. | Shows overall profitability of sales. |

### Деньги / Cash flow

#### `CASH_IN_OP` · money

- **Входы:** `bank/cash inflows classified as operating`. **Формула:** `Σ classified operating cash receipts`.
- **Ограничения:** Exclude loans and owner contributions; classify refunds and taxes per method.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Денежные поступления от деятельности | Сколько денег реально поступило от операционной деятельности. | Суммирует подтверждённые операционные поступления за период. | Показывает приток денег, который может отличаться от выручки. |
| `kk` | Операциялық ақша түсімдері | Негізгі қызметтен нақты қанша ақша түскенін есептейді. | Кезеңдегі расталған операциялық ақша түсімдерін жинақтайды. | Ақша кірісін көрсетеді, ол түсімнен өзгеше болуы мүмкін. |
| `en` | Operating cash receipts | How much cash actually came in from operations. | Sums verified operating cash inflows in the period. | Shows cash collected, which can differ from revenue. |

#### `CASH_OUT_OP` · money

- **Входы:** `bank/cash outflows classified as operating`. **Формула:** `Σ classified operating cash payments`.
- **Ограничения:** Cash-flow classification (interest/tax) must be method-versioned.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Денежные выплаты по деятельности | Сколько денег реально потрачено на операционную деятельность. | Суммирует подтверждённые операционные платежи за период. | Показывает денежную нагрузку, а не бухгалтерские расходы. |
| `kk` | Операциялық ақша төлемдері | Негізгі қызметке нақты қанша ақша төленгенін есептейді. | Кезеңдегі расталған операциялық төлемдерді жинақтайды. | Ақша жүктемесін көрсетеді, ол бухгалтерлік шығыстан бөлек. |
| `en` | Operating cash payments | How much cash was paid for operations. | Sums verified operating payments in the period. | Shows cash used, not the same as accrued expense. |

#### `NET_CASH_FLOW` · money

- **Входы:** `all operating/investing/financing inflows and outflows`. **Формула:** `TOTAL_CLASSIFIED_CASH_IN − TOTAL_CLASSIFIED_CASH_OUT`.
- **Ограничения:** Include financing/owner flows in this total, classify separately; reconcile balances.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Изменение денежных средств | Насколько за период выросли или снизились деньги. | Из всех денежных поступлений вычитает все денежные выплаты. | Показывает чистое изменение денежных средств за период. |
| `kk` | Ақша қаражатының өзгерісі | Кезең ішінде ақшаның қанша өскенін не азайғанын есептейді. | Барлық ақша кірісінен барлық ақша шығысын шегереді. | Кезеңдегі ақшаның таза өзгерісін көрсетеді. |
| `en` | Net change in cash | How much cash increased or decreased. | Subtracts all cash payments from all cash receipts. | Shows the period's net cash movement. |

#### `CASH_END` · money

- **Входы:** `opening cash balance;net cash flow;reconciliations`. **Формула:** `CASH_BEGIN + NET_CASH_FLOW ± documented reconciliation adjustments`.
- **Ограничения:** Distinguish restricted from unrestricted cash; missing opening balance => NOT_COMPUTABLE.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Остаток денег на конец периода | Сколько денежных средств осталось к концу периода. | Прибавляет чистое изменение денег к начальному остатку и учитывает сверенные корректировки. | Показывает доступный остаток при корректном составе счетов. |
| `kk` | Кезең соңындағы ақша қалдығы | Кезең соңында қанша ақша қалғанын есептейді. | Бастапқы қалдыққа таза өзгерісті және расталған түзетулерді қосады. | Шоттар дұрыс қамтылса, соңғы қалдықты көрсетеді. |
| `en` | Closing cash balance | How much cash remains at the period end. | Adds net cash movement and documented adjustments to opening cash. | Shows closing cash across the included accounts. |

#### `CASH_COVER_30D` · ratio

- **Входы:** `unrestricted cash;confirmed inflows;mandatory outflows in next 30 days`. **Формула:** `(UNRESTRICTED_CASH + CONFIRMED_INFLOW_30D) / MANDATORY_OUTFLOW_30D`.
- **Ограничения:** Future inflows can fail; if denominator=0 then NOT_APPLICABLE, not infinite safety.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Покрытие платежей 30 дней | Во сколько раз прогнозные доступные деньги покрывают обязательные платежи 30 дней. | Делит свободный остаток плюс подтверждённые поступления на обязательные выплаты ближайших 30 дней. | Показывает краткосрочный риск ликвидности в заданном сценарии. |
| `kk` | 30 күндік төлемдерді жабу | Алдағы 30 күннің міндетті төлемдерін қолжетімді ақша қанша есе жабатынын есептейді. | Еркін қалдық пен расталған түсімді 30 күндік міндетті төлемдерге бөледі. | Белгілі сценарийдегі қысқа мерзімді өтімділік тәуекелін көрсетеді. |
| `en` | 30-day payment coverage | How many times available cash covers mandatory payments due in 30 days. | Divides unrestricted cash plus confirmed inflows by mandatory outflows due within 30 days. | Indicates short-term liquidity pressure under assumptions. |

#### `CASH_RUNWAY` · months

- **Входы:** `unrestricted cash;representative monthly operating cash burn`. **Формула:** `UNRESTRICTED_CASH / POSITIVE_AVG_MONTHLY_OPERATING_CASH_BURN`.
- **Ограничения:** Only when positive representative burn; no burn => NOT_APPLICABLE.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Запас денежных средств (месяцы) | На сколько месяцев хватит денег при текущем темпе расходования. | Делит свободные деньги на подтверждённое среднее месячное чистое сжигание денег. | Показывает примерный горизонт при продолжающемся оттоке. |
| `kk` | Ақша қорының жету мерзімі | Ағымдағы ақша жұмсау қарқынында қордың неше айға жететінін есептейді. | Еркін қалдықты орташа айлық таза ақша тапшылығына бөледі. | Ақша шығыны жалғасса, болжамды мерзімді көрсетеді. |
| `en` | Cash runway (months) | How long cash may last at the current burn rate. | Divides unrestricted cash by representative positive monthly operating cash burn. | Estimates duration if net operating cash burn continues. |

### Долги / Debt and receivables

#### `AR_END` · money

- **Входы:** `approved customer invoices and settlements`. **Формула:** `Σ outstanding trade receivables at period end`.
- **Ограничения:** Exclude loans to other parties unless separately classified.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Дебиторская задолженность покупателей | Сколько клиенты ещё должны бизнесу. | Суммирует непогашенные торговые требования на дату отчёта. | Показывает деньги, ещё не полученные от клиентов. |
| `kk` | Сатып алушылардың дебиторлық берешегі | Клиенттердің бизнеске қанша қарыз екенін есептейді. | Есеп күніне өтелмеген сауда талаптарын жинақтайды. | Клиенттерден әлі түспеген соманы көрсетеді. |
| `en` | Trade accounts receivable | How much customers still owe the business. | Sums outstanding trade receivables as of the reporting date. | Shows customer balances not yet collected. |

#### `AR_OVERDUE_SHARE` · percent

- **Входы:** `aging schedule;trade AR at date`. **Формула:** `100 × OVERDUE_TRADE_AR / AR_END`.
- **Ограничения:** AR_END=0 => NOT_APPLICABLE; due dates/aging required.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Доля просроченной дебиторки | Какая часть долга покупателей просрочена. | Делит просроченную дебиторскую задолженность на всю торговую дебиторку и умножает на 100. | Показывает долю просроченных платежей клиентов. |
| `kk` | Мерзімі өткен дебиторлық берешек үлесі | Клиент қарызының қандай бөлігі мерзімінен өткенін есептейді. | Мерзімі өткен берешекті барлық сауда дебиторлығына бөліп, 100-ге көбейтеді. | Кешіктірілген клиент төлемдерінің үлесін көрсетеді. |
| `en` | Overdue receivables share | What proportion of customer receivables is overdue. | Divides overdue trade receivables by total trade receivables and multiplies by 100. | Shows exposure to late customer payments. |

#### `AP_END` · money

- **Входы:** `supplier invoices and settlements`. **Формула:** `Σ outstanding trade payables at period end`.
- **Ограничения:** Loans and taxes shown separately; avoid combining unlike liabilities.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Кредиторская задолженность поставщикам | Сколько бизнес должен поставщикам. | Суммирует неоплаченные обязательства перед поставщиками на дату. | Показывает будущую нагрузку по расчётам с поставщиками. |
| `kk` | Жеткізушілер алдындағы кредиторлық берешек | Бизнес жеткізушілерге қанша қарыз екенін есептейді. | Күніне өтелмеген жеткізуші міндеттемелерін жинақтайды. | Жеткізушілерге алдағы төлем жүктемесін көрсетеді. |
| `en` | Trade accounts payable | How much the business owes suppliers. | Sums unpaid trade obligations at the reporting date. | Shows future supplier settlement pressure. |

#### `DEBT_SERVICE_COVER` · ratio

- **Входы:** `cash flow available for debt service;contractual principal and interest due`. **Формула:** `CFADS / (PRINCIPAL_DUE + INTEREST_DUE)`.
- **Ограничения:** CFADS methodology and period required; debt due=0 => NOT_APPLICABLE; not bank approval.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Покрытие обслуживания долга (DSCR) | Хватает ли доступного денежного потока на платежи по долгу. | Делит денежный поток, доступный для обслуживания долга, на проценты и основной долг к уплате. | Показывает способность обслуживать долг по выбранной методике. |
| `kk` | Қарызға қызмет көрсету өтемі (DSCR) | Қарыз төлемдерін жабуға ақша ағыны жете ме, соны есептейді. | Қарызға қызмет көрсетуге қолжетімді ақша ағынын негізгі қарыз бен пайыз төлеміне бөледі. | Таңдалған әдістеме бойынша қарызды өтеу қабілетін көрсетеді. |
| `en` | Debt service coverage (DSCR) | Whether cash available for debt service covers debt payments. | Divides CFADS by principal and interest payments due. | Shows debt servicing capacity under the selected method. |

### Продажи / Sales

#### `AVG_ORDER` · money/order

- **Входы:** `same-cohort completed orders;net order sales`. **Формула:** `NET_COMPLETED_ORDER_SALES / COMPLETED_ORDER_COUNT`.
- **Ограничения:** No completed orders => NOT_COMPUTABLE; cohort and refund handling fixed by method.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Средний чек заказа | Сколько в среднем приносит один завершённый заказ. | Делит чистую стоимость завершённых заказов на их количество. | Показывает средний объём продажи на заказ. |
| `kk` | Тапсырыстың орташа чегі | Бір аяқталған тапсырыс орташа қанша түсім әкелетінін есептейді. | Аяқталған тапсырыстардың таза сомасын олардың санына бөледі. | Бір тапсырыстың орташа сатылымын көрсетеді. |
| `en` | Average order value | How much an average completed order generates. | Divides net sales from completed orders by the count of completed orders. | Shows average sales value per order. |

#### `RETURNS_RATE` · percent

- **Входы:** `returns and gross sales on comparable basis`. **Формула:** `100 × RETURNED_GROSS_SALES_VALUE / GROSS_SALES_VALUE`.
- **Ограничения:** Match order cohort/time basis; denominator=0 => NOT_COMPUTABLE.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Доля возвратов по стоимости | Какая доля валовых продаж вернулась покупателями. | Делит стоимость возвратов на валовую стоимость продаж за сопоставимую базу и умножает на 100. | Показывает масштаб возвратов. |
| `kk` | Қайтарымдардың құндық үлесі | Жалпы сатудың қандай бөлігі қайтарылғанын есептейді. | Қайтарылған сату сомасын салыстырмалы жалпы сатуға бөліп, 100-ге көбейтеді. | Қайтарым көлемін көрсетеді. |
| `en` | Value return rate | What share of gross sales was returned. | Divides returned sales value by comparable gross sales value and multiplies by 100. | Shows the value impact of returns. |

#### `CLIENT_TOP1_SHARE` · percent

- **Входы:** `customer-level net sales;complete coverage`. **Формула:** `100 × MAX(NET_CLIENT_REVENUE) / REV_NET`.
- **Ограничения:** Complete customer attribution required; anonymous/missing IDs => incomplete coverage.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Концентрация на крупнейшем клиенте | Какую часть выручки приносит самый крупный клиент. | Делит чистую выручку крупнейшего клиента на общую выручку и умножает на 100. | Показывает зависимость бизнеса от одного клиента. |
| `kk` | Ең ірі клиентке тәуелділік | Ең ірі клиент түсімнің қанша бөлігін беретінін есептейді. | Ең ірі клиенттің таза түсімін жалпы түсімге бөліп, 100-ге көбейтеді. | Бизнестің бір клиентке тәуелділігін көрсетеді. |
| `en` | Largest-customer concentration | What share of revenue comes from the largest customer. | Divides the top customer's net revenue by total net revenue and multiplies by 100. | Indicates dependence on a single customer. |

### Запасы / Inventory

#### `INV_TURNOVER` · times/period

- **Входы:** `COGS;beginning and ending inventory valued consistently`. **Формула:** `COGS / ((INV_BEGIN + INV_END)/2)`.
- **Ограничения:** Average inventory=(start+end)/2 is a simplified method; seasonal cases need daily/monthly average.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Оборачиваемость запасов | Сколько раз за период условно обернулись запасы. | Делит себестоимость продаж на среднюю стоимость запасов. | Показывает интенсивность использования товаров на складе. |
| `kk` | Қорлардың айналымдылығы | Кезеңде қорлардың шартты түрде қанша рет айналғанын есептейді. | Сату өзіндік құнын орташа қор құнына бөледі. | Қордың қаншалықты белсенді пайдаланылғанын көрсетеді. |
| `en` | Inventory turnover | How many times inventory effectively turned over in the period. | Divides COGS by average inventory value. | Indicates how actively inventory was used. |

#### `INVENTORY_DAYS` · days

- **Входы:** `days in period;avg inventory;COGS`. **Формула:** `PERIOD_DAYS × ((INV_BEGIN + INV_END)/2) / COGS`.
- **Ограничения:** COGS=0 => NOT_COMPUTABLE; must use consistent basis with INV_TURNOVER.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Запасы в днях | На сколько дней продаж условно хватает запасов. | Умножает отношение средних запасов к себестоимости продаж на дни периода. | Показывает длительность оборота запасов. |
| `kk` | Қорлардың айналым күндері | Қордың қанша күнде айналатынын шамалап есептейді. | Орташа қордың өзіндік құнға қатынасын кезең күндеріне көбейтеді. | Қор айналымының ұзақтығын көрсетеді. |
| `en` | Inventory days | Roughly how many days inventory takes to turn. | Multiplies average inventory divided by COGS by the number of days. | Indicates length of the inventory cycle. |

### Планирование / Planning

#### `BREAK_EVEN_REV` · money

- **Входы:** `fixed costs;net revenue;variable costs / contribution profit`. **Формула:** `FIXED_COST_TOTAL / (CONTRIBUTION_PROFIT / REV_NET)`.
- **Ограничения:** Positive contribution margin ratio required; assumes stable mix/prices/cost structure.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Выручка безубыточности | При какой выручке покрываются постоянные и переменные затраты. | Делит постоянные затраты на долю маржинального дохода в выручке. | Показывает расчётный порог безубыточности. |
| `kk` | Залалсыздық түсімі | Қандай түсімде тұрақты және айнымалы шығындар жабылатынын есептейді. | Тұрақты шығындарды маржиналдық табыстың түсімдегі үлесіне бөледі. | Залалсыздықтың болжамды шегін көрсетеді. |
| `en` | Break-even revenue | What revenue is needed to cover fixed and variable costs. | Divides fixed costs by the contribution margin ratio. | Shows estimated sales required to break even. |

#### `SAFETY_MARGIN` · percent

- **Входы:** `revenue;break-even revenue`. **Формула:** `100 × (REV_NET − BREAK_EVEN_REV) / REV_NET`.
- **Ограничения:** Not a solvency guarantee; denominator=0 => NOT_COMPUTABLE.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Запас финансовой прочности | Насколько выручка превышает порог безубыточности. | Вычитает порог безубыточности из выручки и делит на выручку, умножая на 100. | Показывает допустимое снижение выручки в упрощённой модели. |
| `kk` | Қаржылық беріктік қоры | Түсімнің залалсыздық шегінен қанша жоғары екенін есептейді. | Түсімнен залалсыздық түсімін шегеріп, түсімге бөліп, 100-ге көбейтеді. | Қарапайым модельде түсім қанша төмендей алатынын көрсетеді. |
| `en` | Margin of safety | How far revenue exceeds the break-even level. | Subtracts break-even revenue from current revenue, divides by revenue and multiplies by 100. | Shows tolerable sales decline in the simplified model. |

### Владелец / Owner

#### `OWNER_DRAWS` · money

- **Входы:** `cash transactions classified as owner draws`. **Формула:** `Σ cash transfers/withdrawals classified as owner personal drawings`.
- **Ограничения:** Never automatically classify as P&L expense; legal/tax treatment requires country method.

| Язык | Название | Что считает / What | Как считает / How | Что показывает / Shows |
|---|---|---|---|---|
| `ru` | Изъятия владельца | Сколько денег собственник вывел из бизнеса для личных нужд. | Суммирует личные изъятия владельца из предпринимательских средств. | Показывает влияние личных изъятий на ликвидность, а не на прибыль. |
| `kk` | Иесінің жеке қаражат алуы | Иесінің бизнестен жеке қажетіне қанша ақша алғанын есептейді. | Кәсіпкерлік қаражаттан иесінің жеке алуларын жинақтайды. | Жеке алудың өтімділікке әсерін көрсетеді, пайдаға емес. |
| `en` | Owner withdrawals | How much cash the owner took for personal use. | Sums cash transfers classified as owner personal drawings. | Shows impact on liquidity, not operating profit. |

## 3. Золотые примеры и отрицательные сценарии

| ID | Проверка | Ожидаемое значение/состояние |
|---|---|---|
| MET-01 | REV_NET=5 000 000 KZT; COGS=3 000 000 KZT | GROSS_PROFIT=2 000 000 KZT; GROSS_MARGIN=40% |
| MET-02 | GROSS_PROFIT=2 000 000; OPEX excluding COGS=1 200 000; interest=100 000; tax expense=200 000; other items=0 | OPERATING_PROFIT=800 000; NET_PROFIT=500 000; NET_MARGIN=10% |
| MET-03 | REV_NET=5 000 000, CASH_IN_OP=3 000 000 | два разных показателя, без автоматической подмены |
| MET-04 | закупки 1 000 000, расход проданных товаров 600 000, остаток вырос на 400 000 | COGS=600 000 при подтверждённых запасах |
| MET-05 | CASH_BEGIN=500 000; все притоки 2 000 000; все выплаты 2 200 000 | NET_CASH_FLOW=−200 000; CASH_END=300 000 |
| MET-06 | UNRESTRICTED_CASH=300 000; CONFIRMED_INFLOW_30D=400 000; MANDATORY_OUTFLOW_30D=1 000 000 | CASH_COVER_30D=0.7 раза; вывод лишь в рамках предположений |
| MET-07 | FIXED_COST_TOTAL=1 000 000; REV_NET=4 000 000; VARIABLE_COST_TOTAL=2 000 000 | CONTRIBUTION_PROFIT=2 000 000; BREAK_EVEN_REV=2 000 000; SAFETY_MARGIN=50% |
| MET-08 | REV_NET=0 и GROSS_PROFIT есть | GROSS_MARGIN=NOT_COMPUTABLE, не 0% |
| MET-09 | AR_END=0, просроченная дебиторка=0 | AR_OVERDUE_SHARE=NOT_APPLICABLE, не 0% при пустом портфеле |
| MET-10 | OWNER_DRAWS=400 000; новый заём=1 000 000 | ни владельческие изъятия, ни основной долг кредита не попадают в REV_NET и обычные расходы P&L |
| MET-11 | Одна и та же версия fact snapshot, смена locale ru→kk→en | только тексты меняются; code/formula/value/status совпадают |
| MET-12 | Перевод отсутствует, формула изменена без version bump или код дублируется | Registry CI FAIL; нет silent fallback неподтверждённого перевода |

## 4. Реализация и расширяемость

- **P0/G1:** зарегистрировать минимум `REV_NET`, `COGS`, `GROSS_PROFIT`, `GROSS_MARGIN`, `NET_PROFIT`, `NET_MARGIN`, `CASH_IN_OP`, `CASH_OUT_OP`, `NET_CASH_FLOW`, `CASH_END`, `AR_END`, `AP_END`, `OWNER_DRAWS` и их входы. Остальные из текущего каталога по мере достаточности форм и модели.
- **P1/G2:** сценарии, debt/stock/commercial KPI, отраслевые условия, сравнения периодов; тесты по MET-01…12 и независимый S2 на версиях формул и переводов.
- **P2/G4:** versioned country/tax methods и расширение отраслей (food cost, loading, CAC/ROAS с атрибуцией, unit economics). Новые metricCode — добавлением, не подменой смысла старого.
- **Обязательная проверка CI:** уникальные коды, все три полные локализации для каждого элемента, единые шаблонные плейсхолдеры и единицы, проверка AST и отсутствующих inputFactCodes, циклов зависимостей, нулевых знаменателей, детерминизма и золотых примеров. Проверка казахских терминов носителем/предметным редактором до релиза.

**Текущий статус:** это нормативный **проектный словарь**. JSON registry, исполняемый Calculation Engine, автотесты и UX ещё не созданы.
