# FIN-UX-ADR-001 — От вопроса — к нужным данным

**Status:** ACCEPTED — product concept and target UX architecture (2026-11-18).
**Repository:** `alimbetov/fin-analyzer`. **Working branch:** `feat/fin-form-library-standalone`.
**Scope:** FIN Financial Intelligence Platform, FIN-KPI-R0/R1, FIN Form Studio.
**Decision:** combine Atlas, Flow, Ledger, Cockpit into one product; Orbit is optional.
**Important:** ACCEPTED means **product/architecture direction adopted**. This document
does **not** claim that the full 3D design is implemented, browser-tested or production ready.

## 1. Product promise and UX north star

> **От вопроса — к нужным данным.**

Supporting explanation: **FIN превращает финансовый вопрос в понятный маршрут:
какие показатели нужны, какие формы их содержат, что уже известно и чего не хватает для достоверного расчёта.**

This is a user-facing value proposition, not a claim that all methods are qualified.
It is the acceptance criterion for the entire user journey:
- Users start with natural-language-like **business goals**, not technical form codes.
- Users can understand **why** a metric and a form are needed.
- The UI shows a **branching dependency structure**, not an arbitrary checklist.
- It always identifies the **next useful action** and the reason for every blocker.
- Numeric results are shown only after all qualified method/data/permission gates are met.

Main entry point: **FIN Flow — “От вопроса — к нужным данным”**.
**FIN Atlas** is the spatial discovery view and optional overview of complex graph
relationships. The core Flow must remain usable without any 3D capability.

## 2. Five concepts, one integrated product

| Concept | Product role | Primary user action | Motion / depth |
|---|---|---|---|
| **FIN Flow** | DEFAULT primary user journey and guided dependency tree | Choose goal → indicator → data/form → report editor | Branch expansion, progressive disclosure, 2.5D hover and card layering |
| **FIN Atlas** | Spatial overview of the financial dependency graph | Explore indicator relationships and drill down into Flow | Node motion, highlighted graph edges, gentle parallax |
| **FIN Ledger** | High-density financial input, source evidence, immutable revisions | Edit and save a pinned version of form Fxx/DFxx/SFxx | Minimal motion; spreadsheet UX dominates effects |
| **FIN Cockpit** | Verified results, decision brief, history, provenance, scenarios | Examine verified calculations and their input lineage | Subtle progressive chart reveal and layered analytics panels |
| **FIN Orbit** | Optional immersive 3D explorer / demo mode | Orbit/zoom through metric graph and select a node | Three-dimensional scene, orbit movement with accessible 2D fallback |

**Ordering:** Flow first for usability, Atlas as visually expressive companion,
Ledger for precise data work, Cockpit for decision evidence, Orbit only when
its added depth justifies the performance and accessibility cost.

## 3. Core user story

**Given:** “Стоит ли инвестировать в проект?”

1. Select a business question in Flow (e.g. investment appraisal).
2. See linked output metric `PROJECT_NPV` (and explanation of economic meaning).
3. Expand dependencies for candidate method `INV-05`: forms `P07` (investment
   financial model), `P03` (cash-flow forecast), `P05` (CAPEX plan).
4. Separate **CORE**, **SUPPORTING**, **EVIDENCE** for each task and identify
   which fields/periods/units are required by *approved* versioned contracts.
5. Display branch states: `NOT_PROVIDED`, `DRAFT`, `DATA_UNQUALIFIED`,
   `METHOD_UNQUALIFIED`, `READY`, or `RESULT_VERIFIED`, with reason codes.
6. On click, navigate to the existing form editor with exact `formCode`;
   do not bypass `canEditForm` or backend `FieldMetricContractGuard`.
7. Save immutable form revision to `fin_intake`, read back exact values,
   show timestamp, revision N, form version and content SHA.
8. When method and financial S2 qualified, provide FIN-BE-R2
   PlanPreview/Commit with immutable bindings; **until then calculation
   remains unavailable**, including for `CALC_PILOT` and `DESIGN_ONLY`.
9. Return to Flow automatically preserving graph context (not hidden draft state).

The **branching tree is an information architecture**, not a claim of
causal economic influence. Source requirements, business relevance and
conditional impact are distinct relation types with provenance.

## 4. Navigation and IA

- `/decisions` — default Flow question-first screen.
- `/atlas` — dependency explorer; same graph nodes and IDs as Flow.
- `/forms` — professional library of all 166 forms.
- `/forms/{formCode}` — editor, exact typed/versioned contract.
- `/projects/{projectId}/results` — Cockpit (only after verified IAM/ABAC).
- `/orbit` — optional 3D experience, never critical navigation.
  
These are **target routes**, not existing route contracts. During the local
R0 transition they may be controlled views in a single React component.

Every node links deterministically to a business goal, metric code, method
variant/version or form code; stable IDs prevent losing context.
Provide breadcrumb and visible **Back to decision tree** button.

## 5. Visual direction and motion guidelines

**Shared visual system:** typography optimized for RU/KK/EN/ZH, meaningful
statuses, clear amounts, semantic color, dark/light theme, consistent iconography.

**Atlas:** dark navy/cyan luminous graph; limit ambient motion to subtle drift.
Connections encode real `REQUIRES_DATA` edges, not visual-only relationships.

**Flow:** quiet light canvas; branching connectors and ordered stages; content
and next action must remain obvious even if animations are disabled.
The guiding sentence “От вопроса — к нужным данным” is the primary hero.
Never force reading 166 form codes to start.

**Ledger:** sharp grid typography, sticky context and columns, keyboard
navigation, Excel paste, validation near fields, financial accuracy and
revision history. *Do not animate numbers while a user is typing or pasting.*

**Cockpit:** enterprise-level analytical density, clearly separated
`SOURCE`, `DERIVED`, `UNQUALIFIED` and `VERIFIED` panels.
Never show decorative sample data as real financial results.

**Orbit:** lazy-load `Three.js` / `React Three Fiber`; support keyboard,
zoom, focus, reduced-motion and 2D graph alternative. Avoid WebGL being
required for any financial workflow.

**Animation budgets (initial UX targets, not measured performance claims):**
- Enter/exit card reveal ~160–260 ms, branch expand ~180–300 ms.
- Persistent parallax/ambient animation disabled under
  `prefers-reduced-motion: reduce`.
- No animation during save/read-back/precision-sensitive entry.
- Preserve scroll/focus/selection; no flashing or spatial disorientation.
- 60 fps *target* in normal desktop UI; verify with device profiling.
- Progressive enhancement for low-power/mobile and WebGL failures.

## 6. Data quality and fail-closed semantics

Known source facts at decision date:
- 166 catalog forms; 840 metrics; 42 candidate analytics models, 46 output metrics.
- 7 candidate business tasks, 101 direct goal-form links.
- 37 models `DESIGN_ONLY`, 5 `CALC_PILOT`.
- Only 71 of 166 forms have goal/model navigation links; 95 are
  `NOT_YET_MAPPED` — visible as professional forms, not silently hidden.
- These are **catalog relations**, not 166 approved financial contracts.
- Real form revision is stored in `fin_intake`; metadata in `fin_lib`.

For each Flow branch include:
- What is this? Why is it needed? What does it affect?
- Where does it come from? Exact form / field code / source version.
- What is missing? Why is the method blocked?
- Which action is allowed now? (`OPEN_FORM`, `VIEW_DRAFT`, `REVIEW`,
  `CALCULATE` only when qualified).
- Source provenance, review status, locale review, version/SHA.

Project readiness is private. No fake client-side "70% ready" based only on
catalog presence; real readiness requires authenticated project context,
revision coverage, period/currency consistency, approved method and permissions.

## 7. Technical implementation decisions

- React + TypeScript is the shell, accessible DOM first.
- `SVG` (or React Flow if needed) for branch edges and keyboard selection.
- Motion (or CSS where sufficient) for limited transitions.
- Three.js/R3F only for Orbit, lazy-loaded and optional.
- No second independent KPI registry: use `fin_lib.lib_metric_definition`.
- `FIN-KPI-R0` REST catalog endpoints remain read-only with candidate states.
- No new microservice solely for UX navigation; modular Backend is sufficient.
- Caffeine only for cacheable *public reference metadata*, keyed by catalog
  version and locale; no user authorization or private project snapshots
  cached without explicit identity-safe design.

## 8. Delivery and acceptance

| Stage | Deliverable | Acceptance |
|---|---|---|
| **UX-R0** | ADR, information architecture, tone, UI tokens, five component boundaries | Reviewed design + documented status gates |
| **UX-R1** | Flow default and guided branching map | User can reach relevant form from question without knowing codes; all graph links real |
| **UX-R2** | Ledger integration | Correct form, exact pinned version, no data loss, successful revision read-back |
| **UX-R3** | Atlas 2.5D graph | Graph edge provenance, focus/zoom, reduced motion, visual clarity |
| **UX-R4** | Cockpit over qualified data | No result without approved method, true provenance and project ABAC |
| **UX-R5** | Optional Orbit | Lazy load, graceful 2D fallback, perf/a11y validation |

Mandatory gates:
1. Backend/React CI and PostgreSQL R3 qualification on exact SHA.
2. Browser E2E: decision → metric → form → save/reopen → back to Flow.
3. Keyboard-only and screen reader navigation; contrast and locale overflow.
4. `prefers-reduced-motion`, small screens, no WebGL and reduced-power devices.
5. Negative test for unqualified metric: calculation button must never execute.
6. Preserve honest statuses throughout UX: `DESIGN_ONLY`, `CALC_PILOT`,
   `CANDIDATE_UNQUALIFIED`, `NOT_YET_MAPPED`.

## 9. Explicit non-goals

- No claim that 840 metric descriptions have completed four-language S2 review.
- No fabricated algorithm results, readiness ratios or sample project facts.
- No React UI approval bypass for the 166 forms.
- No forced 3D editor or heavy animation for financial input.
- No production IAM/ABAC implied by using local UAT organization IDs.

## 10. Decision rationale

**Flow first, Atlas second**: the central promise is clarity, not graphical
spectacle. Atlas and Orbit make an impressive identity, but Flow is the
primary way a non-financial specialist converts questions into necessary
data, with transparent lineage and next actions. Ledger and Cockpit complete
the workflow without compromising precision.
