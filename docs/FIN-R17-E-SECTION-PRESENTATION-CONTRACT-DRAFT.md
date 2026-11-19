# FIN R17.E — Versioned Section Presentation Contract (design draft, HOLD)

Status: **DESIGN READY / IMPLEMENTATION HOLD**, until the same-commit Chromium browser qualification is GREEN.

## Context
The current `fin_lib.lib_form_section` records `section_code`, `section_order`, `layout_kind` and `parent_section_id`, but does **not** own localized titles or icon keys. The initial `CatalogBootstrap` seeds the single section `MAIN`. Thus the R17.C sections are presentational, not reviewed financial grouping. Do not assert meaningful sections exist in 166 forms.

## Boundary
- This contract belongs to **form presentation**, not `fin_intake` and not FIN HTAP.
- Existing field/metric financial contracts and immutable revisions are unchanged.
- Server is authoritative; UI icon renderer maps an allowlisted semantic `iconKey` to a Lucide component.
- Never accept arbitrary SVG, CSS, HTML, remote URLs or JS code as icon payloads.

## Proposed immutable schema
`fin_lib.lib_section_presentation_version`:
- `id` UUID primary key
- `form_version_id` UUID foreign key to exact existing form version
- `presentation_version` positive integer; unique by `form_version_id,presentation_version`
- `status` DRAFT / APPROVED / RETIRED
- `contract_version` fixed schema version
- `canonical_sha256` byte-safe SHA of sorted canonical presentation descriptor
- `created_at`, `created_by`, `approved_at`, `approved_by`
- Append-only after approval; no updates or deletes on approved rows.

`fin_lib.lib_section_presentation_entry`:
- `id`, `presentation_version_id`, `section_code`, `display_order`
- `parent_section_code` nullable
- `icon_key` from a finite allowlist; `tone_key` from `blue,teal,violet,amber,neutral`
- `collapsed_by_default` boolean; optional `help_reference` code
- Unique section code and display order per version.

`fin_lib.lib_section_presentation_i18n`:
- `presentation_entry_id`, `locale` in `ru,kk,en,zh-Hans`
- `title`, `help_text` nullable, `translation_state`
- Unique `presentation_entry_id,locale`
- Missing translation must be explicit: `PENDING_TRANSLATION`, never silently declared APPROVED.

## Descriptor / GET endpoint
`GET /api/v1/catalog/forms/{formCode}/section-presentation?locale=ru&formVersion={version}`

Response should contain:
- `formCode`, `formVersionId`, `presentationVersion`, `contractVersion`, `canonicalSha256`
- `locale`, `status`, `sections[]` with code, order, iconKey, toneKey, translated title and translation state.
- A section with no approved title is displayed under its original `sectionCode`.
- Only one ACTIVE/APPROVED version is selectable per exact form version. Draft versions must be queried through privileged admin APIs.

## Validations
1. Every section entry refers to a real section in the exact `form_version_id`; no phantom section and no missing section.
2. Parent references exist and are acyclic.
3. Unique stable sectionCode; deterministic order; no duplicate locale.
4. iconKey and toneKey are allowlisted.
5. SHA computed server-side over canonical UTF-8 data (including locale and fields) and validated on read.
6. Approved versions cannot be mutated; publish uses optimistic version gate and audit.
7. No financial calculations, field bindings, `fin_intake` writes, revision rewrites, or HTAP triggers.

## Rollout and backward compatibility
1. Land database constraints + RED/GREEN PostgreSQL integration tests on disposable DB.
2. Seed `MAIN` as known presentation label only, without pretending other semantic groups are approved.
3. Read-only API in parallel with existing `/catalog/forms/{formCode}`; no breaking DTO changes.
4. React feature flag OFF by default; compare new DTO with legacy local fallback.
5. Enable for one pilot form, then a representative set of content modes and languages, and finally all 166 forms after evidence.
6. Tests: referential integrity, duplicate section/order, missing locale, invalid icon key, SHA tampering, immutable approved rows, stale publish, concurrent moderators, and four-locale Browser E2E.

## Gate
Current browser workflow initially showed **6 PASS / 2 FAIL** on `a17a817`, while React/Backend regression was GREEN. No schema migration or production enabled endpoint for R17.E until browser gate is fully GREEN on the implementation parent SHA. Any later changes need same-SHA requalification.
