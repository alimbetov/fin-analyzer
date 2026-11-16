# FIN EXEC-R3 — Diagnostic Orchestration & Composite CORE Pilot

**Date:** 2026-11-16 · **Branch:** `feat/fin-r0-r1a` · **State:** `IMPLEMENTED_UNQUALIFIED / CI_HOLD`.

## Actual delivered scope

- The existing `SingleExecutorScheduler` remains the only server-side entry for scheduled tasks. PostgreSQL Outbox → DAG → leased worker → `analysis_task` JSON result stays unchanged.
- A **real deterministic horizontal finance calculation** is wired through `QualifiedRevenueHorizontalAdapter`, task code `FIN-01/REV_NET_MOM`. It reuses `TrustedAutoReportService` / `AutoIndicatorEngine`, rather than creating a second formula. For automatic selection it requires: published trusted snapshot, two consecutive calendar-month `REV_NET` totals, one currency/version, no dimensional detail, `FLOW/MONEY` metric contract with **`QUALIFIED`** state and no missing/estimated values. It performs comparison using immutable approved facts and stores the immutable `fin_calc.report_publication`. **This is one variant of FIN-01, not a qualified FIN-01 family or 60 implemented methods.**
- `SyntheticBusinessDiagnosticAdapter` runs the existing immutable `TrustedBusinessDiagnosticService` **only on SYNTHETIC**. Existing pilot rule findings are evidence-linked, marked `PILOT_UNQUALIFIED`. The adapter MUST NOT run these rules on ACTUAL. Its task code is `SYS/DIAGNOSTIC_PILOT`, outside the 60 methodology families.
- `CompositeCoreReportPublisher` aggregates completed `analysis_task` results and any qualified-for-synthetic linked diagnostic findings into one **immutable** `fin_exec.core_report_snapshot`. The DB validates published-run/fact-set identity and hashes the JSONB snapshot in a trigger. Reports always mark `CORE`, `coreReadiness=PARTIAL`, `mlReadiness=NOT_REQUESTED`, `qualification=PILOT_UNQUALIFIED`. No unsupported business-health score, credit decision, investor recommendation or simulated profit.
- If a real organization lacks fully qualified contract/facts, only the internal integrity task is scheduled. A published CORE snapshot explicitly indicates missing economic coverage. No auto ML or Monte Carlo.

## Deliverables

| Item | File | Evidence |
|---|---|---|
| FIN-01 actual-eligible adapter | `backend/src/main/java/kz/fin/backend/execution/QualifiedRevenueHorizontalAdapter.java` | Uses `TrustedAutoReportService` and requires `QUALIFIED` contract |
| Diagnostic pilot adapter | `backend/src/main/java/kz/fin/backend/execution/SyntheticBusinessDiagnosticAdapter.java` | SYNTHETIC only |
| Immutable report schema | `backend/src/main/resources/db/migration/V20261116_04__composite_core_report_pilot.sql` | PostgreSQL immutable trigger, trust FK, sha256 |
| Report builder/read | `backend/src/main/java/kz/fin/backend/execution/CompositeCoreReportPublisher.java` | Stored result aggregation; org-scoped read |
| FIN-01 qualification test | `backend/src/test/java/kz/fin/backend/execution/QualifiedRevenueSingleExecutorIntegrationTest.java` | golden net revenue 100→150 = 50% MoM; negative PILOT and missing months |
| Diagnostic + report E2E | `backend/src/test/java/kz/fin/backend/persistence/TrustedBusinessDiagnosticIntegrationTest.java` | 13-month synthetic history, 3 findings, immutable CORE, cross-tenant denied, ACTUAL exclusion |
| Required test evidence gate | `.github/workflows/ci.yml` | Two integration test classes are mandatory, same SHA |

## Coverage of methodology target

- **60** methodology families in agreed target catalog.
- **1 FIN-01 variant wired** to orchestrated runtime, conditional on qualified metric contract. Family remains `IMPLEMENTED_UNQUALIFIED`.
- **59 families not claimed to be wired or qualified** by this change. Current legacy implementations require inventory and independent methodology/negative-test mapping; do not mark them `MISSING` without auditing their code.
- **3 synthetic-only diagnostic rules** exercised through the existing trusted pilot service; not a substitute for the 60 families.
- **1 CORE report format** `CORE_PILOT_V1` built from stored task results. `CORE_WITH_ML` is not implemented.
- React reporting UI, external PDF/Excel export, tenant/field/period ABAC at public REST boundary, industry qualification, ModelRegistry and background ML inference are **not delivered by this slice**.

## Mandatory acceptance before turning on FIN_EXECUTION_ENABLED

1. `mvn -B -ntp -f backend/pom.xml verify` with Docker/Testcontainers against PostgreSQL 16.
2. One commit SHA: GitHub Actions Node, Java/PostgreSQL, macOS/browser, acceptance gate all GREEN; record run URLs.
3. Independently verify test results `TrustedBusinessDiagnosticIntegrationTest` and `QualifiedRevenueSingleExecutorIntegrationTest`, plus `FactPublicationIntegrationTest` worker crash/retries; no skipped tests.
4. Verify the new Flyway V20261116_04 migration on empty and existing schema, plus rollback/upgrade safety.
5. Independent financial S2: evaluate monthly REV_NET semantics, same accounting basis, no duplicate source aggregation, percent-change rounding, zero-denominator behavior and qualification provenance.
6. Security S2: tenant isolation (including report ID guessing and tenant+period field access), no public endpoint until ABAC, worker isolation.
7. Resource safety: fixed 60-second lease needs heartbeat/timeout for long CPU/ML tasks; don't enable heavyweight adapters until implemented.
8. Manual React/Excel/server parity and external report qualification **before** release.

## Next functional expansion

- Formal `MethodologyRegistry` with versioned family→variant→required fact mapping and approved qualification gates.
- **FIN-01:** support additional approved periods/indicators after independent methodological proof; **FIN-04:** exact liquidity/current ratio using F01 balance source, not simulated rows; **CON-01/02:** verified Pareto/ABC SKU cases from approved granular sales; then CASH/BUD/OPS in batches.
- `AnalysisProfile` + explicit completeness/critical metrics + actual Cross-Method Diagnostic Engine, with provenance and evidence gating.
- Immutable, role-restricted report API, React charts, RU/KK/EN/ZH output, human-authorized bank/investor package.
- `CORE_WITH_ML` only after trained and independently backtested advisory models are qualified. Preserve exact CORE snapshot.

**Current release status: HOLD.** Code and tests have been written in GitHub, not executed in this session. Do not report CI GREEN, 60/60 coverage, or production-ready before evidence.
