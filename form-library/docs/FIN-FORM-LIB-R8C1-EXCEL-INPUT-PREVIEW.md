# FIN-FORM-LIB-R8.C1 — Excel-style Read-only Template Preview (implemented)

Branch: `feat/fin-form-library-standalone`. Java 21 / Spring Boot / H2 / Liquibase.

## Endpoint

`GET /api/v1/templates/{formCode}/preview?locale=ru&periodType=M&periodCodes=2026-01&periodCodes=2026-02&styleCode=EXCEL_GRID`

The endpoint reads the current `fin_lib` form version and returns a **blank structural preview**, without saving an instance, revision, input value or project/author data. `previewOnly=true` and `persisted=false`.

Supported styles for `SINGLE_RECORD`:
- `EXCEL_GRID` — indicators in rows, periods in columns;
- `TRANSPOSED_GRID` — periods in rows, indicators in columns;
- `SECTION_FORM` — indicators grouped by section, exactly one selected period.

`ROWSET` selects `ROWSET_TABLE`, with columns and zero fabricated business rows.
`EVIDENCE` selects `EVIDENCE_INFO`, with no fabricated upload; unsupported content modes fail closed.

## Presentation profile v1 (in code, not yet stored as database rows)

- Row-number gutter: **36px width, min 36px, max 36px**, fixed/resizable=false, pinned LEFT, horizontalAlign CENTER, verticalAlign CENTER, 12px font.
- Excel-style A/B/C columns, 1/2/3 row indexes, explicit semantic keys `metricCode` and `periodCode` (never identify data via Excel coordinates).
- `showGridlines=true`, `banding=ROWS`, `freezeHeader=true`, `freezeFirstColumn=true`, `density=COMPACT`.
- `highlights.active=ACTIVE_BLUE_BORDER_2PX`, `selectedRange=SELECTION_PALE_BLUE`, `requiredMissing=REQUIRED_MISSING_AMBER`, `invalid=INVALID_RED_BORDER`, `changed=CHANGED_DOT`, `readOnly=READ_ONLY_MUTED`.

These are renderer tokens. **Active selection, edit/dirty/error events, actual focus and keyboard handling require a UI renderer.** This release provides the data/appearance contract only: no React, HTML renderer, style CRUD or database migration.

## Safety invariants

- System metric codes come only from `fin_lib.lib_metric_definition`. No invented derived/system codes.
- Cell `value=null`, `state=MISSING` until real data is submitted to a separate intake writer.
- Only source fields with `inputMode=MANUAL`, a known source metric and compatible form capture mode are marked `editable=true`. Other fields are read-only. Missing translations are shown with status, not guessed.
- The ordering/labels/style does not affect canonical financial identity or SHA. This API never touches `fin_intake`.
- Unsupported styles, malformed periods, duplicated canonical periods and unknown locale/form are rejected.

## Verified test matrix

`InputTemplatePreviewIntegrationTest` checks: Excel coordinates, fixed centered 36px gutter, alternating-row metadata, manual/editable F02 cells, two orientations, four locales, `SECTION_FORM`, ROWSET without invented rows, rejected invalid styles/periods, and unchanged `fin_intake` record counts.

Full suite: `mvn --batch-mode -f form-library/pom.xml clean verify`.
