# FIN R7 — exhaustive Excel template-to-entity structural smoke

**Branch:** `feat/fin-r0-r1a` · **Release:** DRAFT / HOLD.
**What is proved by this suite when CI is GREEN:** preservation of source columns, typed values, structural identity, and reporting coordinates **before persistence**.
**What it does NOT prove:** an approved financial methodology, accurate real-world accounting, actual user access rights, uploaded original authenticity, PostgreSQL acceptance of all R6 forms or release readiness.

## Exact inventory and supported routes

| Family | Count | Test route |
|---|---:|---|
| TYPED_FIELD_HINTS: 59 DF + 63 SF | 122 | Browser `generateTypedCatalogExcel → edit each field → parseTypedCatalogExcel → DTO parity` |
| Specialized, unqualified C/F/P candidate | 32 | Same route, plus preliminary arithmetic identity checks |
| Total local R6 Excel-editable | **154** | 154 × 3 locale/period/scale variants; compare all 794 source field coordinates |
| Evidence-only D01–D08/T01–T04 | **12** | Refuse fake numeric Excel template |
| Total FormRegistry | **166** | Exactly classified as 154 editable + 12 evidence-only |
| Extension sheets `EX-*` | **30** | Supporting source sheets, not independent FormRegistry templates; refuse fake standalone XLSX |
| Server reviewed XLSX pilot | **5 form codes** | Apache POI: F01/F02/F03/DF-CSH-01/02 plus separate F01-LIQUIDITY version |
| Sector pilot legacy XLSX | **9** | Existing full-field positive test; new per-form currency/period/model mismatch rejection |
| React V5 standalone | **F02** | Existing independent ExcelJS and browser tests; the R6 F02 candidate is **not** this protocol |

Typed and specialized form fields total exactly **794** (618+176). `FIN-CATALOG-XLSX-2` source fields are all visited using their actual code and in-order Excel column; there are no invented approval flags.

## Algorithm

1. Select one `formCode` from 154 editable registry entries.
2. Generate its actual versioned workbook via `generateTypedCatalogExcel(context)`.
3. Fill every declared field `FIN_Catalog!B4..` sequentially using deterministic, **nonempty**, type-safe and field-distinguishable values. Values represent only synthetic source transport. For F01/F03/F04/P01/P03/P04, ensure the already-defined preliminary arithmetic identities are exactly consistent.
4. Serialize the workbook again, including its hidden registry metadata, visible passport and multilingual instructions.
5. Import through production `parseTypedCatalogExcel(buffer, context)`.
6. **Before saving anything**, verify `formCode`, all context attributes (project, language, reporting period/type/coverage, KZT and amount scale), exact field-set cardinality, `rowId`, every canonical decimal/text/date/boolean/period value, and parity with the independent manual validator. Assert `UNQUALIFIED_DRAFT`, `publishable=false`, `persisted=false`.
7. Repeat each form using three different contexts:
   - Russian, MONTH `2026.10`, PERIOD_ONLY, KZT ×1;
   - Kazakh, AS_OF `2026-10-31`, AS_OF, KZT ×1,000;
   - English, YEAR `2026`, YEAR_TO_DATE, KZT ×1,000,000.
8. For **each** of 154 forms, refuse mismatched project, period, amount scale and a workbook whose `_FIN_Metadata` currency is changed to USD. Only KZT is supported by current R6 catalog. These tests demonstrate coordinate integrity, not permission to use every period type for every financial methodology.
9. All 12 evidence-only and all 30 extension-sheet identifiers must fail closed instead of producing a fictional template.

Output: `test-results/fin-template-transfer-smoke.json`, per `formCode` showing verified field count and three variants. `FIN CI` requires exactly 154 verified forms / 794 field coordinates / 3 variants each and uploads artifact `fin-template-transfer-154-pre-save`. One failed form **fails the whole CI gate**; no blanket PASS.

## Java Apache POI (real server parser)

`backend/src/test/java/kz/fin/backend/persistence/PilotXlsxStructuralTransferTest.java` defines **48** dynamic pre-save tests:
6 issuer contracts × 2 currencies (KZT/USD) × 2 scales (1/1,000) × 2 monthly periods (2026-10, 2026-12).

All source fields must reappear exactly once, with their original code, `YearMonth` and exact `BigDecimal` from decimal-as-text input. Fingerprint must be stable. Negative tests cover currency/scale changes, invalid month, forged metadata/version.

**P0 gap explicitly confirmed by source-level contract and regression:** the old `PilotXlsxTemplateService.Context` does NOT contain an expected reporting month, and its workbook metadata does not pin a month. The parser preserves and validates the literal month, but a valid substitution 2026-10 → 2026-11 remains accepted. This is **not** sufficient for a project/period-bound issuance contract. Future version must include the expected period in the server-owned context, bind it to project and source revision, and reject cross-period uploads before intake. Do not break `FIN-PILOT-V1` without a versioned migration.

`FIN CI` includes the Java test as a required Surefire XML report. The existing Testcontainers reviewed-source tests cover persistence and two-person approval independently. This suite itself performs **no database writes**.

## Hardened UI from audit

- `validateCatalogContext` is used for both typed and specialized manual drafts, closing a path where F/C/P candidate numbers could be marked locally valid despite an empty project or impossible period.
- Stale context change clears `busy`; old XLSX parser completions cannot overwrite a new project's preview.
- Document evidence input now rejects size 0 or over 10 MB **before** `file.arrayBuffer()`, and discards stale results if form/context changed.
- `e2e/catalog-intake-r6.spec.ts` explicitly blocks local JSON export for a specialized form missing project code or having an invalid period.

## How to reproduce

```bash
npm ci --no-audit --no-fund
npm run typecheck
npx vitest run src/catalog-intake/template-transfer-smoke.test.ts src/catalog-intake/catalog-excel.test.ts src/sector/excel.test.ts
mvn -B -ntp -f backend/pom.xml -Dtest=PilotXlsxStructuralTransferTest test
npm run test:e2e -- --project=chromium
```

Run the full `FIN CI / acceptance-gate` on a **single SHA** for final machine-green evidence. PostgreSQL Testcontainers require Docker; browser E2E does not replace real Keycloak and two-user server acceptance. MacOS manual UAT and independent economic S2 remain separately OPEN.

## Release verdict

**CODED / SOURCE-INVENTORY VERIFIED / CI RESULTS NOT YET CONFIRMED / FINANCIAL METHODOLOGY HOLD.**

Expected before declaring structural R7 GREEN: a real Actions run link, exact SHA and a nonempty JSON artifact with 154/794/3; Java Surefire report 48 positive test cases and negative tests, source-qualified security Testcontainers results, and browser E2E result. Existing numerical KPI coverage (22 separately golden-tested forms) is not increased merely by an XLSX round-trip.
