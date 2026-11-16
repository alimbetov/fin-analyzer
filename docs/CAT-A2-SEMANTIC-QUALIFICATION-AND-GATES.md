# FIN CAT-A2 — finance/sector semantic candidate qualification

Status: **CANDIDATE / INDEPENDENT S2 HOLD**. Branch: `feat/fin-r0-r1a`. Production financial advice and trusted fact promotion are forbidden.

## Scope and source of truth

First-wave review covers 18 forms / 92 fields in `docs/CAT-A2-PILOT-FIELD-REVIEW.tsv`:
- F01–F03: source financial statements, 13 candidate fields.
- DF-CSH-01…06: cash ledger / balances / receivables / payables / debt schedule, 30 fields.
- SF-G01…03, SF-M01…02, SF-S01, SF-C01…03: nine trade, service and manufacturing forms, 49 fields.

The canonical `form-compatibility.tsv`, `specialized-schema-candidates-r6.tsv` and `form-field-presentation-review-r6.tsv` remain authoritative for registered source field identities, **not** accounting-method approval.

## Executable isolated proof

- `packages/sector-pilot/src/cat-a2-candidate-semantics.mjs`: dependency-free BigInt fixed-scale (12 decimal places), explicitly synthetic-only candidate proofs. Does NOT write, publish, authorize, or connect to PostgreSQL.
- `packages/sector-pilot/tests/cat-a2-candidate-semantics.test.mjs`: positive numerical golden cases, negative input/scope cases and fail-closed production approval tests.
- `packages/sector-pilot/tests/catalog-a1-a2-integrity.test.mjs` and `catalog-a2-review-contract.test.mjs`: per-code registry integrity and 92-field inventory drift detection.

Run `npm --prefix packages/sector-pilot test` (Node >=22) and retain the actual CI log/SHA. Creation of test files alone is NOT a PASS claim.

## Methodological qualification constraints

| Form(s) | Candidate arithmetic | Specific limitation before independent approval |
|---|---|---|
| F01 | assets - liabilities - equity | Precision, accounting basis, consolidated adjustments, materiality/rounding |
| F02 | netRevenue - cogs = gross profit | Net profit cannot be reconciled from these columns alone; tax, finance and other income/expense may be absent |
| F03 | closingCash - (openingCash + CFO + CFI + CFF) | FX effects, scope of cash equivalents and other reconciling items must be separately modeled |
| DF-CSH-01/02 | Positive recorded payment magnitude | Sign convention, payment direction, reversals, transfers and unique payment IDs |
| DF-CSH-03 | restricted <= balance (candidate) | Restricted/reserve overlap; currency/account basis and as-of date |
| DF-CSH-04/05 | recognized - received; amount - paid | Negative residual is not necessarily a defect; prepayments, returns, allocations, cut-off and aging |
| DF-CSH-06 | Nonnegative principal / interest / balance | Cannot prove closing debt without opening balance, drawdowns and repayments |
| SF-G01 | netRevenue - cogs | Revenue recognition, tax/returns, dimension granularity |
| SF-G02 | Input validation only | Average stock basis, same time window and inventory valuation |
| SF-G03 | Input validation only | Whether purchasePrice is per unit, whether freight is total |
| SF-M01 | billableHours <= availableHours | Time semantics and contractor/overtime treatment |
| SF-M02 | recognizedFee - directHours × hourCost - otherCost | Rate unit, recognition and allocation |
| SF-S01 | netRevenue - directLaborCost - materialsCost | Cost scope, refunds, definition of completed job |
| SF-C01 | goodUnits <= unitsStarted | Rework may overlap good/scrap; no unsupported quantity conservation equation |
| SF-C02 | materialUsed + directLabor + overhead | Work-in-progress, absorption, allocation policy |
| SF-C03 | runHours <= plannedHours | Planned/run/downtime intersection and unscheduled overtime |

**No current test result may be promoted to `INDEPENDENT_S2_APPROVED`.** The isolated proof module always rejects attempts to authorize trusted diagnosis, even if a client submits a forged approval status.

## Approval workflow and acceptance gate

1. Finance reviewer independently documents grain, accounting framework, sign, stock/flow, unit, currency, recognition rules, date cut-off and comparability for each field.
2. Independent golden and counterexamples include negative values/reversals where legitimate, explicit null vs zero, duplicates, different calendars, FX, and period boundaries.
3. FormSchema + Excel + React + Java normalize identically; `sourceRevisionId`, row, field, report basis and project/organization are server-owned and auditable.
4. Review decision records method version, reviewer identity, source, effective date and limitations. Never auto-promote `DESIGN_ONLY` or `CANDIDATE_NOT_APPROVED`.
5. Integrate only separately approved methods into the server-side eligibility/Methodology Registry; enforce published FACT lineage, OIDC/ABAC and tenant isolation.
6. Obtain Node, JUnit, PostgreSQL Testcontainers, Playwright and macOS GREEN on one SHA; independently verify actual React→API→PostgreSQL→diagnosis E2E.
7. Until all gates are satisfied, status remains **HOLD**, and outputs are synthetic candidate observations only.

Do not expand to the other forms before finance review of the first wave; do not change `main`, weaken fact approval, or equate structurally valid source data with a financial diagnosis.
