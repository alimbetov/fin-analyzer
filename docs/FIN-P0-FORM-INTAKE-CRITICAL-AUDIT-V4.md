# FIN P0 — независимый технический и продуктовый аудит загрузки финансовых форм (AUDIT-V4)

**Дата:** 2026-11-16. **Режим:** READ-ONLY CODE REVIEW + проектирование; **исполнение нового V4-механизма НЕ заявляется**.  
**Repository:** `alimbetov/fin-analyzer`, branch `feat/fin-r0-r1a`.  
**Аудируемый HEAD до нового документа:** `379568b06a9dbde51e1554fc818e032e7b2f4816`.  
**Базовый CI:** FIN CI `43799698840` = SUCCESS для прежнего кода/документов. **Нет** полного E2E нового реестра, Preview V4 и локали zh-Hans.

## 1. Вердикт

**Текущее состояние: архитектурная основа есть, единый пригодный для масштабирования Intake UI/Excel/Preview — нет.**

Каталог FIN на 166 кодов — не 166 готовых к вводу/переводу/расчёту форм. Последние сервисные тесты покрывают ограниченный набор из пяти исходных пилотных форм и месячный период. Документирование WEEK/MONTH/QUARTER/HALF_YEAR/YEAR/AS_OF и будущего китайского языка не заменяет исполняемую реализацию.

Без исправления разрозненности источников истины, проектной привязки, денежных координат и общих состояний загрузки при расширении каталога появятся несопоставимые значения, ошибочные периоды, «нулевые» пустые показатели и вводящие пользователя в заблуждение предпросмотры.

**Приоритет владельца:** сделать жизнеспособный, удобный и проверяемый механизм ввода/импорта. **Не** запускать разработку Keycloak, полного ABAC, новой стратегии анализа и новых методик в рамках этого пакета. Существующий запрет доступа по умолчанию не ослаблять.

## 2. Evidence: проверенные файлы и проблемы

| Severity | Код | Реальный источник | Наблюдение | Риск |
|---|---|---|---|---|
| **P0** | AUD-01 | `src/diagnostics/TrustedFinanceWorkspace.tsx` строки 6–18; `PilotXlsxTemplateService.java` строки 30–37; `src/sector/state.ts` | Несколько вручную поддерживаемых схем `FIELDS/LABELS` и sector fields | Изменения Excel и React расходятся, миграция 166 форм неуправляема |
| **P0** | AUD-02 | `TrustedFinancialFormMapper.Cell` строки 29–38; `PilotXlsxTemplateService.java` строки 128–145; `TrustedFinanceWorkspace.tsx` строки 251–253 | `YearMonth` и `type=month` — единственный рабочий путь | Нет единой загрузки WEEK / QUARTER / HALF_YEAR / YEAR / AS_OF |
| **P0** | AUD-03 | `V9__reviewed_intake_submissions.sql`; `ReviewedFinancialIntakeService.IntakeRequest` строки 39–41 | Нет server-side `project_id`; есть организация, entity и scope | Ошибка привязки данных к проекту и невозможность изолированного project preview |
| **P0** | AUD-04 | `foundation-titles-ru-kk-en-v2.tsv`; `TrustedFinanceWorkspace.tsx` `LABELS`; `form-compatibility.tsv` | Переводы охватывают не все формы/поля; динамического locale registry нет | Китайский язык потребует переделки и может изменить смысл финансовых полей |
| **P0** | AUD-05 | `PilotXlsxTemplateService.java` строки 58–79, 113–124 | Заголовки в Excel на 1-й строке, данные со 2-й; только четыре технических столбца | Пользовательское требование «паспорт/шапка/данные» и читаемость не выполнены |
| **P0** | AUD-06 | `TrustedFinanceWorkspace.tsx` строки 274–325; `src/styles.css` строки 76–84 | Есть таблица редактирования и список сырых строк одобренного источника, нет полноценного отдельного `PreviewSnapshot` до сохранения | Пользователь не может визуально сверить импорт до подтверждения |
| **P0** | AUD-07 | `src/styles.css` строки 76–84 | Заголовки таблицы выравниваются неодинаково, нет table-role-specific vertical alignment | Нельзя гарантировать центрирование текста, правое выравнивание чисел и одинаковый вид Excel/React |
| **P1** | AUD-08 | `ReviewedFinancialIntakeService.java` строки 109–112; `PilotXlsxTemplateService.Context` строки 38–46 | Валюта — произвольный трёхбуквенный код; scale только 1 или 1000 | Нельзя однозначно обрабатывать валюты/млн и иностранные формы |
| **P1** | AUD-09 | `PilotXlsxTemplateService.amount` строки 161–175 | Excel NUMERIC проходит через `double` с ограничением точности | Возможны отказ/потеря точности и недоверие к формату отчётов |
| **P1** | AUD-10 | `src/domain/project.ts`, `src/domain/financial.ts` | Локальный `fin.project/1` содержит компанию и баланс 2024/2025 | Это не backend-проект с жизненным циклом импортов |
| **P1** | AUD-11 | `src/diagnostics/registry.ts` строки 81–119 | 166 форм в реестре, шесть синтетических межформенных отношений и пять источников пилота | Нельзя выдавать 166 методов ввода/диагностики за готовые |
| **P1** | AUD-12 | `src/sector/excel.ts`, `src/adapters/excel.ts`, `PilotXlsxTemplateService.java` | Несколько исторических Excel layout и metadata | V2 должен быть новым контрактом, не silent overwrite старого импорта |
| **P1** | AUD-13 | `TrustedFinanceWorkspace.tsx` строка 253, 256 | Смена месяца сбрасывает `cells`; смена формы предупреждает, но не единая draft policy | Ввод может быть потерян; требуется сохранение/подтверждение смены контекста |
| **P1** | AUD-14 | `form-compatibility.tsv` | Есть каталог типов STRUCTURED/DOCUMENT/EXTERNAL_TAX и DESIGN_ONLY | Документам и справкам нельзя автоматически выдавать денежные поля |

**Примечание:** выше приведены факты статического чтения HEAD, не результаты нового запуска приложения. Оценка `P0` относится к **готовности будущего механизма импорта**, а не к инцидентам в уже развёрнутом production.

## 3. Бизнес-риски, которые нельзя проигнорировать

1. **Частота отчётности ≠ семантика суммы.** Например, 2026-Q2 может означать движение только за апрель–июнь, а может накопительным итогом с января. Ввести `valueCoverage = PERIOD_ONLY | YEAR_TO_DATE | LIFE_TO_DATE` для потоков, а для остатков `AS_OF`; иначе система дважды суммирует обороты.
2. **Период проекта ≠ календарный период.** ISO WEEK year может начаться в декабре предыдущего года; нестандартный финансовый год или 4-4-5 должен требовать отдельный версионированный календарь, а не угадываться.
3. **Учётная, отчётная и валюта операции разные.** Автоматическое сложение без FX policy недопустимо. Масштаб 1000 означает перевод лишь в контексте указанной валюты; не «умножить все значения».
4. **Язык = представление, не источник расчётов.** Различие `ru/kk/en/zh-Hans` не должно изменять formCode, fieldCode, normalized period, decimal и content fingerprint. Изменённые термины должны версионироваться отдельно от числового контракта.
5. **Preview — обязательная точка доверия пользователя.** Серверная обработка может пройти, но одна неверно смещённая колонка или тысяча вместо единиц приведёт к катастрофическому впечатлению. Без `review before save` нельзя считать импорт удобным и безопасным.
6. **Не путать статусы:** `Parsed` ≠ `Validated` ≠ `SavedDraft` ≠ `Reviewed` ≠ `Published` ≠ `FinancialMethodQualified`.
7. **Нет полноценной доказательности исходных файлов.** Сохранённый SHA-256 подтверждает целостность загруженных байтов, но не достоверность бухгалтерского источника.
8. **Юзабилити четырёх языков:** если RU/KK/EN/ZH писать в каждый Excel-заголовок без настройки ширины и высоты, шаблон станет нечитаемым. Нужны локаль формы, ordered `headerLocales`, многострочная шапка и независимая версионированная переводная разметка.
9. **Длинные названия и числовые колонки:** китайские и казахские переводы отличаются по ширине; визуальные тесты должны проверять отсутствие обрезаний, выравнивание по типам и читаемую печать.

## 4. Принимаемые архитектурные решения

| ADR | Решение | Что не делать |
|---|---|---|
| FIN-INTAKE-ADR-01 | Один versioned `FormSchema`, потребляемый React, XLSX и server validator | 166 отдельных компонентов или hardcoded mapping |
| FIN-INTAKE-ADR-02 | Типизированный `ReportingPeriod` + `valueCoverage` + `calendarVersion` | `period: string` без частоты и semantics |
| FIN-INTAKE-ADR-03 | `MoneyContext(accountingCurrency,reportCurrency,rowCurrency?,amountScale)` с decimal-string | Автоматический курс/пересчёт или вычисления JS floating point |
| FIN-INTAKE-ADR-04 | BCP47 locale registry `ru,kk,en,zh-Hans`, переводы версионированы и квалифицируются | Enum из трёх жёстко заданных языков |
| FIN-INTAKE-ADR-05 | Версии `FormLayout` и `FormDisplaySpec`, отдельно от metric schema и label revisions | Парсинг по тексту заголовка или незаметная смена листов |
| FIN-INTAKE-ADR-06 | Один `CanonicalFormSubmission` после обоих способов ввода | Два независимых validation path с разными кодами ошибок |
| FIN-INTAKE-ADR-07 | `PreviewSnapshot` после server validate, до сохранения/утверждения | Публикация при загрузке или использование preview как approval |
| FIN-INTAKE-ADR-08 | Server-owned project identity и unit/currency/period pinning | Использовать company name или скрытый Excel лист как источник авторизации |
| FIN-INTAKE-ADR-09 | Отдельные читабельные `FormDataTable` и `FormPreviewTable` на общих row definitions | Показывать пользователю сырой JSON или бесформенный список |
| FIN-INTAKE-ADR-10 | V1 → V2 только с явным `formatVersion`, legacy regression | Ломать или переименовывать существующие XLSX |

**Документ решения и Acceptance Contract:** [FIN-P0-FORM-IMPORT-UX-I18N-PREVIEW-SPEC-V4.md](FIN-P0-FORM-IMPORT-UX-I18N-PREVIEW-SPEC-V4.md).

## 5. Критические RED-тесты до реализации

**Contract/registry**
- Дубликаты `fieldCode`, недостаточная спецификация поля, unknown form, нет утверждённого перевода RU/KK/EN для пилота, unsupported future language → deterministic error.
- Новый `zh-Hans` добавляется **без изменения** schemaHash бизнес-полей и canonical fingerprints; отдельный translation layout/version hash изменяется.
- Отчёт `F02` нельзя тихо переинтерпретировать как `F01`; неисполняемые DOCUMENT формы не получают числовые ячейки.

**Period**
- ISO week-year `2026-W01` начинается 2025-12-29; `2020-W53` допустима, `2021-W53` недопустима.
- `2026.02` корректный февраль, `2026.13` invalid; `2024-02-29` valid AS_OF, `2025-02-29` invalid.
- Q4 2026 заканчивается 2027-01-01 (exclusive); H2 2026 идентичен по датам Q3+Q4 без overlap.
- `YEAR_TO_DATE` не прибавляется к `PERIOD_ONLY` для того же времени и не суммируется второй раз.

**Money**
- `0` сохраняется как explicit zero; empty/null как missing; negative values в допустимых полях, precision up to approved limits, grouping/locale not altering normalized value.
- `KZT × 1000` и `USD × 1` не смешиваются; currency mismatch → validation error; scale 1e6 не поддерживается историческим V1, поддерживается V2 после qualification.

**UI/XLSX/Preview**
- Одинаковые данные в Excel и React → одинаковая `CanonicalFormSubmission` и `ValidationReport` (исключая origin-specific metadata).
- XLSX row 1 passport, row 2 headers, row 3+ only data; formula/external links/unknown columns/source row alias rejection.
- В preview: headers и значения текстовых колонок по центру; numeric/percentage по правому краю; **все ячейки вертикально центрированы**; значение не форматируется через float; изменения языка не меняют raw decimal.
- Сотни строк, длинный казахский текст, будущий китайский язык, разные масштабы экрана, keyboard и screen reader; screenshots.
- Перед сохранением показывать тип периода, календарную границу, валюту, масштаб, количество строк/ошибок, версию и выбранный проект.
- Если есть BLOCKER → confirm disabled, исходный черновик не теряется; повторная загрузка не удаляет предыдущую ревизию.

## 6. Release/scope-политика

**Текущий P0:** REFACTOR IMPORT UX. **Не** развивать Keycloak, Bearer-token, security-policy tooling, новый Compass UX или массовую финансовую диагностику. Уже имеющиеся финансовые HTTP endpoints остаются защищёнными/deny-all. Внутренние contract/integration tests допустимы; не добавлять anonymous route для обхода test blocker.

**Definition of Done этого аудита:** полная критическая матрица, выбранные архитектурные решения, формализованные RED-тесты и приоритеты. Это **не** отметка о том, что код уже соответствует V4.
