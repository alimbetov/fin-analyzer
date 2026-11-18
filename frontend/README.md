# FIN Form Studio — local React/TypeScript

## Layout / services

```text
fin-analyzer/
  compose.local.yml              PostgreSQL 16, loopback 127.0.0.1:5433
  form-library/                  Spring Boot Java 21, loopback 127.0.0.1:8086
  frontend/                      React 19 + TypeScript + Vite, loopback 127.0.0.1:5173
```

No React Router, no SPA routes, no Keycloak, no Spring Security. This is **local UAT only**. The `X-FIN-UAT-Token` header is a **correlation label, not an access credential**: backend does not validate it and it cannot grant rights. Never deploy this application publicly, never open Backend port 8086 to the network, and never use real customer/financial data in this local-only no-IAM mode.

## Start on Windows (PowerShell)

From repository root:

```powershell
docker compose -f compose.local.yml up -d
docker compose -f compose.local.yml ps
```

The development database persists in Docker volume `fin_pgdata`. The Compose password `fin_local_password` is a localhost-only example; for a distinct password set `FIN_FORM_DB_PASSWORD` before container creation, then set it for Backend too. **Changing the password environment variable does not alter the password inside an already initialized PostgreSQL volume.** Never commit a production password.

In terminal 1 (Backend, Java 21):

```powershell
cd form-library
mvn spring-boot:run
```

Spring Boot applies Liquibase migrations and seeds 166 forms + 47 categories and 11 currencies. Runtime `application.yml` defaults to PostgreSQL:

```text
jdbc:postgresql://127.0.0.1:5433/fin_form_library
```

You can override `FIN_FORM_DB_URL`, `FIN_FORM_DB_USER`, `FIN_FORM_DB_PASSWORD` when needed. Keep `FIN_FORM_UAT_EDITOR_ENABLED=false` — React uses independent REST endpoints and does not need Thymeleaf.

In terminal 2 (Frontend, Node 22 or Node 20.19+):

```powershell
cd frontend
npm install
npm run dev
```

Open **http://127.0.0.1:5173/**.

Vite proxies `/api/v1/*` to `http://127.0.0.1:8086/api/v1/*`; no browser CORS changes required.

### IntelliJ IDEA

- Import `form-library/pom.xml` as Maven; run `FormLibraryApplication` on Java 21.
- Open terminal in `frontend`, run `npm install` and `npm run dev`. IntelliJ supports an npm Run Configuration with `package.json=frontend/package.json`, command `run`, script `dev`.
- PostgreSQL Data Source: host `127.0.0.1`, port `5433`, database `fin_form_library`, user `fin`, password matching `FIN_FORM_DB_PASSWORD`.
- Verify Backend: http://127.0.0.1:8086/api/v1/catalog/navigation?locale=ru
- Verify React proxy: http://127.0.0.1:5173/api/v1/catalog/category-tree?locale=ru

## Functionality

- Dashboard: actual catalog counts, editor progress, revision/SHA.
- Sidebar navigation through the **47 real fin_lib categories** and **166 associated canonical forms**. Search all forms.
- `EXCEL`, `CLASSIC`, `DASHBOARD` React render modes, field titles from backend translations.
- Period selection: `D,W,DEC,M,Q,H,Y`; 11 active currencies; four locales.
- `SINGLE_RECORD`: manual field values and backend `POST /api/v1/drafts` with idempotency key and `expectedRevision`. Existing revision context remains fixed.
- Excel paste: copy a column of Excel values, select the first input in `EXCEL` mode, press Ctrl+V; values distribute vertically and can be navigated with Enter/Up/Down. Clipboard data is parsed as plain text; no `innerHTML`, no formula evaluation, no decimal conversion using binary floating-point. Backend remains canonical validator.
- Export XLSX V4 with a backend-generated filename from `Content-Disposition`.
- Import XLSX by registering the versioned import profile, Preview with no financial write, then Commit followed by read-back reconciliation of values, SHA and revision.
- Persist synthetic local organization/scope UUIDs in browser localStorage. Use **Context** icon to set existing local UUIDs and reopen an instance.

**Current explicit limitations:** No persisted project/organization browsing, no IAM, no true PDF/HTML renderer yet, no React router, no `ROWS` editor yet, no authoritative bank/investor sharing or download entitlement. Forms other than `SINGLE_RECORD` are browseable but SAVE is disabled to avoid corrupt or empty financial reports. The three render styles are presentation-only, not three independent financial models. Context UUIDs are not proof of tenant authorization.

## Verification

From `frontend`:

```powershell
npm run typecheck
npm run build
```

From `form-library`:

```powershell
mvn clean verify
```

Existing fast tests run H2 using **test-only** `src/test/resources/application.yml`. The running application uses PostgreSQL. The 231-case R19 matrix is opt-in and previously qualified only on H2. Do not run synthetic tests against your regular application database.

```powershell
mvn -Dtest=R19ImportMatrixIntegrationTest -Dfin.uat.matrix.enabled=true test
```

To qualify PostgreSQL persistence, use a separate Testcontainers PostgreSQL datasource rather than overwriting real local UAT data.

## Manual end-to-end smoke

1. Start Postgres + Backend + Frontend. See 166 forms and 47 categories.
2. Choose F02, `M` / `2026-11`, KZT, enter values using keyboard or paste from Excel.
3. Save; record revision 1 and SHA. Export XLSX. Its `REPORT`/ `PARAMETERS` must match revision 1.
4. Edit financial values in exported XLSX; use Preview. Revision count must remain unchanged.
5. Commit and verify a new revision/SHA appears in the React status. Export again to confirm new filename.
6. Restart Backend and React. In the same browser open the previous instance UUID using the context panel. PostgreSQL must preserve the report and its revisions.
7. Repeat for period/currency/style variations. `ROWS` reports are **not writable** in React yet.

Release state is **HOLD** until real local `npm run build`, Maven and PostgreSQL end-to-end test evidence is available.
