# FIN F01-LIQUIDITY-V1 — reviewed manual/XLSX/React intake implementation

**Date:** 2026-11-16  
**Branch:** `feat/fin-r0-r1a`  
**Status:** `IMPLEMENTED_IN_SOURCE / TESTS_AUTHORED / CI_NOT_YET_CONFIRMED / PRODUCTION_HOLD`

## Existing functionality reused

The baseline React balance screen already contains `CURRENT_ASSETS` and `CURRENT_LIABILITIES`, but its **local** approval cannot authorize ACTUAL source facts. The existing trusted React workspace calls a Keycloak/OIDC-guarded backend and provides independent `FIN_REVIEW`. This change extends that **existing trusted workspace** rather than presenting another local draft as financial truth.

The existing `F01 FIN-PILOT-V1` and other four pilot form versions stay unchanged. The new version `F01 FIN-LIQUIDITY-V1` is explicit; the server must never infer it from an ordinary F01 submission.

## Delivered backend changes

1. Flyway `V20261116_06__reviewed_f01_liquidity_intake.sql` enables `FIN-LIQUIDITY-V1` **only for F01** and adds its five existing total-balance field mappings; the two CURRENT field mappings are defined by `V20261116_05`. Both migrations do not qualify metric contracts.
2. `ReviewedFinancialIntakeService` version-pins submission, manual-source digest, independent review, source provenance and the mapping into ACTUAL. The source version is recorded, recovered from DB and matched against the reviewed XLSX original, not inferred from the current UI selection.
3. `PilotXlsxTemplateService` retains the 7-argument legacy context constructor and adds an explicitly versioned context. Its new F01 template has the existing five optional/required fields plus `currentAssets` and `currentLiabilities`.
4. `TrustedFinancialFormMapper` allows the new version only for F01 and requires independently reviewed source evidence, versioned field mapping, sealed source population and the existing immutable trust barriers.
5. `FinancialIntakeController` accepts `formVersion` in manual body and XLSX/template query parameters, defaulting to the old pilot version. JWT organization permissions, submitter/reviewer identity separation and publication flow remain mandatory.

## F01 liquidity acceptance rules

- A new source revision contains exactly **one as-of date**, expressed by month of the balance closing date.
- Mandatory fields: `assets`, `liabilities`, `equity`, `currentAssets`, `currentLiabilities`. Optional: `cash`, `receivables`.
- Each source cell must use the canonical F01 `balance` row ID; no unknown or duplicate metric fields.
- Exact balance identity `assets = liabilities + equity`.
- `currentAssets ≥ 0`, `currentLiabilities ≥ 0`; neither current amount may exceed its corresponding total.
- The submitted Excel file is immutable source **evidence**, not approval. The manual intake digest is manifest-derived, not a forged original document SHA.
- Independently approved and published F01 produces `CURRENT_ASSETS` and `CURRENT_LIABILITIES` as `STOCK/MONEY` `AS_OF` facts with recorded origin, source document hash and population controls.
- These inputs **do not themselves qualify** `FIN-04/CURRENT_RATIO`: separately reviewed `fin_calc.metric_contract` must be `QUALIFIED` for versions 1.0.0; `fin.methods.fin04.enabled` and `fin.execution.enabled` remain OFF in production.

## React and testing

`TrustedFinanceWorkspace.tsx`: when F01 is selected, user chooses either `FIN-PILOT-V1` or `FIN-LIQUIDITY-V1`. The new version shows two extra fields with clear labels, constructs the versioned manual submission, downloads server-authored Excel and uploads the original back with pinned version. The independent reviewer stage and ACTUAL publication remain unchanged. The chosen source version is visible when reading the submission.

`f01-liquidity.ts` provides browser-side exact decimal and balance validation **for UX only**; the authoritative rules are in the Java service.

Tests added:
- `ReviewedFinancialIntakeIntegrationTest`: reviewed version, approval by distinct reviewer, seven mapped facts, two correct current-balance facts, source lineage/date, cross-tenant read denial, missing/negative/over-total/mixed-date/unmapped field cases.
- `PilotXlsxIntakeIntegrationTest`: seven-field XLSX round-trip, independent approval, ACTUAL publication, legacy format mismatch, missing current liabilities.
- `f01-liquidity.test.ts`: exact input invariants and distinct existing/new form versions.

## Release/CI gates

**No claim of GREEN is made.** At last user update the macOS desktop check was in progress; subsequent new commits invalidate any earlier single-SHA acceptance results. Backend Maven/JUnit/PostgreSQL tests, Node typecheck and Playwright must all run successfully on the final commit SHA.

```bash
mvn -B -ntp -f backend/pom.xml verify
npm ci
npm run typecheck
npm test
npm run build
npm run test:e2e -- --project=chromium
```

Review the actual logs and CI URLs. New test code is not execution evidence. Do not turn on FIN-04 or CON-01/02 until these gates, independent methodological review, and security review.

## Next work

1. Fix any compile/Flyway/regression errors from actual CI.
2. Confirm end-to-end F01: React manual → independent FIN_REVIEW → approved FACT_REVISION → provenance → FIN-04 (sandbox with verified metric contracts).
3. Start CON-01/02 **as a separate methodology-qualification slice** with independently computed Pareto/ABC goldens, no invented SKU population or unqualified financial conclusions.
4. After qualifying these, continue PRO-03 and CASH-01, and strengthen the cross-method CORE report.
