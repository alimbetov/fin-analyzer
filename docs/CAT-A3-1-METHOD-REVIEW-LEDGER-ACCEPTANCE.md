# FIN CAT-A3.1 — append-only methodology review and trusted-fact admission

**Status:** CODE ADDED; **CI NOT CONFIRMED**; **NO REAL S2 APPROVALS**; RELEASE HOLD.
Branch `feat/fin-r0-r1a`, PR #2 Draft; `main` unchanged.

## Implemented in this increment

1. `V20261116_08__cat_a31_method_reviews.sql`: immutable S2 review decisions with method/variant/schema version, reporting basis, source-semantic/golden/evidence SHA-256, submitter/reviewer separation, validity interval and unique version-level approval. Separate append-only revocation event table; rejection and revocation are never implemented as UPDATE/DELETE. Zero approvals seeded.
2. `CatA31MethodReviewRegistry`: backend-only read-only lookup; returns NOT_REVIEWED, REJECTED, APPROVED, REVOKED, NOT_EFFECTIVE, AMBIGUOUS. Conflicting decisions fail closed. APPROVED is **not** an authorization to run a method.
3. `CatA31QualificationInspector`: composes published FactSet scope/hash verification, S2 review lookup, and `TrustedFinancialPopulationService.requireReviewedActual`. Only server-derived complete required groups may be used; the current inspector itself does not accept a web request or return an execution permit. Even fully reviewed inputs yield `REVIEWED_INPUTS_RELEASE_HOLD`.
4. `CatA31MethodReviewRegistryIntegrationTest`: real PostgreSQL Testcontainers checks append-only DB triggers, expiration, revocation, conflicting decisions and self-review.
5. `CatA31QualificationInspectorTest`: unit tests fail closed on missing group, synthetic data, invalid publication, revoked review, incomplete trusted population and still-HOLD reviewed facts.

## Remaining essential work (not implied by adding tables/tests)

- **Independent authority:** The stored `submitted_by`/`reviewed_by` identities and SHA values are structurally constrained, but there is currently NO IAM-protected signed S2 submission/verification workflow. Database writers could forge them; do not claim independent accounting approval until source identity/role attestation, evidence storage, privileges and audit are implemented.
- **Meaningful versioning:** Bind approved method review to exact form-field semantics, signed required metric groups, calendar, FX, sign, amount scale, treatment of null/reversal, rounding and source metadata. The current method/version/form tuple is necessary, not sufficient.
- **Actual FactGate:** `TrustedFinancialPopulationService.requireReviewedActual` checks reviewed source rows and totals for provided groups. They must be **derived by a server-owned qualified method definition**; never let a user choose a subset to pass an incomplete source as complete.
- **Concurrent safety:** Recheck revocation and validity *at execution time* and serialize approval/revocation admission appropriately; a read-only result may become stale after a concurrent revocation.
- **SingleExecutor:** Do not enable any new method adapter or auto-run until S2+FactGate+IAM and concurrency tests all pass. Existing adapters are outside this increment's scope.
- **E2E:** Real authenticated Excel → React/API preview → human approval → immutable published FactSet → authorized executor → report → reload. Negative tenant, stale revision and authorization tests mandatory.

## Run and record precise evidence

```sh
git rev-parse HEAD
mvn -B -ntp -f backend/pom.xml -Dtest=CatA31MethodReviewRegistryIntegrationTest,CatA31QualificationInspectorTest,CatA3MethodFactAdmissionTest test
mvn -B -ntp -f backend/pom.xml verify
npm --prefix packages/sector-pilot test
```

PostgreSQL Testcontainers requires Docker available locally. Confirm GitHub Actions Node/Java/Postgres/Playwright conclusions and the *same* commit SHA before changing release status. Neither this document nor its test source asserts PASS.

**Release policy:** keep `METHOD_S2_HOLD` and `REVIEWED_INPUTS_RELEASE_HOLD` until independently reviewed methods, authenticated approval authority, complete fact verification and end-to-end execution evidence are established.
