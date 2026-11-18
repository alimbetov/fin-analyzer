# FIN Local Smoke — Windows

The PowerShell smoke checks the **running** FIN deployment at `127.0.0.1`:

- PostgreSQL 16 inside Docker Compose with local user `FIN`, password `FIN`, database `fin_form_library`, port `5433`
- Spring Boot REST at `127.0.0.1:8086`
- React/Vite UI at `127.0.0.1:5173` and its API proxy
- 166 catalog forms, 47 categories, 389 associations, F02 and KZT
- New F02 financial revision and readback SHA
- Exact replay of a save with the same Idempotency-Key
- Revision 2 and immutable history, stale update rejected with HTTP 409
- XLSX V4 export with period/currency/style/locale/version/revision/SHA filename
- XLSX V4 modification using **PowerShell/.NET ZIP OpenXML**, with no Excel or Python dependency
- Versioned XLSX Preview including raw file SHA, no financial write
- Single Commit to revision 3 and rejection of a repeated commit

Prerequisites: **PowerShell 7.2+** (`pwsh`), Docker Desktop, Java 21, Maven, Node.js 22 and npm. No Keycloak or Spring Security. Use synthetic/local data only.

## Start the local stack in three terminals

**Terminal 1 — from the repository root:**

```powershell
git pull
docker compose -f compose.local.yml up -d
```

**Terminal 2 — Backend:**

```powershell
cd form-library
mvn spring-boot:run
```

**Terminal 3 — Frontend:**

```powershell
cd frontend
npm install
npm run dev
```

## Run smoke from repository root in a fourth terminal

```powershell
pwsh -NoProfile -File .\scripts\smoke-local.ps1
```

A faster backend-only option skips XLSX Preview/Commit:

```powershell
pwsh -NoProfile -File .\scripts\smoke-local.ps1 -SkipImport
```

Skip the Docker-specific check if your existing PostgreSQL is running outside Compose (this does NOT skip PostgreSQL REST persistence checks):

```powershell
pwsh -NoProfile -File .\scripts\smoke-local.ps1 -SkipDocker
```

The script exits **0 only if every selected check passes**; otherwise exits **1**. The `PASS/FAIL` result JSON, original XLSX and modified XLSX are stored under a fresh `fin-smoke-<random>` directory in Windows `$env:TEMP`; the script prints that path. Each run creates a new synthetic organization and report, never deletes existing data. Successful test rows remain in the local development database, so re-running the script accumulates small amounts of synthetic test data.

**What this test does not prove:** a browser click-through of the form editor, Ctrl+V keyboard interactions, all 166 writable schemas, PDF/HTML renderers, access control, production readiness or throughput/performance. Those require separate acceptance tests. For build and database isolation checks, also run:

```powershell
# from frontend
npm run check

# from form-library
mvn clean verify
mvn -Dtest=ReactLocalPostgresIntegrationTest -Dfin.postgres.uat.enabled=true test
```

The Testcontainers test uses an isolated temporary database and **does not touch** the local Compose volume.

### Common failures

- **PostgreSQL auth failed:** old Docker volume was initialized with different credentials. The `POSTGRES_USER` and `POSTGRES_PASSWORD` variables do not change users in an existing PostgreSQL cluster. Preserve the data and migrate the role/password or initialize a disposable fresh volume.
- **Connection refused 8086:** Backend has not started or Liquibase migration failed.
- **Connection refused 5173:** Vite is not running or is configured for a different host/port.
- **HTTP 422 on Save:** check F02 typed contract and fields, period and money format.
- **XLSX failed:** inspect the reported step and local XLSX in the printed temp path. Do not retry the same Commit blindly after a transport timeout: read the current revision first.
- **`pwsh` not found:** install PowerShell 7 and restart your terminal; built-in Windows PowerShell 5.1 cannot use the script's multipart `-Form` invocation.

**Security:** `FIN/FIN` and unprotected Backend endpoints are strictly for localhost development. Neither the smoke test nor the `X-FIN-UAT-Token` is authentication.

## Troubleshooting: PostgreSQL migration rejected CLOB (2026-11-18)

If Spring Boot connects to PostgreSQL 16 but Liquibase fails on `001-lib-schema.sql`
with `ERROR: type "clob" does not exist`, the local working copy predates
the PostgreSQL TEXT mapping fix. Pull the current
`feat/fin-form-library-standalone` branch or re-download it if the folder
was extracted from a GitHub ZIP. The fix covers **all five** SQL columns:
`typed_params`, `values_json` (revision and row), `metadata_json`,
and import-profile `binding_json` in `017`. Four corresponding JPA fields
no longer use `@Lob`, which maps Strings to PostgreSQL OID large objects.

From the project root, after updating the code:

```powershell
cd form-library
mvn -Dtest=PostgresqlMigrationTextCompatibilityUnitTest test
mvn clean package -DskipTests
mvn spring-boot:run
```

Do **not** disable Liquibase, use Hibernate `ddl-auto=update`, drop the
Docker volume, or create a global PostgreSQL domain called `CLOB`.
The original failed changeset was not committed to
`public.databasechangelog` (`Previously run: 0` in that log), so
a clean PostgreSQL transaction can retry it with the fixed SQL while
preserving the database. On a database where the original migrations were
already successfully applied (e.g., H2), modifying a Liquibase changeset
can cause checksum validation to fail: that environment requires separate
migration planning; do not clear checksums blindly.
