# FIN CALC-A05-R1.2 — Amendment Scenario UAT Qualification

**Дата:** 2026-11-20  
**Ветка:** `feat/fin-form-library-standalone`  
**Статус:** `CONTRACT_AMENDMENT_SCENARIOS_UAT_ACCEPTED` — НЕ юридическое изменение C05, НЕ финансовый S2, НЕ production admission.

## Точные результаты

- **Java 21 + PostgreSQL 16: 14/14 PASS**, 0 failures, 0 errors, 0 skipped: [run 44729611238](https://github.com/alimbetov/fin-analyzer/actions/runs/44729611238). 8 независимых математических проверок и 6 реальных интеграционных PostgreSQL16 проверок. Контрольные тесты включают досрочное погашение, две стратегии, разные grace, капитализацию, split-rate, запрет незафиксированного FLOATING_INDEX, fee-policy отказ, повтор запроса, SHA, REST.
- **Real React + Spring Boot + PG16 Chromium: 2/2 PASS**, 0 failures/errors/skipped: [run 44730051035](https://github.com/alimbetov/fin-analyzer/actions/runs/44730051035). Сценарий REDUCE_TERM vs REDUCE_INSTALLMENT, сравнение и восстановление старой редакции; отдельно principal grace, капитализация и mid-coupon rate step.
- Это независимые qualified *UAT runs* на разных SHA. Они подтверждают расчётное ядро и интерфейс, **не** непрерывную цепочку всех workflow в одном HEAD.

## Архитектура и хранилище

`fin_intake` остаётся единственным writer исходных условий договора; C05 и типизированные ставки/комиссии A05-R1.0 **не изменяются** при what-if сценарии. Parent — конкретная неизменяемая `fin_calc.a05_schedule_revision` из A05-R1.1. Реализация:

- `LoanAmendmentCalculator` переиспользует единые расчётные `LoanPaymentScheduleCalculator` day-count, calendar, rounding и fee functions; самостоятельный универсальный финансовый движок не создаётся.
- `LoanAmendmentService` атомарно пишет `fin_calc.a05_amendment_revision`, `a05_amendment_line`, `a05_amendment_line_fee` и идемпотентные receipts.
- Миграция `061` аддитивна после `060`; guards на исходный parent C05/contract/app SHA; UPDATE/DELETE/TRUNCATE результатов и receipts запрещены.
- GET exact повторно пересчитывает из сохранённых immutable inputs, проверяет SHA сценария и **физические строки и комиссии PostgreSQL**; history — без implicit latest.
- API (только при `fin.calc.a05-intake-uat.enabled=true`):
  `POST /api/v1/uat/a05/amendments`,
  `GET /api/v1/uat/a05/amendments/{id}`,
  `GET /api/v1/uat/a05/amendments?organizationId=&projectId=&parentScheduleRevisionId=`.
- React `LoanAmendmentPanel` встроен под сохранённым C05/LoanSchedulePanel: оригинал и amended totals/maturity показаны рядом, значения не пересчитываются в JavaScript, истории доступны по exact UUID.

## Реализованные финансовые правила

| Событие | Действие | Граница |
|---|---|---|
| PREPAYMENT, REDUCE_TERM | Дополнительная выплата principal на существующую дату платежа, исходная регулярная сумма сохраняется, остаток закрывается раньше | Комиссия `AT_MATURITY` при ускоренном закрытии требует отдельной fee policy; без неё fail closed |
| PREPAYMENT, REDUCE_INSTALLMENT | Дополнительная выплата на due date, новую регулярную амортизацию/аннуитет фиксировать по оставшимся датам, maturity сохранять | Только single-event сценарий |
| GRACE, PRINCIPAL_ONLY | Principal не выплачивается в рамках периода льготы, проценты и применимые комиссии оплачиваются, после льготы — амортизация на остаток срока | Даты grace совпадают с существующими dueAt |
| GRACE, FULL_CAPITALIZE | Денежные проценты за время льготы = 0, начисленные проценты капитализируются в principal, после льготы — амортизация выросшей задолженности | Не означает отсрочку комиссий; судьба комиссий задаётся отдельно |
| RATE_STEP | Пересмотр **фиксированной** процентной ставки в дату внутри купона; сумма процентов делится по двух датированным интервалам, остальные платежи пересчитываются | FLOATING_INDEX без индексных фиксаций и нормативной методики запрещён |

Контроли: `opening + capitalizedInterest - scheduledPrincipal - extraPrincipal = closing`;
`openingOriginal + SUM(capitalized) = SUM(scheduledPrincipal+extraPrincipal)`;
`cash = scheduledPrincipal + extraPrincipal + interestCash + feeCash`; финальный баланс ноль. Никакого предположения о том, что плановое досрочное погашение уже произошло.

**Репрезентативный goldens:** KZT 1 000, нулевая ставка, четыре периода по 250; дополнительное погашение 400 в первую дату платежа: REDUCE_TERM → три периода, maturity 2027-04-01; REDUCE_INSTALLMENT → четыре периода, остаточная доля 116,67 / 116,67 / 116,66, maturity 2027-05-01. Отдельная RATE_STEP 12→24% 2027-01-15 при ACT/365 даёт 15,78 процентов к 2027-02-01 на сумме 1 000.

## Матрица 38 A05 acceptance gates

[Полная матрица](../FIN-CALC-A05-R1-ACCEPTANCE-MATRIX.tsv): **12 PASS_UAT / 13 PARTIAL / 13 NOT_IMPLEMENTED**.

- `T014` и `T016` прошли в ограниченном UAT профиле.
- `T015` частичный: два варианта рассчитаны, но все комбинации последствий комиссий ещё не квалифицированы.
- `T017` частичный: DATE-AWARE RATE_STEP реализован, полноценный FLOATING_INDEX и index fixing feed отсутствуют.
- `T018` частичный: effectiveDate/lineage/immutable replay доказаны **для scenario**, но юридическое изменение активного договора должно создавать новую ревизию C05 с подтверждением, не переименованием what-if.
- `T035` покрыт сравнением и восстановлением неизменяемого parent/child; T036 concurrency/stress остаётся частичным.

**Дальше:** сначала отдельно задать event-sourcing / identity legal amendments C05 (без retroactive overwrite), `FLOATING_INDEX` calendar fixing и fee prepayment policy; затем можно претендовать на полный A05-R1.2 milestone. Отдельные IFRS9 EIR, KZ GESV, A01 payment allocation, P09 DSCR и financial S2 остаются закрытыми.
