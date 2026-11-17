# FIN R13 — Project Workspace, Immutable Dossiers and Controlled Sharing

**Status:** APPROVED FOR ARCHITECTURAL REVIEW (PROPOSED); **not implemented**. This is a design baseline, not a secured sharing service.
**Repository:** `alimbetov/fin-analyzer`, branch `feat/fin-form-library-standalone`. **No new public controllers or unsigned sharing routes in this stage.**
**Audience:** project owner / economist / accountant, lender, investor, partner, reviewer.

## 1. Architecture decision

**A project is the collaboration, scope and charging context. Form definitions stay global and versioned in `fin_lib`. Saved report instances and immutable revisions stay in `fin_intake`. External sharing grants access to an immutable, explicitly published DOSSIER REVISION, not to the mutable project and not to the organization-wide `fin_intake` namespace.**

An entity can have multiple projects. A project may assemble a subset of reports, scenario models and evidence documents. The same report revision may be linked into several project dossiers only when organization ownership/authorization and provenance permit it. Cross-organization report references are denied unless an explicit future secure copy/export workflow is qualified.

A project may publish separate lender, investor, partner, and business plan packages: each includes independently selected report/document revisions. Sharing the "project" in the UX means inviting an authenticated collaborator to the project OR sharing a published project package. These are different security operations.

### Existing verified code

- `fin_intake.lib_form_instance` already requires organizationId, economicEntityId, businessScopeId, projectId. It has no foreign key to a project master in this module. It has currentRevisionId, latestRevisionNo, workflowStatus, statusVersion.
- `fin_intake.lib_form_revision` is an immutable source revision with formVersionId and canonicalContentSha256.
- `fin_lib.lib_report_definition` / `fin_intake.lib_report_revision` are a separate multiperiod table writer and presently have organizationId but **no projectId**. Unify project binding before offering the grid-based reports through the same share package.
- Current API has local-only actor labels, no verified IAM, no authorization of organizations/projects, no payment/entitlement verification, no external share links, no stable storage/distribution of user files. Never claim production sharing is already available.
- Current `CatalogReadCache` is strictly for public reference data. Private projects, members, dossiers, share tokens, access decisions, payment states, render artifacts and historical financial facts MUST NOT be cached there.

## 2. Domain boundaries

### `fin_workspace` (or a separately deployed project service) — proposed

1. `project`: id UUID, tenant/organization ID, owner legal entity ID, project code/name, kind (`BUSINESS_PLAN|INVESTMENT|LOAN_APPLICATION|OPERATIONAL|OTHER`), description, primary locale, visibility=PRIVATE, lifecycle status (`DRAFT|ACTIVE|ARCHIVED`), rowVersion, created/updated timestamps. One project belongs to exactly one tenant. "Archived" hides it from the standard workspace; it does not delete history or revoke existing links implicitly. Existence must be tenant-scoped.
2. `project_member`: projectId, verified principalId, role (`OWNER|ADMIN|EDITOR|VIEWER`), grantedBy principalId, version, expiresAt optional. Bind user identity only from a verified bearer token; never from a request's userId or createdBy text.
3. `project_report_link`: projectId, sourceKind (`FORM_INSTANCE|REPORT_GRID`), sourceId, organizationId, displayOrder, sectionCode, linkedAt, linkedBy. Full validation of source ownership, project tenant, source current source revision; links to current working reports are PRIVATE and do not grant external access by themselves.
4. `project_document`: metadata for attachment objects (projectId, trusted uploader, media type, size, checked object URI, raw SHA256, scanStatus, currentRevisionId); metadata never implies file is safe to download.
5. `project_dossier`: projectId, recipientUseCase (`BANK_FINANCING|INVESTOR_READINESS|BUSINESS_PLAN|PARTNER_COLLABORATION|OTHER`), display name, lifecycle (`DRAFT|PUBLISHED|SUPERSEDED|REVOKED`), latestPublishedRevisionId, rowVersion. Multiple dossiers per project; no implicit cross-dossier visibility.
6. `project_dossier_revision`: immutable package revision with dossierId, revisionNo, projectId, organizationId, audience/purpose, locale, publication actor and time, content manifest canonical SHA256, immutable publishedAt, status. Revisions are append-only and cannot be edited after publish.
7. `project_dossier_item`: immutable item list with dossierRevisionId, sourceKind (`FORM_REVISION|REPORT_GRID_REVISION|DOCUMENT_REVISION|RENDERED_ARTIFACT`), exact source revision UUID, source digest, display order, title translation snapshot, sharing flags (viewAllowed/downloadAllowed), integrity metadata. Must verify source belongs to project and tenant. Distinguish source data revisions from optional rendered PDF/XLSX artifacts.
8. `project_share_grant`: grantId UUID, dossierRevisionId UUID, tenant, accessMode (`VIEW_ONLY|VIEW_AND_DOWNLOAD`), audience (`SINGLE_RECIPIENT|INVITATION_LINK`), verified recipient email/domain or recipient principal when available, tokenHash (HMAC-SHA256 or SHA-256 with secret pepper), tokenVersion, expiresAt, maxViews/maxDownloads optional, state (`ACTIVE|REVOKED|EXPIRED`), createdBy verified principal, createdAt/revokedAt, rowVersion.
9. `project_share_access_event`: append-only, grant ID, dossierRevisionId, observed time, action (`VIEW|DOWNLOAD|DENIED|REVOKED|EXPIRED`), result, request correlation ID, pseudonymized subject, IP/UA logging only to the minimum necessary by approved retention policy. Never log a raw bearer share token.
10. `project_subscription_entitlement`: organizationId/projectId, plan/feature (`CREATE_PROJECT|PUBLISH_DOSSIER|SHARE_EXTERNALLY|EXPORT_PDF|EXPORT_XLSX|ADVANCED_ANALYTICS`), source billing verification reference, validity, revocationVersion. Financial access decisions require a trusted, authoritative entitlement check (not a frontend flag).
11. `project_export_artifact`: dossierRevisionId, format (`PDF|XLSX|ZIP`), pinned render version, objectRef, raw SHA256, generation state (`QUEUED|READY|FAILED|REVOKED`), createdAt; download only through a short-lived grant after active share verification. ZIP must be bounded and safe.

**Do not** store copies of all 166 definition records per project. Store only projectId + form code/current selected formVersionId on intake creation and immutable source revision references on published packages.

### Database integrity / tenancy

- Composite tenant-scoped lookup and foreign keys where feasible: (`organization_id, project_id`), (`organization_id, dossier_id`), (`dossier_revision_id, item_source_revision_id` validated by a trusted service) and indexed lists by (`organization_id, project_id, updated_at, id`).
- Unique (`organization_id, project_code`), (`dossier_id, revision_no`), (`grant_id, token_version`); state constraints, ownership checks and optimistic versioning. Avoid a global item reference without an ownership check.
- Enforce `project_report_link.sourceKind` via explicit service-level lookup and negative PostgreSQL integration tests; polymorphic `sourceId` cannot be blindly assumed referentially safe.
- Publish transaction stores manifest, items, SHA and outbox event atomically. Render artifacts are asynchronous/idempotent and refer to a pinned dossier revision; no "latest" substitutions.
- Time and locale are presentation properties. The pinned source SHA, formVersionId, methodology/configuration versions and package digest identify actual content; don't hash display layout or language into the financial source hash. Publication manifest can separately hash localized rendering and attachment digest.

## 3. Access / authorization matrix

| Actor | Private project | Draft forms | Published dossier | View shared link | Download |
|---|---|---|---|---|---|
| OWNER | full control | edit | publish/revoke | own | entitlement-gated if required |
| ADMIN | manage permitted items | edit | delegated publish | own | entitlement-gated if required |
| EDITOR | edit assigned items | edit | no publishing by default | if explicitly allowed | only explicit right |
| VIEWER | read authorized project materials | read-only | read granted packages | if explicitly allowed | only explicit right |
| External recipient (bank/investor/partner) | **no** general access | **no** | explicit pinned dossier only | token + optional email OTP/auth | only `VIEW_AND_DOWNLOAD` and item allowed |
| Unknown anonymous user | none | none | none | none without validated grant | none |

**Project member ACL** and **dossier share capability** are separate. Project archive state must not silently invalidate active share grants; owner sees explicit warning and can revoke them. Platform financial S2 qualified status is separate from project/dossier publishing state.

### External link design

- Create a random ≥256-bit cryptographic token using a CSPRNG; show the URL once on creation. Persist only a secure hash/peppered digest (never token cleartext). Do not put bearer tokens in frontend analytics, HTTP access logs, referrer propagation, previews/metadata unfurling, or automated bot links. Set `Referrer-Policy: no-referrer`, `Cache-Control: no-store` and restrictive CSP on external view.
- Prefer **invitation to a named recipient + email verification/OTP or login** for confidential lender/investor packages. Unrestricted bearer links are a reduced-assurance option that must be explicitly selected and short-lived, with clear warning and rate limits.
- A share is to **dossierRevisionId**, not `projectId` or "latest revision". Publishing dossier revision 2 does not automatically expand the scope of an existing revision 1 link. Revocation blocks BOTH read and download on every request.
- Default `VIEW_ONLY`, expiry 7 days maximum by configurable policy; require a confirmed explicit `VIEW_AND_DOWNLOAD` grant for exports. A watermarked browser viewer, PDF disable-save hints, etc. do not prevent screenshots/recording; do not promise DRM protection.
- Display title/description in RU/KZ/EN/ZH as requested, without synthesizing untranslated financial details. A recipient can choose locale only for presentation, not choose extra materials or new source revisions.
- Chat distribution: owner copies the safe invitation URL and pastes it into their preferred chat. No chat API integration is implemented; chat message text is not authorization.
- A service should support recipient-specific/grant-scoped artifacts and enforce `Content-Disposition`, MIME type, bounded streaming, antivirus validation of attachments and malware-safe archives. Each completed download is audited.
- External share and payment state checks are done server-side on EVERY access. User-visible UI must not be considered an ACL. Never expose raw private files via permanent public object-storage URLs.

### Billing separation

Billing is charged on a verified organization/project plan (and possibly seats/features), **not** by changing the catalog's global form definition or flipping `isPaid` in browser memory. The owner pays for FIN services (premium analysis, unlimited projects, exports, advanced sharing). The recipient's read/download permission comes from the publisher's explicit share grant; it must not be inferred from payment.
- Denying an expired subscription may block creation of new premium shares, but must follow a defined policy for already published grants; no accidental revocation of legally retained data. 
- If finance report templates themselves become premium, keep template entitlements distinct from secure project sharing rights.
- Payment webhooks must be signed, replay-safe and reconciled against a trusted provider before granting entitlements. No direct "paid" mutation from the UI.

## 4. State machines and consistency

Project: `DRAFT -> ACTIVE -> ARCHIVED`; explicitly permit restore to ACTIVE, with access audit. Project archive is not deletion.
Dossier: `DRAFT -> PUBLISHED -> SUPERSEDED|REVOKED`; later re-publication creates a NEW immutable dossier revision. Publication requires selected report revisions, supported export formats, satisfied required checklist and an authenticated role. Do not equate user publication with financial S2 approval.
Share: `ACTIVE -> EXPIRED|REVOKED` (no resurrection; create a new token instead).
Form draft: existing `DRAFT -> READY_FOR_REVIEW -> DRAFT`. An unqualified draft may be shared as draft only if the publisher explicitly labels it **UNVERIFIED** and the external recipient acknowledges; regulated underwriting packages should require stronger review policy.
Revocation: perform a live indexed database/authoritative lookup on every read/download, and block on stale grant status; eventual TTL caching of grants is forbidden. Short-lived object links still permit access until they expire, so prefer authenticated streaming or extremely short expiry + revocation-aware proxy.

Concurrency:
- Package publish uses expectedProjectVersion, expectedDossierVersion, exact source revision IDs and full content hashes; stale selections fail HTTP 409.
- Creation of share grants requires idempotency key, active/current published dossier revision and ownership entitlement.
- Revoking a grant and downloading must be serialized/ordered at a defined authorization point. Downloads granted before revocation may already have begun; log and disclose that limitation.

## 5. Proposed REST contracts (NOT implemented)

Private (OIDC/JWT identity + project membership):
```http
POST   /api/v1/projects
GET    /api/v1/projects?page=0&size=24
GET    /api/v1/projects/{projectId}
PATCH  /api/v1/projects/{projectId}
POST   /api/v1/projects/{projectId}/members
DELETE /api/v1/projects/{projectId}/members/{principalId}
POST   /api/v1/projects/{projectId}/reports
GET    /api/v1/projects/{projectId}/reports?page=0&size=24
POST   /api/v1/projects/{projectId}/dossiers
POST   /api/v1/projects/{projectId}/dossiers/{dossierId}/publish
GET    /api/v1/projects/{projectId}/dossiers/{dossierId}/revisions
POST   /api/v1/projects/{projectId}/dossiers/{dossierId}/shares
GET    /api/v1/projects/{projectId}/shares?page=0&size=24
DELETE /api/v1/projects/{projectId}/shares/{grantId}
```

External audience:
```http
GET    /s/{opaqueToken}                 # browser landing; no private preload
POST   /api/v1/external-shares/resolve # token + optional recipient verification
GET    /api/v1/external-shares/session/items
GET    /api/v1/external-shares/session/items/{id}/view
POST   /api/v1/external-shares/session/items/{id}/download
```

All external routes require risk/rate limits and deny-by-default. Prefer ephemeral scoped external session stored in a secure HttpOnly cookie after token verification; never pass raw token to downstream file services. Propose separate route for `VIEW_ONLY` and `VIEW_AND_DOWNLOAD` accordingly. No public endpoint is added before IAM and attack-path tests.

Project item attach command example:
```json
{
  "sourceKind": "FORM_INSTANCE",
  "sourceId": "<instance-uuid>",
  "expectedOrganizationId": "<tenant-uuid>",
  "placement": "FINANCIAL_STATEMENTS"
}
```

Dossier publish command:
```json
{
  "expectedDossierVersion": 3,
  "purpose": "BANK_FINANCING",
  "locale": "ru",
  "items": [
    {"sourceKind": "FORM_REVISION", "sourceRevisionId": "<uuid>", "downloadAllowed": true},
    {"sourceKind": "DOCUMENT_REVISION", "sourceRevisionId": "<uuid>", "downloadAllowed": true}
  ]
}
```

Server must resolve and validate source revisions itself; caller-selected IDs are hints, not proof of ownership.

## 6. FIN Mission Control UX

A top-level **"Projects / Проекты / Жобалар / 项目"** area. Project cards with company and project status (not synthetic financial health), form count, dossier count and last update.
Project workspace tabs:
1. Overview — purpose, organization, period horizon, tasks, status.
2. Report forms — attach 166-catalog form or reuse a permitted existing report, open period selector and Excel-like input; saved values remain in `fin_intake`.
3. Business plan — sections, narratives, financial forecasts and references, with locale/version.
4. Documents — evidence and export artifacts, scanned/verified status.
5. Packages — separate lender/investor/partner dossiers and pinned versions.
6. Sharing — link mode, recipients, expiry, download, revoke, access audit.
7. Project team and Plan — authenticated ACL and entitlements, not owner-supplied strings.

A "Share project" dialog MUST ask **"Entire project member invitation"** vs **"Published read-only package link"**; use package sharing as the external default.
At share link creation show audience, exact document list, revision numbers, recipient, expiry and rights. Never let a single generic global "Public" switch expose all forms.

## 7. Phased delivery with hard release gates

- **R13.A Data/Consistency:** `fin_workspace.project` table, verified organizationId, project ownership and form-instance FK; unify or explicitly project-bind `ReportGridService`. Keep all share endpoints disabled.
- **R13.B IAM:** Keycloak/OIDC or approved trusted provider, organization/project resource authorization, deny by default, tenant-isolation PostgreSQL tests. Untrusted `createdBy` and `organizationId` from body no longer decide ownership.
- **R13.C Dossiers:** immutable item manifest, revision-pinned report + file, checksums, append-only publish/audit, source integrity and negative tests.
- **R13.D Controlled shares:** secure token generation, recipient OTP/verification for bank/investor, VIEW vs DOWNLOAD separation, expiry/revoke/strict audits/rate limits; browser and API integration tests.
- **R13.E Entitlement/Billing:** verified subscription features, payment webhook replay tests, limits; never interleave payment logic with `fin_intake` canonical hashing.
- **R13.F React:** project cards/list, report picker and period chooser, dossier builder and share permission modal with four-language labels, real browser E2E.
- **R13.G Acceptance:** PostgreSQL negative tests for IDOR/cross-tenant, guessed IDs, expired/forged/revoked share, previous revision leak, stale dossier publication, unauthorized download, paid feature bypass, concurrent revoke/download, malicious attachments and no public cache.

## 8. P0 risks

| Risk | Required control |
|---|---|
| Guessing project UUID or changing request organizationId | Authentication, tenant-scoped authorization, database constraints |
| Link exposes latest revisions or new attached reports | Bind to immutable dossierRevisionId and explicit items |
| Viewing allowed but downloading enabled by file endpoint | Separate server-side action/authorization on each file |
| Caffeine serves revoked private grant | NEVER cache share grants in catalog cache; authoritative verification |
| Forwarded raw share URL in chat | Warning, expiry, recipient verification, immediate revoke and no token logs |
| "Paid" flag unlocks protected reports | Billing entitlements independent of report ACL |
| ReportGrid lacks project ownership | R13.A data model extension before external access |
| Archive treated as access revoke | Explicitly separate lifecycle from grant revocation |

**Architecture recommendation:** ship R13.A+B+C before any R13.D externally accessible route. The current localhost-only module must NOT publish public finance links merely because a new UI button exists.
