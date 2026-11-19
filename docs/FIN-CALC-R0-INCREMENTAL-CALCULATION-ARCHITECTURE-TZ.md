# ТЗ FIN CALC-R0 — Incremental Calculation Architecture Foundation (FIN HTAP v2)

**Статус:** SPECIFICATION HARDENED AFTER SOURCE→FACT S1 DATA-FLOW REVIEW / 166-FORM + 794-FIELD BASELINES / IMPLEMENTATION NOT STARTED / FINANCIAL S2 HOLD  
**Репозиторий:** `alimbetov/fin-analyzer`  
**Единственная рабочая ветка:** `feat/fin-form-library-standalone`; без создания ветки, merge в `main`, изменения старых миграций или переноса старого FIN Analyzer Backend.  
**Роль:** новая минимальная расчётная основа в standalone FIN Form Library, а не заявление о готовом production HTAP.  
**Зависимость:** FIN-EDIT-R1/R3, FIN R16 typed contracts и FIN-BE-R2/FIN-MR-R2 (при последующей интеграции по проверенным контрактам).

## 1. Цель и принцип MVP

Собрать в текущем модульном монолите Spring Boot / Java 21 + PostgreSQL 16 два изолированных расчётных контура:

1. **Interactive Preview:** `React → debounce → preview API → UI`. Выполняет только предварительные формулы для несохранённых или сохранённых вводов, не публикует факты, не изменяет `fin_intake`/FIN HTAP.
2. **Qualified Execution:** `trusted qualified FactSet → incremental DAG plan → PostgreSQL jobs → worker → immutable result → guarded current pointer`. Запускается **только** от независимого доверенного допуска, никогда непосредственно от изменения черновика.

Transactional outbox доставляет события, PostgreSQL queue даёт конкурентное выполнение и retry, DAG определяет затронутые узлы, fingerprint обеспечивает дедупликацию результата. **Многопоточное исполнение обязательно:** изолированные ограниченные пулы для интерактивного preview и background qualified jobs; независимые DAG-узлы можно распараллелить внутри вычисления при оправданной сложности, с доказанным детерминизмом и контролем ресурсов.

**Фазовая cache-архитектура:** `CALC-R0 = PostgreSQL + incremental DAG + fingerprints + Caffeine L1`; `CALC-R1 = CalculationCache port + optional Redis L2 adapter`; `CALC-R2 = activation by measured benchmark`. Redis по умолчанию OFF, Redis не является ни authority, ни job broker, ни source of truth. Для правильного выполнения сначала проверяется trusted binding, затем выполняется поиск reuse/cache; **публикация CURRENT всегда проверяет актуальный допуск в PostgreSQL/authority, а не кеш**.

**R0 MVP:** один вертикальный сценарий `F02 → immutable revision → exact source reader → typed F02 source-to-fact mapping → independent qualification/test fixture → persisted fin_fact FactSet + 3 observations → 3-node DAG → PostgreSQL job → immutable result`. Не строить универсальный конструктор 166 форм, distributed scheduler, UI администратора методик или Kafka до функционального доказательства этого сценария.

## 2. Проверенное исходное состояние (source inventory)

- Источники: `catalog/form-navigation-r10.tsv`, `form-compatibility.tsv`, `form-field-presentation-review-r6.tsv`, `analytics-model-catalog.tsv` в `form-library/src/main/resources/catalog/`.
- 166 уникальных форм: 8 FINANCE, 10 PLANNING, 14 LEDGERS, 12 EVIDENCE, 59 DETAIL, 63 SECTOR.
- 794 **публикационных/кандидатных** описания полей в R6 для 154 форм; 12 EVIDENCE форм не имеют R6 строк, хотя runtime bootstrap создаёт для них metadata-поля. Эти 794 нельзя приравнивать к физическим полям: FIN-EDIT-R1 фиксирует отдельную проверку ~877 runtime fields.
- Runtime bootstrap на чистой БД задаёт 3 `SINGLE_RECORD` (`F01/F02/F03`), 151 `ROWSET`, 12 `EVIDENCE`. Это **bootstrap baseline**, не верификация конкретной существующей БД и не финансовое утверждение.
- Из 42 каталогизированных аналитических моделей 37 `DESIGN_ONLY`, 5 `CALC_PILOT`; эти статусы **не подтверждают исполнение алгоритмов**.
- Существующий `ReportDerivedTotalService` поддерживает только `SUM`/`LAST_VALUE` для Report Grid как `UNQUALIFIED_DRAFT`. `FormDraftService` отдельно сохраняет immutable JSON/ROWSET в `fin_intake`. **В коде этой ветки отсутствуют `fin_fact` таблицы, source-to-fact mapper и qualified FactSet producer**; автоматической единой проекции и FIN HTAP writer нет. Указанные далее fact-таблицы — новые планируемые DDL, не существующие.
- У standalone нет production IAM/S2 authority. `X-FIN-UAT-Token` — метка корреляции, не разрешение. Выполнение на реальных персональных/банковских финансовых данных и открытый production доступ запрещены.

**Приложения:** `docs/FIN-CALC-R0-COVERAGE-166-BASELINE.tsv` — одна строка на каждую форму; `docs/FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv` — одна строка на каждое из 794 R6 кандидатных полей с явными пробелами по time semantics, units, source metric binding, aggregation и qualification. 84 runtime evidence metadata и расхождения с фактическими физическими таблицами должны быть отдельно reconciled по DB, а не замаскированы автоматически. Сводная form-matrix не является исполняемым calculation coverage contract. Поля `candidate_operations_not_approved` являются **гипотезами для ревью**, а не готовыми формулами. `consumer_models_declared` — обратные ссылки из каталога моделей; НЕ прямые зависимости между формами. Значение `NOT_DECLARED` означает отсутствие утверждённого формального form→form dependency. `NOT_AUTHORED` / `NOT_PROVEN_PER_FORM` запрещают интерпретировать строку как реализованную методику.

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
- Для утверждения формул отдельно потребуется полевой `fieldCode → metricCode/version → value kind → time semantics → aggregation policy → unit → null/missing policy` контракт, зафиксированный для **каждого** релевантного поля и версии формы. `R0 baseline != R1 formula approval`.
- Автоматически проверять оба TSV по exact source SHA и кодам: 166 уникальных formCode и 794 уникальных `(formCode,fieldCode)`; наличие поля в R6 не доказывает его наличие в runtime, правильную unit-policy или готовность к вычислению.

### CALC-FR-02. Typed operations без runtime-скриптов
- MVP операции `SUBTRACT`, `DIVIDE` и проверка неполного ввода, реализованные в Java `BigDecimal` по жесткому allowlist и версии дескриптора. Никаких `eval`, SpEL из пользовательского ввода, динамического SQL и JavaScript-формул на сервере.
- В дальнейшем расширяемый реестр: SUM, LAST_VALUE, RATIO, WEIGHTED_AVG, BALANCE_CHECK, ROLL_FORWARD, RECONCILE, AGING, NPV, IRR, SCENARIO_SHOCK и др. **Количество ~30 не является контрактным пределом.**
- Не приводить MISSING/NOT_APPLICABLE к нулю; DELTA по STOCK не равен сумме остатков; несовместимые периоды, валюты и units должны блокироваться.

### CALC-FR-03. F02 pilot DAG
Пиновать **реальный** `fin_lib.lib_form_version.id` F02 вместе с `schema_sha256`, `mapping_sha256` и `FIN-FORM-V2` (UUID у bootstrap может различаться между БД), три входа `netRevenue`, `cogs`, `operatingExpenses` с типом MONEY. Все они должны пройти действующий field-contract guard **и** локальную тестовую проверку типов, unit, period semantics, decimal precision, signed cost convention. `FieldMetricContractGuard.matches()` — структурная сверка с `fin_lib.lib_field_binding_contract`, НЕ независимое S2-утверждение. Выходы F02 пилота являются отдельными локальными derived codes; не регистрировать их как APPROVED `metric_code`.

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
- R0 без брокера: `processed_at` означает завершение DB-local dispatch, не Kafka publish. Брать outbox пачками через `SKIP LOCKED` и **одной** транзакцией фиксировать дедупликацию consumer, создание плана/job (только trusted event) и `processed_at`; при rollback все операции откатываются. Retry ограниченный, poison event получает `FAILED_FINAL`, а зависшие события видны мониторингу. Payload содержит ID+SHA, не сырые финансовые значения; финансовый source читается по pinned revision.

### CALC-FR-06. Incremental DAG Planner
- `GraphDescriptor` immutable/versioned. Build из allowlisted descriptors; запрет циклов, отсутствующих входов, несовместимых grain/unit/time semantics, неутверждённого переназначения `metricCode`.
- Impact сравнивает **точные canonical values по fieldCode/rowId/period** у версий inputs; изменение визуального порядка `ROWSET` не меняет финансовое содержимое, если алгоритм не чувствителен к порядку.
- R0: один небольшой DAG в одном job, а не отдельный PostgreSQL job для каждой операции. Будущая partitioning стратегия — только по бенчмаркам.
- Учитывать **два** уровня: целый binding/plan меняется при новой квалифицированной редакции, а отдельный node может сохранить тот же результат. Для адресного пересчёта сравнивать `nodeFingerprint`, построенный **только из реально потребляемых значений и соответствующих версий операций**, а не из SHA всего FactSet/редакции. Транзитивно грязные узлы пересчитать в топологическом порядке; неподверженные узлы могут быть использованы только при совпадении typed inputs, period, unit, config и valid state.
- При новой квалификации/F02 revision создавать **новый полный immutable result snapshot**, содержащий как пересчитанные, так и безопасно переиспользованные значения узлов, но с **новым binding/lineage**. Не перепривязывать прежнюю строку `calc_result` к чужому `plan_id`; историю сохранять.
- При идентичном fingerprint — reuse валидного immutable результата, но **перед возвращением как CURRENT повторная проверка актуального admission обязательна**.

### CALC-FR-07. PostgreSQL Job Queue
- `PENDING → RUNNING → SUCCEEDED`; альтернативы `FAILED_RETRYABLE`, `FAILED_FINAL`, `SUPERSEDED`, `BLOCKED_STALE_APPROVAL`. У plan отдельный `PUBLISHED`/rejected state.
- `SELECT ... FOR UPDATE SKIP LOCKED` + `UPDATE RETURNING` в **короткой транзакции claim**. Worker выполняет CPU расчёт **после COMMIT claim**.
- Каждому claim присваивается монотонный `fencing_token`; `lease_until`, `lease_owner`, heartbeat, max attempts, bounded retry with backoff + jitter, expired-lease recovery, terminal failure visibility.
- `SUCCEEDED` записывается только conditional update по текущим token/owner/lease и после проверки версии плана. Устаревший worker не должен портить state или current pointer. Проверка lease и fence должна быть в том же UPDATE/transaction, что и запись результата; недостаточно Java-проверки до вычисления.
- PostgreSQL queue даёт `at-least-once` вычисление, но **at-most-one accepted result per exact execution binding** и fenced publication. Expired RUNNING reaper увеличивает fence и переносит в PENDING/FAILED_RETRYABLE; worker heartbeat продлевает только своё действующее владение.
- Нагрузка ограничена размером пула/очереди; никаких безлимитных virtual threads.

### CALC-FR-08. Двухуровневый fingerprint, reuse и immutable результаты
- Использовать canonical serialization **с версией и однозначными границами полей** (canonical JSON/length-prefixed, не конкатенация неоднозначных строк). Числа нормализуются по явной numeric/scale policy, а состояния MISSING/NULL/NOT_APPLICABLE участвуют в digest.
- **`executionFingerprint` (full binding):** SHA-256 по domain tag, версии контракта, полному tenant/entity/businessScope/project/period/currency/basis/coverage/dataSpace scope, exact FactSet ID+SHA+admission epoch, methodology/variant+version, approved config/params/artifact SHA, ordered pinned revisions и input source hashes, DAG descriptor SHA. Это ключ **отдельного утверждённого исполнения** и одной полной immutable result-записи.
- **`nodeFingerprint` (computational reuse):** SHA-256 по domain tag, версии конкретного алгоритма узла, его effective params/config/rounding, typed unit/period/semantic dimensions, scope и **только фактически потребляемым operand value digests и upstream node fingerprints**. Не включает неиспользуемые поля, ID всей новой редакции или SHA всего FactSet; иначе требование адресного пересчёта A10 невыполнимо.
- При новом FactSet допускается reuse **вычисленного значения узла**, если его `nodeFingerprint` идентичен, исходный результат целостен, корректен и доступен в том же tenant/scope; новое `calc_result` содержит копию значения + новый binding, точный lineage до новых источников и метки RECOMPUTED/REUSED. Не разрешать cross-tenant reuse даже при совпадении числа. Неквалифицированные исходники не становятся APPROVED от reuse.
- Отдельно хранить **`outputValuesSha256`** — hash canonical экономических выходов (typed values/state/unit/algorithm policy; не включает REUSED/RECOMPUTED, timing, cache tier), и **`resultSha256`** — hash целостности полного immutable result snapshot с pinned binding/lineage/tech execution metadata; `executionId` идентифицирует попытку. Это три разных идентификатора, и только `outputValuesSha256` сравним между режимами Redis OFF/L1/L1+L2 при одинаковой методике и источниках. `calc_plan` должен быть уникален по full scope + `executionFingerprint`, чтобы дубль точного события находил первоначальный `plan_id`; иначе unique(result fingerprint) + FK на разные планы даст некорректный lineage.
- `calc_result` append-only с DB-level UPDATE/DELETE protection, уникальность `(tenant, executionFingerprint)`; Caffeine только для immutable descriptors/graph cache, не authority или revocation.

### CALC-FR-09. Qualification / publication gate
- Всякий qualified plan создаётся **только** через `TrustedQualificationGateway`, который подтверждает authority, decision SHA, binding к FactSet/config/artifact, validity и revocation epoch.
- Перед установкой `current_result` атомарно перепроверить revocation/config epoch, exact pinned input binding, fencing и expected current pointer (CAS/row lock). Исполнение и опубликование независимы. `calc_current_result` должен иметь полный stable identity: organization/economicEntity/businessScope/project/methodVariant/target period/currency/coverage/basis/dataSpace, а не только (project, method).
- **R0 local test:** test-only authority/epoch сериализуются с публикацией в одной PostgreSQL DB transaction (общий guard row и lock order; допускается plan row при строго одном тестовом binding). **Production:** если authority находится в другой БД/сервисе, простая проверка по HTTP и последующий COMMIT НЕ атомарны; production publication остаётся HOLD до описания синхронизированного authority epoch, обязательного fail-closed read gate и проверки гонки с отзывом. Не заявлять глобальную атомарность без общей транзакционной границы.
- После отзыва/смены config старые immutable результаты остаются в истории, но endpoint CURRENT обязан fail-closed или вернуть только другой валидный результат. Запрос без авторизованного tenancy в production запрещён.

### CALC-FR-10. Обязательное многопоточное исполнение и resource admission

- R0 **MUST** запускать `2–4` конкурирующих worker-процесса/потока в PostgreSQL Testcontainers-проверках; production sizing задаётся конфигурацией `fin.calc.workers.max`, по умолчанию `min(4, availableProcessors())` (минимум 1 на одноядерном окружении). Конкурентность принадлежит **разным независимым job и их данным**, не разделяемому мутабельному `BigDecimal` контексту.
- **CPU-bound** вычисления выполнять в отдельном bounded `ThreadPoolExecutor` / `ExecutorService` на platform threads (Java 21), а не в unlimited `virtual threads`, `parallelStream()` или общем `ForkJoinPool.commonPool()`. Параметры: `maxWorkers`, `maxQueuedJobs`, `maxNodeParallelism`, `perTenantInFlightLimit`, timeout/budget и shutdown policy; все значения измеряемы и управляемы конфигурацией. При переполнении — backpressure/defer task, но не запуск произвольного дополнительного потока.
- **Interactive Preview** получает отдельный bounded execution budget/queue от qualified workers; 3-node F02 допускает последовательное выполнение внутри одного задания, поскольку thread scheduling может стоить дороже BigDecimal арифметики. Однако **обязательна реальная параллельность независимых расчётных jobs**; независимые узлы/ветви большого DAG могут исполняться параллельно **по уровням топологического графа** (barrier после каждого уровня) при достижении измеренного порога затрат.
- Любой DAG worker держит **immutable** `ExecutionContext` с pin-версиями, typed input snapshots и отдельным локальным result-map, безопасным для concurrent access; зависимые узлы не запускаются до получения успешных результатов родителей. Ошибка/timeout/cancellation одного обязательного узла не допускает неполную публикацию. Нет float/double в финансовых вычислениях; `BigDecimal` immutable, `MathContext`/rounding explicit.
- I/O claim/heartbeat/fencing/publish выполняются в коротких PostgreSQL транзакциях вне CPU-bound DAG node tasks; каждый job имеет один fencing owner. Lease обновляется через heartbeat, пока ограниченная задача исполняется; отмена/прерывание/worker shutdown не гарантирует остановку CPU task, поэтому **TX-D/TX-E fencing обязательны независимо от cancellation**.
- Очередь обеспечивает fair scheduling и отсутствие monopolization одним tenant через `perTenantInFlightLimit` плюс ограничение in-flight по всему процессу. Если задач больше, чем capacity, оставлять их в PostgreSQL `PENDING` до доступного слота; не буферизовать их безлимитно в памяти.
- На shutdown: прекращение claim, корректное истечение/продление lease, bounded graceful wait, затем безопасный reaper/retry. Функциональная корректность не зависит от graceful shutdown.
- Один и тот же `nodeFingerprint` внутри процесса должен проходить single-flight только если это не обход trust gate; полученная value из single-flight — **не опубликованный результат**, а untrusted compute reuse. Cache coalescing не должно удерживать DB lock и не заменяет job idempotency.

### CALC-FR-11. Поэтапное кеширование Caffeine / optional Redis

**Ключевое правило:** `DAG impact → exact nodeFingerprint → trusted reusable-value lookup (L1/L2/PG) → compute on miss → full immutable result → TX-E publication gate`. Логическая схема хранения источника и публикации остаётся PostgreSQL; L1/L2 не являются системой принятия финансовых решений.

| Этап | Архитектурный результат | Условие |
|---|---|---|
| **CALC-R0** | Бounded `Caffeine` L1 для immutable/versioned `FormulaDescriptor` и `GraphDescriptor`. Переиспользование node values из **проверенных PostgreSQL immutable results**; для первого F02 пилота допустимо без отдельного node-value L1 cache, но `nodeFingerprint` обязателен. | Только сохранённые approved-for-test descriptors; ни raw draft, ни approval/revocation, ни current pointer в кеш. Нет Redis dependency в Maven. |
| **CALC-R1** | Ввести Java порт `CalculationCache` с методами `getNodeValue(tenantScopeHash,nodeFingerprint)`, `putNodeValue(...)` и `invalidateLocalDescriptor(...)`; локальный `CaffeineCalculationCache` и **необязательный** `RedisCalculationCache` adapter (L2), плюс `NoopCalculationCache` для сравнения. | Redis starter и конфигурация подключаются только при включаемом adapter-модуле, `fin.calc.redis.enabled=false` по умолчанию; PostgreSQL read/compute fallback обязателен. |
| **CALC-R2** | Benchmark и controlled Redis enablement в многопроцессной схеме; сравнить OFF vs L1 vs L1+L2 на одних фиксированных входах и одинаковом числе worker. | Включать только при воспроизводимом снижении p95/p99 и PostgreSQL load без роста error rate и без нарушения correctness/cross-tenant isolation; при отсутствии выгоды оставить OFF. |

- Cache key для node value: `fin:calc:node:v1:{tenantScopeHash}:{nodeFingerprint}` с domain separation, HMAC/key-scoping без PII; никакого `projectId` без версии алгоритма или `formCode` как самостоятельного ключа.
- Node value в кеше: только `valueState`, canonical numeric string/value unit, descriptor/operation SHA, `payloadSha256`, serialization version, optional provenance digest. Не хранить весь пользовательский JSON, JWT, доказательство approval или права доступа. Size/TTL/timeouts/bounded payload обязательны; при ошибке формата/SHA/версии считать cache miss, не публиковать.
- **Невозможна глобальная “очистка периода” как механизм корректности.** Новая редакция/конфигурация формирует новый `executionFingerprint`; dirty dependent nodes получают новые `nodeFingerprint`. Неподверженные узлы могут повторно использовать cached value только с проверкой типизированных operands/scope; новый итоговый `calc_result` всегда имеет новый exact binding/lineage.
- Порядок получения: **L1** immutable value → **optional L2** Redis → **PostgreSQL** проверенный ранее результат (или небольшой R1 node-value index, если оправдан benchmark) → вычисление. Нельзя делать быстрый Redis-hit основанием для пропуска `TrustedQualificationGateway` или TX-E/current validation. В R0 достаточно L1 для descriptors/graphs и прямой validated PG result reuse; full node-value cache — R1.
- Redis outage, timeout или slow response **fail-open только для кеша** (продолжить к PostgreSQL/compute), но **fail-closed для admission**; bounded timeout и breaker. Кеш не держит `lease`, `fencing_token`, `calc_current_result`, `qualification_epoch` как authority и не заменяет PostgreSQL Job Queue.
- Для локальных cache races нужен bounded single-flight с собственным лимитом памяти; для разных инстансов возможны дубли **чистых вычислений**, но не duplicate accepted immutable results. Redis distributed lock и Redis job queue запрещены в R0–R2.
- Наблюдаемость: `calc_cache_get_total{tier,hit_or_miss}`, `calc_cache_corrupt_total`, `calc_cache_timeout_total`, `calc_cache_evictions_total`, `calc_node_reused_total`, `calc_node_recomputed_total`, `calc_cpu_active_workers`, `calc_cpu_executor_queue_depth`, `calc_job_queue_lag_seconds`, `calc_tenant_backpressure_total`. Метрики не содержат организационных идентификаторов, fingerprint или финансовых значений.

### CALC-FR-12. ОБЯЗАТЕЛЬНЫЙ Source→Fact / FactSet boundary (P0)

**Структурный разрыв выявлен при S1 data-flow review**: исходное ТЗ имело абстрактный "Approved FactSet", но не определяло, как значения F02 переходят из `fin_intake.lib_form_revision.values_json TEXT` в проверенные факт-таблицы. Полный контракт, Java интерфейсы, DDL и тесты F01–F16: [Source-to-Fact Pipeline TЗ](temporary/FIN-CALC-R0-SOURCE-TO-FACT-PIPELINE-TZ.md).

1. **TX-A**: сохранение новой `fin_intake` revision + `DRAFT_REVISION_SAVED` outbox. Это событие **не публикует** fact и не запускает квалифицированный DAG.
2. **PinnedSourceReader**: читает точный revision UUID, `form_version_id`, полный scope из DB и верифицирует существующий `FIN-LIB-CONTENT-V1` canonical hash (не `SHA256(values_json)`). Не читает `current_revision_id` для пинованного плана.
3. **SourceFactAssembler**: versioned field→metric mapping, типизированные значения `BigDecimal`, period/grain/currency/unit/sign/missing policy; F02 тестовый scope: строго три источника `netRevenue,cogs,operatingExpenses`. `F02.netProfit` — legacy derived candidate, не четвёртый факт; не продвигать `CANDIDATE_UNQUALIFIED` mapping как S2. Детерминированно строит source+mapping+value digest.
4. **Independent qualification**: только доверенный `TrustedQualificationGateway` проверяет exact candidate factset SHA, issuer/epoch. В R0 разрешён лишь тестовый `TEST_QUALIFIED` в изолированной среде, в production — fail closed.
5. **TX-Q**: одной PostgreSQL-транзакцией сохраняет immutable `fin_fact.fact_set`, три `fin_fact.fact_observation`, обновляет локальный TEST_ONLY `fin_fact.factset_control` (active factset/epoch) и пишет trusted `FACTSET_QUALIFIED` outbox. Отказ любой операции откатывает всё; idempotent replay не дублирует observation.
6. **TX-B**: квалифицированный event dispatcher заново загружает persisted FactSet, проверяет SHA, mapping, authority epoch и completeness, затем создаёт `calc_plan`/`calc_job`. Worker читает **только pinned `fin_fact` наблюдения** через `QualifiedFactReader`, не черновой JSON и не latest редакцию.
7. **TX-E**: при публикации current проверяет именно active `factset_control`/epoch в локальном тесте. При новой квалификации старый current становится историческим до нового успешного расчёта; непринятая черновая правка не меняет active binding. Production внешний S2 без общей транзакции остаётся HOLD.
8. **Временные периоды и инвалидация**: new qualified binding → new `executionFingerprint`; dirty `nodeFingerprint` лишь по потребляемым значениям; time-window dependencies и AS_OF/INTERVAL задаются версионированными формулами. Никакого `SUM` остатков по месяцам, авто-FX и безусловной "чистки старых периодов".
9. **Предыдущие редакции/источники**: backfill существующих `fin_intake` данных, для которых нет новых outbox events, должен быть отдельным idempotent discovery/staging процессом без автоматического S2. `ROWSET`, EVIDENCE, `ReportGridService` — явно не входят в F02 assembler, требуют отдельных source adapters перед масштабированием на 166 форм.

**Новая минимальная модель R0**: пять таблиц `fin_calc` **плюс три** `fin_fact` (`fact_set`, `fact_observation`, `factset_control`). Это не отдельный DWH и не допуск к реальным банковским данным. Наличие Redis/Caffeine не изменяет trust boundary.

## 5. Минимальная модель хранения (схемы `fin_calc` + `fin_fact`)

R0 имеет **восемь новых таблиц**: пять `fin_calc` ниже и три `fin_fact` по CALC-FR-12. Это минимальный полный source→fact→calculate пилот, без Kafka, DWH и отдельной таблицы на каждый узел DAG. `019-fin-calc-r0-postgresql.sql` создаёт calc-контур, `020-fin-fact-r0-pilot-postgresql.sql` — факт-контур и FK `calc_plan.factset_id → fin_fact.fact_set.id`:

| Таблица | Минимальные поля и ограничения |
|---|---|
| `calc_outbox_event` | `id PK`, `producer`, `producer_event_id`, `event_type`, `aggregate_id`, `revision_id`, `payload_sha256`, `payload_json`, `occurred_at`, `processed_at`, `state`, `available_at`, `attempts`, `last_error_code`, UNIQUE(producer, producer_event_id); payload SHA не является дедупликационным ключом. Payload содержит ссылки и digests, не финансовые значения |
| `calc_plan` | `id PK`, **full** tenant/entity/scope/project/period/currency/basis/coverage/dataSpace key, `execution_fingerprint`, `factset_id`, `factset_sha256`, `qualification_binding_sha256`, `qualification_epoch`, `method_id/version`, `config_sha256`, `params_sha256`, `artifact_sha256`, `dag_snapshot_json`, `dag_sha256`, `status`, `created_at`; UNIQUE(full_scope,execution_fingerprint), immutable binding columns |
| `calc_job` | `id PK`, `plan_id FK`, `node_group_key`, `execution_fingerprint`, `status`, `priority`, `attempt_count`, `available_at`, `lease_owner`, `lease_until`, `fencing_token`, `last_error_code`, `result_id NULL`, timestamps; UNIQUE(plan_id,node_group_key), partial ready index, conditional fenced state transitions |
| `calc_result` | `id PK`, full scope, `plan_id FK`, `execution_fingerprint`, `output_values_sha256` (pure economic outputs; cache-mode invariant), `result_sha256` (full immutable snapshot integrity), `result_json` (complete 3-output snapshot incl. value states/nodeFingerprints/reuse flags), `lineage_json` (binding to **this** FactSet), `computed_at`, `qualification_mode`; UNIQUE(tenant,execution_fingerprint), DB-level UPDATE/DELETE forbidden |
| `calc_current_result` | complete organization/entity/businessScope/project/methodVariant/period/coverage/currency/basis/dataSpace stable key, `result_id FK`, `pointer_version`, `approval_epoch`, `activated_at`; FK, CAS, serialized admission/revocation gate |

Это **логическая SQL-спецификация**, точные миграции и PostgreSQL constraints/indices будут написаны в R0.B. `calc_outbox_event` и результат имеют разные правила жизненного цикла: технические поля доставки outbox изменяемы; финансовые payload и result immutable. У `calc_plan` DAG фиксируется в snapshot JSON + SHA (не хранить отдельную таблицу edges до необходимости). Существующие `fin_lib` / `fin_intake` таблицы не переписывать.

## 6. Транзакционные границы и concurrency

1. **TX-A**: сохранить `fin_intake` revision + append outbox event **в том же PostgreSQL и физической транзакции**; rollback удаляет оба. У исходного `FormDraftService` должны сохраниться no-op/409/idempotency правила.
2. **Pinned read + Assemble (no write)**: по exact revision ID + исходным canonical SHA и versioned mapping извлечь типизированные F02 source observations; сформировать candidate FactSet digest. **Independent test-only qualification** verifies the exact digest/issuer.
3. **TX-Q (qualified fact commit)**: вставить `fin_fact.fact_set` + `fact_observation`, обновить local `factset_control`, создать trusted `FACTSET_QUALIFIED` outbox **в одной DB транзакции**, не раньше доверенного допуска.
4. **TX-B**: dispatcher забирает DB-local unprocessed events через `SKIP LOCKED`, проверяет **persisted fin_fact digest/epoch**, дедуплицирует по `producer_event_id`, создаёт план/job только для доверенного `FACTSET_QUALIFIED` и помечает `processed_at` **в том же COMMIT**. Для `DRAFT_REVISION_SAVED` разрешено только локальное read-model действие; на неутверждённом черновике qualified job запрещён.
5. **TX-C**: claim очереди по `FOR UPDATE SKIP LOCKED`, обновить lease, token, status; COMMIT.
6. **Compute (вне TX)**: загрузить строго закреплённые `fin_fact.fact_observation` по FactSet ID, проверить canonical factset SHA, точный scope/mapping/period и active binding epoch; выполнить DAG и per-node dirty/reuse; подготовить output snapshot и SHA без DB UPDATE. **Никакого fallback к raw draft JSON** при ошибке фактов.
7. **TX-D (result commit)**: в одной DB транзакции условно по `lease_owner + fencing_token + lease_until + plan state` записать новый immutable result и `SUCCEEDED`/`result_id`; при rollback нет ни результата, ни SUCCEEDED. Если `executionFingerprint` уже закоммичен, возвращать исходный plan/result, не делать cross-plan rebinding.
8. **TX-E (publication)**: отдельная короткая транзакция, revalidate trusted authority/epoch, exact binding, config/artifact, plan state и CAS pointer, затем отметить план опубликованным. Это делает проверяемым crash **между результатом и публикацией**. Повтор TX-E идемпотентен.
9. **Recovery**: reaper находит просроченные RUNNING, переоткрывает задачи с увеличением token; retry и crash after TX-D before TX-E не дублируют результат и не создают phantom CURRENT.
10. **Revocation race**: отзыв должен сериализоваться с publication по общей версии/locking authority **в рамках допускаемого local test trust boundary**. Простое `SELECT approved` перед `INSERT` без блокировки — НЕПРИЕМЛЕМО. Чтение current также проверяет действующий admission; для внешней production authority без общей транзакции остаётся HOLD.

## 7. REST и локальные UI-контракты

**MVP preview:**
`POST /api/v1/calc/preview` → `{contractVersion, formCode, formVersionId, inputState, period, values, clientSequence}`; ответ `{clientSequence, qualification:"PREVIEW_UNQUALIFIED", outputs:[{code,state,value,unit}],issues,descriptorSha256}`.

**Read-only UI** (после включения real IAM или строго localhost UAT):
`GET /api/v1/calc/plans/{id}` → `{status,jobStates,blockingReasons,fingerprint}`;
`GET /api/v1/calc/results/{id}` → immutable result + lineage + publication status.

**Не предоставлять публичный `POST /approve`/`POST /qualify` в standalone**. В R0 тестовый `TrustedQualificationGateway` вызывается только test harness; обязательный real approval API и issuer-binding являются отдельным production gate. REST-параметры organisation/project без проверенной identity не предоставляют доступ.

## 8. Нефункциональные требования и измерения

- Детерминированные BigDecimal операции; no floating-point для финансов; explicit currency/units/rounding, no implicit zero; stable input ordering.
- Isolation: tenant/project/period/form version и reporting basis обязательны в fingerprint и доступе; нет прямого `fin_intake → fin_fact` writer. Preview в R0 только на loopback с feature flag, bounded payload (3 F02 inputs), без публичного Swagger write API для S2; rate limit и auth обязателен для внешнего размещения.
- **R0 source-to-fact boundary:** PinnedSourceReader читает точную `fin_intake` revision для assembler, а **worker читает уже типизированные `fin_fact.fact_observation` из пинованного FactSet**. Не использовать `current_revision_id` для pinned plan. Три новые fact-таблицы **не** заменяют `fin_intake` и не сливают его с `ReportGridService`; последний требует отдельного adapter + разрешения source conflict. Статусы `CANDIDATE_UNQUALIFIED` и `READY_FOR_REVIEW` не дают права на квалифицированный fact writer.
- **Hardening prerequisite:** existing `fin_intake` исторические revision/row/evidence таблицы не имеют доказанного PostgreSQL UPDATE/DELETE deny trigger. До production qualification обязателен DB-level append-only guard или equivalent access segregation и негативный SQL-тест; наличие Java immutable flow недостаточно.
- Observability: `eventId`, `planId`, `jobId`, `executionId`, `fingerprint`, `factSetSha`, `methodId`, error code, duration, nodes total/recomputed/skipped, queue lag, retry/fence counters, active/queued CPU workers, tenant backpressure, cache L1/L2 hits/misses/timeouts. Не логировать финансовые значения, tenant identifiers или PII как высококардинальные labels.
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
| A10 | Modify only operatingExpenses (new bound FactSet) | only operatingResult `nodeFingerprint` dirty/recomputed; grossProfit/grossMargin reused; **new complete result snapshot + new exact lineage**, no cross-plan FK alias |
| A11 | Same exact qualified binding, inputs, config, artifact | identical **executionFingerprint** yields same plan/result; separate qualified binding results in a distinct immutable result even if selected nodeFingerprints are unchanged |
| A12 | 2–4 concurrent workers claim same job | at most one active fencing owner, no duplicate current |
| A13 | Worker A lease expires; B reclaims; A finishes late | A cannot update job/result/current |
| A14 | Revoke local trusted-test admission during compute/publication | serialized fail-closed current; rejected stale advancement; old history retained; production authority integration explicitly HOLD |
| A15 | Activate algorithm/config version with new SHA | changed fingerprint; no stale cache/current reuse |
| A16 | Crash after TX-C; rollback during TX-D; crash after TX-D before TX-E | recovered claim, one immutable result, idempotent publication, no phantom current |
| A17 | Missing input/incorrect grain/currency/period | deterministic blocked/error without publishing |
| A18 | PostgreSQL full migration + React browser regression | CI GREEN **on same exact SHA**, no legacy regression |
| A19 | New revision changes unrelated input; node vs execution fingerprint | only dependent node hashes change; new plan and result lineage required |
| A20 | Unauthorized HTTP preview/worker API and cross-scope reads | bounded localhost preview, no public approve, no cross-tenant output or data leak |
| A21 | Synthetic local revocation/config activation concurrent with TX-E | locked ordering / epoch CAS guarantees no CURRENT advancement after revocation |
| A22 | 2–4 independent jobs on bounded worker pool | prove parallel execution with barriers/latches; each job exactly once accepted by DB fence |
| A23 | Independent DAG branches executed in parallel above threshold | correct topological barriers, deterministic SHA and identical outputs to sequential mode |
| A24 | One tenant floods PostgreSQL jobs while another tenant submits work | per-tenant limit, bounded memory, no starvation; queue backpressure and visibility |
| A25 | Concurrent cache L1/optional L2 reuse and eviction | stable key + immutable payload validation, corrupt hit treated as miss, no cross-tenant data |
| A26 | Redis disabled, timeout, outage and restart (R1/R2 only) | seamless PostgreSQL/compute fallback; no skipped authorization or phantom CURRENT |
| A27 | CPU worker crash, heartbeat expiry, cancellation race | no stale owner result, no published partial DAG; reaper and idempotent retry |
| A28 | Identical benchmark workloads Redis OFF vs Caffeine vs Caffeine+Redis (R2) | store p95/p99, CPU, PG load, cache hit/miss, error budget, compare exact `outputValuesSha256`, and record release decision |
| A29 | Source-to-Fact qualification seam: test-only F02 revision | `fin_fact.fact_set` + exactly 3 typed observations + trusted event atomically committed, exact SHA/lineage |
| A30 | Source correction, stale mapping, source SHA mismatch and out-of-order qualification | reject wrong facts/current, preserve history, no implicit draft promotion |
| A31 | Full PostgreSQL migration 001–020, failed TX-Q, replay, source tampering and backfill discovery | F01–F16 in source-to-fact appendix GREEN; no historical checksums rewritten |

### Проверяемые SQL-постусловия
- Счётчик `fin_intake.lib_form_revision` соответствует коммитам save; rollback не оставляет `calc_outbox_event`.
- `calc_job`: истёкший fencing token не может совершить conditional write; heartbeat/lease не допускают double-own.
- `calc_result`: unique scope/fingerprint и append-only; result JSON и lineage hash воспроизводимы.
- `calc_current_result`: только binding с допустимым epoch; отзыв исключает current даже при concurrent publication.

## 10. Этапы разработки без переусложнения

| Этап | Содержимое | Условие завершения |
|---|---|---|
| **R0.A — Contract + Coverage** | Данное ТЗ, baseline 166 форм, 3 F02 formulas, аудит исходников | Полнота и неизобретённые зависимости подтверждены; DESIGN READY |
| **R0.B — Minimal Calculation Kernel + Fact boundary** | Typed F02 DAG, preview, additive calc 019 + fact 020 tables, pinned source→fact assembler, TEST_ONLY qualification, outbox, bounded multithreaded workers, Caffeine descriptor/DAG L1 | A02–A09 + A22 + A29 PostgreSQL + React GREEN |
| **R0.C — Incremental + Safety** | Field-level impact, per-node + execution fingerprints, stale/lease/fencing, revoke, method changes, parallel DAG branches above measured threshold | A10–A17 + A23/A24/A27 PostgreSQL concurrency GREEN |
| **R0.D — Qualification** | CI full regression, browser, source→fact+recalculation PostgreSQL E2E, measurements, technical review | A18–A25/A27/A29–A31 и F01–F16 GREEN на одном SHA, локальный TEST_ONLY пилот разрешён |
| **CALC-R1 — Cache Adapter (вне R0)** | Port `CalculationCache`, optional Redis L2 adapter, OFF by default | A25/A26 GREEN, fallback и безопасность доказаны |
| **CALC-R2 — Redis Performance Gate (вне R0)** | Benchmark Redis OFF / L1 / L1+L2 для multi-worker workloads | A28 GREEN и статистически обоснованное enable/disable решение |
| **Production S2/IAM Gate (отдельно)** | Реальный S2/IAM, FactSet authority, approved publish, масштабирование на 166 форм | Нет HOLD; финансовый выпуск отдельно |

Не создавать generic formula DSL, 30 ops, массовую поддержку 166 расчётов, Kafka/Debezium, ClickHouse, микросервисы или Redis в R0. **Многопоточный bounded execution в R0 обязателен; Redis в R0 запрещён как runtime dependency.** Caffeine L1 для versioned descriptor/DAG cache; node-value cache и CalculationCache port — R1. Никогда не кешировать admission и current status. Outbox transport может быть заменён позже без изменения доменной логики.

## 11. Неподлежащие нарушению ограничения

1. **Сохранение черновика не равно публикации FactSet** и не разрешает FIN HTAP write.
2. **Без независимого issuer/S2 нельзя обозначать результат APPROVED**, в том числе в локальном UAT; test fixture явно помечается TEST_ONLY.
3. **План, формула и входные редакции пинованы**; ни выбор последней версии формы, ни обновление активного алгоритма не должны молча менять уже исполняемый план.
4. **Нельзя выдавать статус IMPLEMENTED по наличию текста**. Принимать только PostgreSQL 16 + HTTP/React доказательства на одном SHA.
5. Работать только в `feat/fin-form-library-standalone`, не менять `main`, старые Liquibase и чужие ветки.

## 12. Definition of Done

**R0.A**: ТЗ + две baseline-матрицы в ветке, точно 166 form rows и 794 unique R6 field rows, все формы включены, отсутствующие поля/semantics видны, формулы/зависимости честно кандидатные, без изменений исполняемого кода/миграций.  
**R0.D**: F02 preview + **persisted immutable fin_fact with 3 typed F02 observations** + synthetic qualified E2E + DAG + PostgreSQL queue + bounded multithreaded workers + fencing + immutable result + Caffeine L1; тесты A01–A25, A27, A29–A31, F01–F16 (A26 — R1, A28 — R2) CI GREEN на одном SHA.  
**Production**: НЕ READY, пока нет независимого финансового S2, IAM/ABAC и реального approved FactSet admission.

### Review reference

Выполнены документационные S1 review: `docs/audits/FIN-CALC-R0-S1-ARCHITECTURE-REVIEW-2026-11-19.md` и **P0 source→fact gap review** `docs/audits/FIN-CALC-R0-S1-SOURCE-FACT-CALC-FLOW-REVIEW-2026-11-19.md` (9 P0 / 7 P1). Это **не** независимое финансовое S2 и не результаты исполняемых PostgreSQL-тестов.

### Temporary code-level implementation workpacks

- `docs/temporary/FIN-CALC-R0-CODE-LEVEL-TZ.md` — class/package contracts, PostgreSQL migrations 019/020, F02 DAG, bounded workers, outbox, tests.
- `docs/temporary/FIN-CALC-R0-SOURCE-TO-FACT-PIPELINE-TZ.md` — **P0 source→fact audit closure**: source extraction, typed observations, TEST_ONLY FactSet, epoch, TX-Q and tests F01–F16.
- `docs/temporary/FIN-CALC-R1-CACHE-REDIS-CODE-LEVEL-TZ.md` — CalculationCache interfaces, Caffeine node values, optional Redis L2, feature flags, fallback.
- `docs/temporary/FIN-CALC-R2-REDIS-BENCHMARK-CODE-LEVEL-TZ.md` — 3-mode benchmark scripts/fixtures, telemetry, correctness and Redis activation gate.

These are versioned temporary **specifications only**, not implemented code or proof of GREEN.

### Source references
- `form-library/src/main/java/kz/fin/formlib/service/FormDraftService.java`
- `form-library/src/main/java/kz/fin/formlib/service/ReportDerivedTotalService.java`
- `form-library/src/main/java/kz/fin/formlib/bootstrap/CatalogBootstrap.java`
- `form-library/src/main/resources/db/changelog/001-lib-schema.sql`, `003-lib-report-grid.sql`, `014-lib-versioned-field-contracts.sql`
- `docs/FIN-EDIT-R1-166-FORM-QUALIFICATION.md`, `docs/FIN-EDIT-R3-PERSISTENCE-QUALIFICATION.md`.
