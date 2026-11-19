# FIN FACT-1.A/B — Trusted Qualification and F02 FactSetCommitter
**Code-level ТЗ · v1.0 · 2026-11-19 · PRE-IMPLEMENTATION / production S2 HOLD**

Branch: `feat/fin-form-library-standalone`. Starting HEAD: `39786870bc96e08378b90dcc7bdb1921cfb5423c`.
Authoritative code baseline: migrations `019–021`; `FormFactCandidateService`; `FormFactSourceIntegrityVerifier`; `FactMonthlyPartitionService`; `Fact1F02TestOnlyQualificationTest`.
This file is a **specification**, not an implementation or a claim of S2 approval.

## 1. Stage boundary

**FACT-1.A1**: typed, immutable, versioned F02 source→metric descriptor; canonical `CandidateFactSet` and two independent hashes. Pure Java; no SQL writes.

**FACT-1.A2**: `TrustedQualificationGateway`, `AuthorityDecisionVerifier`, `TrustedRequestPrincipal`. Runtime default **DENY / AUTHORITY_UNAVAILABLE**. No production issuer, IAM or Keycloak configured yet, so PROD remains HOLD. Test issuer only in test sources.

**FACT-1.B1**: guarded main-source `FactSetCommitter` consuming only a verified decision. Additive DB changeset 022 only if receipt/idempotency contract needs persistence. No public REST approval.

**FACT-1.B2**: fully atomic F02 TX-Q → immutable FactSet, **3** observations, monotonic local epoch control, immutable decision receipt and one outbox event, plus PG negative/concurrency tests. Runtime fails closed without trusted S2.

**FACT-1.C** (separate decision): independent authority and IAM integration, revocation and release S2. No PRODUCTION qualification/CURRENT until the external trust model is independently validated. No Redis, Kafka, new methods, mass auto-approval of 166 forms.

## 2. S1 review — gaps in actual implementation

| ID | Severity | Verified issue | Required fix |
|---|---|---|---|
| F1-01 | P0 | F02 TX-Q exists only in `src/test` | Port algorithm/validations into separate main-source components; never port the synthetic issuer |
| F1-02 | P0 | `fact_set.qualification_mode` CHECK permits only `TEST_QUALIFIED` | **Do not** loosen migration 020; production mode is a later migration after real S2 |
| F1-03 | P0 | INGEST-2 uses `REQUIRES_NEW REPEATABLE_READ`; commit would use a different DB snapshot | Reverify pinned revision/SHA/scope and decision binding **inside TX-Q** or block; source immutable SQL privileges required |
| F1-04 | P0 | Test fixture computes binding with issuer literal, not a verifiable signature | Typed signed decision, trusted keys, audience, expiry, issuer epoch and independent verifier |
| F1-05 | P0 | Local `factset_control` epoch is not remote revocation authority | Explicit external-epoch protocol; fail-closed publication/CURRENT live validation; no fictitious atomic HTTP+DB commit |
| F1-06 | P0 | Replay keyed by revision string in test outbox | Unique `issuerId+decisionId` + candidate+scope+binding SHA; detect conflicting duplicate; cannot rewind current |
| F1-07 | P0 | SQL source triggers exist but database role may still own schema/partitions | Complete FACT-0.H role separation and security tests before external writes |
| F1-08 | P1 | `source_revisions_jsonb` pilot lacks pinned full source membership | Include revisionId, instanceId, formVersionId, schema/mapping SHA, source SHA, period/role and order SHA policy |
| F1-09 | P1 | No runtime dispatcher or result/current computation exists | FACT-1 writes an event only; no claims about CALC-R0 worker |
| F1-10 | P1 | Dynamic partitions and no DEFAULT partition | Maintenance preflight before TX-Q; never create partition inside financial commit |
| F1-11 | P1 | No production principal tied to full org/entity/businessScope/project | Unforgeable server-side principal from verified IAM/ABAC, never client UUIDs |
| F1-12 | P1 | Historical data no authoritative per-metric policy | Only pinned F02 TEST_ONLY descriptor; financial approval for production still BLOCKED |

## 3. F02 v1 source and mapping

Source: exact immutable `fin_intake.lib_form_revision.id` (not current/latest), pinned formVersion + canonical source SHA (`FIN-LIB-CONTENT-V1`), `SINGLE_RECORD`, `MANUAL`. Rowset/evidence/ReportGrid blocked for F02 v1. Only three fields:

| sourceFieldCode | metricCode TEST_ONLY | type | period | value/state |
|---|---|---|---|---|
| `netRevenue` | `F02.netRevenue` | `MONEY` → `BigDecimal/NUMERIC(38,12)` | `INTERVAL` source period | `PRESENT`; >=0 |
| `cogs` | `F02.cogs` | same | same | `PRESENT`; nonnegative cost magnitude |
| `operatingExpenses` | `F02.operatingExpenses` | same | same | `PRESENT`; nonnegative cost magnitude |

No imported `netProfit`, implicit subtraction, NULL→0, auto currency conversion, auto aggregation or period conversion.
Pilot constraints `M / KZT`, approved scope/grain/coverage/reportingBasis/dataSpace and version UUID are explicit allowlists; do not infer sign semantics for real-world IFRS/returns.
`F02MappingDescriptor` pins `formVersionId`, `schemaSha256`, `mappingSha256`, descriptor version, review status, exact field type/state/scale, mapping SHA and policy SHA. Any change gives new effective descriptor SHA. Descriptor `TEST_ONLY_PILOT` does NOT prove financial S2.

## 4. Java contract (new types; implement under kz.fin.formlib.calc.fact)

```java
record FactScope(UUID organizationId, UUID economicEntityId,
  UUID businessScopeId, UUID projectId, String datasetCode,
  String dataSpace, String coverage, String reportingBasis,
  String currencyCode, String periodType, String periodCode,
  LocalDate periodStart, LocalDate periodEndExclusive) {}

record PinnedSourceMember(UUID revisionId, UUID instanceId,
  UUID formVersionId, String formCode, String canonicalContentSha256,
  String rowOrderSha256, String schemaSha256, String mappingSha256,
  String sourceRole) {}

record TypedObservation(LocalDate periodStart, LocalDate periodEndExclusive,
  UUID sourceRevisionId, String sourceRowKey, String sourceFieldCode,
  String metricCode, String metricContractVersion, BigDecimal value,
  String unitCode, String currencyCode, String valueState,
  String dimensionDigest, String observationKeySha256, String valueSha256) {}

record CandidateFactSet(FactScope scope,
  List<PinnedSourceMember> sources, List<TypedObservation> observations,
  String mappingBundleSha256, String descriptorSha256,
  String sourceSetSha256, String candidateFactsetSha256,
  int canonicalizationVersion) {}

interface F02SourceFactAssembler {
  CandidateFactSet assemble(
    FormFactCandidateExtractor.Projection reverifiedSource,
    F02MappingDescriptor descriptor);
}

record QualificationDecision(UUID decisionId, String issuerId,
  String keyId, String signatureAlgorithm, String signedPayload,
  String audience, String nonce, Instant issuedAt,
  Instant notBefore, Instant expiresAt, String stableScopeKey,
  String candidateFactsetSha256, String sourceSetSha256,
  String descriptorSha256, String mappingBundleSha256,
  String bindingSha256, long issuerEpoch, String qualificationMode) {}

interface AuthorityDecisionVerifier {
  VerifiedQualification verifyExact(
    QualificationDecision decision, CandidateFactSet candidate,
    TrustedRequestPrincipal principal);
}

interface TrustedQualificationGateway {
  VerifiedQualification verifyCurrentDecision(
    QualificationDecision decision, CandidateFactSet candidate,
    TrustedRequestPrincipal principal);
}

interface FactSetCommitter {
  FactSetCommitReceipt commitExact(CandidateFactSet candidate,
    VerifiedQualification qualification, TrustedRequestPrincipal principal);
}

interface QualifiedFactReader {
  QualifiedFactSet readAndValidate(UUID factSetId,
    FactScope expectedScope, VerifiedAuthoritySnapshot authority);
}
```

Use immutable types and package-controlled construction for `VerifiedQualification` and `TrustedRequestPrincipal`. Do not deserialize server-verification status from user JSON. No `@RestController` that can self-approve. Default runtime `DisabledQualificationGateway` rejects every request until real S2. Test-only issuer lives **only in src/test**, not toggled into main by env flags.

## 5. Crypto, actor and digest verification

- Signature verifies against **independently trusted issuer key material** (allowlisted issuer/keyId, pinned Ed25519 or RS256), never keys supplied inside the request. Reject `alg=none`, unknown key, invalid signature, invalid nonce/expiry/notBefore, wrong audience, wrong issuer epoch and key revocation.
- Signed claims bind decisionId, issuer, intended service/audience, exact full scope, exact candidate SHA, source member set SHA, F02 descriptor+mapping SHA, canonicalization version, epoch, issue/expiry and nonce. `approved=true` is not a proof.
- Principal verified by server IAM/ABAC must authorize organization + economicEntity + businessScope + project and action (not only org+project IDs). An authenticated normal editor cannot approve.
- `stableScopeKey` = versioned canonical full org/entity/business/project/dataset/dataSpace/basis/coverage/currency/period SHA. `sourceSetSha256` = sorted exact member identities + hashes + source role, with row order policy. `candidateFactsetSha256` = canonical sorted typed observations + full scope/sourceSet/descriptor/mapping/version. `bindingSha256` = deterministic hash of signed verified decision claims. SHA is an **identity**, not a digital signature.
- Canonical encoding must be typed and length-delimited; BigDecimal normalized by approved precision/scale, use no `double`, random IDs, timestamps or map iteration order. Zero≠missing. Recompute when reading persisted fact observations.
- Fail with stable error codes `S2_UNAVAILABLE`, `INVALID_QUALIFICATION_SIGNATURE`, `AUTHORITY_EPOCH_STALE`, `SOURCE_BINDING_MISMATCH`, `SCOPE_FORBIDDEN`, `FACTSET_INTEGRITY_MISMATCH`, not with a permissive fallback.

## 6. TX-Q implementation and replay protocol

1. **Read-only preparation:** verified exact INGEST-2 source → pinned F02 mapper → candidate SHA; validate an **independently issued** decision; pre-create required month via maintenance role in a separate TX. No PostgreSQL DDL inside TX-Q.
2. Start short TX-Q; lock `fin_fact.factset_control` for full stable scope (`SELECT ... FOR UPDATE`). Use same lock ordering everywhere: scope row → decision identity → immutable facts → current control → outbox. Avoid nested independent INGEST-2 `REQUIRES_NEW` as a substitute for TX-Q source recheck.
3. Re-read exact revision, instance full scope, version/schema/mapping and SHA inside TX-Q; verify source membership and candidate/economic digest and signed decision binding; ensure required partition coverage.
4. Compare issuer epoch to last observed authority epoch + local control `qualification_epoch`; reject stale, revoked, expired or superseded decisions. Local monotonic control is **not** proof of remote S2 truth. Do not permit older event to change active.
5. Replayed `issuerId+decisionId` with same candidate+binding returns existing FactSet receipt without inserting new rows/control/outbox or incrementing epoch. Same ID with different digest → `QUALIFICATION_REPLAY_CONFLICT`.
6. Fresh decision: insert immutable `fact_set`, exactly 3 `fact_observation`, optional immutable decision receipt, advance local test `factset_control` epoch/pointer and INSERT `calc_outbox_event(FACTSET_QUALIFIED)`, then COMMIT as **one PostgreSQL TX**.
7. On failure at any point, rollback all changes. No worker job created in FACT-1. Do not mark output `CURRENT`.

**External revocation race:** PostgreSQL cannot atomically lock a separate S2 service over HTTP. Externally approved production commit/publication must remain **HOLD** until trusted issuer epoch semantics, durable revocation feed and a **live fail-closed authority check on publication AND CURRENT read** are independently proven. If S2 is unreachable, never return an active financial result as currently approved.

## 7. Additive database plan: 022+ only

Already implemented 019/020/021 are immutable. Start any new migration with `022` after review:
- Preferred `fin_fact.factset_qualification_receipt`: immutable issuerId, decisionId, issuer epoch, decision/candidate/binding SHA, scope/org, factsetId with composite full-scope FK, issuedAt/expiresAt, algorithm/keyId, signature digest (never raw private keys). Unique `(issuer_id, decision_id)`; reject duplicate with changed economic digest. Parent factset historical.
- Extend only with additive constraints/indexes and explicit source membership if needed; clean PG16 and 018→022 upgrade both required. Do not weaken `qualification_mode CHECK ('TEST_QUALIFIED')` to claim a real approved mode. Only separate future independent S2 migration may introduce production states.
- Never SQL `MERGE UPDATE` existing fact values; new revision/decision → immutable new FactSet + lineage. No automatic qualification from `DRAFT_REVISION_SAVED`.

## 8. Negative and positive PostgreSQL/JUnit gates (QF-01..QF-24)

One separately reportable test per ID; when grouped by a JUnit `@ParameterizedTest`, report each parameter case independently. Provide a PostgreSQL log/SQL assertion when a test exercises persistence.

| ID | Mandatory test | Expected proof |
|---|---|---|
| QF-01 | Exact INGEST-2 F02 source | Three known source fields and deterministic sorted typed candidate digest |
| QF-02 | Zero vs missing vs blank | `0` stays numeric zero; absent/blank required value BLOCK, never coerce |
| QF-03 | Fourth derived `netProfit`/unknown field | Reject before database writes |
| QF-04 | Invalid numeric/overflow/sign convention | Reject exponent where noncanonical, overflow and negative pilot cost magnitude |
| QF-05 | Wrong source kind | ROWSET/EVIDENCE/ReportGrid cannot qualify with F02 SINGLE_RECORD mapper |
| QF-06 | Wrong formVersion/schema/mapping/descriptor | Exact UUID/SHA mismatch rejects even when field names are identical |
| QF-07 | Foreign full scope | Org/entity/businessScope/project/period/basis/currency/dataSpace mismatch denies |
| QF-08 | Unknown key, unsupported signature algorithm or invalid signature | `INVALID_QUALIFICATION_SIGNATURE`; zero DB mutations |
| QF-09 | Expired, premature, wrong audience/nonce | Fail closed including boundary clock/clock-skew tests |
| QF-10 | Missing server principal/ABAC action | Reject independently of supplied client scope UUIDs |
| QF-11 | Valid synthetic test-only signed receipt | Exactly one header, three observations, matching local epoch and one outbox |
| QF-12 | Exact issuerId+decisionId replay | Same FactSet ID and epoch; zero additional rows/events |
| QF-13 | Same decision ID, different candidate/binding SHA | `QUALIFICATION_REPLAY_CONFLICT`; no insert or pointer update |
| QF-14 | Eight or more parallel identical decisions | Separate real PG connections → one FactSet/outbox, no deadlock |
| QF-15 | Two approved revisions same stable scope | Two immutable FactSets; monotonic epoch, newest valid active |
| QF-16 | Older decision arrives after new epoch | Older event cannot rewind pointer, including after revocation |
| QF-17 | Concurrent revoke versus TX-Q | PostgreSQL local serialization and externally unsolved atomicity explicitly reported |
| QF-18 | Inject error at header/row2/control/before-outbox | Each failpoint rolls back all header, facts, receipt, control and outbox |
| QF-19 | Missing/wrong month partition | Reject before/inside TX-Q, never DDL in business transaction |
| QF-20 | Source SHA/scope/version changes between read and TX-Q | Re-read pinned state and BLOCK before commit |
| QF-21 | Persisted observation deleted/corrupt/mismatch count or lineage | `FACTSET_INTEGRITY_MISMATCH` and no result/current publication |
| QF-22 | Cross-tenant receipt, foreign FactSet/control FK | DB FK and Java scope guard both deny |
| QF-23 | Persistent historical upgrade 018→022+ | Exact source checksums/values and migrations 001–021 preserved |
| QF-24 | S2 unavailable/revoked key/unknown issuer epoch | Runtime rejects; test issuer absent from production executable JAR |

**DoD for A:** Java typed contract + canonicalization + fail-closed gateway and unit/negative tests PASS; no production write API.
**DoD for B:** main-source transactional committer + reviewed 022 receipt storage + PG16 tests QF-01..24 PASS at same SHA, existing 6/6 quick smoke and 166-form INGEST regression remain GREEN. Still `PRODUCTION HOLD` without independent S2/IAM.
**DoD for C:** independent issuer and ABAC configured, revoke/read fencing measured, finance policy approved, DB roles and restore proven; only then production release.

Report before/after HEAD, exact CI URLs, test PASS/FAIL/NOT_RUN, no claims for missing authority.
