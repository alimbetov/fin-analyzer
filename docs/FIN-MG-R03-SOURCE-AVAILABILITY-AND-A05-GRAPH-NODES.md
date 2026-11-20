# FIN MG-R0.3 — Exact Source Availability and A05 Calculation Nodes

**Дата:** 2026-11-20  
**Ветка:** `feat/fin-form-library-standalone`  
**Статус:** `READ_ONLY_UAT_ACCEPTED`; **не** qualified execution graph, **не** финансовое S2.

## Квалификация

**Java 21 + real PostgreSQL 16: 5/5 PASS**, zero skipped/errors/failures: [run 44730441137](https://github.com/alimbetov/fin-analyzer/actions/runs/44730441137), включая opt-in security flag, аттестацию исходных версий C05 и цепочку изменённого A05-графика.

## Архитектура

`MetricSourceAvailabilityService` использует действующие `MetricIndicatorBindingService`, `MetricGraphResolver`, `FinIntakeUatSourceAdapter`/INGEST-2 и `LoanScheduleService`. **Нет** нового реестра финансовых метрик, второго writer или автоматической финансовой квалификации.

1. Принять точный UUID редакционной связи `metric_indicator_binding_revision`, `organizationId` и `projectId`, ожидания `periodType/periodCode/currency/reportingBasis/dataSpace`, **точные UUID ревизий исходных форм** по `formCode`.
2. Развернуть существующий связанный `MetricGraphResolver` в конкретные physical requirements. Ничего не искать по именам показателей или по `latest`.
3. Для каждой ожидаемой физической формы взять через INGEST-2 конкретную сохранённую ревизию, проверить SHA, организацию, проект, схему/версию, период, валюту и вид бухгалтерского отчёта.
4. Для одного числового значения проверить `PRESENT`. Если исходная форма ROWSET содержит более одной подходящей строки, не суммировать: `ROW_SELECTION_REQUIRED`. Нет значения → `MISSING_VALUE`.
5. Вернуть typed candidate value **строкой** с непродукционным статусом; без `fin_fact`, `CalcPlan` или публикации. Если методика производная и не утверждена, `METHOD_OR_METRIC_NOT_AUTHORED`.

## Статусы

| Состояние | Значение |
|---|---|
| MISSING_SOURCE | Нет явного source revision ID |
| STALE_SOURCE | Schema SHA / formVersion расходится с текущей методикой |
| INCOMPATIBLE_SCOPE | Период, валюта или reporting basis не совпадают |
| MISSING_VALUE | Поле пусто или отсутствует |
| ROW_SELECTION_REQUIRED | ROWSET без определённого grain/row selector |
| SOURCE_PRESENT_UNQUALIFIED | Проверенная исходная кандидатная цифра, не финансовый S2 |
| METHOD_OR_METRIC_NOT_AUTHORED | Методика отсутствует, например F02.netProfit |
| INPUTS_PRESENT_NOT_FINANCIALLY_QUALIFIED | Все источники присутствуют, но расчёт не допущен |

## A05 в едином графе

- `CALC-A05.CONTRACT_CASH` — точный R1.1 schedule result. Dependencies: C05 sourceRevisionId + revision app contract. Перед отдачей повторно подтверждаются SHA исходной C05 и строк результата.
- `CALC-A05.AMENDED_CONTRACT_CASH` — точный R1.2 amendment result. Dependencies: C05 sourceRevisionId, конкретный parent scheduleRevisionId и immutable eventSha256. Проверяется parentResultSha256 и amended resultSha256.
- Обе записи являются **specialized calculation result nodes**, не новой финансовой методикой и не сформированным полноценным DAG CalcPlan. `executable=false`, `financiallyQualified=false` у read-only descriptors — верно, потому что невозможно получить production execution admission через этот API.

**Условие доступа:** API по умолчанию выключен, opt-in только в изолированном UAT: `fin.calc.metric-source-uat.enabled=true`. Существующий A05 расчётный UAT отдельно регулируется `fin.calc.a05-intake-uat.enabled=true`.

## API

- `POST /api/v1/metric-graph/source-availability/resolve` (read-only preview, требует exact binding+source scope).
- `GET /api/v1/metric-graph/source-availability/a05/{exactScheduleRevisionId}?organizationId=...&projectId=...`.
- `GET /api/v1/metric-graph/source-availability/a05/amendments/{exactAmendmentRevisionId}?organizationId=...&projectId=...`.

## Ограничения и следующий шаг

Это **не** автоматическая генерация графов 154 форм и не квалифицированное исполнение формул. Требуются explicit row grain, многоисточниковый period-alignment policy, версионированные business-goal bindings для A05 outputs, CalcPlan attachment/fencing, конечные formula executors, negative concurrency E2E и отдельный финансовый S2. Для реального borrower DSCR нужен CFADS с точно утверждённой методикой, а для фактических платежей — A01. Платежи A05 являются сценарными, а не фактом F03.
