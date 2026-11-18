# FIN R18 — XLSX exchange using REPORT + PARAMETERS

**Branch:** `feat/fin-form-library-standalone`  
**State:** implementation committed, verification pending; no Maven/CI GREEN claim.

## XLSX V2 contract

Each exported file contains exactly **two** sheets:

1. `REPORT` — editable values, rendered in one of `CLASSIC`, `EXCEL`, `DASHBOARD` visual layouts.
2. `PARAMETERS` — structural template contract. Header is `FIN-FORM-XLSX-V2`. Contains the source instance UUID, organization UUID, exact form version UUID, form code, expected revision, original content SHA-256, style code and number of input fields. Field rows contain `fieldKey`, `valueCell`, `fieldType`, `unitCode`, `required`, `parser`.

**Do not trust the workbook:** the second sheet is self-described metadata, not a cryptographic signature or authorization token. Import validates all bindings against server-side definitions from the *pinned* `lib_form_version`, and matches the instance/revision/SHA against `fin_intake`. A user changing units, field types, parser or coordinates must be rejected. The backend's `FormDraftService` performs actual data validation and immutable revision write. XLSX formulas and non-text values are rejected to avoid evaluating untrusted formulas and Excel numeric precision loss.

## Current endpoints

```http
GET /api/v1/drafts/{instanceId}/export.xlsx?organizationId=<uuid>&styleCode=EXCEL
```

Style is optional and may be resolved from `userId` preferences; otherwise `CLASSIC`.

```http
POST /api/v1/drafts/{instanceId}/import.xlsx
Content-Type: multipart/form-data
Idempotency-Key: <unique-key>
```

Multipart: `organizationId`, `actor`, `reason`, `file`. Files are limited to 2 MB; the endpoint writes through the existing `FormDraftService.save` transaction. The template's exact revision is mandatory; stale imports fail closed.

## Supported and unsupported

- Supported: export of existing **saved** `SINGLE_RECORD` and `ROWSET` `lib_form_instance`; three styles; import of edited values back into the same instance; immutable revision history.
- Supported round-trip only for keys already generated in `REPORT`/registered in `PARAMETERS`. For ROWSET, row identity and ordering must remain unchanged. Any new row, column, or unknown field is rejected.
- The `PARAMETERS` sheet documents validation metadata but cannot override server-side type, requiredness or metric mapping.
- Not implemented: `ReportGridService` multi-period matrix export; XLSX import to create a **new** form instance; import from external arbitrary/non-FIN templates; PDF/HTML/CSV output; mapping preview/commit workflow; approved S2 financial import.
- No UI implementation; the REST API is local-only while authentication/authorization is absent. Caller-supplied userId/actor/organizationId must never be trusted in production.
- The default H2 database is volatile. Persistent PostgreSQL deployment needs complete Liquibase 001–016 proof (and any later migrations) before production use.

## Test commands

```bash
mvn -f form-library/pom.xml -Dtest=FormXlsxCodecUnitTest test
mvn -f form-library/pom.xml -Dtest=FormXlsxExchangeIntegrationTest test
mvn -f form-library/pom.xml clean verify
```

Test coverage: all three styles, two-sheet output, exact values, preservation of historical revisions, stale revision conflict, rejected formula, changed code/address/type/unit/required/parser, unknown metadata cells and oversize files.

## Future: declarative parser policies

For controlled external spreadsheets, the trusted server must store a versioned **ImportProfile** (separate from UI style), containing source format, sheet and coordinate mappings, locale-aware decimal policy, header signature, unit conversion rules, validators, fixed SHA-256 and moderation state. Preview parses into a normalized candidate snapshot with diagnostics, and only an explicit commit calls `FormDraftService`. The workbook's PARAMETERS can select a registered profile ID/version but must not define permissive validation policy itself.

## R18.1 — compact editor layout, localization and revision safety

- `EXCEL` now renders **Field / Value in adjacent columns A and B**, with values located at `B5`, `B6`, etc. The unused B gap from the previous layout is gone; addresses in `PARAMETERS` reflect the new export.
- Input cells use visual hints: yellow for required values, pale blue for optional values. Field labels and headers are styled independently. Financial values are exported as **text**, preserving exact decimal digits and avoiding Excel binary rounding.
- `GET /api/v1/drafts/{instanceId}/export.xlsx?organizationId=<uuid>&styleCode=EXCEL&locale=kk` supports `ru`, `kk`, `en`, `zh-Hans`; default is `ru`. The temporary UAT page has a corresponding language selection. Form title and visible field labels are resolved from existing `fin_lib` translations. When no suitable translation exists, codes are displayed; translations are NOT generated or verified automatically.
- The human-readable `REPORT` captions are **not** used to determine field identity. The unchanged, server-validated `PARAMETERS.fieldKey` and `valueCell` remain authoritative. Therefore the report can be presented in any supported language without altering values, form version, immutable content SHA or import mapping.
- `PARAMETERS` includes `displayLocale` and `reportTitle` as informational presentation values. Financial metadata remains separately validated against the pinned server snapshot.
- Import now canonicalizes incoming values in the no-write preview. An **unchanged** file fails with `XLSX_NO_CHANGES` (422) and does not create a redundant financial revision. A changed financial value goes through `FormDraftService.save` with the source `expectedRevision`; the writer calculates a **new** canonical `contentSha256` and saves an immutable next revision. The original revision and SHA remain readable.
- Existing XLSX files exported under the previous `EXCEL` A/C coordinate layout are not transparently migrated by the strict current V2 parser. Re-export using this version before editing, or introduce an explicit versioned compatibility adapter.

### Regression commands

```bash
mvn -f form-library/pom.xml -Dtest=FormXlsxCodecUnitTest,FormXlsxExchangeIntegrationTest,VersionedImportProfileIntegrationTest test
mvn -f form-library/pom.xml clean verify
```

**Release state remains HOLD until a real Maven/CI GREEN on the same commit SHA.**

## R18.2 — accounting context in XLSX

The previous CLASSIC/EXCEL/DASHBOARD screenshots showed a title and values but not enough context to interpret the report. The three renderers now show in the first three rows:

- Localized report title (from `fin_lib`).
- Accounting period code and localized period type (day/week/decade/month/quarter/half-year/year).
- Accounting/reporting currency from the persisted `fin_intake` instance.
- Inclusive reporting date range (stored `periodStart`, converted display of `periodEndExclusive - 1 day`).
- Reporting basis, form version label and current workflow status.
- DASHBOARD also displays separate type/coverage and revision metadata.

The EXCEL style's column titles are also localized into `ru`, `kk`, `en`, `zh-Hans`. Input cells remain at the previous deterministic addresses (`B5`, `B6`, `B7` for simple EXCEL/CLASSIC), so the previous FIN XLSX V2 cell mapping is not shifted.

Additional `PARAMETERS` entries (rows 12 and 13, counting from 1) contain `periodType`, `periodCode`, `periodStart`, `periodEndExclusive`, `currency`, `reportingBasis`, `coverage`, `dataSpace`, `formVersionLabel`, `workflowStatus`. These are source assertions, not authorization. The import service rechecks the entire context against the persisted report and rejects edits with `XLSX_REPORT_CONTEXT_CHANGED`. Edited visible period/currency headers are also rejected.

Older FIN XLSX V2 files without the additional context rows remain readable by the compatible parser, with the original strict revision/instance/version/content SHA checks; they do not acquire this additional context validation. Exporting a new file is recommended before changing input.

**Future enhancements, not implemented here:** organization and legal entity display names (from trusted identity/entity catalog), explicit scale (units vs thousands vs millions), document author and export timestamp, auditor sign-off, evidence attachments, frozen publication manifest, localized financial disclosure footnotes, and print layout. These should not be invented or used to alter financial input values.

**Verification:** `FormXlsxCodecUnitTest`, `FormXlsxExchangeIntegrationTest` and `VersionedImportProfileIntegrationTest`, plus complete `mvn -f form-library/pom.xml clean verify`. As of this commit no CI GREEN is recorded.

## R18.4 — deterministic descriptive export filenames

XLSX downloads use a server-generated ASCII-safe filename that carries the actual **persisted** accounting context (not mutable browser controls).

```text
FIN_<FORM>_<PERIOD_TYPE>-<PERIOD_CODE>_<CURRENCY>_<STYLE>_<LOCALE>_<FORM_VERSION>_r<REVISION>_<SHA12>.xlsx
```

Example:

```text
FIN_F02_M-2026-11_KZT_EXCEL_ru_FIN-FORM-V2_r0003_abcdef123456.xlsx
```

- `FORM`: stable form code, such as `F02`.
- `PERIOD_TYPE` and `PERIOD_CODE`: canonical accounting period from `fin_intake`, such as `M-2026-11`, `W-2026-W47`, `Q-2026-Q4` or `Y-2026`.
- `CURRENCY`: stored ISO currency, such as `KZT`, `JPY`, `USD`.
- `STYLE`: `CLASSIC`, `EXCEL`, `DASHBOARD`.
- `LOCALE`: `ru`, `kk`, `en`, `zh-Hans`.
- `FORM_VERSION`: pinned immutable form schema version label.
- `r0003`: financial revision number (minimum four digits, leading zeros).
- `SHA12`: first 12 characters of canonical `contentSha256`. A **visual differentiator only**, never a replacement for full SHA-256, authorization or the `PARAMETERS` integrity checks.

The filename intentionally omits organization UUID, project UUID, employee identity, form title (non-ASCII and possibly sensitive), and accounting amounts. Tokens are sanitized to ASCII `A-Z a-z 0-9 . _ -` and bounded in length; safe for Windows / macOS / Linux downloads. Filenames are **not** trusted on import: field mappings, revision, context and full SHA are always checked from `PARAMETERS` against the server snapshot.

The REST response sets `Content-Disposition: attachment; filename="<name>"`; temporary Thymeleaf UAT uses this header as the browser download attribute instead of overwriting it with `fin-F02-EXCEL.xlsx`. An import commit changes the revision and typically the content hash, so a new export is distinguishable from the previous one.

Tests: `FormXlsxFilenameUnitTest` (file safety, format, dimensions) and `FormXlsxExchangeIntegrationTest` (HTTP header, 3 styles × 4 locales, revision change). Run `mvn clean verify` and the opt-in 231-case R19 matrix for acceptance. CI status is not implied by the commit.
