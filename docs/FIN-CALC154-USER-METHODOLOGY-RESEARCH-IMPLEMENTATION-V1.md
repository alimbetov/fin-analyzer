# FIN CALC-154 — User methodology research adoption, V1

**Branch:** `feat/fin-form-library-standalone`  
**Date:** 2026-11-20  
**Source:** [User-provided detailed formula research](./sources/FIN-CALC154-USER-FORMULA-RESEARCH-2026-11-20.md).  
**Uploaded raw bytes SHA-256:** `de698223e65c64ce887ee30796a658b2706278e5ff7469a257f06b8d779d35c3`. Git content uses normalized text line endings.  
**Financial S2:** `HOLD`; **production admission:** `CLOSED`.

## What is actually implemented

1. `051-fin-calc154-dynamic-methodology-parameters-postgresql.sql`: `methodology_family`, immutable `methodology_variant_revision`, immutable `methodology_parameter_definition`, immutable project/scenario-scoped `methodology_selection_revision`; PostgreSQL16 SHA revalidation, typed JSON, DCF flow/rate/output constraint.
2. `052-fin-calc154-user-methodology-research-postgresql.sql`: 44 **non-executable** researched formula candidates. Each has form code, related forms, original source digest, expression as display-only text and explicit blocking condition. No direct insertion into approved `formula_contract_revision` or `calc_plan`.
3. `053-fin-calc154-methodology-research-readiness-view.sql`: overlays research candidates onto existing 154-form methodology authoring workqueue. Does not relabel unapproved formulas.
4. `MethodologySelectionService`: internal backend service to list DCF variants and typed parameter definitions, create idempotent immutable UAT selection and verify exact PostgreSQL-canonical params/selection SHA. Only `FCFF_WACC` and `FCFE_KE` revision 1 are admitted to this selector.
5. `MethodologyResearchReadService`: read-only backend for one form's research and blockers; no arbitrary SQL execution or public API.
6. `FinancialMethodologyMath`: narrowly scoped arithmetic UAT controls for NRV, past-due days, deterministic equal-year EIR example, FCFF/WACC vs FCFE/Ke compatibility, contractual-cash DSCR.
7. `FinancialMethodologyMathTest` and `MethodologySelectionPostgresqlTest`: proposed 13-test qualification gate; CI run must establish GREEN before any reliability claim.

## Corrections and required manual review of user source

| Research point | Finding | Implemented decision |
|---|---|---|
| EIR 5-year loan: 1000 nominal, origination fee 20, net proceeds 980, 8% nominal, four annual 80 and final 1080 | Source says EIR ~10.2%, which is incorrect for specified flows. Exact root is ~8.5076328113%; year-one effective interest expense ~83.3748 on carrying amount 980 | Source retained verbatim for audit; corrected deterministic numeric control in Java/JUnit |
| C01 days overdue | Source uses `dueAt - asOf` (wrong sign) and labels it as overdue *amount* | `daysPastDue = max(0, asOf - effectiveDueAt)` in days; monetary overdue requires exact open-instalment ledger and settlement allocation |
| C02 overdue amount | Difference of two dates produces days, never money | Same day-based correction; no fabricated overdue amount |
| IFRS 9 ECL / write-off | 100% ECL is not itself a command to write off gross and reserve. Write-off is based on no reasonable expectation of recovery and other applicable accounting requirements | ECL rate matrix remains a blocked estimate candidate; no automatic source mutation/write-off |
| IAS 32 and supplier advances | Prepayments for goods/services may not be financial instruments; two offset conditions alone cannot make all advances IAS 32 eligible | Offset marked conditional to correct instrument classification and approvals; no automated netting |
| IFRS 15 variable consideration | `min(expected_value, probable_not_reversed)` is not a general valid accounting algorithm; variable consideration constraint requires judgment | Research only, no automatic revenue modification |
| C04 revaluation and OCI | OCI/PL treatment requires full history of revaluation and reversal; naive always-OCI is unsafe | Research only, no automatic OCI posting |
| F03 operating cash flows | Indirect `NI + depreciation - ΔNWC` and direct receipt equations are incomplete without actual cash and noncash adjustments | Research only; preserve exact period and reconciliation blockers |
| DSCR and EIR | EIR accounting effective-interest expense is not the same as contractual cash interest due, so changing EIR does not necessarily change the DSCR cash denominator | UAT test calculates only `CFADS / (contractualCashInterest + principalDue)`; covenant fees remain policy-dependent |
| P07 DCF | `FCFF` must pair with `WACC` and `ENTERPRISE_VALUE`, `FCFE` with `COST_OF_EQUITY` and `EQUITY_VALUE` | DB CHECK and Java preview reject cross pairs; still verify rate-source revision and actual cashflows |
| IRR roots | A single IRR may not exist or multiple IRRs may exist for sign-changing flows; blanket reinvestment assertion is not a reliable computation rule | IRR remains research candidate until exact cashflow, dates, root policy and golden vectors exist |

## Exact remaining blockers

- The 44 research formulas are evidence **candidates**, not qualified executable formula revisions. Data-source gaps for FIFO lots, EIR cashflow schedule, fair value, ECL parameters, IFRS15 allocation and cash flow adjustments persist.
- `MethodologySelectionService` currently supports an **internal service call**, not a React user-facing selector. A secured controller must first prove project membership/authorization and cannot be exposed with user-chosen project UUID alone.
- Neither `cashflowRevisionId` nor `rateSourceRevisionId` is yet dereferenced and cross-checked against source FactSet. Therefore a stored DCF choice is a configuration, **not an executable DCF plan**.
- Selecting a DCF variant for one project/scenario does not yet bind all applicable P-form CalcPlans to it. Require an immutable `CalcPlan` reference and stale/revocation checks before execution admission.
- PostgreSQL16 Testcontainers, Liquibase 051/052/053 and unit golden tests must pass CI. Until then only code/SQL commit status, not GREEN.
- IFRS and financial definitions in the uploaded memo remain editorial hypotheses until verified against exact applicable publication, effective date, reporting basis and independent S2. No production qualification may be inferred.

## UAT qualification command

```bash
mvn -B -ntp -f form-library/pom.xml test \
  -Dfin.postgres.uat.enabled=true \
  -Dtest=FinancialMethodologyMathTest,MethodologySelectionPostgresqlTest \
  -DfailIfNoTests=true
```

CI workflow: `.github/workflows/fin-calc154-dynamic-methodology-pg16.yml`.

## Next step after GREEN

Build `QualifiedMethodologyBinding` for the exact `organizationId/projectId/scenarioId`, `selectionRevisionId/selectionSha256`, `rateSourceRevisionId`, `cashflowRevisionId`, approved policy version, source FactSet pins and SHA. A future P07 financial plan must reject a stale selection or mismatched P01–P10 scenario; UI option list must be server-generated, never execute user-supplied formula text.
