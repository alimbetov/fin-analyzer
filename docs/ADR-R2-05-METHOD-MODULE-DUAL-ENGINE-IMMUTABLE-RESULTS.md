<!-- FIN-R2-W GOVERNANCE HEADER: superseded topology only; retain safety and acceptance -->
> **FIN-BE-R2-W architecture decision (2026-11-17):** The target execution topology, tenant-aware capacity management, shared calculation workspace, SQL/JAVA worker admission, fixed-partition policy and W0–W8 delivery order are governed by [ADR-R2-06](ADR-R2-06-TENANT-AWARE-CALCULATION-WORKSPACE.md), [FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ](FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ.md) and [FIN-BE-R2-EXECUTION-SUPERSESSION-LEDGER](FIN-BE-R2-EXECUTION-SUPERSESSION-LEDGER.md).
> **SUPERSEDED ONLY:** globally FIFO/single-task execution as the target scaling model, fixed lease as a long-term resource policy, stand-alone scheduling topology, and previous implementation sequencing. **RETAINED:** trusted immutable FactSet, server-owned method variants and permits, exact data-space/scope controls, versioned DAG/PlanPreview/PlanCommit separation, golden tests, CORE constraints, independent S2 and RELEASE_HOLD. Historic sections below are retained as traceable requirements; code is not deprecated automatically. **Document changes do not implement the new architecture.**

# ADR-R2-05 — One Business Method, One MethodModule, Dual Engine, Immutable Results

**Decision:** ACCEPTED AS TARGET ARCHITECTURE / IMPLEMENTATION PENDING
**Date:** 2026-11-17
**Repository:** `alimbetov/fin-analyzer`; **sole development branch:** `feat/fin-r0-r1a`
**Scope:** FIN-BE-R2.A–F, FIN-DIAG-60 and future method implementations.
**Authority:** This ADR supplements `FIN-BE-R2-METHODOLOGY-ORCHESTRATION-TZ.md` and `FIN-BE-R2-AB-CD-EF-DETAILED-IMPLEMENTATION-TZ.md`. Existing restrictions on FactSet trust, execution permits, CORE, release HOLD, and Flyway immutability prevail if any interpretation conflicts.

## 1. Normative decision

**One business method = one independently versioned `MethodModule`. Exactly one common orchestrator. Exactly two calculation engines: `SQL` and `JAVA`. Exactly one centrally managed immutable logical-result store.** These are logical modules in the existing Java 21 / Spring Boot monolith, **not** separate microservices or a second scheduler.

A method family (`FIN-01`) is not executable; a precisely versioned variant (`FIN-01/REV_NET_MOM`) is. The 60-family catalogue remains metadata with `releaseQualified=false` and `releaseGate=HOLD`; no registration, successful calculation or synthetic test qualifies a family.

The existing seven `MethodTaskAdapter` implementations (two SYS + FIN-01, FIN-02, FIN-04, CON-01, CON-02), `SingleExecutorScheduler`, outbox, `MethodVariantRegistry`, `MethodApplicabilityEvaluator`, `TrustedAutoReportService`, `AutoIndicatorEngine`, `ExecutionContracts`, and `CompositeCoreReportPublisher` are **reused or migrated behind facades**, not copied into a parallel platform.

## 2. Module isolation and interfaces

Each independent business variant owns: immutable `MethodDefinition`, versioned input contract, applicability rules specific to that method, deterministic source population mapping, computational implementation (or bounded SQL), versioned result schema, its own golden/negative/SQL regression fixtures, diagnostics and documented method semantics. Cross-cutting authorization, trusted FactSet resolution, execution permit, worker lease/fencing, task DAG, result persistence, retention and audit are centralized. No module may approve a source, promote `ACTUAL`, write fact assertions, mutate another module's result, publish CORE by bypassing orchestration, or execute arbitrary client-provided formula/SQL.

Target contracts (illustrative API to implement; **not a claim that these classes exist**):

```java
enum CalculationEngine { SQL, JAVA }
record MethodIdentity(String familyCode, String methodCode, String variantVersion) {}
record MethodDefinition(MethodIdentity identity, CalculationEngine engine,
                        String implementationVersion, String resultSchemaVersion,
                        String inputSchemaVersion, String descriptorFingerprint,
                        String roundingPolicyVersion, String assumptionsVersion,
                        List<MethodDependency> dependencies) {}
interface MethodModule {
    MethodDefinition definition();
    MethodInput resolveAndValidate(PinnedFactSet source, MethodParameters parameters);
    MethodComputation compute(MethodInput input, MethodExecutionContext context);
}
interface SqlMethodModule extends MethodModule {}
interface JavaMethodModule extends MethodModule {}
```

`MethodModule.compute` receives an immutable, server-selected and prevalidated input; it never loads arbitrary organization data by a free-form client selector. `MethodTaskAdapter` remains the compatibility SPI during migration: an allowlisted bridge maps exactly one method variant to exactly one `MethodModule`. Avoid duplicate implementations and ambiguous bean selection. The orchestrator alone controls `resolve -> applicability -> permit -> plan -> activate -> compute -> result commit -> report`.

Existing `MethodVariantRegistry.requireExecutablePlanBinding()` and advisory `MethodApplicabilityEvaluator.Decision.executionPermitted()` remain fail-closed until the existing R2 permit authority is independently implemented and verified. `autoEligible=true` is never an execution permit.

## 3. Engine selection and execution safety

`SQL` is suitable for set-based, relational and window-function analysis (horizontal, vertical, Pareto/ABC, scoped period comparisons, grouped budget variances). `JAVA` is suitable for precision-sensitive models, cash cycles, multi-stage projections, iterative/investment calculations, scenario logic. A method has **one canonical engine in the versioned descriptor**, with any reference implementation for golden cross-checks clearly designated **non-production**.

SQL:
- Use reviewed, immutable-versioned SQL resources and bound parameters (`NamedParameterJdbcTemplate`) or equivalent repository-owned prepared statements; **no SQL text, relation name, stored procedure, Java class, formula or expression accepted from HTTP, configuration or mutable registry rows**.
- Query only exact tenant-scoped published FactSet/source selection through server-enforced SQL joins and deterministic cardinality, period, measureKind, currency, reportingBasis and qualification policies; use REPEATABLE READ for a coherent pinned snapshot.
- Never infer `ACTUAL` from draft; never use catalog latest as fallback; never call a security-definer procedure that widens tenant privileges. Bound read-only DB credentials are preferred for SQL computations; writes to the result store occur via the orchestrator's dedicated transaction.
- Explicit order-by and tie-breaking for `LAG()`, rolling accumulations and classifications; exclude mixed populations, overlaps, stale publication or incompatible periods. No implicit exchange-rate conversions.
- Numeric calculations use PostgreSQL `numeric` with explicit precision, scale, division-by-zero and rounding policy; compare with Java `BigDecimal` reference fixtures and canonical decimal encoding. Zero, missing, not applicable and estimated are distinct statuses.
- Versioned SQL source checksum must be pinned into `implementationVersion` / `implementationFingerprint`; changing SQL logic requires a new version and regression proof.

JAVA:
- Use `BigDecimal` / versioned `MathContext`, immutable inputs and explicit precision/rounding; avoid `double` and `float` for accounting calculations.
- Do not hold a DB transaction during lengthy CPU calculations. Apply bounded memory/time, source fingerprint validation, cancellation/timeout policy and deterministic ordering.
- Forecast and valuation assumptions (discount rates, cash flow dates, tax/basis, horizon) must be pinned in `assumptionsFingerprint`; never conceal estimates behind CALCULATED.

Both engines produce the same versioned `MethodResultEnvelope` semantics and are tested against a common result-contract test suite.

## 4. Immutable result identity and storage

Introduce one **additive Flyway migration** for central `fin_exec.method_result` or `fin_calc.method_result` (schema choice requires code-backed DB review), append-only results, immutable FK/scoping and read-side indexes. Do not modify legacy `analysis_task.result_json`, report publications, CORE or old Flyway checksums in place. Use existing immutable trigger function or an equivalent deny UPDATE/DELETE policy and explicit FK protection. Operational retries may update *task state*, not the committed logical result.

Required persisted fields:
- `result_id`, `organization_id`, `economic_entity_id`, `business_scope_id`, `fact_set_revision_id`, `source_snapshot_hash`, `data_space`, `reporting_basis`;
- `method_code`, `variant_version`, `descriptor_fingerprint`, `implementation_version`, `implementation_fingerprint`, `result_schema_version`, `engine`;
- `input_fingerprint`, `parameters_fingerprint`, `assumptions_fingerprint`, `policy_fingerprint`, `permit_fingerprint`, `logical_result_key`;
- canonical `result_json` (typed schema validated), `result_hash`, `computation_status`, `qualification_status`, creation timestamp, task/run provenance.

`logical_result_key = SHA-256(canonical versioned encoding of source identity + method/variant + exact implementation and SQL checksum + descriptor + selected population fingerprint + parameters + assumptions + policy + result contract)`. Never include random UUID, worker ID, attempt number, timestamps or localized strings. Add a database UNIQUE constraint on tenant + logical key. Concurrent workers must yield one immutable result with bounded conflict/re-read verification, not an UPDATE or duplicate logical record. A collision with unequal canonical content fails closed; never overwrite.

Separate `COMPUTED` from `METHOD_QUALIFIED` and `PRODUCTION_PUBLISHABLE`. Result envelope includes machine-readable `reasonCodes`, per-field provenance, value states, canonical decimal strings and disclosure of warnings. `ACTUAL` is guarded by independent qualified source + method S2 + execution authority; SYNTHETIC proof cannot grant ACTUAL. Historical results remain readable against pinned method/result schema; schema changes require explicit read-only adapters or migration contracts without rewriting saved results.

Preserve existing CORE_PILOT_V1 SQL readiness states (`PARTIAL`, `BLOCKED`) until a separate, evidenced versioned migration allows anything else. No auto-publication to third parties.

## 5. Orchestrator: single existing execution path

```text
published FactSet + committed outbox
    -> scoped PinnedFactSet snapshot
    -> versioned MethodVariantRegistry / module binding
    -> MethodApplicabilityEvaluator + independent ExecutionPermit
    -> deterministic PlanPreview (read-only)
    -> guarded PlanCommit (immutable plan, no compute)
    -> separately authorized Activation
    -> existing SingleExecutorScheduler (lease, fencing, DAG, retries)
    -> MethodTaskAdapter bridge -> SQL or JAVA MethodModule
    -> validate result envelope / canonical hash / immutable result commit
    -> existing CORE publisher and authorized read-only APIs
```

Existing outbox `event_id` uniqueness and `analysis_run.event_id` uniqueness remain intact. Versioned re-analysis needs its own **new logical plan identity** and proven migration; do not reuse a delivered outbox event to duplicate a run. Crash after a computation but before receipt must converge to one committed logical result. Any permit revocation, stale source, mismatched implementation hash or result schema blocks compute or publication with reason codes.

## 6. Migration plan without breaking current code

**M0 — contract, RED tests and ADR.** Inventory current seven adapters, method catalogue and exact tables. Fix interface/version fields. Assert no new scheduler, outbox or client-owned executable definitions. Existing R1-X6 uploads remain unqualified.

**M1 — module SPI and dual engine.** Introduce `MethodModule`, `CalculationEngine`, `MethodResultEnvelope`, `MethodModuleRegistry` with startup fail-fast for duplicate keys/bindings, cyclic deps, missing source contracts, unsupported versions. Provide adapter bridges; preserve existing adapter codes and feature flags. Keep execution OFF.

**M2 — first SQL pilot.** Port `FIN-01/REV_NET_MOM` to a pinned SQL implementation *only after* its reviewed approved source semantics match the Java golden engine. Then `FIN-02/REV_CATEGORY_SHARE`, `CON-01/PARETO_SALES`, `CON-02/ABC_SALES`, one at a time, each with independent approval and no simultaneous duplicate task binding.

**M3 — first JAVA pilot.** Move `FIN-04/CURRENT_RATIO` behind `JavaMethodModule` without changing its F01 lineage and fail-closed denominator rules. Add `PRO-03` and `CASH-01` only as separate variants after proving inputs/units/formulas; existing pure-engine classes do not count as qualified module implementations.

**M4 — central immutable store.** Add backward-compatible Flyway schema, result commit/re-read and race/replay tests. Do not enable CORE consumption of new result envelopes until old/new reconciliation is proven.

**M5 — orchestrator integration.** Bridge existing scheduler to versioned plan + permit + module execution, making preview, commit and activation physically separate. Preserve lease fencing, retry and required/optional dependencies, default disabled.

**M6 — security, load and S2.** Tenant/ABAC security, SQL boundary protection, source revocation, offline migration, concurrency (two workers), crash-replay, 166-form non-promotion regression, locale-independent fingerprints, diagnostic acceptance and same-SHA CI. Independent Method S2 and Security S2 are separate decisions.

## 7. Per-method acceptance contract (mandatory)

For **every** method variant:
1. Document financial objective, formula and economic meaning, applicable sectors, input grain, dimensions, period/calendar, amount scale and currency policy.
2. Pin source forms, fields, metric contract versions, trusted population, required/optional inputs, provenance and exclusions; never infer absent or estimated input.
3. Define engine, exact SQL/Java implementation/checksum, `MathContext`, rounding, zero denominator, negative values, ties, nulls, revisions and expected canonical result schema.
4. Include at least: positive independent finance golden; missing vs explicit zero; period gap, overlap and boundary; currency/basis conflict; duplicate source; stale/revoked source; cross-tenant; idempotent replay; two-worker contention; schema/implementation change; non-approval of ACTUAL.
5. PostgreSQL Testcontainers for SQL (actual PostgreSQL 16, not H2), JUnit BigDecimal golden for Java, full integration through existing scheduler and central result store, verified lineage of every published number.
6. Independent methodological S2 accepts **one exact variant version only**; family-wide qualification is never inferred; all CI evidence must refer to the same immutable HEAD.

A module is **IMPLEMENTED** when code and interface exist; **ENGINEERING_GREEN** only after relevant tests; **METHOD_S2_APPROVED** only on independent review; **PRODUCTION_ENABLED** only after separate operational permit. `RELEASE_HOLD` remains until expressly cleared.

## 8. Migration matrix (initial target; not completed work)

| Variant | Canonical engine target | Existing implementation to preserve | Qualification |
|---|---|---|---|
| `FIN-01/REV_NET_MOM` | SQL | `QualifiedRevenueHorizontalAdapter`, `AutoIndicatorEngine` | HOLD |
| `FIN-02/REV_CATEGORY_SHARE` | SQL | `QualifiedRevenueStructureAdapter` | HOLD |
| `FIN-04/CURRENT_RATIO` | JAVA | `QualifiedCurrentRatioAdapter` | HOLD |
| `CON-01/PARETO_SALES` | SQL | `QualifiedSkuParetoAdapter`, `ConcentrationAnalysisEngine` | HOLD |
| `CON-02/ABC_SALES` | SQL | `QualifiedSkuAbcAdapter`, `ConcentrationAnalysisEngine` | HOLD |
| `PRO-03/CONTRIBUTION_MARGIN` (provisional exact variant code) | JAVA | `OperatingFinanceMethodEngine` pure engine | NOT WIRED / HOLD |
| `CASH-01/CASH_CONVERSION_CYCLE` (provisional exact variant code) | JAVA | `OperatingFinanceMethodEngine` pure engine | NOT WIRED / HOLD |

For the other 53 families/variants, select the engine only after verified inventory and financial semantics review; **do not silently assign 60 execution engines from catalogue titles**. The two SYS adapters stay infrastructure modules and do not count toward the 60 business families.

## 9. Change control and Done criteria

Update all R2 references to treat this ADR as normative. Deliver M0–M6 sequentially; no merge to main; no new branches; backend only; Flyway forward-only; preserve concurrent commit history; no S2 self-approval. A release candidate needs one fixed-SHA CI GREEN, documented independent S2, MacBook local smoke, explicit operational approval. **This ADR itself is a specification change, not implementation or test evidence.**
