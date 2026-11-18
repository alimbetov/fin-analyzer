# FIN React Form Studio R0.1 — Critical Review & Integrity Hardening

**Branch:** `feat/fin-form-library-standalone`  
**Scope:** React/TypeScript local-only form editor, existing Spring MVC REST, XLSX round-trip, PostgreSQL persistence.  
**Claim:** code and regression gates committed; **not** a declaration that CI or a browser on the developer's Windows PC has passed. Release remains `HOLD` pending test logs and local E2E.

## Reviewed source and real contracts

- `frontend/src/App.tsx`, `api.ts`, `clipboard.ts`: local Vite SPA (no routing).
- `form-library/src/main/java/kz/fin/formlib/service/FormDraftService.java` and `ReferenceCatalogController.java`: typed form fields, statuses, immutable revisions, pinned slot identity.
- `VersionedImportProfileService.java`: independent no-write preview tickets and guarded import commit.
- `FormXlsxExchangeController.java`: actual XLSX export and server filename contract.
- `CatalogCategoryReadController.java`: 47 categories and 389 links to 166 canonical forms.
- `form-library/src/main/resources/application.yml`: PostgreSQL production/local runtime; test resource remains H2 for fast tests.

## Findings, priorities and changes

| Priority | Finding before review | Remediation / proof |
|---|---|---|
| P0 | Any SINGLE_RECORD catalog entry could appear writable even without typed schema approval | React checks `captureMode=TYPED_DRAFT` (or F02 explicit pilot), SINGLE_RECORD, status DRAFT and actual MANUAL fields. Backend remains final authority. |
| P0 | Original clipboard handler guessed the last column on every multirow paste, potentially writing amounts under wrong indicators | Pure `parseExcelPaste` supports one value column, validated field+value pairs, or validated Nº+field+value rows. Ambiguous/overflow/misaligned ranges are rejected before state changes. |
| P0 | Client used new idempotency keys after an uncertain POST response | Retains the same idempotency key for a retry of the same frozen payload; verifies revision and SHA from Backend after receipt. Distinguishes definitive HTTP rejection from network uncertainty. |
| P0 | Import Commit could succeed but an HTTP failure/readback error allowed unsafe blind retry | Marks uncertain before POST, clears preview and blocks further write until current revision is reloaded and inspected. Definite 4xx errors do not lock the editor. |
| P0 | Report input state could be discarded by changing forms or closing the page | Dirty flag and confirm-on-navigation/beforeunload. Snapshot is only considered saved after read-back SHA/revision reconciliation. |
| P0 | Reopened MVC-created instance lost its entity/project/accounting scope, causing `SLOT_CONTEXT_CHANGED` | Additive REST `DraftView` fields: economicEntityId, businessScopeId, projectId, coverage, reportingBasis, dataSpace. React reuses those pinned values. |
| P0 | Current catalog fields might be different from the version of an old saved revision | Disable writes when `DraftView.formVersion != FormDetail.form.version`; historical versioned editor still required. |
| P1 | Preview lacked a readable financial diff | Added UI table with old/new values and explicit user confirmation before Commit. |
| P1 | No automated frontend CI | Added independent React typecheck, Excel paste unit tests, and Vite build job. |
| P1 | PostgreSQL integrity was not qualified for the new React REST path | Added opt-in `ReactLocalPostgresIntegrationTest` using PostgreSQL 16 Testcontainers, Liquibase, real seeded catalog, two immutable F02 revisions, SHA and stale revision rejection; CI job runs it separately. |
| P1 | No reproducible frontend dependency lock | **Open**: commit an `npm-lock` from `npm install` after a verified local install, then switch CI from `npm install` to `npm ci`. |
| P1 | All 166 forms are navigable, but only approved typed forms are writable | **Open**: finish contract qualification; `ROWS`/EVIDENCE editors intentionally read-only. |
| P1 | Dashboard has counts and catalog but no paged recent report list | **Open**: add Backend server-side recent reports query with scoped organization and pagination after PostgreSQL qualification. |
| P1 | React uses basic async state rather than an RTK/Query or TanStack server state cache | **Deferred**: current footprint is small; revisit when multi-page navigation and concurrency require it. |

## Invariants

1. No financial calculation or approval rule is implemented only in React. Backend validates every write.
2. Browser `X-FIN-UAT-Token` is not an access control token; no Spring Security. Services must remain loopback-only for development.
3. Stable `fieldCode` rather than Excel coordinates is the source of binding truth. Excel clipboard labels/row numbers are verified when pasted with values.
4. Never coerce monetary amounts through `Number` or IEEE754. Values stay strings until Backend canonicalizes with BigDecimal.
5. React must not rewrite immutable context or historical form version.
6. A Save/Commit response timeout **does not imply rollback**. Resolve the status before a new write.
7. H2 GREEN alone does not qualify PostgreSQL. Real artifact/browser UAT also required.

## Acceptance commands

From Windows repository root:

```powershell
docker compose -f compose.local.yml up -d
cd form-library
mvn clean verify
mvn -Dtest=ReactLocalPostgresIntegrationTest -Dfin.postgres.uat.enabled=true test
mvn spring-boot:run
```

In another terminal:

```powershell
cd frontend
npm install
npm run check
npm run dev
```

Open `http://127.0.0.1:5173`. Do not interpret existing `http://127.0.0.1:8086/uat/form-editor` as proof of React browser UAT.

### Required operator smoke (React + real local PostgreSQL)

1. Confirm catalog loads exactly 166 forms, 47 navigation categories; verify four locales and three display modes.
2. Choose F02. Paste a single Excel value column, and a B/C/D numbered+label+value range. Confirm values map to correct fieldCode. A mismatched label/row number must be rejected without changing values.
3. Save F02; verify revision 1 and full SHA in Backend and SQL. Reopen by UUID and verify persisted scope fields.
4. Change value; save revision 2; previous revision 1 must remain immutable. Attempt stale expectedRevision: HTTP 409.
5. Export XLSX V4 with server filename. Change XLSX amount, Preview; verify **zero additional revisions** in PostgreSQL, and see old/new values in UI.
6. Commit once, assert revision increments and content SHA matches the read-back. Repeat with a stale XLSX; expect a negative response.
7. Trigger network failure after a Save/Commit request (proxy/server interruption). Resolve via same-key retry or explicit revision read; **never** blindly create a new revision.
8. Restart Backend and Frontend. Confirm previously stored revisions remain in PostgreSQL. Validate locales/styles are display-only.
9. Verify all interfaces are localhost-only; no external host access; do not use real personal/company financial data without IAM.
10. Verify non-qualified or ROWS forms cannot be submitted as empty financial drafts.

## Remaining release gates

- **Compilation:** no verified local npm `check`, Maven `verify` or CI GREEN for this change set in this ChatGPT session.
- **PostgreSQL:** new Testcontainers contract has been added, but its run outcome is not yet observed.
- **Browser UAT:** currently accessible connected browser tabs include the MVC UAT at :8086, not the new React app at :5173. No screenshot/click-through on React claimed.
- **Dependencies:** lockfile and pinned transitive npm dependencies remain open.
- **IAM / sharing / PDF:** not part of this stage and remain unavailable.
