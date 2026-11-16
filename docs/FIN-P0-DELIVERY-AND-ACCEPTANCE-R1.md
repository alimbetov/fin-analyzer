# FIN P0 — исполняемый план, зависимости и критерии приёмки v1

**Дата:** 2026-11-16. **Ветка:** `feat/fin-r0-r1a`. **Статус:** `DELIVERY_PLAN_BASELINED / RELEASE_HOLD`.
**Источник состояния:** проверены существующие файлы Java/Spring, React, Flyway, `FIN CI`, `CAT-A2-PILOT-FIELD-REVIEW.tsv`, `methodology-catalog-r1.json` и документы V5/R6. Это **не** независимый S2 и не отчёт о выполнении тестов.

## 0. Цель: один реальный вертикальный сценарий

Юридическое лицо / проект с серверным `organizationId` и `projectId` → пользователь скачивает версионированный XLSX или вводит те же поля в React → сервер проверяет паспорт, период, валюту, масштаб, каждую ячейку и полномочия → создаёт immutable PreviewSnapshot → пользователь подтверждает → независимый reviewer утверждает пригодность исходного факта → FactSetRevision с исходным хешем публикации → `MethodGate && FactGate` → `SingleExecutor` → immutable `CORE` с полями/источниками/причинами ограничений → React → reopen той же версии.

**Недопустимо:** выдать локальный `CONFIRMED` F02 за `APPROVED_SOURCE`, синтетический test flag за S2, клиентский `approved=true` за серверный permit, или `CORE_PILOT_V1` за банковское заключение. `ACTUAL` не публикуется/не исполняется при неполном MethodGate или FactGate. `FIN_EXECUTION_ENABLED=false` вне изолированного acceptance-контекста.

## 1. Два независимых потока и критический путь

- **Поток A, инженерная готовность:** P0.1 CI → P0.3 server Intake → P0.4 approved-source execution → P0.7 user-visible CORE.
- **Поток B, экономическая пригодность:** P0.2 формальная методология → P0.4 методический допуск → P0.5 CON-01/02 и P0.6 PRO-03/CASH-01 по мере подготовки доверенных источников.
- P0.2 можно начинать параллельно P0.1/P0.3; P0.3 может разрабатывать/хранить DRAFT до независимого S2, но не публиковать `ACTUAL`.
- P0.7 **не должен ждать** P0.5/6: первые проверенные F01/F02/F03 методы формируют CORE; неподключённые торговые/производственные методы не мешают явно помеченному `PARTIAL`. До production economic report необходим S2 для *тех вариантов*, что реально выводятся.
- Все 7 этапов имеют machine-readable критерии в `docs/FIN-P0-ACCEPTANCE-MATRIX-V1.json`; `scripts/fin-p0-contract-check.mjs` проверяет структурные и исходные инварианты и `FIN CI` должен вызывать его. Эта проверка сама по себе не проходит S2, Testcontainers или пользовательский E2E.

## 2. Исполнение P0.1 — один проверяемый CI SHA

**Найдено:** `.github/workflows/ci.yml` уже имеет `verify` (Node 22, Vitest, 154-form/794-field XLSX template manifest, Linux Playwright), `backend-verification` (JDK 21/Maven/JUnit и real PostgreSQL Testcontainers), `desktop-macos` (Chromium/WebKit), `acceptance-gate` (все три `needs` должны быть `success`). Исторические GREEN — не доказательство для изменённого SHA.

**Доделать:**
1. Запускать контролируемую статическую проверку P0-контракта и выгружать отдельный JSON evidence artifact; нельзя считать его автоматическим доказательством CI GREEN.
2. Для одного **push SHA** записать workflow run ID/URL, exact `GITHUB_SHA`, статусы 4 jobs, число тестов/ошибок/skipped из XML, артефакты. PR synthetic merge SHA не подменяет push source SHA.
3. При реальном RED — исправить указанную ошибку, затем повторить весь gate на *новом* SHA; нельзя складывать SUCCESS с двух ревизий. Не отключать медленные Testcontainers/Playwright для «зелёного» статуса.
4. Привязать required check `FIN CI / acceptance-gate` к PR/защите ветки, если GitHub branch protection поддерживается. Дополнительно сохранить stdout/trace failing tests и успешный отчет.
5. Отдельно: ручной MacBook UX и финансовое S2 никогда не выдавать за выполненные только на основании автоматических jobs.

**RED/negative:** отсутствующий Surefire class/zero tests/skip, Node FAIL, macOS skipped, dependency fail, иной SHA, неверная структура proof JSON, отсутствующий отчет => `P0.1 BLOCKED`.

**DONE:** ссылки на один green run, все четыре `success`, артефакты доступны и соответствуют SHA; S2 и UAT фиксируются в собственных независимых gates.

## 3. Исполнение P0.2 — 92 поля это 43 + 49, а не только F01–F03/DF-CSH

Фактический `docs/CAT-A2-PILOT-FIELD-REVIEW.tsv`: **18 форм, 92 уникальные пары form/field**.
- **Первая волна, финансовое ядро (43):** F01=4, F02=4, F03=5, DF-CSH-01…06=30.
- **Вторая волна, отрасли (49):** SF-G01/G02/G03, SF-M01/M02, SF-S01, SF-C01/C02/C03.
- Код `CatA3MethodFactAdmission` выполняет раннюю инспекцию публикации и по определению возвращает `METHOD_S2_HOLD`; он **не** является неизменяемым независимым реестром MethodGate.

**На каждое поле:** `formCode, formVersion, fieldCode, fieldType, economicMeaning, unit, currency, scale, sign, valueCoverage, stockFlow, timeCutOff, requiredness, nullVsZero, sourcePriority, reconciliation, precisionPolicy, regionApplicability, reviewerDecisionId`. Семантика валюты/масштаба и признания — не вопрос UI. 
**На каждый вариант формулы:** `methodVariant, version, AST/definition, inputs, scope, periods, denominators, rounding, approvedGoldenFixtures, negativeFixtures, independentReviewerId, approvedAt, revokedAt, evidenceDigest`.

**Доделать:** immutable `MethodApproval` и `FieldSemanticApproval` в PostgreSQL; reviewer независим от автора/загрузчика; версии не меняются задним числом; revoke/expiry прекращают новые расчёты; прежние отчёты сохраняют pinned review и становятся `STALE/REVOKED_REFERENCE` по политике. Первым квалифицировать F01/F02/F03 и cash-формы/варианты, нужные для P0.7, затем 49 SF. Не повышать 92 поля одним batch approval.

**RED/negative:** другой formVersion, unknown field, stale approval, self-review, reviewer missing, revoked/expired, cross-tenant, mixed ACTUAL/SYNTHETIC, invalid stock/flow, missing period/currency/scale, duplicated revenue or cash row => denial.

**DONE:** 43/43 отдельных формальных review decisions + подписанные golden на используемые варианты первой волны; 49/49 во второй. Экономическая независимость подтверждается реальными решениями reviewer; CI лишь проверяет их контракт.

## 4. Исполнение P0.3 — server React/Excel Intake

**Найдено:** локальные F01 и отраслевые Excel; R6 покрывает локальный маршрут 122 typed + 32 specialized candidates + 12 evidence/document forms. `FormDraftRevisionV5Controller` уже содержит защищённые project-scoped endpoints `createProject`, `F02/previews`, `confirm`, `current`, `freshness`, `history`, `uploads`, но пилот принимает **manual F02** и `CONFIRMED` — ещё не `PUBLISHED_FACT_SET`.

**Разбить:**
- **3A Scope:** project UUID и org/economicEntity/businessScope server-owned, JWT subject → ABAC (read/submit/review отдельно). `projectCode`/company label в Excel не заменяет identity boundary.
- **3B Versioned schema:** один field code/period/currency/scale contract для ручного ввода, FIN-FORM-XLSX-V2 и backend parser. Поддержать F01 AS_OF, F02/F03 flow, DF-CSH по применимости.
- **3C Secure upload:** лимиты raw/compressed/uncompressed, MIME/magic, запрет formulas/macros/embedded links по правилу, quarantined raw hash; безопасный parser Java Apache POI, не доверять client normalization. Отдельный evidence-only document workflow.
- **3D Preview:** invariant values, row IDs, missing/zero distinctions, cell coordinate, reconciliations, content fingerprint; однозначный immutable PreviewSnapshot и confirmation digest.
- **3E Revision:** confirm с optimistic concurrency/If-Match, idempotency key, reason; старую ревизию нельзя удалить/заменить. Отдельная reviewer approval и `FactSetPublicationService`. Повторная загрузка создаёт diff и может инвалидировать dependent snapshot.
- **3F UI:** upload → preview (без изменения editor) → apply draft → confirm → reviewer status → published revision → reload, RU/KK/EN/ZH только при проверенных текстах.

**RED/negative:** форму F02 в другом project/month/org, "2026-13", currency KZT/USD mixed, locale race, formula cell, ZIP bomb, duplicate row, replay digest, stale expectedRevision, conflicting parallel users, malformed file after current revision, missing approval => previous published data unchanged.

**DONE:** один end-to-end F01/F02/F03 + DF-CSH subset через React/Excel, backend PostgreSQL, два JWT principals, независимая ревизия, проверенный repeat upload, никакого неавторизованного ACTUAL.

## 5. Исполнение P0.4 — SingleExecutor / MethodGate + FactGate

**Найдено:** `SingleExecutorScheduler` + outbox/dependencies/retry/leases, `CatA3MethodFactAdmission`, `CompositeCoreReportPublisher`, несколько FIN/CON adapters. Scheduler default `fin.execution.enabled:false`; отдельные CON adapters default `fin.methods.con.enabled:false`. Не переименовывать existing Java services и не плодить второй планировщик.

**Доделать:** signed S2 method version lookup, reviewer-approved canonical fact population, pinned `AnalysisProfile` + `MethodologyVariantVersion` + `InputSnapshotHash` into immutable `AnalysisManifest`. `METHOD_S2_HOLD`/missing scope/unknown source → no task. Разделить: `INELIGIBLE`, `BLOCKED_MISSING`, `BLOCKED_CONFLICT`, `COMPUTED`, `FAILED_FINAL` и snapshot `CORE.PARTIAL/BLOCKED`.

**RED/negative:** cross-tenant output read, stale worker after lease expiry, crash before/after commit, two workers duplicate task, poison event retry/DLQ, invalid dependency, unordered revision, changed source hash, revoked S2, PII leakage, `SYNTHETIC` promoted to `ACTUAL`.

**DONE:** real PostgreSQL Testcontainers with 2 workers and 2 tenants, deterministic golden for FIN-01/FIN-02/FIN-04 accepted variants, exact task-to-FactSet lineage; verified fail-closed when S2 missing. Production flag stays OFF until full release approval.

## 6. Исполнение P0.5 — CON-01 Pareto / CON-02 ABC

**Найдено:** `ReviewedSkuPopulationLoader`, `QualifiedSkuParetoAdapter`, `QualifiedSkuAbcAdapter`, `ConcentrationAnalysisEngine` plus SKU review/certificate tests. This is not yet universally qualified.

**Input source of truth:** audited SKU population, org/legalEntity/scope, period, currency, recognized sale vs return/cancel, stable transaction/line key, unit, snapshot hash, row count and net-total certificate. Group-by must never sum a subtotals row with its children, silently drop returns or count an identical sales event twice. Selection of one certified month, not silent latest.

**Goldens:** SKU A/B/C/D=500/300/150/50, total=1000, Pareto shares 50/30/15/5 and cumulative 50/80/95/100%; policy `ABC_BOUNDARY_V1` gives A/A/B/C. Stable ties, precise unrounded cumulative cutoffs and invalid denominator tests.

**RED/negative:** return recorded twice, negative net SKU without approved handling, incomplete reviewed manifest, two overlapping certificates, mixed currencies/months, duplicate transaction, unauthorized lookup, cancellation conflict, tie/boundary errors, changed published fact hash.

**DONE:** exact PostgreSQL source certificate + both methods through `SingleExecutor` with pinned policy, method qualification and CORE snapshot. Sandbox flag does not turn family `RELEASE_QUALIFIED`.

## 7. Исполнение P0.6 — PRO-03 and CASH-01

**PRO-03:** contribution = recognized net revenue − **approved variable costs**; contribution margin % = contribution / net revenue ×100. COGS is not automatically variable cost. Positive/zero/negative contribution, cost taxonomy, partial cancellations/refunds, fixed allocation and scope consistency. Golden 1000−600 = 400; margin=40%. Missing variable-cost classification → `NOT_COMPUTABLE`.

**CASH-01:** `CCC = DSO + DIO − DPO`; DSO = average AR / credit revenue ×days; DIO = average inventory / COGS ×days; DPO = average AP / **approved credit purchases** ×days. Explicit 360-day policy or calendar-derived day count; do not silently replace purchases with COGS. Golden average AR=120, revenue=720 → 60d; inventory=90, COGS=540 → 60d; AP=60, purchases=540 → 40d; CCC=80d. Average balances require real compatible snapshot dates. No denominator → `NOT_COMPUTABLE`, not infinity.

**DONE:** independent economic goldens and negative fixtures; versioned source mapping/approval; complete PostgreSQL population; React/CORE trace; S2 separately signed for both variants. Independent unit tests can be started now; production execution waits for P0.4.

## 8. Исполнение P0.7 — first trustworthy CORE report

**Найдено:** `CompositeCoreReportPublisher` already writes immutable `CORE_PILOT_V1` for committed tasks with status `BLOCKED` if no successful methods, `PARTIAL` for a partly computed run. It does not expose a financially qualified user report or external publication. `TrustedFinanceWorkspace` exists as separate React pilot.

**Minimum sections:** project/tenant/period, trusted FactSet + method review versions, 3-statement reconciliations, qualified trend/liquidity indicators, observed movements, explicit missing evidence, `OBSERVATION` vs `HYPOTHESIS`, recommended *review/question* not automatic action, formula/field-to-source trace, scenario marked `SIMULATED_ONLY`, risk/materiality only with signed policy, timestamp and revocation status.

**User journey:** same project open → reviewed form → preview/approve/publish → qualified execute → immutable report ID → display in React → reload/reopen report by ID → correct source in new revision → old report pinned / stale notification. RU/KK/EN/ZH numbers/qualifiers identical, professionally reviewed strings only; `TRANSLATION_NOT_READY` otherwise.

**RED/negative:** missing one mandatory form/period, failed/empty run, revoked method, mismatched report hash, tenant A reading tenant B, reviewer missing, stale source, simulated result displayed as actual, unsupported causal text, missing source row, unauthorized export.

**DONE:** at least one real authenticated financial journey to immutable explanatory CORE via PostgreSQL + React and independent S2 sign-off on each displayed ACTUAL method, archived evidence and manual desktop UAT. First CORE may be `PARTIAL` if optional CON/PRO/CASH are not qualified, but must never imply completeness.

## 9. Release status semantics and evidence checklist

| Label | Meaning | Who can promote |
|---|---|---|
| `SPECIFIED` | Tested contract described, not runtime | Engineer |
| `CODED_UNVERIFIED` | Source authored, CI not confirmed | Engineer |
| `ENGINEERING_VERIFIED` | One-SHA automated golden+PostgreSQL+browser pass | QA + traceable CI |
| `METHOD_S2_APPROVED` | Independent method+facts signed, review version pinned | Finance/economic reviewer, **not author** |
| `UAT_ACCEPTED` | Actual user journey and permissions inspected | Product owner / manual QA |
| `RELEASED` | Engineering, method, security, UAT ALL pass | Authorized release owner |

For each sign-off persist `artifactCode, sourceSha, GitHub workflowUrl/runId, environment, testMethod, goldenFixtureId, status, reviewerId/role, reviewTimestamp, expirationOrRevocation, decisionDigest`. A field `approved=true` from API/Excel is never sufficient proof.

**Definition of release:** `P0.1 GREEN && selected P0.2 S2 && P0.3 TRUSTED E2E && P0.4 VERIFIED EXECUTION && P0.7 UAT`. P0.5/6 may follow later as optional verified packs, without inventing their readiness in CORE. `main` merge requires separate approval; current branch remains Draft/HOLD.

## 10. Next engineering actions in order

1. Get actual failing `FIN CI / acceptance-gate` job log for latest **push SHA**; do not patch guessed errors. Keep same-SHA evidence.
2. Independently review first 43 fields and selected FIN-01/02/04 variants with finance reviewer; record sign-offs and no-approval reasons.
3. Server project-scoped Apache POI XLSX V2 for the **one selected F02 monthly flow** (manual path already exists), then F01 AS_OF, F03 period FLOW and DF-CSH. Reuse `FormDraftRevisionV5Service` identity/revision semantics.
4. CAT-A3.1 immutable method approval registry + reviewer segregation + expiry/revocation; require `FactGate` population completeness.
5. Finish CON-01/02 boundary/returns/tenant integration; add PRO-03/CASH-01 pure tests in isolation.
6. Once (1–4) have actual acceptance evidence, wire one approved `FIN` method to `CORE` and complete real React/DB/reopen E2E. Do not defer manual MacBook UAT to release day.

**External approvals unavailable in this work session:** no independent licensed financial reviewer, no production access authority; therefore remain `RELEASE_HOLD` irrespective of code/document commits.
