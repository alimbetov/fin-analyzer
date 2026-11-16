# FIN-BE-R1-X — Four-Language Financial Report Snapshot & XLSX/HTML/PDF Export

**Document status:** TECHNICAL SPECIFICATION / CODE IMPLEMENTATION NOT CLAIMED  
**Review status:** code-backed technical design review completed; independent S2 approval PENDING  
**Date:** 2026-11-16  
**Repository:** https://github.com/alimbetov/fin-analyzer  
**Only working branch:** feat/fin-r0-r1a  
**Reviewed baseline HEAD:** 67721f2033d75effd9d5c79dcb6eb64e470dac57  
**Related review:** [FIN-BE-R1-X-TECHNICAL-REVIEW-2026-11-16.md](FIN-BE-R1-X-TECHNICAL-REVIEW-2026-11-16.md)

## Codex mandate and hard restrictions

Act as Principal Software Architect, Senior Java/Spring/PostgreSQL Engineer, Financial Data Engineer, Excel/PDF/HTML Renderer Engineer, i18n Engineer, Application Security Engineer and QA Automation Architect. Implement **only** these stages, in order:

- **X0 — Four-Language Catalog**
- **X1 — Financial Report Snapshot**
- **X2 — Excel Data Export**
- **X3 — HTML Report**
- **X4 — PDF Report**
- **X5 — Acceptance & Regression**

Start by refreshing the current branch HEAD; the SHA above is a review baseline, **not** permission to overwrite subsequent commits. Inspect git status and existing working changes; preserve concurrent work. **Do not create branches, merge, rebase, force-push, alter or push to main.** Work and commit only to feat/fin-r0-r1a. Historical Flyway migration files must not be rewritten; new schema changes are additive, versioned migrations. Do not rewrite the five-form reviewed intake, FactSet publication, legacy F02 V5 or Keycloak/IAM. Do not create a parallel 166-form catalog or claim that generic catalog drafts are approved financial facts.

Test execution evidence and independent S2 review are required to claim acceptance. Compiler success, static metadata, generated empty files or a Codex self-review alone do **not** constitute acceptance. If a tool/runner is unavailable, report NOT VERIFIED instead of inventing results.

## 1. Source-backed baseline, scope, and actual limitations

At reviewed HEAD, the server catalog enumerates exactly **166 form codes**: 122 TYPED_DRAFT, 32 SCHEMA_NOT_APPROVED and 12 EVIDENCE_ONLY. The structured forms have **794** catalog field slots. The catalog XLSX path uses Apache POI, FIN-BE-CATALOG-XLSX-V2, exactly three sheets in order: Instructions, FIN_Data and _FIN_Metadata (VERY_HIDDEN), with ISSUED_TEMPLATE_ID. The existing server loader parses literal STRING cells and rejects formulas/errors. The existing *React-local* ExcelJS file uses a **different** FIN-CATALOG-XLSX-2 layout and FIN_Catalog data sheet. **These two workbooks are not interchangeable.** Scope X2 round-trip proof to the server-issued FIN-BE-CATALOG-XLSX-V2 format; do not misleadingly claim React-local compatibility. If integrating the local studio, do so explicitly with a separately tested adapter, not silent format conversion.

Database source coordinates include organizationId, projectId, formCode, reportingBasis, periodType, periodCode, coverage, currency, amountScale. The latest confirmed catalog revision is fin_intake.catalog_xlsx_slot.latest_revision joined to fin_intake.catalog_xlsx_revision; the corresponding unqualified original is in fin_intake.catalog_xlsx_draft. A newer draft upload is **not** necessarily the latest confirmed revision. Existing catalog confirmations have the state CONFIRMED_UNQUALIFIED_DRAFT, **not** APPROVED or ACTUAL. Only the distinct reviewed intake pipeline may qualify financial facts.

Localization baseline from backend/src/main/resources/fin/form-field-presentation-review-r6.tsv: 794 field rows, 9 filled RU, 9 KK, 9 EN, 6 zh-Hans; 9 PROVISIONAL_NOT_S2 entries, 785 PENDING_TRANSLATION. These are facts of the reviewed baseline, not a target to assert hard-coded after code changes. Backend intake Context and PostgreSQL issued-template input_locale permit ru/kk/en only; React presentation registry already mentions zh-Hans, but the React capture locale currently excludes it.

The FIN Backend Domain job at baseline is GREEN; **the full FIN CI on that SHA must be checked again before declaring an all-workflow pass.** This document itself is not proof of implementation.

### Business objective

A user with valid organization-scoped authorization chooses a form, reporting coordinates and display locale, selects its **latest confirmed** or an **exact historical** catalog revision, and obtains a report from the same immutable source data in **XLSX, HTML or PDF**. All formats must be faithful representations of a **single resolved FinancialReportSnapshot**. No invented totals, silent currency conversions, scale changes, zero-filled nulls, fabricated translations or implied finance approval.

Successful rendering is not financial-methodology qualification.

## 2. Cross-stage invariants

1. **Tenant isolation:** every lookup is bound to verified organizationId and projectId, and every joined draft/revision belongs to the same scope. No lookup by bare revisionId that bypasses tenant checks.
2. **Financial data fidelity:** retain exact canonical decimal strings (up to existing precision 38 and scale 12), row identifiers, field identifiers, source order, currency, amountScale and coverage. BigDecimal for numeric checks; never float/double for MONEY/DECIMAL.
3. **Snapshot consistency:** the selected sourceRevisionId is immutable for rendering. Separate requests for /latest may resolve different revisions if the database changes: returning three files with the **same contents** requires the client to supply the **same explicit sourceRevisionId** from preview or a server-issued source selection reference. Do not falsely promise cross-request atomicity.
4. **Status honesty:** CONFIRMED_UNQUALIFIED_DRAFT must be visibly marked as unqualified in all formats. No ACTUAL/APPROVED stamp or signed-audit assertion.
5. **Translation honesty:** missing field translations fall back to fieldCode, with MISSING status. PROVISIONAL_NOT_S2 is never APPROVED. UI/catalog readiness is separate from semantic financial qualification.
6. **Machine/display distinction:** displayLocale changes labels only; sourceInputLocale controls parsing and canonicalization of previously uploaded data, and cannot be silently rewritten. Never treat changing display locale as a new source revision.
7. **No side effects from read-only preview:** generating a reusable issued XLSX and registering an export is an explicit command; no write-on-GET.
8. **Safe outputs:** no executable formula, script, macro, external URL/resource, remote font, SSRF, raw HTML injection or unsafe file path. PDF renderer must operate offline and be bounded.
9. **Version compatibility:** old FIN-BE-CATALOG-XLSX-V1 and issued V2 intake contracts and historic Flyway checksums remain valid.

## X0 — Four-Language Catalog

### X0.1. Locale model and boundaries

Introduce canonical **displayLocale = ru | kk | en | zh-Hans** consistently in the shared presentation API, localization service, React report UI and export renderers. Normalize only documented aliases at HTTP boundary (prefer strict BCP-47 zh-Hans); never use zh or zh-CN silently as an arbitrary fallback.

**Do not globally widen SOURCE input locales as a shortcut.** On reviewed baseline, CatalogXlsxRoundTripService.Context, CatalogCaptureRegistry and issued-template input_locale are tri-language. A Chinese **display** of a report that originated from ru/kk/en must not mutate the saved source locale or invalidly rewrite the V2 _FIN_Metadata. First implement zh-Hans as a distinct rendering locale. If extending source **input** support to zh-Hans is actually required for editing new Chinese-origin workbooks, implement and test it as an explicit, backward-compatible subchange including canonical decimal rules, React import/export, Java parser and additive PostgreSQL CHECK changes. Never alter issued-template input_locale on an already issued workbook. A Chinese display export may retain original sourceInputLocale in V2 metadata while placing Chinese labels on the Instructions sheet.

Define and test sourceInputLocale/displayLocale separation and conversion rules. Preserve original source values such as textual identifiers, mixed-language text, dates and precision-exact decimals. Localized visual decimal separators do not change canonical stored numbers.

### X0.2. Field/title dictionaries

Use form-field-presentation-review-r6.tsv as the authoritative **review inventory**, not an approved dictionary. Reconcile all 794 field keys across CatalogCaptureRegistry and SpecializedSchemaCandidateRegistry. Include four independent display slots and explicit statuses per key/locale:

- APPROVED — only with auditable actual review authorization/evidence.
- PROVISIONAL — existing unapproved proposal.
- MISSING — empty or not reviewed.

Do not invent 794 human Chinese accounting translations, and do not mark existing PENDING_TRANSLATION as APPROVED. A label missing for locale returns an escaped fieldCode and status MISSING; warn that localized terms have not been verified. Existing source-specific F02 V5 translations, if used, must remain version-scoped and not be copied to unrelated form codes.

Do **not** hard-code unit labels as KZT: derive currency and amountScale from snapshot, while preserving unitPolicy=PENDING_UNIT_REVIEW where applicable. Field requiredness and economic semantics are not inferred from translation text.

### X0.3. Tests and evidence

- Strictly verify 166 unique codes, 794 field keys and four presentation slots.
- Verify Chinese labels and help text work where actually populated, missing labels fall back to fieldCode.
- Verify PROVISIONAL never appears as APPROVED and Chinese coverage is reported numerically.
- Verify displayLocale is independent of original ru/kk/en inputLocale and that stored numeric values do not change.
- Verify invalid locale/ambiguous decimal conventions are rejected safely.
- Verify existing tri-language ingestion and local Excel tests remain GREEN.
- Generate measured locale-coverage evidence; do not report four-language catalog as fully translated.

**X0 gate:** 4 render/display locales supported, no fabricated translations, original source locale contracts intact.

## X1 — Financial Report Snapshot

### X1.1. Service and immutable DTO

Implement a single read-only **FinancialReportSnapshotService**. Renderers must not query the database, infer a new latest revision or compute finance values.

Suggested DTO (adjust to existing schema after code audit):

~~~java
record FinancialReportSnapshot(
    UUID organizationId, UUID projectId,
    String formCode, String captureMode,
    String reportingBasis, String periodType, String periodCode,
    String coverage, String currency, int amountScale,
    UUID sourceRevisionId, int revisionNumber, UUID sourceDraftId,
    String normalizedFingerprint, String schemaFingerprint,
    String documentSha256, String sourceState,
    List<ReportRow> rows, List<ReportField> schemaFields
) {}
record ReportRow(String rowId, Map<String, String> canonicalCells) {}
record ReportField(String fieldCode, String fieldType, int schemaOrdinal) {}
~~~

Make nested maps/lists deep immutable; preserve row identity and schema order deterministically. Distinguish missing, blank and explicit zero **where source representation supports that distinction**; do not invent distinctions already lost by an older normalizer.

Locale is renderer context, not part of data snapshot identity. Keep a separate presentation version/fingerprint.

### X1.2. Revision selector

Request includes organizationId, projectId, formCode, periodType, periodCode and optional disambiguators reportingBasis, coverage, currency, amountScale.

- LATEST_CONFIRMED: resolve exactly **one** fin_intake.catalog_xlsx_slot by the complete coordinates and join on r.revision_number=s.latest_revision. Treat latest_revision=0 as no confirmed source.
- EXACT_REVISION: accept sourceRevisionId or revisionNumber **only together with the organization/project/form/context** and confirm membership.
- Ambiguous slot: FIN_EXPORT_AMBIGUOUS_CONTEXT, HTTP 409, without leaking foreign tenant coordinates.
- No selected revision: FIN_EXPORT_REVISION_NOT_FOUND, HTTP 404.
- Client-supplied expectedRevisionId with a newer current head: FIN_EXPORT_REVISION_CHANGED, HTTP 409.

Use a coherent SQL query / transaction isolation such as REPEATABLE READ where necessary; ensure coordinates, revision, rows and original SHA are from the same consistent read. Capture selected sourceRevisionId once and render from that snapshot.

### X1.3. Stored-data integrity and schema drift

- Join and verify organization and project identity on each table.
- Confirm source revision state is CONFIRMED_UNQUALIFIED_DRAFT and draft is UNQUALIFIED_DRAFT.
- Verify schemaFingerprint against a versioned compatible schema, not blindly today's current schema. Historic schema drift must yield a typed error or use a fully proven compatibility path.
- Verify persisted original XLSX/document digests where original bytes are accessed, and normalized fingerprint using a shared canonical algorithm. Avoid duplicating an approximate hash computation across services.
- No source -> 404, corrupt persisted data -> controlled 409/500 depending cause, **not** an empty report presented as success.
- Document-only forms expose verified registry entries and original document metadata; they do not fabricate numeric cells.

### X1.4. Data equality

MONEY/DECIMAL are canonical decimal strings end-to-end; BigDecimal may validate and compare but never modifies authoritative stored text without an explicit contract. Preserve amountScale (1, 1000, 1000000), currency and coverage. No FX, inferred calculations, rounding, subtotal generation or derived ACTUAL.

A **snapshot content fingerprint** should be stable for equal data: use a versioned, unambiguous canonical encoding of context, ordered fields/rows, value presence and source digests. Include sourceRevisionId in provenance metadata while distinguishing source identity from content equality. Verify canonical fingerprint and provenance separately.

**X1 gate:** same selected revision yields byte-logically equivalent source values under all locales/formats, tenant-safe and repeatable.

## X2 — Excel Data Export

### X2.1. Server XLSX renderer

Implement **CatalogXlsxExportService** using Apache POI and **existing server-issued FIN-BE-CATALOG-XLSX-V2**, not React ExcelJS FIN-CATALOG-XLSX-2. Reuse the existing catalog, column order, validation, issued manifest schema and security.

Maintain **exactly**:
1. Instructions
2. FIN_Data
3. _FIN_Metadata (VERY_HIDDEN)

Preserve original authoritative sourceInputLocale for V2 parser metadata. Do not add arbitrary metadata rows, sheets or columns forbidden by parseIssued(). Keep ISSUED_TEMPLATE_ID bound to the currently issued template/project/context. Any source revision or export ID extra information goes in compatible Instructions content and the server-side export receipt, **not** by breaking the parser. Preserve safe cell budgets (server 2 MB compressed / 200 rows and existing ZIP limits); reject oversized reports instead of truncating.

Populate every persisted row, deterministic rowId, complete schema field headers, and exact canonical values. Do not emit fake zeros for blank/unset cells, or dummy rows pretending to be source data.

### X2.2. Excel precision and injection

For canonical MONEY/DECIMAL of up to precision 38/scale 12, write lossless STRING cells with an explicit Excel TEXT ("@") style. Do not convert through float, double or Excel numeric serialization. Test negative/high-precision values and leading zeros in REF/CODE. Escaped literal strings starting with =, +, - or @ must never become formulas; semantic validation of reference fields remains in place. Disallow macros, external links, unsafe ZIP entries and formula cells.

Localize Instructions and optional non-semantic cell comments rather than fieldCode headers. Do not allow locale to change machine ids.

### X2.3. Issuance and atomicity

XLSX must be reusable for edit-and-upload. Generate a **new** issuedTemplateId and ensure issue manifest matches organization/project/source context; it cannot reuse a template bound to another project or period. Design a safe transaction boundary: build bytes/validate workbook before committing successful issuance/receipt where feasible; commit issued manifest and successful export receipt atomically. Avoid a successful record when rendering fails. Recognize that HTTP disconnect after server generation does not prove client delivery: receipt state must say GENERATED, not DELIVERED.

Download must not automatically confirm a new draft revision or enter approved FactSet.

### X2.4. Round-trip acceptance

Test PostgreSQL -> snapshot -> issued XLSX -> real Apache POI open -> parseIssued() -> normalized equality **for each of 166 codes**. For 12 EVIDENCE_ONLY forms compare document registry data; original binary evidence is accessed **only** through an independently authorized mechanism, not secretly bundled in XLSX. After user changes an editable value, upload produces a new unqualified draft, not a new approved fact.

**X2 gate:** genuine populated 166/166 server-issued XLSX round-trips, exact source values, correct metadata and no security regression.

## X3 — HTML Report

Implement a server-side **FinancialReportHtmlRenderer** taking FinancialReportSnapshot + presentation dictionary; it is not an independent data source.

HTML content:
- Organization/project identification from trusted context.
- Form code/name, periodType/periodCode/coverage, reportingBasis, currency, amountScale.
- Exact sourceRevisionId/revisionNumber, source state, generation timestamp.
- Prominent UNQUALIFIED mark and translation readiness where applicable.
- Complete rows, codes, safe displayed labels, values, and separate document register for EVIDENCE_ONLY.
- Responsive tables, wide-table horizontal scrolling, print CSS, deterministic output for a given snapshot, locale and renderer version (timestamps passed explicitly).

**Security:**
- All user/stored text rendered with context-sensitive HTML escaping, no raw HTML or Markdown interpolation.
- No active scripts, event handlers, forms performing actions, iframe, remote CSS/images/fonts, third-party pixels or generated links accepting arbitrary URLs.
- Strict CSP, appropriate style hash/nonce or safe static stylesheet; X-Content-Type-Options: nosniff; Cache-Control: no-store; security headers on error paths too.
- Avoid React dangerouslySetInnerHTML with untrusted strings. React integration, if included, must use safe DOM rendering. No broad UI redesign.
- Bounded size and escaping against XSS, attribute injection and Unicode bidi spoofing where relevant; do not silently truncate monetary values.

Test HTML with a real HTML parser and browser smoke, including Chinese/Kazakh labels, long rows, tags, quotes, HTML entities and malicious TEXT. Check every exported canonical value against snapshot, accounting for separately documented display formatting.

**X3 gate:** all 166×4 HTML render combinations, safe, exact source values, no active or remote content.

## X4 — PDF Report

Implement **FinancialReportPdfRenderer** from the same immutable snapshot and trusted local presentation model, preferably through safe HTML-to-PDF. Before selecting dependencies, record a short ADR covering Java 21/Spring compatibility, commercial license, CJK/kazakh fonts, CSS pagination, memory/resource budget and offline rendering. No requirement to install a headless browser if it adds avoidable attack surface.

**Requirements:**
- All four locales, including full Kazakh Cyrillic and zh-Hans glyphs.
- Only legally distributable, locally bundled or system-provisioned fonts, with embedding rights verified; no external font fetch.
- Title/status/organization/project/form/revision/period/currency/scale in visible report passport.
- Repeated table headers, page numbering, controlled page breaks, landscape/column pagination for wide tables; **no clipped financial values or missing columns**.
- UNQUALIFIED status visible; no fabricated signature, auditor approval, PDF/A claim or legal certification.
- Offline renderer: reject file://, HTTP(S), data-URL payloads of unbounded size, script, entity, CSS remote imports and arbitrary local path references. Limit object/DOM/image/font/page/time/heap budgets.
- Validate generated bytes with an actual PDF parser. Verify text extraction, all key figures, and RU/KK/EN/zh-Hans characters. Include visual screenshot/raster smoke for sample multi-page and CJK layouts; PDF parsing alone cannot prove glyph quality.

Document registers contain document metadata only; do not embed arbitrary uploaded originals as executable attachments.

**X4 gate:** all 166×4 PDF render combinations produced from real form snapshots, parseable, bounded, printable and content-accurate, with independent Unicode/rendering tests.

## X5 — Acceptance & Regression

### X5.1. Actual test matrix

**166 forms × 4 display locales × 3 formats = 1,992 structural rendering cases** (not 1,992 finance-qualified reports). Build parameterized/dynamic tests with explicit independent per-case PASS/FAIL identity (formCode, locale, format) and **real output bytes/HTML**. For all 166 forms at least one real PostgreSQL Testcontainers readback of a confirmed revision must precede renderer tests; do not count catalog lookup alone as output proof.

Distinguish:
- 154 structured forms: field and row value equality.
- 12 documentary forms: document register equality and separately verified original metadata/digest.
- Four **presentation** languages: render works; NOT a claim all human labels are translated.
- One consistent snapshot per selected sourceRevisionId; compare format outputs against that snapshot, not text-vs-text between renderers as the sole oracle.

### X5.2. Golden and negative tests

Use hand-authored independent golden fixtures, not expected results from the same renderer/canonicalizer:
- 0, explicit empty, missing, negative, positive, high precision and 38-digit/12-scale decimals within current domain restrictions.
- Leading zeros for codes and refs; Unicode Cyrillic/Kazakh/Chinese; giant/small magnitudes and locale display separators.
- Formula-like TEXT, markup injection, URL injection, bidi controls, oversized rows, PDF font/glyph fallbacks.
- Illegal locale, invalid/missing revision, ambiguous context, stale expectedRevisionId, fingerprint mismatches, rejected schema drift.
- Cross-tenant organization/project/revision/issuedTemplateId attempted access; JWT absent, wrong fin_access, FIN_READ-only request attempting XLSX issuance where FIN_SUBMIT is required.
- Two concurrent confirms while rendering; verify selected revision and metadata never mix.
- 166 output generation and full 3-format data parity at fixed sourceRevisionId.
- Negative issues must assert no partial successful export receipt and no newly approved/FACT rows.
- Backend legacy 5/5 pilot regression, F02 V5, existing ingestion, Flyway clean+upgrade and existing security tests.

### X5.3. CI fail-closed evidence

Add a dedicated mandatory CI gate **FIN_BE_R1_X_EXPORT_ACCEPTANCE** to existing workflows without weakening current checks. Persist:

backend/target/fin-evidence/fin-be-r1-x-export-coverage.json

Each actual test output records schemaVersion, gitSha, formCode, displayLocale, format, sourceRevisionId, sourceState, snapshotFingerprint, artifactDigest (for binary outputs), generated, validated, valuesEqual, translationStatus, testCaseId, verdict. Independent verifier reads the actual JUnit XML and coverage JSON and compares to the committed 166 form codes, 4 locales and 3 formats. Require **exactly 1,992 unique combinations**, no missing/duplicate tests, no SKIPPED/UNIMPLEMENTED/NOT_RUN. Verify test count and report SHA equals workflow SHA. Keep translation coverage and financial method qualification in **separate** fields, not rolled into structural PASS.

Use real PostgreSQL 16 Testcontainers and actual Apache POI/PDF/HTML parsers. Where 1,992 heavy PDF cases exceed CI budget, optimize fixtures and runner strategy **without reducing coverage**, e.g. reuse immutable DB fixtures per form, parallelize boundedly, and separate presentation/structural validation from deep rendering/visual smoke.

Suggested commands:
~~~sh
node scripts/check-flyway-versions.mjs
mvn -B -ntp -f backend/pom.xml verify
npm test
npm run build
~~~

Check actual package scripts before running; do not claim a command executed if it failed or was unavailable. If introducing a new evidence verifier, run it **after** Maven verify, and upload artifacts with if-no-files-found:error.

### X5.4. Runtime guards and acceptance

Set measured and configurable render budgets (rows, output bytes, PDF pages, heap/concurrent tasks, wall time); do not invent production load numbers. Run at least one local Mac/browser PDF CJK and Excel real-client smoke if environments permit; otherwise mark as an explicit release HOLD, not PASS.

Must present:
1. Baseline and final commit SHA, diff and file list.
2. Each X0–X5 gate result with command, PASS/FAIL/SKIP and actual logs.
3. Exact structural 166×4×3 matrix evidence and per-locale translation counts.
4. Cross-format financial value parity from independently specified golden expectations.
5. Real tenant/security results and schema/legacy regression.
6. Dependency and CJK font-license ADR.
7. Independent S2 review by a genuinely separate reviewer; model self-review is **not** independent APPROVE.
8. Remaining gaps and explicit RELEASE decision.

Acceptable status vocabulary:
- ENGINEERING_ACCEPTED: all technical gates and genuinely independent review complete.
- ENGINEERING_HOLD: any required proof/review missing.
- FINANCIAL_QUALIFICATION_HOLD: mandatory for unqualified catalog sources regardless of engineering result.

## 3. API contract

Prefix: /api/v1/financial/organizations/{organizationId}/projects/{projectId}/forms/{formCode}/exports

**GET /preview**: read-only, FIN_READ; query periodType, periodCode, optional basis/coverage/currency/amountScale, displayLocale; returns selected sourceRevisionId, revisionNumber, normalizedFingerprint, schema fingerprint, source state, output availability, translation coverage. If coordinates match more than one slot, HTTP 409. No mutation, no generated issuedTemplate.

**POST /**: explicit report generation/receipt with body:
~~~json
{
  "format": "XLSX",
  "displayLocale": "zh-Hans",
  "selector": { "mode": "EXACT_REVISION", "sourceRevisionId": "UUID" },
  "context": {
    "periodType": "MONTH",
    "periodCode": "2026.10",
    "reportingBasis": "MANAGEMENT",
    "coverage": "PERIOD_ONLY",
    "currency": "KZT",
    "amountScale": 1000
  },
  "expectedRevisionId": null
}
~~~

Modes: LATEST_CONFIRMED and EXACT_REVISION. EXACT_REVISION binds to organization/project/form and context; do not accept unscoped UUID lookups. LATEST_CONFIRMED optional expectedRevisionId supports optimistic consistency; if absent, include actual selected sourceRevisionId in response metadata. To obtain multiple formats from **one** revision, call preview once and then render each format using EXACT_REVISION and the same UUID.

Content types:
- XLSX: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet; attachment.
- HTML: text/html; charset=UTF-8; inline (strict CSP).
- PDF: application/pdf; inline or attachment as explicit option.

All return safe Content-Disposition filename, Cache-Control:no-store, X-Content-Type-Options:nosniff, source revision ID and export ID headers (safe non-sensitive IDs only). Use RFC 5987 compliant filename only from validated codes/periods, never user-supplied arbitrary paths.

Authorization: FIN_READ for preview/HTML/PDF; for XLSX issuing a reusable upload template require **FIN_READ plus FIN_SUBMIT** (or a newly reviewed narrower permission if already present). Actor comes only from validated JWT. Never permit one org's token to probe another org via detailed error text.

Errors: invalid context/locale/format 400; missing scoped source 404; ambiguous slot/stale expected revision/schema incompatibility 409; unauthenticated 401; unauthorized 403 or non-disclosing 404; measured render budget 413/422; genuine internal failures 500. Document exact HTTP mapping in tests. Do not accidentally use HTTP GET to write an audit/issued-template record.

**Audit receipt** (new additive migration if required): exportId, organizationId/projectId/formCode, sourceRevisionId/sourceDraftId, canonical snapshot hash, presentation version, displayLocale, format, actor, createdAt, artifactSha256, optional issuedTemplateId, state GENERATED. The generated record does not prove network delivery. For XLSX, manifest + receipt must commit atomically, after output bytes are generated and validated; refactor existing issue service minimally if needed. For HTML/PDF, render from a frozen snapshot; persisted receipt is a separate short transaction and must not alter finance source rows. Repeated render requests may produce new receipt IDs but must never generate data revisions.

## 4. Explicit out-of-scope boundaries

- No semantic approval of 32 candidate schemas, provisional units or 794 labels.
- No automatic 166-form ACTUAL/FactSet publication.
- No conversion of XLSX documentation into live financial analytics.
- No external tax document ingestion change.
- No universal translation service or AI-generated accounting terminology.
- No redesign of React-local FIN-CATALOG-XLSX-2 intake.
- No new authentication provider, Keycloak schema redesign or main merge.
- No PDF/A, legally signed report or certified audit claim.
- No automatic currency conversion, formula inference or cross-period aggregation.

## 5. Implementation sequence and gates

| Stage | Required deliverable | Gate before next stage |
|---|---|---|
| X0 | Four display locales; translation-status inventory; source/display locale isolation | i18n tests and tri-language regressions GREEN |
| X1 | Scope-safe immutable PostgreSQL snapshot and revision selector | Testcontainers and adversarial revision/tenant tests GREEN |
| X2 | Populated issued V2 XLSX, per-form round-trip, atomic issuance | 166 server XLSX cases + golden precision tests GREEN |
| X3 | Offline/escaped HTML from same snapshot | 166×4 HTML cases + XSS/browser tests GREEN |
| X4 | Safe CJK-capable printable PDF from same snapshot; dependency ADR | 166×4 PDF cases + extraction/visual tests GREEN |
| X5 | Full 1,992 matrix; independent evidence verifier; CI/regression/S2 | No missing cases; green at one SHA; external S2 approval |

**Core acceptance invariant:** every displayed financial value in XLSX, HTML and PDF is traceable to one exact normalized field and one explicit confirmed revision of the correct organization's PostgreSQL source. Technical correctness is not financial/methodology approval.

## 6. Codex completion report template

For each stage report: actual SHA, changed paths, design decisions, exact tests/commands/log links, number PASS/FAIL/SKIP, security findings, source/catalog translation coverage and remaining HOLD items. Final decision must differentiate ENGINEERING_ACCEPTED vs ENGINEERING_HOLD and FINANCIAL_QUALIFICATION_HOLD. Work only on feat/fin-r0-r1a; never merge to main.
