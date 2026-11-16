# FIN — FIN-04 / EXEC-R2-R3 Independent Source Review (2026-11-16)

**Branch:** `feat/fin-r0-r1a`  
**Decision:** `FIXES_AUTHORED / ACCEPTANCE_HOLD / CON-01-02_NOT_STARTED`  
**Scope:** F01 field mapping, approved-source origin, metric contracts, RatioOf engine/projection, SingleExecutor task gate, PostgreSQL Testcontainers fixtures and GitHub CI.

## Findings

| ID | Severity | Finding | Disposition |
|---|---|---|---|
| F04-RV-001 | **P0 build** | `QualifiedCurrentRatioIntegrationTest.seed()` referred to an undefined `contractVersion` variable when inserting liabilities. Test source could not compile, making prior “tests added” claim insufficient for actual acceptance. | **FIXED IN SOURCE:** use the F01 versioned mapping's `1.0.0` explicitly; retained numeric goldens. Require Maven GREEN. |
| F04-RV-002 | **P0 data integrity** | `QualifiedCurrentRatioAdapter` compared current assets and liabilities currencies to each other but not to the *reviewed F01 source intake currency*. USD/USD assertions could pass an intake marked KZT. | **FIXED IN SOURCE:** SQL requires `a.currency_code=i.input_currency`; new two-USD / reviewed-KZT negative Testcontainers scenario. |
| F04-RV-003 | P1 deployment safety | FIN-04 family not independently qualified. Separate method-level feature switch exists and is false by default. | **TEST ADDED:** `QualifiedCurrentRatioFeatureGateTest` proves disabled by default, expected verification dependency and opt-in behavior. CI explicitly includes test. |
| F04-RV-004 | P0 release gate | Java/Maven/PostgreSQL Testcontainers have not been executed on verified SHA; current execution environment lacks Maven and Docker daemon. GitHub status/run response does not furnish successful acceptance. | **OPEN:** execute locally or GitHub CI; inspect compilation, Flyway, PostgreSQL trigger and all mandatory scenarios. |
| F04-RV-005 | P1 product readiness | `FIN-LIQUIDITY-V1` is a mapping registry addition, not approved Excel template/React F01 intake or qualified accounting method. | **OPEN:** complete user-facing F01 upload/manual React, reviewed mapping and qualification gate. |
| F04-RV-006 | P1 method eligibility | Current ratio selects exactly one approved as-of pair per revision; no explicit multi-period selection rule. | **KNOWN PILOT LIMITATION:** report periods require a versioned profile and selector. |
| F04-RV-007 | P1 methodology completeness | This is one `FIN-04/CURRENT_RATIO` variant. Quick ratio, cash ratio, and liquidity interpretation are not implemented/qualified by this commit. | **OPEN:** independent variants and source tests; do not mark FIN-04 family fully qualified. |
| F04-RV-008 | P1 cross-method | Unified CORE remains profile-unqualified PARTIAL; no materiality/cross-method synthesis. | **OPEN:** after correctness gate, then Cross-Method Diagnostic Engine and controlled report. |

## Preserved guarantees

- `FIN-PILOT-V1` total `ASSETS / LIABILITIES` **must not** be silently mapped to `CURRENT_ASSETS / CURRENT_LIABILITIES`.
- Genuine input requires `F01 FIN-LIQUIDITY-V1` + `fact_origin` + `approved_source_intake` + `source_population_control` + both `QUALIFIED` STOCK/MONEY metric contracts + immutable approved revision.
- Same tenant/entity/scope, reporting basis, date, currency, source revision and snapshot hash.
- `AutoIndicatorEngine.ratioOf` returns dimensionless coefficient 400 / 200 = 2; `percentOf` returns 200%, which is not interchangeable.
- 400/0 returns `NOT_COMPUTABLE / ZERO_DENOMINATOR`, no fabricated value.
- `FIN_METHODS_FIN04_ENABLED` is false unless explicitly enabled. Overall `FIN_EXECUTION_ENABLED` also defaults false; production must remain off.
- Report remains `CORE_PILOT_V1`, `PARTIAL`, not a bank/investor-ready financial opinion.

## Mandatory requalification

1. On an actual Java 21 + Maven + Docker environment run `mvn -B -ntp -f backend/pom.xml verify`.
2. Check compilation, all Flyway migrations, and PostgreSQL Testcontainers: `QualifiedCurrentRatioIntegrationTest`, `QualifiedCurrentRatioFeatureGateTest`, `AutoIndicatorEngineTest`, `FactPublicationIntegrationTest`, `QualifiedRevenueSingleExecutorIntegrationTest`, `QualifiedRevenueStructureIntegrationTest`, `TrustedBusinessDiagnosticIntegrationTest`.
3. Independently check concurrent scheduling, lease recovery, fact population seal, zero denominator, source-intake-currency mismatch, cross-tenant access, immutable snapshot and event replay.
4. Capture test XML, failing logs, the exact SHA and GitHub Actions run URLs on **that same SHA**; remediate any failures, then repeat full verification.
5. Only after GREEN and independent financial S2 review should CON-01/02 execution be connected and enabled. Design fixtures and pure computations may be developed separately but cannot be reported as qualified.

**Current verdict:** defects discovered and code corrections committed. Their runtime correctness is unverified. **Do not proceed to production enablement or claim “all OK.”** `main` untouched.
