# FIN UX-R1 — «От вопроса — к нужным данным»

**Status:** IMPLEMENTED IN CODE, CI qualification in progress; Browser E2E not yet qualified.
**Branch:** `feat/fin-form-library-standalone`.
**Prerequisite ADR:** `FIN-UX-ADR-001-SPATIAL-FINANCIAL-EXPERIENCE.md`.

## User-facing flow

1. Start FIN in the Financial Decisions view (not the professional form library).
2. Choose one of **seven** catalog business questions, or search by title/code/KPI.
3. Choose an output metric among the actual model outputs for that question.
4. Inspect the indicator's `meaning`, `helpsDecision`, `limitations`,
   explicit `EDITORIAL_DRAFT_NOT_S2` / `PENDING_TRANSLATION`, unit policy,
   and `CATALOG_UNQUALIFIED` status.
5. Expand/collapse a **model branch** attached to that exact metric. Every
   model edge is sourced from `analytics-model-catalog.tsv` and is labeled
   `MODEL_INPUT_CANDIDATE`.
6. Independently inspect *goal-level* forms which are not inputs of the selected
   metric's model. These originate from `use-case-form-map-r10.tsv` and
   are labeled `GOAL_RECOMMENDATION_CANDIDATE` with their CORE/SUPPORTING/EVIDENCE role.
7. Open a form in FIN Form Studio. The editor retains the existing
   `canEditForm`, server `FieldMetricContractGuard`, immutable report revision,
   read-back verification, and `fin_intake` write semantics.
8. A context banner shows the exact
   `goalCode → metricCode → modelCode? → formCode` lineage, and “К дереву задачи”
   returns to the same selected graph branch. The navigator remains mounted
   while the editor is visible to preserve selection and collapsed nodes.
9. If changing forms would discard unsaved edits or an uncertain server write,
   the existing confirmation guard still applies. Canceling it does **not**
   replace the editor's financial context.

## Contract and provenance

### Source graph
- `IndicatorNavigatorCatalog`: 7 goals, 42 models, 46 unique output metrics,
  166 forms; 101 direct goal→form relationships.
- User-facing source form titles and metric draft labels originate from existing
  `/catalog/navigation` and `/catalog/metrics`; no new KPI registry.
- `indicator-explanations-r1.tsv`: 46 distinct **RU draft editorial**
  descriptions, one per output metric, each with meaning, decision use and caveat.
  Must be independently reviewed; cannot be treated as an approved formula.
- Existing `GET /api/v1/indicator-catalog/indicators?locale=ru|kk|en|zh-Hans`
  now contains `explanation:{meaning,helpsDecision,limitations,reviewStatus,source}`.
  For unsupported localization the text fields are `null` and
  `reviewStatus=PENDING_TRANSLATION`; no silent Russian substitution.
- A source-only `CALC_PILOT` is **not** an approved method or an executable plan.
- The UI never reports project completion percentages or verified outcomes
  without IAM, reviewed contracts, qualified FactSet and FIN-BE-R2.

### Client model
- `frontend/src/decisionTree.ts` constructs metric-specific branches from
  actual `goal.models[].outputMetricCodes`, model input forms, and
  `goal.forms` separately. Wrong metric/model/form combinations return null
  (fail-closed navigation).
- `frontend/src/FinancialGoalsNavigator.tsx` renders accessible buttons with
  `aria-pressed`, `aria-expanded`, short explanation sections, model accordion,
  and linked form nodes.
- `frontend/src/App.tsx` owns the selected form and transient decision context;
  it preserves existing editor draft fields and uses an explicit return button.
- The tree uses responsive 2.5D CSS and honors `prefers-reduced-motion`.
  WebGL/Three.js are **not** prerequisites for this core workflow.

## Tests & gates

| Test | Requirement |
|---|---|
| Java 21/H2 | All 46 indicators expose RU draft explanation; KK keeps null + PENDING |
| REST/H2 | Correct statuses; 7 goals, 42 models, 46 unique outputs, 101 links |
| REST/H2 | Browsing graph does not write any `fin_intake` revisions |
| React unit | Model input P03/P05/P07 for PROJECT_NPV, distinct goal-level advice |
| React unit | Duplicate model inputs removed, stable source role and ordering |
| React unit | Wrong metric, wrong model or unrelated form never produces navigation context |
| React build | Typecheck, unit tests and Vite production build |
| PostgreSQL | Existing `fin_intake` regression and concurrent revision tests unchanged |
| Browser E2E | Open from chosen question → form → save/reopen → return to exact metric/model, with unsaved prompt |
| Accessibility | Keyboard, focus, screen reader, small viewport and reduced-motion manual check |

**Do not confuse successful CI compile/REST qualification with successful
browser UX testing.** The R1 implementation does not provide production
authorization or S2 method qualification.

## Follow-on plan

**UX-R1.1:** Browser-level E2E including saving F02 and ROWSET; accessible
focus return, recovery after refresh and explicit deep links.

**UX-R2:** Real per-project data readiness after IAM/ABAC and S2 reviewed
form/metric contracts (no completion percentage from catalog links).

**UX-R3:** Atlas 2.5D graph reusing the same `DecisionTree` source,
not duplicating financial dependencies.

**UX-R4:** Cockpit with qualified method provenance and immutable results.

**UX-R5:** Optional lazy 3D Orbit with 2D fallback.
