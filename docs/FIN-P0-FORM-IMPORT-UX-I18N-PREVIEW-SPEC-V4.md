> **SUPERSEDED FOR ACTIVE DEVELOPMENT — 2026-11-16.** После повторного аудита действует [ТЗ V5: Excel заголовки только на выбранном языке, стилизованная таблица React и диагностические выводы/происхождение на четырёх языках](FIN-P0-UNIFIED-FORM-EXCEL-REACT-I18N-DIAGNOSTIC-V5.md), с [независимым аудитом V5](FIN-P0-V5-INDEPENDENT-REQUIREMENTS-AUDIT.md). **Конфликтное требование V4 о трёх-/четырёхъязычных заголовках в одной Excel-ячейке ОТМЕНЕНО**; выводы и статус китайских переводов должны следовать V5. V4 оставлено только как история решений. Существующую защиту backend не ослаблять.

# FIN P0 — ТЗ V4: универсальная загрузка, i18n RU/KK/EN/zh-Hans и Preview таблицы

**Дата:** 2026-11-16. **Статус:** проектирование / NOT IMPLEMENTED. **Ветка:** feat/fin-r0-r1a. **PR #2:** Draft. **Production:** HOLD.

**Связанный технический аудит:** [FIN-P0-FORM-INTAKE-CRITICAL-AUDIT-V4.md](FIN-P0-FORM-INTAKE-CRITICAL-AUDIT-V4.md). Этот документ заменяет ТЗ V3 в части активных требований к загрузке, локализации и предпросмотру.

## 1. Цель, границы, пользовательский результат

Пользователь выбирает проект → финансовую форму → язык → тип и конкретный отчётный период → учётную и отчётную валюты / масштаб. Он может заполнить динамическую React-форму или скачать понятный версионированный Excel-шаблон. **Оба пути обязаны преобразоваться в одни canonical rows и одинаковый ValidationReport.** После загрузки/валидации система показывает **красивую read-only таблицу Preview**, а не сразу утверждает или публикует данные. Пользователь видит все значения, нули/пропуски, выбранный проект, валюту, период, ошибки и только затем подтверждает сохранение черновика.

**Текущий scope:** FormSchema, locales, time model, currencies/scale, Excel/React UI, importer/validator, PreviewSnapshot и project-bound draft. **Отложить:** разработку Keycloak/JWT/bearer-token/ABAC, новый UI выбора стратегии анализа бизнеса, массовую диагностику и внедрение production. Существующие серверные guards остаются fail-closed; нельзя открывать анонимные HTTP endpoints ради smoke.

## 2. Архитектура: одна схема — два ввода — один Preview

    Catalog (166, readiness by form)
           → Published FormSchema + TranslationBundle
           → Project + Period + Currency/Scale context
              ├── Dynamic React controlled form
              └── Excel V2 template/generator/parser
                         ↓
               CanonicalFormNormalizer
                         ↓
               Shared FormValidator
                         ↓
               PreviewSnapshot
              ├── BLOCKED: fix and retry
              └── READY_TO_SAVE: explicit confirmation
                         ↓
                Project-bound SavedDraft
           [later: independent review → FactSet → graph]

**Главный инвариант:** machine keys (formCode, fieldCode, sourceRowId, formVersion, schemaHash, periodCode, currencyCode) не зависят от языка. Все поля, валидации и layout исходят из одной версии FormSchema. Нет двух отдельный списков полей в Java и React. Разделить редактор (editable), preview (read-only), финансовую методику (позднее).

Форма может иметь тип **FIXED_FIELD**, **REPEATING_LEDGER**, **EVIDENCE_ONLY**. Документальным формам не выдумывать числовые показатели. Из 166 записей только квалифицированные для ввода версии становятся ACTIVE; остальные показывают SCHEMA_NOT_READY. Первые пять пилотов: F01/F02/F03/DF-CSH-01/DF-CSH-02. 

## 3. Динамический языковой реестр: китайский закладываем сейчас

| Название | Стабильный locale tag | Пилотный статус |
|---|---|---|
| Русский | ru | ACTIVE после утверждения переводов |
| Қазақша | kk | ACTIVE после утверждения переводов |
| English | en | ACTIVE после утверждения переводов |
| 简体中文 | zh-Hans | PLANNED: архитектура и визуальные тесты; переводы ещё не утверждены |

Не фиксировать в сущностях/Java/TypeScript enum из трёх языков. Модель: LanguageDefinition(code, formatterLocale, direction, enabled, status), FormTranslation(formCode,formVersion,locale,title,description,revision,status), FieldTranslation(fieldCode,locale,label,help,revision,status), ErrorTranslation(messageKey,locale,text,status). Добавление пятого языка не должно требовать миграции ключей.

**ru/kk/en** — доступные после проверенной локализации; **zh-Hans** — зарезервированная будущая локаль, при отсутствии утверждённых переводов явная TRANSLATION_NOT_AVAILABLE, а не русские строки, выдаваемые за китайские. Китайские финансовые термины необходимо отдельно проверить специалистом. Нормализовывать zh-CN→zh-Hans только через явный alias. Код казахского — kk, не kz.

Разделить semanticSchemaHash (поля/типы/валидация), translationBundleHash (подписи) и displayLayoutVersion (порядок, ширины, набор заголовочных языков). Добавление zh-Hans не меняет исходные числа и hashes канонических фактов. Locale меняет только отображение, а не KZT в CNY и не числовую точность.

**Excel row 2:** сейчас три языка RU/KK/EN в одной ячейке заголовка по отдельным строкам. После утверждения ZH — в каждой такой ячейке RU/KK/EN/ZH; количество столбцов и номер первой строки данных не меняются. Реестр headerLocales содержит динамический список языков с версией и порядком, не захардкожен. При 4 языках увеличивается высота шапки/ширина колонок. В React по умолчанию показывать выбранный язык, другие — по запросу/подсказке. Не принуждать пользователя видеть сразу 4 языка в каждой строке React.

UTF-8 в БД и API, Unicode/CJK-friendly font fallback; не передавать шрифты как артефакты. Обязательны тесты длинных казахских и китайских строк без обрезаний.

## 4. Схема формы и типизированные бухгалтерские периоды

FormDefinition(formCode,kind,activeVersion,status,sectorApplicability); FormSchema(version,fieldDefinitions,sections,validationRules,allowedPeriodTypes,allowedValueCoverage,languagePackVersion,layoutVersion,hash); FieldDefinition(fieldCode,order,labelKey,valueKind,required,repeatable,sourceRowPolicy,unitCode,currencyPolicy,precision,subtotalPolicy,timeRole).

ReportingPeriod(type,code,calendarId,calendarVersion,startInclusive?,endExclusive?,asOfDate?,valueCoverage).

| Type | Canonical code | Отображение RU | Семантика |
|---|---|---|---|
| WEEK | 2026-W03 | 12–18 января 2026 | ISO week-year, Пн–Вс |
| MONTH | 2026.10 | Октябрь 2026 | [2026-10-01, 2026-11-01) |
| QUARTER | 2026-Q3 | III квартал 2026 | [2026-07-01, 2026-10-01) |
| HALF_YEAR | 2026-H2 | II полугодие 2026 | [2026-07-01, 2027-01-01) |
| YEAR | 2026 | 2026 год | [2026-01-01, 2027-01-01) |
| AS_OF | 2026-10-31 | На 31.10.2026 | Остаток на дату, не оборот |

**Обязателен valueCoverage:** PERIOD_ONLY / YEAR_TO_DATE / LIFE_TO_DATE / AS_OF. Например F02 Q2 за квартал нельзя смешивать с F02 Q2 накопительно с января. F01 может быть срезом на конец месяца, но stock не суммируется с flow. Для SUM/aggregate требуются непересекающиеся интервалы и финансово совместимые политики.

ISO 2026-W01 начинается 2025-12-29. 2020-W53 valid, 2021-W53 invalid; календарные leap и quarter boundary проверяются сервером. Fiscal calendars требуют календарного ID/версии; нестандартный год или 4-4-5 без реализации resolver => UNSUPPORTED_CALENDAR, не превращать в обычный календарный год. Не использовать универсально YearMonth. Клиент не назначает дату начала/конца свободным текстом.

## 5. Учётная валюта и масштаб — отдельная семантика

**Project accountingCurrency** (например, KZT) хранится в версии контекста проекта, явно видима в UI и Excel. **ReportCurrency** — валюта представления формы. **RowCurrency** — валюта отдельных операций, когда ledger мультивалютный. **AmountScale**: 1 / 1000 / 1000000; отдельно от currency и от unitCode.

Отчёт в USD при учётной KZT без FX policy не пересчитывать автоматически; показывать FX_POLICY_NOT_CONFIGURED / NOT_COMPARABLE. Нельзя складывать разно-валютные значения в «Итого». Для количеств, процентов и дней масштаб денежной формы не применим. Канонические суммы и ввод сохранять в decimal strings / Java BigDecimal без JS float; скрытое умножение scale дважды запрещено.

**Визуализация:** над значениями всегда написано: «Валюта отчёта KZT · суммы в тысячах» или аналог. Для 400,00 тыс. KZT можно раскрыть эквивалент 400 000,00 KZT, если политика это разрешает. NULL/MISSING ≠ explicit zero ≠ NOT_APPLICABLE. Negative форматируется минусом/скобками, не только цветом. Локаль zh-Hans не переключает валюту на CNY.

## 6. Excel V2: row 1 / row 2 / row 3+

**Лист FIN_Data — видимый.** A1:H1 содержит именно machine values с подписью в примечании или листе Instructions; Excel layout не сдвигать даже ради длинных заголовков.

| Row | A | B | C | D | E | F | G | H |
|---|---|---|---|---|---|---|---|---|
| 1: паспорт | F02 | ru | 2026.10 | Отчёт о прибылях и убытках | KZT (report) | 1000 (scale) | FIN-DEMO (project code) | KZT (accounting) |
| 2: заголовки | Код строки (RU/KK/EN) | Код поля (RU/KK/EN) | Наименование (RU/KK/EN) | Значение (RU/KK/EN) | Валюта (RU/KK/EN) | Ед.изм. (RU/KK/EN) | Период (RU/KK/EN) | Примечание (RU/KK/EN) |
| 3+: values | pnl | netRevenue | Чистая выручка | 400.00 | KZT | пусто для MONEY | 2026.10 | — |
| 4 | pnl | cogs | Себестоимость | 260.00 | KZT | пусто для MONEY | 2026.10 | — |

FIN_Data row 2 сохраняет **три локализованных заголовка в каждой ячейке с переносами строк**, не три дополнительных строки Excel. После утверждения ZH — четвертый язык в ячейке (свой headerLocales/version). Не изменять колонки. Row 3 — всегда первая строка данных. Поля A/B — стабильные машинные коды; C — человекочитаемая локализованная подпись; D — редактируемые деньги/значения; E/F/G — по политике формы; H — комментарий. SourceRowID повторяющихся ledger строк должен быть уникальным.

Отдельный veryHidden _FIN_Metadata: templateFormat=FIN-FORM-XLSX-V2, formVersion, semanticSchemaHash, translationBundleHash, headerLocales, projectId, projectVersion, periodType/code, valueCoverage, calendarId/version, start/end/asOf, accountingCurrency, reportCurrency, amountScale, issuanceId. Hidden metadata **не является правом доступа**. Вариант V1 сохраняется через явный layout dispatch и регрессию.

**Качество оформления Excel:**
- Row 1: крупные, различимые реквизиты, названия разрешены к переносу; D1 шире остальных; visible accounting currency и scale.
- Row 2: тёмный или светлый контрастный фон, жирные многострочные подписи, **горизонтально и вертикально CENTER**, высота 64–96 pt по локалям.
- Rows 3+: **все текстовые ячейки CENTER и vertical CENTER**, разрешён word wrap; **числа RIGHT и vertical CENTER**, фиксированное число знаков/группы разрядов; курсив только для подсказок.
- Нулевые значения показывать 0/0,00, пропущенные — пусто с пояснением, отрицательные — знак минус или скобки.
- Frozen 2 top rows, если позволяет layout — 2 технические кодовые колонки. Подсветка только редактируемых ячеек, читабельные колонки, data validation/dropdowns, примеры ввода, печать landscape/fit-to-width; запрет ####.
- Названия/строки не должны исполняться как формулы; запрет на неподдерживаемые формулы, внешние ссылки, лишние столбцы/листы, неправильный размер/архив и подмену проекта/периода.
- Для денежных точных величин вне безопасной Excel NUMERIC точности — ввод через decimal-as-text с серверной проверкой; никаких неявных округлений.

## 7. React: генератор + отдельный Preview

### 7.1. Экран выбора и редактирования

Понятные имена проекта и формы, языки RU/KK/EN (zh-Hans активировать позже), тип периода + конкретный период + объяснение покрытия суммы, видимая учётная/отчётная валюта и масштаб. Не вынуждать обычного пользователя вводить UUID. Для недоступной формы показывать SCHEMA_NOT_READY, а не фиктивную структуру.

Одинаковые упорядоченные поля формы в Excel и React, локализованные подписи и помощь, обязательные поля, повторяющиеся строки, поиск, логичные разделы. Управление: «Скачать Excel», «Загрузить Excel», «Проверить ввод», «Предпросмотр», «Сохранить черновик». При смене проекта/формы/периода с заполненными полями — подтверждение или сохранение draft; не очищать введённые значения молча.

### 7.2. Единый серверный PreviewSnapshot

**Компонент:** `FormPreviewTable` — самостоятельный read-only React-компонент. **Источник данных:** `PreviewSnapshot` от общего нормализатора/валидатора. Никаких отдельных JSX-таблиц для ручного и Excel ввода; разница только в provenance и координатах ошибки.



После server parse/validation ручного ввода или XLSX всегда **PREVIEW**, а не сразу SAVED/APPROVED. Статусы PARSED, VALIDATION_FAILED, PREVIEW_READY, SAVED_DRAFT; отдельно позже REVIEWED/PUBLISHED. Preview read-only и server-authoritative: идентификатор preview, hash нормализованных строк и контекста, version form/schema/layout, project, period/coverage, currency+scale, source type, errors and warnings.

Паспорт на экране: «Проект · Форма/версия · Период/границы · Тип периода · Валюта учёта · Валюта отчёта · Масштаб · Загружено из Excel / введено вручную». Над таблицей — counts total/populated/missing/errors/warnings. Названия и коды в одном месте, не скрывать исходную экономическую единицу. Локализация Preview использует одни labels со схемой; переключение языка не меняет raw input/hash.

**Таблица предпросмотра** (read-only): № / Показатель / Код / Период / Значение / Валюта/ед. / Статус. Группы/подытоги по определению формы, никакой суммы несопоставимых строк. У каждой строки fieldCode/sourceRowId и optional cell coordinate FIN_Data!D3; ошибки видимы рядом со строкой, не только сверху. В ERROR режиме показывать таблицу того, что удалось распарсить, и блокировать кнопку «Подтвердить и сохранить». Исправление данных возвращает в редактор или на выбор файла без удаления предыдущего confirmed draft.

**Строгие правила выравнивания React Preview:**

| Элемент | Horizontal | Vertical | Детали |
|---|---|---|---|
| Заголовок таблицы | CENTER | MIDDLE | Ясные названия, перенос строк |
| Текстовые ячейки: название, период, коды, статус | CENTER | MIDDLE | Длинные RU/KK/ZH не обрезать |
| Числа, денежные суммы, проценты, количественные показатели | RIGHT | MIDDLE | Tabular figures, группировка тысяч и нужная precision |
| Ноль | RIGHT | MIDDLE | Отображать реальный 0, а не прочерк |
| Отсутствует/неприменимо | CENTER | MIDDLE | Отдельный понятный статус |
| Итого | по типу | MIDDLE | Bold, subtle top border, только утверждённая subtotalPolicy |

Изолированный CSS namespace (не переопределять существующую глобальную .data-table):

    .fin-preview-table th { text-align: center; vertical-align: middle; }
    .fin-preview-table td { vertical-align: middle; }
    .fin-preview-table .cell--text { text-align: center; }
    .fin-preview-table .cell--number {
      text-align: right;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }

Поддержать sticky table headers, аккуратное чередование строк, hover/focus, хорошую контрастность, горизонтальную прокрутку без потери числовой колонки, обработку 100–500 строк через pagination/virtualization. Семантическая table с caption, thead/th scope, keyboard, 200% browser zoom, тест на macOS и Windows; шрифтовой fallback для китайского.

**Внешнее числовое форматирование:** decimal-safe formatter из **raw strings**, без конвертации всей денежной суммы в JS Number. Locale-specific grouping/decimal separators; каноническое значение и hash не меняются при switch ru → zh-Hans. ru-RU 1 234 567,89; en-US 1,234,567.89; zh-CN 1,234,567.89 без неявного множителя 万. Отдельно raw, entered-in-scale value и optional normalized value.

### 7.3. Preview DTO (предлагаемые контракты, пока не API)

Preview request: projectId, projectVersion, formCode, formVersion, schemaHash, locale, translationVersion, reportingPeriod {type,code,calendarId,calendarVersion,valueCoverage}, money {accountingCurrency,reportCurrency,amountScale}, inputMode MANUAL|XLSX, rows [{sourceRowId,fieldCode,rawValue,rowCurrency?,unitCode?}]. Сервер разрешает проектный context и контролирует расхождения, не доверяет переданному currency из клиента.

Preview response: previewId, confirmationDigest, status PREVIEW_READY|VALIDATION_FAILED, projectId, form/period/monetary context, counts, rows [{sourceRowId,fieldCode,labelKey,valueKind,rawValue,presence,validationStatus,sourceCell?}], findings [{ruleCode,fieldCode,sourceRowId,excelCell?,severity,translationKey}], sourceFormat/sourceSha.

Кнопка «Сохранить черновик» отправляет только previewId + confirmationDigest + expectedDraftRevision; сервер проверяет что preview не устарел после изменений, idempotent save создаёт или обновляет **новую версию** draft без silent overwrite. Валидный preview **не** означает независимое утверждение источника, признание бухгалтерских документов и публикацию FactSet.

## 8. Проект и версии

Серверный проект: projectId UUID, projectCode, projectName, organizationId/economicEntityId/businessScopeId (серверные), projectVersion, accountingCurrency, calendarRef. В данном этапе реализовать жизненный цикл **тестового/draft** контекста без IAM; остальное внедрять позже. Excel G1 — человекочитаемый projectCode, hidden manifest — projectId. Смена выбранного проекта при загрузке Excel => PROJECT_CONTEXT_MISMATCH; никакого привязывания по текстовому названию компании.

Source revision pinning: projectId, form version, semanticSchemaHash, period code/type/coverage, calendar version, money context, source digest. V1 и V2 не импортируются одним parser без explicit dispatch. Старый FIN-PILOT-V1 не удалять.

## 9. Smoke / RED acceptance matrix (15 scenarios)

| ID | Что проверить | PASS |
|---|---|---|
| IMP-01 | React schema F02 ru/kk/en + future zh-Hans fixture | Единые fieldCodes; zh-Hans PLANNED не выдаёт выдуманные переводы |
| IMP-02 | Excel F02 внешний вид | Ряд 1 паспорт с currency+scale, ряд 2 заголовки, ряд 3+ значения |
| IMP-03 | React vs Excel ввод одинаковых F02 чисел | CanonicalFormSubmission и ValidationReport идентичны кроме source metadata |
| IMP-04 | WEEK / MONTH / Q / HALF_YEAR / YEAR / AS_OF | Правильные границы, ISO week-year, запрет invalid period |
| IMP-05 | YEAR_TO_DATE / PERIOD_ONLY | Без double count, incompatible coverage rejected |
| IMP-06 | KZT/USD reportCurrency и rowCurrency | Не объединяются без FX policy |
| IMP-07 | 1 / 1000 / 1000000 и BigDecimal | Ни округления, ни двойного умножения |
| IMP-08 | Empty / 0 / NOT_APPLICABLE / negative | Разные типы состояния и формат |
| IMP-09 | XLSX подмена project/form/schema/period/field, формула | Точная ошибка, черновик не перезаписан |
| IMP-10 | После загрузки до сохранения | Тот же Preview после React и Excel, readable, ошибки в строке |
| IMP-11 | Alignment Excel и React | Текст и headers CENTER, числа RIGHT, все MIDDLE |
| IMP-12 | Длинные казахские названия и подготовленный ZH fixture | Нет overflow/truncation, отсутствие доступной ZH локали отмечено |
| IMP-13 | 100–500 строк, zoom 200%, keyboard | Читаемость, прокрутка, focus, номера и статусы |
| IMP-14 | Ошибки, исправление, повторная загрузка/смена проекта | Не теряются/не подменяются подтверждённые drafts |
| IMP-15 | Регрессия старых Excel и React | V1 остаётся работоспособен, V2 не ломает legacy |

**Выполнять по вертикали:** F02 MONTH/KZT/ru → Excel и React → общий parser/validator → Preview → save draft → F01 AS_OF → F03 FLOW → остальные частоты → переключение i18n. Сначала unit+contract RED, затем Java/PostgreSQL tests, Vitest, Playwright и ручная проверка Excel/React на MacBook. Keycloak/real auth browser E2E **отложен**, его нельзя пометить PASS без запуска.

## 10. Обязательные пакеты реализации

1. **V4-R0:** утверждение DTO FormSchema/ReportingPeriod/MoneyContext/Preview, golden fixtures и RED tests.
2. **V4-R1:** единый schema/translation registry, ру/каз/англ для 5 форм, planned zh-Hans, calendar resolver и money.
3. **V4-R2:** единые Excel V2 и React dynamic renderer, row 1/2/3+, layout, V1 compatibility.
4. **V4-R3:** общий normalizer/validator и PreviewSnapshot + стилизованная таблица с проверяемой типографикой.
5. **V4-R4:** серверный project-bound draft, правильный period/currency pinning, conflict/revision policy.
6. **V4-R5:** smoke IMP-01…15 на одном SHA + результаты без придуманных PASS.

**Готовность ТЗ:** архитектура предложена; код V4 ещё не реализован. Стратегический интерфейс анализа и новые финансовые методы — следующий большой этап только после качественной загрузки. Production HOLD остаётся.