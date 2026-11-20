# FIN CALC-154 Methodology Completion + CALC-4D.5 UAT Recovery Runbook

**Branch:** `feat/fin-form-library-standalone`  
**Date:** 2026-11-20  
**Scope:** all **154 ROWSET/SINGLE_RECORD forms**, existing 10 candidate formula revisions, synthetic mathematical controls, and opt-in lease/fencing UAT calculation jobs.  
**Financial S2 and production:** **HOLD**. No user, service, job or review proposal is allowed to claim an independent financial approval from this implementation.

## 1. What is actually available

| Layer | Available | Not available |
|---|---|---|
| Form inventory | 154 form versions; 145 with direct numeric source fields and 9 reference-only; exact type and version-pinned SHA | Approved business classification of mandatory outputs for every form |
| Candidate formulas | 10 immutable formula revisions across 5 forms, typed input dependencies, version hashes | Fully authored formula set for the other 149 forms |
| Mathematical tests | 12 synthetic reference cases across all 10 known formula candidates (including zero denominators); 3 pre-existing F02-only cases | Independent financial source documents and economic validation of those cases |
| UAT calculation | Generic arithmetic, grouping and ratio-of-sums; SQL/JDBC batch; exact synthetic FactSet lineage; immutable UAT result | Approved finance rounding, FX, stock/flow time policy, production admission |
| UAT dispatch | `SKIP LOCKED` competing claims, monotonic fencing epoch, lease heartbeat, retry/backoff, exhausted-job terminal state, crash recovery, immutable claim receipt, atomic job/result completion | Autonomous enabled scheduling, production IAM authorization, signed financial S2 |

The existing `fin_calc.calc_plan`, `calc_job`, `calc_result`, and HYB-2B blocker are unchanged. UAT data lives in `calc4d_uat_plan`, `calc4d_uat_job`, `calc4d_uat_job_receipt`, and `calc4d_uat_result`. Results from a leased worker store the exact `worker_job_id` and `worker_fencing_token` in immutable lineage (migration 048). Legacy manual synthetic preview results have both fields NULL, deliberately distinguishing their provenance.

## 2. Form-by-form financial methodology completion

Use `docs/FIN-CALC4D-154-METHODOLOGY-AUTHORING-BACKLOG.tsv` for all form/field candidates and `docs/FIN-CALC154-METHODOLOGY-OPERATION-GAPS.tsv` for operator-family gaps. Live PostgreSQL source of truth:

```sql
SELECT form_code,form_version_id,content_mode,
       required_numeric_input_fields,optional_numeric_input_fields,
       manual_numeric_input_fields,ui_derived_numeric_fields,
       formula_candidate_count,mathematical_control_cases,
       formula_candidates_missing_math_control,
       proposed_required_outputs,proposed_optional_outputs,
       unbound_derived_metrics,requiredness_status,
       candidate_methodology_status,policy_review_state,
       independent_control_state,financial_approval_state,
       qualified_for_production
FROM fin_calc.calc154_methodology_completion_v1
ORDER BY form_code;
```

**Important:** `lib_form_field.required` is source-form **input requiredness**, not a determination that a computed financial output is mandatory. Likewise `input_mode='DERIVED'` describes editor behavior, not necessarily an approved financial equation.

For EACH of the 154 forms, a financial methodology owner must explicitly decide:

1. Whether the form is **SOURCE_ONLY** (a legitimate zero-derived-output decision), **CALCULATED**, **MIXED**, **CROSS_FORM**, or **NEEDS_RESEARCH**. Do not assign `SUM` automatically to every numeric field.
2. For each output: metric code, **REQUIRED/OPTIONAL**, exact formula revision and operation, input field/derived metric UUIDs and SHA, explicit dimension grain, source period and target period, stock `AS_OF` versus flow `INTERVAL`.
3. Currency and unit rules: homogeneous units, conversion source/rate/version where applicable, permitted signs, calculation precision and accounting rounding, `NULL`, missing, zero denominator, overflow, duplicate source handling and financial evidence.
4. Independent financial examples for **normal, zero, missing, negative, boundary period, duplicated dimension, revoked source, multi-form and multi-currency** scenarios, with human-trusted expected values.
5. Separate independent approval and revocation of exact versions by an authorized financial S2 reviewer before production.

An explicitly authored *candidate* formula can be appended to any form with `Calc4DFormulaCandidateRegistrar.register` or atomic `registerBatch`. Source and formula UUIDs must match the current immutable catalog versions. A human review draft may be recorded with `RequiredOutputReviewDraftService.appendUnqualified`. Neither API creates a signed approval.

All 12 `calc154_synthetic_control_case` cases are mathematical UAT checks, **not** independent financial expected values. Their metadata/expected values are bound to exact formula revisions and cannot be edited. The historical F02 3-case dataset remains separate.

## 3. Methodology gaps to prioritize, WITHOUT claiming approvals

The R0 catalog mentions **23** operator families across 154 data forms. Proposed (not qualified) operations most often include:

| Design-only operator family | Forms proposing use | Engineering decision |
|---|---:|---|
| `SUM` | 128 | Stock vs flow, period and dimensional grain must be defined before generalizing `SUM_FLOW` |
| `GROUP_BY` | 127 | UAT primitive exists; define valid dimensions, grouping keys and missing rules |
| `PERIOD_COMPARE` | 74 | Must specify comparison period alignment, fiscal calendar, base-zero semantics and currency |
| `RATIO` | 25 | UAT arithmetic exists; required output unit, zero denominator and financial precision remain unapproved |
| `ROLL_FORWARD` | 15 | Requires auditable signed movements and opening/closing stock reconciliation |

The detailed matrix covers the other proposed families, including AGING, ALLOCATION, AMORTIZATION, NPV, IRR and scenario calculations. The operation registry should grow **only after** a concrete financial output, typed sources and independent expected values justify an additional primitive.

## 4. UAT leased worker usage (internal only)

1. Create a reproducible synthetic FactSet, with exact source revisions and checked controls.
2. Stage a UAT preview: `Calc4DUatPlanStore.stage(org, exactFormulaRevisionIds, exactFactsetIds)`.
3. Enqueue once: `Calc4DUatLeaseQueue.enqueue(org, planId)`.
4. Two or more test harness workers may concurrently call `claimNext(org, workerId, 120)`; only one gets a given job. Each claim returns a new monotonic fencing token.
5. If a worker is idle with a live claim and needs more time, call `renew(claim, seconds)` **before** beginning the calculation; during active execution the job row is held by a PostgreSQL transaction and concurrent heartbeat attempts cannot update it.
6. Execute `Calc4DUatLeasedWorker.execute(claim)`: recheck job ownership, lock source FactSet controls, verify INGEST-2 source revisions, execute SQL/JDBC batch, insert immutable result tied to exact job/token and atomically mark job `SUCCEEDED`.
7. On an identified transient failure, call `failOrRetry(claim, typedErrorCode, backoffSeconds)`. A worker crash is recovered when `claimNext` sees the expired lease; the old token is always rejected.
8. A stale RUNNING job with no remaining attempts becomes `FAILED` rather than being claimed forever. Status and immutable event history are read from `calc4d_uat_job` and `calc4d_uat_job_receipt`.

Config is `FIN_CALC4D_UAT_EXECUTION_ENABLED=false` by default. No public API or scheduled worker has been enabled. Worker ID and fencing token are carried in PostgreSQL transaction-local settings and guarded by a server-side result-insert trigger. This is synthetic UAT protection, **not an authentication layer against malicious database principals**.

## 5. Mandatory next acceptance and release gates

- Prove the job and result's exact fencing lineage and SHA in all PostgreSQL negative tests.
- Add crash recovery/retry tests for connection loss, long-running work past lease expiry, worker failure between computation and commit, and high concurrency at operational load levels.
- Benchmark representative ROWSET forms at 10k/100k/1M rows using repeatable input snapshots and report p95/p99, CPU, memory and row-group cardinality.
- Require a signed, version-pinned financial policy and independently validated examples per output; retain audit and revocation.
- Only afterward design a **new qualified production admission contract**. Do not remove or bypass HYB-2B protection.

**Status:** infrastructure can calculate verified **synthetic candidates** and prepare 154 per-form review packets, but **0/154 forms are financially S2-approved**. Financial methodology completion remains a substantive expert-reviewed activity; it cannot be claimed from catalog field names alone.
