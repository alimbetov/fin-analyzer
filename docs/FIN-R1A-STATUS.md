# FIN-R0/R1A — implementation evidence

**Branch:** feat/fin-r0-r1a  
**Status:** TESTED (CI) / MANUAL DESKTOP UAT PENDING. Not RELEASE READY.

## CI Evidence (verified)

- [Run #43641246267](https://github.com/alimbetov/fin-analyzer/actions/runs/43641246267): TypeScript PASS, build PASS, **23/23 Vitest**, Chromium **2/2 E2E** after XLSX hardening.
- [Run #43641435798](https://github.com/alimbetov/fin-analyzer/actions/runs/43641435798): macOS 15 arm64 **Chromium + WebKit 4/4 E2E PASS**. Screenshots/traces in [artifact](https://github.com/alimbetov/fin-analyzer/actions/runs/43641435798/artifacts/14132731774).
- A `package-lock.json` was generated and checked in; both CI jobs now use `npm ci`. Current HEAD CI evidence must be checked after the documentation commit.
- This is automated cross-browser evidence, **not** an independent S2 sign-off or a human macOS UAT.

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
- ZIP central-directory preflight now enforces metadata budgets (compressed size, declared unpacked size, entry count, compression method). An attacker can still forge declarations: no bounded decompression sandbox or browser Worker termination is implemented. Production hardening remains required.
- Manual local approval is not auditing or certification; original financial report may still contain incorrect source amounts.
- Automated macOS Chromium/WebKit E2E passes and the core scenario checks unexpected network requests. No human manual UAT, formal accessibility audit, or comprehensive network security audit yet.
- Offline availability after initial load via PWA service worker **not implemented**.
- Rendered charts are approximate visualizations; canonical values remain decimal strings.
- No Quarto, PDF export, dynamic strategies or multi-company comparison in this slice.

## Required next verification

1. [DONE] Typecheck/build + 23 unit tests + Linux Chromium 2 E2E.
2. [DONE] macOS Chromium/WebKit 4 E2E + screenshot artifact.
3. [DONE] Reproducible `package-lock.json`; CI switched to `npm ci`.
4. [OPEN] Manual **MacBook Desktop UAT**, visible UI and accessibility checks.
5. [OPEN] S2 review by a separate reviewer; no independent APPROVE currently.
6. [OPEN] Adversarial inflated XLSX isolation with bounded runtime/decompression.
7. [OPEN] npm dependency vulnerability scan, period/rounding policy and provenance design review.

See [master specification](FIN-SPEC.md) for complete product requirements.
