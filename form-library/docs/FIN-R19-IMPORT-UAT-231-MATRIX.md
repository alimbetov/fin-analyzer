# FIN R19 — 231-case import acceptance and reconciliation

**Branch:** `feat/fin-form-library-standalone`  
**Execution state:** TEST SOURCE COMMITTED; MAVEN/POSTGRESQL GREEN NOT YET EVIDENCED. No production release without a real run.

## Scope

The actual system catalog currently defines seven accounting period types and 11 currencies. The XLSX transport supports three presentation styles. The opt-in HTTP integration test exercises the complete cross-product:

**7 period types × 11 active currencies × 3 styles = 231 cases**.

The test uses fresh `F02` instances with `dataSpace=SYNTHETIC`, not real reports. It uses existing controllers, not a hidden database writer. Each case performs:

1. Create draft revision 1 through `POST /api/v1/drafts` with a unique organization/project/scope/actor.
2. Resolve and assert canonical type/code and date range using `PeriodResolver`.
3. Read revision 1 and confirm selected accounting currency and original values.
4. Export the chosen style and locale to FIN XLSX V3 (`REPORT` + `PARAMETERS`).
5. Check the **№** index column is centered, the XLSX schema version is V3, and the value address in `PARAMETERS` points at `C5` for the first field. V3 layout:
   - CLASSIC / EXCEL: A = centered row number, B = localized indicator, C = editable value.
   - DASHBOARD: A/B/C and E/F/G two side-by-side numbered blocks with column D as spacer.
6. Edit the XLSX financial value: `1500,25` for 2-minor-unit currencies, `1500` for 0-minor-unit currencies. Decimal comma is normalized only at the import boundary.
7. Register/pin R19 profile, Preview without persistence and assert `fin_intake` revision count remains 1.
8. Commit exact SHA-bound file, assert new immutable revision 2, and a changed canonical content SHA.
9. Re-read revision 2; confirm changed amount plus unchanged cost and expense inputs, period, currency and canonical SHA.
10. Read revision 1 and confirm the original amount/SHA remain intact.
11. Re-export and compare revision number, SHA, period, currency and display locale in `PARAMETERS`.
12. Re-preview the old file; expect HTTP 409 stale revision and no further writes.

Locales rotate through `ru`, `kk`, `en`, `zh-Hans`; each is checked across the matrix but they do not multiply the 231 base cases. Locale controls titles and captions only, never the financial data contract.

### Supported period cases

| Type | Sample code | Meaning |
| --- | --- | --- |
| D | 2026-11-18 | Day |
| W | 2026-W47 | ISO week |
| DEC | 2026-11-2 | Second ten-day interval of November |
| M | 2026-11 | Calendar month |
| Q | 2026-Q4 | Quarter |
| H | 2026-H2 | Half-year |
| Y | 2026 | Year |

### Currency cases

`KZT, USD, EUR, RUB, CNY, GBP, TRY, JPY, AED, CHF, KRW`.

The test **reads the actual active currency dictionary** and refuses to assert complete coverage if the catalog has drifted from the expected 11 currencies. It respects `minorUnits` (JPY/KRW have 0; the rest have 2).

No exchange rate conversion is involved. Currency is a pinned property of each report instance; a workbook must not convert or overwrite it.

## Run on the local computer

Start from the root of the updated working branch (Java 21 required):

```bash
git checkout feat/fin-form-library-standalone
git pull --ff-only
java -version
mvn -f form-library/pom.xml clean verify
```

Run the full matrix **separately** (explicit opt-in; excluded from ordinary regression to keep feedback fast):

```bash
mvn -f form-library/pom.xml \
  -Dtest=R19ImportMatrixIntegrationTest \
  -Dfin.uat.matrix.enabled=true test
```

**CSV report:** `form-library/target/uat/r19-import-matrix.csv`. Every row is an actual executed period/currency/style case with `PASS` or `FAIL` and a diagnostic. The test fails if any case fails.

For a quick manual smoke using Thymeleaf:

```bash
FIN_FORM_UAT_EDITOR_ENABLED=true mvn -f form-library/pom.xml spring-boot:run
```

Open `http://127.0.0.1:8086/uat/form-editor`. Select one of the seven types, choose a valid period code, one of the active currencies, style and language. Create the F02 instance, edit, save, export, change XLSX, Preview, Commit, then press **Сверить сохранённые значения**. The page compares Preview values with the persisted revision and checks previous SHA/history.

**Never change the period/currency of an already-saved report by editing Excel or switching the UAT controls**: source context is immutable. Create another F02 instance for a new period/currency.

## XLSX V3 compatibility policy

- V3 adds centered report line numbers and moves the values to column C; `PARAMETERS.valueCell` is updated deterministically.
- The importer accepts prior FIN XLSX **V2** according to its old coordinate mapping. It does not reinterpret a V2 sheet using V3 positions. Supported V2 classic/Excel/dashboard labels are preserved; legacy Excel headers `Field key/Value` also remain recognized.
- A manual edit of the numeric index column causes `XLSX_ROW_NUMBER_CHANGED` (422), not an accidental financial data update.
- Both workbook versions still require matching instance, organization, form version, exact source financial SHA and revision. Old workbook after a new commit is stale.

## Negative and release gates

In addition to the base matrix, run `FormXlsxCodecUnitTest`, `FormXlsxExchangeIntegrationTest`, `ImportNumberNormalizerUnitTest`, `VersionedImportProfileIntegrationTest`:

```bash
mvn -f form-library/pom.xml \
  -Dtest=FormXlsxCodecUnitTest,FormXlsxExchangeIntegrationTest,ImportNumberNormalizerUnitTest,VersionedImportProfileIntegrationTest \
  test
```

These cover swapped context, altered currency, stale SHA, invalid numbers, formula injection, row number tampering, metadata binding changes and unchanged import.

The default test datasource is in-memory H2; an independent PostgreSQL Testcontainers verification with full Liquibase 001–018 must pass before declaring production readiness. The UAT page uses caller-provided user/organization IDs without IAM and is **localhost-only**.

### Required evidence from the user's machine

- Exact checked-out commit SHA: `git rev-parse HEAD`.
- `mvn clean verify` summary with zero compilation and test failures.
- The 231-row CSV and test summary.
- PostgreSQL migration and persistence test evidence before marking R19 production ready.

The code committed to GitHub is **not** itself proof of GREEN.
