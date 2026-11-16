# FIN-BE-R2 — ТЗ: Оркестрация подключения методик

**Приоритет:** P0. **Статус:** SPECIFIED / NOT ACCEPTED. **Ветка:** `feat/fin-r0-r1a`.  
**Цель:** после подтверждённой версии FactSet автоматически выбрать **только применимые и допустимые** варианты методик, построить dependency DAG, выполнить их однократно логически и зафиксировать immutable CORE.

## 1. Имеющийся фундамент

`FactSetPublicationService` → `fin_exec.fact_publication_outbox` → `SingleExecutorScheduler` (manual `tick()`, scheduled disabled by default) → `MethodTaskAdapter` → `CompositeCoreReportPublisher`. Есть `FOR UPDATE SKIP LOCKED`, lease, fencing, retry, immutable snapshot, `ExecutionContracts` и `CalculationDag`. Это технический фундамент, а не исполнение всех 60 методов. `methodology-catalog-r1.json` содержит 60 семейств, `releaseGate=HOLD`, `releaseQualified=false`. `CatA31QualificationInspector` сейчас не выдаёт execution permit.

## 2. R2-A — Method variant registry/applicability (P0)

Ввести versioned server-owned `MethodVariantDescriptor`: familyCode, variantCode, version, exact required metrics/forms/versions, REQUIRED/OPTIONAL/EVIDENCE_ONLY inputs, sector, scope, reportingBasis, dataSpace, periodGrain/coverage, unit/currency policies, minimum history, source-population constraints, dependencies, assumptionsVersion, qualification evidence, executionPolicy. Нельзя исполнять arbitrary SQL/Java/формулу из непроверенного JSON.

`ApplicabilityDecision` должен выдавать `ELIGIBLE, NOT_APPLICABLE, BLOCKED_MISSING, BLOCKED_CONFLICT, DISABLED_POLICY, UNQUALIFIED` и **объяснимые reasons**. Наличие `formCode` само по себе не достаточно. Метод `ACTUAL` нельзя включать из одного `autoEligible=true`, если методически неквалифицирован.

Связать registry с существующими `MethodTaskAdapter` без создания второго executor. Проверка duplicate variant, unknown family/version, cycles, incompatible stock/flow/ratio/period, source revision hash. Method passports R3 — самостоятельный квалификационный gate.

## 3. R2-B — Deterministic DAG, triggers, idempotency (P0)

- Триггер **только committed FactSetPublication**, не raw upload/preview/draft. Сначала транзакционная outbox запись, затем plan.
- `PlanPreview` — pure Java service: показывает chosen/blocked methods, explicit required source fields, DAG и estimated taskCount; ничего не пишет и не запускает.
- Ключ логического запуска: organization/entity/scope + published factSet revision/hash + method variant/version + execution profile/version + accounting/units policy + assumptions fingerprint. Повторный event/poll/restart не рождает повторный результат.
- DAG учитывает REQUIRED/OPTIONAL/EVIDENCE_ONLY. Пропущенная обязательная зависимость блокирует **только descendants**. Независимые задачи продолжаются; source conflict фиксируется, не замалчивается.
- Изменение одной ревизии/метрики запускает re-evaluation только true dependents. Старый run/snapshot неизменяем; новая политика/версия методики → новый manifest.
- Согласовать persisted SQL status с `ExecutionContracts` без скрытого изменения исторических enum values.

## 4. R2-C — Crash-safe execution/CORE (P0)

Один task claim на PostgreSQL, lease+fencing; не держать транзакцию при долгом вычислении. Stale worker result reject, after-crash recovery, bounded retry/backoff, dead-letter + reason codes. Duplicate outbox planning idempotent. Task terminal failure propagates to required dependents; `PARTIAL/FAILED` корректны. При STOP или unavailable ML CORE остаётся доступным.

Immutable result хранит methodVariantVersion, exact serialized decimals, unit/period, source fact references, implementationBuildSha, assumptions and input fingerprint. Report CORE собирает только допустимые traces с явными limitations. Не допускать `COMPLETE` на основании одного "COMPUTED" без обязательного execution profile. `fin.execution.enabled=false` по умолчанию не менять; тесты используют explicit Java tick.

## 5. Приоритеты вариантов

| Приоритет | Вариант | Gate |
|---|---|---|
| 1 | FIN-01/REV_NET_MOM | Two comparable trusted months; flow policy |
| 2 | FIN-02/REV_CATEGORY_SHARE | Reconciled approved category population; sum equals F02 |
| 3 | FIN-04/CURRENT_RATIO | Reviewed F01 current assets and liabilities, zero-denominator failure |
| 4 | CON-01/PARETO | Approved SKU journal, refunds and cancellations reconciled to F02 |
| 5 | CON-02/ABC | Same-run CON-01, deterministically sorted shares |
| 6 | Synthetic diagnostic method | Published SYNTHETIC sources only; not ACTUAL |

Порядок **технической** интеграции, не автоматический financial qualification. Остальные семейства остаются с explicit pending statuses.

## 6. Проверяемые сценарии

| ID | Испытание | Критерий |
|---|---|---|
| BE-R2-01 | One published revision, repeated delivered event | One logical analysis run |
| BE-R2-02 | Two concurrent schedulers | No duplicated claimed task/result |
| BE-R2-03 | Crash after claim, late worker completion | Fencing rejects stale; new worker completes |
| BE-R2-04 | Missing REQUIRED dependency | Child blocked, independent method runs |
| BE-R2-05 | OPTIONAL dependency/ML unavailable | CORE unchanged, explicit optional status |
| BE-R2-06 | Cycle or unknown dependency | Planner rejects with exact error |
| BE-R2-07 | Changed version/source/assumptions | New manifest and recompute affected branches |
| BE-R2-08 | Incompatible currency/basis/period, duplicate revenue | Block affected calculation, no silent sum |
| BE-R2-09 | 60 unqualified families | No mass promotion or unsanctioned ACTUAL task |
| BE-R2-10 | Default background disabled | No job executes without explicit switch |
| BE-R2-11 | Retry exhaustion, crash after outbox commit | Dead-letter recorded and replay idempotent |
| BE-R2-12 | Repeated CORE generation | Immutable report hash preserved |

**Тестовые опоры:** `SingleExecutorGraphTest`, `ExecutionContractsTest`, `MethodologyCatalogContractTest`, `QualifiedCurrentRatioIntegrationTest`, `QualifiedRevenueSingleExecutorIntegrationTest`, `ReviewedSkuSingleExecutorIntegrationTest`, `FactPublicationIntegrationTest`, `PostgresInvariantTest`.

**DoD:** один Java execution SPI + server registry, applicability proof и versioned DAG, SQL schema upgrade tests, concurrent/crash recovery on real PostgreSQL, immutable CORE, exact same-SHA JUnit/CI evidence. **Не входит:** frontend, IAM/Keycloak, произвольные методики из запросов, безусловный запуск 60 методов и ML.

