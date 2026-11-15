# FIN IND-R0 + IND-R1 — implementational handoff and acceptance

**Date:** 2026-11-15. **Branch:** `feat/fin-r0-r1a`. **State:** CODED / AUTOMATED CI EVIDENCE PENDING / INDEPENDENT METHOD REVIEW PENDING / MANUAL DESKTOP UAT PENDING. This status is not a production release claim.

## Scope delivered in existing code branch

- Reused canonical Java backend TSV `sector-business-model-packs-v2.tsv` and `form-compatibility.tsv` directly from the browser build. **Not a copy of the 21-sector/64-variant catalog.** `src/sector/registry.ts` loads, validates and routes 21 sectors, 64 activity variants, 63 sector form references and 166 registered top-level forms.
- Imported the **byte-identical** existing `packages/sector-pilot` from `docs/fin-marketplace-requirements-baseline`, including the nine-form fixed-point engine and its golden tests. A typed `index.d.mts` exposes the functions to TypeScript.
- React `SectorStudio` enables data entry only for `SF-G01/G02/G03`, `SF-M01/M02`, `SF-S01`, `SF-C01/C02/C03`. All other sections and forms render an explicit **not implemented** state.
- Per-form, per-month, per-dimension and currency data are **locally approved by user**, not remotely verified. Full required fields, nonnegative decimal syntax, physical checks and source/revision IDs are enforced by the source calculation kernel. Previous approvals are retained on reapproval. Aggregation results cover month, quarter, half year and year if source coverage permits.
- Controlled XLSX template per pilot form, with structural, metadata, ZIP budget and formula rejection. Import only stages editable values; a separate confirmation triggers a calculation. `src/sector/excel.ts` and `src/sector/excel.test.ts`.
- Current and superseded sector approvals survive `.fin.json` save/reopen; old FIN project schema without a sector payload remains supported. Invalid or mismatched payloads are rejected. Note: this is **local** user confirmation and a **schema validation**, not digital source authenticity or audited ledger evidence.
- Ten candidate method passports with RU/KK/EN strings, explanations, formula descriptions and explicit `PILOT_UNQUALIFIED` labels; mathematical execution remains in the imported fixed-point kernel, not user-editable formula text.
- Existing `.github/workflows/ci.yml` now includes `npm --prefix packages/sector-pilot test`, alongside TypeScript, Vitest, build, Linux Playwright and macOS desktop browser automation.

## Verified by inspection (not automated PASS)

- Source `sector-pilot/index.mjs` and `pilot.test.mjs` have identical Git blob hashes across source and destination branches.
- Existing registry TSV consists of exactly 21 sector sections, 64 distinct variants, 63 sector form references, 166 declared forms; all listed base/sector form codes resolve.
- This inspection does **not** substitute for executable CI, React DOM rendering, XLSX roundtrip validation or methodological review.

## Required commands, expected evidence

```sh
npm ci
npm --prefix packages/sector-pilot test
npm run typecheck
npm test
npm run build
npx playwright install --with-deps chromium
npm run test:e2e -- --project=chromium
```

**Evidence gate:** record commit SHA + exact GitHub Actions run URL + job conclusions, failing logs or screenshots. Until this evidence is available, do not claim any new tests passed. macOS manual exploratory acceptance is separate from automated browser CI.

## Pending blockers and external qualification

| ID | Gate | Status |
|---|---|---|
| IND-01 | 21/64/63/166 registry and negative contract cases on one SHA | IMPLEMENTED; CI result PENDING |
| IND-02 | Pilot kernel golden/negative regression | PREVIOUS BASELINE PASS; NEW SHA PENDING |
| IND-03 | React controlled input → approval → metrics → save/reopen | IMPLEMENTED; browser CI result PENDING |
| IND-04 | XLSX roundtrip, metadata tampering, formula injection rejection | IMPLEMENTED; browser CI result PENDING |
| IND-05 | Full official five-digit OKED mapping, country/edition crosswalk | NOT IMPLEMENTED |
| IND-06 | True company access/authorization, Keycloak, backend source approval | NOT IMPLEMENTED in local pilot |
| IND-07 | Independent accounting and sector formula review, 3-language expert approval | NOT APPROVED |
| IND-08 | Manual MacBook UAT with actual generated UI screenshots | NOT DONE |
| IND-09 | Unified React + backend canonical EconomicFact and Formula AST reconciliation | NOT IMPLEMENTED |
| IND-10 | Hardened XLSX decompression resource/time sandbox (Worker) | NOT IMPLEMENTED |

**Draft warning:** unapproved sector field edits remain in React memory and are not included in `.fin.json`; changing the selected form/context requires explicit discard confirmation. Browser refresh before approval still loses the draft. Only approved sector revisions are retained.

**Additional limitations:** the pilot accepts nonnegative decimal fields at up to six decimal places and does not support corrections through negative operational inputs, arbitrary 5-digit OKED codes, fractional physical-unit policy changes, audited approval or currency conversion. A business with multiple model variants receives dimension-keyed separate calculations; full consolidated cross-model financial results and shared cost allocations remain out of scope.

## Next acceptance

1. Run all CI gates on one HEAD; fix actual failures and repeat until green.
2. Verify real Chromium/WebKit screenshots on MacBook, incomplete-month states, formula explanations, invalid Excel, old project restoration.
3. Obtain independent S2 expert review of all ten pilot method passports and assumptions; update their qualification only with recorded evidence.
4. Formalize five-digit OKED mapping based on source-controlled official classification files and transition tables; never infer a code from a model prefix.
5. Integrate accepted sector facts into a canonical controlled source ledger, cross-frontend/backend golden fixtures, audited tenancy and permissions before hosted launch.

**Invariant:** no changes to `main`, no additional branch, no silent approval and no claim of 63 executed forms.
