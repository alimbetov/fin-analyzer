# FIN CALC-154 — C02 Payables | Methodology review v1

**Form:** C02 — Реестр кредиторской задолженности.
**Status:** `AWAITING_EXPLICIT_DOMAIN_REVIEW`; **NO FINANCIAL S2**; **NO qualified CalcPlan**.
**Sources:** `FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv`, `FIN-CALC154-FINANCIAL-REVIEW-WAVE-01-C01-C10.tsv`, `FIN-CALC154-METRIC-COVERAGE.tsv`.
This is a candidate editorial packet, not an assertion of the underlying accounting policy.

## Exact source fields; do not invent fields

| Code | Catalog type | Proposed output dependency | Proposed source requiredness |
|---|---|---|---|
| `C02.counterparty` | REF | vendor/counterparty breakdown | CONDITIONAL (subject to review) |
| `C02.outstanding` | MONEY | total outstanding, overdue share denominator | REQUIRED for both outputs (review) |
| `C02.overdue` | MONEY | overdue share numerator | REQUIRED for overdue share only (review) |
| `C02.dueAt` | DATE | due date/aging grouping | CONDITIONAL for aging; not report asOf (review) |
| `C02.debtCategory` | CODE | category breakdown | CONDITIONAL for category breakdown (review) |

Organization, legal entity, currency and snapshot `asOf` must be bound by the FactSet/plan scope and audited; they are NOT claimed to be C02 row fields. No durable payable obligation ID appears in the 5-field list; it is a **contract gap**, not a field to infer from `counterparty`.

## Proposed outputs

### C02.totalOutstandingProposed — REQUIRED candidate
```text
totalOutstanding = SUM_AS_OF(C02.outstanding)
```
- Grain: one legal entity / organization, FactSet asOf, currency; counterparty/category grouping optional.
- Time: immutable `AS_OF` stock. Never sum the same obligation across two closing snapshots.
- Currency: one currency per sum; cross-currency consolidation requires a separately approved FX contract.
- The meaning of `outstanding` (principal only vs full payable, gross/net, taxes, accrued interest, credits and reversals) is NOT confirmed.

### C02.overdueOutstandingShareProposed — OPTIONAL candidate
```text
overdueOutstandingShare = SUM_AS_OF(C02.overdue) / SUM_AS_OF(C02.outstanding)
```
- Output: dimensionless ratio (`0.20` = 20%).
- Proposed `overdue` is an asOf component of `outstanding` — **UNCONFIRMED** pending domain review.
- For denominator=0, return `UNDEFINED_ZERO_DENOMINATOR`, not an invented zero.
- `dueAt` may classify payable as overdue only if the contractual grace period, timezone, asOf and partial-payment rules are explicitly defined.

## Proposed policies and blockers

| Area | Safe interim stance | Required domain decision |
|---|---|---|
| Unique payable position | BLOCK duplicate obligation snapshots; no counterparty-only deduplication | durable obligation key; invoice/contract/installment/line grain |
| Sign and offsets | no implicit abs(), netting or sign reversal | are credit balances/advances negative? can outstanding be negative? |
| `overdue` scope | do not assume correspondence to positive outstanding without review | gross vs net overdue, partial settlement, penalties, grace period |
| As-of | exact FactSet asOf, not `dueAt` | business calendar, cut-off and settlement posting rules |
| Missing values | required NULL/missing -> BLOCKED | absent vs actual zero; optional row exclusions |
| Currency | strict single-currency group | FX source, rate date, versions and rounding |
| Numeric | BigDecimal arithmetic | currency scale, accounting rounding, overflow/tolerance |
| Dimensions | legal entity/asOf/currency | counterparty and category unknown/multi-membership policy |
| Evidence | immutable source hashes + independent finance examples | accounting policy source, owner and financial S2 later |

`SUM_AS_OF` is a proposed operator family/gap; do NOT substitute generic multi-period `SUM_FLOW`.

## Synthetic controls (arithmetic only, not financial evidence)

| Scenario | Outstanding | Overdue | Total | Share |
|---|---:|---:|---:|---:|
| Two same-currency payable positions | 60.00 + 40.00 | 10.00 + 10.00 | 100.00 | 0.20 |
| No overdue | 100.00 | 0.00 | 100.00 | 0 |
| Zero outstanding | 0.00 | 0.00 | 0.00 | UNDEFINED_ZERO_DENOMINATOR |
| Suspect overdue | 100.00 | 110.00 | BLOCKED_PENDING_SCOPE_POLICY | BLOCKED_PENDING_SCOPE_POLICY |
| Different currencies | 60 KZT + 40 USD | n/a | BLOCKED_CURRENCY_MISMATCH | n/a |

## Decisions requested for C02 editorial sign-off

1. Does `outstanding` represent the payable balance as of FactSet before netting creditor advances, including which taxes/interest?
2. Is `overdue` a subset of outstanding, and is it gross or net? How are partial payments and credit notes handled?
3. Is `dueAt < asOf` the default overdue criterion, or are day cut-offs, grace periods and explicit payment status authoritative?
4. What is the durable payable obligation business key? How are installment rows and duplicate invoices identified?
5. What sign, missing, FX, precision and rounding policies apply?

**Approval boundary:** editorial confirmation (if obtained) changes domain-review state only, never financial `S2_APPROVED`. Independent S2, qualified plan admission and production remain on HOLD.
