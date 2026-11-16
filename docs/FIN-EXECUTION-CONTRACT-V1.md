# FIN-EXECUTION-CONTRACT-V1 — нормативный контракт расчётов и отчётности

**Версия:** 1.1.0 · **Дата:** 2026-11-16 · **Статус:** `DESIGN_BASELINE / IMPLEMENTATION_PENDING`.
**Branch:** `feat/fin-r0-r1a`. **Область:** P0-01…P0-07.
**Наследует:** [FIN-DIAG-60-IMPLEMENTATION-TZ](FIN-DIAG-60-IMPLEMENTATION-TZ.md), [AUTO-ORCHESTRATION](FIN-DIAG-60-AUTO-ORCHESTRATION-TZ.md).

> Нормативный приоритет: этот документ имеет приоритет над прежними несовпадающими определениями execution/report states, completeness и source identity. Непосредственные версии Java DTO/SQL подлежат сопоставлению с существующими классами перед внедрением. Документ **не утверждает**, что код или тесты реализованы.

## P0-01. Единый источник контрактов

Реестр 60 `MethodologyDefinition` — источник истины для идентификаторов, семейств, подметодов и применимости. Этот документ — источник истины для семантики выполнения. `MethodVersion` включает semantic version, fingerprint алгоритма и qualification evidence. `MethodFamily` и `MethodVariant` различаются. Все потребители (Java, React, reports, XLSX, ML) используют версии опубликованной схемы, не хранят копии формул/статусов.

Контракт меняется с migration/compatibility тестами; все изменения enum/status/schema требуют записи ADR и версии. Внешняя локализация RU/KK/EN/ZH является представлением, а не источником формул.

## P0-02. SingleExecutor и транзакционные границы

`SingleExecutor` — единый прикладной фасад `preview/submit/status/cancel`, **не** однопоточное вычисление и **не** монолитный класс со всеми формулами. Состав: `PolicyGate` → `VerifiedFactSetResolver` → `ApplicabilityResolver` → `MethodDependencyPlanner` → `RunCoordinator` → `SpecializedExecutionAdapters` → `CompositeAggregator` → `ReportSnapshotBuilder`.

Отдельные транзакции:
1. **Approval TX**: сервер проверяет права, сохраняет immutable `FactSetRevision` и outbox event `FactSetApproved(eventId, org, revisionId)` в **одной** PostgreSQL-транзакции. Никаких внешних побочных действий внутри этой TX.
2. **Enqueue TX**: consumer проверяет `eventId` и idempotency, сохраняет `AnalysisRun` и задачи DAG атомарно. Повторное событие не создаёт второй logical run.
3. **Claim TX**: worker берёт ready task с `FOR UPDATE SKIP LOCKED`, задаёт `leaseOwner, leaseUntil, attempt`. Сама тяжёлая операция **вне** долгоживущей DB-транзакции.
4. **Publish TX**: worker проверяет `runId + taskId + attempt fencing token + inputFingerprint` и уникально сохраняет результат, переводит задачи в следующее состояние, публикует outbox `TaskCompleted`. Поздний stale-worker не может затереть результат.
5. **Report TX**: создаётся новая immutable report revision по строго зафиксированным опубликованным result IDs; публикация третьей стороне только отдельной авторизованной командой.

Delivery: **at-least-once** с exactly-once *logical publication* через unique constraints, не заявлять exactly-once физическое исполнение. Retry с backoff, максимальным количеством попыток, terminal failure, cancellation, lease recovery и восстановлением после crash.

Idempotency uniqueness: `(tenant, requestKind, idempotencyKey)`, плюс проверка fingerprint; повтор с тем же ключом и **другим** payload = `409 IDEMPOTENCY_KEY_CONFLICT`. Event dedup `(tenant,eventId)`. При авторасчётах идентификатор политики/профиля и ревизии включать в logical run identity.

## P0-03. Manifest, согласованность и immutability

Минимальный `AnalysisManifest`:
- `tenantId, organizationId, legalEntityScope, factSetRevisionId, factSetFingerprint, dataTrustLevel`;
- `analysisProfileId/profileVersion, reportingPeriod, calendar, reportingBasis, accountingPolicyVersion`;
- `currency, FXRateSetVersion, unitPolicyVersion, consolidationPolicyVersion`;
- `methodologyCatalogVersion, methodVariantVersions, calculationPolicyVersion, assumptionsFingerprint`;
- `executionPlanFingerprint, executorBuildSha, outputSchemaVersion`;
- опционально для ML: `modelArtifactDigest, modelVersion, featureSetVersion, trainingDatasetManifestId, evaluationVersion, inferenceEnvironment, horizon`.

Использовать canonical serialization + documented hashing algorithm, различать content fingerprint и authorization scope. Никогда не считать fingerprint заменой server-side ABAC. `REPORT_CORE` и `REPORT_CORE_WITH_ML` одного выпуска ссылаются на **один и тот же** CORE snapshot и все идентификаторы фактов; ML, рассчитанный на r2, не может обновить CORE по r1. Новая ревизия данных во время run не переключает snapshot: старый run остаётся на r1, новый стартует с r2.

Результаты `CORE` не меняются после публикации. Поздний ML создаёт **новую immutable report revision** `parentReportId`, `coreSnapshotId` неизменен. Сырой ML/Monte Carlo output может истечь по TTL, но опубликованный report обязан содержать достаточную provenance/model metadata, чтобы честно указывать пределы воспроизводимости; нельзя обещать byte-identical rerun после удаления единственных необходимых inputs/models.

## P0-04. Отдельные ортогональные состояния и полнота

**Execution:** `QUEUED, PLANNING, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED` (+ stale marker как freshness, не терминальная успешность).
**Core readiness:** `COMPLETE, PARTIAL, BLOCKED`.
**ML readiness:** `NOT_REQUESTED, PENDING, AVAILABLE, UNAVAILABLE, FAILED`.
**Method task:** `PENDING, READY, RUNNING, COMPUTED, SKIPPED_NOT_APPLICABLE, BLOCKED_MISSING, BLOCKED_CONFLICT, DISABLED_POLICY, FAILED_RETRYABLE, FAILED_FINAL`.
**Qualification:** `DESIGN_ONLY, IMPLEMENTED_UNQUALIFIED, TESTED, METHOD_APPROVED, RELEASE_QUALIFIED, SUSPENDED`.
**Result reason:** `NOT_COMPUTABLE, INSUFFICIENT_DATA, NOT_APPLICABLE, SOURCE_CONFLICT, METHOD_NOT_QUALIFIED`.

Отдельные `requestedMode=CORE|CORE_WITH_ML` и `deliveredMode`; по умолчанию `CORE`; отсутствие ML не ухудшает CORE. Для `CORE_WITH_ML` доступны только `ADVISORY`/надлежащим образом квалифицированные модели. ML failure не меняет `coreReadiness`. UI может показывать составные подписи, но не создавать комбинаторные enum статусов.

`AnalysisProfile(id,version,scope,requiredCapabilities,conditionalCapabilities,optionalCapabilities,blockerPolicy,qualificationFloor)` определяет **обязательные функции** для `COMPLETE` с учётом отрасли и фактов. `COMPLETE` = все required applicable capabilities квалифицированы и `COMPUTED`, нет profile-critical reconciliation conflicts, trace present. `PARTIAL` = безопасный поднабор вычислен и каждый пробел маркирован. `BLOCKED` = невалиден доверенный фундамент анализа или критические ограничения профиля. `COMPREHENSIVE` не означает запуск всех 60 методов на любом предприятии. Пустой набор required capabilities не может давать `COMPLETE` без явной profile policy.

Примеры профилей: `BUSINESS_HEALTH`, `CASH_RISK`, `SALES_PROFITABILITY`, `CREDIT_READINESS`, `INVESTMENT_ANALYSIS`, `COMPREHENSIVE`. Каждый требует утверждённую спецификацию required/conditional/optional methods.

## P0-05. Точный типизированный DAG и cache

Узел: `(methodCode,variantId,methodVersion,inputFactIds+revisions,derivedMetricDeps,scope,period,basis,currency,unitPolicy,assumptionVersion,qualification)`. Рёбра: `REQUIRED, OPTIONAL, EVIDENCE_ONLY`. Планировщик отвергает циклы, ambiguous fact mapping, unknown variants и dimension violations. Месячная выручка FLOW суммируется при согласованной политике; баланс STOCK — на конец периода; ratio нельзя суммировать по месяцам.

`nodeFingerprint` включает реальные версии **всех** зависимостей (не только верхнеуровневый `FactSetRevision`), классификатор, FX/accounting/consolidation/method policies. Derived output reusable лишь если входной content fingerprint/semantic context одинаков, квалификация не отозвана, актуальны лицензии и права доступа. Кэш всегда разделён по tenant, encrypted at rest where required и авторизован при чтении; scope/rule changes invalidate/re-authorize. При изменении одного факта повторять только transitive downstream; `SOURCE_CONFLICT` блокирует только зависимые ветки; `PARTIAL` явно виден в отчёте.

## P0-06. Данные и TTL

`DataRetentionClass` и серверная policy:
- `INCOMPLETE_UPLOAD`: default 7 дней;
- `TEMP_SIMULATION`, `TEMP_FEATURE_SET`, `ANALYSIS_SCRATCH`: default 30 дней от определённого `createdAt`/completion event;
- `TEMP_REPORT_EXPORT`: 7–30 дней по policy;
- `APPROVED_FACTS`, `APPROVED_SOURCE`, `IMMUTABLE_REPORT`, `METHOD_EVIDENCE`: **не** удалять по временному 30-дневному TTL, хранить согласно юридической/договорной retention policy.

`expiresAt` вычисляет сервер; `legalHold`/published evidence references блокируют удаление до завершения retention review. При долгом ML run TTL не истекает до окончания законного активного lease: cleanup работает по state/pin refs. Удаление RustFS/S3 и PostgreSQL **не является атомарным**: durable deletion state machine `ELIGIBLE → MARKED → OBJECT_DELETED → METADATA_DELETED → DONE` с retry, audit, идемпотентностью. Backup expiry отдельно. Для удаления организации — cascading purge workflow и модель зависимости ML/training; удалённые данные не должны оставаться в accessible cache/derived outputs.

## P0-07. Family / variant / algorithm acceptance

60 верхнеуровневых `MethodologyFamily` — **целевой каталог**, но не 60 равнозначных единичных формул. Например `FIN-04` = типизированные коэффициенты; `INV-01` = NPV и XNPV; `INV-02` = IRR и MIRR; `CON-04` = композиция ABC и XYZ; `ADV-01` = конкретные forecast baselines/ML models. Каждому variant: отдельные `inputPolicy`, `algorithmVersion`, независимые goldens, negative/edge cases, `qualificationEvidence`, owner/reviewer, status. Семейство `RELEASE_QUALIFIED` только если **все обязательные для семейства утверждённые variants** квалифицированы; разрешённый subset при неполном семействе показывать как partial и явно называть.

При регистрации нового variant нельзя автоматически повышать family readiness. Формы/EX-листы не считаются «покрытыми» из-за наличия одного метода; `Form→Fact→Variant` map подтверждается отдельно.

## Acceptance / связывание с mock fixtures

[FIN-DIAG-60-P0-ACCEPTANCE.md](FIN-DIAG-60-P0-ACCEPTANCE.md) и [p0-golden-cases-v1.json](../backend/src/test/resources/fin/method60/p0-golden-cases-v1.json) определяют независимые синтетические контракты для P0. Они представляют **ожидаемую логику**, а не результаты выполненных тестов. Требуемые TEST suites: JUnit5+Testcontainers PostgreSQL (outbox, lease/fencing, TTl, tenant), Vitest/Playwright (CORE/ML reports), deterministic golden (economic calculations). До GREEN на одном SHA плюс independent financial/S2 review: `SPECIFICATION_DEFINED / IMPLEMENTATION_HOLD`.
