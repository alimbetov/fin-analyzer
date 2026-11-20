# FIN CALC-154 — Methodology Wave 02: 6 Domain Groups and Cross-cutting Evidence

**Date:** 2026-11-20  
**Branch:** `feat/fin-form-library-standalone`  
**Input:** user-provided 106-line Markdown methodology note; SHA-256 `4f0512ad272f64374daf94978f66d1381b691ce6a6bee4d7a2d5902a580ba9db`.  
**Authority:** domain research / editorial evidence request, not independent financial S2 or automated IFRS endorsement.  
**Ledger:** `FIN-CALC154-WAVE02-METHODOLOGY-DECISION-REGISTRY.tsv` (23 requests) and `FIN-CALC154-WAVE02-ALL154-EVIDENCE-WORKLIST.tsv` (154 forms).

## 1. Separation of concerns

1. **Verified normative rule** — reference exact effective edition and standard paragraph, legal entity/reporting scope, applicable date and data evidence before claiming `STANDARD_VERIFIED`.
2. **Allowed policy choice** — store immutable versioned selection with scope and SHA, e.g., FIFO vs weighted average, depreciation method, FCFF vs FCFE; no hidden global defaults.
3. **Professional estimate / judgment** — ECL staging, NRV, CGU impairment, useful lives, discount rate, fair value; separate model inputs, reviewer and independent golden examples.
4. **Generic arithmetic** — calculate SQL/Java parity without falsely implying the financial meaning or standard compliance of a source amount.
5. **Financial S2** — independent decision and production admission remain unavailable via this source document.

## 2. Six groups: implementable proof before certification

| Group | Forms explicitly covered | First source evidence to demand | Readiness output |
|---|---|---|---|
| Credit/loans | C05; P04 | One real synthetic/anonymized loan: fees, loan balance, contractual rate, EIR schedule, allocation, prepayment and ECL assumptions | EIR vs nominal difference, schedule reconstruction, ECL stage blocked until evidence |
| PPE | C04; F01 | One asset, component lifetimes, cost/revaluation policy, impairment test | Cost model / revaluation, depreciation schedule, reconciliation into balance sheet |
| Inventory | C03 | One item or interchangeable item group with purchases, quantities, selling/finish costs | FIFO/WAC or specific-ID source proof; cost vs NRV lower valuation |
| Revenue/expenses | C07–C09; F02 | One contract with performance obligations, invoices, recognized revenue, discounts and returns | Gross-to-net, point/over-time recognition and expense reconciliation |
| Planning / valuation | P01–P10 | Explicit investment perspective, scenarios, WACC/cost of equity, FCFF/FCFE, projection and terminal assumptions | Configurable DCF and sensitivity families; never label managerial planning as IFRS-mandated |
| Reconciliation | F01; F03; F06 | Balance sheet accounts, cash opening/closing, flows and noncash financing-liability movements | Accounting balance check, IAS 7 cash bridge, financing-liability reconciliation |

## 3. Four cross-cutting policies for all 154 forms

**Basis:** exact `REPORTED` / `NORMALIZED` / `MANAGEMENT_PROJECTION` lineage. Internal FIN `FactSet` does not, merely by name, imply an external FactSet data-vendor feed or consensus forecasts.

**Currency:** monetary-vs-nonmonetary classification, transaction/cutoff, versioned FX quote, presentation currency, conversion basis and OCI/P&L where applicable; forbid implicit rates and mixed-currency sums.

**Grain:** exact original source row and durable business obligation/item identity, entity, currency, asOf/interval, dimensions and dedup, no mixing stocks across reporting cutoffs.

**Materiality:** source/reporting precision, signed-value constraints, accounting-vs-display rounding, absolute/relative reconciliation tolerance, scope and explicit exception handling. Do not silently drop below-materiality entries from source data.

## 4. Corrections to broad statements in the source memo

- The `IAS 2` general lower-of-cost-and-NRV principle is a standard rule, but cost-formula selection is constrained by inventory nature: specific identification is used for noninterchangeable items; FIFO or weighted average for ordinarily interchangeable items. No blanket free choice for every item.
- `IAS 7.44A` states a **disclosure objective concerning changes in liabilities arising from financing activities** (including cash/noncash changes). It is not itself a universal executable formula for every current liability.
- `IFRS 18` supersedes IAS 1 for annual reporting periods beginning on or after **2027-01-01**, earlier application permitted. Bind standard version/effective date to each financial method; do not silently replace historical IAS 1 references in 2026 data.
- Planning formulas (NPV/IRR, DCF) are not automatically an IFRS financial-reporting obligation, but IFRS recognition/valuation and impairment models can use forecasts; `NO_ACCOUNTING_STANDARD_APPLIES` would be too broad a claim.
- The note lists options, research questions and diagnostics; it is **not evidence that a selected policy has already been adopted** by any entity, nor proof that FIN's 154 forms contain all the fields required to execute it.

## 5. Required concrete control vectors

For each methodology decision, preserve 1–2 anonymized exact source lines and their full editable/immutable origin:
- `sourceRevisionId`, `FactSetId`, `formVersionId`, SHA, `asOf` or interval, `organizationId`/`economicEntityId`, currency, dimensions, business key.
- Original source amount, standard-aligned alternative, input parameters, formula variant, independently computed expected value, tolerance and difference reason.
- Positive and negative vectors: NULL, zero denominators, multiple currencies, duplicate business key, credit/negative position, period mismatch, stale/revoked revision, obsolete standard effective date.
- Test acceptance needs PostgreSQL/JDBC + Java `BigDecimal` parity; no `AUTO_QUALIFIED` from caller-supplied Boolean assertions.

## 6. Rollout

1. Keep existing C01/C02 exact AS_OF UAT receipts separate; their editorial V2 is not S2.
2. C03 inventory lower-of-cost-and-NRV and C04 component depreciation are proposed next manageable **one-item** E2E demonstrations, provided source fields exist.
3. C05/EIR is a separate scheduler/rate/day-count parameter contract; prohibit coding a universal EIR method without pinned cash-flow basis and external fee details.
4. F01/F03/F06 reconciliation is a separate multi-FactSet provenance contract; do not add current and prior stock periods.
5. P07 DCF/NPV/IRR belongs to versioned scenario inputs with cash-flow dates and signs; reject missing/ambiguous IRR roots.
6. Scale to the remaining 134 unscoped forms by deliberate group assignment and actual output dependencies. **A form included only through four cross-cutting controls is not methodology-complete.**

## 7. Verification

```bash
python3 scripts/verify-fin-calc154-wave02-methodology.py
```

Expected artifact invariants: 154 forms, 23 decisions, 20 explicitly scoped forms, 134 unscoped forms, 4 cross-cutting controls per form, zero false S2 claims. This validation checks the **editorial worklist only**, not mathematical or financial qualification.

## 8. IFRS Foundation reference pages (context, not blanket approvals)

- IAS 2 Inventories: https://www.ifrs.org/issued-standards/list-of-standards/ias-2-inventories/
- IFRS 9 Financial Instruments: https://www.ifrs.org/issued-standards/list-of-standards/ifrs-9-financial-instruments/
- IAS 16 Property, Plant and Equipment: https://www.ifrs.org/issued-standards/list-of-standards/ias-16-property-plant-and-equipment/
- IAS 36 Impairment of Assets: https://www.ifrs.org/issued-standards/list-of-standards/ias-36-impairment-of-assets/
- IFRS 15 Revenue from Contracts with Customers: https://www.ifrs.org/issued-standards/list-of-standards/ifrs-15-revenue-from-contracts-with-customers/
- IAS 7 Statement of Cash Flows: https://www.ifrs.org/issued-standards/list-of-standards/ias-7-statement-of-cash-flows/
- IAS 21 The Effects of Changes in Foreign Exchange Rates: https://www.ifrs.org/issued-standards/list-of-standards/ias-21-the-effects-of-changes-in-foreign-exchange-rates/
- IFRS 18 Presentation and Disclosure: https://www.ifrs.org/issued-standards/list-of-standards/ifrs-18-presentation-and-disclosure-in-financial-statements/

These links establish standard context only; qualification requires exact clauses, edition and applicability evidence as described above.
