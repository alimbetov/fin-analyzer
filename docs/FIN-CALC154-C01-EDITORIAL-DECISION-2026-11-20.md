# FIN CALC-154 — C01 Receivables | Editorial decision ED-2026-11-20

**Form:** C01 — Реестр дебиторской задолженности.
**Status:** `DOMAIN_POLICY_PARTIALLY_CONFIRMED` (editorial only; extended by V2 below).
**Financial approval:** `S2_NOT_APPROVED`; no qualified CalcPlan, production execution or production admission.
**Authority:** requester's editorial decision, 2026-11-20. This document does not constitute independent financial review.

## Confirmed semantics (binding for subsequent editorial methodology work)

1. `C01.gross` is the gross receivable **before** impairment allowance.
2. `C01.allowance` is a **nonnegative positive deduction**, not a negative balance. Calculation subtracts it once.
3. `C01.overdue` is a **subset of gross**, not a separate amount to add. The proposed overdue share uses gross overdue divided by gross receivables.
4. The report snapshot `asOf` is taken from the version-pinned FactSet reporting date; `C01.dueAt` is a contractual due date and must never supply or override `asOf`.

These decisions resolve only these four semantic questions. They do not affirm a general definition for negative credit notes, partial payments, FX, row identities or missing inputs.

## Exact catalog inputs; editorial requiredness for aggregate outputs

| Catalog field | Type | Requiredness for core C01 net output | Policy |
|---|---|---|---|
| `C01.gross` | MONEY | REQUIRED | finite monetary amount; pre-allowance |
| `C01.allowance` | MONEY | REQUIRED | nonnegative; NULL is not zero |
| `C01.overdue` | MONEY | REQUIRED only when computing optional overdue share | subset of gross; no double count |
| `C01.counterparty` | REF | CONDITIONAL | needed for by-counterparty output; cannot serve as unique obligation ID |
| `C01.dueAt` | DATE | CONDITIONAL | required only for an approved aging rule; not report asOf |
| `C01.relatedParty` | BOOLEAN | CONDITIONAL | needed for related-party breakdown; unknown must not silently become false |

The source editor's `required` flag is distinct from this **methodology requiredness proposal**. Organization, legal entity, currency and exact FactSet asOf must be present in the surrounding immutable calculation context even though no corresponding C01 source fields are declared.

## Proposed outputs (not qualified)

- REQUIRED candidate `C01.netReceivablesProposed`: `SUM_AS_OF(C01.gross) - SUM_AS_OF(C01.allowance)`.
- OPTIONAL candidate `C01.overdueGrossShareProposed`: `SUM_AS_OF(C01.overdue) / SUM_AS_OF(C01.gross)` with ratio units.
- The sums operate within one version-pinned FactSet snapshot and one currency/grain. Never use `SUM_FLOW` over successive balance dates.
- `SUM_AS_OF` remains a methodology/operator gap until implemented and qualified against version-pinned source and policy contracts.

## Unresolved policies: blocked unless explicitly resolved for the selected output

| Policy | Editorial requirement before formula qualification |
|---|---|
| Unique receivable position | durable source/business key (counterparty alone insufficient), duplicate correction and cancellation rules |
| Negative balances | explicit credit-note/netting/sign convention; fail closed until defined |
| Allowance bounds | decide if allowance may exceed gross, and whether exceptional/contra positions may do so |
| Partial settlement and overdue | asOf balance, settlement timing, and rule for overdue gross portion |
| NULL/missing | gross or allowance missing -> BLOCKED, no implicit zero; optional overdue missing -> BLOCKED for overdue-share output only |
| Zero gross | net value may be zero when valid inputs are zero; overdue ratio -> UNDEFINED_ZERO_DENOMINATOR, not zero |
| Currency and FX | group by homogeneous currency; no cross-currency sum without independently reviewed FX source/rateDate/version |
| Numeric policy | BigDecimal; input/output scale, rounding mode, overflow/tolerance and display rounding not yet approved |
| Dimensional grain | unique debt position -> entity/asOf/currency -> optional counterparty and relatedParty partitions |
| Provenance | exact FactSet/revision hashes, field UUIDs, formula revision SHA, immutable editorial decision version and test evidence |

## Synthetic arithmetic checks — not financial control evidence

- gross=100.00, allowance=10.00, overdue=20.00 -> net=90.00, overdueShare=0.20.
- gross=100.00, allowance=0.00, overdue=0.00 -> net=100.00, overdueShare=0.
- gross=0.00, allowance=0.00, overdue=0.00 -> net=0.00; ratio UNDEFINED_ZERO_DENOMINATOR.
- gross=100.00, allowance=120.00, overdue=110.00 -> BLOCKED until exceptional balance policies are approved.

## Admission invariant

`DOMAIN_POLICY_PARTIALLY_CONFIRMED` != `FINANCIAL_S2_APPROVED`. No positive editorial decision, unit test or synthetic smoke test grants independent financial S2 or enables qualified CalcPlan/production. All pre-existing blockers remain active.

## Editorial V2 update — 2026-11-20

See `FIN-CALC154-C01-C02-EDITORIAL-POLICIES-V2-2026-11-20.md` (superseding these open questions while preserving the original decision trail). The following principles are now confirmed **as domain policy**: gross is open pre-allowance balance at FactSet asOf after recognized settlements; receivable credit balances and advances are classified separately; excess allowance requires exceptional review, not automatic write-off; overdue is the gross subset based on valid contractual maturity, amendments and payments; accumulate full precision and round at reporting. The record still requires a durable debt-position business key, payment allocation/source provenance, full currency and numerical rounding profile, and independently verified financial evidence. `FINANCIAL_S2_NOT_APPROVED` and production HOLD.
