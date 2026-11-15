# FIN-SIM-R1 — synthetic historical financial benchmark

**Status:** generated synthetic financial records, **not customer accounting data**,
not source-form mappings approved by FIN, and not evidence of financial
predictive quality. The primary purpose is to falsify errors in the
diagnostic and report calculation engine before actual-client integration.

## Design

Data lives in the separately named PostgreSQL schema `fin_sim`:

- `dataset`: industry section, profile, version, 36-month range, expected disposition.
- `observation`: 19 metric/period combinations with FLOW or STOCK semantics,
  source-form candidate, unit/currency, explicit value states, BigDecimal precision.
- `expected_finding`: external **golden oracle**, including expected zero
  findings; each rule × finding kind has an explicit count.
- `evaluation_run`: execution record tying the synthetic case to a
  published `fin_fact` snapshot and optional persisted `fin_diag` diagnosis.

No financial records are inserted automatically by Flyway. V5 creates
the empty schema and constraints; V6 adds the read-only
`fin_sim.monthly_dashboard` SQL view for independent finance inspection
(MoM, YoY, gross margin, TTM, rolling average, cash/AR changes).
The view suppresses noncomparable currency mixes, incomplete windows,
missing COGS and zero percentage baselines as NULL rather than presenting
false numeric results. Real `ACTUAL` remains isolated.

## Data set inventory

| Property | Size |
| --- | ---: |
| Scenario profiles | 9 |
| Calendar months per scenario | 36 (Nov 2023 – Oct 2026) |
| Measures per scenario/month | 19 |
| Fully materialized observations | **6,156** |
| Golden finding conditions | **81** |
| Original form catalog entries inventoried | **166** |
| Form codes used as *illustrative synthetic aggregates* | **20** |
| Other form codes inventoried as CONTRACT_ONLY | **146** |
| Actual production-qualified source-row ingestion mappings | **0** |

The 19 metrics include monthly revenue, COGS, operating expenses,
synthetic net result, cash receipts/payments, capital expenditure,
purchases, debt draw/repayment, tax payments, returns, cash change,
sales quantities, and end-of-month cash/AR/AP/inventory/debt.

Profiles and their falsifiable expectations:

| Scenario | Industry | Expected outcome |
| --- | --- | --- |
| HEALTHY_RETAIL | G — retail | No findings: avoid false alarms |
| WORKING_CAPITAL_STRESS | G — retail | Cash/AR/revenue divergence: OBSERVATION + HYPOTHESIS |
| MARGIN_SHOCK_MANUFACTURING | C — manufacturing | Gross margin compression: OBSERVATION |
| SEASONAL_SERVICES | M — services | September→October seasonal fall but **no YoY false alarm** |
| REVENUE_DECLINE_CONSTRUCTION | F — construction | YoY revenue decline: OBSERVATION |
| MISSING_COGS_RETAIL | G — retail | Missing cost: MISSING_EVIDENCE, no fabricated margin |
| ZERO_REVENUE_BASE_SERVICES | M — services | Year-ago zero denominator: MISSING_EVIDENCE |
| MISSING_PRIOR_YEAR_RETAIL | G — retail | Missing year-ago revenue and cost: two MISSING_EVIDENCE |
| INVALID_MIXED_CURRENCY | G — retail | Refuse mixed USD and KZT series, never auto-convert |

## Accounting coherence and limitations

An independent test verifies, for every case/month, five rollforward
identities plus a simplified profit reconciliation when the required
observations are present:

```text
AR_end        = AR_begin + revenue - customer_receipts
Inventory_end = Inventory_begin + purchases - COGS
AP_end        = AP_begin + purchases - supplier_payments
Debt_end      = Debt_begin + draws - principal_repayments
Cash_end      = Cash_begin + operating_receipts - operating_payments
                - CAPEX + draws - principal_repayments
Synthetic_net_result = revenue - COGS - OPEX - modeled_tax_payment
```

Cash outflow includes supplier payments + operating expenses + modeled
tax payment. These are deliberately simplified **simulation identities**,
not IAS/IFRS/KZ-accounting classifications. The benchmark does not
demonstrate complete double-entry accounting, statutory tax calculation,
FX translation, correct cash-flow statement classification, payroll,
cost allocation, credit-rating calibration or realistic macroeconomic
risk. Never use its numbers as a production report.

The detector is tested on the **published revision path**, not merely
on a list of in-memory figures. PostgreSQL Testcontainers materializes
all `fin_sim` rows, then explicitly replays the 4 currently implemented
*diagnostic input candidates* to `fin_fact.fact_assertion`,
`fact_selection`, `fact_set_publication`, calls
`TrustedBusinessDiagnosticService.assessAndPublish`, compares all 81
oracle expectations, and persists a pass/fail evaluation record.

Only the test harness marks those sources as approved and uses
`DataSpace.SYNTHETIC`. This must never be exposed for actual reporting.
The source forms appearing beside monthly aggregates are illustrative
traceability labels, **not** real validated transformations from source
rows into domain facts. The compatibility inventory does not simulate
146 remaining forms.

## Run automatically

```sh
mvn -B -ntp -f backend/pom.xml verify
```

The new `SyntheticBenchmarkIntegrationTest` requires PostgreSQL 16
Testcontainers. It exercises numeric consistency, source/period/type,
missingness, diagnostic rule selectivity, tenant-scoped publication,
rejection and durable evaluation rows.

## Load separate tables into an isolated local database

After the application has run Flyway V5, from the repository root:

```sh
PGOPTIONS='-c fin.simulation_load_enabled=on' \
  psql -X -v ON_ERROR_STOP=1 \
  -f backend/scripts/load-synthetic-benchmark.psql "$FIN_TEST_DATABASE_URL"
```

The script is deliberately guarded and **first-load only**: a second
invocation fails on unique constraints rather than changing existing data.
Never point `FIN_TEST_DATABASE_URL` to production. It populates
`fin_sim.dataset`, `fin_sim.observation` and
`fin_sim.expected_finding`, but does **not** replay them to operational
economic fact tables; that replay only occurs in Testcontainers tests.

The checked-in TSV files can also be inspected without a database.

## Next quality gates

1. Generate **source-row conformant** fixtures for each of the remaining
   146 form codes, not synthetic aggregates disguised as real forms.
2. Add independent accountants' golden cases for F01/F02/F03 and statutory
   consolidation, tax, cash categorization and balance checks.
3. Add restatements, revisions, historical knownAt/availability,
   fiscal calendars, multiple functional currencies and FX rate sets.
4. Expand RuleRegistry beyond three rules only after economic validation;
   maintain a locked, out-of-time evaluation suite and measure false
   positives/negatives, not merely passing predetermined examples.
5. Add scenario/optimization experiments (13-week cash and constrained
   spending) with separate `PLAN`/`SCENARIO` state and assumptions.
6. Keep simulation access separated from users' real databases by DB
   roles, authorization and CI isolation. Never write synthetic facts to ACTUAL.

**Release gate:** operational diagnostic conclusions remain HOLD until
real source/metric contracts, Keycloak/ABAC/RLS and independent financial
qualification; a green synthetic suite alone is not production proof.
