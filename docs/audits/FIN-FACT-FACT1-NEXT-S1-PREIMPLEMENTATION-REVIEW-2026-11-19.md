# FIN FACT — S1 pre-implementation architectural review for FACT-0.H / FACT-1.A/B

**Date:** 2026-11-19 · **Branch:** `feat/fin-form-library-standalone` · **Baseline:** `39786870bc96e08378b90dcc7bdb1921cfb5423c`.  
**Review sources:** code-level `FormFactCandidateService`, `FormFactSourceIntegrityVerifier`, `FactMonthlyPartitionService`, `Fact1F02TestOnlyQualificationTest`; actual PostgreSQL Liquibase `019–021`; roadmap and previously completed PG16/smoke evidence. **Review is specification/static design review, not new Java/SQL acceptance.**

## 1. Architecture verdict

**GO** for implementing pure contracts (FACT-1.A1/A2), with default-deny qualification gateway; **GO** for FACT-0.H role hardening and its PostgreSQL negative tests.  
**HOLD** for any externally callable/production F02 qualifier or `CURRENT` reader until genuine signed S2, tenant IAM/ABAC, issuer epoch revocation model, privileged DDL isolation and negative tests have independent release evidence.  
The earlier `6/6` smoke certifies the test-only F02 transaction on a disposable PostgreSQL database **only**. It does not certify production IAM, S2 or a worker.

## 2. Findings

| Finding | Severity | Status in updated ТЗ |
|---|---|---|
| Test qualifier resides solely in `src/test` | P0 | Keep test-only issuer separated; introduce a default-deny main runtime port |
| `fact_set.qualification_mode` CHECK only `TEST_QUALIFIED` | P0 | Keep unchanged; introduce production mode only after later S2 review and additive migration |
| Source rehash `REQUIRES_NEW` independent of TX-Q | P0 | Explicit second pinned-source/descriptor/scope verification under TX-Q |
| No signed issuer, trusted actor or key-management policy | P0 | Exact signed binding, key/audience/nonce/epoch and ABAC verification; no approval boolean |
| Local epoch cannot linearize external S2 revocation | P0 | Separate local fencing and remote authority, live fail-closed publication/read gate |
| Replay based on raw revision string | P0 | Stable issuerId+decisionId + exact candidate/binding SHA and conflict rejection |
| Runtime vs partition owner/DDL privileges not qualified | P0 | Separate role/maintenance plan and OPS negative tests |
| Historical 019–021 migrations previously applied | P0 | Never mutate historical checksums; future 022+ additive |
| Source JSON member lacks exact full lineage | P1 | Include instance/formVersion/schema/mapping/row-order and deterministic source digest |
| Financial F02 descriptor not approved for real usage | P1 | `TEST_ONLY_PILOT` mapping, separate financial review and production HOLD |
| No real outbox dispatcher/DAG/worker | P1 | Scope FACT-1 stops at committed `FACTSET_QUALIFIED` event |
| No 36-month partition pruning, WAL restore evidence | P1 | Independent OPS gates, no performance claim without measurement |

## 3. Updated normative specifications

- [FACT-1.A/B exact code-level specification](../temporary/FIN-FACT-1A-1B-TRUSTED-QUALIFICATION-CODE-LEVEL-TZ.md): stages A1/A2/B1/B2/C, typed Java records and interfaces, F02 field policy, immutable digests, TX-Q/epoch/transaction order, candidate vs S2 authority, QF-01–24.
- [FACT-0.H PostgreSQL hardening and operations](../temporary/FIN-FACT-0H-POSTGRES-SECURITY-AND-OPERATIONS-TZ.md): runtime/schema/maintenance roles, no unsafe owner rights, separate partition provisioning, 36-month pruning and WAL restore gates OPS-01–16.
- [Canonical development roadmap](../FIN-FACT-DEVELOPMENT-ROADMAP.md) and [source-to-fact pipeline](../temporary/FIN-CALC-R0-SOURCE-TO-FACT-PIPELINE-TZ.md) must point to these specifications. Actual PG16 schema is in `form-library/src/main/resources/db/changelog/019–021`, not a temporary SQL draft.

## 4. Ordered release plan and acceptance checkpoints

| Order | Package | Before coding | Green evidence to record |
|---|---|---|---|
| 1 | FACT-1.A1 typed F02 mapper/digests | Exactly pinned 3 inputs; non-approved financial policy flagged | Unit tests, deterministic SHA golden vectors, malformed/negative policy tests |
| 2 | FACT-1.A2 independent decision verifier | Default DENY, no test issuer in JAR, no client-approved REST endpoint | Signature/issuer/audience/ABAC/expiry/epoch negative matrix |
| 3 | FACT-0.H DB role boundary | Dedicated runtime/maintenance/migrator roles, no table owner in web app | OPS-01..12/16 real distinct-role PostgreSQL tests |
| 4 | FACT-1.B1/B2 committer | F02 decision receipt persistence via additive 022; TX-Q lock order; source snapshot recheck | QF-01..24, 8+ connection replay concurrency, failpoint rollback and 018→022 upgrade |
| 5 | Separate production S2 & CALC-R0 | Independent issuer configured, revocation/authority read semantics, QA & security review | Independent S2, worker and CURRENT read gates plus production release signoff |

**Smoke policy:** retain `scripts/smoke-fin-fact.sh --quick` and `--extended`; augment with a focused S2-disabled negative test. Successful old smoke ≠ new QF/OPS test PASS. Report exact source SHA and workflow job logs separately.

## 5. Change guardrails for next implementation

1. Do not modify 001–021 Liquibase migrations or downgrade their checksums.
2. Do not add Redis, Kafka, new reports, methods or 166-form auto-qualification.
3. Keep a single branch and one testable vertical slice per commit.
4. Do not expose public qualification endpoints before principal/authority verification.
5. All production writes blocked until real S2; test-only fixture code stays out of packaged JAR.
6. No PostgreSQL DDL inside TX-Q and no automatic partition DROP; no assumption that SQL trigger protects table owner from DDL.
7. Never represent `SOURCE_SHA_REVERIFIED` as `S2_APPROVED` or signed `QUALIFICATION_GRANTED`.
8. In independent review, prove test conditions, trust boundary and observed CI results rather than infer from code.

**Decision:** specifications strengthened and ready for **FACT-1.A1** implementation and **FACT-0.H** security preparation. S2 production release remains **HOLD**.
