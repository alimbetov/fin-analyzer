# FIN-BE-R2 — детализированное ТЗ: R2.A+B, R2.C+D, R2.E+F

**Редакция:** v1.0, 17 ноября 2026.  
**Статус:** TECHNICAL SPECIFICATION / CODE NOT IMPLEMENTED / INDEPENDENT S2 REVIEW PENDING.  
**Базовая спецификация:** FIN-BE-R2-METHODOLOGY-ORCHESTRATION-TZ.md (v2.0).  
**Репозиторий:** alimbetov/fin-analyzer. **Рабочая ветка:** feat/fin-r0-r1a, без создания веток и без merge в main.  
**Code-backed baseline для требований:** 28a62cee670c1c920ffa7f40cf46513b9baf352f. Перед разработкой заново определить HEAD и сверить все контракты.  
**Исполнители:** Principal Architect, Senior Java/Spring/PostgreSQL Engineer, Financial Methodologist, QA Automation Architect, Security Engineer; независимый S2 reviewer — другой участник.  
**Главный результат:** инженерно доказать Published FactSet → объяснимый и детерминированный plan → единственный разрешённый расчёт → immutable CORE → авторизованное чтение → evidence/CI. Это не автоматическое финансовое утверждение методик.

**Пред-R2.B аудит:** [FIN-BE-R2-PRE-B-TECHNICAL-AUDIT-2026-11-17.md](FIN-BE-R2-PRE-B-TECHNICAL-AUDIT-2026-11-17.md). Зафиксированы code-backed дефекты R2.A1/A2, исправления, негативные PostgreSQL-тесты, ограничения legacy executor и обязательные gates перед неизменяемым PlanPreview/PlanCommit. Технический GREEN не заменяет независимую приёмку или ACTUAL-полномочие.

## 0. Контракт приоритетов, критерии завершения и определения

Этот документ — более детальный **реализационный контракт** для этапов A–F базового ТЗ; при противоречии не расширять полномочия автоматически, а исправлять документацию отдельным ADR до кода. Входной R2.0 Entry Gate с четырьмя ADR остаётся обязательным.

Три независимых acceptance milestone:

| Контрольная точка | Доказуемая функция | Запрещённый side effect | Релизный статус |
|---|---|---|---|
| M1: R2.A + R2.B | Один published FactSet даёт полный список applicability decisions, reason codes, DAG и immutable план | Нет метода execute(), analysis_task RUNNING/COMPUTED, fin_calc.report_run, CORE | PLAN_ONLY_ENGINEERING_ACCEPTED |
| M2: R2.C + R2.D | Один разрешённый SYNTHETIC метод с SYS source check исполняется ровно логически один раз, сохраняет проверяемые входы и immutable результат, CORE_PILOT_V1 | Нет ACTUAL permit, массового исполнения 60 семейств, ML или COMPLETE | CONTROLLED_SYNTHETIC_EXECUTION_ACCEPTED |
| M3: R2.E + R2.F | Read-only защищённые API, полная safety/functional acceptance, независимый S2 и same-SHA CI | Нет внешней публикации или production ACTUAL по техническому GREEN | ENGINEERING_ACCEPTED / METHOD_S2_HOLD / PRODUCTION_EXECUTION_HOLD |

M1, M2, M3 не являются тремя новыми системами. Реализуются в **едином** Java Backend и PostgreSQL, поверх FactSetPublicationService, SingleExecutorScheduler, MethodTaskAdapter, CatA31QualificationInspector, CompositeCoreReportPublisher и ExecutionContracts.

Термины:
- Published FactSet: действительная fin_fact.fact_set_publication и immutable fin_exec.fact_publication_outbox с одинаковыми организацией, scope, dataSpace, reportingBasis, factSetRevisionId, snapshotHash.
- Applicability: проверка пригодности данных и семантики конкретного variant; не означает разрешение запускать ACTUAL.
- ExecutionPermit: серверная проверка разрешения на исполнение с ограничением source, variantVersion, profile, dataSpace, policy и review window; отсутствие разрешения всегда запрещает запуск.
- PlanPreview: read-only DTO, не создаёт ни DB plan, ни task.
- PlanCommit: сохраняет immutable plan и decisions; **сам по себе не активирует task execution**.
- Activation: отдельная транзакционная операция после gate M1, переводящая допустимый план в выполняемый run.
- Logical result: один неизменяемый semantic result для одного (source+variant+profile+inputs+assumptions+implementation), независимо от повторной доставки сообщений.
- Pilot CORE: CORE_PILOT_V1, только PARTIAL или BLOCKED; status COMPLETE запрещён текущим DB CHECK.

Ограничения: Java 21, Spring Boot, PostgreSQL 16/Flyway, реальный Testcontainers, BigDecimal + plain decimal strings, без float/double для финансовых чисел, без новых веток, Keycloak/IAM/frontend/Flutter изменений, без повреждения старых R1 XLSX/HTML/PDF и старых публикаций. fin.execution.enabled=false, fin.methods.fin04.enabled=false, fin.methods.con.enabled=false — начальные значения. Ручной test tick не является production enablement.

## 1. Проверенная фактическая база и архитектурные препятствия

На указанном baseline:
1. SingleExecutorScheduler.tick() объединяет planOne(), claimOne(), adapter.execute() и refreshRunStates(); с включённым вызовом создание плана может сразу запустить расчёт. Для M1 это должно быть физически разнесено.
2. planOne() выбирает autoEligible/applicable adapters, а не versioned registry + ExecutionPermit, поэтому нельзя доверять готовому execution plan без новой admission policy.
3. fin_exec.analysis_run.event_id имеет UNIQUE; fin_exec.outbox_delivery.event_id — PRIMARY KEY. Старый initial event означает максимум один исторический run; повторный анализ при смене версии методики требует нового совместимого договора.
4. fin_exec.analysis_task хранит method_code и operational result_json; в нём нет pinned variantVersion, permit fingerprint и отдельного append-only result receipt.
5. fin_exec.analysis_dependency имеет только пары UUID; SQL не различает REQUIRED/OPTIONAL/EVIDENCE_ONLY.
6. Lease фиксирован 60 секунд; heartbeat не реализован. Старый поздний worker в update() не сохраняет результат после expiry, но требует отдельно проверяемого безопасного поведения всех дочерних публикаций адаптера.
7. CORE_PILOT_V1 DB CHECK допускает core_readiness IN ('PARTIAL','BLOCKED'), ml_readiness='NOT_REQUESTED'. Это обязательные границы M2.
8. CatA31QualificationInspector.Decision.permitsExecution() и CatA31MethodReviewRegistry.Result.permitsExecution() возвращают false. S2 APPROVED — необходимая, но не достаточная составляющая будущего ACTUAL permit.
9. METHOD catalog содержит 60 семейств с releaseGate HOLD и releaseQualified=false. Реальные adapter code включают SYS/VERIFY_PUBLISHED_FACT_SET, SYS/DIAGNOSTIC_PILOT, FIN-01/REV_NET_MOM, FIN-02/REV_CATEGORY_SHARE, FIN-04/CURRENT_RATIO, CON-01/PARETO_SALES, CON-02/ABC_SALES.
10. Последующая загрузка FIN-BE-R1-X6 и каталоговые unqualified revisions **не** являются published financial facts, даже при успешном HTTP-import.

**Обязательный результат R2.0:** подтверждённые схемы/индексы, current HEAD, baseline тесты, четыре ADR: trusted source, plan identity/migration, permit authority и CORE readiness; приложенные RED-тесты на разрыв planning/execution.

## 2. Модель доверия и invariant matrix

| Действие | Источник полномочия | Можно ли клиентским JSON? | Контроль |
|---|---|---|---|
| Подтвердить факт наличия ревизии | Tenant-scoped published DB source | Нет | source ID + org/entity/scope/hash |
| Выбрать метод и версию | Immutable server registry | Только profile allowlist в запросе | version + descriptor hash |
| Оценить применимость | Pure server evaluator + exact fact population | Нет | reasonCodes + input references |
| Дать ACTUAL execution permit | Отдельный server authority + method S2 and operational policy | Нет | signed/reviewed + revocation |
| Запланировать | Server commit after published outbox verification | Нет | logical key + SQL unique |
| Активировать | Server activation transaction | Нет | plan state + gate + policy |
| Исполнить | Existing SingleExecutor + pinned task | Нет | fencing + live permit |
| Сохранить результат | Validated result envelope + immutable receipt | Нет | result SHA + source lineage |
| Читать CORE | Tenant/field/period ABAC + read-only API | Только scoped selectors | no-store, non-disclosing errors |

**Fail-closed precedence:** security/source mismatch > invalid definition/graph > missing or revoked permit > source semantic conflict > missing required input > policy disabled > eligible. Причины сохранять списком; приоритет влияет на основной итоговый статус, но не должен терять полезные безопасные диагностические причины. Не возвращать клиенту частные сведения другой организации.

**Invariant A:** ни один unqualified catalog draft / не опубликованный FactSet не попадает в plan source.
**Invariant B:** planFingerprint не зависит от UUID plan, времени, worker order или локали, но зависит от всех методически значимых входов.
**Invariant C:** в M1 ни один вызов preview или plan commit не вызывает execute().
**Invariant D:** только SYNTHETIC pilot допущен для M2 до отдельного S2 ACTUAL permit; неподтверждённый FIN-01/02/04 не запускается как ACTUAL.
**Invariant E:** при одинаковом logical key не возникает второго logical run/result/CORE.
**Invariant F:** старые operational rows, revisions, outbox, method reviews и CORE остаются читаемыми и неизменяемыми.

## 3. M1 / R2.A — Registry, Applicability, Permit Boundary

### 3.1. Deliverables и классы

- MethodVariantDescriptor + MethodInputRequirement + MethodDependencyDescriptor + ResourceBudget.
- MethodVariantRegistry (read-only, server-controlled, versioned, валидируемый при старте), MethodVariantRegistryValidator.
- PublishedFactSetSnapshotRepository (REPEATABLE READ; scoped immutable snapshot), InputPopulationResolver.
- MethodApplicabilityEvaluator с отдельными structured Decision, Reason и EvidenceRef.
- ExecutionPermitEvaluator (только server-side; в M1 может возвращать hold для ACTUAL), ReviewedMethodAuthorityAdapter.
- VersionedAnalysisProfileRegistry с REQUIRED/OPTIONAL capability set и версией.
- CanonicalPlanEncoder и MethodCatalogConsistencyValidator.
- Tests: registry structure, baseline 60 family coverage metadata, reason precedence, cross-tenant, conflict source, no ACTUAL permit.

Реализовать минимальные неизменяемые DTO на Java records. Структуру можно уточнять при сохранении полей и инвариантов:

~~~java
record MethodKey(String familyCode, String variantCode, String variantVersion) {}
record InputRequirement(
    String metricCode, String metricVersion, String requiredSourceForm,
    String formVersion, DependencyKind role, String measureKind,
    String timeKind, String periodGrain, String unitCode,
    String currencyPolicy, String populationPolicy, String sourceFieldCode) {}
record MethodDependency(String methodCode, String requiredVersion, DependencyKind kind) {}
record MethodVariantDescriptor(
    MethodKey key, String descriptorVersion, String adapterId,
    String implementationVersion, String outputSchemaVersion,
    List<InputRequirement> inputs, List<MethodDependency> dependencies,
    Set<String> allowedDataSpaces, Set<String> allowedReportingBases,
    String profileCapability, String assumptionsVersion,
    String roundingPolicyVersion, String reviewPolicyCode,
    String featureFlagName, ResourceBudget resourceBudget) {}
enum ApplicabilityStatus {
    ELIGIBLE, NOT_APPLICABLE, BLOCKED_MISSING,
    BLOCKED_CONFLICT, DISABLED_POLICY, UNQUALIFIED
}
record ApplicabilityDecision(
    MethodKey method, ApplicabilityStatus status, List<String> reasonCodes,
    String sourceSnapshotHash, String descriptorFingerprint,
    List<SourceFactRef> matchedFacts, List<String> missingRequired,
    List<String> optionalWarnings, String permitDecisionId) {}
~~~

DTO в блоке — **договор полей**, не утверждение, что перечисленные классы уже существуют. Требуемый порог качества: immutable nested collections, детерминированная сортировка, no null ambiguity, технически неповторяющиеся ключи и валидируемые версии.

### 3.2. Registry contract

Key: familyCode + variantCode + variantVersion. methodCode строится из family/variant по существующему соглашению, не создаётся второй несовместимый код. Фиксировать:
- methodVersion, descriptorVersion, implementation/artifact build SHA, profile compatibility, result schema;
- exact required metricCode+version, source population, field source/form/version;
- distinction STOCK/FLOW/RATIO, date/interval/granularity/coverage, currency, accounting basis, amountScale, rounding and denominator;
- OPTIONAL vs REQUIRED vs EVIDENCE_ONLY input/dependency semantics;
- dataSpace allowlist (ACTUAL/SYNTHETIC), review evidence / expiration / revocation authority;
- resource class, bounded memory/time, minHistory, enabled config;
- canonical descriptor fingerprint independent from Java object identity.

Registry нельзя загружать из payload с исполняемым SQL, Java class name, expression text или URL. Выбирать только allowlisted MethodTaskAdapter bean и подписанные/ревьюированные серверные descriptors. Не изменять 60 releaseQualified=false и общий releaseGate HOLD без отдельной методической приёмки. Исторический descriptor после изменения не перезаписывается.

**Startup RED/GREEN:** duplicate method key, nonexistent family, alias conflict, mismatched adapter, unknown dependency, cyclic graph, unsupported metric/version, empty result schema, unapproved policy and unexpected dataSpace → explicit failure or disabled-with-reason according to S2-approved bootstrap policy; никогда не переходить к eligible через fallback.

### 3.3. Source snapshot / comparator

Один PostgreSQL read transaction REPEATABLE READ читает fin_fact.fact_set_publication, выбранные fact_selection, fact_assertion и metric_contract; сверяет tuple (organizationId, economicEntityId, businessScopeId, factSetRevisionId, hash, reportingBasis, dataSpace) и хеш selected population, включая form version/origin. Не читать catalog latest при отсутствии published data.

Для каждого требования:
- REQUIRED: отсутствие -> BLOCKED_MISSING, unit/period/basis/currency/value-state conflict -> BLOCKED_CONFLICT;
- OPTIONAL: отсутствие отражается предупреждением, method eligibility возможна только если descriptor официально разрешает fallback;
- EVIDENCE_ONLY: факт учитывается в audit provenance, недостаток методически необходимого evidence отдельно блокирует Permit, не создаёт фиктивное числовое поле;
- многопериодные ряды проверяются на interval continuity, duplicate, source revision identity, time boundaries; сравнение прошлого и текущего периода только при согласованном policy;
- ZERO ≠ NULL ≠ ABSENT ≠ NOT_APPLICABLE; decimal checked via BigDecimal без произвольной конвертации валют.

Не запрашивать raw values у разных org сквозь незащищённые join. Логировать только безопасные коды причин, не суммы и персональные данные.

### 3.4. Исполнимые правила статусов и permit

Различать:
- ApplicabilityStatus: техническая применимость **и** допустимость для выбранного mode. ELIGIBLE только при complete source/registry/permit decisions для выбранного режима.
- ExecutionPermitStatus: SYNTHETIC_LAB_ALLOWED, ACTUAL_ALLOWED, DENIED_MISSING_S2, DENIED_REVOKED, DENIED_POLICY, DENIED_SCOPE, DENIED_SOURCE.
- TaskStatus: PENDING/READY/RUNNING/COMPUTED/FAILED_FINAL и существующие SQL блокировки; нельзя путать с applicability.

В M1 ACTUAL_ALLOWED **никогда не выдаётся** пока независимый authority/operations gate не реализован и не принят; формально APPROVED в методическом реестре — ещё не разрешение запуска. Для SYNTHETIC_LAB_ALLOWED только server-side allowlist и debug/staging-controlled mode, не производство.

Отзыв review между планом и claim означает запрет нового claim, но не уничтожает исторический immutable plan/ledger. Отзыв во время compute требует revalidation перед commit и проверки fencing, возможного terminal failure и аудита причины. Для исторического результата сохраняется статус permit на момент исполнения и время review; не переписывать его.

### 3.5. Acceptance для R2.A

A-01 published SYNTHETIC FactSet + valid method → ELIGIBLE с точными fact refs и review/permit metadata.  
A-02 missing REQUIRED → BLOCKED_MISSING и список только отсутствующих кодов.  
A-03 currency/period/measure/unit/scope mismatch → BLOCKED_CONFLICT, финансовых чисел в reason нет.  
A-04 NOT_APPLICABLE для сектора вне descriptor, без ошибки.  
A-05 feature OFF → DISABLED_POLICY, даже при valid data.  
A-06 ACTUAL без method+execution review → UNQUALIFIED; autoEligible не может повысить статус.  
A-07 malformed/duplicate registry/version/cycle → отказ до планирования, никакого silent skip.  
A-08 sealed source revision vs mismatched hash/current draft → SOURCE_HASH_CHANGED / SOURCE_NOT_PUBLISHED, ноль plan/task.  
A-09 чужая организация / экономическая сущность / scope → недекларирующий отказ.  
A-10 60 family metadata без execution bindings не превращаются в 60 runnable tasks.

**Gate A:** все десять тестов реальны, функции применимости детерминированы, ни один test subject не получает ACTUAL permit и ни один evaluator не записывает execution tables.

## 4. M1 / R2.B — PlanPreview, immutable PlanCommit, DAG без исполнения

### 4.1. Главное решение: разделить plan и execute

Текущее SingleExecutorScheduler.tick() планирует и сразу claims один task. Для M1 отделить:
1. PublishedEventPlanSelector: читает committed outbox event.
2. PlanPreviewService: проверяет source, registry, applicability и DAG, возвращает immutable DTO **без записи**.
3. PlanCommitter: сохраняет план, decisions/edges, source identity/hash/versions **без создания runnable tasks и без delivery ACK**.
4. PlanActivator: в M1 отключён. Только после gate M1, в R2.C, создаёт run/tasks через existing SingleExecutor.
5. SingleExecutorScheduler claim/execute остаётся физически выключенным для новой версии плана до активации; M1 CI доказывает отсутствие execute(), analysis_run для новых planOnly events, любых fin_calc.report_run и CORE.

Сохранить legacy tick для исторических записей только через explicit compatibility mode, без обхода нового permit; миграция на новый planner не должна создавать «два читателя одного outbox», дублирующих source publication. Предпочтителен один scheduler/coordinator с отдельными entrypoints planTick() и executionTick(), а не два независимых рабочих цикла.

**PlanPreview** (GET) не изменяет вообще ни один fin_exec/fin_calc/fact table, включая telemetry/receipts; read-only транзакция, deterministic output кроме явно отмеченного generatedAt (не участвует в hash).

### 4.2. Модель PlanPreview (иллюстративный JSON-контракт)

~~~json
{
  "schemaVersion": "FIN_R2_PLAN_V1",
  "source": {
    "organizationId": "<server-authorized UUID>",
    "factSetRevisionId": "<published UUID>",
    "snapshotHash": "<verified SHA-256>",
    "dataSpace": "SYNTHETIC"
  },
  "profile": {"code": "SYNTHETIC_LAB", "version": "1"},
  "registryFingerprint": "<SHA-256>",
  "planFingerprint": "<SHA-256>",
  "planStatus": "PREVIEW_ONLY",
  "decisions": [
    {
      "methodCode": "SYS/VERIFY_PUBLISHED_FACT_SET",
      "variantVersion": "1",
      "status": "ELIGIBLE",
      "reasonCodes": []
    },
    {
      "methodCode": "FIN-04/CURRENT_RATIO",
      "variantVersion": "1",
      "status": "DISABLED_POLICY",
      "reasonCodes": ["EXECUTION_DISABLED"]
    }
  ],
  "edges": [],
  "estimatedTaskCount": 1,
  "executionPermittedNow": false
}
~~~

Строки в angle placeholders выше — **пример DTO**, не реальные UUID/хеши и не доказательство выполненного теста. Event/plan identity, server profile and review authority derive only from backend.

Результат охватывает все зарегистрированные method variants; family catalog без binding представлен агрегированным явно NOT_CONNECTED/UNQUALIFIED (не как возможная задача). Для blocked statuses сохранять reason и matched/missing metadata без утечки чужого tenant.

### 4.3. Plan identity, canonical encoding

Input plan fingerprint обязан включать:
- organization + entity + businessScope;
- exact factSet revision + publication snapshotHash + dataSpace + reportingBasis;
- descriptor registry fingerprint + profile code/version + selected method versions + adapter implementation build SHA;
- unit/accounting/fiscal calendar/FX/amount scale policy versions, review/permit decision fingerprints, assumptions;
- nodes, typed edges, source requirement bindings and canonical input population fingerprints.

Сериализация: schema version prefix, UTF-8 with explicit length/typed framing or tightly defined canonical JSON, sorted keys/nodes/edges, stable locale-neutral decimal text, unique required node keys. Не использовать Map.toString(), default unordered Jackson output, random UUID, createdAt/Instant.now() или local timezone как плановую идентичность.

PlanFingerprint SHA-256 фиксируется во время планирования, а повторная вычислительная проверка хеша в PostgreSQL/API должна совпадать по той же спецификации. Негативный тест меняет по очереди source hash, variantVersion, metricVersion, accountingPolicy, method review и assumptionVersion; hash должен измениться каждый раз. Отсутствующие optional inputs также part of canonical decision graph.

### 4.4. DAG and typed edges

Node = (methodCode, variantVersion); edge = (dependent, prerequisite, kind, versionConstraint). Проверить unknown version, self-edge, duplicate, non-deterministic tie order, cycles и недопустимые dataspaces. Stable topological sort: сначала prerequisite, затем lexical method key с определённым version comparator.

- REQUIRED: запуск child только после валидного COMPUTED parent; при FAILED_FINAL/BLOCKED child отмечается BLOCKED_* с causal chain. Не блокировать независимые nodes.
- OPTIONAL: если predecessor отсутствует/FAILED, child может быть READY только когда методическое правило explicitly allows absent optional; warn и pin input omission. Нельзя молча считать нулём.
- EVIDENCE_ONLY: придаёт source lineage, не требует COMPUTED task. Если само evidence обязательно для legal/qualification, ExecutionPermit DENIED; тип edge не является обходом review gate.

Все blocked, skipped, disabled решения сохраняются в **plan_decisions**; SQL tasks создаются исключительно для узлов с активируемым permit и прошедшими gates. Нельзя насильно создавать task для 60 семейств без binding.

### 4.5. SQL-структура — архитектурно определённая минимальная схема

Добавить новую forward-only Flyway migration после текущего HEAD:
- fin_exec.analysis_plan_manifest(plan_id UUID PK, event_id UUID FK, logical_plan_key CHAR(64), plan_fingerprint CHAR(64), organization_id UUID, economic_entity_id UUID, business_scope_id UUID, fact_set_revision_id UUID, snapshot_hash CHAR(64), profile_code/version, registry_hash, permit_policy_version, schema_version, canonical_manifest JSONB, created_at, plan_kind INITIAL/REEVALUATION; append-only);
- fin_exec.analysis_plan_decision(plan_id FK, method_code, variant_version, status, ordered reason_codes JSONB, descriptor_hash, population_fingerprint, permit_status, reviewed_evidence_id/hash, unique(plan_id,method_code,variant_version); append-only);
- fin_exec.analysis_plan_edge(plan_id FK, dependent_code/version, prerequisite_code/version, kind REQUIRED/OPTIONAL/EVIDENCE_ONLY; PK covering full identity; append-only);
- fin_exec.analysis_plan_activation(plan_id UNIQUE, run_id UNIQUE NULL, activation_status, activated_at, actor, permit_evidence_hash, last_error_code): отдельный operational ledger; M1 содержит только NOT_ACTIVATED/ABSENT.

DB constraints: immutable trigger for manifest/decision/edges, approved-or-null review evidence, CHECK status allowlist, tenant/source FKs, versioned JSON schema check where feasible, unique(logical_plan_key) и unique initial-plan per event_id. Для re-evaluation требовать server-owned authenticated request + idempotency key + separate plan_kind; один event может иметь re-evaluation plans, но старый outbox_delivery не получает второго ACK.

**Не менять** прежние analysis_run.event_id UNIQUE / outbox_delivery.event_id PRIMARY KEY в M1: нет ни одной законной причины создавать второй execution run до R2.C. Новые tables не должны ломать исторические SELECT и старые тесты.

### 4.6. Exactly-once logical plan

Single transaction: SELECT immutable event FOR UPDATE SKIP LOCKED → verify matching publication (org+revision+hash) → call canonical planner → INSERT plan/decisions/edges ON CONFLICT logical key returning existing plan → COMMIT. При crash до commit 0 rows и повторный план эквивалентен; при crash после commit повторный вызов возвращает тот же planId/fingerprint. Не записывать outbox_delivery в M1, т.к. это пока **не** task execution delivery; состояние планирования учитывать в новом plan ledger.

Режим re-evaluation по методике/profile/assumption без нового published FactSet — отдельная server-owned команда (не HTTP произвольному пользователю), с родительским immutable plan и новой версией policy. Новая публикация FactSet естественно создаёт новый event и новую identity. Правило «пересчитать только true dependents» требует input-level fingerprints; в M1 вычислять affected nodes в preview, но не исполнять.

### 4.7. M1 обязательная демонстрация и DoD

Создать PostgreSQL SYNTHETIC FactSet публикацию, заполнить 1–2 валидных metric observations, выбрать SYNTHETIC_LAB profile. Проверить:
- PlanPreview возвращает source provenance, разрешённые и заблокированные варианты с точными reasonCodes и stable DAG;
- повторный PlanPreview не создаёт ни одной строки в analysis_plan_manifest/analysis_run/analysis_task/fin_calc.report_run/core_report_snapshot;
- PlanCommit записывает один план и decisions, повторная доставка возвращает тот же план, SQL evidence доказывает отсутствие задач и результатов;
- при смене версии descriptor/new source новый hash, старый plan immutable;
- unknown dependency/cycle, forged input, wrong org не оставляют частичных записей;
- SQL clean + upgrade migration проходит, old outbox / old CORE читаются.

**Gate M1:** реальный опубликованный FactSet → одно стабильное preview → one persisted immutable plan, deterministic statuses/reasons; task executions = 0; CI на одном SHA + плановый independent design review. Никакого ACTUAL разрешения и production enablement.

## 5. M2 / R2.C — Активация, lease/fencing, recovery и результат

### 5.1. Функциональный контракт

Потребитель PlanActivator принимает **только persisted immutable plan**, созданный после M1. Он проверяет pinned source/hash, current permit/policy, registry implementation SHA и profile. Успешная активация создаёт один analysis_run + разрешённые tasks + typed dependencies; при нарушении либо не меняет состояние, либо фиксирует controlled failure в operational ledger. Все writes выполняются в одной короткой PostgreSQL TX.

Для первого SYNTHETIC vertical slice:
- plan.kind = INITIAL;
- один initial plan на fact publication event;
- один analysis_run на event (старый UNIQUE(event_id) сохранён);
- outbox_delivery одна запись с analysis_run_id после активации;
- только methodCode, которые были ELIGIBLE в сохранённом плане и разрешены актуальным SYNTHETIC_LAB profile, создают runnable tasks.

Повторная activation идемпотентна. На M2 не делать bypass через старый planOne(autoEligible) или прямую запись tasks. Отозванный permit/изменённый source hash запрещает activation, не переписывает immutable plan.

### 5.2. Re-evaluation одного события — согласованный DB upgrade

Схема legacy analysis_run.event_id UNIQUE запрещает новый run для того же event. R2.C включает отдельный ADR и forward-only миграцию **до** включения re-evaluation execution:
1. Добавить analysis_run.plan_id nullable FK, logical_run_key, run_kind INITIAL/REEVALUATION, pinned registry/profile/policy hashes; историю пометить LEGACY_INITIAL без выдуманного S2 permit.
2. Изменить старый UNIQUE(event_id) только после backfill и data integrity tests. Ввести unique(logical_run_key), partial unique(event_id) WHERE run_kind IN ('INITIAL','LEGACY_INITIAL'). Существующий outbox_delivery.event_id PRIMARY KEY остаётся initial-only.
3. Re-evaluation run привязывать к новому planId и отдельному server-side request ledger, **без INSERT второго outbox_delivery**.
4. Старые task/result/CORE references остаются неизменными и читаемыми, historical run/status не повышаются автоматически.
5. Deploy safety: backfill → verify → index creation → constraint migration → new code. Не менять старые Flyway checksums; на неразрешённых дублях abort.

Если независимый ADR отклоняет этот способ, выбрать эквивалентную версионированную модель и обновить ТЗ **до** кодового merge. Не заявлять «re-evaluation executed» без работающей разрешающей SQL-модели.

### 5.3. State machine

| Entity | Исходное состояние | Событие | Итог / ошибка |
|---|---|---|---|
| Plan | persisted, NOT_ACTIVATED | activate, valid permit | ACTIVE + runId; immutable plan unchanged |
| Plan | persisted, NOT_ACTIVATED | permit revoked / source mismatch | ACTIVATION_DENIED with reason, zero tasks |
| Task | PENDING | REQUIRED completed | READY |
| Task | PENDING | REQUIRED FAILED/BLOCKED | BLOCKED_MISSING/CONFLICT (versioned SQL constraints), причина/цепочка |
| Task | PENDING | OPTIONAL failed | READY только при разрешённой методике, warning |
| Task | READY | claim token+lease | RUNNING, attempt++, fence++ |
| Task | RUNNING | heartbeat with current fence | extended lease only, short TX |
| Task | RUNNING | valid result and permit still valid | COMPUTED + immutable receipt |
| Task | RUNNING | retryable failure | READY, bounded next_attempt_at |
| Task | RUNNING | terminal reason or attempts exhausted | FAILED_FINAL |
| Task | RUNNING | stale token or expired lease | stale completion discarded, no new result/side effects |
| Run | all terminal tasks | aggregate completion | COMPLETED/PARTIAL/FAILED as profile rules, not arbitrary bool |
| CORE | terminal source-bound run | publish exactly once | immutable PARTIAL/BLOCKED CORE_PILOT_V1 |

Исторические SQL statuses оставить совместимыми. Новые statuses только в forward-only migration с явно согласованными CHECK constraints. Причины отказов — canonical enum, не free text исключения Java.

### 5.4. Lease / heartbeat / fencing implementation

- Claim: SELECT .. FOR UPDATE SKIP LOCKED, conditional UPDATE with previous fencing_token, leaseUntil по DB NOW(), ограничение max workers и task attempt.
- Compute: вне DB transaction, под ограниченным resource profile.
- Heartbeat: независимый от блокирующего execute поток/механизм, short transaction и UPDATE WHERE id=? AND fence=? AND RUNNING AND leaseUntil>DB_NOW; при потере права остановить работу либо запретить commit.
- Lease TTL, heartbeat interval, task timeout, retry/backoff — server config versioned и measured test values; heartbeat interval < TTL с запасом, нет бесконечного продления без wall-time budget.
- Commit: guarded source hash + actual permit + plan descriptor/version + RUNNING fence and unexpired lease. Только успешная conditional transaction может сохранять terminal result receipt; stale worker не может создать новый результат.
- Transient retry: exponential backoff, bounded retries + jitter independent of business data. Permanent invalid unit, conflict source, revoked review, invalid result schema, wrong tenant → FAILED_FINAL, не повторять бессмысленно.
- DLQ: planning poison в fin_exec.outbox_planning_failure, execution failure в отдельном ledger; immutable published outbox не изменять и не удалять.

**Проверка гонок:** два независимых connections, два worker, crash after claim, fail before result commit, stale worker after new fence, DB unavailable during heartbeat, repeated task receipt, retry exhaustion. Условие одно: один accepted immutable result на logical key.

### 5.5. Immutable result receipt

Новый versioned immutable fin_exec.analysis_task_result_receipt (либо эквивалент):
- receiptId, planId, runId, taskId, methodCode, variantVersion, resultSchemaVersion;
- exact sourcePublicationId, sourceHash, selected fact/source refs and inputPopulationFingerprint;
- descriptorVersion/hash, assumptions/accounting/unit/FX policy fingerprints, implementationBuildSha;
- attempt + fencingToken, acceptedAt (DB timestamp), elapsed/resource profile;
- resultJson validated typed envelope, resultSha256, resultStatus, reasonCodes, methodQualificationStatus, dataSpace;
- UNIQUE(taskId) accepted terminal result, DB CHECK JSON type, foreign key to actual running task and plan;
- immutable UPDATE/DELETE rejection trigger, safe query index by tenant/run; no PII/secrets in ordinary logs.

Рекомендованный нормализованный envelope:

~~~json
{
  "schemaVersion": "FIN_R2_RESULT_V1",
  "methodCode": "SYS/DIAGNOSTIC_PILOT",
  "variantVersion": "1",
  "sourceFactSetRevisionId": "<UUID>",
  "sourceFingerprint": "<SHA-256>",
  "inputFingerprint": "<SHA-256>",
  "implementationBuildSha": "<SHA>",
  "dataSpace": "SYNTHETIC",
  "status": "CALCULATED",
  "metrics": [],
  "limitations": ["SYNTHETIC_TEST_DATA", "METHOD_FAMILY_NOT_APPROVED"]
}
~~~

Existing adapters return String JSON; migration path — validate and wrap through TrustedResultEnvelopeFactory, preserve old adapter semantics and tests. Do not trust an adapter's self-reported qualification field. No arbitrary strings interpreted as decimal; validate precision/unit/period and prohibit NaN/Infinity.

**Critical side-effect boundary:** some existing adapters call TrustedAutoReportService.calculateAndPublish inside execute(). An old worker might publish a child report even if its parent task fence is stale. Before M2 acceptance either:
- extract pure compute + make child report publication occur inside fence-validated transactional finalizer with idempotent result key; or
- prove existing child report is immutable and idempotent by the **same exact plan/result semantic key**, with no acceptance by a stale worker.
Without one of these proof paths, M2 remains HOLD even if analysis_task eventually COMPUTED.

### 5.6. M2 / R2.C tests

C-01 duplicate activation of initial plan returns same run; SQL UNIQUE.
C-02 stale/expired/revoked permit denies activation without inserted tasks.
C-03 two workers claim a task, only one valid fence can accept.
C-04 worker crashes and lease expires, recovery increments fence.
C-05 late worker result rejected, immutable receipt unchanged.
C-06 heartbeat lost / DB outage does not publish incorrect terminal result.
C-07 retryable failure bounded, permanent input failure terminal.
C-08 REQUIRED failure blocks child, OPTIONAL allowed does not block, sibling continues.
C-09 crash after child report publication does not create a second semantic child report.
C-10 SQL historical migration/backfill with legacy run, old CORE still readable and immutable.

**Gate C:** stable activation + one logical computation with fencing and append-only receipt, no stale side effects, no actual production enablement.

## 6. M2 / R2.D — один экономически контролируемый расчёт

### 6.1. Выбор пилота и порядок

Минимальный vertical slice: SYS/VERIFY_PUBLISHED_FACT_SET → SYS/DIAGNOSTIC_PILOT на опубликованном SYNTHETIC FactSet с точным snapshotHash. Это существующие adapters; требуется доказать их реальные входы, выходы, версии, side-effect boundary и лимиты. Результат маркировать SYNTHETIC_TEST_DATA и METHOD_FAMILY_NOT_APPROVED. Нельзя подменять доказательство запуска реального Java метода заранее сгенерированным JSON.

Затем для инженерной (не финансовой) проверки расчётного движка использовать FIN-01/REV_NET_MOM с независимо заданными golden revenue values на допустимом SYNTHETIC/controlled profile **только если** серверный method permit/policy разрешит этот режим и адаптер доказуемо проверяет источник. Если этот режим не разрешён, не ослаблять adapter: оставить FIN-01 в UNQUALIFIED / HOLD и закрыть M2 по честно ограниченному SYS/DIAGNOSTIC_PILOT. Для ACTUAL требуется отдельный method S2 signoff и утверждённая политика выполнения.

Кандидаты для последующих отдельных квалификаций, **не требуются все сразу** для M2: FIN-02/REV_CATEGORY_SHARE, FIN-04/CURRENT_RATIO, CON-01/PARETO_SALES, CON-02/ABC_SALES. Каждый variant review отдельно; фамильные 60 release gates остаются HOLD.

### 6.2. Финансовый контракт на variant

Для каждого подключаемого business variant надо утвердить passport:
- precise input metricCode+version, source document/form field/approval refs, calculation formula version;
- economic meaning + measureKind STOCK/FLOW/RATIO + date/interval and fiscal policy;
- amountScale/currency/unit, sign convention and zero/nonzero denominator;
- exact required/optional population, duplicates/returns, scope/basis/dataSpace;
- rounding/MathContext, output decimal representation, not-computable reasons;
- independent reviewer evidence and revocation;
- output metric dimensions/period, source references and method limitations.

Критерии тестовых golden values:
- 125000.50 / 100000.00 two months, expected 25.0005% при конкретно утверждённой формуле и scale; negative revenue/zero prior base typed NOT_COMPUTABLE;
- STOCK assets/liabilities одной as-of даты, без TOTAL substitution;
- SKU refund/reversal and ties stable order, exact reconciliation with F02;
- 38/12 decimal values, no double/float, period leap and month boundary;
- sourceHash mutation, wrong metricVersion and conflicting currency block method.

Значения здесь — **пример независимого тестового oracle**; бизнес-утверждение формулы требует реального методического паспорта. Нельзя автоматически признавать рассчитанное число советом клиенту.

### 6.3. CORE snapshot — строгое поведение

Только после terminal run и проверки sourceHash against fin_fact.fact_set_publication, tenant, result envelopes, permit and profile requirements создавать immutable fin_exec.core_report_snapshot. Для CORE_PILOT_V1 допускаются только coreReadiness PARTIAL/BLOCKED, mlReadiness NOT_REQUESTED. FAILED run или 0 accepted computations → BLOCKED. Даже при успешном synthetic diagnostic business-method profile остаётся PARTIAL; не вводить COMPLETE без отдельного release-qualified profile и forward-only SQL migration.

Сохранять report schemaVersion, runId, planFingerprint, sourceRevision/sourceHash, method variant versions, result receipt hashes, evidence of omissions and limitations. Результаты не меняют утверждённые FactSet и не создают новый catalog revision.

Repeated publish: SELECT terminal run FOR UPDATE SKIP LOCKED, compare pinned source and profile, INSERT ... ON CONFLICT(run_id) DO NOTHING, verify existing SHA equals expected canonical body; при mismatch — deterministic CORE_REPORT_CONFLICT, не silently return success. Старый CORE immutable даже после методической revocation, но внешнее его использование подчиняется текущим правилам доступа.

### 6.4. M2 end-to-end proof

В Testcontainers реально выполнить цепочку:
PostgreSQL published SYNTHETIC FactSet + immutable event → PlanCommit → PlanActivator → SYS publication verification → одна допущенная diagnostic method task → result validation → accepted immutable task receipt → terminal run → CORE_PILOT_V1.

Проверить и вывести в machine evidence: sourceRevision/hash, planId/fingerprint, runId, variantVersion, taskId, attempt/fence, exact inputFingerprint, accepted resultHash, CORE reportId/hash/readiness. Тест повторяет event/activation/claim/completion и проверяет идентичные значения и отсутствие дублей. Другой published ACTUAL источник без методического permit должен остаться без business execution.

**Gate M2:** один реальный расчёт и immutable результат при полном source provenance; independent golden oracle; replay/crash proof; финметодики ACTUAL всё ещё HOLD.

## 7. M3 / R2.E — авторизованный API, audit, эксплуатационная наблюдаемость

### 7.1. HTTP API contract (предлагаемые, ещё не реализованные пути)

Prefix: /api/v1/financial/organizations/{organizationId}/executions

| Метод/путь | Кто | Семантика / side effects |
|---|---|---|
| GET /plan-preview?factSetRevisionId=...&profileCode=... | FIN_ANALYZE + scope/field ABAC | Pure preview, 200 JSON, no DB writes |
| GET /plans/{planId} | FIN_READ + scope/field/period ABAC | Immutable plan + source-limited decisions; hide restricted fact details |
| GET /runs/{runId} | FIN_READ + scope ABAC | Status, tasks count, last reason; no raw financial values without permission |
| GET /runs/{runId}/tasks | FIN_READ + field/period ABAC | Ordered task statuses/reasons, variant versions, permit state |
| GET /reports/{reportId} | FIN_READ + report ABAC | Immutable CORE, hash/status/limitations, no-store |
| POST /execute arbitrary methodCode | НИКОМУ | НЕ создавать в R2; запрещён обход registry/permit |
| POST /reevaluate | Server-owned/internal only | Только после ADR, audit + idempotency key и отдельного security gate |

PlanPreview query минимален: factSetRevisionId + profileCode из allowlist; businessScope, sourceHash, method definitions выводятся с сервера. Запрещено принимать from JSON: org claim, approved/reviewed, requiredGroups, permit=true, freeform SQL/Formula, raw input values.

Ответ status API должен различать ApplicabilityStatus, TaskStatus, ExecutionStatus, CoreReadiness, MethodQualificationStatus. В противном случае клиент покажет «готово» для UNQUALIFIED или PARTIAL.

HTTP:
- Cache-Control: no-store, X-Content-Type-Options: nosniff, safe Content-Type, CSP для HTML if any; GET не имеет durable side effect.
- paginated tasks, bounded output lengths, no raw stack traces, no SQL errors, stable error code.
- 400 invalid selector/profile, 401 no JWT, 403 no organization permission, 404 not found or non-disclosing foreign entity, 409 source/plan version conflict, 422 method unqualified or input conflict (без запрещённых фактов), 429 rate policy, 500 internal only; статусные детали согласовать с существующим FinancialApiErrors.
- HTTP request не повышает run/task статус и не запускает вычисление. Метрики доступа не содержат финансовых сумм, JWT и персональных данных.

### 7.2. Сквозная authorization matrix

- No JWT → 401 (как настроено в существующем Spring Security).
- JWT обычной realm role без fin_access → 403.
- JWT org A с FIN_ANALYZE не может preview org B, угадывать её sourceRevisionId или увидеть факт существования.
- FIN_READ без FIN_ANALYZE не может инициировать даже PlanPreview, если операция классифицирована как анализ.
- FIN_ANALYZE не даёт S2-method-reviewer approval, release permit или access к запрещённым field/period.
- Tenant-scoped read для CORE; нет права раскрывать raw inputs/документы, hidden fact fields и account-level PII.
- Revoked review, stale source, invalid profile and ambiguous method version возвращают reason codes без данных чужого tenant.
- Только доверенный service actor может commit/activate; внешний POST /execute отсутствует.

Field/period ABAC: если текущая версия FinanceAccessPolicy умеет только org-scoped FIN_READ, это недостаточно для безопасного внешнего раскрытия CORE. До отдельного ABAC и негативных HTTP E2E оставить read-only API доступным только контролируемым внутренним consumer/service context либо в HOLD для production. Не заявлять реализованный ABAC из наличия FIN_READ.

### 7.3. Observability, metrics и audit trail

Structured audit events: SOURCE_VERIFIED, APPLICABILITY_EVALUATED, PLAN_PERSISTED, PLAN_ACTIVATED, TASK_CLAIMED, HEARTBEAT_RENEWED, STALE_TOKEN_REJECTED, TASK_FAILED_RETRYABLE, TASK_FAILED_FINAL, RESULT_ACCEPTED, CORE_PUBLISHED, PERMIT_REVOKED, SECURITY_DENIED. Каждое событие содержит correlation/run/plan IDs, sourceHash truncated for logs, reasonCode, actor (где уместно), policy and build version, но не финансовые суммы, source documents, bearer JWT, exception SQL payload.

Operational metrics: plan.preview.duration, plans.accepted, decisions by status, queue backlog, lease expiry, heartbeat failures, retry/DLQ rate, stale result rejects, worker walltime/p95/p99, CORE publish success/fail and source integrity denies. Избегать неограниченной cardinality: пользовательские ID, UUID run и metricCode не должны автоматически входить в Prometheus labels.

Runtime parameters: execution default disabled, max concurrent tasks/tenant/global, queue polling interval, cost budgets, heartbeat TTL, retries, resource/time caps. Численные production SLA устанавливаются после измерений; в тестах использовать явные tight budgets и fail-closed regression assertions.

**Gate E:** API не раскрывает чужие facts, GET не пишет DB, статусные значения не скрывают неполноту, telemetry содержит измеримые события и нет endpoint обхода permit.

## 8. M3 / R2.F — матрица реальных проверок и release evidence

### 8.1. Основная acceptance matrix — связь с FIN-BE-R2 v2.0

Не создавать другую нумерацию, расходящуюся с основной ТЗ. Авторитетный набор **BE-R2-01…36** из раздела 11 базового документа; распределение по этапам:

| Этап | Обязательные BE-R2 кейсы | Дополнительные proof cases |
|---|---|---|
| R2.A | 12–20, 25–30 (semantic source, review, variants) | A-01…A-10, registry/property test |
| R2.B | 01, 04–11, 16, 23, 35–36 | stable canonical SHA, dry-run no-writes, typed DAG |
| R2.C | 02–05, 11, 21–24, 35 | C-01…C-10, real double connection, fencing |
| R2.D | 08, 12–15, 25–34 | single end-to-end result + independent golden |
| R2.E | 17–20, 34, 36 | API authorization/ABAC/error/status contract |
| R2.F | **01–36 все** | CI evidence integrity, release and S2 approval |

Обязательные критические proof-point:
1. PlanPreview читает настоящий published source и выдаёт reason-coded JSON; между вызовами count(*) во всех operational/published/result таблицах неизменен.
2. PlanCommit deterministic и atomic: два concurrent transactions получают одну identity, нет дублирующих планов и нет tasks в M1.
3. Activation допускает только pinned plan/permit, не создаёт второй initial run, не пишет вторую outbox_delivery.
4. Two workers + lease crash/fence: один accepted result receipt, stale не публикует дочерний fin_calc run.
5. REQUIRED failure и OPTIONAL omission: независимая ветвь продолжает работу, typed reasons видны в CORE.
6. 38/12 BigDecimal сохраняет точность, null/missing/zero различаются, MathContext/units/period совпадают.
7. ACTUAL без подписанного/валидного method execution permit не запускается даже при факте source publication.
8. Unqualified method family остаётся HOLD; synthetic result не назван независимым финансовым заключением.
9. CORE_PILOT_V1 содержит sourceHash, versioned result refs, immutable digest, readiness PARTIAL/BLOCKED, ML NOT_REQUESTED.
10. Every endpoint tenant/field/period security proof; no side effects on GET.
11. Fresh Flyway + upgrade from pre-R2 DB, historical run readable; no checksum modifications.
12. Independent finance and security S2 with actual reviewer identity, plus same-sha all CI jobs GREEN.

### 8.2. Требования к golden fixtures

Независимые input/expected fixtures хранить отдельно от production evaluator/adapter, не рассчитывать ожидание тем же Java кодом. Каждая строка включает organization/scope, source publication SHA, form/metric/version, input values, dataSpace, period, currency/scale, descriptor and policy versions, expected decision/status/reason, expected plain-decimal result or expected NOT_COMPUTABLE, expected lineage. Не фиксировать «успешный результат» там, где published source отсутствует. Внешняя методическая экспертная приёмка обязательна для ACTUAL.

Негативные кейсы: duplicate events, SQL unique collisions, malformed descriptors, wrong tenant, fabricated reviewed flag, unsafe profile, metric version mismatch, currency conflict, revocation, crash and stale token, mis-replayed old run, broken JSON, unknown result field, missing original document proof, report/plan hash forgery. Не заменять PostgreSQL Testcontainers unit mocks.

### 8.3. Воспроизводимый CI-gate

Добавить отдельный FIN_BE_R2_ORCHESTRATION_ACCEPTANCE, не ослабляя уже работающие FIN Backend Domain, FIN CI и FIN-BE-R1-X5 доказательства (166 × 4 × 3). Полный набор:
- Maven Java 21 verify + PostgreSQL 16 Testcontainers; separate tests planner, registry, permit, activation, concurrent workers, result, CORE, read-only API.
- clean-install AND historical upgrade Flyway; check all earlier migration checksums.
- JUnit XML + actual PostgreSQL evidence; independent verifier compares exact 36 scenario IDs against executed tests, no missing/duplicate/SKIPPED.
- GitHub workflow HEAD SHA equal evidence gitSha, same SHA for backend-verification, verify, desktop-macos, acceptance-gate.
- Fail-closed if ANY required test PASS not proved (MISSING/SKIP/NOT_RUN/UNKNOWN/FAIL) or artifact generation not from executed test.
- Source/adapter review status cannot be promoted through CI green.

Evidence file: backend/target/fin-evidence/fin-be-r2-orchestration-acceptance.json. Structure:

~~~json
{
  "schemaVersion": "FIN_BE_R2_ACCEPTANCE_V1",
  "gitSha": "<GITHUB_SHA>",
  "milestone": "M1|M2|M3",
  "cases": [
    {
      "testCaseId": "BE-R2-01",
      "suite": "R2PlannerPostgresIT",
      "sourcePublicationId": "<UUID>",
      "planFingerprint": "<SHA-256>",
      "logicalRunId": "<UUID-or-null>",
      "resultSha256": "<SHA-256-or-null>",
      "verdict": "PASS",
      "postgresProven": true,
      "oracleType": "INDEPENDENT_GOLDEN"
    }
  ],
  "sourceQualification": "SYNTHETIC_PILOT",
  "methodS2Approval": "HOLD",
  "productionExecution": "DISABLED"
}
~~~

Пример показывает контракт, не выполненное тестирование. Никаких fabricated IDs/data/metrics. В M1 resultSha256 должен отсутствовать/null, и verifier доказывает 0 tasks/execution.

### 8.4. Release matrix (не объединять разные решения)

| Gate | M1 | M2 | M3 |
|---|---|---|---|
| Source publication integrity | PASS required | PASS required | PASS required |
| Plan + reason-coded decisions | PASS | PASS | PASS |
| No computation in plan-only mode | PASS required | Regression PASS | Regression PASS |
| Crash-safe execution + result ledger | NOT_APPLICABLE | PASS | PASS |
| Semantic golden for selected method | Preview-only source checks | PASS SYNTHETIC | PASS + review |
| Tenant/field/period authorization | planner internal proof | Internal proof | HTTP E2E PASS |
| Independent technical S2 | Required before M2 activation | Required before M3 | Final approval |
| ACTUAL finance S2 | HOLD | HOLD until separately qualified | Separate ACTUAL gate |
| Production execution enablement | OFF | OFF | Separate operations authorization |
| CI all jobs same SHA | Required | Required | Required |

**Engineering acceptance** может быть вынесено для SYNTHETIC platform R2 при сохранении METHOD_S2_HOLD и PRODUCTION_EXECUTION_HOLD. Никакого ACTUAL FIN-01/02/04, CON-01/02 на основании одного технического результата.

## 9. План реализации Codex — обязательные небольшие проверяемые инкременты

| Шаг | Сначала RED | Затем minimal GREEN | Файлы и deliverable |
|---|---|---|---|
| R2-A1 | invalid/missing descriptor + source/tenant negative | registry+validator+canonical fingerprint | Java immutable descriptors + registry tests |
| R2-A2 | missing/conflict/disabled/revoked S2 | scoped InputPopulationResolver + ApplicabilityEvaluator | reason-coded decision tests |
| R2-B1 | preview writes DB / nondeterministic hashes | pure preview + canonical encoder | Preview API-free service + property tests |
| R2-B2 | repeated event duplicates / cycle / unknown edge | plan manifest/decisions/edges with SQL unique | Flyway + Testcontainers plan IT |
| **M1 GATE** | fail if any execution side effect | verified plan-only evidence and S2 | one SHA, no runnable tasks |
| R2-C1 | duplicate activate/foreign plan | controlled activation inside TX | PlanActivator + legacy-safe migration |
| R2-C2 | expired lease/stale worker | heartbeat+fencing+budget | concurrency tests, no DB lock during compute |
| R2-C3 | repeated child publication / duplicate result | validated envelope, immutable result ledger | SQL receipt + crash test |
| R2-D1 | computed task without source input proof | one real synthetic method + CORE | precise actual PostgreSQL E2E |
| **M2 GATE** | no fake source result; no ACTUAL bypass | safe synthetic vertical slice and S2 | method still not family-qualified |
| R2-E1 | cross-org/field/period leaks | read-only scoped API + protected errors | MockMvc + Testcontainers security |
| R2-E2 | missing operational trace | audit / metrics / rate + resource bounds | reproducible diagnostics |
| R2-F1 | fake/skip evidence accepted | independent evidence checker + CI job | 36/36 real cases |
| **M3 GATE** | any P0, fail, skipped, missing reviewer => HOLD | technical S2, one-SHA CI, separate finance/ops gates | release record with explicit states |

По каждому шагу Codex обязан: сверить текущий HEAD, привести diff по Java/SQL, предоставить команду и реальные тесты + counts, проверить отсутствие изменений main, не создавать веток, не менять старые миграции/approved facts. Если в текущей ветке есть незавершённый R1-X6 CI, он становится предварительным blocker до совместной регрессии; техническое ТЗ не должно объявлять его автоматически GREEN.

## 10. Проверка качества ТЗ и открытые проектные решения

**Зафиксированные решения:** один existing executor; planner без compute; immutable plan и result; S2/ACTUAL permit не следует из source approval; изолированные REEVALUATION; type-safe DAG; CORE_PILOT_V1 PARTIAL/BLOCKED; only SYNTHETIC first vertical slice; no production switch; same-SHA evidence.

**Обязательные ADR до кода:** ADR-R2-01 source/metric exactness and source population reconciliation; ADR-R2-02 canonical plan key and re-evaluation SQL upgrade; ADR-R2-03 ACTUAL execution permit authority and revocation races; ADR-R2-04 SQL typed dependency propagation and legacy backfill; ADR-R2-05 child report side effects/idempotency under stale worker; ADR-R2-06 CORE readiness & external ABAC release limits.

**Зоны, где нельзя выдумать текущее состояние:** независимые S2 decisions, production resource SLA, поле/период ABAC completeness, signature infrastructure, data licensing, actual source qualification и deployment runbook. До подтверждения отмечать BLOCKED/PENDING; нельзя автоматически выбирать более мягкое правило.

**Статус этого документа:** реализационное ТЗ составлено по изученным исходникам, не является кодовой реализацией R2 и не заменяет внешнее независимое S2 Review. Финальные выпускные значения не могут быть присвоены текстом спецификации.


## Нормативное дополнение R2 (2026-11-17): MethodModule / SQL + JAVA / Immutable Results

**Принятое целевое решение:** [ADR-R2-05 — One MethodModule / Dual Engine / Immutable Results](ADR-R2-05-METHOD-MODULE-DUAL-ENGINE-IMMUTABLE-RESULTS.md).

1. Один самостоятельно версионируемый MethodModule для каждого исполняемого варианта бизнес-методики; свои входы, алгоритм, результат, финансовые golden-тесты.
2. Один orchestrator поверх существующих MethodVariantRegistry, MethodApplicabilityEvaluator, MethodTaskAdapter, SingleExecutorScheduler, outbox, ExecutionPermit и CORE; отдельный scheduler/outbox запрещён.
3. Два строго закреплённых в descriptor механизма вычислений: SQL (версионированный параметризованный PostgreSQL 16) и JAVA (BigDecimal, инвестиционные и сценарные модели). Никакого клиентского исполняемого SQL.
4. Единое append-only immutable result store: уникальный логический ключ, исходная ревизия, tenant, версия реализации, fingerprint входов и правил, статус расчёта отдельно от методической квалификации.
5. Разработка через M0 ADR и RED, M1 Module SPI/bridges, M2 SQL pilots, M3 Java pilots, M4 immutable store, M5 orchestrator integration, M6 PostgreSQL Testcontainers и независимая S2; существующие M1/M2/M3 safety gates R2 сохраняются.
6. Источники из X6 — CONFIRMED_UNQUALIFIED_DRAFT — не становятся approved FactSet; ACTUAL запрещён без отдельного permit. Release HOLD сохраняется.

При противоречии более строгие ограничения безопасности из исходного R2 и ADR-R2-05 сохраняются; целевые интерфейсы и таблицы не являются утверждением о реализации.
