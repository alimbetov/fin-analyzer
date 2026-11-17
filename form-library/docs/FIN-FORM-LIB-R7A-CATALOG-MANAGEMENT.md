# FIN-FORM-LIB-R7.A — Catalog Management: immutable presentation revisions

**Target:** `form-library/`, branch `feat/fin-form-library-standalone`.
Java 21, Spring Boot, JPA, Liquibase, H2. Backend only, no React/Spring Security.
This is a **local development prototype**; bind only to 127.0.0.1 and never expose catalog-admin routes to the Internet.

## Audit findings (baseline)

`GET /api/v1/catalog/forms` and similar read endpoints existed, but no editable catalog REST lifecycle.
Existing form versions are referenced by form revisions, and source metric identity depends on form-code + field-code. Therefore changing the current catalog in-place would make historical reports ambiguous or break report-grid metric resolution.

## Release R7.A: safe presentation edits

Use an existing form code. Open an edit session by cloning the complete active form version:
- `lib_form_version`, `lib_form_section`, `lib_form_field`;
- all four form translations and field/section translations;
- any form validation rules and metric mapping candidates, unchanged.

Edit only translations, `section_order` and `display_order` within the cloned version. No renaming/retyping/replacing fields; no schema, mapping or financial formula changes. Existing `field_code` remains immutable. **No field-code generator.** A new catalog version is activated by moving `lib_form_definition.current_version_id`. It is still `CANDIDATE_UNQUALIFIED` — not a financially approved form, methodology or FactSet.

`lib_form_edit_session` tracks base/draft identities, status `DRAFT/PUBLISHED/DISCARDED`, edit revision and timestamps. `lib_form_edit_event` stores append-only action metadata and a presentation hash. The actor supplied in the request is **untrusted**, since Spring Security is intentionally absent. Event history is for local debugging, not a certified human approval record.

## API (separate from public read-only catalog)

| Method | Endpoint | Operation |
|---|---|---|
| POST | `/api/v1/catalog-admin/forms/{formCode}/drafts` | Clone a version. Body: `{expectedActiveVersionId, actor}` |
| GET | `/api/v1/catalog-admin/drafts/{id}` | See draft sections/fields/translations |
| PUT | `/api/v1/catalog-admin/drafts/{id}/translations/{locale}` | Edit form, section or field translations. Body: `{expectedRevision, subjectType:FORM|SECTION|FIELD, subjectCode?, title, description, actor}` |
| PUT | `/api/v1/catalog-admin/drafts/{id}/fields/order` | Replace all field positions within a section. Body: `{expectedRevision, sectionCode, positions:[{fieldCode,displayOrder}], actor}` |
| PUT | `/api/v1/catalog-admin/drafts/{id}/sections/order` | Replace all section positions; body with `positions:[{sectionCode,sectionOrder}]` |
| POST | `/api/v1/catalog-admin/drafts/{id}/publish` | Validate unchanged semantic fingerprint, then activate catalog version. Body: `{expectedRevision,expectedActiveVersionId,actor}` |
| POST | `/api/v1/catalog-admin/drafts/{id}/discard` | Discard editable candidate while preserving event history |
| GET | `/api/v1/catalog-admin/drafts/{id}/events` | Event metadata, including SHA-256 |

Publishing verifies the exact current active version, original vs draft semantic contracts (field code/type/unit/required/input mode/formula, section code/layout/parents, validation rules and financial mapping), and original `schemaSha256` + `mappingSha256`. Editing/reordering changes `presentationSha256` only. The old version remains immutable and historical `form_revision.form_version_id` remains valid. Concurrent publication is serialized with a lock on the form row and optimistic edit-revision guards; stale requests receive 409.

**Review states:** edited translated text receives `DRAFT_UNREVIEWED`. Publication means "active catalog presentation", NOT translation/financial S2 approval. Missing source translations remain explicit. Before adding real independent moderator review, authentication and approval attestations are mandatory.

## R7.B / R7.C (not yet implemented)

R7.B: typed creation/retirement of fields and sections, field-code policy (manual codes only), metric catalog synchronization, compatibility diff/impact, migration and parameter validation, and explicit semantic approval gate. Changing the type/identity of a field requires a *new* financial contract and an impact check of existing report/draft references.

R7.C: currency/period/metric reference catalog editing with ISO validation, immutable currency precision/version contracts, permission model and audit. Never change the meaning of an already-used ISO currency or period definition silently.

## Acceptance

An F02 translation draft must not be visible via `GET /api/v1/catalog/forms/F02` before publication. After publication, new title/description/order becomes active, original formVersionId remains retrievable in history, and neither form schema hash nor mapping hash changes. Stale edit or stale base version → 409; duplicate field positions / unknown field or locale → 422; concurrent open draft → 409. No new financial facts or auto-approval may be produced.
