# FIN-FORM-LIB-R8 — Multi-Style Templates with One Canonical Intake Contract

Status: **PROPOSED / architectural specification**. No new production endpoints, schemas or adapters are claimed implemented.
Branch: `feat/fin-form-library-standalone`; scope exclusively `form-library/`.

## Fundamental invariants

1. `fin_lib` owns *system* form and metric codes, structural/formula/validation versions, translations and display profiles. It never receives user-entered numbers.
2. `fin_intake` owns exactly one financial draft lineage: instance -> immutable revision -> typed values (including optional row identity for ROWSET), period, project and verified author context. It does not rewrite the system catalog.
3. Presentation style and data transport (manual, pasted TSV/CSV, API) are orthogonal. Two inputs with the same semantic dimensions and normalized cell set MUST have the **same financial content SHA-256**, irrespective of style, language, table orientation, cell order and display formatting.
4. Author attribution belongs to authenticated revision metadata; it must NOT be inferred from the display style or blindly trusted from a user-editable form control. Auth is not implemented in the current standalone backend.
5. Revisions pin the exact form schema version. A catalog translation/order update may change display while previous submitted values remain valid and readable.
6. A missing cell does NOT mean zero, a real zero does NOT mean missing, and an explicitly non-applicable cell has no numeric value.
7. Display-only derived totals are never accepted as manual inputs or persisted as source facts.

## Styles, not independent parsers/writers

| Style | Primary use | Scope |
|---|---|---|
| `GRID_METRIC_ROWS` | Excel-like sheet: metric rows, period columns | Single-record numeric data |
| `GRID_PERIOD_ROWS` | Transposed sheet: period rows, metric columns | Single-record numeric data |
| `SECTION_FORM` | One period at a time; inputs grouped in sections/cards | Manual entry |
| `ROWSET_TABLE` | Transaction/register forms; each record has immutable row ID | Repeated rows |
| `BULK_PASTE` | Import transport for GRID_* or ROWSET_TABLE, not a separate fact model | TSV/CSV + explicit mapping review |

The MVP includes GRID_METRIC_ROWS, GRID_PERIOD_ROWS and SECTION_FORM. Bulk paste is a separate ingress channel and should reuse the same code binding and validator. ROWSET needs its own stable row identity, never mere visual position.

## Pipeline

`fin_lib (formVersionId, fieldCode/metricCode, type, unit, rules, presentation profile)`
-> `Render(styleCode, locale, period selection)`
-> `Input Adapter (coordinates / form controls / clipboard mapping to canonical keys)`
-> `CanonicalSubmissionNormalizer`
-> `Schema/Type/Range/Unit + authorization checks`
-> `Single FormRevisionWriter`
-> `fin_intake`.

A style adapter does **not** write to the database. It only returns normalized values and diagnostics. No adapter may mint a system metric code or rewrite an existing code.

## Proposed canonical request

```json
{
  "formCode": "F02",
  "formVersionId": "<server-issued UUID>",
  "organizationId": "<UUID>",
  "projectCode": "PRJ-2026-015",
  "periodType": "M",
  "currencyCode": "KZT",
  "expectedRevision": 0,
  "cells": [
    {"metricCode":"F02.netRevenue","periodCode":"2026-01",
     "state":"PRESENT","value":{"kind":"DECIMAL","decimal":"1000"}},
    {"metricCode":"F02.cogs","periodCode":"2026-01",
     "state":"PRESENT","value":{"kind":"DECIMAL","decimal":"600"}},
    {"metricCode":"F02.netRevenue","periodCode":"2026-02",
     "state":"MISSING","value":null}
  ]
}
```

This is a **proposed logical DTO**, not an existing endpoint. In the current sparse implementation, missing values are omitted from the persisted cell table. `userCode` (revision author) must be server-resolved from trusted identity context; until IAM exists, explicitly mark it untrusted local-test metadata. For ROWSET, each cell or row additionally binds to a stable `rowId`; row order is presentation metadata, not identity. Typed nonnumeric fields such as dates, text or booleans require their own type arms, not coercion into decimal.

Business identity must include at least organization, economic entity, business scope where applicable, project, form/version, reporting basis, currency, canonical period and row/metric coordinates. Whether author is part of the SHA domain needs deliberate separation: **author is audit/attribution metadata, not the identity of an economic observation**.

## Spreadsheet paste safety

- Explicitly bind source headers to known, version-pinned system codes. Localized display captions are **never unique identifiers**; duplicate or ambiguous headers must trigger a mapping review instead of guessing.
- An unheaded rectangular paste is allowed only when initiated at a known, fixed template anchor, with exact dimensions and clear axis semantics.
- Normalize recognized periods through `PeriodResolver`, not string matching.
- Parse numeric strings with a declared source locale; reject ambiguous decimal/thousand separators (e.g. `1,234`) unless import profile specifies the format.
- Never execute or evaluate pasted Excel formulas, arbitrary JavaScript/SQL or untrusted expressions. Detect and reject expressions in numeric input; use safe escaping in subsequent CSV/HTML exports.
- Reject unknown fields, duplicate metric+period+row keys, wrong currency/units, invalid period, precision and sign errors, and attempts to write DERIVED/READ_ONLY cells.
- When any paste cell fails parsing, return row/column diagnostics and **do not persist a partial revision**.
- First preview: `parse -> normalized cells + error list + proposed bindings`; second action: `commit` with idempotency key and expected revision. Revalidate server-side on commit.
- Enforce bounded row and column limits; avoid interpreting header strings as executable user code.

## Versioning and persistence

Proposed `fin_lib.lib_form_presentation_profile` (future migration) fields:
`id, form_version_id, style_code, style_version, enabled, default_for_content_mode, profile_json, profile_sha256`.
Profile JSON may contain section visibility, frozen headers, card grouping and paste mapping hints. It must **not** contain metric definitions or financial values.

Proposed `fin_intake` canonical writer receives *only normalized cells*. Existing `FormDraftService` (`values_json`, `projectId`, `createdBy`) and `ReportGridService` (`metricId+periodCode` cells but no project identity) **must converge before a third persistent API is added**. Do NOT introduce another independent table of facts. A candidate approach is a versioned `FormInstance -> FormRevision -> FormRevisionValue` model, with migration and compatibility adapters for the two existing storage shapes. For numeric multi-period forms, existing `lib_report_revision_cell` provides useful primitives; do not copy it blindly without project/author/form-version context.

Canonical SHA inputs include scope + pinned schema + exact set of normalized typed observations. Exclude styleCode, orientation, locale, header text, formatting, copied whitespace and row display order. Audit can separately record `inputChannel`, `styleCode`, `authorUserCode`, timestamp, applied mapping version and client metadata.

## Acceptance matrix

1. 3 different styles, same F02 facts -> identical set of typed keys/values and same content SHA.
2. Orientation and language RU/KK/EN/ZH do not alter content SHA.
3. Empty vs numeric zero vs NOT_APPLICABLE remain distinct; never invent zero.
4. Incomplete, unknown, duplicated or ambiguous pasted cells -> fail-closed and no database revision.
5. Pasted formula text or injected markup -> never evaluated; invalid numeric input is rejected.
6. A duplicate command + same idempotency key is replayed, changed body is rejected.
7. Concurrent saves to one instance -> stale expectedRevision rejected.
8. Old form revision and old template remain readable after newer `fin_lib` presentation publication.
9. ProjectCode and identity-bound authorUserCode are available for auditing; mismatch/cross-tenant access rejected.
10. Every display profile allows output-only derived totals without letting clients post them as source values.

## Delivery sequence

- **R8.A**: agreed canonical DTO, schema version pinning, pure in-memory style adapters, parser fuzz/negative tests, preview-only API. No DB writes, no React.
- **R8.B**: unify existing intake writers and enforce project/user context; real H2 + PostgreSQL integration tests and transactional negative cases.
- **R8.C**: style profile versioning; template render/read-only API with CSS-neutral JSON and optional React later.
- **R8.D**: safe bounded TSV paste + mapping preview + atomic submit; end-to-end proof across all styles.
