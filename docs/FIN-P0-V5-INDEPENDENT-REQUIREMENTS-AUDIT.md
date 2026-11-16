# FIN P0 V5 — независимый повторный аудит требований и реализации

**Дата:** 2026-11-16
**Branch:** feat/fin-r0-r1a · **PR #2:** Draft · **production:** HOLD
**Исходный проверенный HEAD:** 2d9e55d7ab66beacf88ba9ba3753afeca7f250fd
**Тип:** строгая проверка ТЗ V2–V4 относительно реализации, без утверждений о готовности новых функций
**Новый основной документ:** [FIN-P0-UNIFIED-FORM-EXCEL-REACT-I18N-DIAGNOSTIC-V5.md](FIN-P0-UNIFIED-FORM-EXCEL-REACT-I18N-DIAGNOSTIC-V5.md).

## 1. Итог аудита

**ТЗ V4 НЕЛЬЗЯ утверждать без исправлений.** Архитектурное направление правильное, но в V4 есть противоречия и существенные пробелы:
- В Excel обещана трёх-/четырёхъязычная шапка в каждой ячейке, хотя выбранный пользователем язык должен определять единственную понятную шапку, подписи строк, инструкции и сообщения;
- стилевые требования описывают преимущественно read-only Preview, но не полноценный editable-grid для фактического ввода в React;
- локализация диагностического графа/результата и source lineage отсутствует; смена языка не может означать повторный расчёт;
- будущее включение китайского заявлено, но не описан готовый acceptance gate на все четыре языка именно для diagnostic output;
- слабая формализация отсутствующих/неприменимых значений, периода YTD, валюты/масштаба, pinned translation versions, Excel signature/manifest and import-review state;
- разные исторические Excel layouts и React поля приводят к несогласованности.

## 2. Критические находки с привязкой к коду

| Приоритет | ID | Основание и несоответствие |
|---|---|---|
| P0 | REV-01 | V4 §3, §6 требует RU/KK/EN на каждой Excel шапке. Это **конфликт с актуальной целью**: шаблон должен быть на языке выбора пользователя |
| P0 | REV-02 | src/diagnostics/TrustedFinanceWorkspace.tsx (строки 6–18, 274–307) — ручные FIELDS/LABELS, фиксированный месяц, редактирование примитивной таблицы; нет генератора полноценной локализованной editable grid |
| P0 | REV-03 | src/styles.css (строки 76–84) — общая .data-table, styled input right-align, но нет нормативов vertical middle, display/text-specific alignment, keyboard behavior и стилевого контракта отдельного редактора |
| P0 | REV-04 | CrossFormDiagnosticEngine.Finding (строки 100–113), TrustedCrossFormDiagnosticService.toSnapshot (строки 365–455) — только ruleCode, reasonCode, verdict, computed, missing, source ids; отсутствует locale-aware projection |
| P0 | REV-05 | RelationshipStudio.tsx (строки 7–29) и TrustedFinanceWorkspace.tsx (строки 353–369) — русские подписи и сырые ruleCode/sourceField/reasonCode, нет объяснений и происхождения на RU/KK/EN/ZH |
| P0 | REV-06 | src/diagnostics/registry.ts (строки 145–149), foundation-titles-ru-kk-en-v2.tsv (41 строка, включая заголовок) — фиксированные русские названия некоторых проблем, частичный трёхъязычный словарь, нет полного versioned DiagnosticTextCatalog |
| P0 | REV-07 | Excel V1: PilotXlsxTemplateService.java (строки 58–79, 113–124) — первая строка заголовков, данные со второй, только технические headers. V2 нужно реализовать отдельно |
| P0 | REV-08 | TrustedFinancialFormMapper.Cell YearMonth и ReviewedFinancialIntakeService.IntakeRequest: projectId/type/coverage/translationVersion отсутствуют. Месячный поток нельзя универсально трактовать как любой период |
| P0 | REV-09 | Current diagnostic methods (IndicatorRelationshipRegistry, CrossFormDiagnosticEngine) — шестёрка пилотных отношений, ACTUAL остаётся PROVISIONAL / S2 HOLD. Нельзя объявлять локализованный текст независимой финансовой квалификацией |
| P1 | REV-10 | V4 нет формального запрета на несогласованный XLSX metadata context и языка шапки, не задано поведение старых переводов и изменённого названия колонки |
| P1 | REV-11 | V4 не закреплены точные UX правила редактора: порядок Tab/Enter, add ledger row, paste, read-only codes, valid/missing/invalid, сохранение draft |
| P1 | REV-12 | V4 не определяет, как переводить source provenance, time roles, computed labels, ratio/amount units и qualification/disclaimer, не изменяя исходные ревизии |
| P1 | REV-13 | Не определён fail-closed режим при отсутствующем китайском DiagnosticTextCatalog — нельзя выдавать частично русские отчёты за китайские |
| P1 | REV-14 | Не хватает language-pair parity, reversal and golden tests: один Excel импорт и один snapshot должны показываться на четырёх языках без изменения смыслового hash |

## 3. Новый критерий качества: не только схема, но сквозной UX

**Визуальный контракт Excel:** выбран один активный locale; строка 1 — паспорт, строка 2 — заголовки только выбранного языка, строка 3+ — ввод. Пояснения, названия строк, лист Instructions, единицы и ошибки также в выбранной локали. Система парсит по полевым кодам, а не по переведённой подписи.

**Визуальный контракт React редактора:** отдельный компонент FormDataEntryTable, не просто список полей. Поля/разделы по schema, выбор контекста, правильные типы controls, right-aligned decimal, horizontally centered labels/headers, middle vertically, validators per cell, keyboard + row operations + long text & CJK support.

**Preview:** отдельный read-only FormPreviewTable, одинаковый для обоих путей, поступает из server-validated canonical model и сохраняет source location. Нельзя подменять preview локальными сырыми значениями без серверного парсинга.

**Локализованный Diagnostic View:** Immutable FactSetRevision → Immutable diagnostic snapshot (ruleCode, reasonCode, verdict, raw decimal strings, source refs, qualification flags) → versioned locale renderer → RU/KK/EN/ZH presentation. Locale никогда не меняет математический snapshot hash, исходные суммы и provenance. UI может переключать язык **без повторного анализа**.

**Функциональная готовность китайского:** схема локализации и тестовая поддержка zh-Hans закладываются сразу, но реальные переводы требуют корректуры. Требование «диагностика на 4 языках» получает отдельный blocking acceptance gate: пока все критичные diagnostic keys не переведены/утверждены, китайский диагностический отчёт не считать готовым, отдавать TRANSLATION_NOT_READY, не имитировать перевод.

## 4. Риски методики и корректность

- Период WEEK/MONTH/QUARTER/HALF_YEAR/YEAR/AS_OF, valueCoverage PERIOD_ONLY/YTD/LIFE_TO_DATE/AS_OF, calendarId/version, разница stock/flow.
- MoneyContext accountingCurrency/reportCurrency/rowCurrency, amountScale (1/1000/1e6), unitCode, precision; никакого автоконвертирования валют и двойного scale.
- Канонический fact fingerprint без locale; display locale formatting decimal string без плавающей точки JS; underlying value 0 vs MISSING vs NA.
- Данные источника и полевые надписи: пользовательский комментарий и отсканированный/загруженный текст сохраняются в исходном языке и помечаются; перевод терминов из контролируемого каталога не равен переводу произвольного документа.
- Любой published source и diagnostic snapshot остаётся immutable, preview не является независимым одобрением, ACTUAL diagnostic пока PROVISIONAL и не содержит финансовых причинных утверждений.

## 5. Обязательная RED-приёмка до GREEN

- **P-01:** form locale RU/KK/EN/ZH в Excel: генерируется ровно один выбранный язык, machine codes/position одинаковы; если locale не ACTIVE, запрос явно отклонён.
- **P-02:** для active locale в row 2, C3+, Instructions нет слов другого языка; парсер принимает корректный файл независимо от локали, не доверяет переводам как fieldCode.
- **P-03:** одинаковые значения, введённые React и Excel на разных языках, дают равные canonical rows, currencies, periods and validator codes.
- **P-04:** styled editor: numeric right, labels/header centered, all vertical middle, valid/invalid clear, keyboard sequence, repeating rows, clipboard paste with constraints, no draft loss.
- **P-05:** Preview после server validation показывает тот же набор полей, readable number grouping, explicit zero/missing/NA, provenance coordinates, controlled confirm.
- **P-06:** переключение между ru/kk/en/zh-Hans на диагностике сохраняет один snapshot hash и все BigDecimal/computed/missing/sourceRevision/factId; меняет только текст, числоформат и дату.
- **P-07:** все 6 пилотных методов должны иметь locale coverage для rule title, verdict, reason, method qualifier, computed metric labels, evidence missing, next form actions, provenance labels, disclaimers, time roles.
- **P-08:** S2 HOLD и MISSING_EVIDENCE никогда не исчезают при смене locale; машина не выдумывает причинно-следственную рекомендацию на другом языке.
- **P-09:** нет «восточного» автоизменения KZT→CNY, суммы и курсы не меняются из-за смены локали.
- **P-10:** legacy V1 XLSX, ранее утверждённые FactSetRevision/snapshots и существующие CI tests продолжают работать.
- **P-11:** visual tests 200% zoom, long KK/zh-Hans text, text overflow, number columns, Excel macOS/Windows, mobile narrow width.
- **P-12:** backend endpoints не открывать при отключённом IAM; service-level tests при необходимости, real E2E auth DEFERRED.

**Вердикт:** V4 заменить V5 как активное ТЗ. Ранее реализованные компоненты не переписывать без regression tests и согласованного migration/adapter. Это review документа и кода, не отметка о реализованном функционале.
