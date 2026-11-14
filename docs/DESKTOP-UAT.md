# FIN-R1A Desktop UAT — acceptance checklist

**Scope:** Balance 2024–2025 only, 100% local browser workflow.

## Automated browser evidence

The GitHub Actions `desktop-macos` job runs Playwright in Chromium and WebKit on an actual macOS runner. It exercises:
- Generated XLSX download and edit/upload.
- Rejection of a balance mismatch.
- Approval of corrected statements.
- Rejection of changed template version without damaging approved analysis.
- ECharts render and local FIN project save/reopen.
- Independent React entry path.
- Screenshots of initial, validation, and approved views in an Actions artifact.
- Unexpected outbound HTTP request detection in the core scenario.

**Important:** Automated Playwright on a macOS CI runner does *not* constitute manual UAT on the user's MacBook. An S2 reviewer must independently review critical finance and XLSX logic; self-audit alone does not qualify as independent approval.

## Manual macOS UAT

Use Chrome/Safari on a MacBook with Node.js 22+:
```bash
git clone -b feat/fin-r0-r1a https://github.com/alimbetov/fin-analyzer.git
cd fin-analyzer
npm install
npm run dev
```

1. Open the local address printed by Vite.
2. Verify no horizontal desktop overflow, all Russian labels readable and no console errors.
3. Enter company name `ТОО FIN UAT`, download the Balance XLSX and fill C6:D10.
4. For 2024 and 2025 respectively: assets 1000; current assets 400; liabilities 600; current liabilities 200; equity 400 (all in thousands of KZT).
5. Intentionally change one total to 1001, upload and confirm the blocker prevents approval.
6. Correct to 1000, upload and confirm, verify Current Ratio = 2 for both years and assets chart values = 1000.
7. Save `.fin.json`, reload page, reopen project, check revision and charts restored.
8. Deliberately upload an Excel with a changed unit label or company and verify refusal without changing approved analysis.
9. Repeat in Safari and confirm download/restore compatibility.
10. Record screenshots, date, device/browser versions and PASS/FAIL for each step.

## Gates still required

- Independent S2 review with a **real GitHub reviewer approval**.
- macOS manual UAT evidence (person, device, browser, step results).
- Adversarial decompression stress / worker timeouts for untrusted XLSX (the ZIP metadata guard is only a first layer).
- Reproducible lockfile + `npm ci` and dependency security review.
- Accessibility review with keyboard/navigation.
- Only then consider changing Draft PR #2 to ready; do not merge without explicit approval.
