# FIN-R0/R1A — implementation evidence

**Branch:** feat/fin-r0-r1a  
**Status:** TESTED (CI) / MANUAL DESKTOP UAT PENDING. Not RELEASE READY.

## CI Evidence

- Commit `ab95f5836ea0681b20479d90d71ad83485dd62ab`
- [GitHub Actions run #43638616781](https://github.com/alimbetov/fin-analyzer/actions/runs/43638616781) — **SUCCESS**.
- Typecheck: PASS.
- Vitest: **16/16 passed** (4 test files): accounting validity, missing/invalid values, negative equity, decimal indicators, revision restoration and negative XLSX cases.
- Vite production build: PASS.
- Playwright on real Chromium: **1/1 passed**, including XLSX download/edit/upload, invalid balance blocking, valid approval, charts and project save/reopen.
- A subsequent README-only commit did not alter runtime code; its CI must also pass before merge.


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

1. [DONE] CI typecheck, 16 unit tests and production build passed.
2. [DONE IN CI] Chromium Playwright E2E: real XLSX download/edit/upload and JSON save/reopen.
3. [OPEN] Real **manual macOS/Desktop** UAT, visual and accessibility checks.
4. [OPEN] Negative security stress on malformed XLSX and large decompression.
5. [OPEN] Independent S2 review of canonical financial math and Excel protocol.
6. [OPEN] Lockfile, dependency vulnerability review and expanded multi-browser/negative E2E checks.

See [master specification](FIN-SPEC.md) for complete product requirements.
