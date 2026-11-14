# FIN-R0/R1A — implementation evidence

**Branch:** feat/fin-r0-r1a  
**Status:** IMPLEMENTED / VERIFICATION PENDING. Not UAT PASS.

## Delivered in this slice

- React + Vite + strict TypeScript application; static browser-first operation.
- Balance XLSX template for two years (2024, 2025), bound to company and template version.
- Browser XLSX upload, guarded structure, cell checks, schema/version/company context, refusal of formula objects.
- React form for correction, staged draft, blocking and warning validation.
- Local approval with immutable revision history during the session; analytical values from **approved** revision only.
- Current Ratio calculated with decimal.js, assets growth and ECharts visualizations.
- Save/reload project using local .fin.json, with schema and approved revision validation.
- Domain/Excel unit tests and GitHub Actions CI workflow.

## Limits (not acceptable as production qualification)

- Only Balance and two hardcoded years; no other forms, dynamic year range, industries, exchange rates.
- 15 significant digits limit due Excel numeric precision; no high-precision decimal text contract yet.
- Zip-bomb and decompression budgets are not assured by ExcelJS's generic loader; 2 MB compressed limit is **not** a proven defense against adversarial workbooks. Security hardening required.
- Manual local approval is not auditing or certification; original financial report may still contain incorrect source amounts.
- No complete browser E2E / multi-browser UAT yet and no network audit.
- Offline availability after initial load via PWA service worker **not implemented**.
- Rendered charts are approximate visualizations; canonical values remain decimal strings.
- No Quarto, PDF export, dynamic strategies or multi-company comparison in this slice.

## Required next verification

1. CI typecheck, unit tests and build; record commit and run URLs.
2. Real Desktop browser smoke of Excel round-trip and save/reopen.
3. Negative security test for malformed XLSX and large decompression.
4. Independent review of canonical financial math and Excel protocol.
5. Add Playwright E2E tests before marking FIN-R1A accepted.

See [master specification](FIN-SPEC.md) for complete product requirements.
