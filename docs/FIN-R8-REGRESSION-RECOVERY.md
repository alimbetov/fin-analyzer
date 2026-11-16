# FIN-R8 — Regression Recovery and Flyway compatibility

**Branch:** `feat/fin-r0-r1a`. **Scope:** unblock CI only; no main merge, no change of financial methodology approval. **Release:** HOLD until full same-SHA evidence and independent review.

## Root causes from CI #43875887955 (491269f8)

- TypeScript TS1005 at `template-transfer-smoke.test.ts:36` and `template-transfer-smoke.ts:99`: XLSX load expression corrected with explicit typed byte buffers.
- Duplicate Flyway `20261116.08`: independent `CAT-A3.1` and later SKU supplemental migration both declared the same version. The earlier CAT migration keeps `08` byte-for-byte; the later SKU migration is now `V20261116_09__sku_original_and_supplemental_analysis.sql`. Do not edit an already applied migration body.
- CI accepts no unverified success: skipped macOS job and failed acceptance gate are a consequence of failed prerequisites.

## Repeatable protection

Run before compiling or running Flyway:

```bash
node --test scripts/check-flyway-versions.test.mjs
node scripts/check-flyway-versions.mjs
```

The checker normalizes Flyway integer segments, rejects duplicate semantic versions and malformed versioned SQL filenames. Both CI workflows execute it; `FIN CI` requires the PostgreSQL upgrade tests in JUnit reports.

## PostgreSQL migration acceptance

`FlywayMigrationRecoveryIntegrationTest` uses real PostgreSQL 16 Testcontainers:

1. Migrate a clean database through all versions; verify `08` and `09` exist once and repeated migrate is a no-op.
2. Build an existing database at `20261116.08` (original CAT migration); store an independent marker and checksum, upgrade to `09`; verify no loss, no checksum modification and repeat migrate is a no-op.

**Operational preflight on any existing PostgreSQL before deploying:**

```sql
SELECT installed_rank, version, description, checksum, success
FROM flyway_schema_history
WHERE version IN ('20261116.08','20261116.09')
ORDER BY installed_rank;
```

The expected historical `08` is `cat_a31_method_reviews`. A database with SKU already recorded at `08` is **not** covered by this migration path; stop and assess checksum/history manually, without blindly invoking `flyway repair` or editing deployed migrations. The test simulates an existing migrated database; it is **not** proof that any actual customer database was upgraded.

## Same-SHA qualification

```bash
npm ci --no-audit --no-fund
npm run typecheck
npm test
npm run build
npm run test:e2e -- --project=chromium
mvn -B -ntp -f backend/pom.xml verify
```

GitHub `FIN CI` additionally requires macOS Chromium/WebKit and `acceptance-gate` on the same commit SHA. Report `GREEN` only after verifying the Actions run, JUnit and structural XLSX evidence. Manual MacBook UAT, independent financial S2 approval and real Keycloak end-to-end remain separate HOLD gates.
