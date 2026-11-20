# FIN CALC-154 — C01/C02 Editorial Policy V2 (2026-11-20)

**Branch:** `feat/fin-form-library-standalone`
**Decision:** explicit domain/editorial interpretation based on user's financial review (2026-11-20).
**Financial S2:** `NOT_S2_APPROVED`; **production admission:** `CLOSED`.
**Status:** `DOMAIN_POLICY_PARTIALLY_CONFIRMED`. This is not an IFRS certification, IFRS audit, or authorization to recognize a liability/asset.
**Supersedes unresolved-question entries in:** C01 editorial decision 2026-11-20, C02 methodology review v1; those records remain as historical evidence.

## 1. Rules and normative claim classification

| Domain rule | Applicability | Status / claim type | Consequence |
|---|---|---|---|
| C01 gross is the open balance after payments at exact asOf, before ECL allowance | Trade receivables pilot | EDITORIALLY_CONFIRMED / domain | Sum only one stock snapshot; never add original invoice face value to unpaid balance |
| C01 IFRS 9 gross carrying amount is not universally identical to principal-only amount | Amortised-cost financial instruments | STANDARD_CONTEXT, not a generic pilot formula | Loans/EIR require separate specific formula and input contracts |
| C01 allowance is a positive deduction | Existing C01 metric | EDITORIALLY_CONFIRMED | `SUM(gross)-SUM(allowance)`, never negative allowance convention or double subtraction |
| C01 allowance > gross is suspicious | Positive stock UAT profile | EDITORIALLY_CONFIRMED *anomaly*, not asserted IFRS prohibition | `REVIEW_REQUIRED`/block output; check wrong allocation, FX, classification; never silently cap |
| C01 zero-recovery write-off | IFRS 9 accounting process | STANDARD_CONTEXT / separate transaction | Write-off is a source event, **not an arithmetic operation** triggered by `allowance > gross` |
| C01 overdue is a positive component of gross | Current C01 analytical view | EDITORIALLY_CONFIRMED | `0 <= overdue <= gross` under positive-only pilot; no double counting |
| C01 credit balances/advances separate unless legitimate net presentation | Accounting classification | EDITORIALLY_CONFIRMED | No `abs()`, implicit signed netting, or negative receivables in the pilot |
| C02 outstanding is unpaid recognized contractual obligation at asOf | Trade payables pilot | EDITORIALLY_CONFIRMED | Carrying amount may differ from nominal for financing arrangements; do not infer principal-only |
| C02 overdue is a component of outstanding | Current C02 analytical view | EDITORIALLY_CONFIRMED | `0 <= overdue <= outstanding` under positive-only pilot; not another liability |
| C02 partial payments reduce outstanding and, per allocation rules, overdue | Payables with verified settlement ledger | EDITORIALLY_CONFIRMED principle; allocation variant pending | Precomputed input valid only with reconciliation and provenance |
| C02 VAT, interest, penalties | Depends on legal obligation and accounting recognition | CONDITIONAL_POLICY_REQUIRED | Include only amounts legally/contractually recognized in selected category and never twice |
| C02 supplier advances separate absent legitimate offset | Trade prepayments | EDITORIALLY_CONFIRMED default | IAS 32 offset only if both items are qualifying financial instruments; non-financial prepayments need separate classification |
| C01/C02 contractual due date with documented amendments | Aging and past-due checks | EDITORIALLY_CONFIRMED principle; cutoff/grace pending | `dueAt` is NOT `asOf`; original maturity cannot override proven amended maturity |
| C01/C02 totals use full input precision before display rounding | Aggregate reports | EDITORIALLY_CONFIRMED principle | Monetary scale, rounding mode, display units, tolerance and materiality remain versioned parameters |
| C01/C02 strict same-currency stock totals | All pilot aggregations | TECHNICAL_SAFETY_POLICY | No aggregation over mixed currency or different asOf |
| C01/C02 unique obligation identity | Invoice/contract/tranche/instalment depending product | POLICY_REQUIRED | `source_row_key` UUID proves technical row identity only, not accounting obligation deduplication |

## 2. Exact source and calculation contracts

**C01 catalog:** `counterparty:REF`, `gross:MONEY`, `allowance:MONEY`, `overdue:MONEY`, `dueAt:DATE`, `relatedParty:BOOLEAN`.

- REQUIRED candidate `C01.netReceivablesProposed = SUM_AS_OF(gross) - SUM_AS_OF(allowance)`; required inputs gross/allowance on **every included obligation row**.
- OPTIONAL candidate `C01.overdueGrossShareProposed = SUM_AS_OF(overdue)/SUM_AS_OF(gross)`; requires overdue on **every included row**. Zero denominator -> `UNDEFINED_ZERO_DENOMINATOR` (NULL ratio), not zero.
- `allowance > gross`, negative gross/allowance/overdue or overdue > gross => exception requiring review under current **positive-stock UAT profile**. Neither status presumes IFRS out-of-policy universally.
- Payments and invoice amendment history are **not** currently represented as typed numeric fields in C01; engine may consume a reconciled asOf snapshot, but it does NOT calculate payment allocation, ECL or write-offs from an event ledger.

**C02 catalog:** `counterparty:REF`, `outstanding:MONEY`, `overdue:MONEY`, `dueAt:DATE`, `debtCategory:CODE`.

- REQUIRED candidate `C02.totalOutstandingProposed = SUM_AS_OF(outstanding)`; required on every included row.
- OPTIONAL candidate `C02.overdueOutstandingShareProposed = SUM_AS_OF(overdue)/SUM_AS_OF(outstanding)`; requires overdue on every included row. Zero denominator -> undefined.
- Negative outstanding, overdue > outstanding, duplicate debts or unproven offset => fail-closed **in the pilot**.
- The legal basis for VAT/interest/penalties and any discounting remains a separate policy profile; the current 5 source fields cannot prove these at transaction level.

## 3. Four missing data / policy contracts (do not fabricate)

1. **Business obligation key / deduplication:** definition varies by product. Need external durable identity of invoice/contract/tranche/installment, adjustment lineage and posted cancellations. Form `counterparty` is insufficient; source row UUID alone is NOT a business key.
2. **Payment allocation / due dates:** contractual settlement order and effective amendments, event timestamps, time zone, grace day and calendar. `overdue` can be imported only with verified provenance; computing aging or `overdue` from `dueAt` alone is not qualified.
3. **Classification and valuation:** trade-vs-financing, EIR cases, supplier advance vs financial asset, VAT/interest/penalty accounting, offsets by qualifying instrument and juridical enforceability. Do not auto-apply IAS 32 to non-financial prepayments.
4. **Precision and FX:** amount currency, currency metadata, input precision, internal decimal scale, final rounding/format (including thousands/millions), FX rate version/valuation date, permissible materiality and tolerances. Current Java ratio `HALF_UP` at scale 12 is a **technical UAT choice**, not an IFRS-defined rounding rule.

## 4. Evidence sources and claim limits

Official IFRS Foundation overview/reference pages (edition/effective-date-specific texts must be checked for normative clauses):
- IFRS 9 Financial Instruments: https://www.ifrs.org/issued-standards/list-of-standards/ifrs-9-financial-instruments/
- IAS 32 Financial Instruments: Presentation: https://www.ifrs.org/issued-standards/list-of-standards/ias-32-financial-instruments-presentation/
- IFRS 15 Revenue from Contracts with Customers: https://www.ifrs.org/issued-standards/list-of-standards/ifrs-15-revenue-from-contracts-with-customers/
- IAS 21 The Effects of Changes in Foreign Exchange Rates: https://www.ifrs.org/issued-standards/list-of-standards/ias-21-the-effects-of-changes-in-foreign-exchange-rates/
- IFRS 18 Presentation and Disclosure in Financial Statements (effective annual periods beginning 2027-01-01, earlier application permitted): https://www.ifrs.org/issued-standards/list-of-standards/ifrs-18-presentation-and-disclosure-in-financial-statements/

These are context references. Do not falsely mark every analytical ratio or input-sign control as `MANDATED_BY_IFRS`. Distinguish `USER_DOMAIN_DECISION`, `PUBLISHED_STANDARD_CONTEXT`, `NORMATIVE_CLAUSE_VERIFIED`, and `LOCAL_TECHNICAL_SAFETY`.

## 5. UAT acceptance evidence and constraints

- Exact active `TEST_QUALIFIED` synthetic FactSet, organization/project/entity/currency, version-pinned source revision and SHA rehash by INGEST-2.
- One AS_OF stock cut-off; no `SUM_FLOW` replacement, cross-currency conversion or hidden credit-note netting.
- PostgreSQL SUM of typed observations and Java `BigDecimal` must agree; zero and missing values have separate status.
- UAT result is append-only, idempotent and may not be promoted to production.
- New independent financial S2 scope/decision required even after editorials; `AUTO_QUALIFIED` != `FINANCIAL_S2_APPROVED`.

**Next evidence request:** actual organization payment allocation policy; examples for partial payments, credit notes, unusual allowances, supplier advances, EIR/financing cases, and a unique source obligation key. Until these are supplied, C01/C02 positive-stock UAT calculations may be technically executed, but financial completeness is still blocked.
