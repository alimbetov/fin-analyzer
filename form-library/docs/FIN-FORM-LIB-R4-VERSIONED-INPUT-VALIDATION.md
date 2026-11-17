# FIN-FORM-LIB-R4 — Versioned Input Validation

**Backend MVP / H2 only / no React or financial S2 approval.**

## Contract

- Missing cells remain MISSING. A saved zero is explicitly PRESENT with value 0. NOT_APPLICABLE is a third distinct state. Never auto-fill missing values with zero.
- Per-report, per-indicator rules have immutable validationVersion and allowMissing, allowNotApplicable, signRule, minValue, maxValue, maxPrecision, maxScale.
- signRule values: ANY, NON_NEGATIVE, POSITIVE, NON_POSITIVE, NEGATIVE, ZERO_ONLY, NON_ZERO.
- Source numeric type and ISO currency minor units are still enforced. A custom rule cannot permit an otherwise invalid value.
- A revision stores its validationVersion and validationSha256 for audit. Updating rules never overwrites historic revisions.
- Backend save must revalidate; UI preview does not constitute approval or financial publication.

API:
- GET /api/v1/report-grids/{id}/validation?organizationId=...
- PUT /api/v1/report-grids/{id}/validation with organizationId, expectedValidationVersion, rules[] (full replacement).
- POST /api/v1/report-grids/{id}/validate with organizationId and cells[] returns per-cell issues.
- POST /api/v1/report-grids/{id}/revisions remains authoritative and rejects invalid content with 422.

The user may define allowMissing=false for required reporting cells; incomplete drafts then cannot be saved in the current MVP. A future SAVE_DRAFT vs SUBMIT split is needed before making all fields mandatory in a data entry workflow.
