# FIN-EDIT-R3 — Financial Report Persistence Qualification

Status: **CODE IMPLEMENTED / POSTGRESQL E2E NOT YET GREEN / RELEASE HOLD**.

Branch: `feat/fin-form-library-standalone`. Scope: local FIN Form Studio,
`fin_lib` versioned contracts, `fin_intake` immutable financial reports.

## Business invariants

1. One report slot (organization, entity, business scope, project, form,
   period, coverage, currency, basis, data space) maps to one
   `fin_intake.lib_form_instance`; database unique constraint remains enabled.
2. Initial save creates revision 1; a successful edit of revision N creates
   exactly N+1 with pinned `form_version_id`. Historical values are immutable.
3. A second first-save returns `REPORT_SLOT_ALREADY_EXISTS` (HTTP 409)
   including when the conflict is detected by the PostgreSQL unique index.
   The client must explicitly reopen the slot before saving again.
4. A stale writer is rejected; the losing transaction must produce no revision,
   row or receipt.
5. Replaying the *same* idempotency key and exact request returns the same
   revision ID, number and content SHA. Changing the request with the same key
   is rejected. Ambiguous HTTP 409 must preserve the key until read-back.
6. A new request key with no economic value **and** no row-order change is
   rejected with `NO_FINANCIAL_CHANGES`; no phantom revision.
7. `SINGLE_RECORD` stores values at the revision level and no row entries.
   `ROWSET` stores values in explicit uniquely identified rows, never in
   the top-level values map. Between two revisions, changing only row order
   preserves content SHA but changes `row_order_sha256`.
8. A response is only accepted by React after read-back of the right tenant,
   instance, form, revision, values and full row set. Read-back failures remain
   uncertain rather than treated as a confirmed save.
9. Local UAT `X-FIN-UAT-Token` and an organizationId request parameter are
   **not authentication**. Production release requires verified principal,
   authorization and project/organization ABAC.

## Regression matrix

| Test | Expected result |
|---|---|
| Initial F02 save | 1 instance, 1 revision, 1 receipt |
| Exact idempotent retry | Original revision and SHA; no write |
| Same slot, new creation key | HTTP 409; instance count unchanged |
| Unchanged content/order with new key | HTTP 422; no new revision |
| Two parallel first writers | One success, one conflict, exactly 1 revision |
| Two parallel writers from revision 1 | One revision 2, one conflict |
| Read revisions 1/2 | Historical values and SHA preserved |
| Different organization | HTTP 404; no disclosure |
| ROWSET with top-level values/null row | HTTP 422, no instance/partial write |
| ROWSET reorder only | Same content SHA, new order SHA, revision N+1 |
| XLSX Preview | No financial writes |
| XLSX Commit | One verified immutable revision; replay rejected |
| React unknown concurrent 409 | Preserve original idempotency key |
| React read-back mismatch | No success banner; fail closed |

## Commands

```powershell
# Standard local suite (H2, pure unit tests, no Docker)
mvn -U -f form-library/pom.xml clean verify

# Isolated PostgreSQL 16 Testcontainers gate (Docker required)
mvn -U -f form-library/pom.xml clean test "-Dtest=ReactLocalPostgresIntegrationTest,R16BPostgresqlContractTest" "-Dfin.postgres.uat.enabled=true"

# Browser-facing frontend qualification
cd frontend
npm install
npm run check
```

Run the real Browser E2E for F02 and at least one typed ROWSET after these
suites pass. Query `fin_intake.lib_form_instance`,
`fin_intake.lib_form_revision`, `fin_intake.lib_form_revision_row` and
`fin_intake.lib_form_command_receipt` directly to confirm postconditions.

## Out-of-scope blockers (still RELEASE HOLD)

- Complete, approved and versioned field/metric contract for all 166 forms
  (including historical pinned versions and locale precision).
- EVIDENCE document upload, content integrity and retention.
- IAM, verified organization/project access, tenant isolation and secure sharing.
- Browser/Windows local test evidence, PostgreSQL concurrency GREEN,
  backup/restore, observability and performance qualification.

**Never mark a step GREEN based on committed source or compiled tests alone.**
