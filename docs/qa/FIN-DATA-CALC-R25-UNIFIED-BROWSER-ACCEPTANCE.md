# FIN DATA-CALC-R2.5 — Unified Calculate/Explain/History browser acceptance

**Date:** 2026-11-20  
**Branch:** `feat/fin-form-library-standalone`  
**Code HEAD tested:** `f5fee543ac8a865ff81e013ad5824e265024a1f6`  
**State:** `BROWSER_UAT_PASS` (limited F02/C01/C02 synthetic management KZT; NOT financial S2).  
**GitHub Actions:** https://github.com/alimbetov/fin-analyzer/actions/runs/44680085762  
**Runner:** Ubuntu 24.04, Java 21, PostgreSQL 16 service container, real Spring Boot, React/Vite and Chromium (Playwright 1.56.1); no mocked financial responses.

## Scope implemented

- Main `frontend/src/App.tsx` now uses the existing `UatCalculationPanel` for **F02, C01, C02** with explicit `VITE_FIN_INTAKE_UAT=true` opt-in. The previous F02-only duplicate UI/action was removed.
- Existing generic `POST /api/v1/uat/calculations/preview` calculates from an **exact persisted, rehashed** `fin_intake` revision via the existing F02 and C01/C02 kernels.
- Visible outputs include value, unit, formula label, method/source SHA, period/as-of, diagnostics and previous-revision comparison. The UI does **not** calculate finance arithmetic.
- The panel disables calculation when unsaved or uncertain edits exist. Calculation is read-only and never publishes an unqualified source to production `fin_fact`.
- Previous `/uat/calculations/f02/preview` REST route remains available for existing clients; common UI uses the unified endpoint.

## Evidence

| Check | Evidence | Outcome |
|---|---|---|
| F02 real editor save → UAT calculation → revision edit → historic comparison | `frontend/e2e/f02-calculation-uat.spec.ts` | PASS |
| C01 two real ROWSET rows → gross/allowance/declared overdue → dirty guard → historic comparison | `frontend/e2e/c01-c02-calculation-uat.spec.ts` | PASS |
| C02 two real ROWSET rows → outstanding / declared overdue share / as-of provenance | `frontend/e2e/c01-c02-calculation-uat.spec.ts` | PASS |
| Browser suite exact count | GitHub Actions 44680085762 JUnit | **3 tests / 0 failures / 0 errors / 0 skipped** |

**Test amounts are synthetic and explicitly scoped to MANAGEMENT/SYNTHETIC/monthly/KZT.** F02 independently tested 200 revenue, 100 COGS, 50 OPEX → 100 gross profit, 0.5 margin, 50 operating result; changed revenue to 300 and compared old/new. C01 tested gross 150 less allowance 15 = net 135; after source edit net 235; declared overdue 30 and overdue share 0.2. C02 tested outstanding 100, declared overdue 20, share 0.2.

**Additional bounded evidence (different SHAs):**

- Prior C01/C02 PostgreSQL 16 source/rowset integration suite: https://github.com/alimbetov/fin-analyzer/actions/runs/44677644424 — success, 14 mandatory tests including F02 at SHA `5163d5144f3bfaa1944f226013db49b9832411bb`.
- Existing full F02 browser intake scenario: https://github.com/alimbetov/fin-analyzer/actions/runs/44680065446 — success at SHA `de851856705c87a0bf52b6b1dde6320bb34e14df`.

Older regression evidence is not silently promoted to the acceptance HEAD. A separate broader Maven/UX regression was still executing when this acceptance note was prepared; its final outcome must be checked independently.

## Honest limits

1. `UatCalculationPreview` is a read-only calculation preview. Persistent immutable result/receipt and a generic executor through the full `CalcPlan` are **not yet provided by this browser gate**.
2. C01/C02 declared overdue is **not reconstructed** from payment allocations; allowance is user-entered and ECL is **not** calculated.
3. The shared UI is currently restricted to the three explicit form codes and is **not** a universal Metric Graph-driven renderer.
4. A05 loan schedules and the 38 acceptance checks remain `NOT_IMPLEMENTED`.
5. No claim of financially qualified IFRS, audited reporting, KZ GESV, production admission, covenant PASS or 154-form E2E.
6. An earlier UX CI run at SHA `72c7882e49b1` failed on a Maven plugin retrieval issue rather than any executed browser assertion. This report's own unified browser suite is green on its exact SHA.

## Next implementation gate

**MG-R0.1 — Metric Graph read-only resolution foundation:** reuse `fin_lib.lib_metric_definition`, existing MethodRevision/CALC-154, `Calc4DTypedDagCompiler` and the 42-model goal/indicator catalog; add one explicit typed resolution API and tests for **goal → metric → method variant → dependency nodes → required physical form fields**. Do not add another metric registry or a second calculator. A05-R1.0 typed loan contract can start after this core dependency is stable, keeping A01 allocation as the prerequisite for A05 plan/fact.

**Decision:** DC-R2.5 **browser UAT accepted for the bounded F02/C01/C02 vertical**. All broader platform and financial qualification gates remain separate.
