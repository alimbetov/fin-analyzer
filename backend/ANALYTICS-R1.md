# FIN Analytics — first executable backend slices

Status: **CALC_PILOT** / method qualification and server integration OPEN.

This is not production financial reporting, investment advice, an official construction
estimate, or a claim that all 166 input forms work. The 42-row
`analytics-model-catalog.tsv` is a traceability proposal linking 42 candidate
professional analytical tables to existing FIN FormCodes. Only a subset of the
calculation arithmetic is currently executable.

## Implemented pure Java slices (no React changes)

1. `ThreeStatementModel` — simplified profit & loss, cash flow, closing balance
   from a **balanced opening balance** and explicit movement drivers.
   Enforces same currency, typed flow period, no hidden balancing plug,
   nonnegative debt/PPE/receivables/inventory, a liquidity warning.
   **Limitations:** interest and tax costs assumed paid, no leases,
   depreciation adjustments beyond one expense, no deferred taxes, no other
   asset/liability categories, no FX, no statement/account mapping. Not an
   IFRS-qualified financial statement.
2. `InvestmentValuation` — annual, equally-spaced end-of-period flows,
   t0 undiscounted, FCFF/WACC or FCFE/cost-of-equity pair, explicit optional
   Gordon-growth terminal value from a separately supplied normalized FCF.
   Exact decimal input, HALF_EVEN 12-dp PV rows with MathContext(50).
   **Limitations:** not dated XNPV, not IRR/MIRR, not WACC derivation,
   not enterprise-to-equity bridge, no market-derived risk inputs or FX.
   Method assumes effective annual discount rates and no inflation mismatch.
3. `ResourceCostEstimate` — BoQ quantity × per-work-unit resource usage ×
   approved unit price, plus *explicit* overhead and contingency fractions.
   Stable report line ordering, duplicate-line and duplicate-resource guards,
   same-currency validation, no silent truncation.
   **Limitations:** synthetic resource rate versions (no verified country rate
   book), no detailed standards, VAT, contract rules, escalation/indexation,
   change orders, cashflow schedule or earned value qualification.
4. `IndicatorReadiness` — pure recursive DAG readiness diagnostics and
   last/current input fingerprint comparison; reports missing/unapproved/
   invalid inputs and STALE vs CURRENT.
   **Limitations:** caller-provided fingerprints are not independently verified;
   no authorized projection, persistence, dynamic form registry or outbox.

No API, application service, JPA write, real fiscal-data import, report rendering,
formal source approval, tenant policy or financial statement publication is present.
Using these classes with user data before provenance and calculation-context
hardening is prohibited.

## Run

```bash
mvn -B -ntp -f backend/pom.xml verify
```

GitHub Actions uses Java 21 and PostgreSQL 16 Testcontainers.
The new pure Java calculation tests are independent of Docker.

## Next gates

- P0: approved source revision and `FactResolver` with immutable finalized sets,
  consistent period/currency/statement basis and journal/statement reconciliation.
- P0: executable FormRegistry/MappingRegistry/MetricRegistry methods and
  IndicatorReadiness trusted fingerprint generation.
- P1: XNPV/IRR, WACC methodology, three-statement loan/lease/differed tax,
  reviewed country-specific estimate ratebooks and earned-value semantics.
- P1: actual PostgreSQL CalculationRun / IndicatorSnapshot / Outbox and
  end-to-end recalculation after period correction.
- P1: independent accountant, investment analyst and cost engineer golden
  methodology review before marking a method QUALIFIED.
