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
