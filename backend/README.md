# FIN Backend Economic Domain Kernel — first executable slice

Status: IMPLEMENTATION CANDIDATE. Do not mark READY until successful CI, PostgreSQL integration tests,
independent financial review and manual qualification.

## Scope
Java 21 / Spring Boot 3.5.7 / Spring Data JPA / PostgreSQL 16 / Flyway.
Domain primitives: typed decimal states, immutable fact coordinates and fact sets, typed formula DAG,
period flow/stock semantics, isolated scenario assumptions.
A form compatibility matrix lists 44 financial/credit, 59 business-input, and 63 sector forms (166).
The matrix is DESIGN-ONLY and NOT executable support for 166 forms.

## Run
Requires JDK 21, Maven 3.9+, Docker (for Testcontainers), PostgreSQL 16 for application startup.

    mvn -f backend/pom.xml verify

Local application (with FIN_DB_URL/FIN_DB_USER/FIN_DB_PASSWORD set):

    mvn -f backend/pom.xml spring-boot:run

## Non-goals
No backend-to-React trusted API integration (the separate local-only IND-R1 React pilot is documented in ../docs/FIN-IND-R0-R1-STATUS.md), no bank approval, no public API, no completed 166-form implementation,
no production identity/security boundary, no server integration with existing React project.
No automatic approval of PDF/Excel contents.

## Next gates
1. Qualify source and row-level form mapping; align financial metric codes and method versions.
2. Make approval orchestration and FactSet persistence authoritative; add optimistic concurrency and outbox.
3. Add Financial Statement + Debt Facility + Sales/Cash transaction typed aggregates.
4. Ensure company/period/currency/basis selection before invoking CalculationDag.
5. Add cross-runtime decimal golden fixtures (existing React and sector pilot are separate baselines).

## Automated analytical columns (prototype)

See [AUTO-INDICATORS-R1.md](AUTO-INDICATORS-R1.md).
Pure Java operators: FLOW_SUM, MONTHLY_FLOW_MEAN, DAY_WEIGHTED_LEVEL_MEAN,
STOCK_CLOSING, ABS_CHANGE/PCT_CHANGE, PERCENT_OF, SHARE_PCT and MOVING_MEAN.
Inputs are scoped by company, business unit, fact-set revision, metric version,
currency/unit, accounting basis, data space and dimensions. Calendar coverage
and baseline/denominator readiness are checked. Sector coverage across A-U
and example report columns are documented as DESIGN_ONLY: they are not
qualified methods, auto-rendered React fields or an authorization-aware API.

## Scenario-oriented form catalog v2 (design only)

See [FIN Form Catalog Foundation v2](../docs/FIN-FORM-CATALOG-FOUNDATION-V2.md).
It preserves 166 existing top-level form IDs, adds 30 proposed detail-sheet
contracts, ten scenario packs, 21 sector sections with 64 candidate business
models, 52 SPEC_ONLY acceptance cases, and RU/KK/EN scenario/sheet titles.
Referential integrity is tested by `ScenarioFoundationCatalogTest`, but this
is **not** a live FormRegistry, Excel importer, backend calculation for 166
forms or a financially qualified industry-method suite.

## FIN Diagnostic Compass — initial executable guidance

`CompassCatalog` loads the checked-in form/scenario/sector registries and
additional versioned conditional/evidence policies. `DiagnosticCompass`
returns deterministic next-form recommendations, reuse, historical-period
preflight, business-model questions, and catalog maturity. See
[FIN Diagnostic Compass R1](../docs/FIN-DIAGNOSTIC-COMPASS-R1.md).
This pure JVM engine is **not a source-approval service, HTTP controller,
qualified business verdict or working React experience**.

## Scenario economics and EX-sheet Compass (synthetic R2)

[Methods and limits](DIAGNOSTICS-R2-SCENARIOS.md). Pure `ScenarioEconomicsEngine`
checks old-form reconciliations, yearly history, budget variance, retail,
production WIP, resource estimates, EVM, dated FCFF NPV and pilot DSCR.
`DiagnosticSheetCompass` prioritizes relevant detail sheets from all 30 EX
contracts and never asks to fill DERIVED formulas manually. These classes
have **no trusted form approval, Keycloak/ABAC, real ACTUAL capability or
React client integration**.
