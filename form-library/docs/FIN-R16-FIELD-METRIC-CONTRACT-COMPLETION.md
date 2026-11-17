# FIN R16 — Field & Metric Contract Completion

Branch: feat/fin-form-library-standalone. Status: R16.A and R16.B are implemented as **draft data contracts and provisional labels**. They are not approved financial schemas.

## Verified catalogue boundary

- 166 forms, 877 physical fields.
- 793 bound source fields; 84 evidence metadata fields; 46 analytical-output metric codes.
- 840 legacy metric definitions in total. F02.netProfit retains its historical SOURCE_FIELD kind for compatibility, but it is explicitly an unbound FORM_DERIVED_CANDIDATE. The three writable F02 source fields remain netRevenue, cogs and operatingExpenses.
- No source field or formula has been automatically given S2 qualification. The F02 formula candidate is not executed by this implementation.

## R16.A — physical binding

Liquibase 012 creates fin_lib.lib_field_binding_contract, lib_metric_binding_exception and lib_field_contract_seed_manifest. Startup validates the 166 form identities, 877 fields, all metric codes, exact types and one explicit derived exception. It inserts 877 contracts transactionally and refuses drift or partial replays. Each contract includes form code, field code, type, input mode, requiredness, unit policy, semantic role, original schema SHA-256, its own SHA-256 and review state CANDIDATE_UNQUALIFIED.

FieldMetricContractGuard checks ownership of a pinned version, field counts, physical types, field requirements, metric source identity, unit policy and schema digest against that version. FormDraftService refuses stale bindings with a 422; ReportGridService refuses stale source selection; InputTemplatePreviewService disables editing when the contract no longer matches. Presentation-only version changes remain compatible.

Read-only APIs:
- GET /api/v1/catalog/contracts/coverage
- GET /api/v1/catalog/contracts/forms/F02?locale=ru
- GET /api/v1/catalog/contracts/metrics/F02.netProfit

The metric contract API explicitly exposes legacy kind versus authoritative semantic role, the unapproved formula candidate, and financialQualified=false. The legacy database column still says SOURCE_FIELD for F02.netProfit: migrating that column is a separate backwards-compatibility task.

## R16.B — localized display candidates

Liquibase 013 creates fin_lib.lib_field_display_candidate and lib_metric_display_candidate, and a content fingerprint manifest. The four-language token glossary at catalog/field-label-glossary-r16.tsv produces nonblank candidate labels:
- 877 * 4 = 3,508 field label records;
- 840 * 4 = 3,360 metric label records.

All automatically assembled captions are marked AUTO_DRAFT_REVIEW_REQUIRED (or PARTIAL_DRAFT if an unknown term appears in a future edition). Sentence-level quality, accounting terminology and grammar are NOT reviewed. Existing editorial labels in immutable form versions take precedence. The Excel preview, forms and metrics APIs now use the candidate for missing labels without rewriting financial schema, canonical values or fact revisions. Reference Caffeine caches are separate and bounded.

## Test evidence

- FieldMetricContractIntegrityTest: source/evidence binding counts, version digest, derived exception, SQL foreign keys and negative values.
- FieldMetricContractApiIntegrationTest: all 166 form bindings through HTTP and semantic role of F02.netProfit.
- FieldMetricDisplayDraftIntegrationTest: all 6,868 drafts across RU/KK/EN/ZH, nonblank names, read-only discovery and unchanged fin_intake.
- FieldMetricContractGuardIntegrationTest: invalid form/version, mutated field type and unit policy disable editing and fail the gate.
- Previous FinIntakeDatabasePersistenceAuditTest continues checking SHA, immutable revisions, 200 rows and 31 day cells.

These tests run in Java 21 / H2 PostgreSQL-compatibility mode, not a real PostgreSQL benchmark.

## Remaining work — release blockers

R16.C: editorial and financial S2 approval per form and per field: type, requiredness, unit, reference dictionary, period policy, decimal scaling, grain, methodology references, versioned signatures, independent reviewer. Do not bulk-approve automatically generated texts.

R16.D: compile and test the 46 analytical output algorithms with exact source versions, explicit formulas, missing/zero/not-applicable semantics, rounding and currency conversion. Five CALC_PILOT catalogue entries are not sufficient to claim 46 executable outputs.

R16.E: real PostgreSQL negative and concurrency tests, React/Browser editor to fin_intake read-back, tenant authorization, p50/p95/p99 latency, rollback and stale-version failure tests.

**Acceptance policy:** Draft completeness is distinct from qualified financial readiness. The 166 forms are structurally represented and discoverable, but not all production ready.
