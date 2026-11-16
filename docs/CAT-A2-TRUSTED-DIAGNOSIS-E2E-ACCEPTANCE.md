# CAT-A2 / FIN — qualification and trusted diagnostic release contract

**Status: UNAPPROVED / HOLD.** This is an acceptance contract, not an independent S2 financial approval or a verified end-to-end run.

## 1. Two separate gates

**MethodGate** qualifies a **versioned method**, not a form by name: methodCode, methodVersion, applicable formVersion, source field set, reporting basis, units, stock/flow semantics, reporting period, sign/reversal policy, rounding tolerance, sector applicability, independent signed golden fixtures and reviewer decision. A missing/expired/revoked review means NOT_QUALIFIED.

**FactGate** qualifies a **concrete set of records**: authenticated tenant authorization; org, project, economic entity, business scope; period/calendar and reporting basis; currency/scale; immutable raw source hash and source revision; server-verified mapping from formCode/fieldCode/rowId; field-level completeness; no selected duplicate; reviewed intake; exact published FactSetRevision; no stale/current-pointer confusion.

**DiagnosticEligible := MethodGate.APPROVED && FactGate.VERIFIED && Method.isApplicable(FactSet).** A user-supplied flag called `approved`, `diagnosticEligible` or `qualificationStatus` must never satisfy either gate.

## 2. First-wave review order

1. F01 balance (assets, liabilities, equity, asOf): same entity/basis/as-of; liabilities and assets as nonnegative magnitudes under documented balance convention; signed equity may be negative. Balance equality needs explicit rounding/materiality policy, not an invented zero tolerance in published statements.
2. F02 financial result (net revenue, COGS, operating expenses, net profit): independently reconcile gross profit; net profit is **not** derivable without financing, tax, other income/expense and discontinued items. Identify whether reported net profit is after tax, and avoid gross/net sales mix.
3. F03 cash flow (opening/closing balances and CFO/CFI/CFF): net flows may be negative; FX on cash equivalents and other reconciliation items may be required. Check date endpoints and cash-equivalent perimeter.
4. DF-CSH-01/02 events: event direction and reversal/correction semantics; unique payment and bank transaction references; same-operation duplicates and internal account transfers; no blanket disallowance of legitimate reversals.
5. DF-CSH-03 stocks: per-account as-of, restricted funds and reserves, available balance not double-subtracted.
6. DF-CSH-04/05 invoices/payables: payments and invoice value may diverge due to advances, allocations, partial settlements and credit notes; negative residual is a REVIEW signal, not automatically a defect.
7. DF-CSH-06 debt schedule: disbursement, scheduled principal, interest, balances and payment dates; no debt rollforward assertion without opening balance and movements.
8. SF-G01–03: SKU/warehouse grain, returns and discounts, comparable COGS periods, average inventory quantity/value, freight allocation, unit prices.
9. SF-M01–02 and SF-S01: working time basis, billable vs available hours, fee recognition, direct cost, service jobs, refund handling.
10. SF-C01–03: WIP/units, good vs reworked vs scrap overlaps; overhead absorption, output denominator, run/planned/downtime basis.

The 92 candidate fields tracked in `docs/CAT-A2-PILOT-FIELD-REVIEW.tsv` remain `PENDING_METHOD_REVIEW` and `CANDIDATE_NOT_APPROVED` until independently signed off.

## 3. Test matrix — must be executable against trusted backend

| Case | Input condition | Expected status / invariant |
|---|---|---|
| Q-01 | F01 assets=1000, liabilities=350, equity=650, matching basis/date | Reconciliation difference 0; no automatic S2 approval |
| Q-02 | F01 equity=649.99, rest unchanged | Difference 0.01; flag REVIEW until approved tolerance |
| Q-03 | F02 revenue=400, COGS=260, OPEX=90, reported net profit=35 | Gross profit=140; net-profit bridge NOT_COMPUTABLE without remaining line items |
| Q-04 | F03 opening=100, CFO=35, CFI=-10, CFF=-5, closing=120 | Arithmetic difference 0, FX/cash perimeter still must be reviewed |
| Q-05 | F03 closing=125, same flows | Difference 5; do not silently adjust closing cash |
| Q-06 | Missing mandatory or unknown field, explicit null, unsupported precision | Validation failure or MISSING_EVIDENCE, never 0 |
| Q-07 | Two payment rows with same payment identity/source revision | BLOCKED_DUPLICATE, no double count |
| Q-08 | Internal transfer appears as two cash-ledger events | Exclude or reconcile per signed transfer policy; no default doubling |
| Q-09 | Invoice 100, payments 120 | Negative residual is reviewable; may represent advance/overallocation |
| Q-10 | Two currencies KZT/USD with no approved rate/policy | BLOCKED_NONCOMPARABLE; no implicit FX |
| Q-11 | AS_OF mixed with monthly flow or quarter/YTD on same metric without typed period coverage | BLOCKED_NONCOMPARABLE |
| Q-12 | Client changes tenant, project, basis, currency, revision or hash after preview | REJECT; preview invalidated |
| Q-13 | Server-published revision N+1 while UI holds N | Explicit STALE_SOURCE or pinned N; no silent update |
| Q-14 | Re-upload identical normalized content and raw file within same idempotency scope | No duplicate fact/revision or second accounting impact |
| Q-15 | User without scope to see source document or period | 403/deny with no data leak in result or logs |
| Q-16 | Independent MethodGate absent, despite valid structural inputs and client `approved=true` | NOT_QUALIFIED; no trusted diagnosis |
| Q-17 | All necessary independent approvals and server facts present | Method executes once per published revision; output records exact method version, source lineage, source hashes and limitations |
| Q-18 | Reload and re-login from second browser authorized for same project | CurrentFormView and history agree with published revision; readonly older revisions immutable |

Q-01..Q-05 have synthetic arithmetic analogues in the isolated candidate module. **Q-07..Q-18 are target acceptance cases; their existence here does not claim implementation.**

## 4. Required business outcome and UI path

Choose a pilot company and reporting month. User downloads an approved versioned F01/F02/F03 XLSX, enters offline or edits the identical schema in React, uploads, receives a server-owned PreviewSnapshot with source-field warnings, confirms a draft, passes reviewer approval, publishes a new immutable FactSetRevision, runs authorized qualified methods, opens a multilingual report with explicit missing evidence / review / unsupported labels, logs out, reopens project, and sees pinned historical versions.

All screen observations must be based on actual browser output, and all numerical conclusions on independently audited fixtures, not synthetic screenshots.

## 5. CI, ownership and rollout

- Run `npm --prefix packages/sector-pilot test`, Node/Vitest/Playwright, Maven JUnit and PostgreSQL Testcontainers, macOS E2E; record single **push** SHA, run URL, all prerequisite job conclusions, and artifacts. An empty GitHub workflow-run response is not PASS or FAIL evidence.
- Independent accountant/financial method specialist signs method-family S2, signs numeric fixtures and completeness/rounding, and records assumptions, effective date and applicable reporting basis.
- Backend owner owns MethodGate, FactGate, source verification, OIDC/ABAC and transactional publication. Client code owns editable drafts, preview display and local parsing only.
- Keep `feat/fin-r0-r1a` and PR #2 Draft until tests and S2 signed; don't merge to `main` automatically.

**Exit criterion:** one REAL, authenticated F01→F02→F03 cross-form scenario with verified FACT provenance and controlled diagnosis, followed by trade/service/manufacturing scenarios. No universal approval of 92 fields merely because the registry is complete.
