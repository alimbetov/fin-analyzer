# FIN CALC-APPS-R0 — Six Financial Calculation Applications

Date: 2026-11-20
Branch: `feat/fin-form-library-standalone`
Status: `SCHEMA_CANDIDATE` / `FINANCIAL_S2_HOLD` / `PRODUCTION_CLOSED`.

## Design decision

The six approved application **concepts** CALC-A01..A06 are reusable calculation-input contract families over the existing 166 FIN form catalog. They are **not six new reporting forms**, not six duplicated calculation engines, and not financially approved mathematical methods.

The source forms remain the authoritative editor/ingestion entry points (fin_intake). The apps normalize and reconcile exact versioned source facts, then feed existing fin_calc hybrid SQL/JDBC and Java BigDecimal calculators once financial methodology and required sources become qualified.

**Implemented in this increment:**
- PostgreSQL migration 054: six immutable `fin_calc.calc_application_definition` and versioned `fin_calc.calc_application_contract_revision` entries.
- PostgreSQL verifies SHA-256 of app code, revision, candidate source-form list, candidate typed fields, output codes, variants and blocker text at insertion. Updates/deletes/truncation denied by existing immutable trigger; every record has `financial_s2_approved=false`, `production_admitted=false` enforced.
- `CalcApplicationContractRegistry.all()` reads and rehashes the registry, `requireSourceCatalogIntegrity()` verifies **actual 166-form bootstrap catalog** references. No HTTP write or execution path.
- `CalcApplicationContractPostgresqlTest` defines four PG16 Testcontainers gates (six contracts/versions; actual source forms; immutability; forged SHA and untyped expressions).
- No public React interface, no business-data ingestion into these app contracts, no UAT or production CalcPlan admission.

**Crucial scope clarification:** `candidate_input_fields_jsonb` is the **union of plausible inputs** across alternative methods. These are NOT all universally mandatory. After variant/policy selection, author version-pinned `REQUIRED`, `CONDITIONAL`, `OPTIONAL` field rules per output. Never turn missing information into zero.

## Application contracts

| Code | Form sources (existing, not duplicated) | Required next technical data contract | Method choices to version | Candidate outputs |
|---|---|---|---|---|
| CALC-A01 | C01, C02, C11, DF-CSH-02/04/05/06 | Stable invoice/contract/installment key, adjustment/settlement ledger, authoritative effective due dates, replay-safe payment allocation | Contract-specific allocation, earliest-due FIFO or explicit posted allocation | Open balance, overdue amount, days past due, aging |
| CALC-A02 | C01, C05, DF-CSH-04, SF-K01 | ECL exposure cohort, ageing buckets, historical losses, staging/credit-risk decisions, forward-looking evidence | Simplified IFRS 9 ECL matrix; full IFRS 9 ECL; optional risk PD/LGD/EAD | ECL, allowance coverage, risk exposure, stage/bucket |
| CALC-A03 | C03, DF-STK-01/02/03 | Purchase lot IDs, stock movements, returns/adjustments, unit conversions, item-level selling assumptions | FIFO, weighted average, specific identification subject to inventory type | Cost of sales, closing inventory, NRV, write-down |
| CALC-A04 | C04, F01, DF-AST-01 | Component identity, useful life, residual value, in-service date, cost/revaluation and impairment events | Straight line, reducing balance, units-of-production; class valuation policy separately | Depreciation, accumulated depreciation, carrying amount, impairment |
| CALC-A05 | C05, P04, P09, DF-CSH-06, DF-CSH-02 | Contract versions, event-dated repayments, fees, cash-vs-accrual classification, day count, modifications | Nominal contractual rate, EIR, annuity, equal principal — note these are **different dimensions** and may be combined, not mutually exclusive single enum | Cash debt service, carrying value, EIR, balance, cash interest |
| CALC-A06 | P01–P09, DF-PRJ-03/05 | Scenario and cash-flow revision ID, dated flow and rate-source revisions, terminals, currency, scenario/plan compatibility | FCFF/WACC for Enterprise Value; FCFE/Ke for Equity Value | NPV, root-aware IRR, EV, Equity Value, scenario difference |

## Financial safety and interpretive rules

- A01: `overdueDays=max(0,asOf-effectiveDueAt)` is time, not monetary overdue; overdue money must be calculated from unpaid due installments after authorized cash allocations. Never double subtract a payment already in the asOf snapshot.
- A02: `allowance > gross` is an anomaly requiring review in the positive-stock pilot, not a universal IFRS prohibition and not an instruction to write off. A write-off is an independently authorized accounting event. Loss-rate adjustment is a reviewed estimate, not a universal IFRS-specified `*(1+factor)` function.
- A03: lower-of-cost-and-NRV must be based on sufficiently granular inventory items. The approved cost-flow formula depends on interchangeable vs non-interchangeable items. Generic NRV is not an inventory market price.
- A04: useful life, salvage value, components and revaluation/impairment effects are separate policy variants. OCI treatment cannot be assumed from asset type alone.
- A05: EIR for amortized cost and contractual cash debt service for DSCR **must not be conflated**. A five-year 980 initial carrying / 80,80,80,80,1080 annual cash-flow example has EIR approximately 8.5076%, not 10.2%. Nominal-vs-EIR is distinct from annuity-vs-equal-principal schedule construction.
- A06: `FCFF+Ke` / `FCFE+WACC` are invalid stock DCF combinations. P07 currently holds references, not complete typed underlying dated flows. A cashflowRevisionId value alone is insufficient without trusted source dereferencing and SHA.

## Core lineage / architecture

```text
166 existing form editors (C, F, P, DF, SF; D/T are documents)
    -> fin_intake (immutable exact source revisions)
    -> INGEST-2 verified source reconstruction
    -> fin_fact (version-pinned qualified FactSet)
    -> CALC-A0X typed input binding (future, not implemented)
    -> methodology variant + parameter revision (existing 051)
    -> immutable CalcPlan tied to app contract SHA, source FactSet SHA,
       source revisions, tenant/project/scenario + methodology selection SHA
    -> SQL/JDBC aggregation -> Java BigDecimal final -> parity
    -> immutable result + explainability and cross-form reconciliation
```

App registry is **configuration metadata**; not a substitute for exact business identity, source events or financial policy. A01/A02/A03/A04/A05/A06 must have distinct normalized source event schemas, with common append-only version/lineage semantics. Prefer typed relational tables for money/date/quantity keys and JSONB only for bounded, schema-validated method-specific parameters. Do not execute text supplied as a formula.

## Next implementation waves

- **CALC-APPS-R1 / A01:** one obligation end-to-end with two settlements, due-date amendment, no double payment and identity-based deduplication. Cash/current receivables and payables must reconcile.
- **CALC-APPS-R2 / A05:** one exact loan, separate contractual cash schedule and accounting effective-interest schedule, versioned day count, early payoff and P04/P09 parity.
- **CALC-APPS-R3 / A06:** one dated scenario, referenced source FactSet, FCFF/WACC vs FCFE/Ke; reconcile scenario to P01–P09. Qualified IRR needs root and date convention.
- **CALC-APPS-R4 / A02+A03+A04:** batch ECL controls, item-level inventory lot and NRV E2E, component depreciation/impairment with independently reviewed evidence.

Never open production automatically at the end of a UAT wave. Require independent financial S2, technical CI, real data source/canonical SHA verification, tenant authorization, and qualified plan admission.

## Acceptance

```bash
mvn -B -ntp -f form-library/pom.xml test \
  -Dfin.postgres.uat.enabled=true \
  -Dtest=CalcApplicationContractPostgresqlTest \
  -DfailIfNoTests=true
```

CI: `.github/workflows/fin-calc154-six-app-contracts-pg16.yml`. It requires exactly **4 passed, zero skipped**, using PostgreSQL 16 + Liquibase and real Java catalog bootstrap. A green build would prove only **schema/read compatibility**, not any financial calculation or S2. At commit time the workflow result is **unverified**.

The repository's `form-library/src/main/resources/db/changelog/052-fin-calc154-user-methodology-research-postgresql.sql` regex syntax was also repaired because it blocked new-schema initialization.
