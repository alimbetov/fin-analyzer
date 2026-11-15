# FIN-DIAG-R3 — single-SHA CI and economic golden-case expansion

**Release decision: HOLD.** This document describes source changes and test definitions, **not a claim of successful remote CI or approved production recommendations**.

## Why this gate exists

Previously `FIN CI` (Node/React/Playwright) and `FIN Backend Domain` (Maven/PostgreSQL Testcontainers) were separate workflows. A green result from only one was insufficient and allowed optimistic status reporting.

`.github/workflows/ci.yml` now defines one `FIN CI` workflow that includes:

1. `verify`: Node 22 install/typecheck/9-form sector smoke/166-form matrix/Vitest/Vite/Chromium Playwright;
2. `backend-verification`: Temurin Java 21 `mvn -B -ntp -f backend/pom.xml verify`, requiring actual Docker-backed PostgreSQL Testcontainers;
3. `desktop-macos` on push: macOS 15 Chromium/WebKit browser E2E;
4. **`acceptance-gate`** with `if: always()` and `needs`: fails if either verified job failed/skipped, or push macOS browser job is not successful. All jobs run on the *same checkout SHA*.

The backend evidence validation parses Surefire XML for `ScenarioFiveQuestionEngineTest`, `ExtensionSheetContractSmokeTest`, `ScenarioEconomicsEngineTest`, `PostgresInvariantTest` and `FactPublicationIntegrationTest`. Each must have **more than zero actual tests and zero failures, errors and skips**. A missing XML file is a failure. The workflow uploads `fin-backend-golden-and-postgres-evidence` and `fin-diagnostic-smoke-matrix`.

A separate legacy `FIN Backend Domain` workflow may still run; its status alone does **not** satisfy `acceptance-gate`.

**Current evidence:** GitHub's available PR-run listing and combined status API did not provide a verifiable GREEN run for the newest changes. This does not establish failure or success. Record actual workflow-run URL, run ID, conclusion, exact SHA and artifact list to close the CI gate. If branch protection is available, make `FIN CI / acceptance-gate` a required check for the relevant PR.

## Executable diagnostic numerical coverage

| Layer | Coverage in source | Qualification |
|---|---:|---|
| Top-level registered forms | 166 | Registry, not 166 calculators |
| Sector SF forms | 63 | 9 JS arithmetic pilots; 54 lack JS implementation |
| Java scenario methods with baseline + altered-case test definitions | **16** | Synthetic only, pending successful JUnit |
| Distinct source form codes in Java golden fixtures | **16** | Three overlap nine JS form codes |
| Top-level forms with either JS or Java numerical golden fixture | **22** | **144** still have no specific numerical golden fixture |
| EX sheets used in Java numerical scenarios | **10** | Sources for some of the above 16 methods, not 10 independent validated engines |
| EX sheets with registration/contract negatives but no numerical golden | **20** | Not computable / no invented results |
| Fully qualified cause + severity + action impact | **0** | S2 and real E2E required |

The 16 Java methods are listed in `backend/src/test/resources/simulation/scenario-five-question-coverage.tsv`. `packages/sector-pilot/src/smoke-cli.mjs` joins the form catalog to this Java manifest and the nine JS golden fixtures. The JSON status `GOLDEN_TEST_DEFINED_CI_MUST_VERIFY` is **not** a test-pass assertion.

## Two new economic methods, with independent fixed expectations

### EX-BUD-06 — 13-week cash forecast rollforward (parent P03)

- 13 **consecutive Monday** dates, each with explicit event date, revision ID, scenario item key, opening balance, inflow, outflow, restricted funds and closing balance;
- `closingCash = openingCash + inflow − outflow`, and next `openingCash = previous closingCash`;
- if any week, date, field, alignment or month is invalid: `MISSING_EVIDENCE`; if cash bridge/continuity disagrees: `OBSERVATION` with reconciliation difference **and no forecast closing cash metric**;
- if fully reconciled: `closingCashForecast`, `forecastNetMovement`, `minimumUnrestrictedCash`, each linked to all 13 source revisions and event dates;
- synthetic golden: 13-week final closing **230**; alternative increases week-13 outflow by 10 and final closing becomes **220**; calculated delta **−10**.
- never interpret synthetic forecast as observed future money, confidence interval or guaranteed liquidity.

### EX-MFG-06 — overhead absorption (parent DF-CST-03)

- `overheadAbsorptionDifference = actualOverhead − absorbedOverhead`; positive is under-absorption, negative is over-absorption under the stated sign convention;
- if capacity base is positive, `absorbedOverheadPerCapacityUnit = absorbedOverhead / capacityBase`; zero base blocks that ratio, not the cash difference;
- negative overhead or capacity is rejected; missing required field is `MISSING_EVIDENCE`;
- synthetic golden: actual 120, absorbed 100 → difference **20**; alternative absorbed 110 → difference **10**, delta **−10**.
- disposition requires an approved capitalization/allocation method; the engine must not automatically adjust financial statements.

Both methods use `ScenarioFiveQuestionEngine`: what=synthetic mathematical value, why=rule hypothesis, seriousness=not assessed, action=human review candidate, effect=recomputed synthetic alternative. The result includes organization/business scope, fact-set revision, source row/revision and exact dated event when provided. The alternative must retain scope/source identity and list changed input assumptions.

## Verification and remaining gates

```bash
npm --prefix packages/sector-pilot test
node packages/sector-pilot/src/smoke-cli.mjs > test-results/fin-form-smoke-matrix.json
mvn -B -ntp -f backend/pom.xml verify
npm ci --no-audit --no-fund
npm run typecheck && npm test && npm run build
npm run test:e2e -- --project=chromium
```

The repository's `FIN CI` runs the complete coordinated gate. Review a **single SHA** rather than combining statuses of different commits.

**Open non-CI release gates:**
- independent S2 review of accounting formulas, fiscal calendars, comparability, monetary units and source-policy contracts;
- authoritative server-side resolution of approved PostgreSQL facts, exact source/field mappings and immutable provenance;
- Keycloak/ABAC access, tenant isolation, and server-side audit trail;
- React → API → PostgreSQL published revision → diagnostic → action → safe alternative → reload E2E;
- manual macOS product UAT separately from CI automation;
- user-approved economic interpretations, causal factor attribution, severity thresholds and scenario cost/benefit sensitivity.

Next numeric expansion should prioritize finance F01/F02/F03 deeper decomposition, 13-week budget scenarios across periods and sector-specific calculations based on **a reviewed applicability matrix**, not indiscriminately assigning one formula to all remaining 144 forms.

**Decision:** `HOLD` until full single-SHA CI evidence and all mandatory methodology/trusted-E2E gates are satisfied.
