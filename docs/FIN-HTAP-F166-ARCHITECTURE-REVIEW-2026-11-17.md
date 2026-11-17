# FIN HTAP F166 — Data & System Architecture Review (including SHA-256 fingerprints)

**Date:** 2026-11-17 · **Internal review only; independent S2 pending**  
**Normative:** [F166 Change/Partition TZ](FIN-HTAP-FORM-166-INCREMENTAL-RECALCULATION-AND-PARTITION-LIFECYCLE-TZ.md), [ADR-R2-10 Hashes](ADR-R2-10-FORM-FINGERPRINTS-AND-INCREMENTAL-RECALCULATION.md), [FIN HTAP consolidated](FIN-HTAP-CONSOLIDATED-TZ.md).
**Reviewed code baseline:** `e5155afcd963c61b967ec9d5ea9e15fc629b1a08`; `CatalogCaptureRegistry`, `CatalogXlsxRevisionService.confirm`, `CatalogXlsxRoundTripService.schemaFingerprint`, `CatalogIssuedTemplateService`, `IndicatorRelationshipRegistry`, `MethodVariantRegistry`, `SingleExecutorScheduler`, `WorkspacePartitionMaintenanceUtility`, migrations V2 and V20261116_01/02/10/11. Architecture commitments in this review are NOT claims of executed migrations or passing tests.

## Mandatory findings and remedies

| ID | Priority | Verified limitation or unresolved target risk | Corrective design and closure |
|---|---|---|---|
| F166-REV-01 | P0 | “Form update” conflates tenant unqualified draft confirmation, published financial source and global catalog definition deployment | Distinct typed event paths and trust boundaries; only trusted FactSet publication may activate qualified financial compute |
| F166-REV-02 | P0 | `CatalogXlsxRevisionService.confirm()` is transactional and CAS-locked, but has no form-change outbox | Add same-transaction, tenant-bound immutable change journal; no-op revision -> zero events; Testcontainers rollback/race proof |
| F166-REV-03 | P0 | Trusted `fin_exec.fact_publication_outbox` has CHECK event_type=FACT_SET_PUBLISHED | Preserve it unchanged, add separate nonfinancial journal consumed by **the same** scheduler, no second general-purpose executor |
| F166-REV-04 | P0 | `analysis_run.event_id` and `analysis_task(run_id,method_code)` unique prevent versioned event/method rerun using old semantics | Additive versioned recalc_plan+intent with semantic hash; preserve legacy data and reports |
| F166-REV-05 | P0 | Catalog has 166 codes but only 122 typed drafts, 12 evidence-only, 32 unapproved schemas; existing registry relations are not production qualified | 166/166 deterministic change classification, numeric compute only after approved metric bindings/source/method/permit |
| F166-REV-06 | P0 | Logical 5-axis key can be mistaken for physical PostgreSQL child relation; form slot also binds entity/scope/coverage/scale/basis | Tenant/form/grain/period/currency slice plus full source context; bounded RANGE reclaim cohorts, not physical child per form/tenant/period |
| F166-REV-07 | P0 | One changed monthly value may affect future windows, YoY and cross-form methods, not only its own month | Versioned reversed dependency DAG and canonical calendar-ordinal impact expansion; unknown horizon blocked/admitted full-series only |
| F166-REV-08 | P0 | Old task can complete after new form confirmation/publication and incorrectly promote stale current output | CAS on source/catalog/method version vector, pinned immutable history, no stale-head promotion |
| F166-REV-09 | P0 | Static 166-form catalog loaded from code/TSV; rolling deploy may mix old/new fingerprints and mappings | Staged manifest + two-phase qualified catalog activation, worker epoch handshake; incompatible workers fail closed, old issued templates remain pinned |
| F166-REV-10 | P0 | Existing `schemaFingerprint` covers only catalog format/fields, not all accounting mapping/approval rules | Distinct `schema_hash_v2` and `mapping_hash_v2` with exact inputs, schema policy and canonical version, migration without rewriting v1 |
| F166-REV-11 | P0 | Existing `normalized_sha` sorts row IDs; changing meaningful row order may retain same digest | Legacy confirm compares ordered JSONB (correct but hash alone unsafe); add order-aware canonical-content SHA-256, preserve historical hash, test row permutation |
| F166-REV-12 | P0 | Proposed MD5 would weaken collision-resistance for financial lineage and idempotency; unkeyed digest is not an authorization token | Use existing SHA-256 with domain separated canonicalization and semantic equality check; never MD5 or JVM hashCode as identity. Signed form authenticity requires distinct security service |
| F166-REV-13 | P0 | A timestamp/date-only partition reaper can drop a cohort still containing another tenant's active/held generation | Cohort membership/drain/confirmed SQL query termination and privileged DETACH CONCURRENTLY/FINALIZE then DROP; no runtime table creation per form |
| F166-REV-14 | P1 | Stored raw XLSX SHA may change from ZIP metadata while normalized semantic values do not | Separate original-file SHA from semantic content hash; byte-only change gets evidence/audit, no unnecessary numeric recalc |
| F166-REV-15 | P1 | Presentation-only catalog updates may generate wasteful global tenant recalculation | Separate `presentation_hash`; differentiate presentation vs structural vs mapping/qualification changes and affected tenant usage |
| F166-REV-16 | P1 | Large catalog activation has potentially O(tenants × 166) impact and can overload DB | Bounded paged scanner, journal cursor/recovery, deduped only-used form bindings and global+tenant PREPARE quotas |
| F166-REV-17 | P1 | New SHA matching on its own cannot prove same tenant, source, period or current revision | Context-bound form/source hash, composite tenant FKs, payload equality recheck, generation CAS, never hash as authorization |
| F166-REV-18 | P1 | No actual benchmark of large cohort deletion or incremental recompute path | Comparative end-to-end benchmark including preparation, reuse, SQL, WAL, DB lock pressure, OLTP p95 and retained bytes before claim of gain |

**Count:** 13 P0, 5 P1. Each has a **specification-level disposition only** in F166+ADR-R2-10; do not label the associated runtime work COMPLETE or test PASS. F166's existing test matrix plus F166-HASH-01..14 covers the intended closures.

## Hash-specific code observations

- `CatalogXlsxRoundTripService.schemaFingerprint`: SHA-256 over `FORMAT | CatalogCaptureRegistry.VERSION | formCode | mode | grain | fields(code:type)`; no complete server-approved mapping/policy hash.
- `CatalogXlsxRevisionService.confirm`: requires expected `normalized_sha`; on prior revision compares normalized SHA, JSONB array row order and optional document SHA. It stores immutable confirmed revisions with `CONFIRMED_UNQUALIFIED_DRAFT`, not approved financial sources.
- `fin_intake.catalog_xlsx_draft` already has typed 64-char SHA-256 columns (`schema_fingerprint`, `original_sha`, `normalized_sha`, `document_sha`); `fin_fact.fact_set_publication.snapshot_hash` is a different trusted source hash, not replaceable by draft hash.
- `CatalogCaptureRegistry` is STATIC code/TSV, not a live versioned admin catalog DB; so `CATALOG_FORM_DEFINITION_ACTIVATED` is a **new target design**, not an observed existing event.
- `WorkspacePartitionMaintenanceUtility.previewRetirement` is read-only, always blocks destructive retirement, and is not a safe reaper for a form update.
- MD5 remains useful in some **nonsecurity** checksums and hashing contexts, but FIN's authoritative identifiers require collision-resistant SHA-256 and exact domain validation (RFC 6151).

## Blocking W0/W1 acceptance

Write PostgreSQL 16 RED tests first: same-transaction form change event, duplicate no-op, rollback/retry race, 166/166 classification, schema/map/presentation independent changes, content row-order hash, cross tenant/currency collisions, catalog rollout across two app versions, period-dependent impact fanout, and no ACTUAL without verified source. Then design additive Flyway for journal/manifest and implement minimal change detection *without computation or DDL*. Independent S2 required before W1 GREEN sign-off and W4 destructive partition operations. Use only `feat/fin-r0-r1a`, no main merge.
