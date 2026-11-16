# FIN-BE-R1-X4 — ADR: PDF renderer, fonts, offline security

**Decision:** Java 21 + Apache PDFBox 3.0.8 (Apache-2.0) embedded-font, direct page renderer. No browser, external HTML/CSS fetch, JavaScript, remote fonts/images, file://, in-PDF attachments or uploaded content interpretation. This is a **provisional engineering report**, not an approved financial ACTUAL document.

## Source and invariants

Both **X3** and **X4** consume a single already-resolved FinancialReportSnapshotService.Snapshot, transformed through FinancialReportRenderModel.prepare(snapshot, displayLocale, generatedAt) into read-only field presentation. Neither renderer queries PostgreSQL or recomputes business facts. X3/X4 HTTP endpoints accept **only an explicitly pinned sourceRevisionId** after an authenticated FIN_READ check; they never infer another latest revision within the renderer. HTML bytes and PDF bytes carry exact source ID/status, organization/project, period/basis, original source and document checksums. No reviewer name, signature or fictitious approval.

The user-controlled values are TEXT, not URLs, tags, HTML, XML, CSS or paths. HTML uses encoded text/attributes and a style-hash CSP. PDFBox draws text from the trusted model only, eliminating HTML-to-PDF URI resolution and external resource loading.

## Dependencies and license boundaries

- [Apache PDFBox](https://pdfbox.apache.org/) **3.0.8**, Apache License 2.0. PDF text extraction and image raster smoke use the same library; parser validation is independent of the renderer code path.
- Latin/Kazakh Cyrillic: locally provisioned **DejaVu Sans TrueType**. Check applicable DejaVu licensing and embedding requirements before distribution. Default Linux path: /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf.
- Chinese Simplified: locally provisioned **AR PL SungtiL GB** TrueType from the Debian fonts-arphic-gbsn00lp package under the **Arphic Public License**; check redistribution/embedding notices with legal review prior to shipping installers. Default Linux path: /usr/share/fonts/truetype/arphic-gbsn00lp/gbsn00lp.ttf. <https://packages.debian.org/trixie/fonts-arphic-gbsn00lp>
- Fonts **are not bundled** into this git repository and must not be redistributed without licensing review. CI provisions packages from its Linux package manager **before tests**; production rendering is entirely offline.
- Production operator may set trusted FIN_PDF_LATIN_TTF and FIN_PDF_CJK_TTF environment variables to installed .ttf absolute paths. These are deployment-only settings, never HTTP input. Missing/incompatible fonts fail closed; missing glyphs never silently become ?. MacBook needs explicit two-font provisioning and raster smoke to be marked tested.

## Layout and budgets

- A4 landscape, four financial field columns per band + row ID; wide schemas continue on new landscape pages, never omit columns.
- Repeated table field codes/labels on every page, page number, sourceRevisionId and report passport on every band/page. Evidence-only forms use a document register, with no original binary attachments.
- Text is wrapped by glyph width (not truncated); oversized row/font/field/page fails explicitly. Maximum **200 rows, 80 columns, 120 PDF pages, 8 MiB PDF**; HTML **4 MiB**. These are engineering safety ceilings pending measured production load qualification, not throughput claims. No unlimited external image or HTML rendering surface.
- Source decimals remain canonical strings; the renderer does not parse to IEEE-754, apply FX, round, or produce implied zeros.
- For deterministic HTML, the timestamp must be passed explicitly. PDFBox output is structurally deterministic for a given snapshot; metadata/compression can vary and binary byte equality across runs is **not** an acceptance criterion. Compare extracted canonical data, source ID, rendered geometry and PDF validity.

## Acceptance and limitations

CatalogXlsxRoundTripIntegrationTest consumes **166 genuine PostgreSQL-backed confirmed snapshots** and checks 166 × 4 HTML plus 166 × 4 PDF outputs. Tests parse HTML with JSoup and PDF with PDFBox Loader/PDFTextStripper. Extra tests exercise XSS, bidi controls, Chinese+Kazakh characters and PDF raster smoke on a sample; mandatory CI proof is fin-be-r1-x3-x4-render-coverage.json (1328 cases) and verifier backend/scripts/verify_fin_be_r1_x3_x4_evidence.py.

**What this does not prove:** independent financial method/field qualification for 166 forms, complete approved translation labels (only 6 of 794 Chinese labels provisionally exist), font embedding license clearance for product distribution, production-performance limits, pixel-by-pixel audit on MacBook, full X5 1,992-way cross-format acceptance and real user client download. All remain explicit release gates.
