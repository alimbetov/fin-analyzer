# FIN P0 — Verified Financial Source Intake and ACTUAL Provisional Graph

**Branch:** `feat/fin-r0-r1a`. **PR:** #2 Draft. **Production:** HOLD.  
**Methodological S2 qualification:** NOT APPROVED. **Real Keycloak UAT:** NOT EXECUTED.  
**Data classification:** server-reviewed financial source evidence can be stored under `ACTUAL`. Financial conclusions remain **PROVISIONAL** and feature-flagged.

## Real implemented flow

1. FIN user signs into a configured Keycloak realm through standard OAuth2 Authorization Code + PKCE (React: `oidc-client-ts`). Browser does **not** accept a pasted token or self-declared org entitlement.
2. A JWT must carry `fin_access` entries for the organization and the specific requested action, validated on the backend using issuer, audience, RS256 signature and JWKS. The issuer's authorized client scopes and its admin controls must be independently verified before any production deployment.
3. User can download a server-issued `FIN-PILOT-V1` XLSX for F01, F02, F03, DF-CSH-01 or DF-CSH-02, fill its literal source rows, and upload **that exact original**. Alternatively, enter cells manually in React.
4. Server verifies the Excel version/structure/context, rejects formulas and bad input, sets the source digest based on original bytes, and saves an immutable submission and its cells; or creates an explicitly labeled `MANUAL` evidence manifest fingerprint. Neither is preapproved.
5. A **different** Keycloak subject with `FIN_REVIEW` loads the submission and approves or rejects with reason. PostgreSQL enforces no self-review and immutable decisions. Approval creates source lineage, approved source version, immutable `FactAssertion` rows and independently sealed source population controls in the same database transaction.
6. A subject with `FIN_PUBLISH` chooses reviewed source revision IDs. Backend checks tenant, entity, scope, basis, approval, count and currency; publishes an immutable `ACTUAL FactSetRevision` and atomically updates `current_fact_set` with optimistic locking. **The candidate is a complete replacement snapshot:** for a subsequent publication, every previously published metric/period/source-row/field coordinate must still exist. Silent removal of an approved financial cell is rejected; authorized explicit withdrawal/restatement is a separate future gate.
7. **Default:** ACTUAL diagnostic endpoint is **disabled** (`FIN_ACTUAL_PREVIEW_ENABLED=false`). In controlled review environments, an authorized `FIN_ANALYZE` subject can request `ACTUAL` arithmetic preview. The engine requires exact independently reviewed source populations and source metadata for **every selected relation input**. Results are stamped `ACTUAL_PREVIEW_UNQUALIFIED_NO_CAUSAL_CLAIMS` and `ACTUAL_PROVISIONAL_METHOD_S2_HOLD`.
8. Each preview is an immutable diagnostic snapshot with source revisions, form code, row/field/fact IDs, values, missing evidence and registry hash. React shows a read-only explanation. The result is not a certified financial conclusion.

## Security prerequisites

Backend environment:

```dotenv
FIN_DB_URL=jdbc:postgresql://localhost:5432/fin
FIN_DB_USER=fin
FIN_DB_PASSWORD=<set-securely>
FIN_SECURITY_ENABLED=true
FIN_OIDC_ISSUER=https://id.example.kz/realms/fin
FIN_OIDC_JWKS_URI=https://id.example.kz/realms/fin/protocol/openid-connect/certs
FIN_OIDC_AUDIENCE=fin-analyzer-api
FIN_UI_ORIGIN=https://fin.example.kz
FIN_ACTUAL_PREVIEW_ENABLED=false
```

Frontend build-time configuration:

```dotenv
VITE_FIN_OIDC_ISSUER=https://id.example.kz/realms/fin
VITE_FIN_OIDC_CLIENT_ID=fin-analyzer-spa
VITE_FIN_API_BASE=https://api.fin.example.kz
```

These are **placeholder URLs and names**. Replace with actual provisioned Keycloak realm/client and application domains. Configure Keycloak public SPA client with PKCE S256, explicit redirect URIs and web origins, API audience and a **trusted signed claims mapper**. Do not map arbitrary user-supplied attributes into `fin_access`. Usernames/org IDs and rights must be controlled by a trusted entitlement service.

Expected JWT (illustrative decoded *claims*, not valid bearer token):

```json
{
  "iss": "https://id.example.kz/realms/fin",
  "aud": ["fin-analyzer-api"],
  "sub": "opaque-keycloak-subject-id",
  "fin_access": [
    {
      "organizationId": "11111111-1111-4111-8111-111111111111",
      "permissions": ["FIN_SUBMIT", "FIN_READ"]
    }
  ]
}
```

- `FIN_SUBMIT`: create manual/XLSX intake; download template
- `FIN_READ`: view reviewed source + fetch original XLSX + read prior assessment
- `FIN_REVIEW`: review submission; same-subject approval rejected by service **and PostgreSQL trigger**
- `FIN_PUBLISH`: create approved ACTUAL FactSetRevision
- `FIN_ANALYZE`: request independent-review-only provisional ACTUAL computation; additionally requires feature flag

**Additional required ABAC:** organization-wide rights do not yet enforce fine-grained business scope, financial zone, document, field, calendar period, approved form revision and expiry. This is a production-blocking gap; the current P0 code is **not a substitute** for complete ABAC or for an independently approved Keycloak policy.

## HTTP routes

All calls must use `Authorization: Bearer <access_token>` and replace `{org}` with an organization authorized in the signed token.

| Verb | Route after `/api/v1/financial/organizations/{org}` | Role |
|---|---|---|
| GET | `/templates/{formCode}?economicEntityId={uuid}&businessScopeId={uuid}&reportingBasis=MANAGEMENT&currencyCode=KZT&amountScale=1` | FIN_SUBMIT |
| POST | `/submissions` | FIN_SUBMIT |
| POST | `/submissions/xlsx` (multipart file + scope/context) | FIN_SUBMIT |
| GET | `/submissions/{sourceId}` | FIN_READ |
| GET | `/submissions/{sourceId}/original` | FIN_READ |
| POST | `/submissions/{sourceId}/review` | FIN_REVIEW |
| POST | `/fact-sets` | FIN_PUBLISH |
| POST | `/fact-sets/{factSetId}/assessments/{YYYY-MM}` | FIN_ANALYZE + review-mode flag |
| GET | `/assessments/{assessmentId}` | FIN_READ + review-mode flag |

Manual submission body example (illustrative only, values are NOT supplied as client-authored approvals):

```json
{
  "economicEntityId": "22222222-2222-4222-8222-222222222222",
  "businessScopeId": "33333333-3333-4333-8333-333333333333",
  "formCode": "F02",
  "reportingBasis": "MANAGEMENT",
  "currencyCode": "KZT",
  "amountScale": 1,
  "cells": [
    {"sourceRowId":"pnl","sourceField":"netRevenue","period":"2026-10","enteredAmount":"400"},
    {"sourceRowId":"pnl","sourceField":"cogs","period":"2026-10","enteredAmount":"260"},
    {"sourceRowId":"pnl","sourceField":"operatingExpenses","period":"2026-10","enteredAmount":"90"}
  ]
}
```

Review: `{"approved":true,"reason":"Reconciled to source evidence"}`. Only a separately authorized reviewer can supply a review decision. Fact-set publication: `{"economicEntityId":"...","businessScopeId":"...","reportingBasis":"MANAGEMENT","approvedSourceIds":["..."],"expectedCurrentRevisionId":null}` for first publish; subsequent publishes must supply current revision ID. Caller never supplies `approvedAt`, `dataSpace`, `reviewedBy` or `factSetRevisionId` for the new FactSet.

## Numeric and evidentiary limitations

- `FIN-PILOT-V1` is a technical pilot form schema, NOT an official audited financial reporting standard or independently qualified method set.
- Manual entry's `source_document_sha` identifies a normalized input manifest, **not an independently sourced original bank document**.
- XLSX raw bytes and hash prove preservation from upload onward; they do **not** prove that the source document was issued by a trusted external institution.
- An XLSX with formulas, unknown metadata, suspicious ZIP entries or missing required figures is rejected. Ordinary Excel numeric precision limits apply; exact high-precision decimals should be entered as literal decimal text.
- Database cross-form findings distinguish mathematical reconciliations, differences, observations, hypotheses and missing evidence. They do not assert financial causality or materiality without independently reviewed methods.
- React local-first FIN balances and sector test data remain SEPARATE from the server ACTUAL publication pipeline.

## Acceptance evidence

Mandatory real PostgreSQL tests in `FIN CI`:
- `ReviewedFinancialIntakeIntegrationTest`: two-person manual intake, ACTUAL publication, self-approval, foreign organization, incomplete F01, immutable review, optimistic locking, rejection, and prevention of silent losses in subsequent fact-set revisions.
- `PilotXlsxIntakeIntegrationTest`: raw-byte roundtrip and SHA, stored original immutability, formula tampering, wrong workbook metadata, incomplete source.
- `FinancialApiSecurityIntegrationTest`: signed-claim-shaped MockMvc JWT principal, separate permissions for submit/review/publish, cross-org denial. **Mock JWT tests do not establish real Keycloak live connectivity.**
- `FinancialApiDisabledIntegrationTest`: all API operations denied by default without OIDC configuration, including under a mock JWT.
- `FinanceAccessPolicyTest`: claims-based org/role deny tests.
- `ActualProvisionalCrossFormIntegrationTest`: ACTUAL reviewed F01/F02/F03 + cash details -> immutable published FactSet -> six numerically verified findings -> immutable provisional snapshot -> isolated tenant, correct S2 HOLD markers, incomplete source => explicit missing evidence.
- Frontend Vitest: `fin-oidc.test.ts` — no guessed org privileges from malformed JWT hints.
- Browser Playwright on Linux Chromium and macOS Chromium/WebKit: `trusted-finance-deny.spec.ts` confirms no fake approved data in default unconfigured UI.

**Record exact source SHA/run IDs only after full verify + backend-verification + desktop-macos + acceptance-gate SUCCESS.**

## Remaining P0 before production

1. Independently reviewed FIN finance S2 method definitions, reporting-basis classifications, currency/FX, VAT, source reconciliation, 3-statement integrity and accepted financial policy thresholds.
2. Full organization/field/document/period/action/expiry ABAC enforced from trusted Keycloak or authorization service + tests across real two-person Keycloak sessions; revocation and session expiry.
3. Trusted external origin verification of genuine accounting statements; fraud/alteration detection, importer certification, and reconciliation policy (manual and XLSX input remain user-supplied).
4. Real browser E2E with Keycloak and PostgreSQL for two distinct principals and complete data lifecycle; save/reopen, authorization regressions and human UAT on MacBook.
5. Scalability, PII controls, security review, CSRF/CORS policy qualification for deployment model, operational audit, observability and backup/retention.
6. Extend reviewed mappings beyond five pilot forms without inventing methodologies; qualify all economically necessary graph edges.

Until these gates close, `FIN_ACTUAL_PREVIEW_ENABLED` should stay `false` in production, PR #2 should stay Draft, `main` unchanged and release **HOLD**.
