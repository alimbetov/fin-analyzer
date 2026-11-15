# FIN-BE-HARDENING-R2 — source publication and immutable report evidence

**Status: engineering pilot; not production-ready.**

The prior V1 table `fact_set_revision.status = APPROVED` was not a
complete approval boundary. Under V2, a candidate revision becomes
a trusted input only after it is present in `fin_fact.fact_set_publication`.
Publication is immutable, enforces canonical identity of every selection
from the stored `fact_assertion`, ensures the source revision is registered
as approved, prevents estimated inputs in ACTUAL, checks scope and
reporting basis, and serializes further selection inserts against publication
with a PostgreSQL row lock. The database produces a SHA-256 snapshot hash.

`FactSetPublicationService.publish` performs an atomic scoped compare-and-swap
of `current_fact_set` using an expected parent revision, then publishes
the trusted snapshot in the same transaction. A losing concurrent attempt
rolls back; the previous approved revision stays intact. This service MUST be
called only after application-layer Keycloak/ABAC enforcement; UUID arguments
do not provide authorization.

`fin_calc.report_run` can reference **published** FactSets only. Its
fingerprint is calculated inside PostgreSQL from the published input
snapshot hash plus report code, report version and method version.
`fin_calc.report_cell` keeps typed statuses, optional monetary currency,
explicit diagnostic reason, deterministic column order, value and method
version. A report can only be finalized with exactly its declared number of
cells; finalized reports cannot receive more cells. All source approvals,
publications, report metadata and cells are immutable.

## Important limitations and open gates

- `approved_source_revision` is currently a controlled registration table,
  not an implemented form-validation and multi-party approval workflow.
- The source-approval event is not yet tied to verified Excel/React upload
  provenance, user authentication, authorization, or signed evidence.
- An approved candidate's raw V1 `status` is not sufficient for trusted use.
  Consumers MUST join `fact_set_publication`.
- `current_fact_set` is mutable operational state, not historical evidence;
  historical report and FactSet publications remain immutable.
- `report_run` and `report_cell` are persistence primitives; the trusted,
  DB-backed FactResolver and server-operated ReportSnapshotWriter are next.
  The current `AutoReportProjection.project` still accepts an in-memory map
  and MUST NOT be exposed directly through a public API.
- Fingerprint currently pins source snapshot and method/report versions.
  It does not include FX-policy version, fiscal calendar, full FormulaRegistry
  AST hash, or assumptions; reports requiring those must not be qualified yet.
- No PostgreSQL RLS policy or least-privilege DB role is configured yet.
- No Outbox, asynchronous replay, multi-period consolidation or production
  completeness gate exists. Existing Java DAG and sector mappings remain pilots.
- V2 migration is additive and cannot retroactively certify existing V1
  candidate rows. It intentionally does not change React or `main`.

## Verification

```sh
mvn -B -ntp -f backend/pom.xml verify
```

PostgreSQL 16 via Testcontainers tests: rejected source/key/scope, immutable
published rows, forged hashes, source-estimate restrictions, sealed report
cells, missing report cells, parent linkage, stale-pointer CAS, and concurrent
publication. Green tests prove those invariants only, not all FIN business forms.

**Next:** trusted FactResolver → CalculationRun → report projection from
stored assertions → transactional report publication; source validation,
readiness/freshness fingerprint and ABAC. Then harden currency/time semantics
and introduce functional integration for pilot forms.
