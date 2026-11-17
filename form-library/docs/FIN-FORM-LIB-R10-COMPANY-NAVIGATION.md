# FIN Form Library R10 — Universal Company Navigation / 166 Forms

**Status:** R10.A read-only classification implemented. Not a S2 financial qualification, legal reporting mandate, tenant permission system, or UI implementation.
**Branch:** `feat/fin-form-library-standalone`. Source of truth for form identities remains existing `form-compatibility.tsv` / `fin_lib`. No new source facts, `fin_intake` writes or UI branch.

## Context: 166 forms, 42 model descriptors, 47 direct references

| Group | Count | Source |
|---|---:|---|
| FINANCE | 8 | F01-F08 |
| LEDGERS | 14 | C01-C14 |
| PLANNING | 10 | P01-P10 |
| DETAIL | 59 | DF-* |
| SECTOR | 63 | SF-* |
| EVIDENCE | 12 | D01-D08 and T01-T04 |
| **Total** | **166** | |

There are 21 sector packs (SF-A..SF-U) with 3 forms each, including household forms in SF-T **which are not a legal-company type**. The existing `analytics-model-catalog.tsv` contains 42 analytical model descriptors referencing 47 distinct forms (119 currently unreferenced in this particular catalog). This is not a claim that the full FIN methodology universe implements just 42 methods, nor that 119 forms are useless. All statuses are unqualified/roadmap-level until separately approved.

## One company may belong to multiple sectors

A single company can have multiple operations, branches and projects. Sector profile is a *set* (e.g., `G` trading + `J` IT) rather than one hard-coded organization category. Holdings must support separate legal entities and consolidated reporting contexts.

Do not confuse:
1. **Legal identity** / organization kind (commercial legal entity, sole proprietor, group/holding, non-profit, public body, household). Requires country-specific legal validation.
2. **Sector/activity** (0..N active 21-sector packs) and revenue model (trade, manufacturing, subscription, project/service, lending, etc.).
3. **Scale/complexity** (starter / standard / enterprise).
4. **Task/role** (bookkeeping, financial control, economic analysis, budgeting, investment, operations, compliance).
5. **Report applicability** (unassessed / recommended / not applicable / separately mandatory by verified regulation), **independent** of technical capture readiness and financial S2 qualification.
6. **Tenant authorization** (what the authenticated user may access), also independent of discoverability.

Persisting and authorizing a company profile is future work, not R10.A. Today the selection is an ephemeral query parameter, and recommendation must never authorize writes or infer a legal filing obligation.

## UX: Mission Control / one useful launchpad, all forms in search

- Top bar: selected organization / legal entity / project / reporting basis (future trusted identity).
- Step 1: choose business activities, **multi-select** sector packs. All 166 remain discoverable.
- Step 2: choose work objective (e.g. working capital, cash flow, budget, investment, operations). In current R10.A the five existing method packs `ACCOUNTING`, `CFO`, `INVESTMENT`, `COSTING`, `MANAGEMENT` are available as coarse navigation facets.
- Step 3: show short list `Recommended` (sector forms + inputs from selected analytical model pack + common financial starters F01-F04), followed by six form-group sections and a search over every form. Starter status is a heuristic, never a mandate.
- Forms open in the existing version-pinned Excel/grid/rowset/evidence readers. Show pending translation and S2 qualification as independent statuses. Do not call drafts "approved".
- For a non-profit or a household, allow role-relevant task-first exploration without forcing a corporate set of financial statements.

## Implemented navigation endpoint

`GET /api/v1/catalog/navigation?locale=ru&sectorCodes=G,J&analysisPack=CFO`

Response includes counts, six groups, 21 labeled sector packs, five model-pack names, `recommended` and all `allForms`. Every form includes:
- system `formCode`, `primaryGroup`, `functionalDomain`, optional `sectorCode`;
- live `fin_lib` current title and translation state (nullable if unavailable);
- catalog `captureMode`, `contentMode`, `qualificationState`;
- `recommendationReason`, one of `SELECTED_SECTOR` / `ANALYSIS_MODEL_INPUT` / `COMMON_STARTER` / `CATALOG_ONLY`.

These are read-only navigation hints. Navigation is not the source of schema or form definitions; no new S2 assertions. All 166 forms remain available regardless of profile selection.

Catalog configuration:
- `form-navigation-r10.tsv`: stable 166-code navigation map with category and functional domain.
- `sector-packs-r10.tsv`: the 21 independent sector labels (RU/EN), including explicit household flag.
- `analytics-model-catalog.tsv`: existing read-only source for pack-to-input-form references.

## Gaps and proposed additions (not yet new forms)

Priority is **model coverage and quality**, not form count:
1. Validate user journeys for a trade company, IT-service company, manufacturing plant, construction project, diversified group, public body and household; check that suggested forms provide useful inputs without enforcing unsuitable forms.
2. Build a machine-audited `form -> required fields -> method input -> output metric` matrix. A method descriptor is a design artifact and does not prove executable coverage.
3. Only then approve domain-specific new forms or **versioned extensions** for missing data: multi-currency exposure, margin by business line, ESG/emissions evidence, customer cohort drivers, and regulatory specifics if applicable. These are examples of gaps to investigate, not proven missing from the 166.
4. For regulated sectors verify jurisdictional validity and date/version before calling anything mandatory.
5. Before production, implement verified organization-scoped authorization, immutable/qualified form mappings and PostgreSQL negative tests.

## Acceptance: fail closed

- Exactly 166 unique form codes; six groups count 8+14+10+59+63+12.
- Exactly 21 sector packs x 3 sector forms; all sector forms remain visible if unselected.
- Multi-sector company gets both sets, without duplicating codes.
- Five model packs derived from source data, 42 model descriptors, 47 forms referenced.
- Invalid locale, sector or model pack rejected, no guessed translations.
- No additional `fin_intake` instances or revisions; S2 statuses remain unqualified.
