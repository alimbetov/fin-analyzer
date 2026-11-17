# FIN R15 — Complete 166-form editorial catalogue seed (RU/KK/EN/ZH)

**Scope:** working branch `feat/fin-form-library-standalone`, Form Library Backend only. This is a **real database load** of display candidates for the existing 166 form codes, not the approval of 166 financial schemas or a real React UI.

## Source of truth and content integrity

- **Existing canonical identities:** 166 `fin_lib.lib_form_definition` entries. No new form codes are created, no form versions are rewritten, and no `fin_intake` data is changed.
- **44 finance/planning/evidence titles**, **59 operational/detail titles**, **63 sector titles** explicitly authored in four languages in three R15 TSV source files. These sources are checked against **all 166 exact codes** of `form-compatibility.tsv` at startup.
- **664 language-specific candidate records**, with `title`, `purpose`, `description`, `locale`, `source_contract_sha256`, `review_status='AUTO_DRAFT_REVIEW_REQUIRED'`, stored in `fin_lib.lib_form_editorial_draft` via Liquibase 011.
- The titles are form-specific, not generic "Form 01" placeholders. **Purpose/description are conservative draft text automatically composed from the real form title, grain, source aggregate and/or canonical field code list**. For original free-form Russian descriptions, the Russian draft uses that content; non-Russian texts are summaries and require editorial review. Some technical field identifiers remain language-independent until reviewed.
- A separate `lib_form_editorial_seed` fingerprint rejects partially populated databases and unplanned source changes; the seed is replay-safe. All 664 records are stored transactionally with JDBC batch insert. Source change requires explicit controlled new edition, not silent replacement.

## Why editorial candidates do not mutate immutable catalog versions

`fin_lib.lib_form_translation` is **version-scoped** and is part of a specific presentation edition. Editing those translations directly in already-active versions could violate immutable presentation SHA and historical binding. Therefore R15 uses a **separate candidate overlay**:

1. Read the active version's locale-specific title/description/purpose.
2. For **each missing field only**, read the matching candidate draft for the same exact `formCode` and `locale`.
3. If any generated title/description is used, return `translationStatus='AUTO_DRAFT_REVIEW_REQUIRED'`. Missing versioned purpose returns `purposeTranslationStatus='AUTO_DRAFT_REVIEW_REQUIRED'`.
4. Existing authored translations take priority and remain unchanged. A moderator should open a catalog draft and publish a new presentation version to promote candidates after editorial approval.
5. Reports continue using pinned `formVersionId` and financial SHA. No entry rights or S2 qualifications are inferred from display availability.

## API / UI behavior

The **existing** routes now return nonblank display text for all 166 forms in all four languages:
`GET /api/v1/catalog/forms?locale=ru`
`GET /api/v1/catalog/ui/categories/GROUP_SECTOR/forms?locale=kk`
`GET /api/v1/catalog/ui/forms/page?page=0&size=24&locale=zh-Hans`
`GET /api/v1/catalog/ui/forms/F02/entry?locale=en`

Existing `GET /api/v1/catalog/ui/coverage?locale=ru` fields preserve their strict meaning: `namedForms`, `describedForms`, `purposefulForms` and `fullyDocumentedForms` refer to **versioned translations only**, not generated candidates. Additional:
- `editorialDraftForms = 166`: catalog candidate entries per locale;
- `displayCompleteForms = 166`: viewable, localized titles/purpose/description per locale, including candidates;
- `needVersionedEditorialReviewForms`: number of forms without a complete versioned triple. This remains positive and is the next editorial backlog.

`GET /api/v1/catalog/ui/translation-gaps?locale=kk&page=0&size=24` remains an actionable backlog for **versioned translations**. It is intentionally NOT zero simply because candidate display strings exist.

## Restrictions

- No automatic FIN S2 financial qualification, schema approval, bank application readiness, paid access or project-sharing entitlement.
- 12 previously curated pilot labels remain preferred over draft fallback; these are still marked `DRAFT_UNREVIEWED`.
- All 166 descriptions, especially translated source free-form contracts, still require independent editor/moderator QA before marking reviewed. R15 is **draft catalog coverage**, not certified four-language editorial completion.
- No new display text is sourced from `fin_intake`. Caffeine caches only public reference DTOs; report values/rights stay uncached.
- This codebase does not include the React catalogue UI or a running deployed production PostgreSQL database. CI loads data into H2 PostgreSQL mode; PostgreSQL tenant and concurrency tests are next.
