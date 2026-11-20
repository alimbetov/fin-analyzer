# FIN CALC-A05-R1.0 — C05 Loan Contract and Fee Studio: bounded acceptance

**Дата:** 2026-11-20  
**Ветка:** `feat/fin-form-library-standalone`  
**Статус:** `A05_R10_TYPED_PG16_AND_CHROMIUM_UAT_PASS` — только input contract и версионирование; не расчёт кредитного графика и не финансовый S2.

## Подтверждённые GitHub Actions

- **Backend / PostgreSQL 16 / Java 21 — 10/10 PASS**, no errors, no failures, no skipped. Квалифицированный commit `5888bd41cfc50253f0902b3e734a779942409aa8`. Run: https://github.com/alimbetov/fin-analyzer/actions/runs/44687823013 .
- **React / Chromium + real Spring Boot + PostgreSQL 16 — 1/1 PASS**, no mocks, no skipped. Квалифицированный commit `ba9427bdbcf6ba6f72c9d93cfc53565d97a05d35`. Run: https://github.com/alimbetov/fin-analyzer/actions/runs/44687393695 .
- Проверки выполнены на **разных точных коммитах**; ни один из этих двух результатов не выдаётся за единый HEAD suite, которого не запускали. После последнего backend-коммита добавлялись только документы.

## Реализация

`fin_intake` остаётся **каноническим writer C05**. Метод `FormDraftService.saveWithA05Companion` включает digest typed data в тот же idempotency command и создаёт новую C05-ревизию, если изменились только условия/комиссии. Spring `LoanContractIntakeService` в **одной DB-транзакции** добавляет sidecar и связывает exact C05 source revision ID/SHA. Отложенный PG16 trigger не позволяет обновить обычную C05 после появления A05-identity без её typed companion.

**Миграции:** 056 — типизированные loan identity, loan contract revision, rate/calendar/drawdown/fee rules и receipts; 058 — уникальность по `organizationId + projectId + creditorRef + agreementRef`. Исторические миграции не изменялись. Оба расширения аддитивны. Таблицы immutable, в `fin_intake`, а не отдельный finance writer.

**Java:** `LoanContractIntakeService`, `LoanContractUatController`.  
**React:** `LoanContractStudio.tsx` — вкладка C05 в feature-flag `VITE_FIN_A05_UAT=true`; обычный SAVE C05 отключён в этом режиме.  
**REST:** `POST /api/v1/uat/a05/c05/revisions` с `Idempotency-Key`, `GET /api/v1/uat/a05/c05/revisions/{exactSourceRevisionId}` с org/project scope. Backend feature flag `fin.calc.a05-intake-uat.enabled=true`.

## Проверенный функциональный профиль

KZT, BORROWER, SINGLE planned drawdown, MONTHLY, FIXED nominal rate, SIMPLE interest. Хранятся даты договора/выдачи/начисления/погашения, explicit day-count, payment day, rounding, repayment method and immutable rule revision IDs. Две комиссии: SETUP one-time и SERVICE recurring. Отдельные `UNREVIEWED` признаки IFRS EIR и KZ GESV; ни один не рассчитывается.

| Контроль | Доказательство | Итог |
|---|---|---|
| Source C05 real ROWSET, businessKey and exact source SHA | PG16 and reader rehash | PASS |
| Initial loan with rate, calendar, one drawdown, two typed fees | PG16 + React E2E | PASS |
| Only-fee change yields a new C05 source revision, even if C05 value SHA remains identical | PG16 + React browser | PASS |
| Old and new typed fee revision readback and immutable history | PG16 + React browser | PASS |
| Idempotency same payload, rejection if key reused with different input | PG16 | PASS |
| Duplicate loanBusinessKey and duplicate creditor+agreementRef across C05 slots are rejected atomically | PG16, additive migration 058 | PASS |
| Invalid C05 source row / fee configuration and cross-org/project access | PG16 | PASS |
| Generic writer cannot create an orphan C05 revision for A05 identity | PG16 deferred constraint trigger | PASS |
| Attempted UPDATE/DELETE of typed contract/fees fails | PG16 | PASS |
| Responsive React loan terms/commission editor with exact typed save and readback | Real Chromium (single E2E) | PASS |

## Статус исходной матрицы 38 checks

Полная матрица: [FIN-CALC-A05-R1-ACCEPTANCE-MATRIX.tsv](../FIN-CALC-A05-R1-ACCEPTANCE-MATRIX.tsv). Привязанные к реально выполненным тестам статусы:
- **PASS_UAT — 3**: T001, T003, T005.
- **PARTIAL — 6**: T002 (отдельный process restart не был доказан), T004 (не все negative cases rate/fee basis), T006 (в R1.0 только fixed rate and limited policy variants), T032 (нет расчёта графика, кнопка Calculate ещё не применима), T037 (ввод запрещает S2 flags; будущие расчётные результаты ещё отсутствуют), T038 (Java/PG/Chromium успешно, но разные HEAD).
- **NOT_IMPLEMENTED — 29**: прочие критерии, включая расчёт аннуитетного/дифференцированного/custom графика, EIR, ГЭСВ, план-факт, DSCR, сценарии и полный графический Explain.

**Важно:** статус `PARTIAL` не следует читать как финансовое выполнение соответствующей проверки. Вне этой input-вертикали все будущие финансовые S2/production gates закрыты.

## Исправленные реальные сбои при браузерной квалификации

1. После успешной initial save менялся `instanceId` и компонент React монтировался заново. Тест перенесён на устойчивую проверку actual C05 revision и backend readback, а не transient notification.
2. BigDecimal после REST JSON представлен числом; React переводит `principal`, `nominalAnnualRatePct`, `amountOrRate` в строку для корректного повторного ввода.
3. Backend возвращает fees ORDER BY `fee_code`. Браузерная приёмка выбирает SETUP по stable feeCode, а не по устаревшему индексу UI. Реальное редактирование нужной комиссии подтверждено итоговым PASS.

## Следующий этап: A05-R1.1

Контрактный график ANNUITY / EQUAL_PRINCIPAL / CUSTOM по конкретным датам и day-count. Использовать имеющиеся typed contract/rate/calendar/fee revisions и MG graph. Отдельные golden (нулевая ставка, нерегулярные даты, комиссии финансируемые/удержанные), BigDecimal, последний principal residual, immutable output revision, объяснение каждой строки. Никаких синтетических payments posted to F03 или автоматического IFRS EIR / KZ GESV.
