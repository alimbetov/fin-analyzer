# FIN HTAP — Execution Strategy and Iterative Implementation Plan (W0–W8)

**Date:** 2026-11-17 · **Version:** 1.0 / AUTHORITATIVE DELIVERY SEQUENCE · **Status:** ENGINEERING STARTED / RELEASE HOLD  
**Repository:** `alimbetov/fin-analyzer` · **sole branch:** `feat/fin-r0-r1a` · never merge to `main` automatically.  
**Contract hierarchy:** [FIN-HTAP-CONSOLIDATED-TZ.md](FIN-HTAP-CONSOLIDATED-TZ.md) is the overall business and technical normative source; [F166 change, impact and partition](FIN-HTAP-FORM-166-INCREMENTAL-RECALCULATION-AND-PARTITION-LIFECYCLE-TZ.md) governs tenant form/catalog change lifecycle; [ADR-R2-10](ADR-R2-10-FORM-FINGERPRINTS-AND-INCREMENTAL-RECALCULATION.md) versioned form SHA-256; [ADR-R2-11](ADR-R2-11-POSTGRES-BATCH-CALCULATION-EXECUTION.md) SQL routine execution; [ADR-R2-08](ADR-R2-08-LIFECYCLE-PARTITION-RECALCULATION.md) eligible cohort GC. Stricter source/trust, R2 execution-permit, immutable-report and independent financial S2 controls survive all precedence decisions.

## 1. Adopted topology and hard prohibitions

**One Java 21 / Spring Boot backend orchestrator**, existing immutable `FACT_SET_PUBLISHED` outbox, plus a **separate durable nonfinancial form-change journal inside the same application and dispatcher**. PostgreSQL 16 is the single transactional source and set-based batch computation engine. Each SQL method is a **versioned, qualified `MethodModule`** implemented as allowlisted, Git/Flyway-reviewed static SQL or typed PostgreSQL `FUNCTION`/bounded `PROCEDURE` without internal commits, executed through JDBC with bound parameters. A catalog table holds immutable *metadata*, routine signature, parameter contract, schema/method version and SHA-256, **not executable arbitrary SQL bodies**.

No new scheduler, no unsandboxed `EXECUTE(sql_from_table)`, no dynamic DDL by SQL method, no one-function-per-tenant/form/period, no mixing of tenant/form/grain/currency, no rewriting sources/results, no implicit FX, no background ACTUAL from draft confirmation. No speculative automatic qualification of all 166 forms. No production activation until a separate signed method permit and independent S2.

**Global physical partition policy:** only large *reconstructible workspace stage payload* is eligible for lifecycle-cohort partitioning and late whole-cohort `DETACH/DROP`. The precise business key `Tenant + FormCode + PeriodType + Period + Currency` remains a logical financial slice identity, further scoped by source/metric/entity/basis/calendars. Default physical design candidates: unpartitioned vs fixed HASH for reusable aggregates, bounded `RANGE(reclaim_epoch)` for disposable workspace stage payload. No child partition per business slice. The existing read-only `WorkspacePartitionMaintenanceUtility` is reused for catalog inspection and dry-run diagnostics; **never enable DROP** until positive membership/drain/reader/lease proof and privileged DDL tests.

## 2. Source event matrix and boundary

| Origin | Trigger | Durable record | May calculate? |
|---|---|---|---|
| Tenant confirms new catalog-form draft | `CatalogXlsxRevisionService.confirm` succeeds with a new revision | New `fin_exec.form_change_journal` in same @Transactional revision+slot CAS | **No ACTUAL**. Automatic validation and future read-only impact preview only |
| Tenant repeats unchanged draft | Service returns prior confirmed revision | **No additional journal event** | No |
| Tenant publishes exact approved FactSet | Verified immutable `fin_fact.fact_set_publication` | Existing `fin_exec.fact_publication_outbox` in publication transaction | Only separately approved variant/ExecutionPermit |
| Global catalog form schema/mapping activation | Server-approved catalog release/definition epoch (future) | Proposed `form_definition_activation` and form-change journal event | Validation/fan-out; financial requalification before execution |
| Method/calendar/FX policy version changes | Independently reviewed version activation (future) | Version-pinned impact plan/registry event | Only new qualified method result identity |

The journal event MUST NOT claim that a tenant has published financial facts. The trustworthy `form_code`, `PeriodType`, `Period`, `Currency`, entity/scope/project/basis and content SHA are resolved from existing immutable draft revision joined with tenant-bound slot/project. A journal's unique immutable `revision_id` is a reference to a fact of *draft revision creation*, not an execution authorization.

## 3. Source-of-truth hashes and change detection

Retain existing `fin_intake.catalog_xlsx_draft.schema_fingerprint`, `original_sha`, `normalized_sha`, optional `document_sha`, and published `snapshot_hash` unchanged and backwards-compatible. Future v2 fingerprints are separate, domain-separated SHA-256 canonicalization version and typed digests for **schema**, financial **mapping**, **presentation**, ordered semantic **content**, raw document bytes, **context-bound form**, selected qualified **source** and method/input **result**. No MD5 or JVM `hashCode()` for authoritative finance identity.

New semantics:
- `same semantic ordered content + same evidence + same exact context` => NO_OP, zero heavy work;
- only presentation changes => no numeric recalculation;
- mapping/PeriodType/calendar/FX or method implementation changes => versioned dependency impact and possible requalification even with unchanged cell values;
- published source changes => newly sealed comparison set and recalculation of **qualified descendants**, never old result UPDATE.
- same content hash across two tenants or currencies cannot share access, event or result solely by digest equality.

## 4. Work breakdown, with stop/go proof (do not skip predecessors)

| Gate | Build in one branch, small incremental commits | Required PASS before advancing |
|---|---|---|
| **W0 / Freeze** | Define trusted FinancialSliceKey/FinancialSeriesKey, PeriodCalendar, input status and hash policy; dependency/permission and DB role threat review; mark S2 pending | Spec consistency, 166 readiness classifications and negative-model tests documented |
| **W1.1 / Form signal (FIRST EXECUTABLE INCREMENT)** | Additive Flyway `V20261117_01__htap_form_change_journal.sql`; append event ONLY for new confirmed `catalog_xlsx_revision` in SAME `CatalogXlsxRevisionService.confirm` DB transaction | `F166-EVT-01..04`, no-op, rollback, concurrent CAS, immutable event, cross-tenant FK, all 166 draft forms, no fact publication |
| **W1.2 / Read-only impact** | Typed event reader with exact tenant+slot+revision+form and source status, mapping SHA/definition version, indexed reverse dependency declarations and read-only ImpactPreview | All 166 produce classified impact; forms without approved numeric contracts show WAITING_QUALIFICATION; no SQL or ACTUAL execution |
| **W1.3 / Trusted source and history** | PeriodCalendar source completeness, versioned source selection, TrustedComparisonSet and sealed financial slice, source publication event wiring | PostgreSQL row/period/currency/tenant/source negatives and sealed count/hash concurrency tests |
| **W2 / Admission** | One effective `fin.execution.mode` OFF by default, global+tenant+resource SQL/JAVA/PREPARE/CLEANUP capacity, durable lease/fence, tenant fairness, physical SQL stop | 2 JVM competing tests: zero oversubscription or stale commit, OLTP connection reserve |
| **W3.1 / SQL Batch pilot** | One FIN-01 REV_NET MoM SYNTHETIC approved trial; static or versioned function; parameter allowlist and SHA; window completeness with batch halo; exact SQL/Java BigDecimal reason/value parity | SQLB-01..14 subset and full FIN-01 golden/negative; no ACTUAL |
| **W3.2 / Auto indicators** | DELTA, MoM/QoQ/YoY, SMA3/6/12, TTM12 FLOW, STDDEV separate versioned descriptors, same SeriesKey; exact period/currency | Java vs SQL deterministic status, windows, rounding, missing/negative and source |
| **W4.1 / Immutable calculation result** | Gen N+1 attempts, fenced batch stage writes and seal, atomic current result pointer CAS, old pinned generations safe | restart/retry/crash and out-of-order r2/r3 2-node tests, no lost immutable receipts |
| **W4.2 / Partition lifecycle** | Durable complete membership ledger, privileged precreate and allowlisted detached-child catalog, reader/lease drain barrier, PostgreSQL 16 `DETACH CONCURRENTLY`/FINALIZE/DROP journal | multi-tenant pinned cohort must not drop; crash between DDL steps; no DEFAULT; workers denied DDL; real PostgreSQL |
| **W5 / Qualified DAG** | Cross-form method dependency DAG, method migration, versioned catalog form definition activation and paged targeted fan-out | Only reviewed input bindings, actual S2 per variant and no launch of unqualified forms |
| **W6 / Performance** | Compare DIRECT, unpartitioned AGG, fixed HASH AGG, RANGE lifecycle stages; direct SQL vs reviewed FUNCTION vs bounded batches; DELETE vs DETACH/DROP | Real workloads, tenant skew, 1/2/4/8 SQL workers, WAL/temp, OLTP p95/p99, end-to-end including build+cleanup, thresholds frozen in advance |
| **W7 / Security+Ops** | Real least-privileged DB roles, audited RLS/pool reset, DDL maintenance role, metrics/tracing, DLQ, recovery and retention | Multi-tenant ABAC, SQL module no DDL, policy downgrade denied, incident drill |
| **W8 / Acceptance** | Same HEAD full CI/Backend Domain, PostgreSQL negative suite, FIN-01 SQL/Java parity, S2 reviews and operational release record | No P0, independent method S2, separate ACTUAL/release approval |

## 5. Current W1.1 implementation declaration (status must be read with CI)

As part of this initial engineering slice (not a claim of fully verified GREEN):
- `backend/src/main/resources/db/migration/V20261117_01__htap_form_change_journal.sql` creates `fin_exec.form_change_journal` with tenant+slot FK, revision+slot FK, unique revision event, immutable UPDATE/DELETE trigger, indexes for scoped and pending scan.
- `backend/src/main/java/kz/fin/backend/intake/CatalogXlsxRevisionService.java` appends `TENANT_FORM_REVISION_CONFIRMED` after CAS inside existing `@Transactional` method, without any second outbox or scheduler.
- `backend/src/test/java/kz/fin/backend/intake/CatalogXlsxRoundTripIntegrationTest.java` verifies one event per all 166 codes, zero event for duplicate, two revisions/two events on changed content or two concurrent success attempts, rollback of event+revision+slot, immutable/tenant FK and no `FACT_SET_PUBLISHED`/analysis job.
- Existing read-only partition inspector is unchanged and keeps `DETACH/DROP` unavailable.

**Not implemented:** a consumer of form-change journal, activation of global 166 form definitions, v2 SHA manifest, versioned impacted-period graph, actual calculation SQL/Java dispatcher, partitioned physical workspace, DROP reaper or auto financial ACTUAL. Mark those as planned and only turn ON with independent evidence.

## 6. Code and test safety practices

- **Never edit historical Flyway**; add only monotonic version scripts. For each migration test fresh PostgreSQL 16 and upgrade from prior committed schema, preserve data/indexes/checksums, then test rollback/retry and referential integrity.
- **RED before GREEN**, test actual PostgreSQL 16, not H2-only/mock success. A new P0 invariant must have both negative (reject) and positive (accepted exact source) test, and deterministic output. A test suite named PASS is not a proof until CI/SHA is checked.
- **Database worker privileges:** ordinary Spring app role may append its event while confirming; it must not perform partition DDL or be owner of `fin_fact` in production. Dedicated maintenance DDL role is separate and not inherited by SQL routine worker.
- **Financial safety:** SQL routines MUST operate on SEALED input, exact trusted selection, tenant, form, PeriodType, Period, Currency, metric+version+dimension, basis/dataSpace and calendar. Period is in point key but not in window grouping. Missing/partial periods must never be filled with zero automatically; currency conversion is a separately reviewed transform.
- **SQL routine registry:** DB stores metadata/digest, the vetted SQL is in Git/Flyway; never arbitrary user SQL from table and no second PostgreSQL scheduler. PostgreSQL routine executes bounded set-based work in caller-managed transactions, no internal `COMMIT`.
- **GC:** a single expired tenant/form slice cannot cause `DROP` of a shared cohort; only a full drained cohort can be detached/dropped. Physical partitions are server precreated by date/retention class, not named after source hash/tenant/currency.
- **Security:** event read/write is tenant-scoped with composite FKs and authenticated controller. Application-level `FIN_* ` ABAC remains authoritative; no SQL text/table name from a tenant user.
- **Ops:** record exact SHA for migration, tests, CI, S2, performance and release decisions; `RELEASE_HOLD` remains until separately removed by approved process.

## 7. W1.1 Definition of Done and immediate follow-up

To mark W1.1 GREEN, record evidence for:
1. PostgreSQL Flyway migration fresh database + from previous V20261116_13 state, correct FK/UNIQUE/immutable event trigger; verify no unexpected DDL.
2. For 166 forms, `CatalogXlsxRevisionService.confirm` adds exactly one event to **the same commit**, including documentary and unapproved schema forms, but no finance source publication or result.
3. Same-content idempotency, new ordered rows despite unchanged legacy SHA, document hash changes, simultaneous confirmations and external transaction rollback.
4. Cross-tenant forged event denied, irreversible/immutable journal row tested, no event for unsuccessful confirm.
5. Current HEAD's `FIN Backend Domain` and `FIN CI` outcomes explicitly known and attributed; no skipped test or stale run claims.

Then implement W1.2: a **read-only, tenant-verified form-change impact preview** backed by the existing catalog form classification and explicit method-binding registry, not any financial SQL executor. Keep W2–W4 blocked until their own RED proofs exist.

## 8. Rollback and recovery

If release with W1.1 fails: disable any future *consumer* (currently none) and revert the Java event emission only with a separately reviewed migration/compatibility plan; **do not drop the immutable journal containing committed history** or rewrite an old migration. For a failed form confirmation, ordinary PostgreSQL transaction rollback removes slot-head change, revision and event atomically. On restart, an event with no delivery record remains durable and unprocessed until W1.2; duplicate scanning must use immutable event_id/revision_id and idempotent delivery in a **separate** table.

**SQL/partition functionality is intentionally absent from W1.1.** No on-demand physical partition creation and no background work that could affect tenant OLTP have been enabled.
