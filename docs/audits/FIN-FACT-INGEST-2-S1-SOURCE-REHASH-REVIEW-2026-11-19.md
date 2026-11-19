# FIN FACT — INGEST-2 S1 Source Integrity / FIN-LIB-CONTENT-V1 Audit

**Date:** 2026-11-19  
**Branch:** `feat/fin-form-library-standalone`  
**Status:** **INGEST-2 FINAL GREEN for canonical source SHA re-verification** at exact code SHA `59dbb02fd03140c912747b01f94e7452eb38ee64` (Java/H2, PG16, Chromium). **Financial S2, fin_fact and publication remain HOLD.**  
**Review:** Java21, PostgreSQL 16 Testcontainers, exact source/read-back architecture. **Not** financial S2, not `fin_fact` materialization.

## 1. Previous failure and core correction

Until INGEST-2 the `FormFactCandidateService` returned `SOURCE_SHA_NOT_REVERIFIED`. `FormFactSourceReader` loaded `lib_form_revision.values_json` and `lib_form_revision_row` by revisionId, but merely copied `canonical_content_sha256`, verified no source SHA or file bytes. Directly trusting this value before `fin_fact` would cross an unqualified source boundary.

Implemented one **shared historical-byte-compatible** algorithm in `kz.fin.formlib.service.FormContentFingerprint`, called by the writer (`FormDraftService.saveCommand`) and by the independent verifier (`FormFactSourceIntegrityVerifier`).

The canonical SHA exactly encodes FIN-LIB-CONTENT-V1:
- organizationId + economicEntityId + businessScopeId + projectId;
- formCode + **pinned versionLabel** (not mutable current version);
- periodType/periodCode + coverage + currency + reportingBasis + dataSpace;
- normalized top-level JSON values sorted by fieldCode;
- source ROWSET rows sorted by **rowId** and canonical field maps, not UI rowPosition;
- EVIDENCE nested JSON string of prior content plus fileName/mediaType/raw SHA;
- UTF-8 SHA256 with exact prefix `FIN-LIB-CONTENT-V1|`.

The distinct `FIN-LIB-ROW-ORDER-V1|` digest includes ordered `rowPosition:rowId` entries in Java List.toString representation. Reordering ROWSET without value changes keeps financial content SHA stable but changes presentation order SHA. A different rowId, numeric value, organization, period or fileName changes the canonical content SHA.

Reader must load the exact historical `revision_id` and `form_version_id`, with matching instance form and caller organization/project, **not** `current_revision_id`. A verified candidate is returned with `SOURCE_SHA_REVERIFIED`; `qualificationState` stays `UNQUALIFIED_SOURCE_PROJECTION`.

## 2. EVIDENCE and transaction gate

Source verifier checks pinned evidence link count ≤1; rejects links on non-EVIDENCE forms; checks evidenceId/documentRef/sha256/mediaType/sizeBytes against snapshot and `FormAttachmentStore.find`; `FormAttachmentStore.load` opens the on-disk file, validates byte size and actual file digest. Missing/tampered bytes fail closed. Empty-attachment evidence metadata forms remain supported.

Service enforces **a single independent REPEATABLE_READ transaction** across source loading, SHA verifier and typed extraction, so a caller's weaker outer transaction cannot silently weaken snapshot isolation. The verifier itself does not approve financial mappings and does not write to fact tables.

Reader rejects non-object JSON, non-string values, duplicate JSON keys and trailing JSON roots; these cannot masquerade as canonical source values.

## 3. Tests and exact CI evidence

### PostgreSQL 16 — *confirmed code SHA d3eb2169…*

- [PG/backend CI](https://github.com/alimbetov/fin-analyzer/actions/runs/44470234440): **SUCCESS**, 13 test methods / 0 failed / 0 errors, including `Ingest2PostgresqlIntegrityTest` **4/4 PASS**.
- Within the INGEST-2 test: **166/166 physically seeded form codes saved** in `fin_intake` on PostgreSQL 16; reloaded exact revision by id; reconstructed content and row-order SHA; asserted version, org, project, 3/151/12 modes and numeric/attribute projections. Inputs are synthetic but use actual `fin_lib.lib_form_field` definitions, not hand-authored artificial one-field schemas.
- F02: after new draft revision, original revision still rehashes correctly; diff of input numeric field changes content hash; false organization/project read is rejected.
- ROWSET: two historical revisions with same financial content but changed row ordering; both pass readback, different row-order SHA; forged rowPosition fails with `SOURCE_ROW_ORDER_SHA_MISMATCH`.
- EVIDENCE: real file upload with stored metadata, byte integrity verification; deliberately corrupt file bytes → fail, restore → pass.
- Forged field value mismatch → `SOURCE_CONTENT_SHA_MISMATCH`, even when the caller copied a plausible stored SHA.
- [Java21/H2](https://github.com/alimbetov/fin-analyzer/actions/runs/44470234615) — SUCCESS.
- [Chromium](https://github.com/alimbetov/fin-analyzer/actions/runs/44470234446) — SUCCESS.

### Additional hardening verified on final SHA

- `FormFactCandidateService`: `Propagation.REQUIRES_NEW` + `Isolation.REPEATABLE_READ`.
- `FormFactSourceReader`: Jackson strict duplicate-detection / trailing document rejection; negative JUnit cases.
- **Verified SUCCESS on the same code SHA `59dbb02fd03140c912747b01f94e7452eb38ee64`:** [Java 21/H2](https://github.com/alimbetov/fin-analyzer/actions/runs/44470613053) — **873 run / 0 failure / 0 error / 14 skipped**, [PG16 + Backend](https://github.com/alimbetov/fin-analyzer/actions/runs/44470613145) — **13 real PostgreSQL 16 test methods / 0 failure / 0 error / 0 skipped**, [Chromium browser](https://github.com/alimbetov/fin-analyzer/actions/runs/44470613318) — **SUCCESS**.

### Golden compatibility unit testing

`FormContentFingerprintTest` reconstructs the historical inline writer formula *independently* and verifies byte-identical SHA for plain rows, row reorder, changed scope and attachment nested JSON. This is a versioned contract; no silent adjustment to old hashes or Liquibase migrations.

## 4. Defects found and corrected during implementation

| Severity | Finding | Resolution |
|---|---|---|
| P0 | INGEST-1 trusted stored SHA without rehash | Shared canonical fingerprint + independent read-back verifier |
| P0 | Verification did not cover physical EVIDENCE bytes | `FormAttachmentStore.load` with actual SHA/size |
| P0 | Former reader did not preserve ROWSET display order digest | Reconstruct separate row-order SHA from persisted row IDs/positions |
| P0 | New SHA helper used `Map.of().containsValue(null)` and crashed on legitimate map | Fixed with value stream null checks; failed CI before fix, GREEN after |
| P1 | Outer READ_COMMITTED might override desired REPEATABLE_READ | REQUIRES_NEW dedicated repeatable-read boundary |
| P1 | Parser could silently resolve duplicate keys as last-value-wins | Strict JSON duplicate/trailing-root rejection |
| P1 | Reference to latest revision would alter historical results | Pinned `revisionId` read; old version tested after newer save |
| P1 | Early PG workflow failure due Maven plugin prefix resolution | Subsequent same-code-sha PG16 run succeeded; no code-data corruption |

## 5. Production HOLD (still NOT implemented/approved)

- Only structural source integrity is verified. No financial metric mapping, no independently approved methodology, no real FIN S2 issuer, no JWT/ABAC-derived full authorization principal.
- The reader's org/project parameters are not an external security principal; keep internal only until IAM/ABAC principal and economicEntity/businessScope constraints are implemented.
- SQL DB roles/append-only source immutability, backup/recovery and unauthorized DDL/TRUNCATE remain release gates for qualified storage; Java SHA cannot substitute for DB permissions.
- `fin_fact.fact_set`, monthly `fact_observation`, `factset_control`, queue and result do **not** exist from this stage. PostgreSQL check in INGEST-2 explicitly confirms absence of `fin_fact`.
- Historical source contract `FIN-LIB-CONTENT-V1` excludes revision creation time/author; this is by design of existing hash, not proof of audit column immutability. Row order has a separate SHA.
- Evidence integrity is checked for local-file attachment store; remote object storage adapters require independent verification before production.
- Java source serialization depends on pinned Jackson formatting and the exact shared code. Any new contract needs an explicit V2 fingerprint and backward compatibility tests.

## 6. S1 decision

**INGEST-2 SOURCE-INTEGRITY GATE GREEN** on `59dbb02fd03140c912747b01f94e7452eb38ee64`: exact SHA rehash for 166 saved PostgreSQL form profiles, strict JSON parser and independent REPEATABLE_READ verified. Financial qualification is NOT implied. **NEXT: FACT-0** additive Liquibase 019/020 + monthly partition service + PFT-01–18; qualified materialization remains blocked by independent TEST_ONLY FactSet admission until FACT-1. Do not imply 166 reviewed financial metric contracts or production S2.
