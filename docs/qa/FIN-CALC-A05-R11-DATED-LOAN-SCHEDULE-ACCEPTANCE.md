# FIN CALC-A05-R1.1 — Датированный договорный график: приёмка UAT

**Дата:** 2026-11-20  
**Ветка:** `feat/fin-form-library-standalone`  
**Статус:** `DATED_CONTRACT_CASH_CORE_IMPLEMENTED`, UAT only, financial S2 = **NO**, production admission = **NO**.

## 1. Доказательства на точных коммитах

- **Java 21 + PostgreSQL 16: 18/18 PASS** (8 математических Java и 10 интеграционных PG16, включая сумму больше IEEE-754 safe integer). Код `a606d35b277cb4f3b4570e89802299e3fdb6156f`. [Actions run 44698245223](https://github.com/alimbetov/fin-analyzer/actions/runs/44698245223). Без failures/errors/skips.
- **React + настоящий Spring Boot + PostgreSQL 16 + Chromium: 3/3 PASS, включая decimal-string hardening**. Код `b53810642c4249e68d002e3cd41488f637dadc03`. [Actions run 44698253076](https://github.com/alimbetov/fin-analyzer/actions/runs/44698253076). Без mocks/failures/errors/skips.
- Оба результата относятся к **одному набору Java/React финансового кода** и подтверждают decimal-string readback; SHA двух workflow-коммитов различается вследствие последовательного обновления CI-конфигурации. Из этого не следует единый production admission, финансовый S2 или квалификация полного репозитория.
- В CI наблюдался HTTP 429 Maven Central при загрузке `maven-clean-plugin`/`maven-jar-plugin`. Для двух профильных A05-R1.1 jobs добавлен CI-only Google Cloud hosted Maven Central mirror, без изменения pom.xml; ошибки загрузки отличать от financial assertions.

## 2. Реализованные компоненты

| Компонент | Результат |
|---|---|
| `LoanPaymentScheduleCalculator` | Чистый Java `BigDecimal` расчёт ANNUITY / EQUAL_PRINCIPAL / CUSTOM и датированных процентов |
| `LoanScheduleService` | Exact C05 source SHA rehash через действующий INGEST-2; exact typed A05 contract; version-1 pin `CALC-A05` app candidate; результат/строки/fees и replay |
| `LoanScheduleUatController` | POST `/api/v1/uat/a05/schedules` c `Idempotency-Key`; GET `/api/v1/uat/a05/schedules/{id}`; GET список ревизий для точной C05 |
| Миграция `059` | `fin_calc.a05_schedule_revision`, `_line`, `_line_fee`, `_custom_installment`, `_command_receipt` с immutability triggers |
| Миграция `060` | Исправление затенения встроенного `FOUND` в PostgreSQL проверке SHA/source pin (аддитивно, без переписывания `059`) |
| `LoanSchedulePanel` | График внутри существующей C05, видимые суммы, датированные строки, исходные SHA, версии результатов, CUSTOM и финансовые diagnostics |
| Тесты / CI | 8 math + 10 PG16 + 3 Chromium; проверенные проходы c GCS mirror; отдельные workflows и Surefire/Playwright artifacts |

**Нет 167-й формы или отдельного writer кредитных исходных условий.** График является расчётной UAT-проекцией `CALC-A05` с immutable result внутри `fin_calc`; original loan/rate/calendar/fees лежат в `fin_intake` и связываются с конкретной C05-ревизией.

## 3. Предметные правила

- Фиксированная номинальная ставка задана **в процентных пунктах годовых**, `r = pct / 100`. Годовые периоды: `ACT_365_FIXED`, `ACT_360`, `THIRTY_E_360`; никаких скрытых `rate/12` и `30E/360 ISDA`.
- Monthly calendar: `dueDay ∈ [1,28]`, `businessDayAdjustment=NONE`; ближайшая дата платежа должна быть строго позже `accrualStartDate`, последняя дата — maturity.
- ANNUITY — равная округлённая сумма **principal+interest**, найденная из фактических датированных discount factors, с допустимой окончательной корректировкой principal.
- EQUAL_PRINCIPAL — округлённые равные доли principal и точное закрытие остатка последней строкой.
- CUSTOM — **версионированный вход сценария** из конкретных дат и сумм principal; проценты по ним рассчитывает Java; сумма principal должна точно покрывать обязательство. Не придумывать custom-interest или custom-cash basis.
- `FINANCED` увеличивает opening liability, `WITHHELD` уменьшает net disbursement, `UPFRONT_PAID` уменьшает net cash proceeds вне расписания; `RECURRING`/ `AT_MATURITY` формируют отдельные fee компоненты будущих платежей. Финансируемые fees не должны вторично появляться в scheduled fees.
- Каждый период: opening − principal = closing, cash = principal + cash interest + fees. `SUM(principal) = drawdown + financed`; `closingFinal = 0`. Отрицательная амортизация, избыточный CUSTOM principal, неверные даты, несовместимые комиссии и недопустимые политики → отказ.
- Все денежные расчёты в Java BigDecimal/DECIMAL128; денежные raw аудиторские поля имеют явный scale, финальная денежная шкала — из pin договора. JSON суммы — **строки** после hardening, не браузерные IEEE-754 числа.

## 4. Криптографический и исторический контроль

Result сохраняет:
`sourceRevisionId`, `sourceContentSha256`, `contractRevisionId`,
`termsSha256`, `appContractRevisionId` и SHA app contract,
`engineDescriptorSha256`, `customInputSha256`, `variantSha256`,
`resultSha256`. PostgreSQL запрещает UPDATE/DELETE расчётных редакций,
строк, комиссий и receipts. При чтении сервис повторно извлекает exact
C05, реконструирует график из saved typed contract + сохранённого CUSTOM,
заново вычисляет digest, проверяет **физические строки PostgreSQL** и SHA.

Повтор ключа с тем же payload → `IDEMPOTENT_REPLAY`; новый ключ с тем же
variant → `EXISTING_VARIANT_REPLAY`; повтор ключа с другим payload → 409.

`engineDescriptorSha256` — **SHA контракта алгоритма**, а не независимый
SHA исполняемого JAR/build manifest. Не называть это квалифицированным
build evidence до отдельной поставки и pin.

## 5. Незакрыто / границы

- В точных математических примерах есть аннуитет с двумя 31-дневными
  периодами ACT/360 и 12% годовых (1,0333% за период), результат principal
  497.43 + 502.57, interest 10.33 + 5.19. Это **не** тест T008
  «1% nominal periodic per period»; последний пока PARTIAL и требует
  версионированного `NOMINAL_PERIODIC` или отдельного golden.
- Нет дополнительных траншей, `FLOATING`, `STEP`, prepayment,
  grace, reset, real calendar holiday shifts, EIR, ГЭСВ и allocation A01.
- P04/P09/F01/F02/F03 здесь не заполняются автоматически; в F03 плановые
  платежи не должны попадать как состоявшиеся факты.
- Нет финансового S2, production, юридически действующей ГЭСВ или
  квалифицированного DSCR.
- Нет общего immutable CalcPlan для A05 и доказанной graph-driven
  квалификации всех 154 форм: сейчас `CALC-A05` candidate version
  прямо pinned, универсальная связка — следующая задача MG-R0.3.
- Нет полноценного параллельного stress/race gate, расчёта множества
  сценариев и performance SLO для 600 строк. Не заявлять production-ready.

**Следующий этап:** методологическая аттестация contract day-count and fees,
`MG-R0.3` source-availability + graph binding, затем `A05-R1.2` amendments,
prepayments и variable rates; отдельный `A05-R1.3` IFRS EIR; `A05-R1.4`
plan/fact with CALC-A01.

**Матрица 38 критериев после приёмки:** `9 PASS_UAT / 11 PARTIAL / 18 NOT_IMPLEMENTED`. Дополнительно T033 (все три варианта на реальном Chromium) закрыт; T008 (ровно 1%/period), T012 (весь набор отрицательных финансовых случаев), T034–T036 (полный Explain, все сравнения и concurrency) остаются частичными.
