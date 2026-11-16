# FIN-BE-R1 — Technical Development-Cycle Audit and Hardening

**Date:** 2026-11-16  **Branch:** `feat/fin-r0-r1a`  **Base inspected:** `16a0fe0fb3a726b51af030c9671907de9ddb0ae1`
**Audit scope:** 166-form catalog → source locale/input → V1/V2 XLSX → PostgreSQL draft → confirmed slot/revision → immutable snapshot → XLSX/HTML/PDF render → re-import → export provenance → authorization → CI. This is a technical assessment, not financial-methodology qualification or independent S2 APPROVE.

## Evidence and reproducibility

- 166 catalog codes (122 TYPED_DRAFT, 32 SCHEMA_NOT_APPROVED, 12 EVIDENCE_ONLY), 794 structured fields. All numbers are engineering coverage, **not** independent financial approval.
- Baseline `16a0fe0` passed FIN Backend Domain, including 664 server-issued XLSX outputs in PostgreSQL/Apache POI across four display locales. Full FIN CI was waiting on macOS.
- Confirmed fingerprint and ZIP hardening passed subsequent FIN Backend Domain runs on `a34128e` and `5814ce3` respectively; inspect corresponding GitHub Actions run logs as the source of test counts.
- During this audit a separate contributor committed X3 HTML and X4 PDF (`571b788`) into the same branch, preserving previous commits. First backend run on `571b788` FAILED with 2 failures and 168 errors due unprovisioned `FIN_PDF_CJK_TTF`; never mark that SHA GREEN.
- Later workflow changes install/verify required offline CJK/Kazakh fonts and add HTTP security tests. Recheck exact final HEAD and CI; do not extrapolate older GREEN to newer commits.

## Findings, actual fixes and remaining gates

| ID / priority | Evidence-backed observation | Disposition |
|---|---|---|
| FIN-R1-AUD-01 / P0 | `CatalogIssuedTemplateService.issuePopulated()` previously compared parsed values only to caller-provided rows; forged rows could be recorded against another valid source revision's SHA. | **FIXED IN CODE**: compare parsed normalized fingerprint with immutable revision fingerprint before inserting manifest/receipt. Negative Testcontainers test verifies forgery rejection, no partial rows, and legitimate export success. Commits `37f7363`, `a34128e`. |
| FIN-R1-AUD-02 / P1 | OOXML ZIP check previously permitted macro, embedded-object, external relationship payloads if size and zip paths looked safe. | **FIXED IN CODE**: explicit active part rejection, safe XML relationship inspection with DTD/external entities disabled, case-insensitive duplicate ZIP entry prevention, negative attack fixtures. Commits `293382f`, `5814ce3`. |
| FIN-R1-AUD-03 / P1 | Feature branch generated many redundant full CI runs, including queueing expensive macOS jobs and confusing SHA-specific readiness. | **FIXED IN WORKFLOWS**: concurrency cancels superseded feature-branch pushes only, retaining main, PR and manual runs. Commit `4ae3b79`. |
| FIN-R1-AUD-04 / P0 | New PDF renderer needs locally provisioned Simplified Chinese TTF; workflow omitted installation. Baseline `571b788` had mass PDF test failures. | **FIXED IN WORKFLOWS, NEW CI PROOF REQUIRED**: explicit apt installation and `test -r` for DejaVu and Arphic paths before Maven. Commit `5b79550`. License and MacBook UAT separate release gates. |
| FIN-R1-AUD-05 / P0 functional | `CatalogIssuedTemplateService.upload()` is a Java service, but a generic tenant-authorized HTTP upload/confirm route for 166 exported XLSX forms is not present. | **OPEN**: complete authorized server-issued upload/readback E2E including evidence original where required, then React browser E2E. Do not claim end-user round trip from service tests alone. |
| FIN-R1-AUD-06 / P1 | XLSX has append-only source-bound export receipts. New HTML/PDF endpoints expose signed-in read and response digests but do not persist comparable render receipts. | **OPEN**: design whether HTML/PDF generated outputs need durable audit receipt; if yes, use separate short transaction that does not change financial source. |
| FIN-R1-AUD-07 / P1 | Catalog's typed draft `validate()` accepts only KZT although XLSX Context schema accepts generic ISO-like three-letter currencies. | **OPEN CONTRACT DECISION**: document KZT-only typed intake or qualify additional currencies and test; no implicit exchange-rate conversion. |
| FIN-R1-AUD-08 / P1 | `CatalogDocumentPreflight.verifyPdf()` checks a PDF version header and EOF marker but does not actually parse pages/object structure. This is not a claim of malware scanning or document authenticity. | **OPEN**: independently parse original PDFs (with bounded resources), add malformed/fake-PDF negative tests; retain issuer-authenticity as separate review process. |
| FIN-R1-AUD-09 / P0 release | No independent GitHub S2 APPROVE and no confirmed latest-sha green across all FIN CI jobs, especially after new X3/X4 and font changes. | **OPEN**: require full workflow status SUCCESS on one final SHA and genuine independent review before release; no main merge. |
| FIN-R1-AUD-10 / financial | All 166 generic catalog sources remain unqualified; human translation coverage is RU/KK/EN 9 each, zh-Hans 6 provisional of 794. | **EXPECTED HOLD**: no ACTUAL/APPROVED financial publication merely from exported bytes or renderer SUCCESS. |

## Threats and invariants to preserve

- Scoped JWT `FIN_READ` for previews/HTML/PDF, plus `FIN_SUBMIT` for reusable XLSX issuance. Tenant-specific revision and issued template IDs must never bypass project scope.
- Snapshot and all renderers use one explicit `sourceRevisionId`, source currency, amountScale, period, coverage and immutable normalized values; fixed `sourceRevisionId` required across separate format requests.
- Only server-issued FIN-BE-CATALOG-XLSX-V2 is round-trip compatible with the Java parser; React-local ExcelJS has a different contract. Never assert compatibility without adapter proof.
- No conversion via `double`, silent rounding, fake zeros, invented human financial labels, external HTML/PDF resources, VBA/macros or misleading source approval.
- Document-only reports are registers; original source files are separate authorized evidence, not fabricated numerical values.

## Release decision and next steps

**ENGINEERING_HOLD** until final X0–X5 source-specific evidence is GREEN at a single HEAD, PDF font/license and HTML/PDF Unicode tests pass, 166-form HTTP issued-XLSX reupload is completed if the end-user loop is required, and an independent S2 reviewer approves. **FINANCIAL_QUALIFICATION_HOLD** for the generic source catalog remains regardless of technical status.

Suggested execution order: (1) verify latest X3/X4 CI after PDF font installation, (2) implement secured generic 166-form POST upload + confirmation API and React E2E with actual document originals, (3) PDF original parser and multi-currency contract decision, (4) independent S2 and release-candidate smoke. Every fix must add a reproducing negative test and preserve Flyway checksums and branch isolation.
