# FIN HTAP — Methodology Reality Check and P0 Recovery Plan

**Review date:** 2026-11-17 · **Working branch:** `feat/fin-r0-r1a` only  
**Status:** CODE-BACKED COVERAGE SNAPSHOT / FIRST P0 GUARDS COMMITTED / INDEPENDENT S2 + FULL HEAD CI PENDING  
**Initial inspected HEAD:** `01151e9afb8e21a7a8d744f3045c3358d78c6329`. Recheck exact SHA and workflow at acceptance.  
**Normative:** [FIN HTAP Consolidated TZ](FIN-HTAP-CONSOLIDATED-TZ.md), [Implementation Strategy v1.1](FIN-HTAP-IMPLEMENTATION-STRATEGY-W0-W8.md), [Technical Review](FIN-HTAP-IMPLEMENTATION-STRATEGY-TECHNICAL-REVIEW-2026-11-17.md).

## 1. Honest status: 60 definitions are NOT 60 implemented methods

**Direct source:** `backend/src/main/resources/fin/methodology/methodology-catalog-r1.json`.

| Status (actual catalogue string) | Count | Engineering meaning |
|---|---:|---|
| `ONE_VARIANT_WIRED_UNQUALIFIED` | **5** | Exactly one candidate business variant connected to legacy adapter: `FIN-01`, `FIN-02`, `FIN-04`, `CON-01`, `CON-02`. Not a completed method family, no method S2 |
| `PURE_ENGINE_GOLDENS_AUTHORED_NO_TRUSTED_ADAPTER` | **2** | Pure calculations with synthetic golden, no approved source binding or run |
| `CODE_CANDIDATE_NOT_QUALIFIED` | **11** | A code-level candidate exists, but no trusted input/method approval |
| `NOT_YET_AUDITED` | **42** | No audited implementation evidence; must not be counted as wired |
| **TOTAL** | **60** | `releaseQualified=true`: **0**. `autoRunAllowed=true`: **0** |

**Important denominator:** `MethodVariantRegistry` has **seven** read-only legacy adapter descriptors: **five** business variants from the five families above and **two** `SYS` operational helpers. Registration is not execution: all have `LEGACY_UNPINNED`, `LEGACY_RESULT_UNVERIFIED`, `methodS2Qualified=false`; `MethodModuleRegistry.requireExecutableBinding` fails closed. FIN-01 has an additional standalone SYNTHETIC SQL pilot, also not automatic ACTUAL. A family is broader than one implemented variant.

**The 166-form registry is a different dimension**: 122 typed draft forms, 12 evidence only, 32 unqualified schema. Form catalogue completeness cannot prove any financial method has valid inputs, outputs or result authenticity. The generic reusable DELTA/SMA/STDEV indicator function is not an independently qualified family until it has a binding, deterministic input semantics, financial golden and method version.

**Regression:** `backend/src/test/java/kz/fin/backend/execution/MethodologyCatalogContractTest.java` now checks all five wired family names against actual registry descriptors and guards the 5/11/2/42 status distribution and 0 qualified. This is a **current evidence baseline**, deliberately revised with code+review as methods become genuinely qualified; it does not freeze the business target at five.

## 2. P0/P1 disposition against actual code

| Review | Classification | Current action/proof | Further gate |
|---|---|---|---|
| P0 PeriodType/Coverage | **P0-GUARD CODED, integration pending** | `HtapCalculationPeriodPolicy` validates the existing `ReportingPeriodV5.Type` and `Coverage` combinations. Only `MONTH/PERIOD_ONLY/INTERVAL` can be an **eligible candidate**, no result/permit. Other valid combinations blocked, invalid source combinations rejected. Unit tests enumerate all 24 pairs and month/AS_OF/YTD/LTD scenarios | W1.2.A independent contract review; metric-specific WEEK/HALF_YEAR/YTD/LTD/AS_OF policy and PostgreSQL history/finance tests before enabling them |
| P0 False NO_OP on schema/mapping | **SCHEMA GUARD CODED; MAPPING STILL OPEN** | Existing `normalized_sha` **already includes** `schemaFingerprint`; previous review overstated the possibility of a *schema-only* false NO_OP for correctly canonicalized new drafts. `CatalogXlsxRevisionService.confirm` now compares draft schema SHA to the current server registry and requires prior schema equality for NO_OP. PostgreSQL regression clones old immutable draft with mismatched schema; expects rejection and no change event | Server-approved `mapping_hash` and catalog activation epoch, rolling-version compatibility, unchanged data but mapping changed negative test. Do not claim P0 fully closed |
| P0 TrustedComparisonSet vs FIN-01 v1 | **SAFETY PRESERVED / FUTURE VARIANT** | Current FIN-01 still intentionally rejects different `source_revision_id` in its input. No unsafe loosening | W1.3 sealed history + explicitly versioned FIN-01 V2 candidate; golden across revised multiple periods, independent financial S2 |
| P0 SQL pilot before immutable results | **STRATEGY SEQUENCING CLOSED / RUNTIME NOT YET IMPLEMENTED** | W3.1 is **SYNTHETIC read-only SHADOW**. No new trusted current/CORE results before W4.1 immutable receipt/fence/CAS mechanism | SQLB golden + failure/cancellation; W4.1 RED→GREEN before promotions |
| P1 Destructive partitions | **FAIL-CLOSED AS DESIGNED** | `WorkspacePartitionMaintenanceUtility` remains catalog **read-only**; no DDL or accidental DROP, no stage table exists yet | W4.2 durable full cohort membership/reader drain + PostgreSQL DETACH/FINALIZE/DROP/recovery tested with separate DDL role |

**New P0 safety boundary:** the period adapter is a classification contract, not an authorization to compute on unqualified drafts. `HtapCalculationPeriodPolicy.eligibleCandidate()` must never substitute `FactSetPublicationService` publication, exact trusted source selection, MethodApplicability and ExecutionPermit. `YEAR_TO_DATE` and `LIFE_TO_DATE` are cumulative coverage, not a monthly incremental flow. `AS_OF` stock may have MONTH cadence in a reviewed future closing-period method; do not force current stock into INTERVAL.

## 3. Recovery strategy: secure the calculation plane, then deliver real variants

**Do not implement 55 arbitrary methods before the engine is correct.** A method is a fully qualified and versioned data contract, not a class name or a formula. Work in vertical *evidence slices*: true input → source approval/publication → applicability and block reasons → plan/permit → calculation → immutable receipt → report/read → PostgreSQL Testcontainers and independent golden → method S2.

**Evidence levels for every method variant:**

1. `CATALOG_ONLY` — name/description.
2. `ALGORITHM_CANDIDATE` — pure Java or SQL code and example tests, no qualified source.
3. `BOUND_VARIANT` — exact source/form/metric/period/currency and controlled adapter.
4. `INTEGRATION_TESTED` — PostgreSQL and Java/SQL parity, negative tenant/period/currency, trusted published inputs, immutable receipts.
5. `METHOD_S2_APPROVED` — independent finance reviewer approves the exact version/formula, assumptions, sample results.
6. `ACTUAL_PERMITTED` — separate explicit production execution policy and operator release, not implied by earlier stages.

**First production-worthy vertical:** `FIN-01/REV_NET_MOM` on explicitly qualified `MONTH/PERIOD_ONLY/INTERVAL` and one currency in a **SYNTHETIC shadow**, then immutable approved history/result pipeline, then independent S2 and authorized ACTUAL only after gates. Next qualify available business variants `FIN-02/REV_CATEGORY_SHARE`, `FIN-04/CURRENT_RATIO`, `CON-01/PARETO_SALES`, `CON-02/ABC_SALES` based on exact approved source populations and their dependency order (CON-02 depends on CON-01). These five represent **5 families × one variant**, not full family coverage. Then evaluate the 13 candidates with evidence and business ROI; the 42 unaudited families require independent domain requirements and prioritized scope.

**Prioritization rule:** method order follows financial value, availability/quality of approved source, deterministic mathematical definition, window/currency complexity, cross-form dependency and independent tests. Do not run all 60 on a form-change event. For a changed source, resolve the finite reverse dependency DAG and period fanout, then allow only eligible variants.

## 4. Concrete deliverables before claiming central HTAP useful

| Phase | Deliverable | Strict acceptance |
|---|---|---|
| **Now, W1.2.A** | Period/source policy and stale schema guard; 60-family evidence test | Verified PostgreSQL CI with actual SHA; 166 existing form regressions preserved; mapping epoch remains explicitly blocked |
| **Next, W1.2.A2** | Versioned `schema_hash`, separately reviewed `mapping_hash`, typed `FormDefinitionActivation`, rolling deploy compatibility | Same values under new mapping do **not** count NO_OP; old source pinned and remains readable |
| **W1.2.B** | Read-only event consumer/impact graph; 166 forms receive reasoned status | Tenant, form, periodType, period, Currency never mix; no financial method run from draft-only update |
| **W1.3/W2** | TrustedComparisonSet, sealed generation, one Java scheduler with global/tenant admission | 2-node races and resource fencing; SQL never authorized solely by form journal |
| **W3/W4** | FIN-01 SQL/Java SYNTHETIC golden then immutable calculation receipt and head CAS | Input/source/period/currency exact, no stale result, no partial report |
| **W4.2** | Optional bounded RANGE physical workspace, safe reaper | All members retired, no pinned readers, no live SQL; DETACH CONCURRENTLY + FINALIZE tests |
| **W5–W8** | Four more wired business variants then candidate qualification; measured performance/finance S2 | Explicit per-variant approvals, realistic OLTP p95 + WAL metrics, no fabricated 60-method completion |

**Release rule:** Claim `FIN HTAP PLATFORM_READY` only after one fully qualified and operationally bounded source→result vertical and independent technical/security qualification. Claim `METHOD_FAMILY_READY` only after explicitly defined supported variants and finance S2, not merely a single pilot. Claim `60_METHODS_READY` only after all 60 have evidence at the required level. Existing `RELEASE_HOLD` and ACTUAL HOLD remain.

## 5. Required CI and migration evidence

- New source changes: `backend/src/main/java/kz/fin/backend/intake/HtapCalculationPeriodPolicy.java`, `backend/src/main/java/kz/fin/backend/intake/CatalogXlsxRevisionService.java`.
- New/expanded tests: `HtapCalculationPeriodPolicyTest.java`, `CatalogXlsxRoundTripIntegrationTest.java`, `MethodologyCatalogContractTest.java`.
- Immutable source/event Flyway `V20261117_01` from W1.1 remains **unchanged**; no destructive migration, method promotion or new table has been made for these guards.
- Record current exact SHA + FIN CI + FIN Backend Domain; the existing W1.1 earlier CI cannot certify these later guards. Independent S2 and exact upgrade-from-populated PostgreSQL fixture remain open.

**Safety and status:** Documentation does not equal code completion. The new period policy currently exposes a validation seam, not a wired execution gate in every calculation path. If future calculation entrypoints do not call it and pin the complete source context, the period P0 is **not fully closed**.
