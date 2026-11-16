# FIN METHOD-R1 — FIN-04 Current Ratio (Liquidity) Integration

**Date:** 2026-11-16  
**Working branch:** `feat/fin-r0-r1a`  
**Status:** `CODED / TESTS AUTHORED / CI GREEN NOT VERIFIED / RELEASE HOLD`

## Source mapping review: material constraint

Existing `F01 / FIN-PILOT-V1` maps fields `assets→ASSETS`, `liabilities→LIABILITIES`. Those are **total** balance amounts, not the two inputs required for Current Ratio. Using them to calculate liquidity would be economically invalid.

`V20261116_05__f01_current_balance_mapping.sql` therefore registers a **new, distinct, unqualified-by-default source profile**:
- `F01 / FIN-LIQUIDITY-V1 / currentAssets → CURRENT_ASSETS, version 1.0.0, AS_OF`
- `F01 / FIN-LIQUIDITY-V1 / currentLiabilities → CURRENT_LIABILITIES, version 1.0.0, AS_OF`

The migration **does not insert or promote** `fin_calc.metric_contract` records. An approved authority must separately qualify `STOCK/MONEY` for each version before real calculation is available. This source mapping is not evidence that the React form or Excel template already collects the two fields. F01 intake/template parity and independent accounting review remain open.

## Implementation

- `AutoIndicatorEngine.ratioOf`: a reusable **dimensionless** quotient primitive. Not PercentOf: `400/200=2.0000`, not 200%. Both operands must have the same time window, dimensions, legal entity, organization, business scope, accounting basis, data space, currency and physical unit. Zero and negative denominators yield explicit `NOT_COMPUTABLE` with `ZERO_DENOMINATOR` / `NEGATIVE_DENOMINATOR`, never Infinity or invented zero.
- `AutoReportProjection.RatioOf`: typed operation with unit `RATIO`, currency null; `TrustedAutoReportService` includes it in operation hashing and referenced metric contract validation.
- `QualifiedCurrentRatioAdapter`: server-side `FIN-04/CURRENT_RATIO` adapter depending on `SYS/VERIFY_PUBLISHED_FACT_SET`. Uses existing `TrustedAutoReportService`; no duplicated ratio calculation.
- Source gate: immutable `fin_fact.fact_set_publication`, pinned `organization + entity + scope + reportingBasis + dataSpace + factSetRevision + snapshotHash`, exact two **AS_OF** F01 current-balance assertions on a single date, same currency/version/source revision, empty dimensions, state PRESENT/EXPLICIT_ZERO, explicitly qualified metric contracts, immutable `fact_origin` linking to correct F01 fields, reviewed `approved_source_intake`, sealed `source_population_control` row count=1 and total=assertion value, matching source-document hash.
- Fail closed on missing or ambiguous source data, estimated facts, mismatched reporting date/currency, total assets/liabilities as substitutes or mixed source revisions.
- FIN-04 has a **second** explicit opt-in `fin.methods.fin04.enabled=false` by default (integration tests override it only for their isolated context). Automatic global scheduling also remains `FIN_EXECUTION_ENABLED=false` by default; the methodology family registry retains `releaseQualified=false` and `autoRunAllowed=false`.
- Ratio result writes a traceable immutable `fin_calc.report_run` and can flow into the existing `CORE_PILOT_V1` immutable report as `PARTIAL`. This does **not** qualify the entire FIN-04 family, bank package or investor opinion.

## Independent tests authored (not claimed green)

`AutoIndicatorEngineTest.dimensionlessCurrentRatioSeparatesCoefficientFromPercentage`
- 400/200→2, distinct from percentOf=200;
- 400/0→NOT_COMPUTABLE/ZERO_DENOMINATOR, no numeric value;
- negative denominator→NEGATIVE_DENOMINATOR;
- mixed date and currency→rejection.

`QualifiedCurrentRatioIntegrationTest` (PostgreSQL 16 Testcontainers):
- approved F01 current amounts 400/200→2 in CORE PARTIAL;
- 400/0→NOT_COMPUTABLE / ZERO_DENOMINATOR and no `value` field;
- missing origin, mismatched date/currency→no FIN-04 task;
- additional unrelated, unqualified F01 fact must **not** contaminate a qualified ratio: trusted report projection loads only the explicitly referenced metric keys;
- F01 TOTAL assets/liabilities→no FIN-04 task;
- tenant-scoped report reads and immutable snapshot UPDATE/DELETE blocking.

The Testcontainers tests create **synthetic test fixtures under ACTUAL semantic data space** to exercise production authorization/source contracts. They do not represent real audited company data.

## Remaining blockers / next acceptance

1. Run `mvn -B -ntp -f backend/pom.xml verify` under Java 21 + reachable Docker + PostgreSQL Testcontainers. Fix any failures, especially SQL source population triggers and ratio serialization.
2. Check **one commit SHA** for `verify`, `backend-verification`, `desktop-macos`, `acceptance-gate` with actual run URLs. A missing GitHub combined status is not evidence of GREEN.
3. Independently review Kazakhstan F01 classification of current assets/liabilities, maturity determination, sign convention, currency translation, report date and no double counting.
4. Deliver a real F01 `FIN-LIQUIDITY-V1` Excel/React collection form, reviewed import mapping and controller integration; source field registration alone does not complete user entry.
5. Confirm whether authorized F01 source revision can contain multiple as-of dates; this pilot deliberately supports only **one** qualified as-of pair. A multi-period report requires a versioned `reportPeriodSelectionPolicy`.
6. Add explicit applicability evidence for skipped FIN-04 variants and full source/form-level security review.
7. Do not enable `FIN_EXECUTION_ENABLED=true` for production until actual CI GREEN and independent financial S2 qualification.

**Outcome:** FIN-04 Current Ratio code and negative cases added; method family qualification remains HOLD.
