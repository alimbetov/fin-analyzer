# FIN R19.B — External XLSX Import Profile Builder

**Branch:** `feat/fin-form-library-standalone`  
**Status:** `DESIGN READY / IMPLEMENTATION BLOCKED BY R19 GREEN GATE`  
**Source contract:** R19 FIN XLSX V2, two sheets `REPORT` and `PARAMETERS`. R19.B is a new server-owned mapping adapter and does not introduce a new facts writer.

## Prerequisites (must be evidenced on same commit SHA)

1. `mvn -f form-library/pom.xml clean verify` GREEN with Java 21.
2. HTTP `VersionedImportProfileIntegrationTest`: immutable revision, no-write preview, tamper failure, ticket expiry/consumption, duplicate request, wrong organization.
3. Liquibase 001–018 clean install on PostgreSQL 16 and migration from existing standalone baseline, without checksum edits.
4. Multi-user/IAM and S2 moderation constraints explicitly remain local-only until supported.

**Do not mark R19.B IMPLEMENTED before these gates.**

## Business goal

Given an arbitrary third-party XLSX export, map source sheets and cells/columns to a *specific approved FIN field contract*, with diagnostics at preview time; make a new immutable form revision only after an explicit Commit.

## DTO proposal

```java
record ExternalImportProfileDraft(
    UUID formVersionId,
    String profileCode,
    String sourceSheet,
    String mappingMode,        // CELL or COLUMN_TABLE
    int headerRow,
    int dataStartRow,
    String localeCode,         // ru-RU, kk-KZ, en-US, zh-CN
    String decimalSeparator,   // explicit '.' or ','
    String groupingSeparator,
    String datePattern,        // allowlisted, e.g. dd.MM.uuuu, yyyy-MM-dd
    List<ExternalFieldBinding> bindings,
    long expectedDraftVersion
) {}

record ExternalFieldBinding(
    String sourceCellOrColumn, // "D7" for CELL, "C" for COLUMN_TABLE
    String targetFieldCode,
    String sourceType,         // DECIMAL, INTEGER, DATE, TEXT, BOOLEAN
    String sourceUnitCode,
    String nullToken,
    String transformCode      // allowlisted only; NO arbitrary expressions
) {}
```

Implement separate `profileDraftId` vs immutable `profileVersionId`. Never edit a published binding in place. Changing any mapping creates a new version and SHA.

## Profile lifecycle

`DRAFT -> REVIEW_REQUESTED -> APPROVED -> PUBLISHED -> REVOKED`

- Only trusted server-side roles may review, publish or revoke a profile.
- S2 must attest to field types, currency/unit conversion and financial semantics.
- Until IAM/S2 is integrated, lifecycle MUST be limited to `LOCAL_TEST_ONLY`; no production approval claims.
- An immutable published profile pins `formVersionId`, descriptor SHA, canonicalization contract version, parser version, unit table version, locale settings, and `profileSha256`.
- Profile revocation MUST stop new imports. Old `fin_intake` revisions remain readable and retain their input provenance.

## Proposed HTTP routes (NOT implemented)

| Verb | Endpoint | Contract |
| ---- | -------- | -------- |
| POST | /api/v1/external-import-profiles/drafts | Create configuration draft |
| PUT | /api/v1/external-import-profiles/drafts/{id} | Update with expectedDraftVersion |
| POST | /api/v1/external-import-profiles/drafts/{id}/validate | Static mapping audit |
| POST | /api/v1/external-import-profiles/drafts/{id}/review | Submit for moderation |
| POST | /api/v1/external-import-profiles/{versionId}/publish | Authorization + immutable publication |
| POST | /api/v1/external-import-profiles/{versionId}/revoke | Fail-closed revocation |
| POST | /api/v1/external-import-profiles/{versionId}/preview | Parse file, return diagnostics and no-write ticket |
| POST | /api/v1/external-import-profiles/previews/{previewId}/commit | Revalidate identity, file SHA, profile SHA, source revision; commit via FormDraftService |

Do not expose any publish/revoke route in the local module until IAM and moderation are actually functional.

## Parser implementation boundary

- Apache POI, XLSX only, allowlisted sheets and bounded rows/columns; reject zip bombs, external references, macros, formulas, and non-allowlisted workbook features. Do not evaluate formulas or silently convert floating-point NUMERIC cells to high-precision financial values.
- For decimal parsing: normalize with exact BigDecimal; reject ambiguous separators unless locale and format are explicit. Money precision and rounding are defined by server field policy, never workbook contents.
- Date parsing: strict resolver, year-of-era and leap-year checks, no silent timezone conversions.
- Header lookup: detect repeated/unknown/ambiguous source labels. A localized human-readable caption is never an identifier by itself.
- CELL mode: unique source cell coordinate per binding; explicit requiredness from the pinned contract.
- COLUMN_TABLE mode: stable row keys and row order preserved; duplicate IDs, missing columns and unbounded rows rejected.
- Unit conversions: explicit allowlisted rational factors + versions; no arbitrary scripting or spreadsheets formulas. Reject unsupported conversions.
- Diagnostics are structured: `code`, `severity`, `sheet`, `cell`, `row`, `sourceField`, `targetFieldCode`, `message`, `rawValue` (redacted if sensitive).
- A fatal issue blocks preview ticket creation; candidate values must never be partially committed.

## Required negative tests

1. Wrong/renamed sheet, missing/repeated headers, ambiguous column names.
2. Duplicate field/cell bindings, unexpected row and invalid row count.
3. `1,234` with unspecified source locale; invalid grouping and decimal precision.
4. `31.02.2026`, leap-year boundaries, ambiguous dates.
5. Unsupported source units and unapproved conversion factor.
6. Excel formulas/external links/ZIP bomb and oversized content.
7. Edited workbook PARAMETERS cannot override server policy or profile SHA.
8. Profile revoked after preview, file changed, old revision, duplicate commit, competing commits.
9. Cross-organization/profile reuse; wrong project/form/version.
10. Exact round-trip to canonical keys/values and immutable `fin_intake` revision after restart.

## Delivery plan, once R19 GREEN

- R19.B1 — draft DTO + server validation + H2/PostgreSQL constraints, tests RED→GREEN.
- R19.B2 — bounded source workbook inspection and static field mapping diagnostics.
- R19.B3 — strict locale number/date parser + typed transformation registry.
- R19.B4 — preview ticket with profile SHA + immutable publish/revoke only behind trusted IAM/S2.
- R19.B5 — commit through FormDraftService + PostgreSQL E2E for all negative cases.
