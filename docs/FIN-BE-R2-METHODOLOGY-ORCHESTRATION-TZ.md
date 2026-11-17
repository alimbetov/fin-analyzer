# FIN-BE-R2 — Methodology Orchestration: согласованное техническое задание v2.0

**Приоритет:** P0. **Статус документа:** TECHNICALLY RECONCILED / READY FOR IMPLEMENTATION / NOT ACCEPTED.  
**Репозиторий:** alimbetov/fin-analyzer. **Единственная рабочая ветка:** feat/fin-r0-r1a.  
**Кодовая база сверки:** 8fb4b4d8ffe5a4233ec8034ee8a428f2986cac2a (17 ноября 2026).  
**Ответственность Codex:** Principal Architect, Java/Spring Engineer, PostgreSQL Concurrency Engineer, Financial Methodology Engineer, Security Engineer, QA Automation Architect; независимый S2 reviewer должен быть отдельным участником.  
**Документы-соседи:** FIN-BE-R1-X-FOUR-LANGUAGE-EXPORT-TZ.md, FIN-BE-R1-TECHNICAL-AUDIT-2026-11-16.md, FIN-EXEC-R2-R3-TECH-REVIEW.md, FIN-DIAG-60-IMPLEMENTATION-TZ.md, методический каталог methodology-catalog-r1.json.

## 0. Управление изменениями и неизменные ограничения

Работать **только** в существующей ветке feat/fin-r0-r1a. Не создавать ветки, не делать merge/rebase/push в main, не переписывать прошлые Flyway-миграции, не редактировать успешные тесты ради GREEN. При параллельных коммитах перепроверять HEAD и сохранять изменения других разработчиков; не использовать force. Каждый этап выполнять на базе реально существующих компонентов и сопровождать unit + PostgreSQL Testcontainers + security + CI evidence. Изменение исторических DB constraints разрешается исключительно новой forward-only миграцией с доказанным clean-install/upgrade; предпочтительны добавочные таблицы и поля.

Весь R2 — **backend only**: Java 21, Spring Boot, PostgreSQL 16, Flyway, JUnit 5, Testcontainers. Не изменять React, ExcelJS, Flutter, Keycloak, IAM, каталог 166 форм, клиентские загрузки и отчётные renderers R1 без доказанного дефекта совместимости. Не строить второй executor, второй publication outbox или самостоятельную формульную подсистему. Использовать существующие CalculationDag, AutoIndicatorEngine, TrustedAutoReportService и серверные MethodTaskAdapter.

Сохранять фин. точность через BigDecimal и канонические десятичные строки; double/float, неявная конвертация валют, округление, подстановка нуля вместо отсутствующего факта и вычисление сомнительных показателей запрещены. Оригинальные fact assertions, публикации, ревизии и CORE не должны изменяться повторной обработкой событий.

Никогда не считать:
- catalog_xlsx_slot.latest_revision / CONFIRMED_UNQUALIFIED_DRAFT = approved FactSet;
- наличие формы или metricCode = разрешение методического расчёта;
- qualified исходную метрику = qualified семействo методик;
- autoEligible=true / Spring Bean / feature flag = ExecutionPermit;
- S2 review metadata = разрешение на выполнение ACTUAL;
- выполненный тест SYNTHETIC = финансовую квалификацию ACTUAL;
- количество 60 семейств в реестре = 60 исполняемых методов.

**Отдельные решения:** ENGINEERING_ACCEPTED только после технических доказательств, METHOD_S2_HOLD до независимой методической квалификации, PRODUCTION_EXECUTION_HOLD до допуска эксплуатации. Нельзя подменять один статус другим.

## 1. Цель, результат и границы доверия

После **committed публикации** fin_fact.fact_set_publication, транзакционный event fin_exec.fact_publication_outbox активирует один детерминированный план допустимых методик для опубликованной пары organization/entity/scope + версии FactSet. Applicability определяет, что выполнить, что пропустить и что блокировать; planner строит versioned DAG; SingleExecutor исполняет только разрешённые задачи; CompositeCoreReportPublisher сохраняет неизменяемый CORE с точным provenance и ограничениями.

Сквозной путь:
1. Проверенная публикация FactSet с хешем и tenant context.
2. Снимок исходных метрик и методически релевантных характеристик; сверка источника, unit, periods и dataSpace.
3. Серверный versioned registry и безпобочный PlanPreview.
4. Выпуск сохранённого immutable analysis manifest / applicability decisions / typed DAG.
5. Delivery из уже существующего outbox, короткий транзакционный claim по PostgreSQL, выполнение без удержания DB transaction, fencing + retry.
6. Проверка результата, immutable task provenance и сборка CORE по квалифицированному profile.
7. Read-only API статуса, матрица тестов и машинные evidence.

**Входной источник:** только опубликованный fin_fact.fact_set_publication с записью fin_exec.fact_publication_outbox и совпадающими organizationId, economicEntityId, businessScopeId, factSetRevisionId, snapshotHash, reportingBasis, dataSpace. Исходный source revision, утверждение и доказательства проверяет сервер; JWT/UI не может объявить источник APPROVED. Проверку публикации не переносить в frontend и не заменить наличием UUID.

**DataSpace:** ACTUAL и SYNTHETIC никогда не смешиваются. SYNTHETIC — отдельный режим пилотных испытаний, не источник публичных финансовых выводов. Малейший конфликт source hash, ревизии, scope или политики — fail closed.

**Явно вне R2:** массовое утверждение 60 семейств, AI/ML, прямой клиентский запуск произвольного Java/SQL/JSON, новый IAM/Keycloak, финансовое консультирование с юридическими гарантиями, отчёт COMPLETE до аттестации profile, React dashboard, новый upload/approval workflow, кросс-валютная консолидация без утверждённого FX.

## 2. Подтверждённые компоненты и расхождения с целевой системой

| Компонент | Есть в исходной ветке | Чего ещё нет / важная граница |
|---|---|---|
| FactSetPublicationService + fin_exec.fact_publication_outbox | Опубликованный source и outbox в одной транзакции, immutable event | Draft XLSX сам по себе никогда не публикует финансовые факты |
| SingleExecutorScheduler | Ручной tick, scheduled polling default OFF, SKIP LOCKED, fencing, retry | План на autoEligible, без variant-policy registry и доказуемого execution permit; lease фиксирован 60 секунд |
| MethodTaskAdapter | methodCode, dependsOn, autoEligible, applicable, execute(TrustedRevision) | Нет обязательного versioned descriptor, структурированного ResultEnvelope и reason-coded applicability |
| ExecutionContracts.AnalysisManifest | versioned Java record с profile, catalog, accounting/unit/assumptions, buildSha | Нужны канонический хеш, подтверждённое соответствие persisted schema и scope/selector binding |
| fin_exec.analysis_run | event_id UNIQUE, один event -> один run | Не способен сам по себе порождать независимые run на новые plan/policy versions одного event |
| fin_exec.analysis_task | UNIQUE(run_id, method_code), попытки и fencing | method variant/version и result schema не прикреплены к задаче как обязательный SQL-контракт |
| fin_exec.analysis_dependency | task_id -> prerequisite_task_id | Нет kind REQUIRED/OPTIONAL/EVIDENCE_ONLY, нет правил propagation |
| fin_exec.outbox_delivery | event_id PRIMARY KEY, привязка к одному analysis_run | Нельзя повторно считать его отдельным журналом versioned re-evaluation |
| CompositeCoreReportPublisher | Immutable JSONB CORE_PILOT_V1, проверка source hash, BLOCKED/PARTIAL | В SQL core_readiness допускает **только** PARTIAL и BLOCKED; COMPLETE без отдельной миграции запрещён |
| CatA31QualificationInspector / CatA31MethodReviewRegistry | Факт-публикация, S2-решения, capability inspection | permitsExecution() возвращает false — R2 не должен выдавать ACTUAL permit без нового проверенного authority contract |
| methodology-catalog-r1.json | 60 семейств, releaseGate HOLD, releaseQualified false | Не регистр исполняемых алгоритмов, нельзя массово снимать HOLD |
| R1 XLSX/HTML/PDF snapshot | Экспорт источника по exact revision, 4 display locale | Не является разрешением на исполнение методик или на статус ACTUAL |

Техническая адаптация сохраняет существующие SQL status values и JSON CORE_PILOT_V1 до доказанного versioned upgrade. Нельзя молча переименовать persisted states или изменить semantics старых запусков.

## 3. Этапы и обязательные gates

| Этап | Содержание | Выход и условие перехода |
|---|---|---|
| R2.0 Entry / S0 | Сверка фактического кода, schema и контрактов, интерфейсы ADR | Decision log, baseline SHA, status map, RED tests; не предполагается эксплуатационная активация |
| R2.A / S1 | MethodVariantRegistry + Applicability + ExecutionPermit | versioned schema, deterministic decisions, no unauthorized ACTUAL |
| R2.B / S2 | PlanPreview + deterministic dependency DAG + idempotency | чистый preview; persisted plan и SQL uniqueness проверены в Testcontainers |
| R2.C / S3 | lease/fencing/recovery + корректное завершение задач | два worker, crash recovery, retry/DLQ и guarded publication |
| R2.D / S4 | первичные варианты методик, точность и golden finance cases | каждая версия доказуема отдельно; qualified input не promoted family |
| R2.E / S5 | authorized read-only planning/status/CORE API, audit/telemetry | изоляция org/field/period; отсутствуют произвольные client-controlled permits |
| R2.F / S6 | functional integrity, release evidence, independent S2 review | все утверждённые P0 сценарии GREEN на одном SHA; production gate остаётся отдельным |

Не переходить к следующему этапу при открытом блокирующем P0 предыдущего. Не разрешать ACTUAL в production из инженерного GREEN.

## 4. R2.0 — Readiness и согласование первичных инвариантов

### 4.1. Технические проверки

- Прочитать на текущем HEAD SingleExecutorScheduler, ExecutionContracts, MethodTaskAdapter, CompositeCoreReportPublisher, CatA31QualificationInspector, CatA32AuthorizedInspectionService, FactSetPublicationService, schema fin_exec и все связанные тесты.
- Зафиксировать исторические Flyway checksums; выполнить fresh schema и миграцию существующей схемы.
- Отделить базовый profile CORE_PILOT_V1 от будущего profile с требованиями к бизнес-методам.
- Проверить user roles: FIN_READ для чтения, FIN_ANALYZE для серверно разрешённых аналитических операций. Отдельное разрешение reviewer S2 и роль, проверяющая подпись/решение, не должны подменяться FIN_ANALYZE.
- Создать матрицу договорённостей: persisted state ↔ Java enum ↔ API status; status family qualification ↔ metric qualification ↔ method variant approval.
- Зафиксировать семантику R2 для EXACT published source; отсутствие источника возвращает NOT_FOUND/REJECTED, не fallback к catalog latest.

### 4.2. Результаты

Обязательные артефакты: ADR-01 Trusted Fact Publication Boundary, ADR-02 Versioned Plan Identity, ADR-03 Permit Authority, ADR-04 Core Readiness Compatibility; тестовая матрица источников и миграций. Без этих четырёх решений написание новых SQL state transitions заблокировано.

Gate S0: доказано, что raw XLSX/upload/CONFIRMED_UNQUALIFIED_DRAFT не инициируют ни ACTUAL planning, ни task compute.

## 5. R2.A — Server-owned MethodVariantRegistry и Applicability

### 5.1. Модель MethodVariantDescriptor

Реализовать immutable, versioned descriptor, не исполняемый клиентский JSON. Поля:
- familyCode, variantCode (вместе стабильный methodCode), variantVersion, descriptorSchemaVersion;
- adapterBindingId + exact supported implementationVersion/build SHA; registry version/fingerprint;
- exact input metricCode+metricVersion+metricQualification, source form/version/field provenance, selection policy (cardinality, order, population and reconciliation);
- input role REQUIRED, OPTIONAL, EVIDENCE_ONLY; quantitative measureKind STOCK/FLOW/RATIO, numerator/denominator semantics, units, currency, scale, timeKind, period grain/coverage, calendar/fiscal policy, reporting basis, entity/scope, dataSpace;
- minHistory, maxSourceAge, permitted source states, exception and zero-denominator policy, rounding/MathContext;
- source population constraints (e.g. SKU refunds and cancellations), required methodology S2 review key/evidence/version, revocation policy and server feature flag;
- dependencies with kind and exact version constraints, capability/profile requirements, assumptionsVersion/fingerprint, executionCostLimit and resource class.

В JSON допускаются **данные декларации**; нельзя принимать произвольный SQL, Java class name, compiled expressions, free-form code или незарегистрированную формулу из HTTP. Adapter binding — allowlisted в коде, отдельное разрешение на изменение registry deployment/reviewed release. Схема валидируется при старте; при некорректном реестре fail fast, не silently skip.

Registry должен сосуществовать с methodology-catalog-r1.json: 60 family metadata не меняются на releaseQualified=true, пока отдельно не одобрены семейства. Variant-level implementation status и qualification status — две разные оси.

### 5.2. ApplicabilityDecision

Отдельный immutable результат с полями decision, reasonCodes[], methodCode, variantVersion, sourcePublicationId, sourceFingerprint, matchedRequiredFacts[], missingFacts[], blockedConflicts[], optionalWarnings[], reviewEvidenceId, reviewEvidenceHash, dependencyVersionMap, policyVersion и evaluatedAt (время только для evidence, не в детерминированном plan hash).

Статусы:
- ELIGIBLE — проходят серверные source, semantic, registry и permit rules именно для запрашиваемого execution mode.
- NOT_APPLICABLE — метод экономически неприменим для данного типа/профиля; не ошибка.
- BLOCKED_MISSING — отсутствует обязательный подтверждённый input.
- BLOCKED_CONFLICT — конфликт валюты, периода, basis, population, количества наблюдений, duplicate/overlap, sign/denominator.
- DISABLED_POLICY — выключено feature/profile/risk policy.
- UNQUALIFIED — источник или вариант пока без необходимого методического разрешения, либо S2 review revoked/missing.

**Decision vs task:** все решения должны сохраняться в плане, даже если task не создаётся. UNQUALIFIED не разрешено молча переводить в COMPUTED; SQL task statuses не расширять без forward-only migration.

### 5.3. ExecutionPermit: независимый fail-closed gate

Разработать серверный ExecutionPermitService с логическим AND:
valid published FactSet + tenant/scope + exact source hash + applicable versioned descriptor + explicitly permitted profile + server config + method S2 evidence with validity/revocation + reviewed metric inputs + selected dataSpace + policy version.

**Критично:** существующие CatA31QualificationInspector.permitsExecution() и CatA31MethodReviewRegistry.Result.permitsExecution() возвращают false. Документ **не** разрешает менять их на true ради GREEN. R2 должен согласовать отдельный проверяемый контракт permit со всеми полномочиями, review signatures, revocation и capability checks; пока он не принят независимым S2, ACTUAL execution и production остаются HOLD. Для разработки разрешён только ограниченный SYNTHETIC/test profile с явным режимом и немаркетинговым результатом.

Не доверять полям approved, autoEligible, requirements, sourceRevision, formCode, reason, actor из JSON пользователя как основанию для permit. FIN_ANALYZE не является S2-ролью. Отзыв разрешения должен блокировать новые claims и фиксироваться в evidence; исторический результат остаётся неизменяемым с прежним состоянием, не перезаписывается.

### 5.4. Gate S1 / тесты

Проверить отсутствие family/variant/version, повтор ключа, version drift, schema cycle, неверный field/type/unit/period, противоречащие constraints, отсутствующий S2 review, revoked review, expired review, чужой tenant, bad dataSpace, source/metric mismatch, feature flag OFF и отсутствие нового EXECUTE permit. Позитивный SYNTHETIC profile не может быть автоматически перенесён в ACTUAL.

## 6. R2.B — PlanPreview, DAG и воспроизводимая идентичность

### 6.1. Два режима

**PlanPreviewService** — read-only, из одной согласованной публикации строит применимость и versioned DAG, ничего не пишет, не выдаёт lease, не доставляет outbox и не делает вычислений. Preview показывает блокирующие и предупреждающие причины и expectedTaskCount. Отдельные источник/методические требования никогда не извлекаются из client-supplied SQL/JSON.

**PlanCommitter** — только серверный обработчик committed event или доверенного санкционированного re-evaluation request. Атомарно создаёт immutable план/версию, decisions/typed dependencies, задачи и delivery receipt. Outbox event остаётся неизменным.

### 6.2. Plan keys and hashes

CanonicalPlanKey должен включать:
- organizationId + economicEntityId + businessScopeId;
- exact factSetRevisionId + factSetPublication snapshot hash + dataSpace + basis;
- analysisProfileCode/version, methodologyCatalogVersion, registryFingerprint;
- отсортированный набор methodCode + variantVersion + adapter binding fingerprint;
- accounting/unit/calendar/FX policies, assumptionsFingerprint, build SHA/released calculation implementation hash;
- deterministic graph fingerprint, schemaVersion.

Сериализация: versioned canonical UTF-8, длины/тип/порядок полей фиксированы, null и пустая строка не совпадают, массивы и edges сортируются детерминированно. SHA-256 только от канонического байтового представления, **не** от toString() или произвольного Jackson Map order. Временные метки, случайные UUID, polling order и конкретный worker не входят в plan hash.

Доказать два свойства: равные входы → один planFingerprint; изменение любого значимого координата/версии/хеша → новый fingerprint. Это логическая идемпотентность, а не обещание идентичных байтов DOC/PDF.

### 6.3. Совместимость существующей SQL-схемы — ОБЯЗАТЕЛЬНО

Сейчас analysis_run.event_id UNIQUE, outbox_delivery.event_id PRIMARY KEY, analysis_task UNIQUE(run_id,method_code). Поэтому существующий SQL реально гарантирует **один исходный run на публикацию**, но **не** несколько независимых версий плана для одного outbox event.

Выполнение разделить:
- **R2.B1** (обязательный): один idempotent initial plan на event, immutable plan manifest с version/fingerprint; повторная доставка event → тот же run, никакого дубля.
- **R2.B2** (обязательный для заявления «re-evaluation по версии политики»): новая source publication создаёт новый event. Для повторного анализа **того же** event по новой версии методики нужен отдельный server-owned ReEvaluationRequest, PlanRequestId и logicalRunKey. Спроектировать forward-only SQL migration с тестом upgrade: либо новая таблица versioned runs и связь с existing analysis_run, либо controlled снятие UNIQUE(event_id) при введении unique(logical_run_key) и выделении delivery(first-run) vs request, но **не** писать один event_id с новым run в старый outbox_delivery. ADR обязан выбрать ровно один вариант с сохранением старых SQL rows, FK, истории и rollback-safe dual-read semantics.
- Идемпотентность всегда проверять PostgreSQL UNIQUE+transaction, а не только Java locks.

Новый manifest хранит provenance selected publications, exact input selection hashes, descriptor/policy versions, ordered decisions, snapshot fingerprint, build identity, graph hash и immutable plan structure.

### 6.4. DAG и типизированные зависимости

При построении: normalise canonical node ids, validate referenced methods/versions, reject self-edge, unknown target, circular graph (детерминированная topological sort). Стратегия выполнения — stable ordering по methodCode + variantVersion, а не HashMap iteration order.

Семантика edges:
- REQUIRED — если prerequisite не COMPUTED с допустимым result, dependent не READY; failure, blocked source, incompatible version блокируют **только downstream required path**; причина и исходная цепочка фиксируются.
- OPTIONAL — отсутствие/FAILED prerequisite не блокирует dependent, но появляется warning + missing optional evidence; dependent выполняется только если variant реально допускает отсутствие optional input.
- EVIDENCE_ONLY — связь provenance/audit без количественного dependency; отсутствие обязательного для допуска **review evidence** проверяется отдельно в ExecutionPermit, не маскируется как optional calculation input.

Текущая fin_exec.analysis_dependency не хранит kind. В новой migration надо добавить type/kind (для исторических связей по умолчанию REQUIRED после аудита данных) или versioned typed edge table. Нельзя просто изменить Java enum и предположить, что SQL поддерживает OPTIONAL. Поведенческие SQL tests обязательны.

При ошибке REQUIRED не блокировать независимые задачи, при пропущенной OPTIONAL не создавать ложный COMPLETED input. Циклы — reject план до записи, без partial rows.

### 6.5. Re-evaluation и targeted recompute

Новая FactSet publication с изменением только одного input требует сравнения **канонических источник-зависимых fingerprints**. Пересчитывать только affected node + required/optional descendants, если версии остальных inputs, rules, implementation/policies действительно совпадают. Переиспользование результата разрешено только при доказанной идентичности semantic key и immutable original result: не копировать «подходящий старый JSON» без полной проверки provenance. При сомнении пересчитать, не выполнять небезопасную cache reuse.

Новая методика/assumptionsVersion/profile меняет план, старый run и CORE immutable. Никаких silent re-plan старого delivered event.

### 6.6. Gate S2

PostgreSQL tests: duplicated event, two schedulers, same policy same fingerprint, changed policy/new request, failed plan atomic rollback, unknown edge/cycle, REQUIRED failure propagation, OPTIONAL and EVIDENCE_ONLY semantics, deterministic ordering и controlled migration from old data. Проверить 1 event → не более одного initial run, а несколько versioned runs допускаются **только** по принятому R2.B2-contract.

## 7. R2.C — Crash-safe SingleExecutor и CORE публикация

### 7.1. Claims and fencing

Использовать существующий SingleExecutorScheduler; доработать его, а не запускать второй dispatcher. Short PostgreSQL TX: SELECT .. FOR UPDATE SKIP LOCKED → conditional UPDATE task_status/fencing_token/lease_until. Не держать TX во время adapter.execute, HTTP/IO/CPU loops.

Вычисление идентифицируется (taskId, runId, attempt, fencingToken, sourceHash, methodVariantVersion). Результат принимается только при актуальном token, RUNNING, unexpired lease, отсутствии source/policy drift и соответствии result schema. Повторный результат stale worker rejected and audited. Если попытка устарела, она не может публиковать новые финансовые данные.

### 7.2. Lease, heartbeat, timeout

В действующем коде lease 60 секунд без универсального heartbeat. В R2 ввести:
- bounded configurable lease TTL и heartbeat interval с монотонной логикой обновления в коротком TX;
- продление только при совпадающем fencing token и RUNNING;
- max execution wall time для каждого resource class;
- crash recovery после истечения lease, increment fencing;
- остановку/изоляцию долгих адаптеров; если безопасный cancellation не поддержан — запрет heavyweight variants до отдельного дизайна.

**Важно:** изменение длины lease без heartbeat не считается устранением дефекта. Продление должно выполняться независимо от блокирующей вычислительной операции; обратный путь при потере DB соединения fail closed.

### 7.3. Retry/DLQ и распространение ошибок

Классифицировать причины:
- retryable transient: DB retry, bounded upstream unavailability, short outage;
- terminal: invalid data/period/unit/basis, unqualified method, revoked permit, unsupported variant, invalid result schema, poisoned input;
- stale claim/lease expired: neither success nor ordinary business failure by stale worker;
- planning poison: отдельный fin_exec.outbox_planning_failure, не изменять immutable outbox.

Retry с экспоненциальным backoff + bounded jitter, maxAttempts, nextRetryAt; после лимита FAILED_FINAL/dead_letter с reasonCode без финансовых значений в логах. Failure родителя блокирует REQUIRED descendants, но не OPTIONAL и независимые.

Тест: crash до/после claim, после DB commit и до ACK, network partition, duplicate event, two workers, stale token, repeated completion, retry exhaustion. Ни одна из этих гонок не создаёт 2 разных terminal successful results одного logical task.

### 7.4. ResultEnvelope и точность

Адаптер сейчас возвращает String JSON. Переходить к versioned ResultEnvelope (можно через временный validated wrapper, не ломая существующие пилоты):
- runId, taskId, methodCode, variantVersion, descriptorFingerprint, implementationBuildSha;
- sourcePublicationId, sourceFingerprint, exact inputReferences, inputFingerprint;
- outputMetrics с metricCode, status CALCULATED/NOT_COMPUTABLE/ESTIMATED (где политика позволяет), plain decimal text, unit, currency, timeKind, period, dimensions;
- diagnostic reasons, assumptions, limitations, qualification status и resultSchemaVersion.

Проверять object schema, отсутствие NaN/Infinity, типы, precision/scale, permitted units, denominator, expected cardinality и исходные SHA. Adapter-supplied qualification считается **не доверенным authority**: только server policy/review ledger.

Для adapter, публикующего дочерний fin_calc.report_run до завершения task, отдельно разобрать crash boundary: повторное выполнение обязано либо вернуть existing immutable result по idempotent key, либо безопасно завершиться без создания семантического дубля.

### 7.5. CORE readiness and ML isolation

CompositeCoreReportPublisher продолжает принимать только terminal analysis run и проверять matching tenant + published FactSet hash, задачи и method provenance. Имеющаяся таблица CORE_PILOT_V1 допускает лишь PARTIAL и BLOCKED. **Не писать COMPLETE**, пока не утверждены full profile readiness rules, server-side completeness, review permit и forward-only миграция schemaVersion/SQL constraints. FAILED или ноль COMPUTED → BLOCKED. Необязательный расчёт FAILED → PARTIAL или BLOCKED в соответствии с обязательными capability profile, не скрывать отсутствие фактов. Старые CORE immutable; новый метод означает новый run/CORE version.

ML OFF; недоступность ML не блокирует CORE. CORE_WITH_ML в R2 не разрешать как внешнюю выдачу, пока отдельная ML qualification отсутствует.

**Gate S3:** подтверждены one logical task result, stale fencing reject, heartbeat and recovery, idempotent downstream publication, complete/partial/blocked safety. Все статусные правила покрыты SQL + Java tests, включая upgrade historical runs.

## 8. R2.D — Методики первого ограниченного набора

Подключать **реальные существующие variant codes**, не заменять их на предположительные имена. Подтверждённый кодовый набор и требования:

| Очерёдность | Exact variantCode | Существующий adapter/зависимость | Методический gate |
|---|---|---|---|
| System | SYS/VERIFY_PUBLISHED_FACT_SET | PublishedFactIntegrityAdapter | Всегда REQUIRED trusted publication, это не бизнес-метод |
| Pilot | SYS/DIAGNOSTIC_PILOT | SyntheticBusinessDiagnosticAdapter | Только SYNTHETIC, никаких ACTUAL выводов |
| 1 | FIN-01/REV_NET_MOM | QualifiedRevenueHorizontalAdapter | 2 сравнимых месяца, QUALIFIED REV_NET FLOW, одна валюта/basis; нулевая база по правилу |
| 2 | FIN-02/REV_CATEGORY_SHARE | QualifiedRevenueStructureAdapter | согласованная категория/популяция, сумма=REV_NET, одни период/валюта/scope |
| 3 | FIN-04/CURRENT_RATIO | QualifiedCurrentRatioAdapter; fin.methods.fin04.enabled=false по умолчанию | F01 CURRENT_ASSETS и CURRENT_LIABILITIES STOCK на одну дату; не TOTAL balances |
| 4 | CON-01/PARETO_SALES | QualifiedSkuParetoAdapter; fin.methods.con.enabled=false | серверно проверенный SKU journal + refunds/returns + выверка F02 net revenue |
| 5 | CON-02/ABC_SALES | QualifiedSkuAbcAdapter; тот же feature flag | та же популяция и baseline, детерминированные thresholds/ties; зафиксировать зависимость от CON-01 по версии либо документировать независимость вычислительного алгоритма |

Это **варианты**, а не квалификация семейств FIN-01, FIN-02, FIN-04, CON-01, CON-02. Даже для технически исполняемых variants ACTUAL допускается только после отдельного signed S2 methodology permit и source checks. Все прочие семейства из 60 остаются UNQUALIFIED/NOT_CONNECTED с объяснимой причиной.

Для каждого variant: exact metric aliases, input source types, semantic equations, stock/flow intervals, units and scaling, currency, population reconciliation, null/zero, negatives, period boundaries, rounding, reviewer approval, wrong-tenant rejection, independent golden fixtures. Не выводить новый target formula из собственного test helper.

### Финансовые golden cases

- REV_NET month-over-month: отличать ноль прошлой базы, отрицательную выручку, возвращённые товары; не считать процент от некорректного прошлого периода.
- Revenue share: сумма категорий и один F02 REV_NET, проверки duplicate/coverage/negative shares; total нет без подтверждённой популяции.
- Current ratio: два текущих баланса на одну as-of дату; current liabilities ≤0 требует строго описанного решения, результат нельзя выводить как нормальное отношение без методики.
- Pareto/ABC: refunds, cancellations, returns, equal revenue ties, negative lines, discount/net price; идентичный ordered source population.
- Multi-month: leap year, timezone period close, overlapping and missing months.
- Числа: BigDecimal с типизированными scaling policies; независимые ожидания по plain-string output.

**Gate S4:** real PostgreSQL publication → selected input assertions → one variant result с точными values, source lineage и контролируемым BLOCKED при плохих данных. Проход тестов на SYNTHETIC не меняет gate ACTUAL.

## 9. R2.E — Security, API, observability

### 9.1. Авторизация и API

R2 не создаёт нового Keycloak. Использовать существующий FinanceAccessPolicy, tenant-aware JWT и trusted actor, не принимать roles/org/approval/requiredGroups/permit из тела запроса.

Предлагаемые read-only endpoints (не считать реализованными до кода и HTTP E2E):
- GET /api/v1/financial/organizations/{organizationId}/executions/plan-preview?factSetRevisionId=...&profileCode=... : авторизованный FIN_ANALYZE; source/revision scope и profile server-owned.
- GET /api/v1/financial/organizations/{organizationId}/executions/runs/{runId} : FIN_READ + field/period/scope ABAC.
- GET /api/v1/financial/organizations/{organizationId}/executions/runs/{runId}/tasks : FIN_READ + origin context checks.
- GET /api/v1/financial/organizations/{organizationId}/executions/reports/{reportId} : FIN_READ + verified immutable CORE.

Запрос с UUID другого tenant должен отвечать недекларирующим отсутствием/запретом, не раскрывая чужой runId, source hash или число задач. Ограничить количество metric-level полей, наличие raw source facts/PII, периоды и доступ к оригиналам документов политикой ABAC. FIN_READ org-only недостаточно для внешней публикации sensitive CORE; если field/period ABAC отсутствует, оставлять внешние endpoints HOLD.

Не добавлять POST execute для произвольного methodCode. Контролируемые administrative re-evaluation requests — отдельный server-owned endpoint/command после отдельного IAM/ADR, не из R2 preview.

### 9.2. Аудит и ошибки

Persist immutable provenance: actor, server permit/review evidence key, source publication, registry/descriptor fingerprint, profile/policies/buildSha, DAG, attempts/fencing, reason-coded decisions, produced result/schema/hash, timestamp. Operational mutable task rows отличаются от immutable audit ledger; не переписывать старые trace/data.

Коды причин обязательны, минимум:
SOURCE_NOT_PUBLISHED, SOURCE_HASH_CHANGED, SOURCE_SCOPE_MISMATCH, METHOD_UNKNOWN, METHOD_VERSION_UNSUPPORTED, METHOD_S2_HOLD, METHOD_REVIEW_REVOKED, INPUT_REQUIRED_MISSING, INPUT_CONFLICT, PERIOD_INCOMPATIBLE, CURRENCY_INCOMPATIBLE, UNIT_INCOMPATIBLE, REQUIRED_DEPENDENCY_FAILED, OPTIONAL_INPUT_MISSING, DAG_CYCLE, EXECUTION_DISABLED, LEASE_STALE, ATTEMPTS_EXHAUSTED, RESULT_SCHEMA_INVALID, PLAN_KEY_CONFLICT, CORE_PROFILE_INCOMPLETE.

Причина не должна содержать сами суммы, персональные данные, JWT или SQL text. Metrics: planningDuration, queueDelay, claimAttempts, retryRate, staleTokenRejects, DLQ backlog, runLatency p95/p99, sourceIntegrityRejects, version drift, CORE readiness; dimensional labels должны иметь ограниченную cardinality (не помещать UUID пользователей в metric labels).

### 9.3. Operations and resource limits

Конфигурация: fin.execution.enabled=false default; отдельные feature gates FIN04 и CON default false. До S2/operations permit на ACTUAL не включать автоматическое исполнение. Explicit manual tick допустим только на тестовых опубликованных источниках и в контролируемом staging. Для resource-heavy Monte Carlo/ML нет допуска R2.

Fail-closed при missing policy, suspect source, broken review store, disabled feature, exceeded budgets. Задать измеряемые лимиты taskCount, DAG nodes/edges, input rows, output bytes, active workers, execution wall-time и retries; профиль лимитов хранить versioned, проверить в CI, не выдумывать абсолютные production SLA без benchmark.

**Gate S5:** tenant/field/period access, no client-forged permit, nonmutating GET, immutable evidence, observable retry and safe restart.

## 10. PostgreSQL migration policy и согласование схемы

Нельзя менять V20261116_01..._13 или V1..V11 задним числом. Создавать новые последовательные Flyway migrations после проверки фактического HEAD.

Минимальная предлагаемая модель:
- versioned server-owned method_variant_registry (если persistence необходим), immutable review-scoped descriptor versions; иначе signed classpath registry + hash, **без двух source-of-truth**;
- fin_exec.analysis_plan_manifest с logicalRunKey, source publication/context, profile/registry/policies versions, planFingerprint, canonical body, immutable status; unique logical key;
- fin_exec.analysis_plan_decision (включая blocked/not applicable, не только запущенные задачи), unique(planId,methodCode,variantVersion);
- fin_exec.analysis_dependency.kind или новая typed edge table; old rows backfill REQUIRED с проверкой;
- analysis_task pinned variantVersion, adapter/result schema/fingerprint, permits/review evidence and bounded execution budget; сохранить исторические task rows;
- capability/profile readiness ledger в явной версии, без раннего COMPLETE в старой core_report_snapshot;
- controlled heartbeat and stale/retry DLQ telemetry schema.

**Запрещён shortcut:** просто удалить UNIQUE(event_id) без переноса contract outbox_delivery и тестов upgrade. А также нельзя бездумно выставить DEFAULT OPTIONAL в старых edges. Рекомендуемый путь — совместимый R2.B1 + отдельный ADR/R2.B2 migration, сохраняющий старый one-event-one-initial-run ledger.

Проверить PostgreSQL constraints, FK, uniqueness, immutable triggers, indexes, lock granularity, EXPLAIN для claim, transactional outbox, SQL injection, org-scoped queries, Flyway fresh + upgrade, rollback of half-created plans и concurrent conflict. Версионировать plan schema и result schema, не использовать random UUID как смысловой dedupe key.

## 11. Acceptance Matrix BE-R2-01...36

Ниже обязательные **реальные** поведенческие проверки; положительный тест должен создавать настоящее опубликованное FactSet в PostgreSQL 16 Testcontainers. Без опубликованного source и SQL execution path не считать E2E подтверждённым.

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| BE-R2-01 | Same publication event delivered 3 times | Один initial logical run, одна запись delivery |
| BE-R2-02 | Two concurrent schedulers | Task claimed once per valid lease/fence; no duplicate logical result |
| BE-R2-03 | Crash after claim / late completion | New token wins; stale worker rejected |
| BE-R2-04 | Required predecessor fails | Только dependent required descendants BLOCKED |
| BE-R2-05 | Optional predecessor fails | Dependent proceeds when safe, warning recorded |
| BE-R2-06 | Evidence-only link absent | Provenance warning or explicit permit block, never silent numerical dependency |
| BE-R2-07 | Unknown dependency/variant version | Plan rejected atomically |
| BE-R2-08 | DAG cycle, self-edge | Deterministic reject; zero partial tasks |
| BE-R2-09 | Same source + same registry/profile/policies | Identical canonical plan hash |
| BE-R2-10 | Changed source/assumptions/methodVersion | New logical plan identity; old immutable |
| BE-R2-11 | Same event re-evaluation request | Requires R2.B2 authorized request; no duplicate delivery |
| BE-R2-12 | Stock vs flow / period mismatch | Block only affected method, no silent aggregation |
| BE-R2-13 | Currency/basis/unit mismatch | Typed BLOCKED_CONFLICT with exact reason |
| BE-R2-14 | Duplicate/out-of-period revenue/SKU | No double-counted result |
| BE-R2-15 | 60-family releaseGate HOLD | No mass method approval or execution |
| BE-R2-16 | Source upload vs confirmed draft vs publication | Only committed publication triggers orchestration |
| BE-R2-17 | Unknown/expired/revoked S2 | No ACTUAL ExecutionPermit |
| BE-R2-18 | autoEligible=true but method unqualified | ACTUAL not executable |
| BE-R2-19 | Unauthorized caller/client-forged requirements | Permission denied and no run |
| BE-R2-20 | Wrong tenant/run/revision | No cross-tenant existence leak |
| BE-R2-21 | Lost DB during heartbeat | No stale commit; future recovery |
| BE-R2-22 | Retry exhaustion | FAILED_FINAL/DLQ, bounded attempts |
| BE-R2-23 | Crash after event plan commit | Delivery replay idempotent |
| BE-R2-24 | Crash after child financial report publication | No duplicate semantic report |
| BE-R2-25 | Zero denominator/null/negative base | Deterministic typed non-computable where policy demands |
| BE-R2-26 | 38/12 precision, large signed decimals | Exact canonical comparison, no binary floating loss |
| BE-R2-27 | FIN-01 two consecutive months | Independent golden %, scope/source provenance |
| BE-R2-28 | FIN-02 categories reconciliation | Exact sum and missing-category reject |
| BE-R2-29 | FIN-04 current assets/liabilities | Wrong total assets rejected, date consistency |
| BE-R2-30 | CON-01/02 SKU refunds/returns/ties | Same approved SKU population, stable ordering |
| BE-R2-31 | CORE FAILED/zero computed | BLOCKED, never COMPLETE |
| BE-R2-32 | CORE partial missing required capability | PARTIAL/BLOCKED per profile, source preserved |
| BE-R2-33 | Repeated CORE report publish | One immutable snapshot/hash per logical run |
| BE-R2-34 | ML disabled/unavailable | CORE unaffected, ML NOT_REQUESTED |
| BE-R2-35 | Flyway clean-install and pre-R2 upgrade | No migration checksum drift, old reports readable |
| BE-R2-36 | Stop/feature flags default OFF | No scheduled task executes without explicit gate |

Дополнительно: property-based tests topology, canonical hashing, roundtrip stored decimals, planner read-only; failure injection в PostgreSQL, concurrency tests на двух реальных DB connections, timezone boundaries и replay stale tokens. Нельзя заменять эти проверки Mockito-only тестами или статическими JSON manifest.

## 12. CI / machine-readable evidence / S2

Создать FIN_BE_R2_ORCHESTRATION_ACCEPTANCE как **дополнительный** job/gate к существующему FIN Backend Domain/FIN CI, не удаляя проверки FIN-BE-R1-X5 166×4×3 и S2 security. Evidence output под backend/target/fin-evidence/fin-be-r2-orchestration-coverage.json, генерируется только исполненными тестами и сверяется независимым verifier с JUnit XML, актуальной матрицей scenarios и GitHub SHA.

Минимальные поля: schemaVersion, gitSha, stage, testCaseId, sourcePublicationId, sourceHash, planFingerprint, descriptorVersion, analysisProfileVersion, task/result status, actor/tenant (без PII), PostgreSQL evidence key, expected/actual verdict, testCount, PASS/FAIL/SKIP, failureReason, elapsed. Артефакт не должен хранить целые клиентские финансовые документы. FAIL, SKIP, MISSING, NOT_RUN, UNKNOWN — релизный HOLD.

Обязательный test stack:
- Java 21 Maven verify, PostgreSQL 16 Testcontainers, реальные миграции (fresh и upgrade), JUnit + concurrency tests;
- SingleExecutorGraphTest, ExecutionContractsTest, MethodologyCatalogContractTest, QualifiedCurrentRatioIntegrationTest, QualifiedRevenueSingleExecutorIntegrationTest, QualifiedRevenueStructureIntegrationTest, ReviewedSkuSingleExecutorIntegrationTest, FactPublicationIntegrationTest, PostgresInvariantTest;
- независимые golden input/expected fixtures, отдельная проверка CANONICAL hash/review metadata;
- полный FIN CI: backend-verification, verify, desktop-macos, acceptance-gate на **одном SHA**; каждый GREEN, при необходимости подтверждённые MacBook smoke/UAT;
- independent S2 architecture/security + separate finance S2 methodology signoff; reviewer не может быть тем же автором и автосогласование LLM не считается независимым approval.

Отчёт Codex по каждому этапу: стартовый HEAD, коммит, затронутые файлы, API/SQL contract diff, команды, реальные PASS/FAIL/SKIP, найденные и исправленные дефекты, proof links, unresolved risks. Не доказывать свою корректность только внутренним assert в том же production algorithm.

## 13. Definition of Done и release decisions

**ENGINEERING_ACCEPTED** для R2 только при выполнении S0..S6, полном acceptance matrix, SQL-совместимости, tenant/permission proof, resource/retry safety, реально сохранённом plan+result+CORE, green CI on same SHA и независимом S2 technical approval.

**METHOD_S2_HOLD** сохраняется для любой финансовой variant без отдельно подписанного точного review (form+metric+variant+version+accounting policy, valid window, revocation); статус QUALIFIED у метрики не разрешает автоматически метод. **PRODUCTION_EXECUTION_HOLD** сохраняется до независимого операционного разрешения, измеренных лимитов и настроенного безопасного enablement; fin.execution.enabled=false default остаётся.

**R2 минимально полезный доказуемый вертикальный срез:** настоящая опубликованная SYNTHETIC FactSetRevision -> scoped PlanPreview -> versioned accepted DAG -> SYS/VERIFY_PUBLISHED_FACT_SET + один допустимый пилот -> PostgreSQL task result -> immutable CORE_PILOT_V1 с PARTIAL/BLOCKED и source hash -> read-only authorized API. После квалификации отдельного варианта и соблюдения permit gates — ACTUAL вертикаль как отдельная приёмка, не результат первого среза.

## 14. Независимое техническое ревью этого ТЗ — решение по несогласованностям

| ID | Противоречие первоначального ТЗ | Корректировка v2.0 |
|---|---|---|
| S2-R2-01 P0 | S2 metadata считались достаточными для automatic ACTUAL | Явный ExecutionPermit, inspector currently always false, независимо от autoEligible |
| S2-R2-02 P0 | Обещалось много versioned runs на одном event при UNIQUE(event_id) | R2.B1 initial event и R2.B2 separate re-evaluation migration/ADR |
| S2-R2-03 P0 | Java DependencyKind не соответствовал SQL edge schema | typed edge migration и backfill historic REQUIRED |
| S2-R2-04 P0 | CORE COMPLETE обещался при SQL CHECK PARTIAL/BLOCKED | удерживать CORE_PILOT_V1, новые readiness status только versioned DB change |
| S2-R2-05 P0 | Периодические задания/adapter flags могли служить обходом квалификации | multi-factor permit + default off + no auto ACTUAL |
| S2-R2-06 P1 | Fixed lease 60s без heartbeat | heartbeat, bounded wall-time, fencing и crash-proof claim tests |
| S2-R2-07 P1 | Бизнес-метод и текущий код CON-01/PARETO расходились | использовать exact CON-01/PARETO_SALES, CON-02/ABC_SALES |
| S2-R2-08 P1 | Исполнение 60 семейств выводилось из каталога | separate family coverage/variant implementation/qualification |
| S2-R2-09 P1 | Ошибки OPTIONAL и EVIDENCE_ONLY трактовались как REQUIRED | typed semantics + separate permit evidence |
| S2-R2-10 P1 | Финансовый draft R1 мог ошибочно считаться опубликованным FactSet | trusted publication boundary, no direct trigger from export/upload |
| S2-R2-11 P1 | idempotency не учитывала дочерние fin_calc.report_run и source hash | exact downstream idempotency + crash-after-publication proof |
| S2-R2-12 P1 | Общее формулирование качества без воспроизводимых test-oracles | 36 acceptance IDs, Testcontainers, independent golden and CI evidence |

**Статус ревью:** документально согласовано с кодом на указанном baseline; **это не S2 APPROVE**, не изменение application code и не доказательство реализации R2. После параллельных коммитов пересмотреть исходный HEAD, повторить consistency check и работать в той же ветке без merge в main.
