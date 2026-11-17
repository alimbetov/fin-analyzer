# FIN Form Library R11 — Four-language Catalog -> Form -> Period -> Grid

**R11.A code status:** 47 candidate navigation categories, 389 category/form bindings, four locale projections, full form-card/launch read model, nullable versioned form-purpose translation, local catalog editor support. **Not yet a React application, bank S2 approval, paid access, actual sharing or per-form period qualification.**

Repository `alimbetov/fin-analyzer`, only `feat/fin-form-library-standalone`, Java 21 / H2 / Liquibase. Keep `main` untouched.

## 1. UX journey and data identity

1. Choose locale `ru`, `kk`, `en` or `zh-Hans` in Mission Control. One language at a time.
2. Open category tree (7 roots, 12 functional-detail groups, 21 sector groups, 7 purpose groups).
3. Click a category -> the list of distinct form codes belonging directly or via a child category. Each card displays **name**, **purpose** and **description**, translation/review status, form code, current pinned `formVersionId`, capture mode and schema qualification.
4. Click a form -> receive a launch descriptor. For `SINGLE_RECORD` and `ROWSET`, navigate to the **period and style selection panel**. For `EVIDENCE`, navigate to a document evidence view, not a numeric period grid.
5. Once a per-form period policy is independently approved, the user selects period type, start, length (1..12 months or 1..31 days for relevant forms), and style (`EXCEL_GRID`/`TRANSPOSED_GRID`/`SECTION_FORM` or `ROWSET_TABLE`). Then call existing read-only template preview and show the fixed 36px centered row index, scrolling and weekend highlights.
6. All input is written only through a separately qualified intake writer in `fin_intake`, independent of locale, category, row coordinates or style.

**Crucial gap:** Existing `GET /api/v1/templates/{formCode}/preview` always resolves the current catalog version, and does not check `expectedFormVersionId`. It must be hardened for pinned selection before claiming that navigation -> editor is a race-safe immutable workflow. This R11.A step implements **navigation data and launch descriptors**, NOT a full end-to-end save flow.

## 2. Existing authoritative form data

`fin_lib.lib_form_definition`: `formCode` identity, active `currentVersionId`.

`fin_lib.lib_form_version`: immutable definition and schema/presentation/mapping digests, `contentMode`, qualification.

`fin_lib.lib_form_translation` (four records per version): `locale`, `title`, `description`, `review_status`. Liquibase **009** adds separate nullable `purpose` and `purpose_review_status`. This avoids abusing the long description as the concise "what is this report for?" label. A non-null purpose can be created only through a cloned form version (`CatalogManagementService`), preserving old revisions. The translation contributes to the presentation hash, not the financial canonical hash.

All 166 forms have translation slots, but many slots have pending/empty names or descriptions. New curated editorial **draft** purpose translations exist for 12 selected forms in four languages; remaining purposes are **null + PENDING_TRANSLATION**, never machine-guessed. Category translations also have `DRAFT_UNREVIEWED` status until reviewed. Do not advertise full translation approval or S2 qualification.

### Target domain contract for DB-driven category moderation (R11.B)

`fin_lib.lib_catalog_category`:
- `category_code` PK, `parent_category_code` FK nullable, `category_kind`, `sort_order`, `catalog_version`, `lifecycle_status`, `effective_at`, `content_sha256`.
- Nested root/leaf integrity, no circular parents; no concatenation-derived identifiers.
- `ROOT`, `DOMAIN`, `SECTOR`, `PURPOSE`. Purpose is a discoverability facet, *not* a second financial form.

`fin_lib.lib_catalog_category_translation`:
- `category_code` + `locale` + `translation_version` key; `title`, `purpose`, `description`, `review_status`, `translation_sha256`.
- Exactly four supported locale codes; pending strings reported explicitly, never silent fallback. Approval is an editorial procedure separate from FIN financial S2.
- Immutable published version; edits create a new translation/categorization revision.

`fin_lib.lib_catalog_category_form`:
- categoryCode + formCode + mappingVersion + role; `priority`; unique per category/form/mapping version.
- Roles `PRIMARY`, `DOMAIN`, `SECTOR`, `CORE`, `SUPPORTING`, `EVIDENCE`.
- Foreign keys to stable form identity; do not copy financial data, do not attach market price or role entitlement here.

R11.A uses versioned TSV snapshots (`catalog-category-i18n-r11.tsv` and `catalog-category-form-r11.tsv`) as **provisional transport seed**; category tables/CRUD/moderation are R11.B, not falsely claimed implemented.

## 3. Read-only R11.A HTTP contracts

`GET /api/v1/catalog/ui/categories?locale=ru`

Returns `catalogVersion=1`, 47 category nodes with code, parent, type, title/purpose/description, `DRAFT_UNREVIEWED` translation flag, unique form count and children. Parent `GROUP_PURPOSE` is independent of sector membership.

`GET /api/v1/catalog/ui/categories/PURPOSE_BANK_FINANCING/forms?locale=kk`

Returns category header, 27 unique form cards tagged `CORE`/`SUPPORTING`/`EVIDENCE`. A specific form code occurs only once in a category result. `title`, `purpose`, `description` and independent translation readiness are read from **live** `fin_lib`, not duplicate static descriptions. `formVersionId` is included for every card. A missing purpose is `null` + `PENDING_TRANSLATION`.

`GET /api/v1/catalog/ui/forms/F02/entry?locale=ru`

Returns selected form code, active version UUID, text, content/capture/financial state, next screen (period chooser, or evidence viewer), allowed *presentation styles*, and an **unreviewed** per-form period policy with no approved types. Do not interpret the global `D/W/DEC/M/Q/H/Y` catalogue as per-form authorization.

## 4. View example (illustrative shape)

```json
{
  "formCode": "F02",
  "formVersionId": "<existing-current-version-uuid>",
  "locale": "ru",
  "title": "Отчёт о прибылях и убытках",
  "purpose": "Оценить доходность и финансовый результат",
  "description": "Выручка, себестоимость и операционные расходы за период",
  "titleTranslationStatus": "DRAFT_UNREVIEWED",
  "purposeTranslationStatus": "DRAFT_UNREVIEWED",
  "contentMode": "SINGLE_RECORD",
  "nextScreen": "PERIOD_AND_STYLE_SELECTOR",
  "formPeriodPolicyStatus": "NOT_REVIEWED"
}
```

Do not use category label, localized description, sheet column name, or Excel address in source financial identity.

## 5. Policy separation for future UI

- **Catalog structure and four-language text:** `fin_lib`. Editor approval / visibility are not FIN S2.
- **Private favorites and pinning:** per verified user and organization, never global state of the form.
- **Paid access:** entitlement/price catalogue + payment service + server authorization. Never gate with a client-only `isPaid` or share flag.
- **Archived form:** versioned catalog lifecycle or personal archive state, not deletion of `fin_intake` revisions.
- **Sharing:** shares an explicit report instance/revision, with verified owner, expiry, visibility scope, revocation and audit; never a bare public URL to draft facts.
- **User facts, financial approvals and report workflow:** `fin_intake` and qualified FIN processing; do not use status `READY_FOR_REVIEW` to imply approval.
- **Period policy:** bind to exact form version, state, granularity and allowed period types; prevent unapproved daily or monthly entry.

## 6. Test/acceptance matrix

- 47 category nodes, 7 root groups, 4 complete locale-specific candidate category text sets, 389 form-category bindings, 166 unique primary form IDs.
- Group examples: `GROUP_FINANCE` 8, `DETAIL_CASH_AND_DEBT` 6, `SECTOR_G` 3; purpose `PURPOSE_BANK_FINANCING` 27.
- All four locale F02 cards have same `formCode` and `formVersionId` while text changes.
- Missing ZH title/purpose/description in an unfilled form remains null, no fabricated translation.
- Display purpose is editable only in a cloned catalog presentation version; old active catalog remains unchanged until publish.
- Bad locale returns 400, unknown category/form 404; category browsing does not write into `fin_intake`.
- Only after PostgreSQL migrations/negative tests and trusted IAM can this be called production-complete.

## 7. Subsequent steps

**R11.B:** category database schema + revisioned admin/editor + translation workflow, version pinning of preview and reviewed per-form period policy.

**R11.C:** actual React Mission Control catalog and editor integration, keyboard/scrolling and navigation tests.

**R11.D:** authenticated favorites, paid entitlements, archive and share rights. No global unauthorized mutation of financial data.
