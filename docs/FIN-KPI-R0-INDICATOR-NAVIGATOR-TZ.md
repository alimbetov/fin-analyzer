# FIN-KPI-R0 — Навигатор показателей, финансовых решений и форм

**Статус:** ТЗ/архитектурный дизайн, НЕ IMPLEMENTED.
**Ветка:** `feat/fin-form-library-standalone`.
**Принцип:** от вопроса пользователя к показателю → требованиям к данным →
отчётным формам → расчёту/интерпретации. Не превращать каталог в
непроверенный финансовый совет или исполняемую методику.

## 1. Фактический baseline

Источник — текущие TSV каталога:
- `use-case-catalog-r10.tsv`: 7 задач, все `CANDIDATE_NOT_S2`.
- `analytics-model-catalog.tsv`: 42 модели, 37 `DESIGN_ONLY`,
  5 `CALC_PILOT`; 46 уникальных выходных показателей.
- `use-case-form-map-r10.tsv`: 101 отношение use case → form
  (34 CORE, 46 SUPPORTING, 21 EVIDENCE), 53 уникальных формы.
- У моделей 47 уникальных входных форм; union с прямыми связями — 71
  уникальная форма. Оставшиеся 95 из 166 пока не связаны
  с пользовательскими сценариями или модельными входами.
- `lib_metric_definition`: ожидается 840 метрик —
  794 `SOURCE_FIELD` в каталоге и 46 `ANALYTICAL_OUTPUT`.
  Отдельно `F02.netProfit` требует решения по роли
  `FORM_DERIVED_CANDIDATE` против каталожного `SOURCE_FIELD`.
- Поля и метрики пока `CATALOG_UNQUALIFIED`.
  Эти цифры — исходное описание, не результат выполнения алгоритмов.

## 2. Три несмешиваемых типа отношений

**REQUIRES_DATA** (техническая зависимость): конкретный расчёт требует
метрику, поле, период, единицу и/или форму. Подтверждается
version-pinned MethodVariant/Field & Metric Contract.

**HELPS_DECISION** (прикладная значимость): показатель используется для
финансового решения. Описание должно отвечать «что показывает»,
«что изменится в интерпретации», «ограничения» и «когда неприменим».
Не утверждает причинно-следственную связь.

**BUSINESS_IMPACT** (условная экономическая зависимость):
`increase/decrease/nonmonotonic/context-dependent`, с обязательной
областью допущений, подписью ревьюера и объяснением.
Например, рост выручки не гарантирует рост прибыли, если выросли
себестоимость, возвраты или расходы.

Ни одно из этих отношений НЕ выдаёт права на вычисление.

## 3. Пользовательские сценарии

Основная точка входа: **«Что хотите узнать?»**, далее:
1. Выбор цели/роли: предприниматель, экономист, CFO, банк, инвестор.
2. Список релевантных выходных показателей с простым объяснением,
   сроком, единицами, бизнес-вопросом, ограничениями и статусом методики.
3. Карточка показателя: **зачем нужен → как влияет на решение → откуда
   берутся данные → какие формы нужны → чего не хватает → что делать**.
4. Разделение CORE / SUPPORTING / EVIDENCE; обязательные поля
   показываются при наличии reviewed contract, иначе помечаются как candidate.
5. Проектная готовность только после проверки прав на организацию и проект:
   найденная ревизия/версия, период, валюта, полнота, устаревание.
6. «Заполнить форму» открывает существующий FIN Form Studio
   с точным `formCode`, не обходит его собственный fail-closed guard.
7. Рассчитать — только по FIN-BE-R2 approved/immutable methodology,
   не по одному статусу `CALC_PILOT`. Результат хранит
   source factset, hash, версии входов и алгоритма, включая PlanPreview.

Примеры из реального source graph:

| Вопрос пользователя | Индикатор | Модель | Формы-источники | Источник статуса |
|---|---|---|---|---|
| Хватит ли ликвидности на ближайшие 13 недель? | WEEKLY_CASH_BALANCE | CFO-02 | DF-CSH-01, DF-CSH-02, DF-CSH-03, P03 | DESIGN_ONLY |
| Выгоден ли инвестиционный проект? | PROJECT_NPV | INV-05 | P07, P03, P05 | CALC_PILOT |
| Каков цикл оборотного капитала? | CASH_CONVERSION_CYCLE | CFO-05 | C01, C02, C03, P06 | DESIGN_ONLY |
| Как проверить согласованность баланса, прибыли и денежных потоков? | NET_PROFIT, CASH_END, BALANCE_CHECK | CFO-01 | F01, F02, F03, P01, P02, P03 | CALC_PILOT |
| Какие затраты заложены в смете? | BOQ_DIRECT_COST | CST-04 | SF-F02, DF-OPS-02 | CALC_PILOT |

Выше — только source mappings. Отсутствие утверждённой формулы,
обязательного дисконта для NPV, методики или первичных данных
является причиной BLOCKED, а не поводом показать выдуманную цифру.

## 4. Модель данных (надстройка, не вторая система метрик)

Повторно используем:
- `fin_lib.lib_metric_definition` как единственный реестр metricCode;
- существующие `FormDefinition/FormVersion/FormField` и
  версионированный контракт/хэши;
- существующие use-case и analytics-model TSV как источник **candidate**
  relationships до последующей миграции в reviewed SQL;
- FIN-BE-R2 методики и HTAP как источник подтверждённого расчёта.

После утверждения ADR создаём в `fin_lib`:
- `lib_indicator_explanation_version`: metricCode, locale,
  `shortTitle`, `plainLanguageMeaning`, `whyItMatters`,
  `healthyInterpretationCaveat`, `typicalUses`, reviewState,
  contentSha256, author, reviewer, effectiveFrom.
- `lib_indicator_requirement_version`: outputMetricCode,
  methodVariantVersion/sha, source metric/field/formVersion,
  requirement role (REQUIRED/SUPPORTING/EVIDENCE), dimension grain,
  currency/period/coverage requirements, sourceSha256.
- `lib_indicator_impact_version`: fromMetric/toMetric,
  relationship classification, condition expression as restricted
  declarative contract (never arbitrary SQL/Java), sign/ambiguity,
  reviewState, source reference, contentSha256.
- `lib_goal_indicator_link`: existing useCaseCode → outputMetricCode,
  goal role and rank; no duplicate KPI dictionary.
- Optional cacheable, read-only search projection for localized labels
  and graph traversal; source of truth remains immutable qualified records.

**Never infer a numeric effect** from the existence of a dependency edge.
Never mutate historical approved contracts. Any meaningful change yields
a new immutable revision/hash, requires review and may invalidate plans.

## 5. Server contracts

Read-only public **catalog metadata**:
- `GET /api/v1/indicator-catalog/goals?locale=ru`
- `GET /api/v1/indicator-catalog/goals/{code}`
- `GET /api/v1/indicator-catalog/indicators?query=&category=&status=&page=&size=`
- `GET /api/v1/indicator-catalog/indicators/{metricCode}`
- `GET /api/v1/indicator-catalog/indicators/{metricCode}/dependency-graph`
- `GET /api/v1/indicator-catalog/forms/{formCode}/used-by`

**Private project-specific views** (NOT exposed until IAM/ABAC exists):
- `GET /api/v1/projects/{projectId}/indicator-readiness/{metricCode}`
- `GET /api/v1/projects/{projectId}/indicator-calculation-plan/{metricCode}`

Every response exposes `qualificationState`, `sourceVersion`, `reviewState`,
`missingRequirements[]`, `blockingReasons[]`, and at most a list of
available/approved methods. Never return computed values for design-only models.

Versioned Caffeine cache is for non-personal reference metadata only.
Invalidate on catalog/translation/approval/revocation changes. Never cache
a project authorization decision across users or tenants.

## 6. Readiness state machine

Keep separate dimensions:
- **CATALOGUE**: is indicator/form metadata present?
- **CONTRACT**: typed and approved per exact version, metric semantics,
  units, requiredness, period/grain, dictionaries?
- **DATA**: does the authorized project contain compatible qualified
  `fin_intake` revisions/FactSet with period and currency coverage?
- **METHOD**: approved algorithm, params, artifact SHA and financial S2 binding?
- **EXECUTION**: immutable result actually computed and read back?

UI labels:
`DESCRIPTION_ONLY`, `SCHEMA_CANDIDATE`, `INPUTS_MISSING`,
`DATA_UNQUALIFIED`, `METHOD_UNQUALIFIED`,
`READY_TO_CALCULATE`, `RESULT_VERIFIED`.
Show explicit missing requirements rather than an unexplained percentage.
`READY_TO_CALCULATE` requires all gates, including authorization.

## 7. Execution order and tests

**KPI-R0.A — Graph of current catalog (read-only):**
7 goal cards, 42 models, 46 outputs, 840 existing metrics, 166 form
identities and 101 direct goal-form edges; unit test all FK codes and
nonempty mappings; show 95 unlinked forms as a measured gap.

**KPI-R0.B — Educational explanations (draft):**
4-language cards with why/what/limitations, one metric per version.
Explicit source/review labels. Never auto-approve generated text.

**KPI-R1 — Versioned, independently reviewed mappings:**
semantic types, formula and required inputs, units, source fields,
period/currency, DAG cycle and collision rejection, SHA, moderation.

**KPI-R2 — Authorized project readiness:**
join to project-bound `fin_intake` and qualified FactSet with exact
revision/version/permissions and stale-plan invalidation.

**KPI-R3 — FIN-BE-R2/HTAP calculation orchestration:**
only qualified method, admission, immutable binding and artifact,
read-back and stable error codes.

**KPI-R4 — Browser E2E and user research:**
non-financial user can find a business question and required form
without knowing form codes; explain every blockage and demonstrate
source lineage, replay, tenant isolation and revocation.

### Mandatory acceptance
- 7 goals discoverable, each bound to the known source model set.
- All 46 output codes resolve to existing metrics, without duplicates.
- Every form in the catalog has a "used by" path or an explicit
  `NOT_YET_MAPPED` explanation (initial gap: 95 forms).
- No action offers calculations for `DESIGN_ONLY` or unqualified pilots.
- All localized captions have verifiable moderation status.
- Graph traversal bounded, cycle-safe, paginated for lists and cached
  only when no tenant context is involved.
- Read-only endpoints do not write to `fin_intake`.
- R3 PostgreSQL GREEN and R16 field qualification are prerequisite
  for claiming the downstream result as financial proof.

**Implementation scope of this document:** contract proposal only;
no controller/schema migration/frontend is claimed IMPLEMENTED.
