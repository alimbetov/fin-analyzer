# FIN R16.A — 166 Form Contract Inventory and Qualification Gate

**Status:** INVENTORY BASELINE / R16.B IMPLEMENTATION HOLD  
**Branch:** `feat/fin-r0-r1a`  
**Scope:** backend contract governance; no change to execution permissions, existing intake or method logic.

## 1. Reproducible source and classifications

Authoritative inventory source: `backend/src/main/resources/fin/form-compatibility.tsv`.
Derived code-by-code evidence: [fin-r16a-form-contract-inventory.tsv](fin-r16a-form-contract-inventory.tsv).

The source has **166 unique catalog records**: 44 FCP_TD, 59 DF, 63 SF. The existing evidence is 122 `TYPED_FIELD_HINTS` and 44 `CATALOG_SUMMARY`; the existing qualification labels are 157 `DESIGN_ONLY` and 9 `PILOT_UNQUALIFIED`. These are **not** 166 approved field schemas or 122 S2-approved contracts. No row in the derived inventory claims an executable financial authorization.

The source itself remains unchanged. In this audit `NOT_VERIFIED` means that a fully qualified and active schema/metric binding has **not been demonstrated by this inventory**; it does not assert that no implementation exists anywhere else in the repository. Actual runtime coverage must be established through separate source inspections and tests.

## 2. Classification rules (fail closed)

| Existing evidence | R16.A classification | Next step |
| --- | --- | --- |
| `TYPED_FIELD_HINTS` | `FIELD_HINTS_ONLY` | Normalize token hints, verify exact Java types, requiredness, precision, unit, period, null-state, semantic meaning, validators |
| `CATALOG_SUMMARY` | `CATALOG_SUMMARY_ONLY` | Discover fields and obtain finance-owned field semantics and cardinalities |
| `DESIGN_ONLY` / `PILOT_UNQUALIFIED` | Retained unchanged | No S2 or ACTUAL permission inferred |

Every row receives `schemaStatus=NOT_VERIFIED`, `metricBindingStatus=NOT_VERIFIED`, `financialS2Status=NOT_APPROVED`, `activationEligibility=HOLD` in this audit's independent assessment. The source's own `qualificationStatus` is preserved in a separate column. No invented field-to-metric mappings or rounding defaults.

## 3. Mandatory R16.B contract model

- `form_definition`: stable code, category and form kind; exactly one parent per catalog code.
- `form_contract_version`: immutable version ID, source descriptor version, compatibility class and manifest canonicalization version; distinct versioned `schema_hash`, `mapping_hash`, `presentation_hash`; no in-place modification after S1/S2 acceptance.
- `form_field_definition`: immutable field code and ordinal, type, requiredness/cardinality, numeric precision/scale, unit/dimension, explicit absence semantics, sign, aggregation behavior and controlled input/derived classification.
- `form_period_rule`: allowed period type, calendar reference, reporting basis, coverage/as-of semantics and explicit granularity; use the existing platform period taxonomy instead of inventing a parallel enum.
- `form_dictionary_binding`: versioned dictionary identity and resolution semantics; reject unknown code/version.
- `form_metric_binding`: metric code **and version**, typed transformation, scope, dataSpace, currency and source provenance; missing qualified binding blocks numerical promotion.
- `form_validation_rule`: versioned semantic checks (including cross-field balance), deterministic error codes and financial evidence.
- `form_contract_review`: distinct independent S1/S2 review decisions pinned to identical candidate SHA and manifest version.
- `form_contract_activation`: serialized version activation/revocation history with audit identity and effective epoch, without rewriting historical input versions.

Do **not** reinterpret existing `normalized_sha`, `normalized_rows`, `document_sha`, schema fingerprints or published source hashes. New fingerprints are additive, SHA-256, domain separated, deterministic and covered by Java/PostgreSQL golden vectors.

## 4. State machine and authority

`DRAFT -> S1_REVIEW -> S2_REVIEW -> APPROVED -> ACTIVE`, with explicit `REJECTED`, `SUPERSEDED`, `REVOKED` paths. S1 and S2 must be separate principals and author self-approval is forbidden. On candidate mutation, approvals become stale. An activation is one transaction with a compare-and-swap/fencing check of prior active version and a durable change-journal event.

`ACTIVE` permits contract resolution and source validation only. ACTUAL computation additionally requires an approved source FactSet, a release-qualified method and a valid independent execution permit. A changed definition does not silently migrate confirmed historical intake or republish old reports.

## 5. Definition of done and test gates

1. Inventory deterministic: all 166 codes present once; known categories sum to 166; malformed row/duplicate/unknown status fails validation.
2. Every field in the first three pilots has complete exact typing, requiredness, unit, period, rounding/precision and semantic S2 evidence; document-only fields must not be promoted as numerical facts.
3. PostgreSQL 16/Testcontainers RED->GREEN: immutable approved versions; races on activation; stale S2 SHA; revoked version; concurrent review; self-approval; rollback and idempotent replay.
4. Reject mismatched period, unit, dictionary version, decimal overflow, wrong rounding, unsupported reporting basis and cross-tenant/scope resolution.
5. Round-trip tested through the existing `fin_intake` confirmation path, preserving legacy semantics and historical pinned versions.
6. Finance S2 performed independently using fixed source evidence. This document is **not** that approval.

## 6. Implementation order

**R16.A:** committed full-code inventory and documented gaps.  
**R16.B:** forward-only migrations + backend typed model and immutable contract store, only after baseline validation.  
**R16.C:** deterministic validator with three representative forms (numeric, categorical/sector, evidence-only).  
**R16.D–F:** independent reviews, safe activation and intake integration.  
**R16.G–H:** per-code qualification, negative regression and independent financial S2.

Maintain a single working branch `feat/fin-r0-r1a`; no changes to `main`, no automatic ACTUAL release or unapproved auto-mapping.
