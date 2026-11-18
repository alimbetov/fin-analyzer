# FIN R16.B — Additive contract persistence (implementation evidence)

**Branch:** `feat/fin-form-library-standalone`  
**Status:** CODE COMMITTED / VERIFICATION HOLD (no Maven or PostgreSQL green evidence yet).

## Existing model retained
- `fin_lib.lib_form_definition`, `lib_form_version`, `lib_form_field`, `lib_metric_definition` and `lib_field_binding_contract` are not replaced.
- `fin_intake` data and revision tables remain untouched.
- 166 catalog identities are not re-seeded or re-qualified.
- Only additive Liquibase changesets `014` and PostgreSQL-specific `015` were introduced.

## New candidates
- `lib_form_contract_policy`: immutable candidate policy keyed to the existing form-version UUID, canonicalization version, source descriptor, compatibility class; only `CANDIDATE_UNQUALIFIED`.
- `lib_form_field_policy`: requiredness/cardinality supplement, precision/scale, explicit rounding and absence semantics, aggregation and economic definition.
- `lib_form_allowed_period`: existing period type FK plus calendar, basis and coverage.
- `lib_form_field_dictionary`: dictionary code and version, referencing existing form-version field.
- `lib_form_contract_metric_binding`: reference to existing metric identity with explicit contract version and transformation policy.
- PostgreSQL immutable triggers on all five new policy tables; existing R7 editorial version workflows unaffected.

## Tests and commands

```bash
mvn -f form-library/pom.xml clean verify
mvn -f form-library/pom.xml -Dtest=R16BPostgresqlContractTest test
```

`R16BPostgresqlContractTest` uses PostgreSQL 16 Testcontainers and the **actual 014/015 changelogs**, with a minimal fixture of prerequisite older tables. It covers invalid scale, missing field, invalid period, unknown metric, duplicate policy, unknown form version and PostgreSQL immutable UPDATE/DELETE.

**Limitations / required evidence:** This targeted fixture is **not** proof that a clean PostgreSQL database can migrate the entire 001–015 history. Older migrations use H2-oriented CLOB and must receive a separate PostgreSQL clean-install/upgrade compatibility audit before any PostgreSQL deployment. No PostgreSQL container, Maven build or GitHub Actions run was executed/confirmed in this change. These gates remain HOLD.

## Next acceptance gates
1. Obtain Java 21 `clean verify` output with full surefire report and HEAD SHA.
2. Obtain PostgreSQL 16 Testcontainers execution evidence on identical HEAD SHA.
3. Validate whole existing Liquibase chain on PostgreSQL (not only isolated 014/015), preserving all historical checksums. No historical changeset rewrites.
4. Verify H2 bootstrap + JPA `ddl-auto=validate` and the existing 166-code catalog regression.
5. Independent technical S1 followed by financial S2; these are **not** implemented here. No ACTUAL financial execution gate granted.
