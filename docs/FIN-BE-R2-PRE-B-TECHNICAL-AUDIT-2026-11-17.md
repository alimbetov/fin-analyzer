# FIN-BE-R2 — Independent-review-ready pre-R2.B technical audit

**Date:** 2026-11-17. **Repository:** alimbetov/fin-analyzer.  
**Branch:** `feat/fin-r0-r1a`. **Baseline audited:** `51f0d59139d4598bb2742497c6ee7f8985b4e874`.  
**Hardening candidate:** `5e1353bceb62abbf6d830370529aec3f84e89fb1`.  
**Status:** AUTHOR TECHNICAL REVIEW / CI PROOF REQUIRED ON FINAL HEAD / INDEPENDENT S2 NOT OBTAINED.  
**Scope:** MethodVariantDescriptor, MethodVariantRegistry, MethodApplicabilityEvaluator, published FactSet trust boundary, JUnit/Testcontainers, evidence verifier, legacy SingleExecutor interlock. **R2.B code not implemented**.

## 1. Baseline evidence

- FIN Backend Domain and full FIN CI passed at `51f0d59`; R2.A1+A2 mandatory contract gate counted 10 JUnit tests and PostgreSQL evidence.
- `FIN-BE-R2-METHODOLOGY-ORCHESTRATION-TZ.md` and the detailed `FIN-BE-R2-AB-CD-EF-DETAILED-IMPLEMENTATION-TZ.md` govern the staged release: plan only, bounded SYNTHETIC execution, then API/acceptance.
- Neither green JUnit nor a method's `autoEligible()` grants an ACTUAL calculation permit. The 60-family catalogue has `releaseGate=HOLD`.
- The current `SingleExecutorScheduler.tick()` still mixes `planOne()`, `claimOne()`, and `adapter.execute()` without a new R2 ExecutionPermit. This **legacy execution path remains an explicit P0 HOLD**. No R2 PlanCommit or activation is permitted to invoke it before an interlock is implemented/tested.

## 2. Findings and code-backed disposition

| ID | Priority | Verified issue | Resolution / test | Status |
|---|---|---|---|---|
| R2-PREB-01 | **P0** | Registry's semantic SHA-256 originally covered only seven descriptors; changes in the 60-family catalogue could go undetected by a future planner identity | Registry fingerprint now binds raw catalogue bytes + descriptor hash with versioned length-delimited encoding. Negative test changes the catalogue fingerprint and requires a different registry fingerprint | **FIXED IN CODE; CI PROOF REQUIRED** |
| R2-PREB-02 | **P0** | `implementationVersion=LEGACY_UNPINNED` and `resultSchemaVersion=LEGACY_RESULT_UNVERIFIED` cannot support a reproducible executable plan | Explicit `requireExecutablePlanBinding()` fails closed; tests verify candidate registry cannot authorize execution. For R2.B real build/source/adapter and result schema versions remain to be defined | **FAIL-CLOSED GUARD IN PLACE; PLAN BINDING OPEN** |
| R2-PREB-03 | **P0** | Evaluator returned matched metric names without UUID of selected assertion/source revision, form version, period or currency; plan provenance incomplete | Immutable sorted `MatchedFact` now records exact assertion/source, form, version, period and currency. Test proves stable read-only decisions and immutable lists | **FIXED IN CODE; CI PROOF REQUIRED** |
| R2-PREB-04 | **P0** | Two individually approved monthly REV_NET assertions from different approved source revisions could look comparable without a cross-source policy | Fail-closed `R2_INPUT_SOURCE_REVISION_CONFLICT` for FIN-01 cross-source/version, positive detection from actual PostgreSQL publication with two different approved sources | **FIXED IN CODE; CI PROOF REQUIRED** |
| R2-PREB-05 | **P1** | FIN-04 candidate pair needed confirmation of source/form/version consistency beyond metric, date and currency | Strict sourceRevisionId + formCode F01 + formVersion FIN-LIQUIDITY-V1 checks; changed source or form becomes `R2_CURRENT_RATIO_PAIR_INCOMPATIBLE` | **FIXED IN CODE; CI PROOF REQUIRED** |
| R2-PREB-06 | **P0** | Registry's `Role.OPTIONAL` and `Role.EVIDENCE_ONLY` were declared but unimplemented in A2 evaluator and legacy executor; such metadata could be misread as supported | Registry validator now rejects non-REQUIRED roles with `R2_DEPENDENCY_KIND_NOT_YET_IMPLEMENTED`, covered by negative unit test | **FAIL-CLOSED UNTIL TYPED R2.B** |
| R2-PREB-07 | **P1** | A valid publication with zero matching approved selections must not allow SYS source check to appear eligible | Evaluator explicitly rejects `R2_SOURCE_NO_APPROVED_SELECTION`; PostgreSQL publication itself already disallows empty FactSets | **DEFENSE IN DEPTH** |
| R2-PREB-08 | **P0** | Automatic and explicit legacy executor calls bypass R2 method review and A2 admission checks | Leave `fin.execution.enabled=false` and `fin.r2.synthetic-lab.enabled=false` defaults. Separate plan from execution and block B activation and ACTUAL until trusted permit is enforced at claim/commit. Avoid altering existing legacy behavior without a compatibility ADR | **OPEN — BLOCKS EXECUTION** |
| R2-PREB-09 | **P1** | Generic metric matching does not yet seal specialized SKU/category journal, F01 origin+intake, field mapping and independent finance S2 approval | Preserve `UNQUALIFIED` / `R2_REVIEWED_POPULATION_RESOLVER_PENDING` / `R2_METHOD_INPUT_PASSPORT_PENDING`. Do not turn MatchedFact into approved economic evidence | **OPEN — BLOCKS BUSINESS EXECUTION** |
| R2-PREB-10 | **P1** | No tenant/field/period ABAC at new evaluator's service boundary; future HTTP exposure could leak selected source coordinates | Evaluator is internal Java service only. R2.E must enforce explicit FIN_READ/FIN_ANALYZE plus tenant/field/period checks before returning `MatchedFact` | **OPEN — BLOCKS EXTERNAL API** |
| R2-PREB-11 | **P1** | Source population resolver currently reads all matching selection rows without resource budget; a large published set may exhaust memory | Add bounded selection policy, pagination/budget and positive source population reconciliation before exposing planner; benchmark real PostgreSQL on a bounded fixture | **OPEN BEFORE PUBLIC PREVIEW** |

## 3. Required automated gates

Mandatory backend test cases: `MethodVariantRegistryTest` now six tests, `MethodApplicabilityPostgresIntegrationTest` now six tests; independent `verify_fin_be_r2_a1_a2_evidence.py` must refuse test omissions/failures/skips. New negative cases:
- `registryFingerprintChangesWithCatalogContentsAndUnexpectedRolesAreRejected`: immutable catalog-bound registry hash and fail-closed non-REQUIRED role.
- `conflictingApprovedSourceRevisionsMustNotBecomeComparableRevenueInput`: two real selected approved-source assertions in one published FactSet cannot silently compare without explicit source policy.

Previous hardening candidate `e8f954e` failed to compile due to a stale duplicate `matchedFacts` collector left during a Fact → MatchedFact refactor. The exact compiler error was reproduced in FIN Backend Domain; `5e1353b` removed two obsolete lines. Do **not** mark candidate GREEN until actual GitHub Actions jobs succeed, including newly registered evidence-gate checks.

Validation for a released SHA: both FIN Backend Domain and FIN CI (backend-verification, verify, desktop-macos, acceptance-gate) SUCCESS on **one and the same HEAD**. Verify actual Maven test counts and new 12-case R2 A1+A2 gate in job logs. A doc-only commit also requires current-SHA CI according to repository rules.

## 4. Architecture gate before R2.B

R2.B is only **plan-only**. The next engineering work must:
1. Decide versioned canonical `PlanFingerprint` and fully pinned implementation/schema contracts in ADR; candidate versions are not executables.
2. Design one read-only `PlanPreviewService` with explicit tenant/ABAC boundary, DB source selection budget, deterministic input selection, scoped source receipt and reason-coded decisions. No DB writes, no `SingleExecutorScheduler.tick()`, no report execution.
3. Implement immutable PlanCommit and SQL uniqueness **only after** source/registry pass, real forward-only Flyway design and Testcontainers RED→GREEN evidence. Existing `analysis_run.event_id UNIQUE` and `outbox_delivery.event_id PRIMARY KEY` are untouched by this audit.
4. Add typed REQUIRED/OPTIONAL/EVIDENCE_ONLY DAG checks before relaxing registry fail-closed gate; verify unsupported dependency role cannot slip into legacy task creation.
5. Prevent PlanCommit from creating runnable `analysis_task` or outbox delivery ACK, and maintain activation disabled. Require explicit future C-stage PlanActivator with robust permit review, fencing, idempotent result/CORE, and independent S2.
6. Keep document-based financial and method qualification separate from engineering GREEN. No automatic promotion of R1 confirmed unqualified draft to a published FactSet, no ACTUAL without method authority.

## 5. Decision

**Current evidence:** implementation changes and negative tests exist; candidate CI status must be checked on final HEAD.  
**Approval:** no independent S2 APPROVE; Draft PR #2 remains Draft.  
**Release state:** `METHOD_S2_HOLD`, `PRODUCTION_EXECUTION_HOLD`; main unchanged.  
**R2.B transition:** only **PLAN_ONLY design/implementation** after latest SHA code CI GREEN and source/ABAC budget decisions are accepted; R2.B execution or activation stays HOLD even after a planner GREEN.
