# FIN-EDIT-R1 — Qualification of all 166 reporting form contracts

Status: **INVENTORY BUILT / FORMAL QUALIFICATION NOT COMPLETE / RELEASE HOLD**

This specification is subsequent to FIN-EDIT-R3 persistence qualification.
A file in the catalog is **not** a verified financial reporting contract.

## Evidence baseline

The source inventory currently has:
- 166 form identities from `form-compatibility.tsv`.
- 122 with preliminary typed field hints, 32 preliminary specialized schemas,
  and 12 document/external-tax evidence forms.
- 794 rows in `form-field-presentation-review-r6.tsv` across 154 forms.
- 794 pending requiredness reviews, 785 pending translations, 9 provisional
  labels and 482 pending unit policy reviews (plus 44 count/number policy gaps).
- Physical fields and metric binding rows are a separate runtime layer:
  `FieldMetricContractBootstrap` currently expects 877 persisted fields,
  including 793 source bindings and 84 evidence metadata bindings.
  The source TSV counts are **not** a substitute for DB reconciliation.
- None of these counts constitutes S2 approval.

## Contract model to qualify per form/version

Persist a reviewed, immutable descriptor referenced by the exact
`fin_lib.lib_form_version.id`, carrying at least:
1. Identity and scope: form code, business domain, category, content mode,
   grain, allowed period types, currency and reporting basis.
2. Structural contract: field code, type, input mode, requiredness,
   null/blank policy, minimum/maximum occurrences, numeric precision,
   scale, rounding, validation constraints, row identity and ordering.
3. Metric binding: metric identity/version, semantic definition, measure
   and dimension grain, unit policy, aggregation policy, transformation
   policy and source/derived/evidence distinction.
4. Presentation: RU/KK/EN/ZH label review, localization of units,
   exact fallback rules, validation messages and visual layout.
5. Immutable evidence: normalized descriptor SHA-256, reviewer,
   independent S2 decision bound to that exact SHA, approval timestamp,
   effective state, revocation and supersession policy.
6. Execution interface: source schema SHA, canonicalization version,
   supported import/export profile, current and historical version binding.

An approved form cannot have any `PENDING_*`, orphan metric, ambiguous
unit policy, incorrect grain, version mismatch or unreviewed derived metric.

## Release gates

**R3_GATE** (mandatory prerequisite)
- Java 21 `mvn clean verify` GREEN on exact commit;
- dedicated PostgreSQL 16 Testcontainers job GREEN with first-save race,
  same-revision race, replay, no-op, immutable history and ROWSET reorder;
- React `npm run check` GREEN and real browser save/reopen UAT evidence.

**R1_GATE_1 — Inventory**
- Parse exact source TSVs, prove 166/122/32/12 identities without duplicates;
- publish form and field/metric CSV + JSON matrices as CI artifacts;
- reconcile 794 source presentation rows against 877 physical DB fields;
- classify gaps; never set approval based solely on presence of a field.

**R1_GATE_2 — Contract governance**
- Reconcile every field with physical `form_version_id` and schema SHA;
- independent reviewer signs exact field+metric+unit+period contract SHA;
- revisions of contracts produce *new* immutable versions; never mutate
  prior approved snapshots or silently rebind existing reports.

**R1_GATE_3 — Functional proof**
- Run structured data-driven contract tests per approved form (required,
  missing, invalid/overflow, decimal locale, scale, period, cross-tenant,
  stale version, wrong metric binding, row duplicates, idempotency);
- full single-record and rowset read/write/reopen/XLSX proof;
- evidence-only forms use a separate safe upload and retention workflow.

**R1_GATE_4 — Release**
- zero unreviewed contracts among the target release scope;
- all approved forms pass PostgreSQL + React browser E2E on one commit;
- authorization and project ownership checks are enforced server-side;
- sign off immutable artifacts, migration forward/backward compatibility
  and failure/recovery/backup acceptance.

## Current commands

```powershell
python scripts/audit-fin-r1-contracts.py
mvn -U -f form-library/pom.xml clean verify
mvn -U -f form-library/pom.xml clean test "-Dtest=ReactLocalPostgresIntegrationTest,R16BPostgresqlContractTest" "-Dfin.postgres.uat.enabled=true"
cd frontend
npm install
npm run check
```

The generated matrices are under `target/fin-r1/` and are uploaded by
`contract-inventory` GitHub Actions job. These artifacts are gap reports,
**not** published approved form contracts.

**Do not disable the field guard or advertise 166/166 as production writable.**
