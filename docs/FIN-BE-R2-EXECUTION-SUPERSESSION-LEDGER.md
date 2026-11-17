<!-- FIN-HTAP-CONSOLIDATED-ENTRYPOINT-V1 -->
> **FIN HTAP consolidation (2026-11-17):** [FIN-HTAP-CONSOLIDATED-TZ.md](FIN-HTAP-CONSOLIDATED-TZ.md) is now the **single normative implementation specification** for tenant/form/PeriodType/Period/Currency financial slices, calculations, aggregation, workload admission, workspace physical partitions, generation switching and DETACH/DROP. [Technical audit](FIN-HTAP-CONSOLIDATION-TECHNICAL-AUDIT-2026-11-17.md) records identified P0/P1 design defects and test gates. Sections below are **historical rationale and preserved acceptance details**; conflicting execution/physical-design prescriptions are superseded. Stricter immutable-source, authorization, independent method-S2, ACTUAL HOLD and RELEASE_HOLD safeguards are **retained**. This header does **not** assert implementation, CI GREEN or financial qualification.

# FIN-BE-R2 — Supersession Ledger: Legacy Execution Tasks -> Tenant-Aware Workspace

**Decision date:** 2026-11-17. **Status:** ACCEPTED DOCUMENT GOVERNANCE / CODE NOT MIGRATED.
**Branch:** `feat/fin-r0-r1a`. **Normative:** [ADR-R2-06](ADR-R2-06-TENANT-AWARE-CALCULATION-WORKSPACE.md) + [FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ](FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ.md).

## How to interpret historical documentation

Legacy R2 documents are **preserved as immutable engineering history**. Requirements are **not deleted, commented out wholesale or marked completed**. Their execution-specific strategies can be superseded by new W-stage implementations; their safety, source trust, methodological qualification, backward compatibility and immutable financial history requirements **remain normative**. This document resolves overlaps by requirement class. Any genuine conflict requires a recorded design decision and negative test. Do not edit successful historical Flyway migrations or weaken old tests.

There are **three different meanings of M1/M2/M3** in earlier documents (R2 acceptance milestones and ADR-R2-05 delivery modules). The new workspace implementation stages are named **W0–W8** and do not redefine those acceptance gates.

## Traceable mapping (replace mechanisms, retain gates)

| Historic document / section | Legacy task or concept | Decision | New authority / migration |
|---|---|---|---|
| `FIN-BE-R2-METHODOLOGY-ORCHESTRATION-TZ.md` §5 R2.A | versioned descriptors, applicability, ExecutionPermit | **RETAIN** | R2.A remains the server-owned trust/permit gate; W0/W3/W4 extend binding only |
| Same §6 R2.B | deterministic PlanPreview/PlanCommit, DAG, policy fingerprint | **RETAIN, REFRAME** | W4 versioned plan and W5 DAG, W2 fair admission. Preview remains read-only; commit does not execute |
| Same §7 R2.C | one SingleExecutor, lease/fencing/retry and CORE | **SUPERSEDE EXECUTION MECHANISM ONLY** | W2 tenant-aware bounded pool and W4 heartbeat/fencing/result receipts; existing SingleExecutor remains underlying singleton orchestrator |
| Same §8 R2.D | first qualified finance methods/golden | **RETAIN** | W3 FIN-01 SQL/Java parity; W5 FIN-02/CON-01/CON-02 and independently reviewed Java variants |
| Same §9 R2.E | HTTP security, read-only API, audit | **RETAIN & EXTEND** | W7 tenant isolation and quota governance; no arbitrary SQL or execution grants in API |
| Same §10 DB migration policy | preserve historical Flyway, new forward-only schemas | **RETAIN** | W1 schema, W4 receipt and plan migration; fresh-install/upgrade tests |
| Same §11–13 acceptance/CI/S2 | 36 scenarios, independent S2, release HOLD | **RETAIN** | W8 integration and same-SHA CI; ACTUAL permit separately held |
| `FIN-BE-R2-AB-CD-EF-DETAILED-IMPLEMENTATION-TZ.md` §3/§4 | registry and preview | **RETAIN** | W0/W4; do not let workspace create implicit plan |
| Same §5 | 60s fixed lease, existing global single-task claim, operational JSON result | **SUPERSEDED TARGET** | W2 bounded workers/fair quotas; W4 configurable deadline, heartbeat, fenced immutable receipt |
| Same §6 | one controlled synthetic method | **RETAIN AS GATE, CHANGE DELIVERY** | W3 FIN-01 SQL pilot synthetic; W4/W5 gated actual execution integration |
| Same §7/§8 | API/security/tests/gates | **RETAIN** | W7/W8 plus new tenant/pressure tests |
| Same §9 legacy incremental implementation schedule | A+B -> C+D -> E+F monolithic execution order | **SUPERSEDED DELIVERY ORDER** | W0–W8; preserve historical milestone acceptance M1/M2/M3 |
| `FIN-DIAG-60-AUTO-ORCHESTRATION-TZ.md` §2–5 | standalone auto-run style scheduling / simple per-method tasks | **SUPERSEDE TOPOLOGY** | W1 sealed workspace + W2 tenant capacity + W4/W5 single orchestrator |
| Same §6–10 | composite report, goldens, optional ML OFF, test cases | **RETAIN** | W5/W8; no automatic financial or ML qualification |
| `FIN-DIAG-60-IMPLEMENTATION-TZ.md` | 60 method catalogue / method definitions | **RETAIN CATALOGUE, SUPERSEDE UNIVERSAL EXECUTION ASSUMPTIONS** | W3/W5 per-variant MethodModule and explicit input contracts, never autoenable 60 |
| `FIN-EXECUTION-CONTRACT-V1.md` | source/provenance, result/CORE/readiness, safety constraints | **RETAIN** | canonical source/receipt semantics; plan/schema changes must be versioned |
| `ADR-R2-05-METHOD-MODULE-DUAL-ENGINE-IMMUTABLE-RESULTS.md` §2–4/7 | module-per-variant, SQL/JAVA, immutable result, per-method goldens | **RETAIN** | W3/W4/W5 |
| Same §5–6 | implementation path and M0–M6 delivery order | **SUPERSEDED DELIVERY ORDER ONLY** | W0–W8; no second scheduler/outbox and no ACTUAL permit |
| Existing `SingleExecutorScheduler.java`, `analysis_run/task` and original outbox | runtime legacy operational code | **KEEP DURING MIGRATION** | feature-flagged, additive replacement inside same execution service; do not annotate `@Deprecated` until all callers migrated and historical data compatible |

## v1.1 cross-cutting change control (P0 mandatory)

The [2026-11-17 technical review](FIN-BE-R2-W-TECHNICAL-REVIEW-2026-11-17.md) establishes stricter rules than the early W0–W8 proposal. **These are new normative requirements, not deprecated historical safety gates:**

| Previously underspecified | New controlling design requirement | Implementation gate |
|---|---|---|
| `fin.workspace.enabled` + legacy polling | One effective `fin.execution.mode` with fail-fast conflicting flags, no dual execution/CORE publication | W0-MODE |
| “Per-tenant quotas” | Atomic global+tenant+engine stage capacity leases in the same short transaction as fenced task claim | W2-ADMIT |
| “Workers use resources” | PREPARE/SQL/JAVA/CLEANUP all have budgets and cluster-wide connection reserves | W2-STAGE/BULK |
| “Lease expires” | Cancel/confirm physical worker completion before releasing capacity, otherwise quarantine | W4-FENCE |
| “Reusable workspace” | Scoped immutable source+population+policy identity, generation, CAS seal and read-time ABAC | W1-SEAL/ISOL |
| “PostgreSQL isolation” | Scoped composite FK, non-owner/non-BYPASSRLS runtime roles, RLS/pool reset tests where used | W7-SEC |
| “HASH partition defaults” | Unpartitioned baseline and fixed HASH comparison before physical freeze | W1-PART/W6-TUNE |
| “Performance tests” | Zero-violation correctness gates; pre-registered machine-specific OLTP and worker SLOs | W6-SLO |

**Application code is not yet deprecated.** Changes in future commits must preserve legacy tests and report artifacts, add explicit migration/rollback proof and keep the execution mode OFF until W0/W1 gates. The new v1.1 technical review is an internal analysis, **not** independent S2 approval.

## Rules for future code / specification changes

1. **Do not mark all preceding R2 requirements deprecated:** this would accidentally remove critical tenant, source, permit, risk, and CORE protection. Mark only narrow outdated *implementation mechanisms and delivery sequencing* as SUPERSEDED.
2. This ledger has precedence for **execution architecture and task scheduling topology**. For financial validity, source authority, method S2, security, existing PostgreSQL CHECKs and release gates, **the stricter legacy safety contract prevails** unless a new independently reviewed migration explicitly tightens it.
3. Existing tests represent historical behavior. Do not delete/disable them to achieve green. Any intended behavior change requires a new version of contract + tests proving compatibility and safe migration.
4. Preserve R2 acceptance milestone M1 (plan-only), M2 (controlled synthetic) and M3 (read/S2); use W0–W8 exclusively for new implementation work.
5. No claim that 60 methods are executable, no automatic mass migration to SQL, no enablement of ACTUAL due to workspace.
6. Feature flag `fin.workspace.enabled=false` gates new code; old module bridges remain available; existing `fin.execution.enabled=false` continues to protect automatic legacy polling. Demonstrate safe disable/rollback without deleting persisted results.
7. If concurrent development changes HEAD, re-audit before editing and do not overwrite others' changes.

**Acceptance:** all future Codex prompts/tasks and issue descriptions for R2 execution must reference ADR-R2-06, the workspace TZ and this ledger. Existing unrelated R1-X6 HTTP import/166 forms, locale exports and reports are not deprecated.
