# ADR-R2-10 — Versioned SHA-256 Form Fingerprints and Incremental Recalculation

**Decision:** APPROVED DESIGN DIRECTION / CODE AND DB MIGRATION PENDING / S2 PENDING
**Date:** 2026-11-17 · **Repo:** `alimbetov/fin-analyzer` · **Sole branch:** `feat/fin-r0-r1a`
**Normative scope:** [FIN-HTAP-F166](FIN-HTAP-FORM-166-INCREMENTAL-RECALCULATION-AND-PARTITION-LIFECYCLE-TZ.md) and [FIN HTAP Consolidated](FIN-HTAP-CONSOLIDATED-TZ.md).
**Baseline read:** `CatalogXlsxRoundTripService.schemaFingerprint`, `CatalogXlsxRevisionService.confirm`, `CatalogIssuedTemplateService`, `V20261116_10/11` and `V2`.
**Decision:** Use **SHA-256** with versioned, domain-separated canonical input for form schema, financial mapping, normalized content, source and computation identity. Do **not** choose MD5 for any authoritative FIN financial fingerprint, idempotency key, provenance or integrity check. SHA-256 is not a digital signature or substitute for trusted publication, permissions and financial S2.

## 1. Existing mechanisms — preserve their historical behavior

Current source already has:
- `CatalogXlsxRoundTripService.schemaFingerprint(formCode)`: SHA-256 over FORMAT, catalog version, form code/capture mode/grain and field codes/types. **This covers only those aspects**, not every proposed accounting/mapping/qualification/validation rule; do not rename it to a comprehensive `mapping_hash`.
- `fin_intake.catalog_xlsx_draft.schema_fingerprint`, `original_sha`, `normalized_sha`, optional `document_sha`: 64-character lowercase hexadecimal SHA-256. `CatalogIssuedTemplateService` stores a SHA-256 of actual template bytes.
- `CatalogXlsxRevisionService.confirm` compares `normalized_sha` **and** ordered JSONB `normalized_rows` and optional `document_sha` to detect no-op; the existing normalized hash sorts row IDs so its digest **alone** does not detect all order changes.
- `fin_fact.fact_set_publication.snapshot_hash`: immutable verified selected-fact publication identity; `fin_exec.fact_publication_outbox` is published in the same source-authority transaction.
- Existing stored hashes and checksums are **historical contract v1**. Do not re-hash, reinterpret, mutate or overwrite them in place. New canonical hashes are *additional columns or versioned manifests* after Flyway/S2.

## 2. Required versioned fingerprint types

| Field (proposed) | Normative inputs | What equality enables | What equality does NOT enable |
|---|---|---|---|
| `schema_hash_v2` | formCode, schema version, capture mode, field order/code/type, requiredness, cardinality, dimensions, period/calendar and unit semantics, field validation rules | Structural compatibility test for same form/version domain | Mapping equality, approved source or permitted ACTUAL |
| `mapping_hash_v2` | mapping version, field→metric/version, time-kind/period policy, aggregation algebra, currency/unit conversion policy, approved source selector, field defaults and exclusion rules | No numerical remap necessary if mapping identical *and* all other relevant policy hashes equal | No effect of changed input values, model/SQL versions |
| `presentation_hash_v2` | labels/translations/display order/formatting (only reviewed nonnumeric fields) | Distinguish presentation-only updates | Never enter financial input/result identity unless rendering contract requires it |
| `canonical_content_hash_v2` | precisely ordered normalized rows, row identifiers, field codes, canonical type-tagged values, explicit missing/null/zero states and document evidence descriptor, canonical serializer version | Compare exact semantic draft contents under same schema/context | Alone does not confer source approval or identify tenant/form/period |
| `original_file_sha256` | raw original bytes (XLSX/PDF and evidence) | Raw-byte identity and integrity check | Semantic equality of re-exported XLSX, report approval |
| `context_bound_form_hash_v2` | domain tag, organization/tenant, project/entity/scope, form code/version, PeriodType/Period, Currency/unit/basis/dataSpace, `schema_hash_v2`, `mapping_hash_v2`, `canonical_content_hash_v2` | Prevent no-op/impact cache key aliasing across tenants, periods, currency or incompatible schema | Permit execution for an unqualified draft |
| `published_source_hash` | exact qualified FactSet revision, snapshot_hash, source/comparison-set selection and version | Trusted input identity used for computation *only when publication/permit separately verified* | Permission from matching hash alone |
| `calculation_input_hash` / `result_key` | published source, exact FinancialSeriesKey, PeriodType/period window/currency, method and implementation/SQL hashes, inputs, rules, precision and rounding | Idempotent qualified result reuse under identical semantics | Financial qualification or production enablement |

Proposed schema for new durable manifest:
```text
fin_htap.form_fingerprint_manifest(
  fingerprint_id UUID PK,
  organization_id UUID NULL ONLY for server-owned global form definitions,
  form_code VARCHAR(128) NOT NULL,
  form_version TEXT NOT NULL,
  project_id UUID NULL ONLY for global definitions,
  slot_id UUID NULL ONLY for global definitions,
  revision_id UUID NULL ONLY for global definitions,
  algorithm TEXT NOT NULL DEFAULT 'SHA-256',
  canonicalization_version TEXT NOT NULL DEFAULT 'FIN-FORM-CANON-V2',
  schema_hash CHAR(64) NOT NULL,
  mapping_hash CHAR(64) NOT NULL,
  presentation_hash CHAR(64),
  content_hash CHAR(64),
  original_file_hash CHAR(64),
  context_hash CHAR(64),
  created_at TIMESTAMPTZ NOT NULL,
  ... strict row-scope and tenant FK/uniqueness; rows append-only ...
)
```
**This is a contract sketch, NOT executable DDL**. Prefer separate `catalog_definition_manifest` and `tenant_form_content_fingerprint` tables if it makes NULL/foreign-key rules clearer. A global form hash must be derived from server-owned catalog release identity, not from any tenant's submitted rows.

## 3. Canonicalization protocol and collision defense

1. Use domain-separated input such as `FIN:FORM:CONTENT:v2\0` vs `FIN:FORM:SCHEMA:v2\0` vs `FIN:FORM:CONTEXT:v2\0`. For every value write field ID, type ID, explicit length, bytes; define canonical Unicode NFC, numbers as exact decimal canonical strings preserving monetary semantics, date/period in versioned accounting calendar, and a deterministic explicit null/missing/zero marker. **Never rely on JVM `hashCode()`**, `toString()`, default JSON map iteration order, locale, clock/timezone or JDBC driver formatting.
2. Ordered rows are either **semantically ordered** (store original verified row sequence and hash it) or **semantically unordered** (sort by an independently validated unique row key). The existing catalog revision requires preserving ordered-row differences; **for v2 default to order-sensitive hashing**. Whichever policy is chosen must be versioned and tested per form. A permutation of meaningful ordered rows must change `canonical_content_hash_v2`.
3. Compute SHA-256 over UTF-8 canonical **bytes**; persist lowercase fixed-length hexadecimal digest with algorithm/canonicalization version. Java and PostgreSQL independently verify reference vectors where feasible; never simply concatenate nullable text without disambiguating lengths.
4. On hash equality for a critical identity, compare immutable canonical payload or a verified secondary source reference where available; enforce tenant/slot/period/currency/source equality and unique reference constraints. **Hash equality is a fast precheck, not proof of financial authorization or semantic identity**. On detected same-key/different-payload collision, BLOCK and audit, never `ON CONFLICT DO UPDATE` source data.
5. Do not expose tenant-derived digests publicly as a way to discover another tenant's identical data. Keep ACL, row FKs and log controls independent of hash computation.
6. Authenticated/signature provenance requires a separately authorized signature/HMAC policy; SHA-256 **unkeyed** does not authenticate form origin, and original XLSX SHA cannot prove that a source is published or qualified.

## 4. Recalculation decision matrix

**Tenant confirms form revision:** compare exact ordered semantic data and `schema_hash` + `mapping_hash` + context. If truly no-op, no event or task (preserve historical legacy no-op contract). A changed `canonical_content_hash` writes the immutable draft change journal in the same transaction; only draft validation/freshness/impact preview may proceed. No ACTUAL until an approved source is published. A changed raw XLSX hash with identical semantic content may require **evidence/audit update**, not financial recomputation.

**A source FactSet is published:** compare exact trusted selected-fact hashes and source lineage; compute impacted approved metric/periods through versioned dependency graph; schedule permitted calculations only. `canonical_content_hash` cannot replace the `fact_set_publication.snapshot_hash`.

**Global catalog update:** `presentation_hash` changed alone => rendering/presentation compatibility, no numeric compute. `schema_hash` => validation/compatibility review for affected tenants. `mapping_hash` / qualification changed => method dependency invalidation, only approved requalified inputs admitted. No automatic reconstruction or ACTUAL from merely deploying a new Java/TSV catalog.

**Method implementation change:** method implementation hash/SQL checksum changed => new immutable `PlanKey/ResultKey`, independent of form-content equality, no in-place receipt update.

**FX, calendar, rounding rule changes:** independent policy version hash triggers selected affected slices; no untracked implicit currency conversion or full-tenant scan.

**Partition lifecycle:** **Never** name or create/drop PostgreSQL child tables based on any hash. Hash drives semantic change detection, deduplication and bounded ImpactSet; the physical lifecycle cohort is server-allocated and can be `DETACH/DROP` only after every member reference releases it.

## 5. PostgreSQL/Java RED acceptance (before migration)

| ID | Fixture | Required result |
|---|---|---|
| F166-HASH-01 | Identical ordered rows/schema/source context, repeat upload/confirm | same canonical v2 content/context SHA-256; no duplicate event/recalc |
| F166-HASH-02 | Swap two meaningful row positions without changing row IDs/values | v2 ordered hash changes; prior `normalized_sha` stays historically unchanged; actual revision detection remains correct |
| F166-HASH-03 | Same cell values, different tenant/form/PeriodType/Period/Currency | `content_hash` may match, `context_bound_form_hash` differs; no cross-tenant dedup or window |
| F166-HASH-04 | Change numeric field-to-metric mapping, no data changes | `mapping_hash` changes; impacted methods blocked/replanned, no historical overwrite |
| F166-HASH-05 | Change only RU/KK/EN labels | presentation hash changes only; no numeric recompute |
| F166-HASH-06 | Equivalent normalized rows, different XLSX ZIP bytes | raw hash changes, semantic hash stable; no forced numeric rebuild |
| F166-HASH-07 | Change document evidence, rows constant | document SHA change detected; correct evidence/safety impact, not inferred numeric method |
| F166-HASH-08 | Formula/SQL/fiscal calendar/currency rule changed, form content unchanged | different plan/result key; no false no-op |
| F166-HASH-09 | Null vs missing vs explicit zero, `1.0` vs `1.00`, Unicode variant, locale/date | precise documented canonical semantics; no accidental aliasing |
| F166-HASH-10 | Hash matches but tenant/source/slot/period differs or canonical payload unequal | reject identity reuse; never authorize ACTUAL |
| F166-HASH-11 | Failed form confirm after journal insert, transaction rollback | no form revision or change event |
| F166-HASH-12 | Fresh and upgraded PostgreSQL 16 after additive hash manifest | old historical hashes preserved/valid; v2 coexistence proven |
| F166-HASH-13 | Two app instances of different catalog version process same new form | incompatible fingerprints block/hold until reviewed activation and version handshake |
| F166-HASH-14 | Same hash but current source generation changed (ABA/reversion) | monotonic generation/CAS prevents stale result promotion |

**Acceptance:** independent reference-vector tests for canonicalization (Java vs SQL/client), negative tenant/source/period checks on PostgreSQL 16 Testcontainers, same SHA CI, no migration of existing hash semantics, and independent S2 security review.

## 6. Implementation sequence

W0: freeze canonicalization/provenance and exact hash-specific tests. W1.F166: RED tests then additive hash manifest/change journal and atomic form-confirm integration. W2: tenant-aware stage quotas. W3: published SYNTHETIC impact/recalculation parity. W4: immutable generation/receipt and safe physical cohort lifecycle. W5–W8: qualified catalog mapping upgrades, load/ops and independent S2. Do not rewrite historical `V20261116_10/11` or replace existing `normalized_sha` at the same column.

**References:** [RFC 6151](https://www.rfc-editor.org/rfc/rfc6151) says MD5 is not suitable when collision resistance is required. FIN uses existing SHA-256, not MD5, for new authoritative hashes.
