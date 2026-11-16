# FIN — EXEC-R2/R3 Technical Review and METHOD-R1 Entry Gate

**Date:** 2026-11-16 · **Target:** `feat/fin-r0-r1a` · **Status:** `REVIEW_COMPLETE / CODE_FIXES_AUTHORED / CI_NOT_CONFIRMED / RELEASE_HOLD`.

Reviewed: `SingleExecutorScheduler`, `MethodTaskAdapter`, `PublishedFactIntegrityAdapter`, `SyntheticBusinessDiagnosticAdapter`, `QualifiedRevenueHorizontalAdapter`, `CompositeCoreReportPublisher`, `TrustedAutoReportService`, `TrustedBusinessDiagnosticService`, Flyway `V20261116_01..04`, JUnit and GitHub Actions CI.

**This is a source-level review and not an independent S2 approval.** GitHub CI checks and PR-associated workflow runs did not supply a GREEN proof at the inspected SHA; do not infer acceptance.

## Confirmed findings

| ID | Severity | Evidence | Fix / current disposition |
|---|---|---|---|
| REVIEW-P0-01 | P0 | `CompositeCoreReportPublisher` selected terminal `FAILED` runs but emitted `coreReadiness=PARTIAL`, masking total execution failure | **Code fix authored:** derive `BLOCKED` for FAILED or zero computed tasks; persist/read consistent DB and JSON status; PostgreSQL negative fixture |
| REVIEW-P0-02 | P0 | All method rows were stamped `PILOT_UNQUALIFIED`, concealing distinction among internal checks, qualified metric input, and qualified family | **Code fix authored:** record internal vs not-family-approved status; adapter-declared qualification is advisory metadata, not authority; JUnit checks |
| REVIEW-P0-03 | P0 | The composite publisher selected terminal runs without verifying that the corresponding published fact-set hash still equals the run's fingerprint | **Code fix authored:** join `fin_fact.fact_set_publication` and verify identical tenant/revision/hash before snapshot assembly |
| REVIEW-P0-04 | P0 release gate | JUnit/Testcontainers code exists but single-SHA green Maven/PostgreSQL/React/Playwright CI is not established | **OPEN:** require actual CI evidence and failure fixes before METHOD-R1 release |
| REVIEW-P1-01 | P1 | SingleExecutor validates a static DAG of adapters; there is no qualified, versioned 60-family applicability registry with profile-driven required/optional policies | Open; implementation begins after CI gate |
| REVIEW-P1-02 | P1 | 60-second fixed worker lease without heartbeat/resource isolation is not suitable for expensive Monte Carlo/ML | Open; keep heavyweight tasks disabled |
| REVIEW-P1-03 | P1 | One ACTUAL numerical variant `FIN-01/REV_NET_MOM` conditionally connected; only pilot SYNTHETIC diagnostic rules wired | Open; do not claim full financial diagnosis |
| REVIEW-P1-04 | P1 | Composite `CORE_PILOT_V1` stores method results and diagnostic findings, not a qualified bank/investor conclusion or localized export | Open; enhance only after qualified methods and analysis profiles |
| REVIEW-P1-05 | P1 | No external report publication/ABAC completeness and independent economic S2 signoff | Open; external sharing forbidden |
| REVIEW-P1-06 | P1 | Cross-method economic reasoning and materiality rules not implemented in unified CORE pipeline | Open; require independent golden evidence and avoid causal claims |
| REVIEW-P1-07 | P1 | Cleanup, temporary dataset retention and 30-day deletion are in specification, not end-to-end verified on this execution path | Open |

## Corrected status invariants

1. `FAILED` execution or zero computed tasks => `CORE.BLOCKED`. A partially populated terminal execution => `CORE.PARTIAL` until profile-driven readiness is independently qualified.
2. `PILOT_UNQUALIFIED`, `METHOD_FAMILY_NOT_YET_APPROVED`, and trusted input-metric `QUALIFIED` are **not equivalent**. An adapter's JSON result cannot promote family status.
3. Every CORE report must match actual published tenant+revision+snapshot hash and remain immutable after publication. ML remains NOT_REQUESTED.
4. All comparisons, findings, and recommendations must preserve source/version/period/currency and explicit missingness. No inferred account-level financial health grade.

## Proposed METHOD-R1 inventory (target, not acceptance evidence)

| Family | Pilot connection | Existing engine/expected next action |
|---|---|---|
| FIN-01 | `FIN-01/REV_NET_MOM` conditionally wired | Existing `AutoIndicatorEngine` and `TrustedAutoReportService`; expand approved periods and unit tests |
| FIN-02 | Not wired | `AutoReportProjection.PercentOf`/ShareOfTotal may be reused; validate official denominator and netting |
| FIN-04 | Not wired | Server-owned ratio variants, independently approved F01/current-liability semantics |
| CON-01/02 | Not wired | Verified product/population contribution with refund treatment, threshold version, ties |
| PRO-03 | Not wired | Only after variable/fixed cost taxonomy; not COGS blindly |
| CASH-01 | Not wired | DSO/DIO/DPO with approved stock averages, purchase vs COGS denominator, day-count policy |
| Other 54 methodology families | Not qualified by this work | Audit existing pure engines to classify implemented/partial/design/missing, then wire method variants without duplicate algorithms |

**Do not mark a family fully implemented from one variant.** CrossMethodDiagnosticEngine should consume only qualified results, produce observation/hypothesis/evidence gaps, and never claim causality without independent basis.

## CI and independent verification requirements

- `mvn -B -ntp -f backend/pom.xml verify` with Java 21, Docker and real PostgreSQL Testcontainers, **including** `FactPublicationIntegrationTest`, `TrustedBusinessDiagnosticIntegrationTest`, `QualifiedRevenueSingleExecutorIntegrationTest`, `SingleExecutorGraphTest`, `ExecutionContractsTest`.
- `FIN CI`: `verify`, `backend-verification`, `desktop-macos`, `acceptance-gate` must be GREEN on **one SHA**; evidence URLs and console logs recorded in review.
- Negative golden acceptance: concurrent workers one logical result; crash before/after publication; expired lease cannot publish; poisoned event DLQ; ACTUAL cannot run SYNTHETIC pilot; FAIL->BLOCKED; source fingerprint mismatch not accepted; family qualification not inflated; tenant read isolation; no double count.
- S2 finance review: independent verification of approved metrics, periods, sign conventions, zero denominator, scope, duplicated facts, treatment of revenue returns and negative revenue, assumptions, report labeling.
- S2 security review: organization/field/period ABAC, snapshot and cache authorization, logical separation, cleanup and backup implications.

**Decision:** do not begin bulk METHOD-R1 activation while `REVIEW-P0-04` remains unresolved. Corrective code is committed but not necessarily passing CI. Do not enable `FIN_EXECUTION_ENABLED=true` in production. `main` untouched.
