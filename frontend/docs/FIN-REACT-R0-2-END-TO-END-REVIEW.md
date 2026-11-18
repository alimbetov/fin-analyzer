# FIN React Form Studio R0.2 — Frontend → REST → PostgreSQL process review

**Working branch:** `feat/fin-form-library-standalone`

**Status:** code committed, acceptance **HOLD** until actual CI logs, Windows local execution and user browser smoke. The review is based on real GitHub source, not an invented UI screenshot.

## Scope, system boundary and actual process

1. React (`frontend/src/App.tsx`) fetches form navigation, category tree, currencies, allowed period catalog and a versioned form definition.
2. Editor eligibility is evaluated by pure `canEditForm` policy. **Backend always remains the authority**: only approved typed `SINGLE_RECORD` contracts can be posted; the 166 catalog entries are not all writable.
3. A user selects `periodType`, `periodCode`, `currency`, organization/entity/business/project UAT scope. Editing a value marks the document dirty and freezes the financial slot parameters.
4. Numeric amounts stay decimal strings; the backend normalizes with Java `BigDecimal` and checks currency minor units. No JavaScript `Number` conversion.
5. Save posts to existing `/api/v1/drafts` using stable `Idempotency-Key`, `expectedRevision`, scope and exact fields. After receipt, the UI reads the saved revision and validates `revisionNo` and canonical `contentSha256`.
6. XLSX export is produced by Backend from pinned data and includes the unified filename and V4 `REPORT`/`PARAMETERS` sheets.
7. XLSX Preview reads the uploaded bytes without writing financial facts. UI verifies profile, tenant, instance, revision, file SHA-256 and current content SHA against server-returned ticket before exposing Commit.
8. Commit requires visual old/new diff and explicit user confirmation. It uses the backend's preview ticket/transaction and performs a post-commit read-back. A network failure is treated as **uncertain**, not as a rollback; new writes stay locked until reconciliation.
9. Underlying database is PostgreSQL 16 via localhost Docker. H2 remains isolated to fast tests; a new isolated PostgreSQL Testcontainers acceptance covers the full F02 upload cycle.

## Confirmed P0 corrections

| Path | Identified failure mode | Applied correction |
|---|---|---|
| Form navigation | Old form fields remained visible between selection and asynchronous new `GET`; wrong field-to-form association possible | Synchronous reset of `form`, values, revision/preview and pending file on form change; pure editor policy rejects formCode mismatches |
| UAT scope | UI changed `scope` before asking to discard existing financial values | Scope reset is now performed only after user confirmation, in one state transition |
| Slot metadata | Editing after values entered could change period/currency/organization without explicit financial change review | Slot selectors disabled while dirty, during uncertain writes and for existing immutable instances |
| XLSX Preview | UI previously trusted `expectedRevision` only; wrong organization/file could be shown as accepted | Verify exact instanceId, organizationId, profileId, revision, raw upload SHA-256 and server read-back content SHA |
| XLSX Commit | Dangerous repeat after an uncertain response | Commit locks until explicit current-snapshot reconciliation; server consumes preview ticket under transaction; safe negative replay case in PostgreSQL test |
| Editor eligibility | Complex runtime rule embedded in a large React component | Pure `editorPolicy.ts` with targeted tests for unqualified, ROWS, changed form, closed workflow and pinned historic versions |
| Catalog navigation | 47 categories and 389 links queried from PostgreSQL for every navigation request | Locale-keyed bounded Caffeine namespace `category-tree`, normal catalog invalidation; no tenant/private report data cached |

## Test coverage added or updated

- `frontend/src/clipboard.test.ts`: value column, verified 2/3-column maps, rejected misaligned financial rows and shape errors.
- `frontend/src/editorPolicy.test.ts`: form-code mismatch, typed write gates, historical version mismatch, preview identity binding.
- `frontend/package.json`: `npm run check` runs typecheck, both test suites and Vite build.
- `form-library/src/test/java/kz/fin/formlib/ReactLocalPostgresIntegrationTest.java`: PostgreSQL 16 Testcontainers, Liquibase, seeded 166/47 catalog, immutable F02 revision 1/2, stale write rejected, export XLSX V4, edit workbook, preview with NO fin_intake revision, commit revision 3, read-back and SHA check, replay ticket rejected.
- `.github/workflows/fin-form-studio-local-ci.yml`: separate frontend build, backend and isolated PostgreSQL jobs. Actual GREEN must be inspected for the exact new SHA.

## Required commands (repository root; Windows PowerShell)

```powershell
docker compose -f compose.local.yml up -d
cd frontend
npm install
npm run check
npm run dev
```

Use another terminal:

```powershell
cd form-library
mvn clean verify
mvn -Dtest=ReactLocalPostgresIntegrationTest -Dfin.postgres.uat.enabled=true test
mvn spring-boot:run
```

Local URLs:

- React: `http://127.0.0.1:5173`
- Backend form catalog: `http://127.0.0.1:8086/api/v1/catalog/navigation?locale=ru`
- Frontend proxy to catalog: `http://127.0.0.1:5173/api/v1/catalog/category-tree?locale=ru`
- PostgreSQL host: `127.0.0.1:5433`

Do not publish the backend port to LAN/Internet: **the correlation header is NOT authentication**, and Spring Security is deliberately excluded from this local-only stage.

## Remaining release blocks

1. **Verified builds:** cannot declare npm/TypeScript, Maven or GitHub Actions GREEN without their output.
2. **Real local browser:** no click-through or screenshot on the running React Vite page has yet been observed. Must test keyboard navigation, tab/newline Excel paste, category expansion, file import, mobile breakpoints and errors on Windows.
3. **Stable frontend dependency lock:** `package-lock.json` not committed; create under Node 22 and use `npm ci` after lockfile approval.
4. **Historical form versions:** React only renders current catalog version; if an existing revision pins an older version, editing is intentionally blocked.
5. **Complex forms:** `ROWS` and evidence data require separate typed UI adapters. Never claim all 166 forms support input.
6. **Financial sharing:** do not enable external share links, bank/investor access or production traffic until authentication/authorization and a signed publication contract exist.
7. **No client write-through cache:** the Caffeine change is restricted to publicly readable category navigation. `fin_intake` remains fully transactional.

### Acceptance decisions

- Functional P0 code hardening: **implemented; tests added, not yet GREEN**.
- PostgreSQL XLSX Preview/Commit E2E: **test authored; execution pending**.
- Windows desktop Browser E2E: **not tested**.
- Release: **HOLD**.

## Additional R0.2 safeguards

- **HTTP 409 during Import Commit:** could represent a consumed Preview ticket after a prior successful commit whose response was lost. UI keeps the operation uncertain and demands a current revision read-back, rather than unlocking the editor as if every HTTP 409 were a safe rejection.
- **Render error recovery:** `ErrorBoundary` wraps the React root. Fatal UI render exceptions show a recoverable error screen instead of a blank page. Recovery does not claim financial changes were saved.
- **Responsive navigation:** category sidebar is scrollable and opens as a mobile overlay at viewport widths <= 920 px. The default mobile state leaves the financial editor visible.
- **Full XLSX Preview identity:** `PreviewTicket` in TypeScript now includes backend-returned `profileId`, `instanceId`, `organizationId`, `uploadSha256`, expiry and values. A preview whose file digest or context does not match is rejected locally (backend independently rechecks at Commit).

## Future refactoring (not part of the verified implementation)

The current `App.tsx` remains a monolithic component. Only after the R0.2 functional gates pass, split it into `NavigationSidebar`, `DashboardOverview`, `ReportEditor`, `ExcelImportPanel`, and typed hooks for catalog and report commands. Do not perform an aesthetic rewrite while financial persistence is still awaiting green qualification. A genuine ROWS grid should reuse the Backend's existing `/api/v1/report-grids` contracts where appropriate instead of inventing a second intake model.
