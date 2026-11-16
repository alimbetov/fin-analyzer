# FIN CAT-A3.2 / CAT-A3.3 — IAM and safe execution boundary

Status: **PARTIAL IMPLEMENTATION, RELEASE HOLD**. Current working branch: `feat/fin-r0-r1a`; keep PR #2 Draft; do not merge `main`.

## Verified architecture findings

- `FinancialApiSecurity` starts deny-all unless JWT issuer/JWKS/audience are configured; /api/v1/financial/** requires a validated JWT, but `authenticated()` is not a substitute for route-specific authorization.
- `FinanceAccessPolicy` validates per-organization `fin_access` grants. Its `FIN_REVIEW` refers to reviewing data submissions, **not** independent economic-method approval.
- `CatA31MethodReviewRegistry` reads append-only approvals and revocations. Those ledger rows do not attest independently verified identities unless inserted through trusted workflow with database role restrictions and immutable evidence.
- `CatA31QualificationInspector` verifies publication, checks S2 decision and calls reviewed actual population verification; it always ends in HOLD. It currently receives required groups as a Java argument. The groups must originate from a *server-owned, S2-bound method contract*, not the caller.

## Changes in CAT-A3.2 increment

1. Corrected invalid escaped newline artifacts in `CatA31MethodReviewRegistry.java` that could prevent Java compilation.
2. Added `FinanceMethodReviewAccessPolicy`: S2 permissions SUBMIT, REVIEW, REVOKE require a dedicated signed `fin_method_access` scope with `scope=FIN_PLATFORM`; reject missing claims, ordinary `FIN_REVIEW`, wrong scope, spoofed submitter and self-review.
3. Added `FinanceMethodReviewAccessPolicyTest`: action separation, missing/incorrect tokens, self-approval and claimed-author spoofing.
4. Added `CatA32AuthorizedInspectionService`: requires `FIN_ANALYZE` for the revision's organization before delegating to the hold-only inspector. It is **not** an HTTP endpoint or a gate that can approve a financial method.
5. Added `CatA32AuthorizedInspectionServiceTest`: cross-tenant and read-only permission denial, one authorized inspection call, no trusted execution permit.

## P0 blockers before enabling a CAT-A3.3 adapter

- Keycloak issuer/JWKS/audience acceptance and real token-protocol mapper security for `fin_method_access` (server-issued, audience-bound; ensure no self-service role assignment). Unit-test JWT objects do **not** verify signatures.
- Dedicated IAM-backed review submission/revocation service: actor from authenticated principal; immutable source/golden evidence stored and checked by hash; no request-controlled submitted_by/reviewed_by; separation of roles + database INSERT privilege restriction and audit.
- Method version contract must bind **complete required metric groups**, canonical field IDs, source schema/period/currency basis, rounding and golden reference SHA. Review current `TrustedFinancialPopulationService` population rows for actual data; prevent partial subsets.
- Transactional race resistance: revalidate S2 revocation at execute time, not just planning time; test simultaneous revocation, expiry, stale revision and retry.
- Existing `SingleExecutorScheduler` + FIN pilot adapters have different eligibility semantics and may already run when enabled. A new IAM gate should not be assumed to cover them without explicit adapter wiring and regression.
- PostgreSQL Flyway V20261116_08 + Testcontainers and REST security E2E need to pass on same SHA, followed by actual React + authenticated two-user E2E. Do not infer PASS from test sources.
- A human S2 financial expert must independently approve each selected *method variant*. No approval is seeded by migration or created automatically by CI.

## Next implementation order

A. Real Keycloak mapping + authorization integration tests against the designated protected API endpoints.
B. Server-owned signed method requirements and immutable reviewer decision workflow. Lock down database grants.
C. Compose MethodGate/FactGate with source population and recheck immediately before a method executes.
D. Implement **one** narrowly scoped method adapter (F01 or F02 or F03), default disabled, with an independent signed S2 record; then Testcontainers.
E. One exact SHA GREEN: Java/Postgres, Node, Playwright (including Mac desktop browsers), authenticated React browser journey and provenance/report replay.

No new autoEligible method, no manually inserted placeholder approvals, and no production release until these P0 gates pass.
