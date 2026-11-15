# FIN R4.6 — Central indicator relationship graph: implementation and acceptance

**Status:** SYNTHETIC_BACKEND + REACT_PLANNING_UI_IMPLEMENTED; **PRODUCTION ACTUAL:** HOLD.  
**Branch:** `feat/fin-r0-r1a`, Draft PR #2. **Do not merge to `main` automatically.**

## User goal and financial semantics

User may begin with a business question or a report. FIN discovers all **reviewed semantically connected** indicator relations that apply to its scope/period, checks numerical identities and discrepancies, highlights missing inputs, and requests the smallest relevant next source. A shared report period is necessary but **not sufficient** to establish financial comparability. Correlation, accounting identity and causal explanation remain distinct claims.

## Confirmed code changes

### Versioned registry and all-catalog classification

- `backend/src/main/resources/fin/indicator-relationships-v1.tsv` is the single versioned source of the **six synthetic-pilot relationships**, each with stable code, version, type, operator, qualification, same-scope accounting policy, period policy and explicit `metric:timeRole:sourceForm` bindings.
- `IndicatorRelationshipRegistry` validates the relation TSV against the existing 166-form catalog. All forms have a safe disposition: **5 pilot numeric sources**, **149 not-qualified numeric candidates**, **12 evidence-only documents/tax forms**. This is registry **coverage**, not 166 runnable financial methodologies.
- Unknown binding, duplicate relation, conflicting metric owner, incorrect stock/flow role, attempted use of a document as numerical input or invented PRODUCTION_QUALIFIED status fails closed.
- `CrossFormDiagnosticEngine.RULES`, `CrossFormEvidencePlanner`, the backend loader's metric whitelist, its stock-vs-flow semantics and immutable diagnostic definition hash now derive from the same bundled registry. The prior R4.5 hardcoded six-rule list and manual source-map are removed.
- `TrustedCrossFormDiagnosticService` uses prepared SQL bind parameters for the server-owned registry metric set, retains source completeness, tenant, source version, currency, exact dates and provenance guards. Immutable `CROSS_FORM_PILOT` snapshots now contain the registry version/hash and `relatedEvidenceGraph` with bounded missing-metric discovery. Rule-set version bumped to `2.0.0` to keep previous runs immutable and reproducible.

### Dynamic graph planner

- `DynamicDiagnosticPlanner` performs cycle-safe, bounded **1–3 hop** discovery from a business question, named source form or verified metric keys. Only *explicit* registered metric edges are traversed. Disconnected method components are not silently bridged.
- Available facts are passed explicitly; merely choosing a form does not mark its cells approved. No rule is created from field labels or statistical similarity.
- The graph does not invent a formula for an unqualified form. "No reviewed relation" is a valid result.
- The legacy one-hop `nextEvidencePlan` is preserved for existing clients; the deeper `relatedEvidenceGraph` is an additive snapshot field.

### React

- `src/diagnostics/registry.ts` validates the **same TSV** at build/runtime and exposes the safe graph lookup. Duplicate/unknown/production-qualification attempts reject.
- `src/diagnostics/RelationshipStudio.tsx` is integrated into `src/App.tsx` (navigation item `05 · Диагностический граф`), with business-problem or form anchor, accounting month and graph depth selectors, linked methods, required input forms and per-metric time role, and clearly displayed catalog disposition.
- **Security boundary:** React is currently a *read-only study planner*, not an authenticated client of a trusted financial diagnostic API. It does not treat local balance or sector demo rows as server-approved data. It never shows fabricated actual organization results.

### Verification

- `IndicatorRelationshipRegistryTest`: all 166 dispositions, six versioned relations, stable digest, exact source owners, negative tamper/unknown/time-role/production claim tests, bounded graph traversal.
- `src/diagnostics/registry.test.ts`: same safe catalog parse, missing form planning, unknown relation denial and graph depth.
- `e2e/graph-planner.spec.ts`: Chrome/WebKit browser journey through problem/form switch, depth 1→3, required evidence and refusal to invent document methods.
- `.github/workflows/ci.yml`: the new Java class's Surefire XML report is now explicitly required for the one-SHA acceptance-gate; React unit and browser tests are already included by the existing workflow.

**CI proof must be recorded against the *final commit SHA*.** Earlier GREEN is insufficient.

## Still blocked (no production readiness claim)

1. **Approved real source intake** — versioned per-form XLSX generation + trusted parser + independent human approval + exact reviewed source manifest. Existing `FIN-PILOT-V1` is not a statutory form contract.
2. **OIDC/Keycloak + ABAC** — org/period/zone/field/permission policies and audit. Server services currently take `authorizedOrg` as a trusted caller placeholder; never expose it directly as a public endpoint.
3. **Financial methodology qualification** — independent S2 accounting review, revenue and cash-flow classifications, opening vs closing stock conventions, VAT, FX, consolidation policies, fiscal calendars, statement comparability and approved materiality thresholds.
4. **R4.7 full domain graph** — independent reviewed and tested rules for remaining applicable forms/sectors. Do not assign pseudo-correlations, arbitrary KPI or formulas to 149 unqualified numeric candidates and 12 evidence-only forms.
5. **Authorized React E2E** — real user → XLSX/manual form → source validation/approval → published ACTUAL FactSet → complete cross-report evidence → financial computation → immutable snapshot → corrections/revision/reopen, including negative authorization tests.
6. **Performance and safety** — bound relationship graph complexity, add planned fan-out/cycle/property tests, PostgreSQL query metrics and per-tenant isolation under realistic volumes, and human UAT.

**Acceptance philosophy:** all published findings are reproducible from independently reviewed source rows and approved methods; missing/foreign/unverified evidence blocks numbers, observations never prove causes, scenarios never promise real-world effects, release remains HOLD until security + finance S2 + ACTUAL end-to-end.
