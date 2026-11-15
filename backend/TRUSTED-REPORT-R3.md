# FIN Trusted Auto Report R3 — PostgreSQL-backed calculation

Status: **ENGINEERING PILOT, not production financial reporting**.

The V2 publication layer makes `fact_set_publication` the only trusted
source of facts. V3 adds immutable `fin_calc.metric_contract` entries and
pins `report_run.definition_hash`. The report fingerprint now includes:
published source hash, report code/version, calculation method version and
the canonical SHA-256 hash of all report columns and operators.

## Executable path (no React)

1. Insert original assertions and register their approved source revision.
2. Select assertions by **canonical** economic key, preserving source and
   period. Publish with `FactSetPublicationService` (scoped CAS).
3. `TrustedAutoReportService.calculateAndPublish(authorizedTenant,
   factSetRevisionId, serverOwnedReportDefinition)` checks publication,
   policy qualification, scope, period and units, reads selected assertions
   via tenant-scoped JDBC, builds typed series and runs
   `AutoReportProjection` using **database facts only**.
4. In one transaction, persist `report_run`, all calculated/blocked
   `report_cell` records, and final `report_publication`.
5. Repeated call with identical definition is idempotent; changed column
   formulas under an existing reportVersion are rejected.
6. `loadPublished(authorizedTenant,runId)` reads ordered immutable report
   cells and reports `CURRENT_SOURCE`, `STALE_SOURCE` or `UNKNOWN_POINTER`
   relative to the current scoped fact-set pointer.
7. An older run remains reproducible after a new period correction; it is
   NOT overwritten.

`metric_contract.qualification=PILOT` is allowed **only** when the
published data space is `SYNTHETIC`; other spaces require
`QUALIFIED`. No model is automatically qualified by the migration.
Any future role able to insert QUALIFIED methods must be explicitly
authorized and independently controlled.

## Security and remaining HOLD items

- No public HTTP controller and **no Keycloak/ABAC enforcement** yet.
  The `authorizedOrganizationId` argument is a server-side integration
  boundary, not an authenticated identity token. Never connect a client
  directly to it.
- The incoming `ReportDefinition` must come from a trusted, versioned
  server-owned registry; no untrusted expression payload may be passed.
- Source registration still lacks a full FormDefinition validation and
  approval workflow with actor signatures and versioned mappings.
- Semantic metric policies are immutable but have no independent expert
  qualification workflow. Formal report accounting and all-sector claims
  remain HOLD.
- Snapshot fingerprint does not yet pin FX rate sets, fiscal calendar,
  forecast assumptions, legal reporting standard or external data revisions.
  Those must be added as explicit dependencies before qualification.
- `CURRENT_SOURCE` only means the underlying **fact set** is the active
  scope pointer. It does NOT prove latest formula, FX or report policy.
- JDBC role restrictions, PostgreSQL RLS and cryptographic integrity of
  original uploaded source files are not configured.
- The 166-form mapping and 21-sector coverage are still design candidates,
  not automatically ingestible production forms.
- No outbox, retry/recovery worker, REST/React submission, or audit-event
  provenance is present.

## Run

```shell
mvn -B -ntp -f backend/pom.xml verify
```

Testcontainers PostgreSQL 16 provides the actual E2E fixture: two months
of revenues and costs from an approved source, a published FactSet,
versioned report columns, immutable persisted snapshots, a corrected
January revision, freshness state, tenant isolation, missing COGS,
idempotency, definition-hash conflict and rejected unqualified methods.

Future R4: authenticated FormSubmission → Semantic Validation →
FactResolver → canonical publication → durable Outbox →
CalculationRun/ReportSnapshot; then currency, time and version-aware
methodology qualification.
