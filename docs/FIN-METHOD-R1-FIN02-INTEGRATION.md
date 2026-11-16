# FIN METHOD-R1 — FIN-02 Revenue Structure Execution Slice

**Date:** 2026-11-16  
**Branch:** `feat/fin-r0-r1a`  
**Release gate:** `IMPLEMENTED_UNQUALIFIED / CI_NOT_CONFIRMED`

## Delivered

`QualifiedRevenueStructureAdapter` now registers `FIN-02/REV_CATEGORY_SHARE` with the existing `SingleExecutorScheduler`. It depends on `SYS/VERIFY_PUBLISHED_FACT_SET` and calls existing `TrustedAutoReportService` with `AutoReportProjection.ShareOfTotal`, which delegates to `AutoIndicatorEngine.shareByDimension`. No second financial percentage engine was added.

Applicability (fail closed):
- immutable organization-scoped published fact revision and exact matching publication snapshot hash;
- `REV_NET` `FLOW/MONEY` metric contract **QUALIFIED**, approved selected facts only;
- single calendar month, reporting basis, entity, business scope, currency, metric version;
- an un-dimensioned `REV_NET` total and a **complete category population**, with 2–40 distinct nonblank categories in exactly the dimension `category`;
- no missing, estimated, negative, duplicate, mixed-currency or partially overlapping facts;
- **exact reconciliation**: sum of category amounts equals the trusted total, and denominator > 0;
- method not scheduled if requirements are not met. No fabricated zero or denominator.

The method returns an immutable published `fin_calc.report_run` id, input fingerprint, approved fact revision, reporting period, exact category shares, a `METHOD_FAMILY_NOT_YET_APPROVED` qualification flag and input source provenance. The existing `CompositeCoreReportPublisher` includes this result with `coreReadiness=PARTIAL`. It does **not** publish a qualified bank/investor conclusion.

## Synthetic expected results and negative tests

JUnit/Testcontainers class: `backend/src/test/java/kz/fin/backend/execution/QualifiedRevenueStructureIntegrationTest.java`.

| Fixture | Synthetic input | Expected |
|---|---|---|
| FIN02-G01 | Total=1000 KZT, category A=200, B=800 (same approved month) | A=20%, B=80%; FIN-02 task computed once |
| FIN02-N01 | No undimensioned approved total | No FIN-02 task |
| FIN02-N02 | Approved total=990, categories sum=1000 | No FIN-02 task |
| FIN02-N03 | Metric contract PILOT, data space ACTUAL | No FIN-02 task |
| FIN02-N04 | Negative category or nonreconciled amounts | No FIN-02 task |
| FIN02-R01 | Result in immutable CORE snapshot | `PARTIAL`, `NOT_REQUESTED` ML, organization-isolated, no duplicate, UPDATE rejected |

`FIN02-G01` is an independent economic control. Actual JUnit/Testcontainers PASS **not confirmed** until a live Maven+Docker execution.

## Known limitations

- This is **one** revenue-structure variant, not fully qualified FIN-02 vertical analysis across an entire P&L/balance sheet.
- The method registration is a pilot: the 60-family registry retains `releaseQualified=false`, `autoRunAllowed=false`. Automatic adapter applicability is data-guarded but this does not represent institutional release approval. Production `FIN_EXECUTION_ENABLED=false`.
- No official business-model-specific category taxonomy, category coverage certificate or returns policy yet. The exact total-to-group reconciliation is necessary but not sufficient to prove sales population correctness.
- Current scheduler does not persist an explanation for every *skipped* method. A future `ApplicabilityDecision` ledger must record why a method was ineligible.
- No Excel/React manual UAT or PDF bank-ready export; no ML.
- A fully qualified `FACT→METHOD→CORE` trace and profile-driven completeness remain a separate acceptance gate.

## Next

1. Execute `mvn -B -ntp -f backend/pom.xml verify` with Java 21 and real Docker/PostgreSQL Testcontainers, inspect CI `backend-verification`, `verify`, `desktop-macos`, `acceptance-gate` on one commit SHA.
2. Fix observed failing build/tests, repeat until **one-SHA GREEN**; do not silently disable tests.
3. Extend to `FIN-04/CURRENT_RATIO` only after confirming canonical F01 current-assets/current-liabilities codes, reporting dates and independently reviewed balance golden.
4. Continue CON-01/02, PRO-03, CASH-01 with schema/semantic constraints and preapproved fixtures.
5. Add Cross-Method diagnostics and report profile rules only on independently qualified inputs.

**Current readiness: CODED, NOT QUALIFIED.**
