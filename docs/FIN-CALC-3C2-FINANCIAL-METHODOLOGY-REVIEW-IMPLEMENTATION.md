# FIN CALC-3C.2 — Financial Methodology Review & Revision Hardening

Branch: `feat/fin-form-library-standalone` · **UAT / FINANCIAL S2 HOLD**

## Verified implementation boundaries

1. Baseline formula/review/model ApplicationRunner classes are scoped to their exact bootstrap-managed revision keys. Independent appended revisions must not change existing candidates or prevent startup. Acceptance: `Calc3BGovernancePostgresqlTest` (7 PostgreSQL 16 tests including extra-version replay).
2. `fin_calc.financial_control_example_revision` is immutable, pinned to a formula revision and form schema SHA-256, and cannot carry `S2_APPROVED`. Three **synthetic** F02 UAT outputs are stored; these are not independent financial control examples for production.
3. `FinancialMethodologyReviewPacketService.preview(draftId)` produces a deterministic SHA-256 over an *active* editorial draft, form/schema revision, referenced formula revisions and any attached control evidence. A superseded draft fails closed. Every packet returns `qualifiedForExecution=false`.
4. `fin_calc.calc3c2_financial_review_readiness_v1` reports all 154 data forms, current field-derived numeric classification, unqualified formula candidates, synthetic example counts and review-draft provenance. **All 154 remain HOLD**.

## Unresolved financial approvals — mandatory before execution

- **IAM**: `form-library/pom.xml` has no Spring Security or Keycloak resource-server adapter. Internal draft and packet services are **not** public moderation endpoints. The `reviewerRef` string is documentary metadata, not proof of identity. Add authenticated JWT issuer/audience verification, role/scope enforcement and segregation of authors/reviewers before exposing an endpoint.
- **Independent methodology-specific S2**: The existing FactSet S2 verifier is not a formula/mandatory-output S2 verifier. A new signed decision needs a distinct audience, issuer key, decision ID, nonce, expiry, revocation epoch, reviewer authorization, exact `formVersionId`, `sourceSchemaSha256`, `draftBindingSha256`, `formulaBindingSha256`, `controlExampleBindingSha256`, algorithm/config/artifact SHA-256 and replay protection. Neither storing a signature string nor holding an authenticated JWT alone grants financial approval.
- **Business approval**: Finance subject-matter owners must declare required and optional source/derived outputs for each form; verify that the full required set was reviewed; approve periods (`AS_OF` vs `INTERVAL`), currency/FX, dimension and grain, missing/null semantics, division-by-zero and rounding rules. Current 154 `REVIEW_REQUIRED` snapshots must not be rewritten.
- **Independent control cases**: Each approved formula needs externally established input/output expected values including null, zero, negative, currency, period boundaries, multiple dimensions, cross-form joins and precision-sensitive cases as applicable. The three F02 examples in `calc3c2-f02-uat-control-v1.tsv` are only synthetic smoke references.
- **Production gate**: Never generate executable `CalcPlan`, grant a lease, save `calc_result`, or mark a form COMPLETE because a review draft, SHA or UAT control example exists.

## Source paths

- `form-library/src/main/resources/db/changelog/040-fin-calc3c2-control-examples-postgresql.sql`
- `form-library/src/main/resources/db/changelog/041-fin-calc3c2-financial-review-readiness-view.sql`
- `form-library/src/main/java/kz/fin/formlib/bootstrap/Calc3C2ControlExampleBootstrap.java`
- `form-library/src/main/java/kz/fin/formlib/calc/hybrid/FinancialMethodologyReviewPacketService.java`
- `form-library/src/test/java/kz/fin/formlib/calc/hybrid/Calc3C2ControlExamplePostgresqlTest.java`

**Status**: version-replay hardening and UAT evidence plumbing implemented. Independent IAM/S2 financial authorization and formal approval of the 154 required-output sets **not implemented**. Production remains HOLD.
