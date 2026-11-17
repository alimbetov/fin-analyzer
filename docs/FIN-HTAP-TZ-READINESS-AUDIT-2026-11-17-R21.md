# FIN HTAP — Technical Specification Readiness Audit (post FIN-MR-R2.1)

**Date:** 2026-11-17  
**Repository / branch:** `alimbetov/fin-analyzer` / `feat/fin-r0-r1a`  
**Code evidence baseline:** `45d3f37a895ae73d3934ae9979495d28c1b1626b`  
**CI:** [FIN CI #44037588595](https://github.com/alimbetov/fin-analyzer/actions/runs/44037588595) and [Backend Domain #44037588533](https://github.com/alimbetov/fin-analyzer/actions/runs/44037588533): SUCCESS at baseline; backend 1,972 tests, zero failures/errors/skips.  
**Review authority:** internal code/spec consistency audit, NOT independent architecture S2, financial method S2, security certification, load qualification or operational release.  
**Decision:** **CONDITIONAL GO only for R2.2 specification and RED→GREEN hardening, W1.2.A source/period contract and tests, and strictly read-only R2.A/impact design. HOLD for R2.B PlanCommit until inputs and config authority are pinned; HOLD for autonomous execution, persistent HTAP results, cohort DDL reaper, financial ACTUAL and FIN HTAP production.**

## 1. Reviewed sources and facts, not projections

Normative source: [FIN-HTAP-CONSOLIDATED-TZ.md](FIN-HTAP-CONSOLIDATED-TZ.md); implementation sequence: [FIN-HTAP-IMPLEMENTATION-STRATEGY-W0-W8.md](FIN-HTAP-IMPLEMENTATION-STRATEGY-W0-W8.md); methodology: [ADR-R2-12](ADR-R2-12-VERSIONED-METHODOLOGY-REGISTRY-MODERATION-AND-PARAMETERS.md), [FIN-BE-R2-AB-CD-EF-DETAILED-IMPLEMENTATION-TZ.md](FIN-BE-R2-AB-CD-EF-DETAILED-IMPLEMENTATION-TZ.md); 166-form boundaries: [FIN-HTAP-FORM-166-INCREMENTAL-RECALCULATION-AND-PARTITION-LIFECYCLE-TZ.md](FIN-HTAP-FORM-166-INCREMENTAL-RECALCULATION-AND-PARTITION-LIFECYCLE-TZ.md).

Live baseline inspection covered `V20261117_01..07`, `ConcentrationTypedParameters`, `SingleExecutorScheduler`, `DenyAllLegacyExecutionPermitGuard`, `MethodVariantRegistry`, `MethodApplicabilityEvaluator`, `HtapCalculationPeriodPolicy`, `CatalogXlsxRevisionService`, `FormDraftRevisionV5Service`, `WorkspacePartitionMaintenanceUtility`, and PostgreSQL Testcontainers evidence.

| Component | Verified present | NOT established by that evidence |
|---|---|---|
| FactSet, published source/outbox and existing finance reports | Existing immutable source/publication baseline | A complete new HTAP calculation and result pipeline |
| Method family/entity/parameter read model | 60 method families; 259 unreviewed candidates; no new release flags | 60 implemented, S2-qualified formulas |
| R2.0 / R2.0.1 | CHAR(64) Hibernate/Flyway alignment; legacy production scheduler denies execution even if `fin.execution.enabled=true` | A working new ExecutionPermit / plan executor |
| R2.1 | Additive `V20261117_07` typed profiles `CON01_PARETO_FIXED_V1` and `CON02_ABC_TYPED_V2`, Java decoder, six PostgreSQL tests | Binding these configs to `QualifiedSku...` runtime adapters or R2 PlanPreview |
| Moderation | Immutable config/review/activation and CAS pointer mechanisms already exist | Authenticated, independently authorized moderator identity or config-bound method S2 |
| F166 W1.1 | Catalog XLSX confirmation writes a same-transaction immutable form-change journal | F02/V5 change capture, durable impact consumer, 166 qualified metric bindings or recalculation |
| Period policy | Source classifications and fail-closed MONTH/PERIOD_ONLY/INTERVAL candidate policy | Qualified fiscal calendar, multi-source history selection or all-grain calculation |
| Workspace/partition inspection | Non-destructive inspection utility | `fin_agg` sealed cache, physical stage lifecycle, cohort drain and safe `DETACH/DROP` |

**Interpretation:** a successful test run proves the current test suite at that SHA; absent classes, future SQL schemas, independent S2 and performance proof must remain explicitly **NOT IMPLEMENTED / NOT VERIFIED**.

## 2. Findings — specification blockers and required disposition

Priority is **gate-relative**. A P0 below may be a missing prerequisite for the NEXT stage, not a presently exploitable production defect.

| ID | Gate | Priority | Finding and code/spec conflict | Required design decision and executable proof |
|---|---|---|---|---|
| HTAP-TZR-01 | W0 / R2.B | P0 | Parallel plans: the consolidated W0–W8 map lists versioned plan/results under W4, but FIN-BE-R2 requires read-only PlanPreview and immutable PlanCommit **before** activation. No single explicit predecessor relation. | Insert R2.A/B PLAN_ONLY before W2 activation and W3 job execution. W4 builds **result receipts, report-head CAS and workspace**, not first plan. Prove PlanPreview=zero writes; PlanCommit=only immutable plan+decisions, no task execution. |
| HTAP-TZR-02 | R2.2 / R2.B | P0 | Config moderation is DB-guarded (`submitted_by`, `reviewed_by` strings) but no authenticated reviewer/author proof, method S2 effective-config binding, or revocation-race authority. | Freeze trusted actor provenance, role matrix, approval evidence+CAS, effectiveConfigSha/contractSha/method-S2 relation, validity/revocation evaluation. APPROVED config and an active pointer **never imply** ACTUAL. |
| HTAP-TZR-03 | R2.B / W2 | P0 | R2.1 creates typed contracts but decoders are not wired. CON-02 `sourceParetoVersion` is **input-result lineage**, not configuration. Legacy `CON02_ABC_BOUNDARIES_V1` remains historically valid. | Plan pin exact contract ID+SHA/profile, approved config ID+effective SHA, server-approved algorithm artifact identity, source Pareto **receipt ID+version+hash**, input selection hash and S2 status. Unbound/legacy policy => explicit BLOCKED, never silent default. |
| HTAP-TZR-04 | W1.2.A | P0 | Form/source `PeriodType` has WEEK/MONTH/QUARTER/HALF_YEAR/YEAR/AS_OF and Coverage includes YEAR_TO_DATE/LIFE_TO_DATE. Docs mix conceptual MONTH/AS_OF sampling with source `AS_OF`; wrong implicit translation could alter values. | Total versioned `InputPeriodType × Coverage × timeKind × fiscalCalendar` matrix; no YTD/LTD-to-month coercion, no stock SUM, no previous-available-row comparison. Unknown = BLOCKED_UNSUPPORTED_PERIOD_POLICY. |
| HTAP-TZR-05 | W1.2.A/B | P0 | Catalog XLSX and F02/V5 have distinct confirmation persistence. W1.1 captures only catalog events; some text says “all 166 changes” without channel scope. | Separate typed `CATALOG_DRAFT_CONFIRMED`, `F02_V5_CONFIRMED` (only if approved for impact scope), `FACT_SET_PUBLISHED`, and `CATALOG_DEFINITION_ACTIVATED` identities. No draft event may authorize calculation. |
| HTAP-TZR-06 | W1.2.A/B | P0 | Schema/ordered-row protection exists, but a reviewed `mapping_hash` and global catalog activation epoch are not pinned for zero-content-change cases. 166 form class does not mean 166 qualified metrics. | Freeze `schema_hash_v2`, `mapping_hash_v2`, `catalog_epoch`, input/form context and deterministic event identity. Require pending compatibility decision on mixed rolling deployments, not false NO_OP. |
| HTAP-TZR-07 | W1.3 / R2.B | P0 | Current FIN-01 single-source v1 rejects heterogeneous source revisions; future `TrustedComparisonSet` allows independently reviewed per-period revisions. Specs do not yet include an executable selector/schema with collision rules. | Keep FIN-01 v1 unchanged. Separate selector version, exactly one approved point per metric/dimension/period, complete timeline, closed lineage hash, immutable source snapshots; conflict returns BLOCKED without last-write-wins. |
| HTAP-TZR-08 | W2 | P0 | The target mode enum still names `LEGACY_TEST`; R2.0.1 deliberately **hard-denies the legacy executor in production**, including if `fin.execution.enabled` is enabled. | Production mode set must be OFF / PLAN_ONLY / SYNTHETIC_SHADOW / WORKSPACE (future); LEGACY_TEST is only `src/test` fixture authorization, not an application runtime mode. Fail startup on flag conflict and refuse new execution without an exact server permit. |
| HTAP-TZR-09 | W2/W3 | P0 | No implemented tenant-aware global+stage capacity/lease/physical SQL cancellation. SQL shadow code exists only as a limited FIN-01 pilot. | Atomic DB admission for tenant/global/STAGE budgets, fenced claims, statement termination confirmation, OLTP reserve, pressure cancellation; no permission to persist trusted output merely from PLAN_COMMITTED or WORKSPACE_SEALED. |
| HTAP-TZR-10 | W4.1 | P0 | Immutable new result receipt, exact input provenance and atomic complete required-report-set head CAS do not exist. W3 SQL experiment precedes W4. | W3.1 stays read-only SYNTHETIC SHADOW. Before any result promotion: append-only ResultKey, payload equality collision test, fence/lease check, source/config/S2 recheck, CAS report-set generation and reader-pinned N. |
| HTAP-TZR-11 | W4.2 | P0 | Read-only `WorkspacePartitionMaintenanceUtility` is NOT a reaper. Mixed-tenant cohorts cannot be dropped by date alone. | Separate reaper/operator role, catalog bounds/OID and membership ledger, all-pin/drain/liveness barrier, autocommit DETACH CONCURRENTLY, FINALIZE recovery, no DEFAULT/CASCADE, complete DDL audit. |
| HTAP-TZR-12 | W6/W8 | P0 before release | Acceptance names metrics and workloads but does not freeze actual dataset distribution, performance budgets, acceptable OLTP impact, retention bytes or threshold owner. | Owner-approved numeric targets **before** the benchmark; compare end-to-end DIRECT/AGG/HASH/RANGE and 1/2/4/8 workers; record p95/p99/WAL/locks/OLTP and reject optimizations with net regression. |
| HTAP-TZR-13 | W8 | P0 before release | Passing 1,972 tests and both workflows is not proof of load, S2, populated upgrade, reviewer identity, field/period ABAC or physical recovery. | Independent technical/security/DB/method S2, same-SHA acceptance ledger, populated Flyway upgrade, recovery drills and separate release/ACTUAL decisions. |
| HTAP-TZR-14 | R2.2 | P1 | `algorithm_sha256` is part of the config but the typed profile validates the version label, not cryptographically that caller-provided artifact SHA equals the deployed allowlisted implementation. | Bind deployment artifact SHA or vetted routine checksum server-side before PLAN_ONLY pins; mismatched/unknown artifact => BLOCKED_IMPLEMENTATION_NOT_PINNED. |
| HTAP-TZR-15 | R2.2 | P1 | `params_sha256` v1 is based on PostgreSQL 16 `jsonb::text`. Cross-major/Java hashing is not proven equivalent. | Golden vectors for key order, decimal scale, nested arrays, UTF-8 and non-ASCII; freeze PG16-MCONFIG-V1, version new codec on upgrade, never mutate historic hashes. |
| HTAP-TZR-16 | W1.2.B | P1 | F166 `ImpactPreview` and FIN-BE-R2 `PlanPreview` can be confused, wrongly implying draft may create runnable method nodes. | Different DTO/API purpose: FormImpactPreview is **unqualified change classification**, MethodologyPlanPreview is for a **published FactSet** and validated method applicability; no shared permit implication. |
| HTAP-TZR-17 | W1.2/W7 | P1 | A stale read-side methodology entity may disagree with the evidence-aware SQL catalog view on status; there is no proven drift check across DB and code deployments. | One versioned read contract, JPA-vs-view parity test and rolling 2-node catalog epoch handshake. |
| HTAP-TZR-18 | W1.2/W7 | P1 | No fully measured populated-Flyway upgrade and explicit separation between DB schema owner, app reporter, calculator and DDL reaper accounts. | Fresh+populated migration suite and grants/denies, pre-migration back-up, rollback/restart runbook; forbidden worker DDL/UPDATE/DELETE tests. |
| HTAP-TZR-19 | W1.2–W8 | P1 | Older documents still say “CON-02 only validated,” “LEGACY_TEST runtime,” and “next commit W0” despite R2.1 and R2.0.1 being completed. | Amend normative documents and maintain a single evidence/status ledger. Historical snapshots can remain unchanged but must link to this audit. |

## 3. Unambiguous authority matrix

| Event / state | May classify impacts? | May create immutable PLAN_ONLY plan? | May execute financial method? |
|---|---|---|---|
| `CONFIRMED_UNQUALIFIED_DRAFT` | Yes, with tenant and epoch verification | **No** | **No** |
| Global catalog definition activation | Yes, staged/paged | Not without an independently published source | **No** |
| `FACT_SET_PUBLISHED` | Yes | Yes, when R2.A/B are implemented and source is validated | Not merely by publication |
| Config `APPROVED` and active pointer | Targeted invalidation intent | Eligible input to future plan identity | **No** |
| Financial Method S2 `APPROVED` | Yes | Yes, but still pin exact config and source | Not alone; exact ExecutionPermit and runtime admission required |
| `PLAN_COMMITTED` | Yes | Already committed | **No** |
| `EXECUTION_PERMITTED` + CURRENT source/config/S2 + admitted capacity | Yes | Yes | Only for the exact permit tuple and only with live fencing |

No client-provided string (including `submitted_by`, `reviewed_by`, algorithm SHA, source revision or tenant) is security authority. All authentication and reviewed bindings must be server-derived.

## 4. W1.2.A schema decisions to freeze before implementation

The next implementable contract should be read-only and immutable; choose DB table names through a separate migration review.

**`TrustedFormChangeRef` minimum fields:**
- `eventId, eventType, organizationId, projectId, slotId, revisionId`; event-kind-specific nullable reference rules rather than fake slot FKs for F02/V5;
- `formCode, formVersion, catalogEpoch, schemaHashVersion, schemaSha256, mappingHashVersion, mappingSha256`;
- `economicEntityId, businessScopeId, reportingBasis, dataSpace, periodType, coverage, timeKind, periodStart, periodEndExclusive/asOf, calendarId/version, currency, amountScale`;
- `orderedContentSha256, documentSha256, publishedFactSetId/snapshotHash` **only if actually published**; must not synthesize publication from a draft;
- `originRevision/immutableSourceRef`, classification status and independent event provenance.

**`CalculationPeriodPolicy`** must explicitly classify every existing source type and coverage combination as `SUPPORTED` or a typed `BLOCKED_*` reason; source-validation success ≠ method applicability. Start numeric computation only with the separately qualified `MONTH + PERIOD_ONLY + INTERVAL` pilot.

**`FormImpactPreview`** should return `NO_OP | DRAFT_CHANGED | WAITING_PUBLICATION | WAITING_QUALIFICATION | BLOCKED`, pinned `eventId, sourceRefs, catalogEpoch, dependencyBindingVersion, impactedPeriods`, `reasonCodes`, deterministic preview fingerprint and explicit `executionPermitted=false`. No `analysis_run`, no `analysis_task`, no report result.

**`MethodologyPlanPreview`** is different: its source is a published FactSet; it pins method descriptor, contract/profile/hash, current config pointer version and effective SHA, algorithm artifact identity, selected source IDs, form/calendar/precision and verified upstream result receipt. R2.B `PlanCommit` persists immutable plan+decisions without launching methods.

## 5. Exact stage predecessor graph — amend W0–W8 implementation order

```text
CURRENT BASELINE: R2.0 + R2.0.1 + R2.1 GREEN
                      |
          W0 specification consistency freeze
                      |
       +--------------+------------------+
       |                                 |
  R2.2 versioning/moderator        W1.2.A source, period,
  identity/revocation/CAS         catalog epoch contract+RED
       |                                 |
       +---------------+-----------------+
                       |
        R2.A read-only applicability
          + W1.2.B form impact preview
                       |
        R2.B immutable PlanPreview/Commit
                   PLAN_ONLY
                       |
         W1.3 trusted source/history
          + W2 permit/capacity/lease
                       |
              W3 SYNTHETIC SHADOW
                       |
          W4.1 immutable result/report CAS
                       |
        W4.2 cohort lifecycle (separate gate)
                       |
           W5 qualified dependency DAG
                       |
            W6 perf -> W7 ops -> W8
```

**R2.B initial source scope:** PLAN_ONLY may use an existing **single published FactSet** whose exact snapshot, selected assertions and source provenance can be verified using accepted R2.A adapters. This does **not** confer support for composite multi-FactSet history. Such history stays BLOCKED until W1.3 separately seals a TrustedComparisonSet, calendar-selection policy and associated S2. W1.3's composite-history extension may thus follow a single-FactSet R2.B PLAN_ONLY prototype, but MUST precede any composite-source plan/ACTUAL execution.

This graph specifies a **hard authorization dependency**, not a requirement to finish all of W4.2 before testing independent W5 read-only logic. W1.2.A and R2.2 may be developed in small independent commits. W3.1 calculations may be performed in isolation as non-persisted tests only; W4.1 must precede any trusted result promotion. No second scheduler or new trust boundary is allowed.

## 6. Mandatory RED→GREEN acceptance suite

| Test ID | Required proof | Gate |
|---|---|---|
| HTAP-TZ-01 | Current legacy execution `enabled=true` denied at startup and direct tick; historical golden fixtures only in `src/test` | R2.2/W2 |
| HTAP-TZ-02 | Two users, forged/self reviewer, stale JWT/role or wrong org cannot approve/activate | R2.2 |
| HTAP-TZ-03 | Config `APPROVED`, active pointer and even method S2 without exact permit never run ACTUAL | R2.2/R2.B |
| HTAP-TZ-04 | Config/profile change affects effective SHA; old result identity preserved; no silent same-formula defaults | R2.2 |
| HTAP-TZ-05 | CON-02 wrong source Pareto receipt/version/hash or stale pointer blocks plan; exact selected immutable parent required | R2.B |
| HTAP-TZ-06 | Invalid/null typed JSON, extra keys, unsupported policy, changed algorithm version are rejected in PostgreSQL and Java | R2.1 regression |
| HTAP-TZ-07 | All source PeriodTypes/Coverages classified, unsupported blocked, MONTH/AS_OF stock not FLOW | W1.2.A |
| HTAP-TZ-08 | Same cells/new mapping or epoch, two deployment versions -> no false NO_OP/current result | W1.2.A |
| HTAP-TZ-09 | Catalog vs F02/V5 event provenance/rollback, tenant FK and no financial publication from draft | W1.2.A |
| HTAP-TZ-10 | Duplicate event and two worker claims -> one durable impact, no `analysis_run` | W1.2.B |
| HTAP-TZ-11 | New `FormImpactPreview` and `MethodologyPlanPreview` both have zero compute and no result writes | W1.2.B/R2.B |
| HTAP-TZ-12 | Same-FactSet Jan–Mar with different approved `source_revision_id` per point, exact form and complete calendar | W1.3 |
| HTAP-TZ-13 | Duplicate/overlap/mixed tenant/form/basis/currency/metric/period/FX unsupported -> BLOCKED | W1.3 |
| HTAP-TZ-14 | PlanPreview twice = zero rows; PlanCommit twice = one immutable plan, zero executor task | R2.B |
| HTAP-TZ-15 | Outdated source/config/contract/algorithm/S2 at plan activation -> blocked with immutable reason | W2 |
| HTAP-TZ-16 | 2+ JVM admission caps and physical SQL cancellation on expired lease; stale fence cannot persist | W2 |
| HTAP-TZ-17 | FIN-01 SQL/Java same golden on SYNTHETIC, no ACTUAL and no trusted report promotion | W3 |
| HTAP-TZ-18 | Duplicate result key with mismatched payload rejects collision; verified result receipt remains immutable | W4.1 |
| HTAP-TZ-19 | Partial required DAG does not promote a mixed generation current report | W4.1 |
| HTAP-TZ-20 | Pinned reader/lease/legal hold in a shared tenant cohort prohibits DROP; interrupted DETACH recovery | W4.2 |
| HTAP-TZ-21 | Populated old-schema Flyway upgrade preserves contract hashes, source, journal, reports and history | W0/W7 |
| HTAP-TZ-22 | App and SQL worker lack DDL, owner and BYPASSRLS; tenant context resets across pool reuse | W7 |
| HTAP-TZ-23 | Load numbers: source→plan→build→compute→commit→retire, OLTP p95/p99 plus WAL/disk/reclaim measured | W6 |
| HTAP-TZ-24 | Same-SHA cross-component evidence, independent S2, clear ACTUAL and release decision | W8 |

Test naming **does not imply execution**. Mark each as `RED_PENDING | GREEN_VERIFIED | BLOCKED` only with linked test output at the exact HEAD. Expected failure must be checked for zero unauthorized side effects, not just an exception.

## 7. Release and evidence policy

Required `AcceptanceEvidence` envelope per gate: `repository/branch/commitSha, migrationHead, postgresMajor, JDK, testSuite, testIds, observedOutcome, CI run URL, red/green commit, independentReviewer, S2 decisionId (when applicable), performanceFixtureAndTargets, exclusions, openRisks`.

Keep separate statuses: `ENGINEERING_TESTED`, `ARCHITECTURE_S2_APPROVED`, `METHOD_S2_APPROVED`, `SECURITY_REVIEWED`, `PERF_ACCEPTED`, `ACTUAL_EXECUTION_PERMITTED`, `PRODUCTION_RELEASE_APPROVED`. Never infer later statuses from a CI green test.

**Before any production work**, W0 must record owner decisions for:
1. Typed source event scope: catalog forms vs F02/V5 vs trusted FactSet (Architecture + Finance).
2. Calendar/coverage semantics and global catalog definition mapping epoch (Finance + Data Governance).
3. Identity/ABAC and reviewer authorization / revocation races (Security + Finance).
4. Trusted method artifact and permit authority, API exposure defaults (Architecture + Security).
5. PostgreSQL role topology, host limits and per-tenant admission budgets (DBA + SRE).
6. Physical cohort bounds, retention/legal hold and crash recovery (DBA + Security).
7. Measured dataset/traffic shape and explicit p95/p99/throughput/disk/OLTP numerical pass thresholds (Performance owner).

No approval is presumed here. Use existing branch only, no main merge, no frontend/Keycloak expansion or production flags.

## 7.A. Subsequent S1 adversarial findings

After the R2.1 readiness audit, the [S1 hard pre-implementation review](FIN-HTAP-HARD-PREIMPLEMENTATION-TECHNICAL-REVIEW-2026-11-17.md) inspected SQL active-pointer delete semantics, mutable nested JPA JSON, read-side implementation evidence parity, method artifact pinning, and independent R1 report/CORE publication paths. These are additional actionable **gate-relative** findings, not a retroactive claim that current FIN Backend CI failed.

- **W0.A:** code `6b543f2` makes method status evidence-aware and nested JSON snapshots immutable.
- **W0.B:** forward-only V20261117_08 blocks unaudited active pointer `DELETE`; config revocation remains to be designed, not silently disabled or deleted.
- **R2.2:** strict authenticated review, config re-review/revoke and parent version lineage, method-S2/config+algorithm artifact binding remain blocked.
- **R2.B/W2:** never reuse `TrustedAutoReportService` or `CompositeCoreReportPublisher` as a permit-free HTAP result path. `MethodVariantRegistry.requireExecutablePlanBinding` intentionally rejects unpinned methods.
- **W0→W6:** freeze dataset shape and performance SLO owner before making irreversible physical partition design decisions.

## 8. Final readiness disposition

| Scope | Decision | Allowed immediately |
|---|---|---|
| Existing R2.0–R2.1 engineering foundation | **PASS on baseline SHA** | Continue without rewriting historic migration/configs |
| W0 normative documentation | **CONDITIONALLY READY after this reconciliation; independent S2 HOLD** | Freeze requirements, RED fixtures, review decisions |
| FIN-MR-R2.2 | **GO for implementation** | Identity, reviewer proof, immutable CAS/lineage hardening, tests |
| HTAP W1.2.A | **GO for contract+RED tests** | Source/period/epoch design; no consumer launch |
| FIN-BE-R2.A / W1.2.B | **GO for strictly read-only planning design** | No computed results or unqualified execution |
| FIN-BE-R2.B PlanCommit | **HOLD pending R2.2 and source/period pinning** | No executable plan activation |
| W2–W5 / automatic calculation | **HOLD** | Controlled individual SHADOW tests only after their gate |
| Partition reaper / full HTAP / ACTUAL | **HOLD** | Read-only inspection and test design only |

**Next highest-value work:** `FIN-MR-R2.2 — Moderator Identity, Immutable Config Authority & Exact Method-S2 Binding`, coordinated with `HTAP-W1.2.A — Source/Period/Catalog Epoch Contracts`. They close the prerequisite risk in the configuration and input trust plane before durable FIN-BE-R2 PlanPreview/PlanCommit.
