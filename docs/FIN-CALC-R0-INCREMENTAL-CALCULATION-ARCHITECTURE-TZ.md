# ТЗ FIN CALC-R0 — Incremental Calculation Architecture Foundation (FIN HTAP v2)

**Статус:** SPECIFICATION + 166-FORM BASELINE / IMPLEMENTATION NOT STARTED / FINANCIAL S2 HOLD  
**Репозиторий:** `alimbetov/fin-analyzer`  
**Единственная рабочая ветка:** `feat/fin-form-library-standalone`; без создания ветки, merge в `main`, изменения старых миграций или переноса старого FIN Analyzer Backend.  
**Роль:** новая минимальная расчётная основа в standalone FIN Form Library, а не заявление о готовом production HTAP.  
**Зависимость:** FIN-EDIT-R1/R3, FIN R16 typed contracts и FIN-BE-R2/FIN-MR-R2 (при последующей интеграции по проверенным контрактам).

## 1. Цель и принцип MVP

Собрать в текущем модульном монолите Spring Boot / Java 21 + PostgreSQL 16 два изолированных расчётных контура:

1. **Interactive Preview:** `React → debounce → preview API → UI`. Выполняет только предварительные формулы для несохранённых или сохранённых вводов, не публикует факты, не изменяет `fin_intake`/FIN HTAP.
2. **Qualified Execution:** `trusted qualified FactSet → incremental DAG plan → PostgreSQL jobs → worker → immutable result → guarded current pointer`. Запускается **только** от независимого доверенного допуска, никогда непосредственно от изменения черновика.

Transactional outbox доставляет события, PostgreSQL queue даёт конкурентное выполнение и retry, DAG определяет затронутые узлы, fingerprint обеспечивает дедупликацию результата.

**R0 MVP:** один вертикальный сценарий `F02 → immutable revision → independent qualification/test fixture → FactSet → 3-node DAG → PostgreSQL job → immutable result`. Не строить универсальный конструктор 166 форм, distributed scheduler, UI администратора методик или Kafka до функционального доказательства этого сценария.

## 2. Проверенное исходное состояние (source inventory)

- Источники: `catalog/form-navigation-r10.tsv`, `form-compatibility.tsv`, `form-field-presentation-review-r6.tsv`, `analytics-model-catalog.tsv` в `form-library/src/main/resources/catalog/`.
- 166 уникальных форм: 8 FINANCE, 10 PLANNING, 14 LEDGERS, 12 EVIDENCE, 59 DETAIL, 63 SECTOR.
- 794 **публикационных/кандидатных** описания полей в R6 для 154 форм; 12 EVIDENCE форм не имеют R6 строк, хотя runtime bootstrap создаёт для них metadata-поля. Эти 794 нельзя приравнивать к физическим полям: FIN-EDIT-R1 фиксирует отдельную проверку ~877 runtime fields.
- Runtime bootstrap на чистой БД задаёт 3 `SINGLE_RECORD` (`F01/F02/F03`), 151 `ROWSET`, 12 `EVIDENCE`. Это **bootstrap baseline**, не верификация конкретной существующей БД и не финансовое утверждение.
- Из 42 каталогизированных аналитических моделей 37 `DESIGN_ONLY`, 5 `CALC_PILOT`; эти статусы **не подтверждают исполнение алгоритмов**.
- Существующий `ReportDerivedTotalService` поддерживает только `SUM`/`LAST_VALUE` для Report Grid как `UNQUALIFIED_DRAFT`. `FormDraftService` отдельно сохраняет immutable JSON/ROWSET в `fin_intake`. Автоматической единой проекции и FIN HTAP writer в этой ветке нет.
- У standalone нет production IAM/S2 authority. `X-FIN-UAT-Token` — метка корреляции, не разрешение. Выполнение на реальных персональных/банковских финансовых данных и открытый production доступ запрещены.

**Приложение:** `docs/FIN-CALC-R0-COVERAGE-166-BASELINE.tsv` — одна строка на каждую форму. Поля `candidate_operations_not_approved` являются **гипотезами для ревью**, а не готовыми формулами. `consumer_models_declared` — обратные ссылки из каталога моделей; НЕ прямые зависимости между формами. Значение `NOT_DECLARED` означает отсутствие утверждённого формального form→form dependency. `NOT_AUTHORED` / `NOT_PROVEN_PER_FORM` запрещают интерпретировать строку как реализованную методику.

## 3. Термины и границы доверия

- **DraftRevision**: `fin_intake.lib_form_revision` + при необходимости `lib_form_revision_row` и evidence; exact `revision_id`, `form_version_id`, `canonical_content_sha256`, `row_order_sha256`, организация/проект/период/валюта/basis/coverage/dataSpace. Immutable source.
- **Preview**: эфемерный расчёт из непроверенных значений, не является фактом, approval или финансовым заключением.
- **QualifiedFactSetBinding**: проверяемый объект допуска конкретных редакций/метрик/версий с идентификатором, canonical SHA-256, authority decision, validity, revocation epoch, точным периодом, областью и организацией. Не принимать `approved=true` от React или тела обычного REST-запроса.
- **CalculationDescriptor**: versioned ID методики/формулы, typed inputs/outputs, strategy, precision/rounding, parameter digest, canonical configuration digest, build artifact SHA-256 и contract version.
- **CalculationPlan**: неизменяемый snapshot разрешённого DAG, конкретных входов и binding. **CalculationJob**: отдельно перепланируемая попытка исполнения. **CalculationResult**: append-only результат и lineage. **CurrentPointer**: управляемая ссылка на допущенный актуальный результат.
- **Исполнение не равно публикации.** `SUCCEEDED` не означает `CURRENT`.

**R0 admission policy:** доверенный production S2 / FIN-BE authority в этой standalone ветке отсутствует. Для PostgreSQL E2E допускается только **отдельная изолированная синтетическая TEST_QUALIFIED фикстура** с явно тестовым issuer и `LOCAL_TEST_ONLY` результатами; её нельзя экспортировать или трактовать как реальный `APPROVED`/S2. В не-тестовой среде Qualified Calculation **fail closed**, пока не появится валидируемый независимый authority adapter. Исходный запрошенный путь через настоящий **Approved FactSet** становится production acceptance gate, а не имитацией одобрения в R0.

## 4. Функциональные требования

### CALC-FR-01. Полная source-матрица 166 форм
- Зафиксировать все 166 форм, группы, семантическую зернистость (grain), bootstrap content mode, известные исходные поля/типы, candidate operations, модели-потребители, отсутствие/наличие зависимости и статусы.
- Контроль: 166 уникальных code; 8/10/14/12/59/63; 794 R6 полей для 154 кодов; 42 модели; не терять 12 document/external-tax форм; запрещены выдуманные поля, формулы, S2 approval.
- Для утверждения формул отдельно потребуется полевой `fieldCode → metricCode/version → value kind → time semantics → aggregation policy → unit → null/missing policy` контракт. `R0 baseline != R1 formula approval`.

### CALC-FR-02. Typed operations без runtime-скриптов
- MVP операции `SUBTRACT`, `DIVIDE` и проверка неполного ввода, реализованные в Java `BigDecimal` по жесткому allowlist и версии дескриптора. Никаких `eval`, SpEL из пользовательского ввода, динамического SQL и JavaScript-формул на сервере.
- В дальнейшем расширяемый реестр: SUM, LAST_VALUE, RATIO, WEIGHTED_AVG, BALANCE_CHECK, ROLL_FORWARD, RECONCILE, AGING, NPV, IRR, SCENARIO_SHOCK и др. **Количество ~30 не является контрактным пределом.**
- Не приводить MISSING/NOT_APPLICABLE к нулю; DELTA по STOCK не равен сумме остатков; несовместимые периоды, валюты и units должны блокироваться.

### CALC-FR-03. F02 pilot DAG
Пиновать точную версию F02 (`FIN-FORM-V2` или её реальный `form_version_id`), три входа `netRevenue`, `cogs`, `operatingExpenses` с типом MONEY. Все они должны пройти действующий field-contract guard.

```text
F02.netRevenue ─┬─> grossProfit = netRevenue - cogs ──┬─> grossMargin = grossProfit / netRevenue
F02.cogs ───────┘                                      └─> operatingResult = grossProfit - operatingExpenses
F02.operatingExpenses ────────────────────────────────────────────────────┘
```

Результаты — пилотные **derived outputs**, не изменения исходного `values_json`, не `fin_fact`. `grossMargin` — безразмерная доля (не умножать на 100 при хранении), представление в процентах — отдельно. При выручке 0 возвращать явный `DIVISION_BY_ZERO`, а не 0/NaN. Нормы rounding/scale должны быть версионированными параметрами. При недостатке входов — `BLOCKED_MISSING_INPUT`; жёсткая ошибка только если тип некорректен. Отдельно проверять F02 completeness по всем трём обязательным полям.

Change impact:
- `operatingExpenses` → **только** `operatingResult`;
- `cogs` → `grossProfit`, `grossMargin`, `operatingResult`;
- `netRevenue` → все три derived outputs.
- Вторичный узел не исполняется без необходимых upstream результатов; DAG acyclic, детерминированный topological sort.

### CALC-FR-04. Interactive Preview
- Endpoint-кандидат `POST /api/v1/calc/preview` (только локальная UAT-область до IAM): versioned formCode/formVersion, typed field values, period/scope, clientSequence. Никаких `organizationId` как доказательства владения.
- React: debounce (начальная гипотеза 250–350 ms), отмена in-flight `AbortController`, отбрасывание stale response по sequence, показ result state, источник `PREVIEW_UNQUALIFIED`.
- Preview **не записывает** ни `fin_intake`, ни `fin_calc`, ни `fin_fact`; отсутствие сети не мешает обычному сохранению отчёта. При сохранении действует старый immutable flow.

### CALC-FR-05. Transactional Outbox
- Событие `DRAFT_REVISION_SAVED` записывается в одной DB транзакции с новой immutable редакцией, только после реального изменения. Нет outbox на rollback, failed save, exact idempotent replay или invalid request.
- Draft событие допустимо **только для локальной проекции/предпросмотра**. Оно не запускает Qualified Workers.
- Только доверенное `FACTSET_QUALIFIED` с exact binding может инициировать qualification plan. Также учитывать `QUALIFICATION_REVOKED`, `METHOD_CONFIG_ACTIVATED` от их авторитетных источников; эти события являются **целевыми интерфейсами**, не существующими сейчас producer'ами.
- Event delivery at-least-once; дедупликация по source event ID + topic/domain; возможна обработка out of order, порядок определяется revision/binding epoch, а не временем поступления.

### CALC-FR-06. Incremental DAG Planner
- `GraphDescriptor` immutable/versioned. Build из allowlisted descriptors; запрет циклов, отсутствующих входов, несовместимых grain/unit/time semantics, неутверждённого переназначения `metricCode`.
- Impact сравнивает **точные canonical values по fieldCode/rowId/period** у версий inputs; изменение визуального порядка `ROWSET` не меняет финансовое содержимое, если алгоритм не чувствителен к порядку.
- R0: один небольшой DAG в одном job, а не отдельный PostgreSQL job для каждой операции. Будущая partitioning стратегия — только по бенчмаркам.
- При идентичном fingerprint — reuse валидного immutable результата, но **перед возвращением как CURRENT повторная проверка актуального admission обязательна**.

### CALC-FR-07. PostgreSQL Job Queue
- `PENDING → RUNNING → SUCCEEDED`; альтернативы `FAILED_RETRYABLE`, `FAILED_FINAL`, `SUPERSEDED`, `BLOCKED_STALE_APPROVAL`. У plan отдельный `PUBLISHED`/rejected state.
- `SELECT ... FOR UPDATE SKIP LOCKED` + `UPDATE RETURNING` в **короткой транзакции claim**. Worker выполняет CPU расчёт **после COMMIT claim**.
- Каждому claim присваивается монотонный `fencing_token`; `lease_until`, `lease_owner`, heartbeat, max attempts, bounded retry with backoff + jitter, expired-lease recovery, terminal failure visibility.
- `SUCCEEDED` записывается только conditional update по текущим token/owner/lease и после проверки версии плана. Устаревший worker не должен портить state или current pointer.
- Нагрузка ограничена размером пула/очереди; никаких безлимитных virtual threads.

### CALC-FR-08. Fingerprint и immutable результаты
- Использовать canonical serialization **с версией и однозначными границами полей** (JSON canonical/length-prefixed, не простое сцепление без разделителей).
- `fingerprint = SHA-256(domainTag, contractVersion, tenantScope, methodologyId+version, artifactSha256, effectiveConfigSha256, paramsSha256, qualifiedFactSetId+sha, orderedInputs[revisionId,formVersionId,contentSHA,rowOrderSHA if semantically relevant,unit,time], targetPeriod, grain, currency/basis/coverage, calculationPolicyVersion)`.
- Fingerprint **не то же самое**, что `resultSha256`; `executionId` не часть fingerprint; результат имеет reproducible source lineage.
- Результат append-only, уникальность fingerprint в разрешённой tenant-scope; конкурентные дублёры возвращают одну ссылку. `Caffeine` только для immutable descriptors/graphs, не источник истины для прав и revocation.

### CALC-FR-09. Qualification / publication gate
- Всякий qualified plan создаётся **только** через `TrustedQualificationGateway`, который подтверждает authority, decision SHA, binding к FactSet/config/artifact, validity и revocation epoch.
- Перед установкой `current_result` атомарно перепроверить revocation/config epoch, exact pinned input binding, fencing, ожидания current pointer (CAS / row lock). Исполнение и опубликование независимы.
- После отзыва/смены config старые immutable результаты остаются в истории, но endpoint CURRENT обязан fail-closed или вернуть только другой валидный результат. Запрос без авторизованного tenancy в production запрещён.

## 5. Минимальная модель хранения (новая схема `fin_calc`)

R0 придерживается **пяти таблиц**, без отдельного Kafka и без таблицы на каждый узел DAG:

| Таблица | Минимальные поля и ограничения |
|---|---|
| `calc_outbox_event` | `id PK`, `producer`, `event_type`, `aggregate_id`, `revision_id`, `payload_sha256`, `payload_json`, `occurred_at`, `published_at`, `attempts`, UNIQUE(producer, producer_event_id); payload SHA не является дедупликационным ключом жизненного цикла |
| `calc_plan` | `id PK`, tenant/project/period scope, `factset_id`, `factset_sha256`, `qualification_binding_sha256`, `qualification_epoch`, `method_id/version`, `config_sha256`, `params_sha256`, `artifact_sha256`, `dag_snapshot_json`, `dag_sha256`, `status`, `created_at` |
| `calc_job` | `id PK`, `plan_id FK`, `node_group_key`, `fingerprint`, `status`, `priority`, `attempt_count`, `available_at`, `lease_owner`, `lease_until`, `fencing_token`, `last_error_code`, timestamps; UNIQUE(plan_id,node_group_key) |
| `calc_result` | `id PK`, tenant/project scope, `plan_id FK`, `fingerprint`, `result_sha256`, `result_json` (canonical numeric strings/states), `lineage_json`, `computed_at`, `qualification_mode`; unique tenant-scope+fingerprint; UPDATE/DELETE forbidden |
| `calc_current_result` | tenant/project/method/period stable key, `result_id FK`, `pointer_version`, `approval_epoch`, `activated_at`; FK and CAS gate |

Это **логическая SQL-спецификация**, точные миграции и PostgreSQL constraints/indices будут написаны в R0.B. `calc_outbox_event` и результат имеют разные правила жизненного цикла: технические поля доставки outbox изменяемы; финансовые payload и result immutable. У `calc_plan` DAG фиксируется в snapshot JSON + SHA (не хранить отдельную таблицу edges до необходимости). Существующие `fin_lib` / `fin_intake` таблицы не переписывать.

## 6. Транзакционные границы и concurrency

1. **TX-A**: сохранить `fin_intake` revision + append outbox event; rollback удаляет оба. У исходного `FormDraftService` должны сохраниться no-op/409/idempotency правила.
2. **TX-B**: dispatcher читает недоставленные события, создаёт план только для доверенного `FACTSET_QUALIFIED` и дедуплицирует. На недоверенном draft событии запрещено создание qualified job.
3. **TX-C**: claim очереди по `FOR UPDATE SKIP LOCKED`, обновить lease, token, status; COMMIT.
4. **Compute (вне TX)**: проверить pinned facts, получить typed inputs, выполнить DAG, рассчитать fingerprint и result SHA.
5. **TX-D**: зафиксировать immutable result только если fencing/plan/binding ещё действительны; CAS publication с проверкой admission в той же логической критической секции; снять lease.
6. **Recovery**: reaper находит просроченные RUNNING, переоткрывает задачи с увеличением token; повторные callback/same fingerprint не дублируют result.
7. **Revocation race**: отзыв должен сериализоваться с publication по общей версии/locking authority. Простое `SELECT approved` перед `INSERT` без блокировки — НЕПРИЕМЛЕМО. Чтение current также проверяет действующий admission.

## 7. REST и локальные UI-контракты

**MVP preview:**
`POST /api/v1/calc/preview` → `{contractVersion, formCode, formVersionId, inputState, period, values, clientSequence}`; ответ `{clientSequence, qualification:"PREVIEW_UNQUALIFIED", outputs:[{code,state,value,unit}],issues,descriptorSha256}`.

**Read-only UI** (после включения real IAM или строго localhost UAT):
`GET /api/v1/calc/plans/{id}` → `{status,jobStates,blockingReasons,fingerprint}`;
`GET /api/v1/calc/results/{id}` → immutable result + lineage + publication status.

**Не предоставлять публичный `POST /approve`/`POST /qualify` в standalone**. В R0 тестовый `TrustedQualificationGateway` вызывается только test harness; обязательный real approval API и issuer-binding являются отдельным production gate. REST-параметры organisation/project без проверенной identity не предоставляют доступ.

## 8. Нефункциональные требования и измерения

- Детерминированные BigDecimal операции; no floating-point для финансов; explicit currency/units/rounding, no implicit zero; stable input ordering.
- Isolation: tenant/project/period/form version и reporting basis обязательны в fingerprint и доступе; нет прямого `fin_intake → fin_fact` writer.
- Observability: `eventId`, `planId`, `jobId`, `executionId`, `fingerprint`, `factSetSha`, `methodId`, error code, duration, nodes total/recomputed/skipped, queue lag, retry/fence counters. Не логировать финансовые значения и PII.
- Целевые **гипотезы для нагрузочной квалификации**, не SLA текущего проекта: p95 preview ≤ 300 ms для 3 полей; p95 от trusted event до результата ≤ 2 s в локальном 1-проектном пилоте; zero unauthorized current publications; zero duplicate results for same scope/fingerprint. Измерять p95/p99 и throughput PostgreSQL/worker; отклонения не скрывать.
- `ddl-auto=validate`, Liquibase additive changesets; запрет правки исторических checksums/миграций. PostgreSQL 16 Testcontainers обязателен для concurrency; H2 не заменяет его.

## 9. Обязательная проверка сценариев (acceptance matrix)

| ID | Сценарий | Ожидание |
|---|---|---|
| A01 | R0 coverage 166 + source audit | 166 unique; 794 R6 fields; 42 models; no invented approved formula; doc 12 retained |
| A02 | F02 preview values/revenue/cogs/opex | correct GP, operatingResult, grossMargin; no DB writes |
| A03 | Preview missing, zero denominator, bad decimals | explicit states; no NaN/implicit zero |
| A04 | React rapid edits/out-of-order replies | only newest sequence displayed |
| A05 | F02 save revision 1→2 | original immutable, exact revision ID/SHA preserved |
| A06 | Outbox same TX as revision; rollback/no-op | exactly 1 event on committed new revision; none on rejected save |
| A07 | Duplicate/out-of-order event | at-least-once safe; no duplicate qualified plan/result |
| A08 | Draft event without qualified FactSet | no qualified job and no current publication |
| A09 | Isolated TEST_QUALIFIED binding → DAG → worker | correct typed outputs; result labelled LOCAL_TEST_ONLY |
| A10 | Modify only operatingExpenses (new bound FactSet) | only operatingResult marked dirty/recomputed; unchanged nodes reused |
| A11 | Same inputs/config/artifact fingerprint | immutable result reused, no new financial result |
| A12 | 2–4 concurrent workers claim same job | at most one active fencing owner, no duplicate current |
| A13 | Worker A lease expires; B reclaims; A finishes late | A cannot update job/result/current |
| A14 | Revoke admission during compute/publication | fail-closed current; rejected stale advancement; old history retained |
| A15 | Activate algorithm/config version with new SHA | changed fingerprint; no stale cache/current reuse |
| A16 | Crash after claim/after result write/before publish | recovery, idempotent retry, no phantom current |
| A17 | Missing input/incorrect grain/currency/period | deterministic blocked/error without publishing |
| A18 | PostgreSQL full migration + React browser regression | CI GREEN **on same exact SHA**, no legacy regression |

### Проверяемые SQL-постусловия
- Счётчик `fin_intake.lib_form_revision` соответствует коммитам save; rollback не оставляет `calc_outbox_event`.
- `calc_job`: истёкший fencing token не может совершить conditional write; heartbeat/lease не допускают double-own.
- `calc_result`: unique scope/fingerprint и append-only; result JSON и lineage hash воспроизводимы.
- `calc_current_result`: только binding с допустимым epoch; отзыв исключает current даже при concurrent publication.

## 10. Этапы разработки без переусложнения

| Этап | Содержимое | Условие завершения |
|---|---|---|
| **R0.A — Contract + Coverage** | Данное ТЗ, baseline 166 форм, 3 F02 formulas, аудит исходников | Полнота и неизобретённые зависимости подтверждены; DESIGN READY |
| **R0.B — Minimal Calculation Kernel** | Typed F02 DAG, preview, additive tables, outbox и один PostgreSQL worker | A02–A09 PostgreSQL + React GREEN |
| **R0.C — Incremental + Safety** | Field-level impact, fingerprint, stale/lease/fencing, revoke, algorithm change | A10–A17 PostgreSQL concurrency GREEN |
| **R0.D — Qualification** | CI full regression, браузер, измерения, независимый code review | A18 GREEN, локальный TEST_ONLY пилот разрешён |
| **R1 / Production Gate (вне R0)** | Реальный S2/IAM, FactSet authority, approved publish, масштабирование на 166 форм | Нет HOLD; финансовый выпуск отдельно |

Не создавать generic formula DSL, 30 ops, массовую поддержку 166 расчётов, Kafka/Debezium, ClickHouse, микросервисы или Redis в R0. Caffeine допустим только для versioned descriptor/DAG cache, не для admission и current status. Outbox transport может быть заменён позже без изменения доменной логики.

## 11. Неподлежащие нарушению ограничения

1. **Сохранение черновика не равно публикации FactSet** и не разрешает FIN HTAP write.
2. **Без независимого issuer/S2 нельзя обозначать результат APPROVED**, в том числе в локальном UAT; test fixture явно помечается TEST_ONLY.
3. **План, формула и входные редакции пинованы**; ни выбор последней версии формы, ни обновление активного алгоритма не должны молча менять уже исполняемый план.
4. **Нельзя выдавать статус IMPLEMENTED по наличию текста**. Принимать только PostgreSQL 16 + HTTP/React доказательства на одном SHA.
5. Работать только в `feat/fin-form-library-standalone`, не менять `main`, старые Liquibase и чужие ветки.

## 12. Definition of Done

**R0.A**: два документа в ветке, точно 166 строк матрицы и все формы включены; формулы/зависимости честно кандидатные, несогласованные поля видны, на код/миграции никаких изменений.  
**R0.D**: F02 preview + synthetic qualified E2E + DAG + очередь + fencing + immutable result, отрицательные тесты A01–A18, CI GREEN на одном SHA.  
**Production**: НЕ READY, пока нет независимого финансового S2, IAM/ABAC и реального approved FactSet admission.

### Source references
- `form-library/src/main/java/kz/fin/formlib/service/FormDraftService.java`
- `form-library/src/main/java/kz/fin/formlib/service/ReportDerivedTotalService.java`
- `form-library/src/main/java/kz/fin/formlib/bootstrap/CatalogBootstrap.java`
- `form-library/src/main/resources/db/changelog/001-lib-schema.sql`, `003-lib-report-grid.sql`, `014-lib-versioned-field-contracts.sql`
- `docs/FIN-EDIT-R1-166-FORM-QUALIFICATION.md`, `docs/FIN-EDIT-R3-PERSISTENCE-QUALIFICATION.md`.
