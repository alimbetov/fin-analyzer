# FIN R16.B / R7.B — F01 and F03 calculation proposals (NOT financially approved)

**Source:** real current R6 form schemas, branch feat/fin-form-library-standalone.  
**Status:** `CANDIDATE_UNQUALIFIED_NOT_S2`. Both methods require independent finance review, typed input mapping contracts, signed decisions, qualified FactSet(s), strategy-aware executor and full E2E before qualification.

## F01 balance (SINGLE_RECORD)

Actual physical fields: `assets:MONEY`, `liabilities:MONEY`, `equity:MONEY`, `asOf:DATE`. Proposed arithmetic reconciliation (`fin_calc.f01_candidate_balance_residual` / `FinancialReconciliationProposals.f01Residual`):

`residual = assets - liabilities - equity`.

Returns only a signed residual, **not** "balanced"/"verified" status. Financial review required for accounting standard, equity-deficit signs, consolidated vs entity scope, currency policy, exact AS_OF date, ledger-to-balance reconciliation, partial balance reporting and materiality/tolerance policy. Existing `fin_fact.fact_observation` numeric-only storage cannot carry the `asOf` date as a numeric observation; qualified ingestion has not been implemented.

## F03 cash flow (SINGLE_RECORD)

Actual physical fields: `openingCash:MONEY`, `cfo:MONEY`, `cfi:MONEY`, `cff:MONEY`, `closingCash:MONEY`. Proposed roll-forward (`fin_calc.f03_candidate_cash_residual` / `FinancialReconciliationProposals.f03Residual`):

`residual = openingCash + cfo + cfi + cff - closingCash`

**Unreviewed assumption:** cfo, cfi and cff are signed net flows. Finance review required for IFRS/standard basis, FX effect on cash, cash equivalents, restricted cash, unit/currency consistency, coverage/grain, related-party consolidations, rounding policy and period interval. No invented sixth FX field or scalar output is permitted.

## Technical implementation boundary

- PostgreSQL additive migration `030` defines two pure, side-effect-free `IMMUTABLE PARALLEL SAFE` arithmetic candidates. They never read `fin_intake` or issue S2.
- Java `FinancialReconciliationProposals` accepts typed input data and computes mathematically exact decimal residuals without making an unapproved tolerance/rounding decision.
- PostgreSQL Testcontainers test `R16F01F03CandidatePostgresqlTest` verifies actual field codes, their draft status, 100 random parity vectors plus boundary cases, invalid date/period rejection, and zero persisted facts/results/methods.
- **Not an E2E:** neither F01 nor F03 has admission, finance review, qualified facts, CalcPlan/Job or immutable result. They must remain blocked from HYB-2B production execution.
- **Next work:** finance reviewer signs source field-to-metric and output semantics, versioned period/dimensions/currency contract, independent S2 gate; then implement F01/F03 qualified FactSet and strategy-aware worker with PostgreSQL negative/replay tests.

Tests and docs are not a substitute for independent financial approval.
