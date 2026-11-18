# FIN R19 — strict number normalization for XLSX preview

**Status:** committed, compile/test execution not yet verified.

## Observed failure (uploaded F02 workbook)

Original workbook `fin-F02-EXCEL (2).xlsx` contains:
- `REPORT!B5` netRevenue `88,56` (STRING)
- `REPORT!B6` cogs `99,66` (STRING)
- `REPORT!B7` operatingExpenses `777.66` (STRING)

The original `FormDraftService.normalize` accepts canonical numbers using a decimal period only. Thus comma-formatted values fail at the Preview boundary with HTTP 422.

## Patch

A dedicated `ImportNumberNormalizer` maps an unambiguous decimal comma or period to canonical `.` for MONEY, DECIMAL, INTEGER fields only. The existing financial validator and immutable writer are still authoritative. No modification of `fin_intake`, no fallback rounding, no binary floating-point conversions.

| Input | Canonical output | Outcome |
| --- | --- | --- |
| `88,56` | `88.56` | accepted |
| `99,66` | `99.66` | accepted |
| `777.66` | `777.66` | accepted |
| `1 234 567,89` | `1234567.89` | accepted with checked three-digit space grouping |
| `1,234` / `1.234` | — | 422 AMBIGUOUS_NUMBER |
| `1,234.56` / `1.234,56` | — | 422 MIXED_DECIMAL_SEPARATORS; needs a specific approved ImportProfile |
| `12 34,56` | — | 422 INVALID_GROUPING |

Locale is provided by `PARAMETERS.displayLocale`, validated against `ru`, `kk`, `en`, `zh-Hans`. This narrow FIN XLSX V2 policy accepts comma or dot decimal when unambiguous even if mixed in the same worksheet. It does **not** infer punctuation-based thousand grouping; full locale-specific number patterns belong in R19.B.

Preview returns a structured error `code`, e.g. `IMPORT_FIELD:netRevenue:AMBIGUOUS_NUMBER`. No ticket and no financial revision is created on invalid numeric input.

When the canonical values differ from the saved snapshot, Commit verifies the pinned source revision/SHA, then `FormDraftService.save` writes the next immutable revision with a new canonical content SHA. An unchanged import remains HTTP 422 `XLSX_NO_CHANGES` and creates no revision.

## Verification

```bash
mvn -f form-library/pom.xml -Dtest=ImportNumberNormalizerUnitTest,VersionedImportProfileIntegrationTest,FormXlsxExchangeIntegrationTest test
mvn -f form-library/pom.xml clean verify
```

Run locally using `FIN_FORM_UAT_EDITOR_ENABLED=true`, re-open the saved form, export the **current** file, edit only values in `REPORT`, select file, and click Preview. This cannot be considered GREEN until Maven/CI runs and the PostgreSQL integration gate passes.
