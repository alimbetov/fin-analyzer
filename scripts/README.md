# FIN FACT — MacBook / Linux quick smoke (Java 21 + Docker)

Для текущих этапов INGEST-2, FACT-0 и **FACT-1 test-only** достаточно
Java 21, Maven и Docker Desktop. **Не нужны** Keycloak, Redis, React,
локальный PostgreSQL Compose, FIN HTAP workers и независимый S2.

Обновить текущую ветку, запустить Docker Desktop и выполнить **из корня**:

```bash
git switch feat/fin-form-library-standalone
git pull
java -version
mvn -version
docker info
bash scripts/smoke-fin-fact.sh --quick
```

Доступные режимы:

| Команда | Проверка | Docker |
|---|---|---|
| `bash scripts/smoke-fin-fact.sh --unit` | 20 Java-only unit-тестов типов/канонического SHA и source reader | Не нужен |
| `bash scripts/smoke-fin-fact.sh --quick` | **6 PostgreSQL 16** smoke-сценариев: F02 readback SHA, месячная схема, 2 месяца, tenant/scope/immutable source, 3 facts+outbox/idempotent replay, TX-Q rollback | Нужен |
| `bash scripts/smoke-fin-fact.sh --extended` | **18 PostgreSQL 16** интеграционных кейсов: в том числе проверка всех 166 форм и исторический upgrade 018→021 | Нужен |

Скрипт запускает `mvn clean test` только с выбранными JUnit-классами,
проверяет **точное ожидаемое число тестов** и отклоняет skipped/failed/error.
База и данные живут в удаляемых после теста Testcontainers; постоянный
`compose.local.yml` volume не трогается. Проверка не
требует запущенного backend или frontend. Повторять smoke можно безопасно.

Точно такой же `--quick` запускается отдельным GitHub Actions
workflow [FIN FACT — Quick PG16 Smoke](../.github/workflows/fin-fact-quick-smoke.yml).
Проверенный успешный smoke доказывает **техническое хранение и
синтетическую тестовую F02 TX-Q**, **не** утверждение бухгалтерских
показателей, S2/IAM или работающие DAG/Worker.

Если Docker Desktop ещё не настроен, запустите `--unit`:
проверка PostgreSQL будет недоступна, и это нельзя обозначать как PG GREEN.

---

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

## Lombok / IntelliJ IDEA (R0.2 local PostgreSQL acceptance)

Backend `pom.xml` now includes Lombok as an optional compile dependency,
configures its annotation processor explicitly for Java 21, and excludes
Lombok from the Spring Boot executable JAR. Only the simple
`FormValidationRule` JPA entity was migrated to `@Getter`, `@Setter`,
`@NoArgsConstructor`; other JPA entities were left unchanged to avoid
accidental equality / proxy / persistence behavior changes. Do not use
`@Data` on these JPA entities.

After updating the branch, reload the `form-library/pom.xml` Maven
project in IntelliJ. Enable annotation processing under
**Settings > Build, Execution, Deployment > Compiler > Annotation Processors**
if your IntelliJ configuration requires it.

```powershell
cd form-library
mvn -Dtest=LombokJpaEntityCompilationUnitTest,PostgresqlMigrationTextCompatibilityUnitTest test
mvn clean verify
```

The first test proves generated Lombok getters/setters are compiled; the second
guards PostgreSQL against reintroduced CLOB/OID mismatches.

If working in a downloaded `fin-analyzer-feat-fin-form-library-standalone`
ZIP folder without a `.git` directory, `git pull` will not update those
files. Download and extract the **latest branch ZIP**, or clone the branch
into a new folder:

```powershell
git clone --branch feat/fin-form-library-standalone --single-branch https://github.com/alimbetov/fin-analyzer.git fin-analyzer-local
```

Keep the existing Docker volume; updating and rebuilding the Backend does not
require deleting PostgreSQL data.

## PostgreSQL category seed: Instant JDBC binding (2026-11-18)

If Liquibase succeeds (all 19 changesets) but application startup fails in
`CatalogCategoryBootstrap.run` while inserting `lib_catalog_seed_manifest`
with `Can't infer the SQL type to use for an instance of java.time.Instant`,
update the working branch. The bootstrap now binds `seeded_at TIMESTAMP WITH TIME ZONE`
as `OffsetDateTime.now(ZoneOffset.UTC)` instead of an untyped `Instant`.
There is no Liquibase schema change, and **do not delete/reset the FIN database**.
The original failed `@Transactional` seed attempt rolls back its category inserts;
on retry the same loader can initialize 47 categories, 188 translations,
389 associations and the SHA manifest.

```powershell
# In form-library of the UPDATED branch; Docker Desktop required for Testcontainers
mvn -Dtest=ReactLocalPostgresIntegrationTest -Dfin.postgres.uat.enabled=true test
mvn spring-boot:run
```

The integration test independently verifies 7 root/40 leaf categories, four
full locale translations, 166 PRIMARY form links, SHA manifest and repeatable
idempotent bootstrap. This is catalog/navigation seed data. It is **not**
synthetic financial fact data; the opt-in F02 end-to-end test creates those
only in its own temporary Testcontainers PostgreSQL.

## Docker Engine 29 + Testcontainers 1.21.3 (Windows IntelliJ)

If `ReactLocalPostgresIntegrationTest` compiles but fails before the
Spring context with `NpipeSocketClientProviderStrategy: Status 400` and
empty `ServerVersion`, check `docker version` for Docker Engine 29.
This is a known client-API incompatibility with Testcontainers 1.21.3.
Both `org.testcontainers:postgresql` and `org.testcontainers:junit-jupiter`
have been upgraded to `1.21.4`, which addresses Docker 29 compatibility
without migrating to Testcontainers 2.x APIs.

```powershell
docker version
docker ps
cd form-library
mvn -U clean test "-Dtest=ReactLocalPostgresIntegrationTest" "-Dfin.postgres.uat.enabled=true"
```

Use a Maven reload in IntelliJ after downloading the new branch. If Docker
CLI itself cannot connect, repair Docker Desktop/Linux engine first. An
unrelated Docker failure should not be masked by disabling Testcontainers;
do not delete the persistent PostgreSQL volume.
