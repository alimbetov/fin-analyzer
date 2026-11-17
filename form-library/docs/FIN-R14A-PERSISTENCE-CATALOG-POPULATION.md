# FIN R14.A — Verified Intake Persistence and Database-backed Form Catalog

**Branch:** `feat/fin-form-library-standalone`. R14.A is a backend-first verification and catalog population step, not a completed React input editor nor production-ready S2 financial qualification. One catalog includes **166 form definitions**; the navigation hierarchy has **47 categories** and **389 links**. These are not 166 categories.

## 1. Direct facts persisted / tested

- `fin_intake.lib_form_instance`: organizationId, economicEntityId, businessScopeId, projectId, form identity, interval/currency scope, current immutable revision reference and workflow status. User-facing `createdBy` is presently untrusted; IAM/tenant permissions are future work.
- `fin_intake.lib_form_revision`: exact pinned form version, normalized JSON source values, canonical SHA-256, immutable revision number. Generated rows stored in `fin_intake.lib_form_revision_row` with stable per-row UUID and display ordering.
- `fin_intake.lib_report_revision` and `lib_report_revision_cell`: multi-period, canonical metric/period/value-state pairs. `PRESENT(0)`, `NOT_APPLICABLE(null)`, and omitted `MISSING` are not interchangeable. This second writer still needs unified qualified form-version and project bindings.
- Existing integration tests check HTTP responses, idempotency and historical revisions. R14 adds **direct database read-back tests**, not merely DTO equality: project ownership field, saved SHA, decimal normalization, original historical data, 200-row complete batch and 31 daily grid cells.
- The save services remain transactional and uncached. Writes to the same existing form instance are serialized by a pessimistic row lock; grid saves now use the same approach. With Hibernate JDBC batch size 32 and `saveAll` for immutable rows/cells, 200 rows can be persisted within one transaction, not 200 API requests.
- Unsupported `SCHEMA_NOT_APPROVED` catalog forms cannot write source drafts (except the exact explicitly modeled F02 pilot). `EVIDENCE_ONLY` upload remains unimplemented. All candidate schemas remain unqualified for financial S2.

## 2. Category catalog populated in `fin_lib`

Migration `010-lib-catalog-category.sql` adds:
- `lib_catalog_category` (47 IDs, 7 roots + 40 leaves);
- `lib_catalog_category_translation` (47 × 4 = 188 draft editorial translations for `ru`, `kk`, `en`, `zh-Hans`);
- `lib_catalog_category_form` (389 links through FKs to `lib_form_definition`, primary category exactly one per form, 166 unique primary links);
- `lib_catalog_seed_manifest` stores source SHA-256 to reject partial or divergent bootstraps;
- additional project-scoped `fin_intake.lib_form_instance` and revision-history SQL indexes.

`CatalogCategoryBootstrap` runs after the 166 canonical form definitions and their 664 translation slots have been seeded. It validates exact coverage and inserts all 624 catalog records in one transaction. Re-running the same seed does not duplicate records. A partially present or source-mismatched seed fails startup instead of silently producing divergent categories.

`CatalogUiNavigationService` now serves category titles and form associations from a *live, validated* database snapshot in Caffeine (5-minute default TTL, cache invalidated when catalog publication commits). TSV snapshots remain a checked deployment seed, not the data source for API browsing.

## 3. Editorial completeness ≠ system completeness

The 47 categories have preliminary text on all four languages. This is **not editorial approval**. For 166 forms, only 12 pilot codes have explicitly curated titles/descriptions/purpose in all four languages. Many other forms have empty purpose or other translations and show `PENDING_TRANSLATION`, not invented descriptions. All 166 active form versions currently have `CANDIDATE_UNQUALIFIED`.

Read-only live coverage endpoints:
- `GET /api/v1/catalog/ui/coverage?locale=ru`: counts of total forms/categories/links, nonblank name/description/purpose per locale, typed/evidence/unapproved categories, S2 unqualified count.
- `GET /api/v1/catalog/ui/translation-gaps?locale=kk&page=0&size=24`: missing fields per form and the exact active formVersionId. Max page size 50.

The outstanding 154+ forms require per-form, per-language editorial review. Do not mark forms as complete merely because `fin_lib.lib_form_translation` rows exist with null text.

## 4. Quality gates and limitations

- Postgres FK/unique/concurrency-negative tests are a **required next gate**. Current GitHub Actions runs use Java 21 + H2 with PostgreSQL compatibility mode. No production PostgreSQL timings, p95/p99, real browser interaction, or cross-node cache invalidation are proven here.
- The original 166 forms are not all usable for financial data entry. The 122 type-hint captures are still unqualified candidates; 32 schemas are not approved and 12 evidence forms cannot upload documents yet. F02 is the defined pilot.
- Existing projectId is a UUID on `lib_form_instance`, not a verified Project entity or tenant authorization. Real project access and secure external sharing are separate R13 gates.
- The second grid writer `ReportGridService` still lacks its own required projectId and pinned source form-version metadata. Do not present the two writers as unified `fin_intake`.
- Caffeine must only be used for public/reference catalog data. Never cache the current private `fin_intake` data, active grants or report statuses in `CatalogReadCache`.
- No strong latency SLA is asserted from a single in-memory CI suite. Benchmark on PostgreSQL with representative concurrency and record p50/p95/p99, errors and SQL roundtrips before shipping.
