# FIN CALC-154 — Financial Methodology Evidence Request (C01/C02 first)
Date: 2026-11-20
Branch: feat/fin-form-library-standalone
Status: editorial source-request packet; **not** financial S2 or production approval.

## What is ALREADY decided for C01
- gross receivable before allowance
- allowance is a nonnegative positive deduction
- overdue is a subset of gross, never added again
- reporting cut-off asOf comes from exact versioned FactSet

Do not request these same decisions again.

## What a financial methodology source must contain
1. Exact title, author/publisher or issuing body, document date, edition, jurisdiction and applicable reporting basis.
2. URL/page/paragraph or extract supplied with permission; specify whether rule is binding, permitted, recommended, illustrative, or entity-specific.
3. Input definitions: gross/net, inclusion of tax/interest/penalty, signs, credit notes, partially settled liabilities, source identity/grain.
4. Time: asOf cut-off, due date, holiday calendar/timezone, partial settlement recognition, grace periods.
5. Currency: homogeneous currency reporting; if FX, rates, rate date, source, precision and version.
6. Null/zero/negative, exceptions, strict decimal scale, rounding (intermediate/aggregate), tolerances.
7. Test vectors: at least ordinary, empty/zero denominator, missing field, negative adjustment, partial settlement, duplicate obligation, currency mismatch, boundary date and revised/revoked source.
8. Explicit output requiredness: REQUIRED/OPTIONAL/SOURCE_ONLY and source metrics for each output.

## C01 outstanding policy decisions that block financial qualification
| Code | Missing decision | Impact |
|---|---|---|
| C01-01 | Is gross the open debt **after** payments but **before** allowance? Account for returns, credit notes, advance/customer credit balances | netReceivables |
| C01-02 | Source obligation key = invoice/contract/installment? Multiple rows and corrections/cancellations? | SUM_AS_OF dedup |
| C01-03 | Is allowance bounded by gross at row/portfolio level? Exceptions for recoveries? | positive stock invariant |
| C01-04 | How is overdue portion determined by dueAt, cut-off, grace period, partial payments and rescheduling? | overdue share/aging |
| C01-05 | FX treatment, grouping unit, KZT currency decimals/rounding and aggregated-level rounding | all metrics |

Existing editorial examples: 100 gross - 10 allowance = 90; 20 overdue / 100 gross = 0.2. Synthetic, not independent finance evidence.

## C02 decisions that block financial qualification
| Code | Missing decision | Impact |
|---|---|---|
| C02-01 | Definition of outstanding at asOf: open full obligation or principal only; taxes, accrued interest, fines, disputed amounts | totalOutstanding |
| C02-02 | Is overdue a positive **subset** of outstanding? What are grace periods, partial settlement, credit notes and debt reclassification rules? | overdue share |
| C02-03 | Netting with supplier advances / debit balances allowed or prohibited; individual negative positions | totals |
| C02-04 | Durable identity = invoice, contract, installment or payment schedule line? Prevent duplicates and reversals | SUM_AS_OF |
| C02-05 | Due date source, business calendar, cut-off; distinguish dueAt from FactSet asOf | aging |
| C02-06 | Currency/FX and rounding/precision/zero denominator | all metrics |

C02 outstanding=60+40 -> 100; overdue=10+10 -> 20 -> share 0.2 is only a synthetic worked arithmetic case.

## Priority search across the remaining 152 forms
- Balance and reconciliation: F01/F03/F06/F07/C03/C04/C06/C10 — sign conventions, stock vs flow, opening/closing positions, depreciation, corrections.
- Revenue and costs: C07/C08/C09, F02, P02 — gross vs net sales, returns, capitalization, fixed/variable costs, discounting.
- Debt/lease: C05/C13/P04 — day-count basis (ACT/365, ACT/360, 30/360), accrual schedule, EIR, fee amortization, resets, installments and early payments.
- Planning and investment: P01-P10 — base/scenario, cash flow timing, NPV, IRR, discount rate, terminal value, assumptions, tolerances.
- Aging/collections: C01/C02 and DF-CSH-01..06 — aging buckets and overdue definitions.
- Broad analytical catalog: grouping (127 design candidates), sum (128), period comparison (74), ratio (25) are *operator suggestions*, not financial approvals. Output names and semantics must first be established for each form.

The implementation may automatically verify generic arithmetic; an international standard reference must be exact and independently verifiable. Where an official standard permits accounting-policy elections, the chosen company policy must be versioned as a separate parameter contract. Independent financial S2 is an additional gate and remains HOLD.

## Engineering status
CALC-C01/C02 AS_OF UAT kernel and JDBC/BigDecimal parity code has been authored, with opt-in PostgreSQL tests, but not yet independently GREEN-confirmed. It is a positive-stock provisional UAT policy only and **not** an approved universally applicable reporting method. No mass financial method authoring is asserted merely by classification.
