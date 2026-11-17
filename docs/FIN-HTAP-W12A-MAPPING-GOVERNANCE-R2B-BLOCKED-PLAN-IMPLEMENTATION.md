# FIN HTAP — W1.2.A Mapping Governance and FIN-BE-R2.B BLOCKED PLAN_ONLY Increment

**Date:** 2026-11-17 · **Branch:** `feat/fin-r0-r1a` only · **Status:** CODE AUTHORED / SAME-SHA CI TO VERIFY / METHOD S2 + ACTUAL HOLD.

## Delivered technical increment

**Predecessor:** `V20261117_13` candidate mappings and exact published source read model, `HtapFinancialSliceKey`, `HtapIsoFinancialCalendar` and `HtapPublishedSliceCandidateResolver`. Prior green checkpoint: `e8a4519`, FIN Backend Domain and FIN CI SUCCESS (newer commits require their own results).

**W1.2.A review and activation foundations:**
- `V20261117_14__htap_mapping_governance_and_catalog_epoch.sql`: append-only mapping review ledger, mapping scoped FK, server-side monotonic tenant `catalog_epoch`, mapping active pointer, append-only activation history, atomic PostgreSQL CAS. No existing mapping candidate row or published financial fact is modified.
- `V20261117_15__htap_authenticated_mapping_authorship.sql`: immutable authorship attestation; review rejects candidate lacking trusted-service-attested author and same actor review. Old free-text `authored_by` cannot alone authorize a review.
- `HtapMappingGovernanceService`: internal service; service inputs carry already-verifiable JWT supplied by the trusted Spring Security caller; FIN_SUBMIT/FIN_REVIEW/FIN_PUBLISH are scoped to the organization, subjects derived from signed token not a request actor field. No public HTTP route. `APPROVED` is **editorial mapping review only**, not financial method qualification or verified source-field semantics.
- `HtapMappingGovernancePostgresTest`: PostgreSQL 16 Testcontainers covering mismatched org authorization, author/reviewer separation, untattested forged candidates, rejected decisions, immutable reviews, stale/duplicate epoch CAS, journal count and zero published report runs.

**R2.B BLOCKED-ONLY first phase:**
- `V20261117_16__htap_immutable_blocked_method_plan.sql`: only immutable `BLOCKED_PLAN_ONLY` records with source/tenant FK, publication/outbox hash proof, current epoch verification, DB-calculated domain-separated SHA-256, idempotency unique by tenant/hash, and no task/result/report table changes.
- `V20261117_17__htap_blocked_plan_payload_integrity.sql`: reject missing reason arrays, wrong per-node source/registry fingerprints, unpinned slice fingerprint, any `status` other than BLOCKED or `executionPermitted=true`. Restrict PUBLIC grants on sensitive ledgers; deployment must assign proper least-privileged roles.
- `HtapBlockedPlanOnlyService`: FIN_ANALYZE+FIN_READ-checked read-only preview for exactly one published FactSet, candidate slice gate, existing R2.A2 applicability evaluator and registry dependency topological ordering. `commitBlocked` **recomputes** preview inside repeatable-read transaction instead of trusting client-provided decisions/hash/epoch, then inserts immutable blocked snapshot with deterministic source+catalog+registry+exact financial slice identity. Duplicate identical requests reuse same immutable receipt. No executable adapter invocation or ExecutionPermit.
- `HtapPublishedSliceCandidateResolverPostgresTest`: verifies 7 blocked method decisions and their dependency order, equal repeated previews, idempotent same-SHA commits, immutable DB snapshot, FIN_READ/ANALYZE isolation, zero report writes.

## Critical distinctions and remaining P0

1. **Editorial approval is not verified semantic mapping:** No W1.2.A certified form field-to-metric registry, catalog-definition epoch handshake or independent reviewer proof tying signed form mappings to every assertion. `HtapPublishedSliceCandidateResolver` still returns `MAPPING_NOT_QUALIFIED` even for an activated editorial mapping; this is intentional.
2. **Calendar restriction:** only strict ISO `MONTH/PERIOD_ONLY/INTERVAL` is a candidate. Fiscal/4-4-5, monthly STOCK closing, YTD/LTD and composite cross-FactSet history need separate approved versions and negative/golden tests. Do not infer unsupported cases.
3. **R2.2 actor/privilege boundary:** application derives scoped actors from verified JWT, but production must explicitly prove least-privileged DB accounts, non-bypassable write paths, no forged direct DB actors, moderator revocation, conflict races and independent S2. Do not present the internal service as an independently reviewed S2-approved IAM subsystem.
4. **R2.B runnable plan:** no pinned ImplementationManifest/exact shipped algorithm SHA, effective config SHA+S2/active pointer lineage or qualified source series, hence **no approved runnable PlanCommit**. This increment persists only blocked evidence. A future runnable plan needs its own signed, immutable contract and P0 negatives, not a status switch on the blocked snapshot.
5. **Execution:** `fin.execution.enabled` production legacy guard stays DenyAll; W2 scheduler/permit/worker lease/cancel and W3 compute remain HOLD. No ACTUAL, task, CORE/report publication, SQL batch or physical partition DDL is enabled by this change.
6. **CI and database:** Testcontainers fresh+legacy populated upgrade, same-SHA full FIN CI, DB role grants and an independent S2 review remain gates. P0 may not be marked fully CLOSED until these proofs exist.

## Acceptance checklist

| Gate | Expected invariant |
|---|---|
| W12A-AUTH-01 | reviewer `subject` differs from attested author and has org FIN_REVIEW |
| W12A-AUTH-02 | no forged `authored_by` without attested service identity |
| W12A-MAP-01 | DB-calculated mapping SHA immutable and no optimistic overwrite |
| W12A-EPOCH-01 | initial epoch 0→1, subsequent 1→2 with journal and stale CAS denied |
| W12A-EPOCH-02 | rejected mapping cannot activate; no direct execution authority |
| R2B-PLAN-01 | one exact published FactSet, source/tenant/hash/epoch frozen |
| R2B-PLAN-02 | R2.A2 decisions include reasons, dependency precedes dependent |
| R2B-PLAN-03 | duplicate identical blocked commit returns same immutable receipt |
| R2B-PLAN-04 | forged executable, missing reasons, foreign source, stale hash rejected |
| R2B-PLAN-05 | no `analysis_task`, CORE/report, execution permit or method invocation |
| FIN-RELEASE | requires independent finance+security S2 and explicit ACTUAL switch; remains HOLD |

**Next engineering order:** same-SHA RED/GREEN CI and fix; review DB least-privilege and populated migration; implement approved field mapping + `catalog_epoch` compatibility handshake; independently qualify financial calendar and selected source history; pin shipped algorithm/contract/effective config to approved Method S2; only then permit runnable R2.B PLAN_ONLY, followed by W2/W3.
