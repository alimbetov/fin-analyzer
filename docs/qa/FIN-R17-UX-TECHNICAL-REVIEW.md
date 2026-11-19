# FIN R17 — UX technical review and semantic icon library

Branch: `feat/fin-form-library-standalone`  
Baseline reviewed: `fdfd882b7db2b79d0eba3b59bff147e314b2e299`  
Scope: React workspace, form editor, sections, icons, browser test selectors. No HTAP changes.

## Findings and remediation

| Priority | Finding | Disposition |
| --- | --- | --- |
| P1 | Browser tests still used removed `Сохранить ревизию` and default financial navigator paths | Updated E2E selectors and explicit navigation in `decision-flow.spec.ts`, `form-qualification.spec.ts`, `decimal-input.spec.ts`, `saved-reports.spec.ts` |
| P1 | Keyboard next-field navigation could focus inside a collapsed section | Editor opens target disclosure before focusing |
| P2 | Technical `sectionCode` exposed as UI heading | `MAIN` and `GENERAL` have explicit RU/KK/EN/ZH-Hans UI captions; unknown codes remain visible, not fabricated |
| P2 | Form icon role inferred only from content mode | Shared domain→icon palette, with reusable icons across all forms |
| P2 | No dedicated visual-registry regression | Added `formVisuals.test.ts` and included it in `npm test` |

## Material architectural limitations

- Current `CatalogBootstrap` creates a single section with code `MAIN` for each seeded form. This is a **layout grouping**, not yet a finance-reviewed semantic section taxonomy.
- Form sections have `sectionCode` and `sectionOrder`, but no localized title property in the exposed section contract. UI translations are limited to known presentation captions; they do not constitute approved financial metadata.
- FIN HTAP remains untouched. No automatic transfer, fact publication or calculation trigger has been added.
- Authentication, project entitlement, and document-download authorization are not production-qualified in this local UAT app.
- No new financial method, field, or metric contract has been inferred from icon metadata.

## Evidence boundaries

GitHub commit and source inspection were performed. Local repository clone failed because `github.com` could not be resolved from the execution container. Therefore **TypeScript compilation, unit test execution, PostgreSQL integration and real Playwright replay were NOT run in this review**. Results are not called GREEN until run on a reachable MacBook/CI environment.

## Required checks before approval

```bash
git switch feat/fin-form-library-standalone
git pull --ff-only
cd frontend
npm ci
npm run check
# Start the backend and an isolated PostgreSQL QA stack first.
npm run test:e2e
# For full 166-form replay, provide the isolated FIN_QA_DATABASE=fin_qa environment.
```

For release qualification, capture test artifact SHA, browser versions, branch SHA and PASS/FAIL per form. Re-run 166-form persistence and revision cycle after any changes affecting editor field rendering. New visual labels are presentation-only and must not bypass draft validation.

## Future

A dedicated, versioned, four-language section-title contract may be added only after editorial and financial review of real section segmentation. Until that step, retain raw codes for unknown sections.
