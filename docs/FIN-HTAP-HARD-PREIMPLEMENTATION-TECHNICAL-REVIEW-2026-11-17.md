# FIN HTAP — Hard Pre-Implementation Technical Review and Engineering Gate (2026-11-17)

**Disposition:** S1 INTERNAL HARD REVIEW; independent architecture/security/financial S2 **PENDING**. Full FIN HTAP implementation **HOLD**; targeted W0 and R2.2 safety-hardening **GO**.  
**Repository/branch:** `alimbetov/fin-analyzer` / `feat/fin-r0-r1a` (one branch, no merge to main).  
**Verified starting point:** `148b741d276945a76745fc4cc84b2277cf6cff0e`, [FIN CI #44041365990](https://github.com/alimbetov/fin-analyzer/actions/runs/44041365990) **SUCCESS** (1,972 backend tests).  
**Post-baseline changes under this review:** catalog evidence-status parity and defensive JSON parameter snapshots in `6b543f2c9a2727afd5c4b49cc5a6ac5781c77fc4`; forward-only active-config deletion hardening is a separate next migration. Recheck live HEAD and current CI before interpreting any new test as GREEN.

**Normative references:** [FIN HTAP Consolidated TZ v1.2](FIN-HTAP-CONSOLIDATED-TZ.md), [W0–W8 strategy v1.2](FIN-HTAP-IMPLEMENTATION-STRATEGY-W0-W8.md), [post-R2.1 readiness audit](FIN-HTAP-TZ-READINESS-AUDIT-2026-11-17-R21.md), [ADR-R2-12](ADR-R2-12-VERSIONED-METHODOLOGY-REGISTRY-MODERATION-AND-PARAMETERS.md), [FIN-BE-R2 detailed TZ](FIN-BE-R2-AB-CD-EF-DETAILED-IMPLEMENTATION-TZ.md). Where these disagree, preserve the stricter source/tenant/financial S2/ACTUAL/immutable history safety rule; this review changes **sequencing**, not authorization.

## 1. Verdict and explicit scope

The PostgreSQL 16 / Java 21 / Spring Boot single-backend approach is worth preserving. It is **not** yet a functioning source→automated HTAP calculation→immutable report pipeline.

- **Observed / tested:** FactSet publication and its separate trusted outbox; catalog draft change journal for catalog XLSX; 60 family definitions and 259 unreviewed parameter candidates; 5 wired *unqualified* financial business variants and 2 SYS adapters; R2.A read-only candidate registry and applicability; R2.1 typed CON-01/CON-02 config validation; fail-closed legacy executor; non-destructive workspace partition inspection.
- **Not demonstrated:** 60 qualified implementations, R2.B immutable plan, trusted algorithm artifact binding, independently authorized moderator, complete PeriodCalendar, all-input financial mapping epoch, F02/V5 impact adapter, persistent impact delivery/consumer, SQL/JAVA production dispatcher, global/tenant/stage capacity, signed/fenced ExecutionPermit, `fin_agg` SEALED layer, HTAP workspace/immutable result receipts/report-head CAS, cohort membership drain and DDL reaper, production load/recovery acceptance, any new ACTUAL approval.
- **Separate existing functionality:** `TrustedAutoReportService.calculateAndPublish` is an internal trusted legacy report-writing API that already persists `fin_calc.report_run/report_publication`; it has no FIN-BE-R2 method-level ExecutionPermit check. `CompositeCoreReportPublisher.publishReady` can be invoked explicitly and is distinct from the new scheduler guard. **Do not assume that R2.0.1 deny-all covers these service entrypoints.** Preserve existing R1 reporting and golden tests, but prohibit unreviewed HTAP plans from using either path without a dedicated gate and negative side-effect tests.

The **first usable vertical slice** must prove, in order: published SYNTHETIC FactSet → source/metric/period checks → explainable PLAN_ONLY → authorized bounded execution → immutable input-pinned result → non-production read-side replay. Do not call catalog enumeration, method approval or a stored JSON hash a calculation.

## 2. Code-backed findings and gate-relative disposition

**Severity convention:** P0/NEXT is a blocker to the next stated stage; P0/RELEASE is a prerequisite for releasing HTAP, **not evidence of a live exploit in a disabled system**.

| ID | Severity/gate | Concrete evidence / failure mode | Mandatory closure |
|---|---|---|---|
| HTAP-HR-01 | **P0/NEXT W0** | `MethodologyCatalogReadService` returned immutable seed `method_family.implementation_status` while SQL view uses latest `method_implementation_evidence`. | Use SQL view as the status authority in the same read transaction and PostgreSQL test with new evidence; do not promote to S2 merely from status. **Code patch `6b543f2`**. |
| HTAP-HR-02 | **P0/NEXT R2.B** | `MethodConfigVersionEntity.getParameters()` used shallow `Map.copyOf`: nested lists/maps remained mutable and JSON null was not supported. | Recursive immutable defensive copy with accepted JSON scalars/null, reject foreign mutable objects; pure negative tests. **Code patch `6b543f2`**. |
| HTAP-HR-03 | **P0/NEXT R2.2** | `V20261117_02` audits `method_active_config` on INSERT/UPDATE, but previously allowed DELETE without activation audit. | Forward-only DB deny-delete migration and PostgreSQL negative proof; define future audited CAS disable/revoke event before providing operational deactivation. **Fail-closed migration V20261117_08**; no historical mutation. |
| HTAP-HR-04 | **P0/NEXT R2.2** | `method_config_review` and `method_s2_review` accept `submitted_by/reviewed_by` strings supplied with inserts; current DB checks only equality/immutability. No proven trusted authenticated moderator, DB write grants or revocation race policy. | Server-derived principal + role/scope authorization, separate author/reviewer, append-only actor/evidence, DB role denies and explicit deny/stale/forgery tests. Never create a public review/activation write route without this gate. |
| HTAP-HR-05 | **P0/NEXT R2.B** | `method_config_version.algorithm_sha256` is checked as 64 hex chars but the DB does not prove equality with the shipped vetted Java/SQL artifact. `MethodVariantRegistry.requireExecutablePlanBinding()` currently ALWAYS throws on unpinned implementations/missing permit. | Versioned server-owned ImplementationManifest with `methodCode+variantCode+implementationVersion+artifactSha+resultSchemaSha+allowedEngine`. Bind exact config `effective_sha256` and approved method S2 scope; unknown/mismatch => BLOCKED_IMPLEMENTATION_NOT_PINNED. Do not relax current registry guard to make a test pass. |
| HTAP-HR-06 | **P0/NEXT R2.B** | CON-02 adapter uses `ConcentrationAnalysisEngine.DEFAULT_ABC_POLICY`, not approved `method_active_config`/V07 values; it computes a fresh Pareto instead of consuming a versioned immutable CON-01 receipt. | R2.B must pin parent result receipt ID/source SHA/version/hash, active config ID + CAS pointer version, contract SHA, exact algorithm artifact, trusted source and result policy. Old adapter is golden only; no silent runtime injection. |
| HTAP-HR-07 | **P0/NEXT R2.2** | V02 checks parent config version only `prior.version_no < NEW.version_no`; it does not require immediate predecessor or define legitimate historical branching. `method_config_review.config_id UNIQUE` permits one terminal moderation decision per config, without a specified re-review/revocation lifecycle. | Decide strict linear chain vs explicit branch/parent identity **before** tightening SQL. Define rejection/re-submission, revocation and reactivation semantics with monotonic pointer, immutable review decisions, race tests and populated-upgrade strategy. Do not guess. |
| HTAP-HR-08 | **P0/NEXT W1.2.A** | `HtapCalculationPeriodPolicy` admits only `MONTH/PERIOD_ONLY/INTERVAL` *candidate*. Source types WEEK, HALF_YEAR, AS_OF and YTD/LTD exist. No qualified fiscal calendar/closing-stock adapter or runtime integration exists. | Total type+coverage+timeKind/calendar-version matrix; exact month ordinal/boundary, stock close and ISO week/year tests. Unsupported remains BLOCKED; candidate ≠ FACTSET authority. |
| HTAP-HR-09 | **P0/NEXT W1.2.B** | `CatalogXlsxRevisionService.confirm` writes `form_change_journal`; separate `FormDraftRevisionV5Service.confirm` does not. Journal is draft-only and lacks durable delivery/reverse dependency impact. | Typed origin-specific source-event contract; distinct F02/V5 event schema if opted in; `FormImpactPreview` with tenant/project/slot/revision checks, 166 classification, idempotent 2-node delivery; zero trusted result side effects. |
| HTAP-HR-10 | **P0/NEXT W1.2.A** | Structural XLSX schema fingerprint is checked, but no approved global catalog epoch/`mapping_sha256` and 2-deployment compatibility handshake are wired. | Freeze staged mapping/definition activation epoch, hash domains, compatibility policy and same-cells/different-mapping negative proof; do not replace V1 hashes. |
| HTAP-HR-11 | **P0/NEXT W1.3** | Current FIN-01 and R2.A are intentionally strict on conflicting SourceRevisions; `TrustedComparisonSet` and financial PeriodCalendar remain proposed. | Exact single-published-FactSet PLAN_ONLY first; no multi-FactSet inference. Composite history is a **separate** sealed selector/version, conflict/overlap/period completeness negative tests and method S2. |
| HTAP-HR-12 | **P0/NEXT W2** | `SingleExecutorScheduler` has production DenyAll, but `TrustedAutoReportService` and `CompositeCoreReportPublisher` have independent report/snapshot-write methods. The future `fin.execution.mode` is not implemented as a unified authorized task dispatcher. | Explicit write-path inventory; never invoke legacy write APIs from unpermitted plan; gate each new HTAP mutation on source + method-S2 + config/permit + live fence; preserve historically authorized reports. Test blocked ACTUAL creates **zero new** analysis tasks/receipts/report/CORE rows. |
| HTAP-HR-13 | **P0/NEXT W2** | No DB-backed global+tenant PREPARE/SQL/JAVA/CLEANUP capacity, query cancellation confirmation, capacity lease/fence or OLTP reserve. | Two independent JVMs with oversubscription, lost lease still-running SQL, cancel/recovery and starved tenant proofs; operational admission before costly stage work. |
| HTAP-HR-14 | **P0/NEXT W4.1** | No new exact HTAP ResultKey/immutable receipt/report-set manifest+CAS. R2.B PlanCommit must be earlier than W2; W3 SQL pilot remains read-only SYNTHETIC shadow. | Source+contract+effectiveConfig+algorithm+precision+calendar+input receipt pins; immutable result + equal-payload dedupe / unequal-payload collision reject; required-set atomic head CAS and old N-reader. |
| HTAP-HR-15 | **P0/RELEASE W4.2** | `WorkspacePartitionMaintenanceUtility` only inspects `pg_catalog`; its naming check cannot prove safe DROP of a shared tenant cohort. | Physical cohort membership/lease/reader/legal hold ledger and privileged operator; `DETACH CONCURRENTLY` on autocommit connection, FINALIZE recovery, no DEFAULT/CASCADE/worker DDL and PostgreSQL lock+WAL tests. |
| HTAP-HR-16 | **P0/RELEASE W6–W8** | No owner-approved numerical throughput/OLTP p95-p99/latency/disk/retention thresholds, loaded old DB migration evidence or independent architecture/finance S2. | Predeclare targets and fixture before heavy workspace SQL/DDL; run paired DIRECT vs optional AGG, 1/2/4/8 workers; fresh+populated PostgreSQL 16 Flyway/privilege proof, same-SHA audit and separately signed ACTUAL/release. |

### Prohibited shortcuts

- Do **not** set `fin.execution.enabled=true` or override `DenyAllLegacyExecutionPermitGuard` in production.
- Do **not** make `ConcentrationTypedParameters` into an implicit approval, or accept client-supplied artifact hashes/tenant actor/source receipts as execution authority.
- Do **not** treat `sourceParetoVersion` as a CON-02 config value; it is a trusted **upstream input receipt** field.
- Do **not** equate 60 method families or 259 unreviewed parameter codes with runnable variants.
- Do **not** introduce dynamic SQL stored in DB tables, a second scheduler, a new data warehouse service or partitions by tenant×166 forms×method×period.
- Do **not** change historic Flyway migrations or rehash old financial receipts to fit a new design.
- Do **not** assert that R1 report writers are automatically safe HTAP result promoters; add a dedicated permit-aware bridge, then tests.

## 3. Frozen predecessor model and minimal usable vertical slice

```text
CURRENT: fact publication / draft change journal / R2.A / R2.1 / legacy DENY
    |
    +--> W0: run tests and freeze trust/source/calendar/method identity
    |          + R2.2: authenticated config review, revocation, artifact binding
    |          + W1.2.A: exact source events, mapping epoch and period matrix
    |                 |
    +--------> R2.A read-only applicability / FormImpactPreview (distinct)
    |                 |
    +--------> R2.B: single published FactSet PlanPreview + PlanCommit
    |                      (NO calculation; no legacy report publication)
    |                 |
    +--------> W1.3: sealed source and calendar, then W2 permit/capacity
    |                 |
    +--------> W3: bounded SYNTHETIC SQL/Java SHADOW against golden
    |                 |
    +--------> W4.1: immutable result receipt + complete report-head CAS
    |                 +--> W4.2: cohort DDL only after separate DBA gate
    |                 |
    +--------> W5: reviewed DAG and cross-form source bindings
    |                 |
    +--------> W6: measured performance / W7: ops security / W8: S2 release
```

R2.B for **one single published FactSet** need not wait for the **multi-FactSet** extension in W1.3, provided selected source IDs/hash are fully verified and no composite history is inferred. The composite extension still blocks until its own trusted-selector qualification.

**SHADOW is strictly non-production and non-authoritative**. An independent Java/SQL experiment can compute a temporary comparison value; it cannot create a current report pointer, financial release or MethodS2 approval.

## 4. W0 completion contract (specific implementation artifacts)

| Work package | Files/modules affected | Required negative proof | Permitted state |
|---|---|---|---|
| **W0.A Catalog consistency** | `MethodologyCatalogReadService`, `MethodConfigVersionEntity`, PostgreSQL tests | latest evidence parity, nested JSON mutation forbidden, valid JSON null intact | GREEN only after same-SHA CI |
| **W0.B Active pointer audit** | new Flyway V20261117_08, `MethodologyConfigurationRegistryIntegrationTest` | DELETE active config rejected, pointer and activation rows unchanged; no historic Flyway rewritten | GREEN only after same-SHA CI |
| **R2.2.A Reviewer authority** | new authenticated server-only reviewer/activation gateway, role tests | self/forged reviewer, wrong org, stale approval/role, missing actor provenance | RED_PENDING |
| **R2.2.B Lifecycle and artifact** | implementation allowlist, config/S2 binding, revoke ledger/epoch | unsupported algorithm SHA, rejected/revoked config, alternate branch/parent, pointer race | RED_PENDING |
| **W1.2.A Period and mapping** | `ReportingPeriodV5`, `HtapCalculationPeriodPolicy`, mapping-version/epoch contract | WEEK/HALF_YEAR/YTD/LTD/AS_OF never silently act as MONTH FLOW; changed mapping with identical values not NO_OP | RED_PENDING |
| **W1.2.B Draft impact** | typed source event join, separate delivery ledger, bounded impact preview | forged tenant/slot, duplicate 2-node events, missing publication, no analysis/report writes | RED_PENDING |
| **R2.B PLAN_ONLY** | exact published snapshot planner, immutable plan ledger and deterministic encoder | repeating preview = 0 writes; repeating commit = one plan, zero compute; mismatched config/source => BLOCKED | HOLD until R2.2/source binding |

### Source and method binding identity — required for R2.B

**Source key:** `organizationId, economicEntityId, businessScopeId, reportingBasis, dataSpace, publishedFactSetRevisionId, snapshotSha256, formCode/version, metricCode/version, selectedAssertionIds, selectedSourceRevisionIds, sourceTimeKind, PeriodType, Coverage, Period/CalendarId+version, currency+unit, dimensionHash, sourceSelectionPolicy`.

**Method binding:** `methodCode, variantCode, descriptorVersion/sha256, implementationVersion/allowlistedArtifactSha256, resultSchemaSha256, parameterContractId/schemaSha256/profile, configId/effectiveSha256, pointerVersion, independentMethodS2ReviewId+semanticSha256+window+revocationEpoch, verified upstream result receipt IDs/hashes where dependent`.

**Plan identity:** versioned, length-prefixed canonical encoding of exact selected sources + method binding + profile/assumptions/precision/calendar and output schema. The plan is an *immutable explanation of decisions* with all blocked reasons. **PlanCommitted does not mean ExecPermitted**, and the permit must be rechecked on claim and before immutable result promotion, not only on Preview.

**Trust decisions not yet ratified:** reapproval vs permanent rejection of immutable config, config branch/rebase semantics, reviewer actor authority and separation of duties, calendar ownership, mapping activation epoch, performance SLO/retention numbers, DB role/grant topology. Mark these `DECISION_PENDING`; do not let defaults slip into a programmatic gate.

## 5. Mandatory hard negative/positive acceptance suites

| Test | Expected | Gate |
|---|---|---|
| HTAP-HARD-01 evidence status changes, Java and SQL view same SHA | no stale family seed status; never flips releaseQualified | W0.A |
| HTAP-HARD-02 nested JSON `Map/List` mutated and `null` | immutable snapshot, no exception for JSON null | W0.A |
| HTAP-HARD-03 `DELETE method_active_config` | PostgreSQL REJECT, pointer and audit unchanged | W0.B |
| HTAP-HARD-04 forged reviewer, self-approval, revoked identity | no effective APPROVED/ACTIVE permit | R2.2.A |
| HTAP-HARD-05 config re-review/rollback/branch version, CAS concurrent writes | one authoritative chain/epoch, immutable provenance | R2.2.B |
| HTAP-HARD-06 config SHA matches but artifact SHA untrusted | method not executable, decision reason recorded | R2.B |
| HTAP-HARD-07 CON-02 config A/B 80/95 → 75/90 | different effective SHA and outputs; old receipt remains pinned | R2.B/W4 |
| HTAP-HARD-08 missing/mismatched CON-01 result receipt | CON-02 blocked, not recomputed by a hidden default | R2.B |
| HTAP-HARD-09 all PeriodType/Coverage/TimeKind combinations | unsupported blocked; valid MONTH FLOW candidate only | W1.2.A |
| HTAP-HARD-10 same content + different approved mapping/schema epoch | not a false NO_OP or trusted reuse | W1.2.A |
| HTAP-HARD-11 F02/V5 confirmation vs XLSX vs FactSet publication | correct typed event, no forged slot FK/source approval | W1.2.A/B |
| HTAP-HARD-12 two tenants, shared business period/currency and hash | no cross-tenant impact, plan, result or read | W1.2.B–W8 |
| HTAP-HARD-13 read-only PlanPreview twice and PlanCommit twice | 0 writes on Preview; one plan and 0 executor/result rows on Commit | R2.B |
| HTAP-HARD-14 R1 report/service path invoked from blocked R2 plan | no unauthorized HTAP report/CORE/receipt; R1 trusted goldens intact | W2 |
| HTAP-HARD-15 2 JVMs, one tenant limit, lost SQL lease still running | no quota oversubscription or stale result commit | W2 |
| HTAP-HARD-16 SQL/Java published-source parity, incomplete history/mixed currency | exact values, statuses, reason codes or explicit block | W3 |
| HTAP-HARD-17 interrupted stage seal/result/head CAS | one immutable receipt, complete required-set head only | W4.1 |
| HTAP-HARD-18 active/pinned tenant in retired shared cohort, DETACH crash | no DROP; safe FINALIZE/recovery, no worker DDL | W4.2 |
| HTAP-HARD-19 populated schema upgrade, PostgreSQL 16 grants/denies | source+report+config/history intact; denied privileged writes | W0/W7 |
| HTAP-HARD-20 direct vs aggregated net performance and OLTP p95/p99 | measured against owner-approved thresholds, never fabricated | W6 |
| HTAP-HARD-21 full same-SHA proofs, independent method/security/DB S2 | separate `ENGINEERING_ACCEPTED`, `ACTUAL_PERMITTED`, `RELEASE_APPROVED` | W8 |

**Evidence rule:** each test is `PLANNED` until code/fixture, observed output and exact SHA+workflow are linked. A green CI does not imply a test that has not been authored; the first three should be checked independently after code integration. Never record S2 APPROVED without actual external reviewer authority.

## 6. Readiness decisions

| Milestone | Decision | Evidence/exit criterion |
|---|---|---|
| W0.A/B foundational consistency and pointer-delete fix | **IMPLEMENTED / CI TO VERIFY ON NEW HEAD** | new Java+SQL tests and both workflows on exactly same SHA |
| W0 full freeze | **CONDITIONAL GO** | actor revocation, source/period/epoch decisions and DBA/perf thresholds assigned |
| FIN-MR-R2.2 | **GO for RED and separate incremental code** | secure identity, version policy, artifact-to-method S2 exactness |
| HTAP-W1.2.A | **GO for contract, RED, then small GREEN** | calendar/source mapping proofs |
| FIN-BE-R2.B PlanCommit | **HOLD** | R2.2 + exact single-FactSet method identity accepted |
| W2 admission/W3 production execution | **HOLD** | independent permit, 2-node capacity and safe source |
| W4 trusted output/reaper | **HOLD** | immutable receipt + cohort barrier/DBA proof |
| HTAP ACTUAL and production | **HOLD** | full W8 and independently approved release |

**Stop rule:** a blocking P0 may be closed only by an explicit test, migration/implementation SHA and independent review; editing this document alone cannot close it. Full FIN HTAP is not yet authorized for automatic ACTUAL financial calculation.
