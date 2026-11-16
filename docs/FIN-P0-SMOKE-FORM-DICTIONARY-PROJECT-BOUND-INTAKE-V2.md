# FIN — ТЗ P0-SMOKE / Form Dictionary & Project-Bound Intake V2

**Статус:** PROPOSED — только спецификация, реализация не начата.  
**Репозиторий:** `alimbetov/fin-analyzer` · **Рабочая ветка:** `feat/fin-r0-r1a` · **PR #2:** Draft · **main:** не изменять.  
**Базис анализа:** SHA `304be2c0019e8f788e50a221a296ac2118a1685f`, подтверждённый [FIN CI #43793179890](https://github.com/alimbetov/fin-analyzer/actions/runs/43793179890).  
**Контур:** FIN P0 после межформенного графа R4.6; релиз `PRODUCTION HOLD`.

## 1. Цель, границы и решение

Построить *один* версионированный источник истины для схем финансовых форм, переводов, правил ввода и Excel/React-представлений. Любая запись финансовых данных обязательно привязывается к **серверному проекту, доступному данному пользователю**. Оба пути — React и XLSX — должны сходиться на **одном серверном DTO/валидаторе/процессе независимого утверждения** и после публикации обрабатываться тем же межформенным графом.

Разделить работу на два самостоятельных процесса:

- **PROCESS A — Form & Template Dictionary:** каталог → версия формы → поле, тип, порядок, локализация `ru/kk/en` → единый schema API → генерация Excel и React. Данные пользователя в этом процессе **не сохраняются**.
- **PROCESS B — Project-Bound Financial Intake:** выбор доступного проекта → период и форма → ручной или Excel-ввод → серверный разбор/валидация → черновик/заявка → другой проверяющий → FactAssertion/FactSetRevision → ограниченная межформенная диагностика. Каталог в этом процессе **не редактируется**.

**Сразу не делать:** массовое добавление фиктивных числовых методик для всех 166 форм, произвольный AI-парсинг, интерпретацию переводов как машиночитаемых кодов, автопубликацию при импорте, подмену Keycloak проверкой одной строки organizationId.

## 2. Анализ фактически существующего проекта (as-is)

| Область | Фактический код | Расхождение с требуемым поведением |
|---|---|---|
| Каталог | `backend/src/main/resources/fin/form-compatibility.tsv`, 166 записей, квалификация разных классов форм | Это реестр назначений, а не исполняемый словарь всех полей/типов/переводов |
| Переводы | `foundation-titles-ru-kk-en-v2.tsv` содержит переводы части дополнительного каталога | Нельзя считать, что все 166 форм и строки покрыты `ru/kk/en` |
| React | `src/diagnostics/TrustedFinanceWorkspace.tsx` содержит `FIELDS` и `LABELS`; `src/domain/financial.ts` — отдельный локальный баланс `FIN_BALANCE` за 2024/2025 | React не генерируется из той же версии определения, что серверный XLSX; локальное `fin.project/1` не является серверным финансовым проектом |
| XLSX | `PilotXlsxTemplateService.java`: 5 форм `FIN-PILOT-V1`, лист `FIN_Data`, заголовки уже в строке 1, данные с 2-й; контекст в `_FIN_Metadata` | Не соблюдает запрошенный контракт «1-я строка метаданные / 2-я заголовки на трёх языках / с 3-й строки данные» |
| Source intake | `V9`, `V10`, `ReviewedFinancialIntakeService.java`: ручной/XLSX, immutable review, no-self-approval, publication | Нет надёжного `projectId` на сервере; пока используется `organizationId + entityId + businessScopeId` из запроса |
| Безопасность | `FinancialApiSecurity.java` + `FinanceAccessPolicy.java`: issuer/JWKS/audience; разрешения организации | Нет проверенных scope/project/period/field/expiry ABAC, живой Keycloak E2E не проведён |
| Диагностика | `IndicatorRelationshipRegistry`, `TrustedCrossFormDiagnosticService` | Шесть synthetic-пилотных связей; `ACTUAL`-вывод — только feature-gated preliminary/S2 HOLD |

**Архитектурное решение:** расширять текущие Java/Spring/PostgreSQL/React компоненты, а не вводить вторую «форменную» подсистему или менять граф. Не разрушать исторический `FIN-PILOT-V1`: новая схема получает **отдельную major-версию** и отдельный version-dispatched parser.

## 3. PROCESS A — версионированный словарь форм и шаблонов

### 3.1. Модель справочника

Предлагаемые сущности (точные имена утверждаются перед миграцией):

- `fin_catalog.form_definition`: `form_code` PK, `catalog_kind`, `applicability`, `form_kind`, `status`.
- `fin_catalog.form_version`: PK `(form_code, version)`, `period_kind` (`MONTH|QUARTER|YEAR|AS_OF|CUSTOM`), `reporting_basis`, `currency_policy`, `scale_policy`, `xlsx_layout_version`, `schema_hash`, `effective_from/to`, `qualified_by/at`, immutable after activation.
- `fin_catalog.form_translation`: PK `(form_code, version, lang)`, `report_title`, `description`, `review_status`. Locale **`ru`**, **`kk`**, **`en`**; `kz` нормализовать только на границе как alias, в БД хранить `kk`.
- `fin_catalog.form_field`: PK `(form_code, version, field_code)`, `source_row_rule`, `metric_code` (nullable для документов), `value_type`, `unit_code`, `scale`, `display_order`, `required_policy`, `nullable_policy`, `time_role`, `constraint_ref`, `dimension_spec`, `cardinality`.
- `fin_catalog.field_translation`: PK `(form_code, version, field_code, lang)`, `label`, `help`, `error_translation_key`, `review_status`.
- `fin_catalog.validation_rule`: версии утверждённых ограничений, например баланс `ASSETS = LIABILITIES + EQUITY`. Отдельно валидность структуры, числовая полнота и финансовая методика.
- `fin_catalog.template_issuance`: необязательно для простых шаблонов, но рекомендуется: `template_instance_id`, `project_id`, `form_version`, `locale`, `period`, `schema_hash`, `issued_by/at`; **не** является подтверждением подлинности введённых сумм.

Машинная идентичность — **только стабильные коды** `form_code`, `field_code`, `source_row_id`, `version`, `project_id` и `schema_hash`. Переводы не участвуют в маппинге, расчётах и дедупликации. Утверждённую версию не редактировать: новая версия, migration/compatibility policy и snapshot предыдущих значений.

### 3.2. Требуемый XLSX V2: строго три логические зоны

**Лист `FIN_Data`:**

| Строка | A | B | C | D | E | F |
|---|---|---|---|---|---|---|
| **1 — метаданные** | `F02` — код формы | `ru` — язык | `2026.10` — период | `Отчёт о прибылях и убытках` — локализованное название | пусто | пусто |
| **2 — шапка (3 языка в каждом заголовке)** | `Код строки / Жол коды / Row ID` | `Код поля / Өріс коды / Field code` | `Статья / Бап / Item` | `Значение / Мәні / Amount` | `Единица / Өлшем / Unit` | `Примечание / Ескертпе / Note` |
| **3 — данные** | `pnl` | `netRevenue` | `Чистая выручка` | `400` | `KZT` | пусто |
| **4 — данные** | `pnl` | `cogs` | `Себестоимость` | `260` | `KZT` | пусто |
| **5 — данные** | `pnl` | `operatingExpenses` | `Операционные расходы` | `90` | `KZT` | пусто |

Пример демонстрационный, числовые значения НЕ считаются реальной финансовой отчётностью.

- **Строка 1** реализует буквальное требование: код формы, код языка, период в `YYYY.MM`, название отчёта; дата — **текст**, а не числовая дата Excel.
- **Строка 2** всегда содержит названия всех колонок на **RU / KK / EN**. Формат перевода фиксированный, например `RU / KK / EN`, с единым разделителем и экранированием `/` внутри текста. Контрактная проверка идёт по schema и версионированным translation keys, не по свободной строке.
- **Строка 3+** — только данные. `source_row_id` уникализирует повторяющиеся строки расшифровок, `field_code` связывает каждую ячейку с реестром. `C` — подсказка на выбранном языке и **никогда** не является ключом импорта.
- Для `kk` или `en` меняется `B1`, `D1` и отображаемые подписи `C3+`; **код формы и код поля неизменны**, схема строк/колонок одинакова.
- Денежные формы с повторяющимися поступлениями/платежами используют уникальные `source_row_id` (например `receipt-001`, `receipt-002`) + одинаковый `field_code`. Наибольший объём строк и набор типов определяет версия формы.
- Сопровождающий **very-hidden** лист `_FIN_Metadata` содержит `format=FIN-FORM-XLSX-V2`, `formVersion`, `schemaHash`, `templateInstanceId`, `projectId`, `organizationId`, `entityId`, `businessScopeId`, `reportingBasis`, `currency`, `scale`, `periodKind`, `periodStart/end`; используется для обнаружения несоответствий, а **не** для авторизации. Наличие скрытого листа или хеша НЕ доказывает подлинность источника.
- Никаких «значений по умолчанию = 0»; пустая сумма → `MISSING`, явный `0` → `EXPLICIT_ZERO`. Формулы, внешние ссылки, макросы, скрытые незарегистрированные значения и неутверждённые `field_code` запрещены.
- В имени файла можно включить `projectCode_formCode_YYYY.MM_lang_version.xlsx`, но **имя файла не является доверенным метаданным**.

**Машинный эквивалент:** тот же `FormSchema(version, period, fields, validations, labels)` возвращается из API и рендерится React. XLSX и React не должны иметь отдельных списков полей и правил. Клиент может валидировать для удобства; *решение о принятии* принимает сервер.

### 3.3. Период и язык

- `YYYY.MM` (например `2026.10`) в V2 означает **месяц**; сервер проверяет регулярный формат и календарную допустимость, нормализует к `YearMonth`. Не принимать `2026.13`, Excel serial dates и формат без ведущего нуля.
- У `F02/F03` показатель относится к периоду `[2026-10-01, 2026-11-01)`; у `F01` — к дате `2026-10-31`. Данные разных месяцев нельзя смешать из-за одинакового отображаемого имени.
- Квартал, год, произвольный диапазон и остаток на конкретную дату — отдельные `period_kind` и layouts следующих версий; не кодировать годовой отчёт под видом `YYYY.MM`.
- Все три языка обязательны **для публикации формы V2**, включая названия формы, заголовки, названия полей и сообщения валидации. Незавершённый перевод → `DRAFT`, а не молчаливое заполнение выдуманной терминологией.

### 3.4. Состояния и квалификация

`DRAFT → TRANSLATED → VALIDATED → REVIEWED → ACTIVE → DEPRECATED`. Отдельно `EXECUTABLE_FINANCIAL_METHOD_QUALIFIED` после независимого S2.

Все 166 форм — исходный каталог; 5 пилотных форм — кандидаты для первой полной квалификации структуры и трехъязычного ввода. Остальные **не** становятся исполняемыми только из-за наличия названия.

## 4. PROCESS B — привязанная к проекту загрузка

### 4.1. Серверный проект, а не локальный заголовок компании

Новая/интегрируемая сущность `fin_project.financial_project`:

`project_id` UUID PK; `organization_id` UUID; `project_code` (человекочитаемый, уникален **в организации**); `project_name`; `economic_entity_id`; `business_scope_id`; `reporting_basis`; `default_currency`; `fiscal_calendar_ref`; `status`; `created_by`; `version`; `created_at`.

Не копировать финансовые данные в проект и не связывать их только по тексту названия. Привязка `(organization_id, project_id)` — серверный FK/tenant guard в `submission`, `template_issuance`, `fact_set_revision` (через версионированный project context/link), `review`, `assessment`. Нельзя добавлять голый `project_id` в существующие V1/2 без проверенного migration/backfill. Для `ACTUAL` project context immutable на момент ревизии; изменение принадлежности требует отдельной миграции.

**Выбор проекта:** `GET /api/v2/financial/projects` возвращает только разрешённые проекты. React запрещает вводить произвольный `entityId` и `scopeId` при выборе проекта: эти значения вычитываются сервером. Пользователь выбирает проект до шаблона и до ручного ввода; выбор сохраняется в сессии интерфейса, но сервер сверяет его с токеном и базой.

### 4.2. Три пользовательские ветки

**B1 — React manual**

1. Логин через настоящий Keycloak → список доступных проектов.
2. Выбор проекта, формы, языка, отчётного периода `YYYY.MM`, при необходимости валюты/масштаба согласно версии формы.
3. Backend выдаёт `FormSchema`, включая translations, типы, обязательность, time role, допустимые повторы.
4. React рендерит из schema, собирает только `field_code/source_row_id/value`, не подменяет пустые значения нулём.
5. `Validate`: server проверяет проект/ABAC/schema/period/value/дубликаты/полноту/взаимосвязи; возвращает `ValidationReport` с точными координатами и severity.
6. `Submit for review`: immutable `fin_intake.submission` с `project_id`, `form_code+version`, периодом и верифицированным `submitted_by`.
7. Независимый reviewer принимает или отклоняет с причиной; автоматическое approval запрещено.
8. Сформированные утверждённые `FactAssertion` могут войти в новый `FactSetRevision` только в scope выбранного проекта.

**B2 — Excel**

1. Автор выбирает **тот же** проект и форму и скачивает шаблон `FIN-FORM-XLSX-V2`.
2. Заполняет лист `FIN_Data` **с 3-й строки**, не меняет код формы и поля.
3. Выбирает целевой проект в React и загружает XLSX; клиентский выбор **является запросом**, а не полномочием.
4. Сервер проверяет actor/project permission, выдачу шаблона, `projectId`, `formVersion`, `schemaHash`, `period`, `language`, reporting basis, currency/scale, весь XLSX и raw SHA-256.
5. Пайплайн преобразует XLSX в **точно тот же нормализованный `ValidatedFormSubmission`**, что и B1; результат получает те же ошибки на те же некорректные данные.
6. Review → approved facts → publication → diagnostic graph без обхода через клиентский импорт.

**B3 — исправление**

Изменённые данные никогда не перезаписывают утверждённые исходные строки. Требуется новая `submission_revision` со ссылкой `supersedes`, независимый review, complete replacement snapshot и явно выведенные differences; конфликт `expectedCurrentRevisionId` → HTTP 409, старый результат не изменяется. Удаление ранее утверждённого поля/периода без отдельной политики restatement запрещено.

### 4.3. Общий контракт нормализации

```json
{
  "projectId": "<server-authorized-project-uuid>",
  "formCode": "F02",
  "formVersion": "FIN-FORM-V2",
  "schemaHash": "<pinned-definition-sha256>",
  "locale": "ru",
  "periodKind": "MONTH",
  "period": "2026.10",
  "inputMode": "MANUAL_OR_XLSX",
  "rows": [
    {"sourceRowId": "pnl", "fieldCode": "netRevenue", "rawValue": "400"},
    {"sourceRowId": "pnl", "fieldCode": "cogs", "rawValue": "260"},
    {"sourceRowId": "pnl", "fieldCode": "operatingExpenses", "rawValue": "90"}
  ]
}
```

Значения примерные; в настоящем запросе `inputMode` должен быть конкретно `MANUAL` или `XLSX`; поле `approved` пользователь **никогда** не передаёт. `economicEntityId`, `organizationId`, `reportingBasis` и право на `projectId` получаются backend из доверенного project context. Суммы передаются **строками** для точного `BigDecimal`, не как JavaScript floating-point; `locale` влияет на вывод, не на финансовую арифметику.

Результат валидации:
`{submissionId?,status:DRAFT|VALIDATION_FAILED|READY_FOR_REVIEW,formVersion,schemaHash,projectId,period,findings:[{severity,ruleCode,fieldCode,sourceRowId,cellCoordinate?,messageKey,args}],sourceDigest}`.
В XLSX `cellCoordinate` — `FIN_Data!D4`; в React — `fieldCode`/номер строки. Никаких скрытых client-side auto-approvals.

### 4.4. Контракт серверных API (предложение V2)

| Метод | Путь | Право |
|---|---|---|
| GET | `/api/v2/financial/projects` | `PROJECT_READ` |
| GET | `/api/v2/financial/projects/{projectId}/forms` | `FIN_FORM_READ` |
| GET | `/api/v2/financial/projects/{projectId}/forms/{formCode}/schema?version=&lang=` | `FIN_FORM_READ` |
| GET | `/api/v2/financial/projects/{projectId}/forms/{formCode}/template?period=2026.10&lang=ru` | `FIN_SUBMIT` |
| POST | `/api/v2/financial/projects/{projectId}/intake/validate` | `FIN_SUBMIT` |
| POST | `/api/v2/financial/projects/{projectId}/intake/manual` | `FIN_SUBMIT` |
| POST | `/api/v2/financial/projects/{projectId}/intake/xlsx` | `FIN_SUBMIT` |
| GET | `/api/v2/financial/projects/{projectId}/submissions/{id}` | `FIN_READ` |
| POST | `/api/v2/financial/projects/{projectId}/submissions/{id}/review` | `FIN_REVIEW` |
| POST | `/api/v2/financial/projects/{projectId}/fact-sets` | `FIN_PUBLISH` |
| GET/POST | `/api/v2/financial/projects/{projectId}/diagnostics/...` | `FIN_READ/FIN_ANALYZE` |

Реализовывать поверх уже существующих `ReviewedFinancialIntakeService`, `TrustedFinancialFormMapper`, `FactSetPublicationService`, `TrustedCrossFormDiagnosticService`. Старый API `/api/v1/financial/organizations/{org}/...` **не удалять молча**: пометить V1 как legacy, запретить смешивание `FIN-PILOT-V1` и `FIN-FORM-V2` в одной версии документа без явного adapter/migration.

## 5. Единые правила безопасности и валидации

1. **Tenancy и project isolation:** signed JWT subject + trusted `fin_access` → проверка именно project scope и роли, а не только организации. Никаких произвольных project IDs из XLSX/URL без server DB resolution.
2. **ABAC:** `organization,project,form,field,period,action,expiry`; reviewer должен видеть только разрешённые поля (маскирование/фильтрация ответа), без утечек через export или diagnostic evidence.
3. **Form contract:** form code/version/schema hash; статус ACTIVE; period kind; все required fields; value type/scale/currency; row uniqueness, source coordinates, dimension schema, справочники/допустимые значения, decimals `BigDecimal`.
4. **XLSX security:** лимиты размера, ZIP bomb/decompression, formula, external links, dynamic cell types, hidden malicious sheets, unknown rows/columns, template issuer mismatch, duplicate lines, blank vs explicit zero. Неподтверждённый файл → VALIDATION_FAILED, без изменения published snapshot.
5. **Financial checks:** `F01 ASSETS = LIABILITIES + EQUITY`; F01 month end/previous close; F02/F03 flows; cross-form reconciliation only for complete populations; missing linked evidence → `MISSING_EVIDENCE`; accounting discrepancy ≠ автоматически причинная гипотеза.
6. **Review and publication:** `submitter != reviewer` on DB; approval/rejection immutable; reviewer not creator; publication scoped by project, set completeness, snapshot hash, optimistic concurrency, no silent row loss. Separate reasoned restatement.
7. **Idempotency and retries:** `Idempotency-Key` per project/action and digest, retries must not create duplicate claims/approvals; read operations do not create revisions.
8. **Audit:** authenticated actor, project, form version, period, schema hash, file hash, timestamps, review decision, publication revision, evidence source links. Financial amounts not dumped to application logs.
9. **Production gates:** Keycloak realm real UAT, S2 finance review, source authenticity assurance, and full-field ABAC. `FIN_ACTUAL_PREVIEW_ENABLED=false` in production until gates clear.

## 6. Достаточный smoke-тест на MacBook — не нагрузочные испытания

### 6.1. Предусловия

- macOS ARM64 (MacBook), JDK 21, Node 22+, Docker Desktop, PostgreSQL 16 (Testcontainers), React/Vite, настоящий тестовый **Keycloak realm** с проверенным issuer/JWKS/audience и dedicated `fin_access` claims. Указание docker compose здесь **не означает, что нужный compose уже есть в репозитории**.
- `User A`: author (`FIN_FORM_READ,FIN_SUBMIT,FIN_READ` в Project A); `User B`: reviewer/publisher/analyzer (`FIN_REVIEW,FIN_PUBLISH,FIN_READ,FIN_ANALYZE` в Project A); нет доступа к Project B. Проверка запрета самоутверждения отдельно для User A с `FIN_REVIEW`.
- Project A (`project_id` из настоящей PostgreSQL-модели), Project B в той же или другой организации, с различными `entity/scope`; локальный `.fin.json` **не используется** как серверный проект.
- Пять пилотных версионированных форм, словарь переводов `ru/kk/en`; набор реальных тестовых сумм маркирован `UAT_FIXTURE`, а не выдаётся за бухгалтерскую отчётность клиента.
- CI `verify/backend-verification/desktop-macos/acceptance-gate` GREEN **на одном коммите** перед ручным UAT. Smoke на живом Keycloak/DB НЕ подменяется MockMvc mock JWT и Playwright с выключенным OIDC.

### 6.2. Минимальные сценарии (15)

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| SMK-01 | User A login/logout/relogin | Новый токен, проект A доступен, B недоступен |
| SMK-02 | Открыть словарь `F02` на RU/KK/EN | Одинаковы fieldCode/order/version/schemaHash; реально переведены title/header/field labels |
| SMK-03 | Генерировать F02 Excel на `2026.10` | Строка 1 код/язык/период/название, строка 2 RU/KK/EN headers, строка 3+ поля; project bound |
| SMK-04 | Открыть F02 React по тому же коду | Одинаковы схема, коды полей, период, валюта и правила с XLSX |
| SMK-05 | Ввести F02 через React | SERVER `READY_FOR_REVIEW`, неизменяемая заявка в Project A |
| SMK-06 | Заполнить идентичные F02 значения через XLSX | Одинаковые нормализованные строки, BigDecimal и ValidationReport; исходный XLSX SHA сохранён |
| SMK-07 | Удалить обязательный показатель | Оба канала получают один `ruleCode`; опубликованные данные не меняются |
| SMK-08 | Изменить `formCode`, `schemaHash`, язык/период или Project B в Excel | Import rejected `FORM_VERSION/PROJECT/PERIOD_MISMATCH`; нет публикации |
| SMK-09 | Неправильный месяц `2026.13`, Excel date, формула, duplicate row | Корректные блокировки, точная координата ошибки |
| SMK-10 | User A пытается сам утвердить | 403 либо доменный `SELF_REVIEW_FORBIDDEN`, запись review не создана |
| SMK-11 | User B проверяет XLSX оригинал и утверждает | Решение/проверяющий immutable, canonical facts и provenance существуют |
| SMK-12 | User B публикует F01 (предыдущий и текущий месяц), F02, F03, две денежные формы | `ACTUAL FactSetRevision` относится только к Project A, проверена полнота |
| SMK-13 | Запросить graph preview в тестовом режиме | `PROVISIONAL/S2 HOLD`, 6 пилотных методик, понятны суммы, расхождения, ссылки на source fields |
| SMK-14 | Убрать денежную форму / попробовать повторную публикацию с потерей одобренных строк | `MISSING_EVIDENCE` для расчёта либо `409/conflict` для потери истории; старый snapshot воспроизводим |
| SMK-15 | Проверка прав через REST/React для чужого Project B, отозванного токена, expired grant | 403/401 без финансовых данных; после reload/reopen Project A и diagnostic snapshot доступны только с правом |

Набор чисел UAT (только демонстрация): F01 конец сентября `ASSETS=900 LIABILITIES=320 EQUITY=580 CASH=100 AR=50`; конец октября `1000/350/650/120/80`. F02 октябрь `REV_NET=400 COGS=260 OPEX=90`; F03 `CFO=35 CFI=-10 CFF=-5`. Денежные расшифровки `CASH_IN=300`, `CASH_OUT=280`. Проверяется равенство `1000=350+650`, `120-100=35-10-5`, `300-280=20`. Прочие связи могут быть **диагностическими наблюдениями**, не принудительными accounting identities.

### 6.3. Отчёт об испытании

Артефакты (создаются тестом, **не имитировать**): `SMOKE-RESULTS.md` со статусами PASS/FAIL/BLOCKED, SHA, дата/время, macOS/JDK/Node/Docker/Keycloak versions, версия схемы, `projectId` (тестовый), входные хеши, response codes, ключевые SQL-счётчики, browser screenshots, backend trace IDs и explicit blockers.

**Критерий приёмки:** все 15 сценариев PASS на одном SHA, независимый автор/проверяющий, одинаковый парсерный результат React/XLSX, ненарушенная изоляция проектов, воспроизводимое объяснение графа; `ACTUAL` остаётся provisional, пока finance S2 не принят. При отсутствующем Keycloak или server project model → BLOCKED, не PASS. Не проводить 1000/10000-user нагрузку на этом этапе.

## 7. Реализационные пакеты, последовательность

| Этап | Что входит | Обязательный результат |
|---|---|---|
| **P0-S1 / Design & RED** | Реестр form/field/translation, проектная модель, проверяемый контракт XLSX/React; PostgreSQL RED-тесты, независимый S2 architecture | Одобрены схемы, API и отрицательные тесты |
| **P0-S2 / Form Dictionary** | SQL/Flyway, 5 V2 пилотных шаблонов RU/KK/EN, schema endpoint, dynamic React, V2 Excel parser/generator | Один versioned schema → одинаковые XLSX/React поля; старый V1 не сломан |
| **P0-S3 / Project Binding** | Истинные server projects + ABAC, проектные записи, source/FactSet/assessment linkage и publication guards | Невозможен доступ, импорт или публикация в чужой проект |
| **P0-S4 / Unified Intake** | Общий Validate/Parse/Review pipeline для ручного/XLSX, provenance, migration of approved facts | Parity goldens и negative PostgreSQL tests PASS |
| **P0-S5 / 15 smoke** | Два реальных пользователя Keycloak, PostgreSQL, React, 5 форм, 2 периода, ручные негативные тесты | Подписанный UAT-отчёт, все 15 PASS |
| **P0-S6 / Qualification** | Finance S2, external-source authentication, scopes/expiry ABAC, audit and threat model | Только после этого рассматривать снятие production HOLD |

**Приоритет:** не начинать S2–S6 без фиксированной версии словаря и проектного контракта S1. Но после S1 выполнять **вертикальный срез на F02** через реальный React/XLSX → review, затем F01/F03/cash и graph E2E; не создавать сразу 166 непроверенных схем.

## 8. Нефункциональные требования / инварианты

- `source digest` ≠ криптографическая подлинность внешнего бухгалтерского документа; это проверка целостности **полученного** источника.
- `schema_hash` пинится в заявке, ревизии и diagnostic snapshot; изменение переводов без изменения кода/метрики не меняет арифметику, но должно сохранять именно использованную display version. Правило расчёта — отдельная версия методики.
- React/Excel: единицы, precision, missing/zero, локализация и календарь совпадают. Сервер сам нормализует только разрешённый формат decimals; **не** полагаться на Excel float для финансовых сумм высокой точности.
- Для одной формы возможны несколько листов/секций только по явно утверждённой `layoutVersion`; базовый V2 имеет `FIN_Data` и hidden `_FIN_Metadata`.
- Прямые JSON requests к новым API должны иметь те же валидаторы, что формы UI; нет доверия клиентской проверке.
- SQL FK: организация + проект + form/version + period + source revision; tenant isolation и no-self-review остаются на уровне PostgreSQL, а не только React.
- Версионирование: export и import старых `FIN-PILOT-V1` и `FIN_BALANCE 1.0.0` не удалять; переход через explicit adapter и smoke regression, не silent migration.
- Отсутствующий доступ/источник/реальный Keycloak/finance S2 → предсказуемый BLOCKED/HOLD, не «успешная диагностика» с подставными значениями.

## 9. Принятые проектные решения и открытые блокеры

- **Решение A:** универсальная форма на основе реестра — ДА. Ручные hardcoded поля и набор шаблонов на 166 специальных Java-классов — НЕТ.
- **Решение B:** явная серверная связь отчёта с `projectId` — ДА; название компании в Excel и локальный `.fin.json` не являются правом доступа.
- **Решение C:** внешний лист в требуемом UX формате 1-я метаданные / 2-я заголовки RU/KK/EN / 3+ данные — ДА; скрытый manifest как дополнительная защитная проверка, не источник авторизации.
- **Решение D:** локаль `ru/kk/en`, период `YYYY.MM` и V2 major-версия — ДА для monthly pilot; другие периоды — отдельные контракты.
- **Блокер 1:** нет текущей server project CRUD/ACL и подтверждённого dual-user live Keycloak — нельзя называть пользовательский smoke пройденным.
- **Блокер 2:** переводы/типизированные поля не квалифицированы для всех 166 — расширение только после методического review.
- **Блокер 3:** источник данных может быть самозаполненным XLSX — финансы требуют проверки подлинности и независимого S2.
- **Блокер 4:** `FIN_ACTUAL_PREVIEW_ENABLED=false` в production; preview не превращается в финансовое заключение автоматически.

## 10. Definition of Done (для этого ТЗ)

- [x] Процессы A/B разделены с явной границей ответственности.
- [x] Зафиксированы строки 1/2/3+, RU/KK/EN, период `YYYY.MM`, поле `projectId`.
- [x] Проанализированы разрывы с исходным кодом, сохранена совместимость со старыми моделями.
- [x] Определены API, SQL-объекты, роли, безопасность, версии и последовательность разработки.
- [x] Определён минимально достаточный smoke-план с негативными сценариями и критериями.
- [ ] Техническая реализация, живой Keycloak smoke, независимое finance S2 — **предмет следующих этапов**, не заявлять как выполненные.
