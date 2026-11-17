# FIN Form Library — standalone backend

**Working branch:** `feat/fin-form-library-standalone`. This branch contains only FIN Form Library; the original FIN Analyzer is preserved on `main` and in Git history.

Java 21 · Spring Boot 3.5.7 · Spring Data JPA · Liquibase · H2 in-memory · **no Spring Security**.

## Run

```bash
cd form-library
mvn clean test
mvn spring-boot:run
```

Local server only: `http://127.0.0.1:8086`.

| Route | Description |
|---|---|
| `GET /api/v1/forms` | List exactly 166 catalog forms with explicit unqualified states |
| `GET /api/v1/forms/F02` | Read ordered F02 sections and fields |
| `POST /api/v1/drafts` | Save SINGLE_RECORD F02 or ROWSET draft with Idempotency-Key |
| `GET /api/v1/drafts/{id}?organizationId={uuid}` | Reopen draft at current immutable revision |

Example F02 request (local pilot; replace UUIDs):

```bash
curl -X POST http://127.0.0.1:8086/api/v1/drafts \
  -H "Content-Type: application/json" -H "Idempotency-Key: f02-demo-1" \
  -d '{
    "instanceId": null,
    "organizationId": "00000000-0000-0000-0000-000000000001",
    "economicEntityId": "00000000-0000-0000-0000-000000000002",
    "businessScopeId": "00000000-0000-0000-0000-000000000003",
    "projectId": "00000000-0000-0000-0000-000000000004",
    "formCode": "F02",
    "periodType": "MONTH",
    "periodCode": "2026.02",
    "currency": "KZT",
    "coverage": "PERIOD_ONLY",
    "reportingBasis": "MANAGEMENT",
    "dataSpace": "SYNTHETIC",
    "expectedRevision": 0,
    "values": {"netRevenue": "12000000", "cogs": "7000000", "operatingExpenses": "2000000"},
    "rows": [],
    "createdBy": "local-dev",
    "changeReason": "initial draft"
  }'
```

## Catalog management — R7.A (local presentation edits only)

Current backend supports a **versioned presentation edit workflow** without React:

| Route | Purpose |
|---|---|
| `POST /api/v1/catalog-admin/forms/{formCode}/drafts` | Clone current form version with an explicit `expectedActiveVersionId` |
| `GET /api/v1/catalog-admin/drafts/{id}` | Read draft sections, fields and translations |
| `PUT /api/v1/catalog-admin/drafts/{id}/translations/{locale}` | Edit form/section/field title and description for ru, kk, en, zh-Hans |
| `PUT /api/v1/catalog-admin/drafts/{id}/fields/order` | Reorder existing indicators inside a section |
| `PUT /api/v1/catalog-admin/drafts/{id}/sections/order` | Reorder existing sections |
| `POST /api/v1/catalog-admin/drafts/{id}/publish` | Validate unchanged schema/mapping semantics and switch current catalog version |
| `POST /api/v1/catalog-admin/drafts/{id}/discard` | Close draft without publication |
| `GET /api/v1/catalog-admin/drafts/{id}/events` | View editing event hashes and actor labels |

**Publishing this catalog revision is not financial approval**. Existing finance metric codes and field semantics are immutable in R7.A. Field creation/retyping, financial metric management, currency metadata edits and independent reviewer approval are **not implemented**. Supplied `actor` is not authenticated; this API must remain on localhost.

Specification: [FIN-FORM-LIB-R7.A](docs/FIN-FORM-LIB-R7A-CATALOG-MANAGEMENT.md).

## Architectural ownership

- **`fin_lib`** — system-owned form/indicator codes, versioned definitions, translations and presentation; source of generated input templates, not entered values.
- **`fin_intake`** — drafts, data revisions, period- and metric-addressed cells, author metadata and evidence references.
- **No trusted `fin_fact` or FIN HTAP writer** in this standalone module. Financial approval remains a separate integration concern.
- Existing `FormDraftService` supports `projectId` and `createdBy`, whereas `ReportGridService` does not yet carry a project identity: unification is still pending. Do not claim complete fact-capture support for project+author codes.
- The R1 document includes a *future* Excel/React scenario, but no frontend or JavaScript workspace is retained in this branch.

## Boundaries

- H2 is volatile: restart deletes all drafts. Liquibase creates 30 `lib_*` tables across `fin_lib` and `fin_intake` (001–007 migrations).
- Seed 166 identities + candidate fields from original TSV. Their status is **CANDIDATE_UNQUALIFIED**, not financial approval.
- F02 fields are based on the original FIN-FORM-V2 pilot. ROWSET supports ordered row IDs and positions.
- The draft REST API does **not** approve an external financial source or publish `fin_fact`. Trusted financial authorization and FIN HTAP must remain in original project.
- No authentication or authorization: tenant IDs are caller-supplied, so **never expose this prototype on a public interface**. Server binds to loopback only.
- EVIDENCE entity exists, but trusted file storage/attachment verification and API are intentionally not implemented yet.
- React clipboard UI is specified by the copied TZ but is not included in this Java-only project.

See [copied full TZ](docs/FIN-FORM-LIB-R1-UNIVERSAL-FORM-LIBRARY-AND-CLIPBOARD-TZ.md) and [local adaptation](docs/ADAPTATION.md).
