# FIN CALC — TEMP Code-Level Workpacks R0 → R1 → R2

**Branch:** `feat/fin-form-library-standalone`  
**State:** DRAFT SPECIFICATIONS ONLY. Java/SQL/Redis implementation and benchmarks remain NOT EXECUTED until separately verified.  
**Canonical architecture:** [FIN CALC-R0 master ТЗ](../FIN-CALC-R0-INCREMENTAL-CALCULATION-ARCHITECTURE-TZ.md).  
**S1 findings:** [Architecture review](../audits/FIN-CALC-R0-S1-ARCHITECTURE-REVIEW-2026-11-19.md), [Source→Fact data-flow audit](../audits/FIN-CALC-R0-S1-SOURCE-FACT-CALC-FLOW-REVIEW-2026-11-19.md).  
**Source matrices:** [166 forms](../FIN-CALC-R0-COVERAGE-166-BASELINE.tsv), [794 candidate fields](../FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv).

## Execution order and gates

| Stage | Code-level TЗ | Core implementation | DoD / no-go |
|---|---|---|---|
| **CALC-R0** | [R0 code-level](./FIN-CALC-R0-CODE-LEVEL-TZ.md) + [Source→Fact contract](./FIN-CALC-R0-SOURCE-TO-FACT-PIPELINE-TZ.md) | F02 typed DAG, **pinned JSON→3 typed fin_fact observations**, exact TEST_ONLY qualified FactSet, PostgreSQL outbox/queue, bounded multithreaded workers + immutable results + Caffeine descriptor/DAG L1 | PostgreSQL 16 migrations 001–020 + F01–F16 + A01–A25/A27/A29–A31 + Java/React E2E on one SHA; no real S2 |
| **CALC-R1** | [R1 code-level](./FIN-CALC-R1-CACHE-REDIS-CODE-LEVEL-TZ.md) | `CalculationCache` port, Caffeine node values L1, optional Redis L2 OFF by default, pg/CPU fallback | Cache failure ≡ correct output; no cross-tenant leak; no Redis dependency for app correctness |
| **CALC-R2** | [R2 code-level](./FIN-CALC-R2-REDIS-BENCHMARK-CODE-LEVEL-TZ.md) | Reproducible OFF/L1/L1+L2 benchmark, exact fixture SHAs, performance + correctness report | Enable Redis **only** after measured benefit; `KEEP_DISABLED_NO_GAIN` is a valid outcome |

**Do not start R1 before accepted R0; do not start R2 before R1 correctness GREEN.** Distinguish architecture test-only acceptance from production financial approval.

## Non-negotiable cross-stage contracts

1. **PostgreSQL is authoritative** for immutable form revision, approved binding validation, queue, fencing, result and current pointer. Redis/Caffeine never make financial authorization decisions.
2. **Two fingerprints:** `executionFingerprint` commits to exact qualified FactSet/binding; `nodeFingerprint` commits only to consumed typed operands and relevant node operation policy, allowing narrow recalculation.
3. **Two hashes:** `outputValuesSha256` hashes canonical economic output only (cache-path invariant); `resultSha256` hashes full immutable snapshot/lineage and may vary by reuse execution metadata.
4. **One qualified binding → one accepted immutable result.** New binding → new immutable result and source lineage even when every node value is reused.
5. **Multithreading required from R0:** bounded dedicated workers, separate preview budget, no unbounded parallel streams, DB leases/fencing, topological barriers before dependent nodes.
6. **Test-only issuer is not S2.** Real production S2, IAM/ABAC, external authority linearization and historical source append-only gates stay HOLD until independently proven.
7. **Exact source versions and persisted fact boundary:** `FormDraftService` transactional save; `fin_intake.lib_form_revision.values_json` TEXT JSON, F02 `FIN-FORM-V2` resolved to actual UUID, 166 form codes, 794 R6 candidate fields. **SourceFactAssembler and TX-Q (fin_fact fact_set/observation/control) mandatory for qualified E2E**; workers never fall back to draft JSON. Existing `CatalogReadCache` is public-only and must not contain tenant financial data.
8. **No early infrastructure:** Kafka, MongoDB, Flink, ClickHouse and Redis distributed locks are outside these stages.

## Codex implementation handoff format

For each sub-slice, Codex must deliver:

```text
Branch:
Before HEAD:
After HEAD:
Changed files:
Migration changeset ID + hashes:
Unit tests (command and result):
PostgreSQL 16 Testcontainers (command and result):
React browser E2E (command and result):
Race/failure scenarios:
Measured p95/p99 (only if actually measured):
Known HOLD / unresolved defects:
Next authorized slice:
```

**Failure gate:** do not report GREEN if relevant PostgreSQL or browser tests have not run. Never change old 001–018 Liquibase checksums. Do not merge to `main` or introduce real financial S2 assertions through test fixtures.

## Temporary document lifecycle

These are implementation workpacks, not financial contracts. After each stage receives code+test evidence:
- Link exact release SHA, source files, migrations and CI run IDs in the corresponding workpack.
- Replace unimplemented skeletons with verified contracts or mark them **SUPERSEDED BY CODE**, retaining document history.
- Resolve conflicts by updating this index and master contract together; do not let Redis feature flags or fingerprint/hash semantics drift.

No benchmark result is implied by drafting this index.
