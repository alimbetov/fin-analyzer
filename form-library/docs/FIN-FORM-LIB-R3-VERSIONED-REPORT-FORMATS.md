# FIN-FORM-LIB-R3 — Версионируемые форматы табличных отчётов

**Status:** технический MVP без React, без финансового S2, без публикации FIN HTAP.

## Инвариант

Значения ячеек сохраняются каноническими десятичными строками со статусами `PRESENT / MISSING / NOT_APPLICABLE`. Форматирование всегда отделено: API возвращает `value` и `formattedValue`. Поворот таблицы, язык, масштаб, округление и правила вывода отрицательных чисел НЕ меняют исходные данные или `contentSha256`.

### Новая схема

Liquibase `004-lib-report-format.sql` добавляет таблицу `fin_lib.lib_report_metric_format` и поле `lib_report_definition.format_version`. Каждая настройка привязана к `reportId + metricId + formatVersion`. При изменении полный набор форматов записывается в новую версию, прежний набор остаётся неизменным. Для конкурентных обновлений требуется `expectedFormatVersion`. Версия данных и версия формата независимы.

| Параметр | Варианты |
|---|---|
| `formatKind` | MONEY, DECIMAL, INTEGER, PERCENT_POINTS, RATIO_PERCENT |
| `decimalPlaces` | 0–12 знаков |
| `scaleDivisor` | 1, 1000, 1000000 (подпись масштаба на четырёх языках) |
| `roundingMode` | HALF_EVEN, HALF_UP, DOWN |
| `groupingEnabled` | true/false; локализованная группировка |
| `negativeStyle` | MINUS/PARENTHESES |
| `currencyDisplay` | NONE/CODE (ISO 4217 для MONEY) |
| `periodLabelStyle` | CODE, SHORT, LONG (аргумент чтения) |

PERCENT_POINTS означает: исходное 12.5 показывается как 12.5 %. RATIO_PERCENT означает: исходное 0.125 показывается как 12.5 %. Преобразование на 100 относится только к отображению и не квалифицирует семантику финансового показателя. MONEY разрешён лишь для MONEY-полей, INTEGER — для INTEGER, DECIMAL/PERCENT — для DECIMAL. Квалификация формулы не следует из формата.

`MISSING` — отсутствующее значение, отображается «—». `NOT_APPLICABLE` — «н/п» и аналогичная метка локали. `PRESENT:0` остаётся числовым нулём. Не импортировать отформатированную строку вместо исходного `value`.

## REST API

- `GET /api/v1/report-grids/{id}/formats?organizationId={uuid}&formatVersion=1` — текущая или архивная версия.
- `PUT /api/v1/report-grids/{id}/formats` — атомарный новый полный набор; обязательны `organizationId`, `expectedFormatVersion`, `formats[]`.
- `GET /api/v1/report-grids/{id}/grid?organizationId={uuid}&locale=ru&timeAxis=COLUMNS&formatVersion=2&periodLabelStyle=LONG` — исходные значения, отображение, локализованные заголовки.
- `GET /api/v1/report-grids/{id}/revisions/{number}/grid` — восстановление старой ревизии с явной версией форматирования.

Пример PUT, когда отчёт содержит ровно один показатель:

~~~json
{"organizationId":"00000000-0000-0000-0000-000000000001","expectedFormatVersion":1,
"formats":[{"metricCode":"F02.netRevenue","formatKind":"MONEY","decimalPlaces":1,"scaleDivisor":1000,
"roundingMode":"HALF_EVEN","groupingEnabled":true,"negativeStyle":"PARENTHESES","currencyDisplay":"CODE"}]}
~~~

## Ограничения

Это автономный H2-прототип без авторизации (только localhost); React и генераторы готовых XLSX/HTML/PDF не реализованы. Экспорты должны использовать общий read-only контракт raw/display и отдельный рендерер. Для точного воспроизведения печатного отчёта впоследствии нужно фиксировать версию рендерера и локальных данных. Текущие 166 кодов и метрики сохраняют статус неутверждённых кандидатов; публикация FactSet и финансовый ACTUAL запрещены.
