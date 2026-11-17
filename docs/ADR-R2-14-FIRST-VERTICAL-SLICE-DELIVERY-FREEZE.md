# ADR-R2-14 — FIN HTAP: First Complete Financial Vertical Slice, delivery freeze

**Decision date:** 2026-11-17  
**Status:** **ACCEPTED DELIVERY STRATEGY / CODE ACCEPTANCE NOT IMPLIED**  
**Scope:** FIN Analyzer backend, Java 21, Spring Boot, PostgreSQL 16, Flyway, branch `feat/fin-r0-r1a` only. No React/Flutter, new branch, automatic `main` merge or ACTUAL permission.  
**Normative parents:** [FIN-HTAP-CONSOLIDATED-TZ](FIN-HTAP-CONSOLIDATED-TZ.md), [FIN-BE-R2-AB-CD-EF-DETAILED-IMPLEMENTATION-TZ](FIN-BE-R2-AB-CD-EF-DETAILED-IMPLEMENTATION-TZ.md).  
**Physical data policy:** [ADR-R2-13](ADR-R2-13-HTAP-PHYSICAL-LAYOUT-PERFORMANCE-FIRST.md) remains unchanged.  
**Purpose:** stop unbounded horizontal architecture work, ship an **end-to-end demonstrably correct synthetic FIN-01 calculation**, then make evidence-driven scaling decisions. This ADR controls **delivery sequence and scope**, not the existing non-negotiable financial, source, security, independent S2, or publication guards.

## 1. Single next product milestone: HTAP-V1

**Canonical milestone ID:** `HTAP-V1 — First Complete Synthetic Financial Vertical Slice`.

**Important naming collision:** historic **FIN-BE-R2 M1** means *R2.A+B PLAN_ONLY* and **FIN-BE-R2 M2** means *first controlled SYNTHETIC execution*. `HTAP-V1` spans these plus a narrowly scoped immutable result/report bridge. **Never relabel an incomplete R2.B plan as executed, or imply that historic M1 contains a calculation.**

**One required functional path:**

`Trusted SYNTHETIC Published FactSet → exact source/period/mapping resolution → R2.A Applicability → R2.B immutable PLAN_ONLY preview+commit → W2 bounded, synthetic-only execution permit/admission → W3 FIN-01/REV_NET_MOM SQL/Java golden parity → W4.1 immutable result + receipt/complete report-set CAS → R1-compatible read-only HTML/XLSX/PDF reporting → PostgreSQL reopen/regression`.

All steps must point to the **same source and method/config/build identities**. Do **not** repurpose the existing `BLOCKED_PLAN_ONLY` table to contain runnable entries; implement a separately reviewed qualified plan contract. No shortcut through legacy report/CORE writers or a new second scheduler.

A simple numerical golden fixture (synthetic, explicitly marked): January 2026 monthly net revenue **100.00 KZT**, February **120.00 KZT**; February MoM `(120-100)/100 × 100 = 20.00%` with a pinned scale/rounding rule. If previous month is absent, denominator is zero, values have different currencies, source revisions conflict, form/metric/basis differ or interval is YTD/AS_OF, the result must be **BLOCKED/UNQUALIFIED or explicit zero-denominator status**, never a fabricated zero or silent previous-available-month substitution. The detailed rounding/scale and numerical error policy must be pinned by a versioned FIN-01 contract **before** golden acceptance; example values are not an assertion that the method is now certified.

## 2. Scope discipline

**IN:** one entity/scope, one tenant-owned published SYNTHETIC FactSet containing precisely verified monthly `REV_NET` inputs, correct period lineage, one method variant `FIN-01/REV_NET_MOM`, deterministic planned inputs and code/parameter identity, bounded execution, immutable output, existing report read path, replay, failure recovery, auditable evidence. SYS publication/source verification is a required dependency, not a second financial methodology.

**OUT until HTAP-V1 acceptance:** adding other method families or 60-method execution; 166-form migration or universal IFRS/XBRL engine; extra frontends; ML; generalized multi-FactSet comparison unless the chosen fixture *demonstrably* requires it; production ACTUAL; distributed multi-worker optimization, aggressive partitioning, speculative universal cache, a new workflow subsystem, unmeasured indexes or broad schema rewriting.

**Allowed P0 exceptions:** only a defect demonstrably blocking this vertical, data integrity, tenant security, independent financial review, backward compatibility, or one-tenant/one-source replay can add scope. Each exception needs failing reproduction, target acceptance test, owner, and exact code path. An audit finding without a concrete impact on HTAP-V1 goes to the **post-V1 backlog**, not a new blocking epic.

## 3. Correct minimal implementation sequence (stop/go)

| Gate | Concrete deliverable | Acceptance proof; do not claim GREEN beforehand |
|---|---|---|
| **V1.0 Baseline** | current HEAD, same-SHA backend+FIN CI, one source/test fixture, baseline published snapshot; inventory existing code and tests | Java/PostgreSQL 16 reproducible baseline, no old test suppression, no main change |
| **V1.1 Financial source** | verified F02 pilot metric mapping and month-flow calendar, each source's exact `fact_origin`/source document/approved population where applicable, published same-FactSet Jan+Feb history, no claims of IFRS certification | 2-month golden + missing, duplicate, currency mismatch, AS_OF/YTD, wrong source revision, reviewer/tenant negatives |
| **V1.2 Immutable plan** | R2.A reason-coded applicability; R2.B plan preview/commit pins exact FactSet SHA, two period selections, mapping+catalog epoch, contract/effective params SHA, deployed implementation artifact+schema SHA and verified financial S2 at appropriate gate | repeated preview deterministic; stale catalog/config/algorithm/FactSet blocked; commit creates **zero** execution tasks/results/reports; atomic idempotent CAS |
| **V1.3 Bounded SYNTHETIC execution** | one scoped permit, one admitted execution route with durable lease/fence and cancellation, no ACTUAL, no write via bypass; minimal W2 rather than speculative full HA scheduler | concurrent/double-claim, restart/retry, tenant quota, stale fence and disabled-production tests, untouched legacy reports |
| **V1.4 FIN-01 result** | SQL/Java parity for January-February and negative financial cases; immutable result identity, input receipt, exact decimal/scale unit, complete report-set CAS, read-only report adapter | golden 20.00%, no duplicate, replay/reopen after restart, hash/input/code/config inspection, no orphan/partial report |
| **V1.5 Product proof** | run one supported HTTP/backend-client path from publication to persisted report in PostgreSQL16; export via existing R1 renderers; build reproducible test artifact and written acceptance | exact commit SHA, CI runs, test fixture, stdout/logs, DB rows and output files; independent S2 before ACTUAL |
| **V1.6 Measured optimization** | only after V1.5: direct vs aggregation/staging layouts, 1/2/4/8 workers and OLTP reserve under W6 test manifest | signed numerical budgets, SQL plans, p50/p95/p99, WAL/locks/disk, cold/warm; change partitioning only with repeatable material benefit |

**Boundary note:** W2/W3 synthetic engineering may be prototyped on a strictly test-only fixture while full financial release S2 is pending, but **no deployable permit, published trusted result or ACTUAL** can bypass the applicable reviewed source/config/code authorization.

## 4. Engineering rules (nonnegotiable)

1. **Functional evidence over architecture output.** "DONE" requires executed automated tests and user-observable backend result at one commit, not only DTOs, migrations, ADRs, or code that compiles.
2. **One vertical backlog.** Limit active implementation work to the nearest failing HTAP-V1 gate; finish its CI/regression before opening another. No large general-purpose framework refactor justified by hypothetical method #27.
3. **Smallest useful contract.** Extend existing FactSet, FIN-BE-R2 method registry, FIN-01 module and R1 reports; avoid duplicate engines/queues/catalogs. No runtime dynamic SQL over user-controlled objects.
4. **Security + accounting correctness veto.** Exact org/entity/scope, source revision, period/calendar, currency/unit, dataSpace/basis, metric/version, mapping and method identity; preserve immutable history; deny stale or unreviewed variants. An accounting STOP is never softened for a milestone.
5. **Do not forge approvals.** Independent S2 decision must originate with a real authorized reviewer and evidence. Self-authored test PASS, CI green and SHA of class bytes **are not** financial S2 or verified deployed implementation manifest.
6. **Only measured optimization.** ADR-R2-13 defaults stand: unchanged immutable `fin_fact`, small unpartitioned manifest, optional unpartitioned indexed `fin_agg`, bounded reclaim cohorts for large disposable stage. Benchmark first; do not add tenant×form×period×revision partitions.
7. **No stale status claims.** Separate statuses `DESIGNED`, `CODED_UNVERIFIED`, `CI_GREEN`, `FINANCIALLY_REVIEWED`, `E2E_ACCEPTED`, `PRODUCTION_RELEASED`. Never upgrade one status into another by wording.
8. **Backend-only delivery and migration discipline.** New migrations are append-only Flyway versions; verify fresh and populated PostgreSQL 16, authorization/role boundaries, compatibility with R1 readers, failure injection, no test removal. Work on one branch.

## 5. Exact acceptance matrix (minimal sufficient, not endless testing)

| Test | Fixture / required oracle |
|---|---|
| `V1-SRC-01` | published two-month FactSet with exact source and approved provenance is readable; wrong tenant/scope/form/revision/hash denied |
| `V1-TIME-01` | Jan→Feb correct leap/year-boundary handling; Jan→Mar with Feb missing rejected, YTD/LTD/AS_OF distinct |
| `V1-CUR-01` | KZT/KZT 20.00%; KZT/USD never summed or compared without FX policy |
| `V1-MAP-01` | F02/netRevenue vs approved REV_NET + qualified metric; wrong sourceField/version and stale catalog epoch fail |
| `V1-PLAN-01` | applicability DAG + reasons; immutable PLAN_ONLY commit, identical retry returns same receipt, zero execution/output writes |
| `V1-PLAN-02` | forged/unsigned algorithm artifact or changed effective config/S2 review cannot be a runnable plan |
| `V1-EXEC-01` | bounded synthetic worker only; two claims, stale fence, timeout and cancel recover without duplicate publish |
| `V1-GOLD-01` | independently checked `(120-100)/100*100=20.00%`; zero denominator, missing values and rounding cases explicit |
| `V1-RESULT-01` | immutable result/receipt, exact source/config/algorithm SHA; restart/replay returns same result |
| `V1-REPORT-01` | report values/hash compatible with existing R1 Excel/HTML/PDF; no partial report visible |
| `V1-REG-01` | same HEAD green backend + FIN CI, PostgreSQL fresh and populated migration, no legacy regression |
| `V1-LOAD-01` | baseline actual p95/p99/OLTP impact and representative source sizes recorded; W6 optimization only if beneficial |

The matrix is a **target**, not a claim that these tests currently pass. **No arbitrary percentages** of completion. An MVP with only plan/blocked receipts is not a completed vertical.

## 6. Change control: how to avoid returning to the same question

The strategy and sequence are **frozen for the HTAP-V1 release train**. ADR-R2-13 remains the physical storage default; this ADR is the delivery-priority authority.

A proposed replacement architecture must supply **all** of:
- reproducible failed invariant or proven performance bottleneck against the current route;
- quantitative impact and affected acceptance ID, with a representative fixture/query plan/financial counterexample;
- at least one simpler remediation attempt and trade-off;
- migration/rollback compatibility and a real owner/reviewer;
- independent architecture/S2 review if changing finance/security/release contracts.

Without these, keep existing design and append nonblocking proposals to a **post-V1 backlog**. Stop creating new architecture documents for differences of opinion alone. This freeze prevents churn, **not** justified fixes to a real P0.

## 7. Current evidence baseline (do not extrapolate)

At this decision, last GitHub workflow evidence observed: `FIN Backend Domain` success on `0b15308f`; later `FIN CI` on `17860d77` was still queued. Earlier `9daa0133` had both required workflows green. The project contains proposed W1.2.A mapping semantics and `BLOCKED_PLAN_ONLY` R2.B receipt but **no accepted end-to-end FIN-01 synthetic calculation/result/report through HTAP**. Always re-check actual head/workflows before reporting a new gate as closed.

**Next work item:** `V1.0 → V1.1`: run a single synthetic two-month F02/REV_NET FactSet fixture through approved provenance and mapping, document the first failing integration path, fix it with targeted PostgreSQL tests. Avoid another platform-wide architectural audit.
