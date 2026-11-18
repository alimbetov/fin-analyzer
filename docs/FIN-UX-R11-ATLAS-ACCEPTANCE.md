# FIN UX-R1.1 + Atlas R0 — Browser Functional Qualification and Spatial Explorer

**Decision date:** 2026-11-18
**Working branch:** `feat/fin-form-library-standalone`
**Qualified source commit:** `b8eb89304f557b5ecb5dc9e4b26ff8b217adbb00`
**CI status for this exact code SHA:** **GREEN**.

## Verified CI evidence

| Workflow | Run | Outcome |
|---|---|---|
| React / Java 21 / H2 / PostgreSQL 16 regression | [44360170404](https://github.com/alimbetov/fin-analyzer/actions/runs/44360170404) | SUCCESS |
| Real Chromium / React / Spring Boot / PostgreSQL 16 | [44360170450](https://github.com/alimbetov/fin-analyzer/actions/runs/44360170450) | SUCCESS; 6/6 passed |

The Chromium run starts actual Spring Boot against a *disposable* PostgreSQL 16
service and runs the real Vite/React application using the pinned official
Playwright Chromium image `mcr.microsoft.com/playwright:v1.56.1-noble`.
The browser test suite does **not** mock /api/v1 responses.

The workflow uploads the artifact `fin-ux-r11-browser-evidence`, containing
Playwright HTML/JUnit output and source-backed desktop/mobile screenshots.

### Test evidence and requirements

| ID | Test | Result |
|---|---|---|
| BROWSER-01 | BANK_FINANCING → NET_PROFIT → CFO-01 → F02; actual financial POST; PostgreSQL-backed read-back; revision 1, content SHA, exact field values; open revision again; return to exact Flow branch | PASS |
| BROWSER-02 | Unsaved input: attempt switching from F02 to F03 through Flow, dismiss confirmation, preserve original input and form | PASS |
| BROWSER-03 | Keyboard Enter/Space for goal/KPI selection, collapse/expand and open source form | PASS |
| BROWSER-04 | Width 390px, no document horizontal overflow, reduced-motion CSS | PASS |
| ATLAS-01 | Flow → Atlas uses same BANK_FINANCING/NET_PROFIT/CFO-01 dependencies; open exact source F02; return to Atlas without losing selection | PASS |
| ATLAS-02 | Keyboard-only Atlas source navigation; width 390px; reduced-motion fallback | PASS |

These tests are **functional Chromium CI tests**, not a manual macOS,
Safari or external-accessibility certification. They do not prove the
production readiness of the full 166-form catalog.

## Implementation architecture

### Single graph

- The same typed pure function `frontend/src/decisionTree.ts`
  constructs the tree for both Flow and Atlas.
- The underlying backend read-only source remains the original
  `IndicatorNavigatorCatalog` (seven questions, 42 candidate analytical
  models, 46 analytical output indicators, 166 forms).
- Atlas obtains the exact `DecisionTree` instance selected by Flow; it
  does **not** duplicate KPI/form dependency data.
- Model-input edges are explicitly `MODEL_INPUT_CANDIDATE`.
- Goal-level recommendations are separately marked
  `GOAL_RECOMMENDATION_CANDIDATE`. Recommendations are not advertised
  as mandatory model inputs.
- No view authorizes calculation, S2 approval, or writes in `fin_intake`
  by the act of navigating.

### User interface

- `FinancialGoalsNavigator.tsx`: toggle Flow / Atlas 2.5D, preserving
  selected question and metric.
- `FinancialAtlas.tsx`: responsive spatial columns from question
  through indicator and model to source forms. Uses accessible native
  DOM controls and subtle CSS perspective/depth.
- `App.tsx`: preserves exact question/metric/model/form context while
  switching to FIN Ledger, returns to the previous graph view.
- `styles.css`: mobile single-column fallback, scrollable wide map
  on desktop when necessary; respects `prefers-reduced-motion`.
- No Three.js or WebGL dependency for Atlas; FIN Orbit remains optional.

## Remaining gates / scope boundaries

1. Manual UX check on target MacBook/Windows and Safari/Firefox, including
   visual inspection of screenshots, focus management and screen reader flow.
2. Deep links / restoring decision selection across full browser refresh.
3. Full evidence upload for 12 document forms and approved typed contracts
   for the remaining candidate-only catalog forms.
4. S2 approval of descriptions, translations, formula/metric semantics,
   currency precision, method variants and approved calculation artifacts.
5. Production IAM/ABAC for project-level readiness, sharing, bank/investor
   links and FIN Cockpit results.
6. Performance profiling against deep graphs and large source catalogs;
   optional virtualized graph if requirements grow.
7. Real financial reports must never be used in the no-IAM localhost demo.

**Current release decision:** FIN Flow + Atlas 2.5D UAT functional acceptance
**GREEN on the qualified SHA**. Full commercial/prod release remains **HOLD**
until contract governance, IAM and calculation qualification are complete.
