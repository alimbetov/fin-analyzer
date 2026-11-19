# FIN CALC-R0 / HTAP v2 — Source-to-Fact Pipeline Contract

**Date:** 2026-11-19. **Status:** INGEST-0–2 and FACT-0 PostgreSQL 019–021 implemented and PG16 GREEN; FACT-1 F02 TX-Q only test-source synthetic GREEN; **FACT-1.A/B runtime NOT IMPLEMENTED; production S2 and CALC worker HOLD**. [Next-step normative ТЗ](./FIN-FACT-1A-1B-TRUSTED-QUALIFICATION-CODE-LEVEL-TZ.md).
**Scope:** `alimbetov/fin-analyzer`, branch `feat/fin-form-library-standalone`.
**Normative parent:** `docs/FIN-CALC-R0-INCREMENTAL-CALCULATION-ARCHITECTURE-TZ.md`.
**Authoritative physical schema:** **APPLIED** `form-library/src/main/resources/db/changelog/019-fin-calc-r0-postgresql.sql`, `020-fin-fact-r0-pilot-postgresql.sql` and `021-fin-fact-scope-source-immutability-postgresql.sql`; historical draft `docs/temporary/sql/FIN-FACT-PARTITIONED-PG16-DDL-DRAFT.sql` is **not normative**. Where older single-source/UUID-only observation keys conflict, the **applied monthly-partition schema** prevails. FACT-1.A/B design: [Trusted Qualification code-level ТЗ](./FIN-FACT-1A-1B-TRUSTED-QUALIFICATION-CODE-LEVEL-TZ.md); security prerequisite [FACT-0.H](./FIN-FACT-0H-POSTGRES-SECURITY-AND-OPERATIONS-TZ.md).

## 1. P0 gap and architectural decision

Previous R0 specified `F02 revision → TEST_QUALIFIED FactSet → DAG → job → result`, but did not define an actual producer of FactSet, typed source extraction, versioned field-to-metric mapping, persisted fact observations, the factset digest, or a qualified event that could be verified by the consumer.

**Verified implementation state (2026-11-19):** `fin_intake` has immutable revisions and INGEST-2 SHA rehash, and `fin_fact.fact_set`/monthly partitioned `fact_observation`/`factset_control` and `fin_calc` storage exist after migrations 019–021. **No production S2 gateway or runtime FactSetCommitter/DAG/worker**, and source field mapping candidates remain `CANDIDATE_UNQUALIFIED`. `FormWorkflowService.READY_FOR_REVIEW` is not S2.

**Decision:** physical storage foundation (3 fact + 5 calc tables) already implemented and tested. Next close the **production trust and transaction gap** with FACT-1.A/B (canonical candidate, signed decision verifier, pinned-source recheck and atomic committer), keeping production S2/HOLD until independent authority, IAM and revoke protocol are available. No warehouse/Kafka/Redis/new methods in this slice.

**Readiness note:** The applied storage tables are real, but a verified typed source is **not** a financially approved candidate. The F02 test-only issuer is confined to `src/test`; production FACT-1.A/B must be fail-closed until signed independent S2. `FormFactCandidateService.extractExact` uses `REQUIRES_NEW`; TX-Q must revalidate source/scope/version in its own transaction. A local epoch cannot atomically guard external S2 revocation; production CURRENT must require live trusted authority proof.

## 2. Exact data flow and transaction boundaries

```text
React F02 edit → PREVIEW_UNQUALIFIED (no fact writes)
  |
TX-A: FormDraftService.save() → immutable fin_intake revision N
      + proposed draft outbox DRAFT_REVISION_SAVED (NOT YET PRODUCED by save path)
  |
Draft event consumer → integrity/staging only, never qualification
  |
Independent qualified command (existing in test fixture only; runtime FACT-1.A/B NOT YET BUILT)
  → PinnedSourceReader(revision_id, full scope, formVersion, content SHA)
  → SourceFactAssembler(F02 field→typed observation + approved-for-test mapping)
  → canonical candidate FactSet digest, no persistence yet
  → TrustedQualificationGateway checks exact candidate digest/issuer/epoch
  |
TX-Q: INSERT fin_fact.fact_set + 3 fin_fact.fact_observation
      UPDATE fin_fact.factset_control (test-local active binding and epoch)
      INSERT outbox FACTSET_QUALIFIED; all in ONE PostgreSQL COMMIT
  |
TX-B: dispatcher loads exact persisted factset, verifies digest/binding
      → immutable DAG plan + job; mark event processed in ONE COMMIT
  |
TX-C: SKIP LOCKED job claim, lease and fencing; COMMIT
  |
CPU: worker reads exact pinned fin_fact observations (NOT latest raw JSON)
     → node fingerprint → reuse or compute changed nodes
  |
TX-D: fenced immutable calc_result + job SUCCEEDED, ONE COMMIT
  |
TX-E: serialized authority/epoch + active factset + config recheck
      → CAS current pointer; separate COMMIT
  |
GET CURRENT: check live authority/epoch; revoked → fail closed
```

**No implicit step:** Saving draft does not produce an approved FactSet. A newly saved revision neither revokes nor silently supersedes a previously qualified revision. A new trusted qualification/revocation controls the active binding and its epoch; out-of-order events must not roll it back.

## 3. Source-to-metric mapping contract for F02

| Source field | Candidate metric | Input | Grain / semantic |
|---|---|---|---|
| `netRevenue` | `F02.netRevenue` | MONEY / BigDecimal | INTERVAL, F02 pilot month M |
| `cogs` | `F02.cogs` | MONEY / BigDecimal | same interval, positive cost convention TEST_ONLY |
| `operatingExpenses` | `F02.operatingExpenses` | MONEY / BigDecimal | same interval, positive cost convention TEST_ONLY |

Use **exact** `form_version_id` UUID and `schema_sha256` / `mapping_sha256`, not merely `FIN-FORM-V2` label. Existing `F02.netProfit` is an unbound historical `FORM_DERIVED_CANDIDATE` despite a legacy SOURCE_FIELD classification; **never import it as an editable fourth fact**.

F02 R0 accepts only `SINGLE_RECORD`, exactly these 3 required numeric fields, same currency/entity/project/period/basis/coverage/dataSpace, all PRESENT. `ROWSET`, EVIDENCE and `ReportGridService` require explicitly separate adapters later. An unsupported source cannot be flattened or treated as qualified. No currency conversion, aggregation or period conversion is inferred from field names. The legacy structural FieldMetricContractGuard is not an independent S2 sign-off.

## 4. Minimum physical fact tables (NEW, not existing)

**Migration contract:** keep `019-fin-calc-r0-postgresql.sql` for five fin_calc tables; add PostgreSQL-only `020-fin-fact-r0-pilot-postgresql.sql` in the master changelog, without editing 001–018. Migration 020 introduces 3 tables below and adds FK `calc_plan.factset_id → fin_fact.fact_set.id`. **Partition only `fact_observation` monthly RANGE(period_start); header/control remain unpartitioned.** The following schemas are conceptual; precise physical columns and PK/UNIQUE constraints are governed by `FIN-FACT-R0-PARTITIONED-166-FORM-INGESTION-TZ.md`.

### 4.1 fin_fact.fact_set — immutable qualified snapshot header

```text
id uuid PK
organization_id, economic_entity_id, business_scope_id, project_id uuid NOT NULL
dataset_code, source_revisions_jsonb NOT NULL  # multiple source forms/versions, not one
period_type, period_code, period_start, period_end_exclusive NOT NULL
currency, coverage, reporting_basis, data_space NOT NULL
source_set_sha256 char(64), mapping_sha256 char(64) NOT NULL
canonicalization_version int NOT NULL
factset_sha256 char(64) NOT NULL
qualification_binding_sha256 char(64), qualification_epoch bigint NOT NULL
qualification_mode varchar(32) CHECK = TEST_QUALIFIED (R0 only)
test_issuer varchar(128), created_at timestamptz NOT NULL
UNIQUE(organization_id, factset_sha256, qualification_binding_sha256)
```

`source_revisions_jsonb` is a canonical, ordered array of exact `revisionId, instanceId, formVersionId, source content SHA, row order SHA when semantically relevant`. `factset_sha256` hashes **canonical typed observation values plus full scope, source versions, mapping version and canonicalization policy**, excluding random ID/timestamp. Transactional duplicate request must return same existing set, not create duplicate rows. SQL UPDATE/DELETE forbidden for immutable fact tables.

### 4.2 fin_fact.fact_observation — immutable typed facts

```text
period_start DATE NOT NULL,
observation_id UUID NOT NULL,
PRIMARY KEY (period_start, observation_id),
factset_id uuid NOT NULL FK -> fact_set(id)
metric_code varchar(160), metric_contract_version varchar(64) NOT NULL
source_revision_id uuid NOT NULL FK -> fin_intake.lib_form_revision(id)
source_form_code varchar NOT NULL
source_row_key varchar(128) NOT NULL DEFAULT 'SINGLE'
source_field_code varchar(128), form_version_id uuid NOT NULL
source_content_sha256 char(64) NOT NULL
period_start, period_end_exclusive date NOT NULL
period_semantics varchar(24) NOT NULL   -- INTERVAL for F02
dimension_digest char(64) NOT NULL
value_kind varchar(24) CHECK = NUMERIC
value_state varchar(24) CHECK = PRESENT
numeric_value numeric(38,12) NOT NULL
unit_code varchar(64), currency_code char(3) NOT NULL
canonical_value_sha256 char(64) NOT NULL
observation_key_sha256 CHAR(64) NOT NULL,
UNIQUE(period_start, factset_id, observation_key_sha256)
-- PostgreSQL 16 partitioned PK/UNIQUE must include period_start
```

F02 uses `source_row_key='SINGLE'` and stable empty-dimension digest. For general ROWSET later, preserve row UUID, true dimensions and grain; do not SUM STOCK across months or ignore YTD/LTD/AS_OF semantics. Null observations and explicit MISSING/NOT_APPLICABLE states will require a separate field policy; do not silently replace missing values by zero. Validate precision, scale, currency units, sign and duplicate business identity before insert.

### 4.3 fin_fact.factset_control — mutable local authority guard (TEST_ONLY)

```text
id uuid PK, stable_scope_key char(64) NOT NULL UNIQUE,
organization_id uuid NOT NULL,
active_factset_id uuid NULL FK -> fin_fact.fact_set(id),
qualification_epoch bigint NOT NULL, revoked boolean NOT NULL,
row_version bigint NOT NULL, updated_at timestamptz NOT NULL
```

The stable_scope_key is a versioned canonical hash of **organizationId + economicEntityId + businessScopeId + projectId + sourceFormCode + target period start/end + currency + coverage + reportingBasis + dataSpace**; it deliberately excludes revisionId/formVersionId so superseding approved versions serialize on the SAME row. **A factset_control row may point only to a FactSet with identical full scope and organization**: enforce with a composite foreign key where practical or transactional scope-join validation plus PostgreSQL negative tests, not just a UUID FK. TX-Q, TX-E, local test revoke and CURRENT reader coordinate against same guarded scope and epoch. A newly qualified FactSet advances active binding; old immutable results remain historical, but are not CURRENT while replacement is pending. A new *draft* does not alter control. PostgreSQL locking and compare-and-swap rules must be consistent and tested.

**Do not mistake TEST_ONLY control for production S2.** External authority in another DB cannot be transactionally serialized by merely calling its REST API before COMMIT. Production publication remains HOLD pending a versioned authority epoch and verified live read/revoke protocol.

## 5. Proposed implementation interfaces (all NEW)

Location: `form-library/src/main/java/kz/fin/formlib/calc/ingest/`.

```java
record PinnedRevisionRef(UUID revisionId, UUID instanceId,
                         UUID formVersionId, String contentSha256,
                         String schemaSha256) {}

record CandidateFactValue(String metricCode, String fieldCode,
                          UUID sourceRevisionId, BigDecimal value,
                          String currency, String valueDigest) {}

record CandidateFactSet(CalcScope scope,
                        List<PinnedRevisionRef> sources,
                        String mappingSha256,
                        List<CandidateFactValue> observations,
                        String factsetSha256) {}

interface SourceRevisionReader {
  VerifiedRevision readExact(UUID revisionId, CalcScope expectedScope);
}
interface SourceFactAssembler {
  CandidateFactSet assembleF02(VerifiedRevision source,
                               VersionedMappingDescriptor mapping);
}
interface TrustedQualificationGateway {
  VerifiedDecision verifyExactCandidate(CandidateFactSet candidate);
}
interface FactSetCommitter {
  QualifiedFactSetRef commit(CandidateFactSet candidate,
                             VerifiedDecision exactDecision);
}
interface QualifiedFactReader {
  VerifiedFactSet readAndVerify(UUID factsetId,
                                String expectedFactsetSha256,
                                String expectedBindingSha256);
}
```

- Reader loads exact revision by UUID, joins instance and formVersion, verifies full scope, bound content hash and F02 source completeness. **Do not compare SHA(values_json) directly to canonical_content_sha256:** existing FormDraftService canonical source hash includes scope/version/rows/evidence. Reuse the versioned FIN-LIB-CONTENT-V1 canonicalization algorithm, with read-back negative tests.
- Assembler uses allowlisted, **test-only** F02 mapping descriptor and typed BigDecimal; CANDIDATE_UNQUALIFIED metadata must never be treated as production S2 authorization. Recompute factset digest from persisted observations at commit/worker-read.
- Gateway rejects non-test profile/DB for synthetic issuer; no HTTP endpoint allows self-approval. Production gateway defaults to fail closed.
- Committer verifies the signed/bound decision exact digest and epoch **before TX-Q**; then under locked test-only control row validates still-current decision, inserts header+observations, advances control and emits FACTSET_QUALIFIED **in same DB TX**.
- Worker uses QualifiedFactReader, **not raw FormDraftService**, to load exact factset and recompute digest; on wrong scope, SHA, source, mapping, missing or duplicate observation **BLOCK**, no fallback to draft latest revision.
- Do not trust an outbox event payload to assert qualification; verify persisted rows and authority binding before enqueue.

## 6. Change impact, period scope, reuse and backfill

For all 166 forms use source mode strategies: 3 SINGLE_RECORD, 151 ROWSET (preserve row UUID and dimensions), 12 EVIDENCE (zero numeric facts by default). R0 still proves only F02. `fact_set` must allow **multiple source revisions/formVersions**, because multi-form methodology input cannot fit a single-form FactSet header. Numeric facts retain exact source form+revision+field+row+period+dimensions; financial meaning is governed by reviewed policies, not SQL MERGE. See `FIN-FACT-R0-PARTITIONED-166-FORM-INGESTION-TZ.md` for full contract.


- Exact qualified FactSet change → new `executionFingerprint` and new full immutable `calc_result` with lineage to new factset and source revisions.
- Only changed typed consumed inputs + relevant algorithm/config/period → changed `nodeFingerprint` and recompute. For F02 changing only operatingExpenses → 1 node recomputed, 2 safely reused.
- Per-method temporal dependency contract: same-month F02 only that month; trailing 12 months recompute windows that contain changed month; YTD recalculates downstream periods to year boundary; STOCK/AS_OF not blindly summed. Multi-form joins require same tenant, entity, basis, coverage, currency and approved mapping/grain policies.
- **Historical backfill:** pre-outbox revisions do not automatically produce an event. Define read-only inventory/discovery of missing source revision IDs, an idempotent staging reprocess path, and an explicit qualification gate. No automatic historic approval. If backfill not implemented in R0, it must be a documented HOLD/TODO, not silently assumed complete.
- **Supersession:** qualified epoch and scope determine current binding, not processing time, idempotency key or latest draft revision. Older approvals may remain historical but never overwrite newer CURRENT.

## 7. Mandatory PostgreSQL 16 tests (additional STF-01–STF-16)

| ID | Scenario | Required postcondition |
|---|---|---|
| STF-01 | Draft commit+outbox; rollback | no orphan outbox or revision |
| STF-02 | Draft event without trusted issuer | no factset, fact observation, qualified job |
| STF-03 | Test-qualified exact three-field F02 | one factset, exactly 3 typed observations, one qualified outbox |
| STF-04 | Duplicate qualification and event replay | same factset/plan/job, no duplicate fact |
| STF-05 | Wrong source hash/formVersion/schema/scope | BLOCK before commit |
| STF-06 | Unapproved F02.netProfit mapping/fourth source | BLOCK; no implicit output-to-fact promotion |
| STF-07 | Missing/non-numeric/overflow/invalid cost convention | BLOCK or explicit domain state, no silent zero |
| STF-08 | Different currency/entity/project/basis/dataSpace/period; forged control.active_factset_id of another tenant | no cross-scope mixing and no foreign-tenant active pointer |
| STF-09 | Fact row corrupted or removed | digest verification fails; no result/publication |
| STF-10 | Draft correction with no new qualification | old qualified factset unchanged; no qualified job |
| STF-11 | New FactSet with only operatingExpenses changed | 1 dirty, 2 reused, new complete result+lineage |
| STF-12 | Trusted event arrives out of qualification epoch order | cannot move CURRENT backward |
| STF-13 | Revoke during worker/publication/current read | fail closed, historical result retained |
| STF-14 | ROWSET/EVIDENCE/ReportGrid offered to F02 assembler | UNSUPPORTED_SOURCE_ADAPTER, never collapsed |
| STF-15 | Already-existing draft saved before outbox feature | discovery/backfill status visible; never auto approved |
| STF-16 | Full PG migration 001–021 already GREEN; 018→022+ upgrade after FACT-1/B | 001–021 immutable checksums, new receipt FK/unique and source integrity negative tests |

**DoD:** completion of master A01–A31 is not sufficient without STF-01–STF-16 and PFT-01–PFT-18 (monthly partitions, multi-source extraction). Qualified end-to-end remains BLOCKED until implemented and PostgreSQL GREEN. For small isolated B1 pure Java DAG tests, five-table kernel can proceed independently. The new three tables are **minimum storage for an actual test-only source→fact chain**, not a full HTAP warehouse.
