# FIN R1 — построчный аудит качества 166 форм / 877 физических полей / 840 метрик

**Дата:** 2026-11-18. **Ветка:** `feat/fin-form-library-standalone`.
**Статус:** статический аудит источников и Bootstrap-контрактов; это **не**
подтверждение состояния пользовательской PostgreSQL и не S2-допуск.

## Артефакты

- [166 форм, качество по каждой форме](FIN-R1-166-forms-quality-summary.csv)
- [877 физических полей, качество каждого поля](FIN-R1-166-forms-field-quality-877.csv)
- [840 метрик, связь с полями и аналитическими моделями](FIN-R1-840-metrics-quality.csv)
- [Read-only PostgreSQL audit](FIN-R1-postgresql-live-contract-audit.sql)

CSV доступны для фильтрации в Excel. Наименование/единицы в CSV
переносятся из исходного TSV без автоисправления. Автоматические словарные
подписи рассматриваются как черновики, а не утверждённые переводы.

## 1. Общая структура

| Показатель | Значение |
|---|---:|
| Коды форм | 166 |
| FCP_TD | 44 |
| DF | 59 |
| SF | 63 |
| TYPED_FIELD_HINTS | 122 |
| Предварительные специализированные схемы | 32 |
| DOCUMENT + EXTERNAL_TAX | 12 |
| SINGLE_RECORD | 3 |
| ROWSET | 151 |
| EVIDENCE | 12 |
| Физические поля (CatalogBootstrap) | 877 |
| Структурированные физические поля | 793 |
| Поля метаданных EVIDENCE (12 x 7) | 84 |
| Строки исходной матрицы показателей | 794 |
| SOURCE_FIELD метрики в справочнике | 794 |
| Аналитические выходные метрики | 46 |
| Метрики всего | 840 |
| Формы с S2 approval в исходных статусах | 0 |
| Метрики с S2 approval по исходному Bootstrap | 0 |

**Важно:** 794 SOURCE_FIELD-строки — это 793 физических SOURCE_FIELD
и **F02.netProfit**, которому в `lib_metric_binding_exception` назначено
`FORM_DERIVED_CANDIDATE`. Каталог `lib_metric_definition` всё ещё
указывает `SOURCE_FIELD` для этого кода. Это требует отдельного
утверждения семантики и формулы, а не автоматического исправления истории.

## 2. Поля по физическому типу

| Тип | Количество |
|---|---:|
| REF | 215 |
| MONEY | 211 |
| DECIMAL | 144 |
| CODE | 100 |
| DATE | 68 |
| PERIOD | 55 |
| INTEGER | 44 |
| ENUM | 30 |
| TEXT | 8 |
| BOOLEAN | 2 |
| **Всего** | **877** |

Распределение числа полей по форме: 3 (1 форма), 4 (23 формы),
5 (92), 6 (31), 7 (16), 8 (3). Максимум — C05, P04, P06.

Физическая обязательность в CatalogBootstrap:
**3 REQUIRED у F02**, остальные **874** создаются с `required=false`.
Нельзя приравнивать это к утверждённому финансовому правилу:
у всех 794 строк исходной матрицы `PENDING_METHOD_REVIEW`.

## 3. Локализация и единицы измерения

| Критерий | Количество | Доля от 794 |
|---|---:|---:|
| Заполнен исходный заголовок RU | 9 | 1.13% |
| Заполнен исходный заголовок KK | 9 | 1.13% |
| Заполнен исходный заголовок EN | 9 | 1.13% |
| Заполнен исходный заголовок ZH-Hans | 6 | 0.76% |
| `PENDING_TRANSLATION` в источнике | 785 | 98.87% |
| `PROVISIONAL_NOT_S2` | 9 | 1.13% |
| `PENDING_UNIT_REVIEW` | 482 | 60.71% |
| `COUNT_OR_NUMBER_PENDING` | 44 | 5.54% |
| `FORM_CURRENCY_AND_SCALE` | 212 | 26.70% |
| `ISO_DATE` | 56 | 7.05% |
| `PENDING_METHOD_REVIEW` requiredness | 794 | 100% |

Итого 526/794 (66.25%) строк без определённой единицы/политики числа;
268/794 (33.75%) с классом единиц или даты, но **без S2 approval**.

Только DF-AST-01 (6 полей) и F02 (3 поля) имеют исходные
заполненные названия. У 164 из 166 форм нет ни одного явного
заголовка поля в исходной матрице. `FieldMetricDisplayDraftBootstrap`
генерирует 877 x 4 + 840 x 4 черновых подписей по глоссарию; статус
`AUTO_DRAFT_REVIEW_REQUIRED`, это не результат ручной проверки.

## 4. Семантика метрик

- 793 физических SOURCE_FIELD покрываются draft-only `lib_field_binding_contract`.
- 84 EVIDENCE metadata намеренно не являются финансовыми метриками.
- 1 F02.netProfit — формула-кандидат `netRevenue - cogs - operatingExpenses`,
  не физический редакторский ввод и не подтверждённая методика.
- 46 аналитических output-метрик создаются из `analytics-model-catalog.tsv`.
- 7 output-метрик приходятся на модели со статусом `CALC_PILOT`;
  остальные — на исходные дизайн-модели. `CALC_PILOT` сам по себе
  **не подтверждает исполняемый и протестированный алгоритм**.
- Все 840 записей в `lib_metric_definition` создаются с
  `qualification_state=CATALOG_UNQUALIFIED`.

## 5. Критические качественные пробелы

| Приоритет | Дефект / нехватка доказательств | Условие закрытия |
|---|---|---|
| P0 | 166/166 текущих версий создаются с CANDIDATE_UNQUALIFIED | Immutable S2 решение по версии и SHA каждой формы |
| P0 | 794/794 requiredness не имеют законченной проверки | Review min/max/null/required и PostgreSQL negative tests |
| P0 | 526/794 unit policy не определена | Утвердить тип единицы, валюту, precision, scale и grain |
| P0 | 164/166 форм без явных названий исходных полей | Реальный редакционный review на 4 языках |
| P0 | 12 документальных форм без полной цепочки evidence upload | Хранение/верификация SHA/доступ/retention + E2E |
| P0 | Источник не доказывает GREEN для реального PostgreSQL | Изолированный R3 gate + проверка live DB |
| P1 | 32 схемы — только кандидаты, не разрешён ввод | Доработать typed fields, справочники, валидаторы |
| P1 | 46 analytic outputs не подтверждены в source как qualified methods | Versioned algorithm and negative numerical tests |
| P1 | F02.netProfit неточно представлен как SOURCE_FIELD в metric catalog | Утвердить derived mapping, обойтись без мутации истории |
| P1 | R16.B policy таблицы не имеют автоматического backfill | Независимая загрузка, review и привязка к версии |

`FieldMetricContractGuard.matches()` проверяет структурную совместимость
schema SHA, field type и metric mapping, но **не выдаёт S2-допуск**.
`fin_intake` сейчас сохраняет `UNQUALIFIED_DRAFT`. R16.B DDL
сознательно ограничивает governance state значением
`CANDIDATE_UNQUALIFIED`; production-governance потребует
отдельного reviewed-state контракта и миграции.

## 6. Методика, происхождение данных и границы достоверности

Сделано соединение (code, fieldCode) исходной презентационной матрицы
с фактическим построением полей в `CatalogBootstrap`:
- F02: 3 поля из `intake-fields-v5.tsv`;
- 122 typed forms: `fieldContract` из `form-compatibility.tsv`;
- 32 specialized forms: `specialized-schema-candidates-r6.tsv`;
- 12 evidence forms: фиксированный семиполевой metadata contract.
Метрики: 794 source indicator rows + 46 уникальных model outputs.

Источник Git blob SHA:
- form-compatibility.tsv: `005848b6aad0c2ec572f8fa8c195169fd81f1211`
- form-field-presentation-review-r6.tsv: `060ff8a42c931857809317d3418f4426cb76e9eb`
- specialized-schema-candidates-r6.tsv: `bbc296f632b52a6086a6f0761e69b88ad85951ac`
- intake-fields-v5.tsv: `b54c8b4646d2317af53f8e445719b92448676752`
- analytics-model-catalog.tsv: `d94f20bb133536a19bd1efc0e42f1595e2f13937`

Этот аудит — статическая проверка текущей ветки. Фактические
`fin_lib.lib_form_field_policy`, `lib_form_contract_metric_binding`,
права пользователей, исторические версии форм и runtime `fin_intake`
потребуют выполнения read-only SQL, проверки фактов на PostgreSQL
и независимого S2 review. Нельзя трактовать 100% полей с Java типами
как 100% готовность финансового содержания.
