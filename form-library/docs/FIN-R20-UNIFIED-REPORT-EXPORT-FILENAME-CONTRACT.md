# FIN R20 — Unified report export filename contract

**Branch:** `feat/fin-form-library-standalone`  
**Status:** shared naming contract implemented; XLSX integration retained; PDF/HTML renderers in `form-library` **not implemented** and **not claimed as available**. No Maven/CI GREEN is yet recorded for the current SHA.

## Invariant

An export filename is **one deterministic projection of a persisted, immutable, revision-pinned report snapshot**:

```text
FIN_<FORM>_<PERIOD_TYPE>-<PERIOD_CODE>_<CURRENCY>_<STYLE>_<LOCALE>_<FORM_VERSION>_r<REVISION>_<SHA12>.<ext>
```

A financial report whose revision 3 carries canonical content SHA-256 beginning with `a1b2c3d4e5f6` has these names:

```text
FIN_F02_M-2026-11_KZT_EXCEL_ru_FIN-FORM-V2_r0003_a1b2c3d4e5f6.xlsx
FIN_F02_M-2026-11_KZT_EXCEL_ru_FIN-FORM-V2_r0003_a1b2c3d4e5f6.pdf
FIN_F02_M-2026-11_KZT_EXCEL_ru_FIN-FORM-V2_r0003_a1b2c3d4e5f6.html
```

Only extension and media type differ across renderers. The style describes the **requested display layout** and is not a financial value. The four supported locales are `ru`, `kk`, `en`, `zh-Hans`.

### Implementation

- `service/ReportExportFilename.java`: canonical `Identity` contract, `Format.XLSX/PDF/HTML`, `build(identity,format)`, `attachment(identity,format)`, MIME mapping.
- `service/FormXlsxFilename.java`: backwards-compatible facade now delegates to the shared contract. The existing `FormXlsxExchangeService.exportNamed` obtains the context from the persisted `fin_intake` instance/revision, not a client-claimed context.
- `api/FormXlsxExchangeController`: existing XLSX export retains its `Content-Disposition` behavior and `private, no-store`. Its controller was not changed to fabricate PDF or HTML.
- `service/ReportExportFilenameUnitTest`: identical basename across formats, MIME, revision/SHA changes, seven period types, four locales, filename sanitization.
- Existing `FormXlsxFilenameUnitTest` and `FormXlsxExchangeIntegrationTest` remain acceptance requirements.

### Renderer integration contract (future work)

Only after a real HTML/PDF renderer is present:

1. Resolve the caller's access to the requested report through **trusted** organization/project/share authorization. The current local-only controller's caller-supplied `organizationId` is insufficient for partner-facing exports.
2. Read one consistent snapshot: immutable `instanceId`, pinned `formVersionId`, `revisionNo`, full `contentSha256`, `periodType`, `periodCode`, `currency`. Prefer a specifically requested historical revision when appropriate, rather than mixing current and historical data.
3. Resolve the approved style and locale for display only; never alter canonical financial content.
4. Render the requested artifact **from that same snapshot**. PDF must contain actual PDF bytes and `application/pdf`; HTML must contain actual escaped HTML with `text/html; charset=UTF-8`. Never rename an XLSX blob to `.pdf` or `.html`.
5. Construct `ReportExportFilename.Identity` from the server snapshot. Call `ReportExportFilename.build(identity,Format.PDF/HTML)`; set `Content-Disposition: attachment; filename="<result>"`, `Cache-Control: private, no-store`, and use the format's correct content type.
6. Never use the filename as an authorization token or revision proof. Full SHA, immutable revision, and publisher/share permissions are separate integrity and security controls. SHA12 is just a human-facing differentiator, not a signature. Filename components deliberately omit organization UUID, project UUID, personal data, financial amounts and user-supplied report titles.
7. For bank/investor sharing, bind a **separate signed publication manifest** to the selected snapshot and approved export artifact if audit-grade verifiability is required. The artifact itself can have an independent SHA-256 distinct from financial content SHA.

### Compatibility

There is **no XLSX schema migration** in R20: V4 workbook `REPORT`/`PARAMETERS` layout, import preview/commit, canonical values, and pinned financial revision remain unchanged. The same XLSX filename is emitted as before this refactor.

Portable filename tokens are normalized to ASCII `A-Z a-z 0-9 . _ -`, capped in length, and checked for required content. The filename should be treated as metadata only.

### Verification (Windows / IntelliJ Maven working directory: `form-library`)

```powershell
mvn -Dtest=ReportExportFilenameUnitTest,FormXlsxFilenameUnitTest,FormXlsxExchangeIntegrationTest test
mvn clean verify
```

For comprehensive F02 import on H2, run the separately opt-in 231-case matrix:

```powershell
mvn -Dtest=R19ImportMatrixIntegrationTest -Dfin.uat.matrix.enabled=true test
```

A real PostgreSQL Testcontainers/PDF/HTML artifact qualification remains a future gate, not proof supplied by these tests.
