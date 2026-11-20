# FIN CALC-4D — Typed DAG, exact FactSet sources, immutable UAT preview & 154-form methodology registration

**Branch:** `feat/fin-form-library-standalone`  
**Scope:** CALC-4D.0 / 4D.1 / 4D.2 + internal candidate methodology registration  
**Security/admission:** UAT read-only calculation planning; **financial S2 HOLD**. This change does not enable a new execution worker, production qualification, public authoring endpoint or financial result publishing.

## Verified architecture (bounded capabilities)

| Component | Contract | What it explicitly does NOT do |
| --- | --- | --- |
| `Calc4DTypedDagCompiler` | Given an explicit set of **formula revision UUIDs**, load and verify typed input bindings, formula SHA and versions, reject missing derived dependencies and cycles, topologically sort all supported operations | Does not infer formulas or approve policies |
| `Calc4DExactFactSetInputResolver` | Given **organization + exact FactSet UUID**, require active TEST_QUALIFIED/SYNTHETIC scope, reconstruct `fin_intake` content using INGEST-2, compare every persisted numeric observation with the exact source revision, pin source SHA and observation fingerprint | Does not accept generic latest/recent facts, non-synthetic data, multi-currency conversion or orphan observations |
| `Calc4DImmutablePlanCompiler` | Combine exact DAG plus **1–32 exact FactSets** from the same org/project/entity/business/period/currency into a deterministic, immutable **preview descriptor** | **Does not** insert a row into `fin_calc.calc_plan`, create a job, execute SQL batch, or write a result |
| `Calc4DFormulaCandidateRegistrar` | Register explicitly authored formula candidates and typed inputs in a PostgreSQL transaction for **any data form**; exact version and idempotent replay; atomic batches of up to 154 definitions | Does not invent mandatory outputs, accept raw SQL code, or grant S2 |

The `FormulaCandidateRegistrar` uses the existing append-only `formula_contract_revision` and `formula_typed_input_binding` tables. All entries remain `CANDIDATE_UNQUALIFIED / NOT_S2_APPROVED`. No historical Liquibase schema was modified.

## Supported primitive family (not yet an end-to-end UAT executor)

- `SUBTRACT`, `RATIO`, `RECONCILE`, `ROLL_FORWARD` — typed arithmetic via the existing SQL/JDBC compiler
- `GROUP_BY`, `RATIO_OF_SUMS` — set-based SQL/JDBC candidate readers
- `SUM_FLOW`, `LAST_AS_OF` — read-only PostgreSQL candidate primitives; not yet part of the new unified persisted-worker contract

**Current typed DAG treats these as explicit operations; different operators do not imply that an approved accounting or economic methodology exists.**

### End-to-end lineage

```text
Authored candidate formula revision (exact UUID + definition/binding SHA)
       |
Typed source and derived-dependency bindings -> topologically ordered DAG SHA
       |
Explicit TEST_QUALIFIED synthetic FactSet IDs -> scope/epoch/control checks
       |
INGEST-2 exact fin_intake revision rehash -> observations must match source values
       |
Immutable UAT plan PREVIEW fingerprint (no PostgreSQL calc_plan row)
       |
[BLOCKED: execution admission, approved policies and independent finance S2]
```

## Registering formulas across all 154 forms

**Do not create a guessed formula by assigning every numeric field `SUM`.** A form with input data is not necessarily a form with derived outputs. Likewise nine reference-only forms may require cross-form sources, or may legitimately have zero derived outputs. Both outcomes require a documented methodology decision.

Before a form can be marked financially complete, its authored contract must declare:

1. **Output inventory** — all required, optional, physical-input and derived outputs, with explicit formula revision UUIDs and no silent defaults.
2. **Typed lineage** — exact field/derived-source metric, source form version, source schema SHA, dependency revision ID and controlled aggregation grain.
3. **Temporal semantics** — `AS_OF` stock vs `INTERVAL` flow, mixed-grain prohibition, `YEAR_TO_DATE`/roll-forward boundaries and sign conventions.
4. **Value semantics** — source/calc unit, currency, FX policy/rate source if needed, dimension compatibility, rounding/precision, `NULL`, zero denominator and non-present values.
5. **Independent examples** — good, edge and negative financial test vectors with known expected results and evidence attribution.
6. **Financial review** — separate approval/revocation receipts (not yet implemented; production remains HOLD).

The read-only baseline `Calc154CatalogWidePlanner.inventory()` exposes every form and its source metrics/blockers. Its PostgreSQL CI produces the artifact `154-form-calc4b-coverage.tsv`. Candidates can be registered individually with `Calc4DFormulaCandidateRegistrar.register(Definition)`, or in a transactional authored batch with `registerBatch(List<Definition>)`. Each batch is all-or-nothing, hash-addressed, size-limited and idempotent. For a dependency on another new formula, register the dependency first so the dependent contract can pin its exact UUID.

**Scope of present work:** 154 form contracts are inventoried, 145 have direct numeric inputs and nine have no direct numeric inputs; only 10 real catalog candidate formulas across five forms were pre-existing. Newly registered test candidates are explicitly synthetic and do not count as approved methodology coverage.

## Methodology-operation gap procedure

Do **not** add financial operations until a needed output and its ground-truth formula are specified. If the current six formula operations are insufficient, add a **new versioned operator family** in this order:

1. Classify needed primitive: `SUM_FLOW`, `LAST_AS_OF`, weighted-average, distinct-count, split by dimension, cumulative/YTD, cash-flow roll-forward or more advanced validated finance formulas.
2. Define inputs, output unit/scale, time/dimension/currency and missing/zero policy; verify reproducible expected outputs independently.
3. Implement native PostgreSQL set-based operator where beneficial; use Java BigDecimal as a separate arithmetic/parity oracle, not as a replacement for financial semantics.
4. Version operation schema and artifact SHA; add guardrails and PG16 negative tests for mixing scope, currency, source revisions, null and denominator zero.
5. Bind it to the typed DAG and, only after UAT plan/result admission is qualified, include it in a persisted immutable `CalcPlan`.

## Remaining CALC-4D release gates

- **4D.3**: durable UAT-only `CalcPlan` persistence, with a separately versioned admission contract; do **not** weaken HYB-2B's existing `BLOCKED` trigger
- **4D.4**: universal SQL/JDBC batch execution across scalar and rowset inputs, staging and immutable results, retry/fencing/lease, isolation from production
- **4D.5**: result fingerprint and complete lineage, zero/NULL/currency/dimension policy, concurrency and revocation E2E, source alteration tests, performance comparison
- **154-methodology completion**: author the actually required formula revisions and independent control cases for each of the 154 forms; only then decide which novel operator types need implementation
- **Production**: independent financial S2, policy review and trusted admission. Still **HOLD**.

## PostgreSQL test gates

- `fin-calc4d-typed-factset-pg16.yml`: eight real PostgreSQL tests for exact DAG, source-rehash and fail-closed preview.
- `fin-calc4d-formula-registrar-pg16.yml`: real PostgreSQL tests for arbitrary-form formula registration, SHA replay, multi-version lineage, cross-form binding and transactional batch rollback.

No security moderation, signed production S2 or release bypass is introduced by this stage.

## CALC-4D.3/4D.4 — 2026-11-20 implementation addendum

### Strictly isolated synthetic UAT execution

The additive Liquibase migrations are `043-fin-calc4d-uat-execution-sandbox-postgresql.sql` and `044-fin-calc4d-methodology-workqueue-postgresql.sql`. All historical migrations remain unmodified.

- `fin_calc.calc4d_uat_plan`: append-only plan snapshot, exact formula revision IDs, exact FactSet references and qualification epochs, SHA of the DAG, source manifest, and execution fingerprint. Explicit state `UAT_ONLY_NOT_S2_APPROVED`.
- `fin_calc.calc4d_uat_result`: append-only synthetic result and SHA, unique per UAT plan, explicit `financially_approved=false`. Never promoted to `fin_calc.calc_result`.
- `Calc4DUatPlanStore.stage`: gated by `FIN_CALC4D_UAT_EXECUTION_ENABLED=false` by default; locks every active `fin_fact.factset_control` source `FOR SHARE`, then re-hashes its exact `fin_intake` revision and checks the full immutable plan. Idempotent `(organization,execution_fingerprint)`.
- `Calc4DUatSqlJdbcBatchExecutor.executeExact`: re-locks/re-verifies current sources before doing one set-based PostgreSQL aggregation over all exact FactSet observations, then evaluates the typed DAG in deterministic order using Java `BigDecimal`. Stores results only in `calc4d_uat_result` with deterministic SHA and idempotency guard.
- Scalar operations `SUBTRACT`, `RATIO`, `RECONCILE`, `ROLL_FORWARD` require one source observation per metric and identical grain, dimensions and unit; no implicit scalar `SUM`.
- `GROUP_BY` and `RATIO_OF_SUMS` group on exact dimension digest; a ratio requires the same complete dimension set and compatible units, otherwise the batch fails. Zero denominator is `INVALID_ZERO_DENOMINATOR`, never silently `0`.
- Fixed **UAT candidate** ratio precision is 12 decimal places with `HALF_EVEN`. This is **not** a financial rounding policy and is not approved for production. No automatic FX conversion, lookback, YTD conversion or cross-period blending.
- Repeated metric values across different input FactSets are refused without a formally authored merge policy, to avoid double counting.
- The `calc4d_uat_*` tables are separate from `fin_calc.calc_plan`, `calc_job`, `calc_result`, and `calc_current_result`; the HYB-2B triggers remain unchanged. There is no public HTTP executor.

### Methodology inventory and registration across 154 forms

`fin_calc.calc4d_methodology_workqueue_v1` is a dynamic SQL view of **all 154 forms**, their verified numeric source fields, actual formula revisions, latest output-review draft, and explicit method/policy/S2 blockers. Query directly:

```sql
SELECT form_code, form_version_id, content_mode, direct_numeric_field_count,
       jsonb_array_length(typed_numeric_sources_jsonb) AS typed_fields,
       formula_candidate_count, mandatory_output_work_state,
       calculation_policy_state, financial_approval_state, executable
FROM fin_calc.calc4d_methodology_workqueue_v1
ORDER BY form_code;
```

This is the authoritative AUTHORING QUEUE, **not** proof of 154 completed calculations. Currently there are ten existing catalog candidate formulas across five forms. Candidate registration via `Calc4DFormulaCandidateRegistrar.registerBatch` is append-only, transactional, SHA-versioned and limited to 154 explicitly supplied definitions. This bulk API does not generate unrequested `SUM` methods from source field presence and does not claim a completed mandatory output set.

### PostgreSQL acceptance scope

- `Calc4DTypedPlanPostgresqlTest`: exact source revision SHA, DAG graph, deterministic UAT plan, immutability, repeated execution, source revocation, cross-organization rejection, zero denominator, 154 methodology workqueue, and real **C07 ROWSET** `GROUP_BY` / `RATIO_OF_SUMS` UAT execution.
- `Calc4DFormulaRegistrarPostgresqlTest`: standalone and batch formula registration, multi-version exact binding, cross-form synthetic methodology, rollback.

The UAT executor has **no** lease/fencing or distributed worker claiming yet. It does not verify an independent financial S2 signature; it does not approve full output requiredness, currency policy, rounding or dimensions. The result is strictly synthetic and remains non-financial.

### Next P0 for CALC-4D.5 and 154 financial contracts

1. Integrate a qualified, version-pinned admission contract with a persistent worker and lease/fencing; do not weaken the blocked HYB-2B trigger. Include concurrency, crash recovery and revoked FactSet between job claim and result commit.
2. Establish an independently accepted formula bundle and required outputs for each of the 154 forms. Determine which forms have **no derived calculations** rather than fabricating formulas. Approve cross-form dependencies, stock-vs-flow periods, policy variants and canonical dimensions.
3. Add independent numerical control examples (including zeros, missing observations, period boundaries, FX/multi-currency and dimensional grain) for each accepted formula; adapt SQL/JDBC operator set only where an approved method requires it.
4. Financial S2 approval/revocation and immutable production result lineage before release. Current production remains **HOLD**.
