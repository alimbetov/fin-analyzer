# FIN P0 — ТЗ V5: единая форма ввода / Excel на выбранном языке / четырёхъязычная объяснимая диагностика

**Дата:** 2026-11-16 · **Статус:** SPECIFICATION / NOT IMPLEMENTED  
**Repository:** alimbetov/fin-analyzer · **branch:** feat/fin-r0-r1a · **PR #2:** Draft · **main:** unchanged  
**Основание:** [FIN-P0-V5-INDEPENDENT-REQUIREMENTS-AUDIT.md](FIN-P0-V5-INDEPENDENT-REQUIREMENTS-AUDIT.md)  
**Заменяет как активное ТЗ:** V4 FIN-P0-FORM-IMPORT-UX-I18N-PREVIEW-SPEC-V4.md; V3/V2 оставлены как история решений.  
**Цель:** добиться единого, удобного и достоверного способа загрузки бизнес-форм, при этом заложить полный слой локализации уже существующих объяснимых диагностических результатов на четырёх языках. Новые финансовые методики и стратегический компас не создавать.

## 1. Обязательные решения (выполнять буквально)

1. **Один versioned FormSchema** — для React editable grid, Excel XLSX, серверного парсера/валидатора, Preview и локализации метрик.
2. **Excel на языке выбора:** если пользователь выбрал русский — все видимые шапки, названия полей, подсказки, инструкции на русском; казахский — на казахском, английский — на английском; после активации китайских переводов — на китайском. **НЕ** объединять четыре языка в одной ячейке Excel. Коды полей и машинные ключи не переводить.
3. **React форма ввода — полноценная форматированная редактируемая таблица**, а не набор технических текстовых полей. После ручного ввода и после XLSX загрузки использовать **один** серверный PreviewSnapshot и один read-only FormPreviewTable.
4. **Два независимых языковых выбора:** inputLocale для Excel/React source-facing captions; outputLocale для просмотра/генерации диагностики. У одного FactSetRevision и diagnostic snapshot может быть сколько угодно read-only языковых представлений.
5. **RU, KK, EN, ZH:** стабильный реестр BCP47 ru/kk/en/zh-Hans. Китайский сейчас поддерживается контрактно, но реальные финансовые переводы и выход Chinese diagnostic обязаны пройти проверку специалистом; отсутствующие переводы не заменять выдуманными или скрытым fallback.
6. **Сквозная provenance:** любая диагностическая фраза/значение должны ссылаться на неизменные ruleCode, metricCode, sourceRevision, sourceRowId, sourceField, factId, период, метод и валюту. Перевод не меняет числа, формулы, статус S2 HOLD или факт отсутствия доказательства.
7. **Отложено:** новые работы Keycloak / Bearer Token / ABAC / strategy-selection UX / новые финансовые методики / production. Уже реализованная fail-closed API security не ослабляется.

## 2. Пользовательские сценарии и состояние

**US-01:** Выбрать проект → форму → язык → период/coverage → валюту/масштаб → получить динамическую React таблицу, увидеть значения и ошибки → Preview → подтвердить сохранение черновика.

**US-02:** Выбрать те же параметры → скачать версионированный Excel-шаблон, оформленный на выбранном языке → заполнить строки с 3-й → загрузить в выбранный проект → общая валидация → тот же Preview → подтвердить сохранение черновика.

**US-03:** Пользователь загружает файл с другим языком, чем язык UI: система читает machine codes/manifest, показывает Preview на выбранном output/displayLocale, а исходный шаблон сохраняет inputLocale. Не требовать перевода данных для правильного парсинга.

**US-04:** Пользователь обнаруживает ошибку: видит поле и FIN_Data!D5, исправляет в React или перезагружает Excel, сохраняемый черновик не перезаписывается до явного подтверждения.

**US-05:** Позже независимое рассмотрение утверждённого источника приводит к FactSetRevision → предварительной межформенной диагностике. Любой один snapshot читается в ru, kk, en и zh-Hans через одинаковые числовые данные и идентификаторы источников.

**Lifecycle:**
NEW → EDITING/UPLOADED → PARSED → VALIDATED | BLOCKED → PREVIEW_READY → CONFIRMED → SAVED_DRAFT. Дальнейший самостоятельный цикл PENDING_REVIEW → APPROVED_SOURCE → PUBLISHED_FACT_SET → DIAGNOSTIC_SNAPSHOT. НИКОГДА не считать PARSED, PREVIEW_READY или SAVED_DRAFT синонимом APPROVED/PUBLISHED.

## 3. Общие typed контракты

### 3.1. Form schema

FormDefinition(formCode, formKind, catalogStatus, applicableBusinessModels, currentFormVersion).  
FormSchema(formCode, formVersion, semanticSchemaHash, fieldSections, fieldDefinitions, validationRules, allowedPeriodTypes, allowedValueCoverage, displayLayoutVersion, translationPolicy, qualificationStatus).  
FieldDefinition(fieldCode, sourceRowPolicy, sectionCode, displayOrder, valueKind, unitCode, currencyPolicy, requiredPolicy, allowedScale, precision, min/max, nullable, repeatable, totalPolicy, labelKey, helpKey).  
DisplaySpec(columnCode, valueKind, displayOrder, width,minWidth,freeze,editable,formatPolicy,alignment,captionKey,tooltipKey).  
TranslationBundle(locale, version, status, translations, coverageReport, checksum).

Form kinds: FIXED_FIELD, REPEATING_LEDGER, EVIDENCE_ONLY; не генерировать фиктивные поля для DOCUMENT/EXTERNAL_TAX. Из 166 catalog codes активировать конкретные версии только после проверки числового и пользовательского контракта; для остальных SCHEMA_NOT_READY.

**Хеши раздельны:** semanticSchemaHash — machine values/validation; translationBundleHash — видимый текст; displayLayoutVersion — UI и Excel layout; diagnosticMethodVersion — вычисление; diagnosticTranslationVersion — перевод результата. Данные и их hash **не зависят от локали**.

### 3.2. Время, включая особые случаи

ReportingPeriod(type, code, calendarId, calendarVersion, startInclusive?, endExclusive?, asOfDate?, valueCoverage). Типы:
- WEEK: 2026-W03, ISO week-year и 52/53 недели;
- MONTH: 2026.10, календарный месяц;
- QUARTER: 2026-Q3;
- HALF_YEAR: 2026-H2;
- YEAR: 2026;
- AS_OF: 2026-10-31, остатки на момент даты.

valueCoverage: PERIOD_ONLY, YEAR_TO_DATE, LIFE_TO_DATE, AS_OF. Нельзя суммировать YTD с PERIOD_ONLY за перекрывающиеся интервалы, или складывать AS_OF stocks за месяцы. Сервер вычисляет границы, включая ISO 2026-W01 от 2025-12-29. Не поддерживаемые fiscal calendars дают UNSUPPORTED_CALENDAR. Категория остатка не может автоматически стать оборотом. В Excel строка 1 C1 показывает **канонический период**, в React — также читабельную локализованную метку с датами.

### 3.3. Деньги и единицы

MoneyContext(accountingCurrency, reportCurrency, rowCurrency?, amountScale, unitCode, precisionPolicy, fxPolicyVersion?). Валюты ISO 4217; учётная проекта может отличаться от отчётной/операции. amountScale = 1/1000/1000000 только для денежных показателей. Нет FX conversion без отдельной утверждённой методики; нет суммирования разно-валютных значений. Input rawDecimalString → BigDecimal, preview separate displayDecimalString; никогда не выводить исходную сумму только через JS floating point.

Состояние ячейки: PRESENT (включая явный ноль), MISSING, NOT_APPLICABLE, INVALID; каждое отображается и сохраняется различимо. Пользователь видит KZT, тыс. KZT или млн KZT отдельно от номера и от единицы немонетарных полей. Округление display не меняет canon.

### 3.4. Серверный проект

ProjectContext(projectId UUID, projectCode, projectName, projectVersion, organizationId, economicEntityId, businessScopeId, accountingCurrency, fiscalCalendarRef, state).

ProjectId в любом submission и template обязателен; G1 — projectCode для человека, hidden metadata — projectId для согласования. Сервер сравнивает выбранный проект с manifest/context и блокирует mismatch. Отсутствие отдельной реализации IAM не разрешает ввод без существующих guard; воспроизводимый unit/service-level тест возможен без публичного маршрута.

## 4. Ровно ОДИН язык в видимой Excel шапке

### 4.1. Стандарт FIN-FORM-XLSX-V2

**Выдача шаблона:** projectId, formCode, formVersion(optional latest ACTIVE), periodType, periodCode, valueCoverage, inputLocale, reportCurrency, amountScale. Сервис разрешает FormSchema + published TranslationBundle(inputLocale), затем формирует пользовательский XLSX. При PLANNED/неполном китайском TRANSLATION_NOT_READY, не русская книга под видом китайской.

**Стабильный лист FIN_Data:**

| Row | A | B | C | D | E | F | G | H |
|---|---|---|---|---|---|---|---|---|
| 1 — паспорт | F02 | ru | 2026.10 | Отчёт о прибылях и убытках | KZT | 1000 | FIN-DEMO | KZT |
| 2 — заголовки НА ВЫБРАННОМ языке (ru) | Код строки | Код показателя | Наименование показателя | Значение | Валюта | Ед. изм. | Период строки | Примечание |
| 3 — ввод | pnl | netRevenue | Чистая выручка | [пустая заполняемая ячейка] | KZT | — | 2026.10 | [необязательно] |
| 4 — ввод | pnl | cogs | Себестоимость | [пустая заполняемая ячейка] | KZT | — | 2026.10 | [необязательно] |
| 5 — ввод | pnl | operatingExpenses | Операционные расходы | [пустая заполняемая ячейка] | KZT | — | 2026.10 | [необязательно] |

**Если inputLocale=en**, A1=F02, B1=en, C1=2026.10, E1=KZT и коды A3/B3 не меняются; D1 и C3+ на английском, строка 2: Row ID | Indicator code | Indicator | Amount | Currency | Unit | Row period | Notes. При inputLocale=kk используются проверенные казахские подписи, при активном zh-Hans — китайские.

**Важное UX-решение:** первая строка остаётся machine-oriented паспортом и НЕ сдвигается. Каждой A1:H1 сопоставляется локализованная Excel Note/Comment + легенда в коротком листе Instructions, например A1='Код формы', B1='Язык', C1='Отчётный период'. Отдельное отображаемое название отчёта D1, projectCode G1, обе валюты E1/H1. Визуально это аккуратная информационная полоса. Не доверять визуальному пояснению как machine code.

**Правила:**
- Row 2 — **только один inputLocale**, не 3/4 языка в каждой ячейке. Разрешить индивидуальную высоту/ширину и unicode; номера строк жёстко Row1 passport, Row2 header, Row3+ data.
- Машинные коды и позиции A/B/G остаются независимы от перевода. Импорт парсит по formCode + formVersion + fieldCode + rowId + position, **не по локализованным названиям**. Если display labels отредактированы и перестали соответствовать pinned bundle — показать DISPLAY_LABEL_CHANGED с координатой и безопасной политикой предупреждения/отказа, но не сопоставлять с произвольным новым полем.
- Очень скрытый _FIN_Metadata: formatVersion, templateInstanceId, projectId + projectVersion, formCode/formVersion, semanticSchemaHash, inputLocale, translationBundleVersion, displayLayoutVersion, calendar/coverage, accountingCurrency, reportCurrency, scale, expected columns/row policy. Manifest помогает целостности, **не** предоставляет пользователю права доступа.
- Instructions sheet также на inputLocale; содержит «Что можно вводить», «Не изменяйте коды», валюту/масштаб, пример и объяснение пропущенных значений. Не добавлять листы/колонки без версии контракта.
- Заполнению подлежит D3+ (и разрешённые детали ledger); машинные коды, заголовки, passport protected/read-only в обычном UX; доступные для ввода ячейки подсвечены, пустые не равны нулю, итоговые вычисляемые строки не редактируются.
- Стили Excel: заголовки CENTER horizontal/vertical, текстовые коды и labels CENTER horizontal/vertical, числа RIGHT horizontal + CENTER vertical. Для длинных текстов wrap; отрицательные видны знаком/скобками, ноль виден явно; numeric format с correct precision и readable thousands grouping, без обрезания ####.
- Row1 светлая/контрастная полоса с паспортом, row2 один уровень жирного header, freeze 2 rows и технические A/B cols, контрастные editable cells, metadata notes, landscape printing. Никаких слияний, нарушающих A1:H1 и A2:H2.
- В Excel numeric cells допустимы только при безопасной точности; иначе ввод decimal-as-text с явной подсказкой, разбор локального десятичного разделителя только по inputLocale и без неоднозначности; import всегда нормализует к decimal string.
- Formula/external link/macro/unknown columns/ZIP bomb/duplicate field/sourceRowId/invalid date/currency/project/version → reject/ValidationReport с координатами. Старые FIN-PILOT-V1, sector и FIN_BALANCE шаблоны не сломать; отдельный parser dispatch по formatVersion.

**Изменение языка после выпуска:** semanticSchemaHash и коды не меняются. Парсер умеет принимать архивную, но ещё поддерживаемую translationBundleVersion, даже если орфография позднее уточнилась; при unsupported bundle давать явный stale-template error и инструкцию перекачать.

## 5. React — стилизованный табличный РЕДАКТОР (не Preview)

**Компонент:** FormDataEntryTable, schema-driven + controlled draft, отдельный от read-only FormPreviewTable. По умолчанию показывает inputLocale; аналитические и технические коды компактно доступны в tooltip/второй строке.

**Навигация/раскладка:** выбор проекта → формы → языка → ReportingPeriod+coverage → денежного контекста → столбцы № / Код / Наименование / Значение / Ед. и валюта / Проверка (для ledger дополнительно даты, валюты/измерения по типизированной схеме). Фиксированная шапка, колонки в строгом displayOrder из FormSchema, контрастная палитра, чередующиеся строки, hover/focus и отчётливое выделение editable inputs. Ширины: indicator название min 260px, numeric min 160px, codes compact, responsive horizontal scroll без залезания чисел на labels. Секция, группа и итоги разделены заголовками по schema, но не придумываются.

**Ввод:** цифровые valueKind → выравнивание RIGHT, vertical MIDDLE, tabular-nums, inputMode decimal, корректные локальные разделители и нормализация без JS Number. Заголовки, текст, коды, периоды, статусы → CENTER horizontal + vertical MIDDLE. Длинные labels с wrap и динамической высотой, высота input и line-height согласованы с высотой строки. Целое/процент/дата/boolean/enum/currency выбирают контрол по valueKind, не все поля — свободная денежная строка.

**Действия:** Tab/Shift+Tab переход между редактируемыми ячейками, Enter подтверждает и переводит фокус по установленному порядку, Arrow keys в таблице только там, где не ломают редактирование текстового input; Escape отменяет несохранённую правку текущей ячейки. Paste диапазона — только в разрешённую прямоугольную область, предварительная валидация типов и размеров, никакой скрытой подмены поля. Ctrl/Cmd+Z undo для изменений пользователя, Ctrl/Cmd+S сохраняет draft в разрешённом режиме. Ledger add/remove row с устойчивым ID; видимые предупреждения перед удалением заполненных строк.

**Валидация:** REQUIRED, numeric precision, currency, unit, period, allowed fields, source row duplicates, identity rules — server authoritative, клиент мгновенно подсвечивает очевидные нарушения. Error code единый между Excel и React; aria-describedby и текст ошибки у конкретной ячейки. Неприменимо/пропуск/ноль выбираются явно. Смена проекта, формы, периода или языка **не стирает черновик молча**; смена только языка перерисовывает labels и формат отображения, сохраняя rawValue и курсор/фокус насколько возможно.

**Адаптивность/доступность:** zoom 200%, 360px viewport с прокруткой, 500 строк без лагов и потерь клавиатурного фокуса, длинный казахский текст и будущий CJK. При необходимости pagination/virtualization со стабильными keys/row IDs. CSS namespace только .fin-entry-table, не ломать существующую .data-table. Семантическая таблица с th/scope, caption и наглядной легендой.

## 6. Единая нормализация и read-only Preview

Путь: React editor raw cells **ИЛИ** Excel parser raw cells → CanonicalFormSubmission(projectId, formVersion, period+coverage, currency+scale, typed rows) → Shared FormValidator → server PreviewSnapshot. Равные данные в разных inputLocale (ru/kk/en/zh-Hans когда включён) дают **равные canonical values и validation codes**, независимо от отображения Excel.

**FormPreviewTable** принимает PreviewSnapshot, но не продолжает редактировать его. Шапка: проект, форма/версия, период и границы, тип покрытия (только период/с начала года), учётная и отчётная валюта, масштаб, происхождение ручной ввод/Excel. Сводка: всего, заполнено, пусто, ошибочно, warnings.

**Выравнивание:** column headers CENTER/MIDDLE; text labels/codes/status/period CENTER/MIDDLE; numeric/monetary/percent RIGHT/MIDDLE; all rows middle. Точное форматирование: decimal raw string → display string с locale grouping, no JS floating-point; separate zero, missing, N/A, negative. Группы/итоги только по опубликованному subtotalPolicy и единой валюте/периоду.

**Ошибки:** click/focus на поле или source Excel coordinate (например FIN_Data!D5). При BLOCKER confirm disabled, исправление сохраняет черновик и ранее утверждённые версии. После PREVIEW_READY «Подтвердить и сохранить» требует привязанный к snapshot previewId + confirmationDigest + expectedDraftRevision; изменение source/data/context invalidates preview, повтор не создаёт duplicate. Preview **не** является review/approval/FactSet publication.

**Формат:** 100–500 строк, sticky headers, legible fonts, contrast and horizontal scroll, keyboard, screenreader, long RU/KK/ZH labels; HTML screenshot regression и Excel screenshots. Изолированный стиль .fin-preview-table.

## 7. Четырёхъязычная диагностика: результаты + доказательства + источники

### 7.1. Архитектура перевода результатов

**Существующий вычислительный путь:** Reviewed input → immutable source facts → FactSetRevision → CrossFormDiagnosticEngine (6 pilot methods) → immutable diagnostic snapshot с codes, BigDecimal-string values, missing facts, source revisions, method/registry hash и status qualification.

**Новый read-only слой:** DiagnosticPresentationService(snapshotId, outputLocale, diagnosticTranslationBundleVersion) → LocalizedDiagnosticView / React DiagnosticResultView. **Нельзя** запускать новый расчёт при смене языка; все четыре представления обязаны указывать на **один и тот же** snapshotHash, factSetRevisionId, source revisions and method version.

**Разделить:** inputLocale (редактор/исходный XLSX), uiLocale (общие элементы интерфейса), outputLocale (человеко-читаемый отчёт диагностики). Их значения могут отличаться. Один и тот же источник загружен на языке ru, но отчёт пользователь читает на kk, en или zh-Hans.

### 7.2. Что именно переводится (100% управляемого текста)

| Объект immutable snapshot / current UI | Контракт языкового представления |
|---|---|
| ruleCode (6 пилотов) | rule title и brief/explanation для ru,kk,en,zh-Hans |
| relationType / verdict | читабельное типизированное название и осторожная формулировка, сохраняющая смысл |
| reasonCode | утверждённый message template со структурированными parameters, без генерации новой причинности |
| qualification | видимое предупреждение о PROVISIONAL, SYNTHETIC, S2 HOLD, NOT_COMPUTABLE/MISSING_EVIDENCE |
| computed keys (например balanceDifference, grossMarginRatio) | имя показателя, unit/ratio, precision и формат суммы |
| missing metricCode/timeRole | название отсутствующего показателя, запрашиваемого периода и ролевая метка |
| source provenance | form title + source field label + sourceRowId + sourceRevision + factId + value and period, все машинные IDs сохраняются |
| evidence plan / nextForms/action/status | название следующей формы и объяснение, чего недостаёт, без создания нового диагноза |
| financial tables/legend | заголовки, итоги, период, валюта, единицы/scale, формат чисел по locale |
| disclaimer/uncertainty | статус финансовой квалификации и отсутствие причинно-следственных доказательств на всех четырёх языках |

**Важно:** исходный текст от автора (примечание к файлу, reviewer reason, произвольное содержимое документа) не переводится автоматически, если нет отдельной утверждённой функции перевода. Отмечать: «Исходный текст: [locale source]», управляемые field labels из FormSchema переводятся. Source IDs/формульные коэффициенты неизменны, и доступны при раскрытии provenance.

### 7.3. Diagnostic Translation Catalog

Versioned DiagnosticTextCatalog(key, locale, template, parameterSchema, qualificationStatus, translationRevision, reviewer, checksum). Ключи: diagnostic.rule.*, diagnostic.reason.*, diagnostic.verdict.*, diagnostic.qualifier.*, diagnostic.computed.*, diagnostic.metric.*, diagnostic.role.*, diagnostic.action.*, diagnostic.evidence.*, diagnostic.disclaimer.*. Использовать структурированные параметры (например numericValue, currency, period, source form) и безопасный formatter; **никогда** не вставлять числа и user-input text в непроверенную строковую формулу/HTML. Параметризация применяется одинаково в четырёх локалях.

**Точное правило:** тексты не должны утверждать «доказанная причина», «финансово подтверждено», «одобрено», когда verdict = OBSERVATION/REVIEW/MISSING_EVIDENCE или qualification = ACTUAL_PROVISIONAL_METHOD_S2_HOLD. Текстовые версии не могут менять severity, вердикт, qualifiers, список missing, related evidence или suggested next forms. Технические reasonCode всегда доступны при раскрытии.

**Генерация:** на основании immutable snapshot и заранее проверенных шаблонов → localized presentation. Свободный LLM может позже предлагать текст для редактора переводов, но не должен самостоятельно генерировать финансовые причины, менять суммы или публиковать незаверенные китайские тексты.

### 7.4. Языковая готовность и требование четырёх языков

Договор: поддерживаем **ru / kk / en / zh-Hans** как четыре целевые локали. Китайский объявлен будущим, поэтому:
- инфраструктура locale-independent snapshot/rendering и тесты layout всех четырёх локалей — **с текущей разработки**;
- **обязательная готовность четырёхъязычной диагностики** означает, что для текущих шести методов ВСЕ контролируемые диагностические ключи на RU/KK/EN/ZH существуют, вычитаны, имеют APPROVED/ACTIVE и пройдены golden tests;
- до этого Chinese DiagnosticResultView может честно показывать TRANSLATION_NOT_READY и список недостающих ключей, но **не выдавать неполный смешанный отчёт** за полноценный китайский;
- добавление пятого языка не меняет систему вычислений, FactSetRevision и коды форм.

Localization renderer может менять language/number/date display без создания новой diagnostic revision. При необходимости сохраняется только отдельный audit-friendly presentation record (locale, bundleVersion, snapshotHash, renderedAt), **не** новый финансовый snapshot. Сохранённый диагностический расчёт доступен в другой локали по запросу.

### 7.5. Интерфейс объяснимого отчёта

На странице диагностики пользователь выбирает «Язык отчёта: Русский | Қазақша | English | 简体中文», независимо от языка исходного ввода. Блоки:
- Summary: статус/qualification/период/валюта;
- 6 pilot relationship cards: человекочитаемый заголовок, verdict, короткая аккуратная причина;
- table: полученные показатели, exact amounts/ratios, валюты/единицы;
- evidence accordion: «На основании каких форм/строк рассчитано», field labels, file/sourceRevision, rowId, factId, source period;
- Missing evidence: какие показатели отсутствуют и в каких формах их получить; explain why no conclusion.
- Download/print presentation (если добавлено позднее) должно использовать **тот же DiagnosticPresentationService**, не создавать отдельный генератор текстов.

Ни один язык не может «повысить» preliminary Actual до certified. В локализованном отчёте остаётся явный S2 HOLD / not causality label.

## 8. API и хранение (предложения; не существующие endpoint)

**Form intake:**
GET form schema (formCode, formVersion, inputLocale),
GET project-bound XLSX template (projectId, inputLocale, period, currency/scale),
POST parse/validate manual,
POST parse/validate XLSX,
GET preview(snapshotId, displayLocale),
POST confirm saved draft (previewId, confirmationDigest, expectedDraftRevision).

**Diagnostic presentation:**
GET assessment snapshot {assessmentId} — существующий immutable machine JSON;
GET diagnostic presentation {assessmentId}?locale=ru|kk|en|zh-Hans&bundleVersion=... — новое предлагаемое read-only представление, которое **не пересчитывает** snapshot.

**Base typed response (example):**
DiagnosticPresentation {assessmentId, snapshotHash, factSetRevisionId, methodVersion, qualification, outputLocale, translationBundleVersion, title, periodLabel, currency, findings:[{ruleCode,title,verdictCode,verdictLabel,reasonCode,reasonText,qualificationLabel,computed:[{metricCode,label,rawDecimal,displayDecimal,unitCode}],missing:[{metricCode,label,timeRoleLabel}],sources:[{formCode,formTitle,sourceField,sourceFieldLabel,sourceRowId,sourceRevision,factId,periodCode}]}], disclaimers:[]}.
**Server must validate language pack completeness** and never construct a new finding or modify immutable metrics. Numeric values in response retain canonical string values in addition to display.

**Compatibility:** V1 and V2 XLSX routes with explicit formatVersion; old snapshots retain machine fields; localized projection of old snapshots works where corresponding translation keys can be mapped unambiguously, otherwise explicit CONTENT_NOT_LOCALIZABLE, not guessed interpretation.

## 9. Полный smoke и доказуемые критерии

| ID | Smoke assertion | Acceptance |
|---|---|---|
| UX-01 | Выбор ru/kk/en и, после активации, zh-Hans | Excel row2 / Instructions / C3+ только на выбранном языке, кодовые колонки неизменны |
| UX-02 | Excel template 1/2/3+ | Видны formCode, language, period, title, report+accounting currencies, scale, project; editable only cells; readable fonts |
| UX-03 | React FormDataEntryTable | Таблица со схематическими группами, центрированный текст, right-aligned numbers, vertical middle, табуляция, add ledger row, no data loss |
| UX-04 | Excel/React parity across different languages | Equal project/form/period/currency/scale → canonical rows and validation findings equal, кроме transport metadata |
| UX-05 | Period types + coverage | ISO week-year, Q/H/Y, month, AS_OF, YTD, calendar mismatch and double-count rejection |
| UX-06 | Money / precision / parsing | BigDecimal, exact 0 vs missing/NA, scale 1/1k/1m, KZT/USD mismatch, no auto FX |
| UX-07 | Excel corrupt / wrong manifest | Formula, unknown codes, forged project, unsupported translation bundle, wrong period reject with cell coordinates |
| UX-08 | Preview before saving | Same read-only table after both channels, errors inline, numeric alignment, currency/period visible, no auto approval |
| UX-09 | Draft/reimport | Invalid data not overwriting prior version, confirmation digest invalidated on edits, project scope pinned |
| UX-10 | Diagnostic six-rule translation coverage | RU/KK/EN/ZH all approved keys for rule/verdict/reason/qualification/computed/metric/role/evidence/next action/disclaimer |
| UX-11 | Same one snapshot in four languages | Same snapshotHash/factSetRevisionId/methodVersion/rawDecimal/missing/sourceRevision/factId; localized text/dates only |
| UX-12 | Financial truth invariant | No locale changes verdict, S2 HOLD, missing evidence, currency or source lineage; no invented causality |
| UX-13 | Language switch and corrupt text pack | No re-evaluation, no mixed-language silent fallback, explicit TRANSLATION_NOT_READY |
| UX-14 | Long Kazakh + Chinese, Chrome/Safari/Excel | No clipped cell labels, readable at 200% zoom, 100–500 rows, keyboard/screenreader |
| UX-15 | Regression previous code | Legacy FIN_BALANCE/sector/FIN-PILOT-V1 and current graph CI still GREEN on same commit |

**Обязательные тесты до реализации:** period/currency/formatter unit tests, PostgreSQL/Testcontainers snapshot/draft/lineage, Apache POI generator-parser golden, Vitest controlled entry grid and i18n catalog, Playwright keyboard/alignment/preview and report language switching, macOS browser/Excel visual UAT. В отсутствие настроенной внешней авторизации защищённый browser↔backend smoke пометить DEFERRED_AUTH; не создавать anonymous bypass.

## 10. Порядок реализации и границы этапов

**R0 — Contract RED:** machine FormSchema, currency/period/coverage, inputLocale/outputLocale, translation catalog, PreviewSnapshot, golden/negative checks.
**R1 — Dictionary + core time/decimal:** 5 pilot forms, ru/kk/en translated fields; zh-Hans PLANNED with fixture, calendar and money.
**R2 — Excel V2 + React ENTRY:** выбранный язык Excel (не all-in-one headings), удобная editable table, version-dispatched parser, regression V1.
**R3 — Unified Preview + SavedDraft:** один validator, один read-only Preview для обоих каналов, schema/project/period/currency binding, no silent overwrites.
**R4 — Diagnostic presentation:** deterministic locale renderer на **четырёх** языках для 6 существующих правил, перевод всех machine labels, codes, statuses, missing evidence и source provenance; no new financial methods.
**R5 — Quality and UAT:** UX-01…UX-15, automated and manual evidence, single SHA GREEN, financial status still S2 HOLD.

**Не начинать новые методы диагностики или стратегический Compass UX, пока нет доказанного F02 React/Excel → Preview → project saved draft и 4-язычного rendering-contract на существующем диагностическом snapshot.**

## 11. Gate checklist (для назначения DONE, а не слов)

- [ ] Одинаковая schema и field codes для Excel и React на 5 пилотных формах.
- [ ] Excel на **одном выбранном языке**, с работоспособной RU, KK, EN; ZH после проверенных переводов.
- [ ] Полноценный форматированный React editor с keyboard, decimal, validation, draft handling.
- [ ] Preview единый, основан на проверенных данных, правильная типографика числа/text/vertical middle.
- [ ] Формы защищены от смешения периодов, YTD, currencies, scale и проектов.
- [ ] Все контролируемые диагностические сообщения для 6 действующих правил имеют verified RU/KK/EN/ZH translations — иначе 4-language acceptance **BLOCKED**, не PASS.
- [ ] Четыре view одного snapshot со строгим numeric/provenance parity, qualifications unchanged.
- [ ] Production security stays fail-closed, existing immutable source review and graph not bypassed.
- [ ] Regression и новый focused smoke GREEN на одном SHA. Материалы UAT сохранены.

**Текущий реальный статус:** ТЗ V5 и аудит существуют; implementation ещё не выполнена. Не считать запланированный Chinese translation или четырехъязычный диагностический renderer уже работающим.
