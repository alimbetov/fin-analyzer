# FIN DATA-R0.1 — Fact Resolver by verified user_id + project_id

Дата: 2026-11-20. Ветка: feat/fin-form-library-standalone.

## Goal

FIN alone owns financial data; while FEngine evolves separately, FIN can retrieve and verify persisted fin_fact facts for a calculation request using exactly user_id and project_id.

## Authorization and isolation

- The user_id query parameter is not authorization. It must equal the UUID subject in a signature-verified OAuth2 JWT. The access token must have issuer, time, audience fin-fact-calculation-data/v1 and Keycloak realm role FIN_FACT_READ.
- Project scope is checked against fact_set and factset_qualification_receipt with an exact matching initiated_by_user_id. This grants access ONLY to factsets the authenticated user originally qualified, not arbitrary team or shared-project facts.
- No trusted project membership/ABAC registry exists yet in this standalone module. Do NOT generalize initiated_by_user_id as the project's owner/ACL.
- A dedicated Spring Security chain under /internal/fin-fact/data/** takes precedence over the legacy permissive editor chain. Default is denyAll.
- Only active, unrevoked, TEST_QUALIFIED F02_TEST_ONLY synthetic FactSets are currently supported. Editorial fin_intake drafts do not become facts automatically.
- Replaced and revoked FactSets are excluded. No fallback to latest arbitrary source/form/period.

## API

GET /internal/fin-fact/data/resolve?user_id={UUID}&project_id={UUID}&page_size=25

Optional exact filter: fact_set_id={UUID}.
Optional cursor: page_token={opaque} (cannot be combined with fact_set_id).
Page size: 1–100 (default 25).

HTTP Authorization: Bearer <signed access token>.

Backend opt-in:
- fin.fact.data-reader.enabled=true
- fin.fact.data-reader.issuer=https://trusted-issuer
- fin.fact.data-reader.jwk-set-uri=https://trusted-jwks
- fin.fact.data-reader.audience=fin-fact-calculation-data/v1

Enabling without HTTPS issuer/JWKS or without the exact audience fails at startup.

## Result contract

The JSON Page includes userId, projectId, factSets[], nextPageToken, status TEST_QUALIFIED_NOT_FINANCIAL_S2, financiallyApproved=false, productionAdmitted=false.

Each FactSet contains exact sourceRevisionId, organizationId, factSetId, source and FactSet SHA256, scope, period, currency, qualification epoch and typed observations. Each observation contains metric code, source row/field/revision, decimal numericValue as STRING (never IEEE-754 float), unit, interval, dimension, value SHA, key SHA.

The reader reconstructs the exact F02 candidate from INGEST-2 source data; verifies factset header + immutable qualification receipt + active control, complete source-revision pin, source SHA, mapping SHA and every physical PostgreSQL observation against independently reconstructed hashes. Inconsistent counts or values FAIL CLOSED.

**Verified 2026-11-20:** [9/9 real PostgreSQL 16 PASS](https://github.com/alimbetov/fin-analyzer/actions/runs/44747141500), no errors/failures/skipped. [Java 21 H2 regression BUILD SUCCESS](https://github.com/alimbetov/fin-analyzer/actions/runs/44747141435) (1211 tests discovered, 290 PG-only skips expected in H2). Acceptance covers source reconstruction, exact user/project, JWT role/audience, revocation, keyset, forged observation and strict immutable read. This is bounded TEST_ONLY UAT, not production/S2.

## Real PG16 acceptance

The dedicated FinFactCalculationDataReaderPostgresqlTest covers:
1. Exact active user-project three typed F02 financial observations, source SHA and HTTP.
2. Missing JWT, wrong role, wrong audience.
3. user_id impersonation rejection, isolation from other user facts.
4. Other project or exact unauthorized FactSet invisible.
5. Editorial draft not promoted to FactSet.
6. Replacement/revocation hides old or invalid FactSets.
7. Keyset pagination across independent scopes, bounded page sizes/cursors.
8. Immutable DB rows and detection of injected/spoofed extra observations.
9. Exact-ID + cursor conflict rejection.

Tests use a Spring Security mock authenticated JwtAuthenticationToken for endpoint checks; separate real Keycloak/JWKS signature and rotation E2E is still required.

## Remaining dependencies

- FIN project membership + tenant roles must be established from trusted IAM, not from a free-form user_id or from initiated_by_user_id alone.
- F02 is the current qualified TEST_ONLY pilot. The other 165 form types (and broader metric families) require explicit source-to-fact contracts, negative tests and financial qualification before advertising complete coverage.
- Once FEngine publishes Model Requirements, FIN will resolve only required metric codes, dimensions and periods, record missing data explicitly and export version-pinned typed bundles. FEngine never connects directly to FIN PostgreSQL.
- Synthetic TEST_QUALIFIED is NOT independent S2 or production admission.
- Re-check revocation and qualification epoch at actual computation admission (snapshot retrieval is not a forever-valid authorization).
