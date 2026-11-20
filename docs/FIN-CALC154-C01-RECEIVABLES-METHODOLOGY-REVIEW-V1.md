# FIN CALC-154 — C01 Accounts Receivable | Methodology review v1

**Form:** C01 — Реестр дебиторской задолженности.  
**Canonical sources:** `editorial-titles-finance-r15.tsv`, `form-field-presentation-review-r6.tsv`, and `FIN-CALC-R0-COVERAGE-166-BASELINE.tsv`.  
**Status:** `DOMAIN_POLICY_PARTIALLY_CONFIRMED` by editorial decision 2026-11-20; **NO FINANCIAL S2**, **NO executable qualified CalcPlan**. See [exact binding editorial decision](FIN-CALC154-C01-EDITORIAL-DECISION-2026-11-20.md).  
**Review wave:** `FIN-CALC154-FINANCIAL-REVIEW-WAVE-01-C01-C10.tsv`.

## Existing exact source fields (do not fabricate alternatives)

| Code | Catalog type | Proposed use | Requiredness decision |
|---|---|---|---|
| `C01.counterparty` | `REF` | debtor and optionally reporting dimension | REVIEW_REQUIRED |
| `C01.gross` | `MONEY` | gross receivables amount (candidate interpretation) | REVIEW_REQUIRED |
| `C01.overdue` | `MONEY` | overdue receivables (candidate interpretation) | REVIEW_REQUIRED |
| `C01.allowance` | `MONEY` | expected credit loss/impairment allowance (candidate interpretation) | REVIEW_REQUIRED |
| `C01.dueAt` | `DATE` | contract due date, not necessarily report as-of date | REVIEW_REQUIRED |
| `C01.relatedParty` | `BOOLEAN` | related-party breakdown | REVIEW_REQUIRED |

No `asOf` source field exists in C01. The exact reporting date must be pinned by the FactSet/reporting-period contract; it MUST NOT be silently substituted with `dueAt`.

## Explicit proposed outputs (not yet financial approvals)

### C01.netReceivablesProposed — REQUIRED proposal

For a **single currency**, a **single confirmed as-of date**, one organization/economic entity, and non-duplicated debts:

```text
grossReceivables = SUM_AS_OF(C01.gross)
allowanceTotal = SUM_AS_OF(C01.allowance)
netReceivables = grossReceivables - allowanceTotal
```

`SUM_AS_OF` is a METHOD REQUIREMENT / OPERATOR GAP; it must not be implemented using unreviewed `SUM_FLOW` over multiple reporting dates. Definition relies on **allowance stored as a positive deduction** and `gross` meaning receivables BEFORE allowance. Both assumptions were confirmed editorially on 2026-11-20; all other policies remain unreviewed. A source `NULL` allowance is **not** automatically zero; missing-policy review is mandatory.

### C01.overdueGrossShareProposed — OPTIONAL proposal

```text
overdueGrossShare = SUM_AS_OF(C01.overdue) / SUM_AS_OF(C01.gross)
```

Unit: `RATIO` (e.g. `0.20` = 20%), not a monetary currency value. Exact numerator convention (gross overdue vs net overdue), duplicates, negative credit notes and division-by-zero policy require review.

### Time, dimensions and currency

- **Time:** `AS_OF` snapshot, never sum the same debt balance across month-end snapshots. A source `dueAt` may classify a debt as overdue but cannot replace paid/unpaid aging facts.
- **Dimensions:** organization, economic entity, counterparty, `relatedParty`, reporting-as-of date. `dueAt` is an attribute for aging buckets and grouping policy; not a separate duplicate total.
- **Currency:** one common currency per aggregation. Cross-currency portfolio values require an explicit approved FX rate source and `rateDate`, otherwise `BLOCKED`.
- **Numeric:** `BigDecimal`; financial display scale, rounding, sign and tolerance are `REVIEW_REQUIRED`. UAT 12-digit ratio precision is **not a financial policy**.
- **Missing / duplicate:** required source missing ⇒ `BLOCKED`; duplicate same debt position on same snapshot ⇒ `BLOCKED`, not implicit addition.

## Synthetic review cases (not independent financial examples)

| Case | Gross | Allowance | Overdue | Proposed net | Proposed overdue share |
|---|---:|---:|---:|---:|---:|
| Regular | 100.00 | 10.00 | 20.00 | 90.00 | 0.20 |
| No overdue | 100.00 | 0.00 | 0.00 | 100.00 | 0 |
| Zero gross | 0.00 | 0.00 | 0.00 | 0.00 | INVALID_ZERO_DENOMINATOR |
| Suspicious | 100.00 | 120.00 | 110.00 | BLOCKED_PENDING_SIGN_RULE | BLOCKED_PENDING_SCOPE_RULE |

The examples demonstrate arithmetic only, NOT a signed finance definition. A real implementation must add independently sourced and approved finance examples, including adjustments, credit balances, partial settlements, duplicate rows and multiple currencies.

## Required domain decisions before editorial sign-off

1. **EDITORIALLY CONFIRMED 2026-11-20:** `gross` is pre-allowance and `allowance` a nonnegative deduction.
2. **PARTIALLY CONFIRMED:** `overdue` is part of `gross`, not additive; credit balances, partial payments and contractual due-date policy remain open.
3. **CONFIRMED:** reporting `asOf` comes from the exact FactSet; granular cutoffs still need policy.
4. Is the source row a unique receivable position? Specify its durable business key; `counterparty` alone may aggregate multiple obligations.
5. Confirm treatment of zero/NULLs, allowed negative balances, FX, numeric scale and rounding.

**Important:** Approval of this review proposal by the requester means `DOMAIN_POLICY_REVIEW_CONFIRMED` for the exact editorial revision *only*. It is not the cryptographic financial `S2_APPROVED` that the production admission requires.
