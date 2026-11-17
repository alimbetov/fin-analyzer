# FIN-FORM-LIB-R1 — единый справочник 166 форм, порядок показателей и Excel Clipboard

**Статус:** DRAFT / ТЗ к независимому техническому и финансовому review; не свидетельство реализации.  
**Repository:** alimbetov/fin-analyzer; **единственная ветка:** `feat/fin-r0-r1a`, `main` не менять.  
**Проверенный baseline:** `01a52d9e392c16896facd9f4458935dd334ced21` (2026-11-17). Перед миграцией и кодом проверить новый HEAD.  
**Стек:** Java 21, Spring Boot, PostgreSQL 16, Flyway; React + TypeScript + Vite (отдельно разрешаемый UI-инкремент).  
**Нормативные границы:** `docs/FIN-HTAP-CONSOLIDATED-TZ.md`, `docs/FIN-HTAP-FORM-166-INCREMENTAL-RECALCULATION-AND-PARTITION-LIFECYCLE-TZ.md`, `docs/ADR-R2-14-FIRST-VERTICAL-SLICE-DELIVERY-FREEZE.md`. Эта спецификация НЕ снимает финансовый S2, ACTUAL HOLD и существующий HTAP-V1 приоритет. Реализация React и масштабной миграции 166 форм требует явно согласованного изменения scope/очередности ADR-R2-14. Можно реализовать изолированный пилот без нового запуска финансовых методик.

## 1. Цель и принятые решения

Пользователь выбирает одну из 166 форм, видит версионированные разделы и показатели в установленном порядке, вручную вводит значения или вставляет диапазон Excel с Ctrl/Cmd+V в React, проверяет ошибки, сохраняет immutable черновую ревизию, закрывает и повторно открывает её без потерь. Тот же backend-контракт обслуживает React, XLSX round-trip и дальнейший источник FIN HTAP.

1. **Все новые физические таблицы имеют префикс `lib_`.** Справочники в `fin_lib`, пользовательские записи и ревизии в `fin_intake`; префикс является частью полного имени (например, `fin_lib.lib_form_field`). Не переименовывать существующие таблицы `fin_fact`/`fin_intake` и не создавать второй `financial_fact`.
2. Формат версии формы: `SINGLE_RECORD` (один объект значений), `ROWSET` (строки с устойчивыми ID и позициями) или `EVIDENCE` (реестр документов с привязкой к оригиналу и SHA-256). Возможность прикрепить документ как доказательство к двум первым форматам предусмотрена, но не превращает их в четвёртый режим.
3. Три **независимых порядка**: `section_order` для разделов, `display_order` для полей внутри раздела, `row_position` для пользовательских строк ROWSET. Позиции — только представление, никогда не идентичность поля или финансового показателя.
4. Идентичность показателя: `field_code` + `form_version_id` и явно утверждённый `metric_code/metric_version` в отдельном финансовом mapping-контракте. Методика никогда не выбирает данные по `display_order` или индексу JSON/Excel.
5. `DRAFT_VALID` означает техническую корректность формы, **не** `SOURCE_APPROVED`, **не** `FACT_SET_PUBLISHED` и **не** `EXECUTION_PERMITTED`. Публикация в `fin_fact` остаётся в существующем независимом доверенном контуре.
6. Версии определения, сформированные ревизии, документы и их digest — immutable. Меняется только указатель «активная версия справочника» или «текущая ревизия слота» с compare-and-swap (CAS).
7. **Не создаём 166 React-страниц, 166 сервисов или отдельный планировщик.** Один декларативный Form Engine и существующие source/HTAP механизмы.

## 2. Сверка с существующим кодом

| Существующий компонент | Установленный факт | Решение |
|---|---|---|
| `backend/src/main/resources/fin/form-compatibility.tsv` | 166 кодов: 122 TYPED_DRAFT, 12 EVIDENCE_ONLY, 32 SCHEMA_NOT_APPROVED | Seed только существующих идентичностей и статусов, не выдумывать квалификацию |
| `CatalogCaptureRegistry` | Типы и канонизация; успешная проверка возвращает `UNQUALIFIED_DRAFT` и `publishable=false` | Переиспользовать валидатор, а не создавать конкурирующие правила |
| `SpecializedSchemaCandidateRegistry` | Поля 32 специализированных форм — кандидаты, не финансовое approval | Импортировать как CANDIDATE, не превращать в ACTIVE FINANCIAL |
| `FormSchemaV5` | Пилот F02 имеет упорядоченные `netRevenue/cogs/operatingExpenses` | Обеспечить parity для пилота, не объявлять расчётные строки утверждёнными |
| `CatalogXlsxRoundTripService` и `CatalogIssuedTemplateService` | XLSX для каталога и draft-версии, сохранение SHA | Сохранять существующий путь; ввод clipboard должен дать ту же нормализованную семантику |
| `CatalogXlsxRevisionService`, `FormDraftRevisionV5Service` | Уже есть immutable draft/revision ledger и проверка конфликта | Никакого silent dual-write или второго изменяемого head для одного слота |
| `fin_fact.*` и `fin_exec.fact_publication_outbox` | Отдельный доверенный путь source approval / FactSet publication | В новом clipboard API запрещена прямая запись в trusted facts |
| `fin_exec.form_change_journal` | Уже есть outbox для нового подтверждённого каталогового черновика | Расширять через совместимый adapter и тесты, не дублировать события |

**P0 миграционный preflight:** провести инвентаризацию реальных PK/FK/API/доступных React grid-компонентов и зафиксировать source-of-truth на каждом этапе. До переключения legacy F02 и catalog XLSX остаются прежними владельцами слотов; библиотека определений служит read-only registry. Переключать запись на `lib_form_instance` только при доказанном паритете и atomic cutover, без параллельной записи в два ledger. Отдельный compatibility adapter читает legacy-ревизии.

## 3. Новые таблицы PostgreSQL

### 3.1. Справочник (схема `fin_lib`, таблицы `lib_*`)

| Таблица | Назначение / ключевые поля | Ограничения |
|---|---|---|
| `lib_form_definition` | `id UUID PK`, `form_code VARCHAR(128) UNIQUE`, `catalog`, `legacy_capture_mode`, `current_version_id` (CAS pointer) | Ровно 166 кодов на исходном seed; новые коды только через утверждённую процедуру |
| `lib_form_version` | `id UUID PK`, `form_id FK`, `version`, `content_mode`, `grain`, `qualification_state`, `schema_sha256`, `presentation_sha256`, `mapping_sha256`, `created_at` | `UNIQUE(form_id,version)`; история immutable; стартовый статус `CANDIDATE_UNQUALIFIED` |
| `lib_form_section` | `id UUID PK`, `form_version_id FK`, `section_code`, `section_order INT`, `parent_section_id NULL`, `layout_kind` | `UNIQUE(version_id,section_code)` и `UNIQUE(version_id,section_order)` на верхнем уровне; при иерархии order уникален в рамках parent |
| `lib_form_field` | `id UUID PK`, `form_version_id FK`, `section_id FK`, `field_code`, `display_order INT`, `field_type`, `input_mode`, `required`, `formula_code NULL`, `units/policy` | `UNIQUE(version_id,field_code)`; `UNIQUE(section_id,display_order)`; устойчивый `field_code` |
| `lib_form_field_translation` | `id`, `form_version_id`, `subject_type` (FORM/SECTION/FIELD), `subject_code`, `locale`, `title`, `description` | `UNIQUE(version_id,subject_type,subject_code,locale)`; ru/kk/en; перевод не меняет семантику |
| `lib_form_validation_rule` | `id`, `form_version_id`, `rule_code`, `rule_version`, `rule_kind`, `typed_params JSONB`, `severity` | Только allowlisted валидаторы Java; без исполняемого SQL/JS в таблицах |
| `lib_form_metric_mapping_candidate` | `id`, `form_version_id`, `field_code`, `metric_code`, `metric_version`, `mapping_sha256`, `review_state` | Только candidate/ссылка на существующий review flow; не создаёт альтернативного финансового утверждения |

Таблицы версий, разделов, полей, переводов, правил и кандидатных mapping неизменяемы после публикации версии; любое изменение делает новую версию и новые SHA. Не запрещать техническое удаление ошибочного **ещё не опубликованного** `DRAFT` в контролируемой транзакции; активированные определения и утверждённые исторические версии неизменяемы.

### 3.2. Пользовательское заполнение (схема `fin_intake`, таблицы `lib_*`)

| Таблица | Назначение / ключевые поля | Ограничения |
|---|---|---|
| `lib_form_instance` | `id`, `organization_id`, `economic_entity_id`, `business_scope_id`, `project_id`, `form_id`, `period_type`, `period_code`, `coverage`, `currency`, `reporting_basis`, `data_space`, `current_revision_id` | Непустой полный slot identity; CAS/version counter, FK tenant/slot; UNIQUE по всем измерениям слота с проверенной NULL-семантикой |
| `lib_form_revision` | `id`, `instance_id`, `revision_no`, `form_version_id`, `content_mode`, `values_json JSONB`, `canonical_content_sha256`, `row_order_sha256`, `source_kind`, `created_at/by`, `change_reason` | `UNIQUE(instance_id,revision_no)`; immutable; `values_json` — SINGLE_RECORD/metadata, не доверенный finance факт |
| `lib_form_revision_row` | `id`, `revision_id`, `row_id UUID` (устойчивая логическая идентичность), `row_position INT`, `values_json JSONB` | `UNIQUE(revision_id,row_id)` и `UNIQUE(revision_id,row_position)`; всегда явно `ORDER BY row_position,row_id` |
| `lib_form_revision_evidence` | `id`, `revision_id`, `evidence_id`, `document_ref`, `raw_sha256`, `media_type`, `size_bytes`, `metadata_json` | Ссылки только на существующий проверенный документный storage; байты не в JSONB; immutability |
| `lib_form_command_receipt` | `id`, `organization_id`, `idempotency_key`, `request_sha256`, `revision_id`, `result_code` | `UNIQUE(organization_id,idempotency_key)`; тот же ключ + иной payload → CONFLICT, тот же payload → исходный ответ |

**Не создавать `lib_financial_fact`.** Его роль уже выполняют утверждённые `fin_fact.fact_assertion` и связанные source revision / selection / publication. Все новые таблицы для intake хранят именно черновые источники. Если `fin_lib` отсутствует, создавать отдельной additive Flyway migration; версию миграции выбирать по фактическому HEAD, не изменять исторические миграции.

### 3.3. Физические ограничения

- `organization_id` передаётся только из доверенного `FinanceAccessPolicy`; запрос по чужому tenant должен давать 403/404 и не должен раскрывать existence.
- FK составные с tenant/parent ID там, где это предотвращает cross-tenant linkage, плюс индекс `(organization_id, form_id, period_type, period_code, currency)` для intake.
- Для `row_position`/`section_order`/`display_order`: `CHECK (>0)`; пользовательское представление по умолчанию с шагом 10 (`10,20,30`). Если места не осталось — новая версия с нормализацией order; идентификаторы не меняются.
- Статусы, коды формы, версии и типы — allowlist CHECK / FK, без «произвольного JSON» как модели бизнес-правил.
- Для `ROWSET` содержимое `lib_form_revision.values_json` может хранить только общую шапку; сами операции — отдельные `lib_form_revision_row`, не дублировать их полным массивом JSONB.
- Историческая ревизия всегда привязана к **точному** `form_version_id`; текущая версия справочника не переинтерпретирует старый JSON.

## 4. Контракт порядка (три слоя)

1. `lib_form_section.section_order` — порядок разделов внутри родителя.
2. `lib_form_field.display_order` — порядок показателей или колонок внутри раздела; stable `field_code` — ключ для значения.
3. `lib_form_revision_row.row_position` — порядок строк операций в конкретной immutable ревизии; `row_id` сохраняется при перестановке или изменении данных.

По умолчанию `ORDER BY section_order, display_order, field_code` и для ROWSET `ORDER BY row_position, row_id`. Нельзя опираться на физический порядок PostgreSQL/JSONB или порядок полей HTTP JSON.

**Два разных сортировочных действия:** временный React-sort по дате/сумме НЕ меняет `row_position` и не создает ревизию; действие «Сохранить новый порядок» создает новую immutable ревизию с теми же `row_id`, новыми `row_position`. Для хронологических методик источник порядка — версия календаря + economic event date + deterministic tie-breaker, **не** `row_position`.

## 5. Содержимое формы, отсутствие и вычисляемые поля

### SINGLE_RECORD (`F02` пилот)

`values_json` (значения — canonical DECIMAL strings, не binary floating point):

```json
{
  "netRevenue": {"state":"PRESENT","value":"12000000"},
  "cogs": {"state":"PRESENT","value":"7000000"},
  "operatingExpenses": {"state":"PRESENT","value":"2000000"}
}
```

### ROWSET (`DF-CSH-01` пилот)

Каждая строка содержит `row_id`, `row_position` и типизированные `values_json` с полями `paymentRef`, `postedAt`, `amount`, `purpose`, `invoiceRef`. Новые поля добавляются только новой версией form contract, а не произвольной пользовательской колонкой.

### EVIDENCE (документный пилот)

`document_ref + raw_sha256 + media_type + size_bytes` с подтверждением объекта в доверенном хранилище; простой вставленный текст/URL **не считается** оригиналом документа.

Для любого поля различать `MISSING`, `PRESENT` (включая числовой ноль), `EXPLICIT_EMPTY` и `NOT_APPLICABLE`. `DERIVED` и `READ_ONLY` нельзя изменять через clipboard. Вычисляемая «Валовая прибыль» в примере F02 не вводится и не публикуется как факт без отдельного утверждённого formula/mapping contract.

## 6. SHA-256, версия и модерация

Разделить отпечатки с явным `hash_codec_version` и domain separation:

| Отпечаток | Что включает | Когда меняется |
|---|---|---|
| `schema_sha256` | версии поля/тип/обязательность/кардинальность/валидация/период/содержимое | изменение структуры/правил |
| `presentation_sha256` | версии названий/переводов/section_order/display_order/layout | изменение внешнего вида |
| `mapping_sha256` | field→financial metric, формулы, policy versions | изменение финансовой интерпретации |
| `canonical_content_sha256` | точные typed value states, бизнес-контекст, source rows без случайного JSON-ключевого порядка | изменение фактических данных |
| `row_order_sha256` | последовательность устойчивых `row_id` по `row_position` | сохранённая перестановка ROWSET |
| `raw_sha256` | исходные байты документа/XLSX | изменение вложения |

Если order **только визуальный**, новый `presentation_sha256` или `row_order_sha256` не может запускать пересчёт финансовых метрик. Если бизнес-контракт специально делает последовательность операций семантической, это отдельное подтверждённое правило и новый semantic mapping SHA с review. Никакого финансирования в один лишь `schema_sha256`.

Режимы квалификации: `CATALOG_CANDIDATE` → `STRUCTURE_REVIEWED` → `ACTIVE_FOR_DRAFT`; независимо от них `FINANCIAL_SOURCE_APPROVED` и `METHOD_S2_APPROVED` определяются **старым trusted source/method flow**, а не таблицей `lib_*`. Изменение полей/формул, влияющих на finance, отзывает/блокирует старую методическую привязку и требует review перед new ACTUAL.

## 7. React Clipboard UX и безопасность

**Цель:** открыть форму → выбрать начальную ячейку → вставить `Ctrl+V` / `Cmd+V` из Excel → увидеть точное размещение и ошибки → подтвердить → сохранить черновик → открыть и увидеть те же значения и порядок.

- Один компонент `FinancialFormGrid` получает immutable `form_version_id`/поля/секции/order. UI не содержит собственного справочника кодов и формул.
- `onPaste` читает сначала `text/plain`, поддерживает TSV с кавычками, встроенными переводами строк, пустыми ячейками, CRLF. Для HTML таблиц — только строго sanitized fallback при отсутствии plain text, без DOM/JS из clipboard.
- Вставка начинается с выбранной ячейки, ширина и высота матрицы проверяются. Позиционное сопоставление разрешено при совпадающей версии layout; вставка «с заголовками» — только по точным `field_code` или утверждённым локализованным alias, неоднозначность блокирует preview.
- Начальный bounded лимит — **до 200 строк, не более 20 полей в одной вставке** (согласован с известным `CatalogCaptureRegistry.MAX_ROWS=200`). Нагрузочные замеры определяют следующий лимит; более длинная вставка не обрезается молча.
- Для `SINGLE_RECORD` вставляются несколько значений в соответствующие поля/строки; `ROWSET` добавляет/обновляет строки с новыми `row_id` лишь для новых операций. `EVIDENCE` не принимает поддельные документы через текст.
- Локальный preview показывает клетки `VALID/INVALID/UNKNOWN_COLUMN/REQUIRED_MISSING/READ_ONLY/CURRENCY_MISMATCH`, count created/updated, предпросмотр сохранённого order. Ошибочные данные можно исправить, но подтверждение при блокирующих ошибках отключено.
- Точный locale-controlled parser: `ru/kk/en`; даты `DD.MM.YYYY` только по явно выбранной политике и normalize to ISO; Money/Decimal серверной канонизацией `BigDecimal` и agreed scale; разделители тысяч не угадывать; `=, +, -, @` как потенциальные текстовые Excel-formulas экранировать при XLSX export, **не запрещать корректные отрицательные числовые значения**.
- `Undo/Redo` до Save, предупреждение об изменениях, отсутствие автопубликации и сетевой записи при Ctrl+V. Не логировать значения clipboard, налоговые/персональные реквизиты, оригинальные документы.
- Сохранение возможно только после backend re-validation, с `expectedRevision`/`If-Match` и `Idempotency-Key`; concurrent edits → 409 с возможностью сравнения ревизий.

## 8. Backend API (проектируемые маршруты; перед реализацией проверить конфликты)

| Метод/маршрут | Контракт |
|---|---|
| `GET /api/fin/form-library` | код/название/доступный режим/версия/статус, фильтрация и пагинация по авторизованной области |
| `GET /api/fin/form-library/{formCode}/versions/{version}` | разделы, поля, 3 типа порядка (первые 2 — layout), constraints, labels, SHA |
| `POST /api/fin/form-drafts/paste-preview` | parsed matrix/cells + exact form version/context/selected cell; только dry-run validation; возвращает errors/warnings/canonical candidate/digest |
| `POST /api/fin/form-instances` | создать слот там, где нет legacy owner; tenant берётся из auth, а не из тела |
| `POST /api/fin/form-instances/{instanceId}/revisions` | immutable revision from validated payload, expectedRevision, Idempotency-Key; 409 stale/conflict |
| `GET /api/fin/form-instances/{instanceId}/revisions/{revisionNo}` | точная frozen версия, values, sections/fields order, row IDs+positions, provenance |
| `GET /api/fin/form-instances/{instanceId}/latest` | pinned current revision by CAS, не «последний по времени» |

**Права:** existing `FinanceAccessPolicy`; read/edit/create tenant scoped, mapping/moderation — отдельные роли. Данные и исходные SHA не должны попадать чужому tenant; retry не создаёт второй revision.

## 9. Примеры ожидаемого поведения

**F02, порядок:** sections `RESULTS:10`; поля `netRevenue:10`, `cogs:20`, `operatingExpenses:40`. Кандидат `grossProfit:30 DERIVED` допускается **только после отдельного утверждения**, не становится автоматически утверждённым F02. Пользователь переставляет отображение — версия layout, факты не меняются.

**DF-CSH-01, ROWSET:** вставляет PAY-001/PAY-002/PAY-003 как `row_id` UUID и позиции 10/20/30; после повторного открытия тот же порядок и значения. При пользовательском сохранении перестановки позиции 30/10/20, идентичности остаются.

**EVIDENCE:** документ с `original_file_sha256` и `document_ref`. Простое вставление имени в ячейку не порождает документ; недоступный объект/несовпадение SHA блокируются.

## 10. План реализации с gate на каждом шаге

| Gate | Работы | Фактический критерий |
|---|---|---|
| `LIB-R0` | Preflight PostgreSQL 16 schema/API, legacy slot owners, React root, Flyway numbering, согласование изменения ADR-R2-14 | Документированные конфликты/решения и first failing test, без изменения фактов |
| `LIB-R1` | Additive Flyway `fin_lib.lib_*`, read-only seed 166 из TSV; field+section order, translation, version, SHA | 166 уникальных кодов 122/12/32, F02 parity, 0 auto-approvals, миграция fresh+upgrade GREEN |
| `LIB-R2` | Новый intake ledger `fin_intake.lib_*` только для пилотов без legacy double-write; ROWSET stable IDs, CAS, immutable history | PostgreSQL 16 real tests: save/reopen/replace/reorder/concurrent/idempotence/tenant isolation |
| `LIB-R3` | React `FinancialFormGrid` + Ctrl/Cmd+V; ввод F02 и DF-CSH-01, preview ошибок | Playwright real paste 3×N, Undo/Redo, 409, reload, original order |
| `LIB-R4` | EVIDENCE refs + attachment SHA; XLSX vs Clipboard canonical parity | Независимая проверка original bytes/ref/hash; равный canonical source для одинакового набора |
| `LIB-R5` | Точечный adapter к существующему approved-source/FactSet flow, при условии отдельного S2 и HTAP-V1 gate | Черновик не публикует факт; qualified SYNTHETIC E2E без ACTUAL bypass |

**Первый вертикальный результат без ожидания всех 166 финансовых схем:** справочник читается из API; F02 `SINGLE_RECORD` и DF-CSH-01 `ROWSET` можно заполнить через paste, сохранить, reopen; одна EVIDENCE-форма прикрепляет документ. Для формы, где contract только `CATALOG_SUMMARY`, отображать `SCHEMA_NOT_APPROVED`/draft-only, не создавать фиктивно заполненный финансовый контракт.

## 11. Обязательная проверка (RED → GREEN)

| Test ID | Сценарий | Обязательный результат |
|---|---|---|
| `LIB-001` | Импорт всех 166 TSV идентичностей | Ровно 166, count 122/12/32, checksum и локали корректны |
| `LIB-002` | Конфликт `form_code`/version/`field_code`/section order/field order | PostgreSQL rejects |
| `LIB-003` | Смена presentation order без смены смысла | Новый presentation hash, прежний mapping/content hash |
| `LIB-004` | Смена field type/formula/mapping | Новый schema/mapping hash, прежний financial approval не переносится |
| `LIB-005` | Историческая F02 ревизия после новой версии каталога | Старый order/labels/values не меняются |
| `LIB-006` | Tenant A читает/пишет tenant B, в том числе через guessed UUID | 403/404; SQL FK guard |
| `LIB-007` | Две записи с одним expectedRevision | Ровно одна CAS-победа, второй 409, нет lost updates |
| `LIB-008` | Повтор Idempotency-Key и тот же payload / иной payload | Same receipt / 409 |
| `LIB-009` | ROWSET reorder | `row_id` стабильны, `row_position` новые, старые ревизии неизменны |
| `LIB-010` | Пустое, нулевое, отсутствующее и неприменимое значение | Четыре различные состояния; отсутствие не равно 0 |
| `LIB-011` | Попытка UPDATE/DELETE immutable revision/activated schema | PostgreSQL fail closed |
| `LIB-012` | XLSX/copy-paste одинаковые значения | Одинаковый typed canonical payload (при одном контексте/версии) |
| `PASTE-001` | Табуляция, CRLF, quoted text с переносом строки | Матрица без потери ячеек и order |
| `PASTE-002` | Заголовки в ином порядке | Mapping по trusted aliases, не по индексу |
| `PASTE-003` | Дубли/неизвестный заголовок, readonly поле | Блокирующая ошибка на конкретной ячейке |
| `PASTE-004` | 201 строк, более 20 колонок или oversized text | Отказ без усечения/записи |
| `PASTE-005` | Запятая/точка, дата, отрицательное число, Excel-formula string | Канонизация по locale, no formula execution, никакой silent conversion |
| `PASTE-006` | React paste 3 строки → Save → reload | Три исходные записи и порядок, backend receipt |
| `PASTE-007` | Undo после paste и отмена формы | Нет backend revision |
| `PASTE-008` | Draft paste → попытка HTAP ACTUAL без S2 | Ноль утверждённых финансовых фактов, fail-closed |
| `PASTE-009` | Сохранить документ с изменённым hash или отсутствующей storage reference | Блокировка, без фальшивого EVIDENCE |
| `PASTE-010` | Проверка совместимости с legacy F02/XLSX и прежними Flyway | Full backend regression GREEN, старые подтверждения читаются |

Тесты в PostgreSQL 16 через Testcontainers, Browser E2E — Playwright, CI публикует точный HEAD SHA, набор тестов, результаты, миграции fresh+upgrade. Unit тестов недостаточно для приёмки.

## 12. STOP-правила и Definition of Done

- Не писать React/UI поверх неутверждённой схемы, выдавая CANDIDATE за финансово квалифицированную.
- Не создавать второй `fin_fact`, второй publication outbox, второй методический orchestrator, произвольный SQL/Javascript formula runner.
- Не менять исторические Flyway миграции, `main`, ACTUAL flags, deny-all guards, независимые S2-решения.
- Не объявлять `IMPLEMENTED` или `GREEN` до HTTP→PostgreSQL→reopen и браузерного E2E на одном commit SHA.
- В scope этого ТЗ **нет** массового запуска 60 методик, физического перераспределения HTAP партиций и автоматической конвертации 166 форм в утверждённые схемы.
- Отдельно фиксировать `DESIGNED`, `CODED`, `PG_GREEN`, `BROWSER_GREEN`, `S2_REVIEWED`, `RELEASED`.

**Приоритет реализации:** после согласования очередности начните с `LIB-R0` и `LIB-R1`; первый пользовательский proof — React paste F02 + DF-CSH-01 → PostgreSQL immutable draft → reload. Именно этот сценарий, а не количество новых классов, является измеримой целью.
