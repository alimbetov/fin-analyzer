# FIN-BE-R1-X — Code-backed technical design review and specification hardening

**Date:** 2026-11-16  
**Branch inspected:** feat/fin-r0-r1a  
**Source baseline:** 67721f2033d75effd9d5c79dcb6eb64e470dac57  
**Review type:** independent-from-draft critical design assessment by assistant; **NOT** an independent GitHub S2 approval  
**Implementation status:** no X0–X5 functionality implemented by this documentation commit; code, automated tests and release remain to be completed.  
**Updated authoritative specification:** [FIN-BE-R1-X-FOUR-LANGUAGE-EXPORT-TZ.md](FIN-BE-R1-X-FOUR-LANGUAGE-EXPORT-TZ.md)

## Evidence reviewed

- backend/src/main/java/kz/fin/backend/intake/CatalogXlsxRoundTripService.java: V1/V2 three-sheet Apache POI formats, strict metadata and string cell parser, max 2 MB, source locale ru/kk/en, V2 issued ID.
- backend/src/main/java/kz/fin/backend/intake/CatalogIssuedTemplateService.java: issue(project/context), preview/upload, persistent issued-template identity.
- backend/src/main/java/kz/fin/backend/intake/CatalogXlsxRevisionService.java: latest_revision, per-slot confirmed history, document digest in duplicate detection.
- backend/src/main/resources/db/migration/V20261116_10__catalog_xlsx_draft_roundtrip.sql, V20261116_11__catalog_xlsx_revision_and_document_types.sql, V20261116_12__catalog_xlsx_issued_template_manifest.sql: draft/revision issuance data; input_locale tri-language.
- backend/src/main/java/kz/fin/backend/security/FinanceAccessPolicy.java: org-scoped fin_access, FIN_READ and FIN_SUBMIT.
- backend/src/main/resources/fin/form-field-presentation-review-r6.tsv: 794 field rows, RU=9, KK=9, EN=9, zh-Hans=6; nine PROVISIONAL_NOT_S2, 785 PENDING_TRANSLATION.
- src/catalog-intake/catalog-schema.ts, field-presentation-r6.ts and catalog-excel.ts: zh-Hans display dictionary, tri-language input locales and React ExcelJS workbook structure distinct from Java server V2.
- backend/pom.xml: Java 21, Spring Boot 3.5.7, Apache POI 6.0.0; no dedicated current server PDF renderer dependency discovered.
- Baseline GitHub Actions: FIN Backend Domain successful; full FIN CI for this HEAD not established as green at review time.

## Findings, dispositions, and explicit remaining work

| Finding | Risk | Review disposition in revised TZ | Status |
|---|---|---|---|
| R1X-S2-01 (P0) | zh-Hans presentation was conflated with ru/kk/en input_locale and immutable issued V2 metadata | X0 splits displayLocale/sourceInputLocale; no blind SQL CHECK widening; Chinese display independent of ingestion | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-02 (P0) | Server V2 Excel and React-local FIN-CATALOG-XLSX-2 have incompatible sheet/schema contracts | X2 requires real server-issued parseIssued round-trip; no false browser interchangeability claim | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-03 (P0) | Picking newest draft would incorrectly supersede last confirmed revision | X1 requires catalog_xlsx_slot.latest_revision with full organization/project/coordinates | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-04 (P0) | Three independent latest requests can return different revisions during concurrent confirmation | X1/API introduce exact sourceRevisionId reuse and expectedRevisionId conflict | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-05 (P0) | Misrepresenting 4-locale label coverage or unqualified financial data | X0 reports real 9/9/9/6 labels + PROVISIONAL/MISSING; status visible in all outputs | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-06 (P0) | Precision loss if stored 38/12 decimals are coerced to Excel numeric/double | X1/X2 canonical strings, BigDecimal-only validation, Excel TEXT cells, independent golden values | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-07 (P0) | Cross-tenant leakage via raw revisionId/issued ID and token claims | X1/API require composite scope checks, FIN_READ, extra FIN_SUBMIT for XLSX issuance | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-08 (P0) | Unsafe HTML and HTML→PDF SSRF/script/font sources | X3/X4 escape inputs, offline renderer, CSP, font-license validation, bounded resources | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-09 (P1) | GET render could persist issue receipt and violate read-only semantics | GET /preview read-only, POST /exports for all generated artifacts, atomic XLSX receipt | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-10 (P1) | Receipt may falsely claim file delivered even if response never reached client | Receipt state GENERATED only; response delivery not asserted | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-11 (P1) | Historical schema and document digest drift can silently corrupt rendered meaning | X1 requires version-aware compatible schema, source fingerprint and digest verification | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-12 (P1) | 1,992 tests could be simulated by static matrix without real files | X5 requires executed per-combination render, actual parser/DB proof and independent JSON-vs-JUnit verifier | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-13 (P1) | PDF created successfully but Chinese/Kazakh glyphs missing or table values clipped | X4 real PDF parser/text extraction plus visual/raster smoke, CJK fonts and pagination | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-14 (P1) | Previously normalized blanks/nulls might have already lost source distinctions | X1 promises only distinctions actually persisted; cannot recreate lost source values | RESOLVED_IN_SPEC, CODE_PENDING |
| R1X-S2-15 (P1) | Tests mistake 166 forms for 166 financially qualified models | Separate structural 166 and 154/12 proof from finance semantic qualification, explicit HOLD | RESOLVED_IN_SPEC, CODE_PENDING |

## Scope and release assessment

This review amends **the specification**, not application code. Do not claim GREEN for FIN-BE-R1-X because its implementation and new tests have not been executed. Backend-domain CI success at baseline is historical baseline evidence only.

**Design assessment:** technically coherent after corrections; READY_FOR_IMPLEMENTATION under staged X0–X5 gates.  
**Independent S2 APPROVE:** NOT OBTAINED; request a different reviewer on implementation PR and record actual outcome.  
**Engineering release:** HOLD until 166×4×3 actual execution evidence, security tests and required green CI on one SHA.  
**Financial release/approved source:** HOLD for unqualified catalog forms regardless of renderer success.

## Required reviewer checklist after code implementation

1. Validate selected revision and tenant join at SQL and REST boundaries; check concurrent confirm/query interleavings.
2. Validate zh-Hans display vs source input contract across old V2 XLSX and database CHECK constraints.
3. Confirm XLSX is re-importable with actual server parser; confirm React-local sheet contract is not misrepresented.
4. Check Decimal(38,12) value parity, no roundoff, no locale-sensitive mutation or fabricated NULL/zero.
5. Inspect authorization, CSP, HTML escaping, offline PDF renderer, ZIP budgets, CJK/font license.
6. Open sample XLSX/PDF/HTML artifacts; verify extracted text and no clipping, including wide tables.
7. Independently inspect Testcontainers/JUnit JSON evidence for all 1,992 unique combination keys.
8. Confirm same SHA across commit, test reports and CI gate; independent GitHub S2 review recorded.
9. Preserve existing five-form legacy and F02 V5 tests; all schema migrations additive, main unchanged.
