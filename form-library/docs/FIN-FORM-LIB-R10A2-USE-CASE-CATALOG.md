# FIN Form Library R10.A2 — Use-case/Purpose Taxonomy for 166 Forms

**Status:** R10.A2 read-only, metadata-only implementation in `feat/fin-form-library-standalone`; not S2 certified, not legally mandatory, not a decisioning engine.

## Why purpose is a separate dimension

Classify forms by three orthogonal facets:

- `primaryGroup` — physical/data semantics: finance, accounting register, planning, operational detail, sector, evidence;
- `sectorCode[]` — company activity, 0..N, including cross-sector holdings;
- `useCaseCode[]` — why a user prepares a package: lender financing, investor diligence, business plan, financial health, investment appraisal, management control or grant application.

A form may belong to several **use cases**; it is never duplicated in `fin_lib` or `fin_intake`. Methodology `analysisPack` remains an independent fourth facet. Do not infer user entitlement, actual loan eligibility, financial approval, or regulation from any facet.

## Seven pilot use cases

| Use case | Main intent | Recommended core inputs |
|---|---|---|
| `FINANCIAL_HEALTH` | Diagnose liquidity, profitability, working capital | F01 F02 F03 |
| `INVESTOR_READINESS` | Prepare an investor diligence dossier | F01 F02 F03 P07 P08 |
| `BUSINESS_PLAN` | Write a credible model-backed business plan | DF-CTX-01 DF-PRJ-01 P01 P02 P03 |
| `BANK_FINANCING` | Prepare a lender data room / application | F01 F02 F03 C05 P03 P04 P09 P10 |
| `INVESTMENT_APPRAISAL` | Evaluate investment alternatives | P03 P05 P07 P08 |
| `MANAGEMENT_CONTROL` | Actual-versus-budget and rolling planning | F02 F03 F07 P02 P03 |
| `GRANT_PROGRAM` | Draft program/grant funding dossier | DF-PRJ-01 DF-PRJ-06 P05 P10 |

Every scenario also includes optional supporting records and conditional evidence. No report is intrinsically legally mandatory for *all* borrowers or investors. Different institutions and jurisdictions require additional checks and bank-specific versions; `T01–T04` in the lending scenario are *conditional evidence*, not mandatory tax submissions.

## Implementation, not claims of model execution

Source: `catalog/use-case-catalog-r10.tsv` includes immutable candidate definition version 1, RU/EN names, audience, intended deliverable and IDs of *existing* analytical model descriptors (42 in `analytics-model-catalog.tsv`).

Mapping: `catalog/use-case-form-map-r10.tsv` links the existing 166 form codes to 7 purposes with roles:
- `CORE` — generally recommended foundational sources, **not** an unconditional completeness gate;
- `SUPPORTING` — useful additional detail according to company profile;
- `EVIDENCE` — conditional documents, audits, legal or tax support.

The source includes 101 curated use-case/form associations; shared forms intentionally appear in multiple scenarios but only once per scenario. This is a navigation/read model, not a seventh source fact mechanism or a new bank underwriting engine.

**Read-only endpoints**:
- `GET /api/v1/catalog/use-cases` — all seven summaries and form counts.
- `GET /api/v1/catalog/use-cases/BANK_FINANCING/forms?locale=ru` — version, model identifiers and detailed classified forms with the **live** `fin_lib` capture and qualification statuses. If a form translation is pending, it remains null/pending.
- `GET /api/v1/catalog/navigation?locale=ru&sectorCodes=G,J&analysisPack=CFO&useCaseCode=BANK_FINANCING` — union of role-specific use-case suggestions, sector choices and model-pack inputs plus common starter forms; full 166 forms still accessible, and purpose-selected forms rank first.

## UI suggestion for FIN Mission Control

Top of the "New analysis" view: a primary segmented purpose choice, **Financial health**, **For an investor**, **Business plan**, **For lending**, **Investment project**, **Management report**, **Grant/program**. Each launches a short task-based checklist with three subgroups (core/supporting/evidence). Sector/project filters remain separate and optional. Search all 166 forms continues to work.

Each card shows independent statuses:
- `Recommended for this purpose` — navigation advice.
- `Captured/has draft` — actual intake state (not implemented by this read-only metadata service).
- `Evidence provided` — attached doc status (not implemented by this service).
- `Schema readiness` and `Financial S2 qualification` — trusted separate review states.
- `Review completeness` — future, only when user-scoped data and reviewed rules exist.

Never show a numeric "95% ready for bank approval" merely from completed form counts; *bank creditworthiness is a separate decision*.

## Future production requirements

1. Define qualified per-purpose checklist contracts (`purposeVersionId`, reviewed policy SHA, applicable company type, period, required evidence, reviewer).
2. Map these contracts to exact form versions and actual metric completeness, not just titles.
3. Bind case to trusted tenant, project and author; store immutable snapshot of selected versions and source revisions in `fin_intake`.
4. Validate that models are actually executable, S2 qualified and up to date; model descriptors are mostly `DESIGN_ONLY`.
5. Add bank-specific or investor-specific templates **only after** an auditable gap matrix demonstrates missing data.
6. Add PostgreSQL negative tests for company isolation, stale revisions, conflicting purpose editions and incomplete/withdrawn documents.

Current tests run in Java 21 + H2; no PostgreSQL/S2 sign-off is claimed.
