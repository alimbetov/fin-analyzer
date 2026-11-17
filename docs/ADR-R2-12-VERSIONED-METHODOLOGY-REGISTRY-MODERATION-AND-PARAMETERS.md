# ADR-R2-12 — FIN HTAP Versioned Methodology Registry, Moderation & SHA-256 Parameters

**Date:** 2026-11-17 · **Status:** TARGET DESIGN + MR-R0 DATABASE BASELINE; ALL METHOD EXECUTION STILL HOLD  
**Repository:** `alimbetov/fin-analyzer` · **Only branch:** `feat/fin-r0-r1a`  
**Sources:** `methodology-catalog-r1.json` (60 families, 5 wired unqualified, 13 candidates, 42 unaudited); `MethodVariantRegistry` (5 business + 2 SYS descriptors); `ConcentrationAnalysisEngine` (CON01 Pareto math, CON02 ABC 80/95 boundaries); `fin_calc.method_s2_review` (independent financial review); [FIN HTAP v1.1](FIN-HTAP-CONSOLIDATED-TZ.md), [Method Reality/P0](FIN-HTAP-METHODOLOGY-REALITY-AND-P0-RECOVERY-PLAN.md), [W0–W8](FIN-HTAP-IMPLEMENTATION-STRATEGY-W0-W8.md).
**Critical:** database moderation of *configuration* NEVER promotes a code candidate to implementation, Method S2, execution permit, or ACTUAL. No method was promoted by this migration.

## 1. Decision: one user-facing catalogue, normalized immutable history

Expose **exactly one read-model row per business method family** in `fin_calc.methodology_catalog_view` (all 60), with nested current variants and configuration/history status. Keep normalized PostgreSQL relations:
- `fin_calc.method_family` — 60 immutable business method IDs, groups, titles, *current evidence baseline* (not method qualification). Populated from the real existing JSON catalogue, never invented formulae or defaults.
- `fin_calc.method_parameter_contract` — immutable, scoped `methodCode + variantCode + contractVersion` typed parameter schema, an application/server validation profile, and server SHA-256.
- `fin_calc.method_config_version` — immutable `methodCode + variantCode + versionNo`, parent revision, code algorithm id/version/SHA, engine JAVA|SQL, exact JSONB parameters, server-computed `params_sha256` and `effective_sha256`.
- `fin_calc.method_config_review` — **append-only** moderation, 1 independent decision per config, `submitted_by != reviewed_by`, exact evidence digest, reason.
- `fin_calc.method_active_config` — CAS-protected pointer to a **moderated** config version. Pointer update is the ONLY mutable part, never a source/result mutation.
- `fin_calc.method_config_activation` — immutable audit event for each activated pointer version; eventual recalculation planner can consume this as a source of **intent** when independently implemented, not as a direct SQL trigger.
- `fin_calc.methodology_catalog_view` — exactly 60 rows even when there are multiple variants/config revisions. It reports nested active variant configurations and `method_execution_permitted=false`.

**No separate physical table per method**: 60 families, 166 forms and tenants MUST NOT yield 60 or 60×166 PostgreSQL child partitions. Method metadata is a small indexed transactional catalogue; computation staging uses independent lifecycle cohorts.

**Separation of domains:** `Family` (FIN-01) is a business category; `Variant` (`FIN-01/REV_NET_MOM`) is one precise formula/input binding; `AlgorithmVersion` is the published reviewed Java/SQL implementation; `ParameterContractVersion` determines the legal parameter set; `ConfigVersion` pins one exact value set; `MethodS2Review` validates the result semantics and approved source/form basis; `ResultKey` pins all of them plus actual tenant/form/PeriodType/Period/Currency and source data. A config approval does not imply that a broader family is ready.

## 2. Fingerprints and effective identity

**Do not use MD5 or Java `hashCode()`.** For MR-R0 PostgreSQL 16 computes:
```
params_sha256 =
 SHA256("FIN:METHOD:PARAMS:PG16-V1|" + PG16_JSONB_TEXT(parameters))

effective_sha256 =
 SHA256("FIN:METHOD:CONFIG:PG16-V1|" + methodCode + "|" + variantCode + "|" +
        engine + "|" + algorithmId + "|" + algorithmVersion + "|" +
        algorithmSha256 + "|" + parameterContractSha256 + "|" + paramsSha256)
```

These hashes are **immutable once inserted**. They do not use the `versionNo` counter because the same effective semantics should reuse/re-activate the old immutable version, not create a duplicate version with identical input. `UNIQUE(methodCode,variantCode,effective_sha256)` rejects such duplicates; changing thresholds, calendar/window settings, algorithm artifact/version or validation contract changes the effective hash. The DB is the digest authority; a caller cannot forge it by setting a different supplied SHA.

**Canonicalization warning:** MR-R0 specifies `PG16_JSONB_TEXT`, relying on JSONB semantic key normalization (object-key order irrelevant). It is explicitly versioned as `PG16-MCONFIG-V1`; this is **not a claim of stable cross-PostgreSQL-major/cross-language binary canonical JSON**. Before PostgreSQL major upgrade or Java-side independent digesting, define and test a separately versioned length-prefixed canonical UTF-8 codec and migrate as a NEW hash identity, never retroactively change historical digests. Financial source `snapshot_hash` is a different integrity domain; neither hash is an authorization/signature.

**Algorithm vs configuration:** Changing CON-02 A/B thresholds creates **a new ConfigVersion and effective calculation identity** while algorithm version/Java source SHA may be unchanged. Changing the formula, tie-breaking, period alignment or source selection requires a **new algorithm version** with new golden/negative tests and independent S2. Both change ResultKey even if the rendered numerical result happens to match.

The ultimate immutable method calculation identity should be:
```
ResultKey = SHA256(canonical(
 tenant + FormCode + formVersion + PeriodType + Period + Currency
 + economicEntity + scope + dataSpace + reportingBasis + dimensions
 + sealed TrustedComparisonSet / selected source hash
 + methodCode + variantCode + algorithmVersion + algorithmSha256
 + parameterContractSha256 + effectiveConfigSha256
 + fiscalCalendarVersion + precisionPolicyVersion + requested outputs))
```
Never use `params_sha256` alone to reuse a result across tenants/currencies/forms/revisions.

## 3. Example: parameter taxonomy, NOT invented approved settings for 60 families

| Method / variant | Allowed **candidate parameters** (must be specifically reviewed) | Invariant / consequence |
|---|---|---|
| `FIN-01/REV_NET_MOM` | `periodType=MONTH`, `lag=1`, `comparisonMode=PREVIOUS`, `zeroBaselinePolicy` | Only compatible consecutive MONTH/PERIOD_ONLY/INTERVAL and same Currency; new lag changes comparison window and input/result identity |
| `FIN-02/REV_CATEGORY_SHARE` | `dimensionPolicy`, `denominatorPopulation`, `scope` | Share-of-total denominator must be a reviewed population, not inferred by grouping any forms |
| `FIN-03/TREND` | `windowPeriods`, `trendEstimator` | Candidate code exists, but its trusted source binding not yet qualified |
| `FIN-04/CURRENT_RATIO` | `closingDatePolicy`, `valueStates`, `denominatorZeroPolicy` | STOCK/AS_OF cannot become monthly FLOW |
| `CON-01/PARETO_SALES` | `sortPolicy`, `minPopulationCoverage`, `returnPolicy`, `reportCutoffPercent` | Existing Pareto algorithm sorts/reconciles whole SKU population; `reportCutoffPercent` if later qualified limits reporting **only**, not source population or the algorithm's 100% distribution |
| `CON-02/ABC_SALES` | **`aLimitPercent=80`, `bLimitPercent=95`** (existing `DEFAULT_ABC_POLICY`); alternate `75/90` only after review | `0 < A < B < 100`; existing boundary-crossing SKU belongs to its preceding band; changed thresholds change classification, not Pareto formula itself |
| `PRO-03/CONTRIBUTION_MARGIN` | `variableCostTaxonomyVersion`, `costSignPolicy` | Unqualified taxonomy cannot silently count entire COGS as variable cost |
| `CASH-01/CCC` | `dayCountPolicy=FIXED_360|ACTUAL_365`, `workingCapitalBalancePolicy` | These are illustrative candidate policies, not approved defaults for every tenant |
| Other 52 families | `PARAM_CONTRACT_NOT_DEFINED` until individual method review | DO NOT generate invented approved defaults, 60 fake SQL routines, or auto-run flags |

Parameter classes to standardize gradually: **PERIOD/GRAIN, COVERAGE, LAG/WINDOW, THRESHOLD/PERCENT, VALUE_STATE, ACCOUNTING_BASIS, CURRENCY/UNIT, POPULATION/DIMENSION, ROUNDING/PRECISION, SCENARIO, SOURCE_POLICY, AGGREGATION_ALGEBRA**. Each schema defines required fields, data type/range, units, allowed values, optional defaults, economic interpretations and change impact `DIRECT|WINDOW_N|FY_ROLLUP|CROSS_FORM|REPORT_ONLY`. Percent values use a clear `0..100` unit (not a mixture of 0–1 fractions).

**Scope v1:** registry/configs are **GLOBAL**, no implicit tenant override. A future tenant-specific overlay must have separate immutable scope identity, permission and review (never simply overwrite `method_family`); tenant-specific overrides cannot violate financial S2 or execution-allowed parameter boundaries.

## 4. Moderation state machine and separation of duties

```text
PENDING (new immutable config version)
   -> independently reviewed APPROVED OR REJECTED
   -> if APPROVED, explicit compare-and-swap activation
      -> method_active_config pointer to exact config version
      -> method_config_activation append-only event
      -> future ImpactPlanner marks matching *qualified* source outputs stale
      -> separate execution permit -> new generation / immutable results
```

- `created_by/submitted_by` must correspond to the actual configuration owner. A different authorized reviewer may APPROVE/REJECT with reason and evidence SHA-256. No self-approval. One review decision per config; a rejected proposal is immutable; retry by creating a new version.
- **Review of parameters ≠ independent financial methodology S2.** Existing `fin_calc.method_s2_review` and revocations remain authoritative for method/form/basis/semantic SHA. A new effective SHA-256 MUST be bound to an exact reviewed economic semantic contract before future ACTUAL; a previously approved old config does not approve the new one.
- No HTTP management write endpoints are introduced in MR-R0. App/user-scoped authorization, separate privilege/RLS strategy, two-reviewer policy if required and activation/run APIs are **W1.2+/W7 gates**. Database constraints are defenses in depth, not substitute for actual authenticated moderator identity.
- Rejected/no-review versions cannot activate; activation pointer uses `pointer_version` CAS and append-only audit; stale writes must fail, old versions remain readable. Operators may roll back by **re-activating an older already approved config version**, auditing a new pointer version; no in-place mutation.
- Version activation is *not* synchronous recalculation. The future HTAP planner receives the immutable activation event and uses reverse dependencies, exactly qualified PeriodType/Period/Currency and source selection to invalidate only affected results. Old generation stays pinned until N+1 SEALED; physical partitions drop only after full cohort drain.

## 5. Initial database implementation: MR-R0

**Flyway:** `backend/src/main/resources/db/migration/V20261117_02__methodology_config_registry.sql`. It creates and seeds 60 immutable family rows from the actual `methodology-catalog-r1.json`, parameter contracts, version ledger, independent review, active CAS pointer and activation audit, and one 60-row read view. Hashes are computed by PostgreSQL SHA-256, not supplied by clients. It defines **one executable parameter validation profile only for the existing CON-02 ABC boundary rules**. All other families may be represented and may have proposals with `NOT_EXECUTABLE` profile, but may not be activated until exact method-specific parameter validation exists. This avoids fabricating “60 implemented methods.”

**Test:** `MethodologyConfigurationRegistryIntegrationTest` uses PostgreSQL 16 Testcontainers for 60-row parity, ABC 80/95 → 75/90, hash and algorithm identity changes, invalid limits and unexpected fields, moderation/no self approval, stale-pointer prevention, version history immutability, source catalog status and no automatic execution. Tests must be GREEN on the exact SHA, not inferred from migration presence.

**Known future addition:** method implementation status and formal parameter schemas for all 60 must be derived from actual review evidence; a static R1 catalogue snapshot is not evidence that later variants have passed financial S2. A separate append-only implementation-evidence ledger (rather than overwriting seed rows) may be added at W5 to track the method-by-method rollout.

## 5.A. MR-R1 — persistent Java MethodologyEntity with all 259 proposed parameters

**Requirement:** the parameter matrix is a backend persistence model, not an Excel-only document. Additive `V20261117_05__method_parameter_candidates.sql` seeds exactly **259 proposed parameter codes** under all **60 existing method_family** rows with `UNIQUE(method_code,parameter_code)`, FK and irreversible append-only records. All rows are `PROPOSED_UNREVIEWED`; the presence of a name is NOT an approved parameter type/default/validation formula or runnable method.

The Java entity model is `kz.fin.backend.methodology.persistence.MethodologyEntity` (`fin_calc.method_family`) with `@OneToMany(mappedBy = "methodology") List<MethodParameterCandidateEntity> parameters` and version history `List<MethodConfigVersionEntity> configurationVersions`. Existing `fin_calc.method_config_version.parameters JSONB` holds the **exact values for a particular immutable configuration version**, and stores SHA-256 and method/code version. The 259 proposed definitions are **not** inserted into the executable parameter contracts or the active configuration.

`V20261117_06__methodology_catalog_parameter_view.sql` extends the single 60-row `fin_calc.methodology_catalog_view` with `parameter_candidates` JSONB array; other fields continue to expose active/latest configs and moderation. The internal `MethodologyCatalogReadService.allWithParameters()` returns all 60 entities with nested parameter names using one JPA fetch-join; it is **read-only**, not a new public unauthenticated API.

**Minimum acceptance:** real PostgreSQL 16 Flyway upgrade, JPA `ddl-auto=validate`, 60 methods, 259 parameters, 60 represented methods, no duplicate candidates, FK integrity, irreversible parameter rows, correct FIN-01 and CON-02 parent-child association, single view row per method with nested candidate arrays, zero new `ACTUAL` or `releaseQualified`. Exact SHA of CI required. A future moderation stage will add typed versioned **approved** parameter contracts and safely display candidate-vs-approved status per variant; no arbitrary string parameter should reach Java/SQL executors.

## 5.C. Hard review: active-config audit and Java/SQL read-model parity

The [FIN HTAP S1 hard review](FIN-HTAP-HARD-PREIMPLEMENTATION-TECHNICAL-REVIEW-2026-11-17.md) establishes three forward-only W0 precautions:

- `MethodologyCatalogReadService` previously rendered immutable `method_family.implementation_status`, ignoring newer `method_implementation_evidence`. Reading the evidence-aware `methodology_catalog_view` status in a single read transaction ensures one consistent engineering **display**; no status grants method S2.
- `MethodConfigVersionEntity.getParameters()` previously copied only the top-level JSON object. The new defensive recursive snapshot rejects mutable nested output and preserves JSON null; the database's server-computed SHA-256 remains authoritative.
- The original `method_active_config` triggers were only on `INSERT/UPDATE`, leaving `DELETE` unlogged. New additive `V20261117_08__method_active_config_delete_safety.sql` rejects this unaudited transition. **No implicit config deactivation** is implemented: authenticated revocation, backdated race handling, re-review and branch/parent version rules are separate R2.2 prerequisites. Existing S2 method review revocation ledger does not substitute for a configuration lifecycle decision.

These updates are **non-financial hardening**, not S2, ACTUAL authorization, full 60-method implementation or a runtime `ExecutionPermit`.

## 5.B. Post MR-R2.0 / R2.0.1 / R2.1 code-backed addendum (2026-11-17)

**Normative consistency decision:** see [FIN HTAP post-R2.1 readiness audit](FIN-HTAP-TZ-READINESS-AUDIT-2026-11-17-R21.md) and the [updated W0–W8 sequence](FIN-HTAP-IMPLEMENTATION-STRATEGY-W0-W8.md). The §5 statement that only CON-02 numeric boundaries were executable is a correct **historic description of V02**, not the current total validation surface.

**Verified at GitHub HEAD `45d3f37a895ae73d3934ae9979495d28c1b1626b`:**
- R2.0: Hibernate CHAR(64) SHA field metadata now agrees with Flyway/PostgreSQL.
- R2.0.1: production `DenyAllLegacyExecutionPermitGuard` prevents planning, task execution and completion through legacy `SingleExecutorScheduler`; TEST-ONLY golden fixtures in `src/test` are not authority for production.
- R2.1: additive `V20261117_07__concentration_typed_parameter_contracts.sql` provides `CON01_PARETO_FIXED_V1` and `CON02_ABC_TYPED_V2`. It does NOT mutate the old `CON02_ABC_BOUNDARIES_V1` configs and does NOT approve any of 259 candidate parameters. `ConcentrationTypedParameters` is a strict pure Java decoder, not runtime wiring, reviewed source selection or a financial execution permit.
- `CON-01/PARETO_SALES`: `populationMetric=NET_SALES`, `tieSortPolicy=AMOUNT_DESC_SKU_CODE_ASC`, `returnsPolicy=REVIEWED_NET_RECONCILED`; `reportCutoffPercent` stays non-executable until an independently qualified algorithm/result contract exists.
- `CON-02/ABC_SALES`: `0 < aLimitPercent < bLimitPercent < 100` and the sole implemented `boundaryCrossingPolicy=CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED`; `sourceParetoVersion` is an **input receipt lineage claim**, NOT a mutable configuration parameter. Future R2.B must require exact verified Pareto result ID/version/source SHA and detect stale/missing input, not trust a caller-supplied string.

**Strict MR-R2.2 gate, not yet implemented:** identity must be derived from authenticated trusted actor context, not database `submitted_by/reviewed_by` strings alone; active config pointer CAS and immutable moderation are necessary but insufficient; bind approved schema/params/effective SHA + allowlisted algorithm **artifact SHA** + method/form/basis S2 revocation state to any proposed `ExecutionPermit`. A config or contract approval never permits ACTUAL. Legacy numeric-only historical CON-02 profile is not silently promoted to the new runtime contract. R2.B immutable PlanPreview/PlanCommit is `PLAN_ONLY`, before W2 admission and W4 result receipt.

**Evidence:** [FIN CI #44037588595](https://github.com/alimbetov/fin-analyzer/actions/runs/44037588595) and [Backend Domain #44037588533](https://github.com/alimbetov/fin-analyzer/actions/runs/44037588533): success, 1,972 backend tests on the same SHA; financial S2 and production release remain HOLD.

## 6. Mandatory follow-up tests and blockers

| ID | Required proof | Gate |
|---|---|---|
| MR-01 | 60 exact codes/statuses seeded from JSON, one view row per family | W1 |
| MR-02 | Changing ABC 80/95→75/90 changes params/effective hash; algorithm hash unchanged | W1 |
| MR-03 | Threshold 0, A≥B, B≥100, unknown/missing key, string-as-number | W1 |
| MR-04 | JSONB object-key order does not change V1 SHA within PG16 | W1 |
| MR-05 | Same exact effective configuration cannot be falsely versioned twice | W1 |
| MR-06 | Author self-review, forged submitter, rejected/no-review activation | W1 |
| MR-07 | Concurrent moderator activation with stale pointer | W1→W2 |
| MR-08 | Immutable config/review/history and method baseline | W1 |
| MR-09 | No new SQL or JAVA execution from approval alone; ACTUAL remains HOLD | W1 |
| MR-10 | Multi-form method binding, exact tenant/scope/grain/currency and calendar in ResultKey | W3→W4 |
| MR-11 | Changed config emits durable impact intent, old results pinned, only affected windows refreshed | W4→W5 |
| MR-12 | Separate S2 approval bound to config effective SHA and source-form basis | W5/W8 |
| MR-13 | Fresh + populated-upgrade PostgreSQL 16 and DB role permission proof | W1/W7 |
| MR-14 | Concurrent code deployments, snapshot reconciliation and catalog/DB drift check | W2/W7 |
| MR-15 | Cross-major PostgreSQL upgrade hash canonicalization vectors and selective rehash | before PG upgrade |

**Release:** **MR-R0 is registry scaffolding**, not method qualification, production calc, a fully secure moderator API, migration of the 60 into executable functions, or partition maintenance. Source FactSet outbox remains the only trusted computation trigger; parameter activation alone never bypasses execution permit. `RELEASE_HOLD` persists.

**PostgreSQL 16 SHA-256:** https://www.postgresql.org/docs/16/functions-binarystring.html  
**JSONB canonical semantics:** https://www.postgresql.org/docs/16/datatype-json.html
