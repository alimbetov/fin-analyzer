# FIN CALC-154 — AUTOQUAL-1 | Standards-Based Methodology Qualification

**Decision date:** 2026-11-20  
**Scope:** 154 numeric forms; initial pilot C01/C02.  
**Authority:** product/editorial policy, not an independent financial S2 review.  
**Execution boundary:** UAT/test-only; production admission remains CLOSED.

## 1. Decision and definitions

Implement a fail-closed, evidence-based automated qualification of standardized computations. A reference to a familiar formula or to an IFRS/IAS publication is **not** sufficient evidence that input recognition, presentation, measurement, accounting policy or parameters are appropriate.

Three independent gates:
1. `FORMULA_VERIFIED`: mathematical/typed operator semantics, edge cases, deterministic execution.
2. `METHODOLOGY_AUTO_QUALIFIED`: exact normative claim if any, effective applicability, field semantics, parameter/accounting-policy profile, pinned source revision, evidence and golden-case parity satisfied. This is **methodology verification only**.
3. `FINANCIAL_S2_APPROVED`: independent financial S2; never inferred from 1/2, editorial decisions, user consent, source popularity or test success.

`PRODUCTION_ADMITTED = METHODOLOGY_AUTO_QUALIFIED AND independent_S2_valid AND existing_security_and_runtime_admission_gates_valid`. Existing stronger gates take precedence.

## 2. Classification

| Class | Meaning | Automation ceiling |
|---|---|---|
| A | deterministic generic arithmetic (sum, subtract, ratio) | FORMULA_VERIFIED; may proceed to methodology gate only when financial input semantics are proved |
| B | published standard with demonstrably unambiguous applicability | METHODOLOGY_AUTO_QUALIFIED if all contracts/evidence present |
| C | standardized but policy/parameters are configurable | METHODOLOGY_AUTO_QUALIFIED only with approved immutable policy profile |
| D | estimation/judgment (ECL, impairment, forecasts) | formula verification only; independent methodology review |
| E | novel/nonstandard | manual domain/methodology review |

Do not infer normative mandate for a particular ratio, aggregation or naming from a generic standard on accounting presentation.

## 3. Per-output qualification contract

Mandatory immutable fields:
- `methodologyId`, `methodologyRevision`, `formulaAstSha256`, `operatorContractVersion`, `artifactSha256` and build manifest/dependencies
- `formCode`, `outputMetricCode`, `fieldRegistryRevision`, exact field UUID bindings, requiredness by **output**
- `standardCode`, `standardEdition`, `standardReference`, `normativeClaim`, `claimKind` (`MANDATED`|`PERMITTED`|`BACKGROUND_ONLY`|`NONE`), `evidenceUrl`, `effectiveFrom`, `effectiveTo` or explicit `NOT_APPLICABLE`
- `policyProfileId`, `policyProfileRevision`, `policyProfileSha256`; each required policy resolved or explicit `NOT_APPLICABLE`
- exact FactSet id/revision/SHA, `asOf`, organization/entity, currency, period/grain, source lineage and obligation-identity contract
- test manifest SHA, PostgreSQL tests + Java BigDecimal reference parity, negative controls, execution environment, review provenance
- independent `financialS2DecisionId`, `financialS2ScopeSha256`, validity/revocation checks **only for the separate S2 gate**.

Use canonical serialization and explicit versioned hash contract; the cryptographic hash must cover every meaning-affecting field. Deterministic replay cannot read mutable current policies or latest facts.

## 4. Fail-closed qualification algorithm

1. Pin exact registry/FactSet/policy revisions and enforce content-addressed consistency.
2. Reject absent, contradicted, expired, superseded or inapplicable standard/claims; `BACKGROUND_ONLY` does not grant standards certification.
3. Validate required source fields and units for *each output*, not merely form-level required flags; reject silently coerced NULL, implicit zero, mixed currency, overlapping grains and duplicate obligations.
4. Validate period type: `SUM_AS_OF` applies one stock snapshot only; `SUM_FLOW` is not a substitute.
5. Check sign/credit-note/offset policy, payment timing, rounding/scale, denominator-zero and overflow behavior.
6. Execute golden examples + boundary + negative tests in PostgreSQL and Java; compare exact BigDecimal semantics at pre-agreed precision and explicit tolerance.
7. Record immutable qualification receipt with full provenance; never persist `AUTO_QUALIFIED` if any required evidence is absent.
8. When source/config/policy/standard/artifact changes or relevant approval is revoked, new plans must requalify; stale plans fail admission. Concurrent workers require lease/fencing and idempotent receipt persistence.
9. Independent S2, runtime admission, isolation and existing production restrictions remain unchanged.

Recommended states: `DRAFT`, `FORMULA_VERIFIED`, `POLICY_INCOMPLETE`, `EVIDENCE_INCOMPLETE`, `AUTO_QUALIFIED`, `MANUAL_REVIEW_REQUIRED`, `REVOKED`, `SUPERSEDED`. Status and reasons are output-specific.

## 5. C01 pilot

Confirmed editorial semantics: `gross` pre-allowance, `allowance` nonnegative deduction, `overdue` subset of gross, `asOf` from version-pinned FactSet.

- `C01.netReceivablesProposed = SUM_AS_OF(gross)-SUM_AS_OF(allowance)`; required `gross,allowance`.
- `C01.overdueGrossShareProposed = SUM_AS_OF(overdue)/SUM_AS_OF(gross)`; required `overdue,gross`; zero gross => `UNDEFINED_ZERO_DENOMINATOR`.
- Pending: source debt-position identity, credits/signs, bound exceptions, partial settlement, FX, precision/rounding and coherent dimensions. A double-entry/trade receivable accounting standard does **not**, without verified exact reference, mandate these particular analytical ratios.
- Current ceiling: `FORMULA_VERIFIED` after executable tests; `METHODOLOGY_AUTO_QUALIFIED` **BLOCKED** while policy gaps remain; financial S2 HOLD.

## 6. C02 pilot

Exact catalog inputs: `counterparty` REF; `outstanding` MONEY; `overdue` MONEY; `dueAt` DATE; `debtCategory` CODE. Do not invent an obligation-ID field in the form; missing durable source identity is a contract gap.

- `C02.totalOutstandingProposed = SUM_AS_OF(outstanding)`; required `outstanding`.
- `C02.overdueOutstandingShareProposed = SUM_AS_OF(overdue)/SUM_AS_OF(outstanding)`; required `overdue,outstanding`.
- `asOf` from FactSet; `dueAt` is contractual maturity and cannot replace it.
- Await editorial definition of gross/net payable amount, taxes and interest, treatment of credit balances/advances, partial settlement, overdue grace/cutoff, source obligation ID, FX and rounding. No implicit assumption that overdue always satisfies `0 <= overdue <= outstanding` before signed-balance policy is approved.
- Current ceiling: synthetic arithmetic **examples only**. No financial policy or auto-qualification for C02 yet; S2 HOLD.

## 7. Required negative cases

- NULL required money, inconsistent FactSet SHA, missing/changed policy hash
- two different currencies aggregated without approved FX policy
- same obligation twice, two dates combined as one AS_OF snapshot
- zero denominator; zero is not a valid default ratio
- overdue > gross/outstanding, negative credit balances under unspecified policy
- change of standard edition/effective date, absent normative reference, unsupported legal entity
- missing or invalid artifact SHA; SQL/Java divergent scale or rounding; tampered manifest
- revoked S2, stale qualification, concurrent lease fencing, idempotent duplicate attempt
- `FORMULA_VERIFIED` or `AUTO_QUALIFIED` **must never** cause `FINANCIAL_S2_APPROVED` or production admission.

## 8. International reference policy

Standards are versioned external evidence, not a substitute for a company's accounting policy.
- IFRS Foundation: IAS 8 / accounting policy selection — https://www.ifrs.org/issued-standards/list-of-standards/ias-8-basis-of-preparation-of-financial-statements/
- IFRS Foundation: IFRS 9 impairment review — https://www.ifrs.org/content/dam/ifrs/project/pir-9-impairment/rfi-iasb-2023-1-ifrs9-impairment.pdf
- Financial presentation/offsetting requires standard- and date-specific applicability checks (including IFRS 18 transition); **do not label C01/C02 analytics as IFRS-mandated** without a verified exact clause.

External references require release-date and jurisdiction applicability review. Store references/metadata and independently authored summaries; do not copy licensed standards into the repository by default.

## 9. Implementation sequence / acceptance

AUTOQUAL-1.A: typed registry fields, immutable `QualificationRequest`/`QualificationReceipt`, SQL schema, status-reason mapping and hashes.
AUTOQUAL-1.B: deterministic evaluator and non-production policy gate; C01/C02 fixture-driven test suite.
AUTOQUAL-1.C: PostgreSQL concurrency/negative/fencing tests and JDBC/Java calculation parity.
AUTOQUAL-1.D: batch classification and gap export for all 154 numeric forms; do not claim forms are complete until each output is qualified.

Acceptance requires actual PostgreSQL integration tests and artifacts, not documentation alone. No tests or migrations are claimed as run by this specification.
