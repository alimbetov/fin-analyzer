# FIN CALC — TEMP Code-Level Workpacks R0 → R1 → R2

**Branch:** `feat/fin-form-library-standalone`  
**State (2026-11-19):** INGEST-0/1/2, physical FIN FACT/FIN CALC migrations 019–021 and TEST_ONLY F02 TX-Q passed PostgreSQL technical qualification; **FACT-1 runtime trusted qualifier, DAG/worker, Redis and production S2 NOT IMPLEMENTED**. [Roadmap](../FIN-FACT-DEVELOPMENT-ROADMAP.md).  
**Canonical architecture:** [FIN CALC-R0 master ТЗ](../FIN-CALC-R0-INCREMENTAL-CALCULATION-ARCHITECTURE-TZ.md).  
**S1 findings:** [Architecture review](../audits/FIN-CALC-R0-S1-ARCHITECTURE-REVIEW-2026-11-19.md), [Source→Fact data-flow audit](../audits/FIN-CALC-R0-S1-SOURCE-FACT-CALC-FLOW-REVIEW-2026-11-19.md), [S1B partitioning and 166-form re-review](../audits/FIN-CALC-R0-S1B-PARTITIONED-166-INGESTION-REVIEW-2026-11-19.md).  
**Source matrices:** [166 forms](../FIN-CALC-R0-COVERAGE-166-BASELINE.tsv), [794 candidate fields](../FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv), [166-form ingestion strategy matrix](../FIN-FACT-R0-166-FORM-INGESTION-COVERAGE.tsv).

## Execution order and gates

| Stage | Code-level TЗ | Core implementation | DoD / no-go |
|---|---|---|---|
| **FACT-0.H** | [PG roles, partitions, recovery](./FIN-FACT-0H-POSTGRES-SECURITY-AND-OPERATIONS-TZ.md) | Separate runtime/owner/maintenance PostgreSQL identities; prove DDL/TRUNCATE denied, pruning and restore | **OPS-01–16**, planned additive 022+; no privileged DDL in business app |
| **FACT-1.A/B** | [F02 typed mapper, authority and committer](./FIN-FACT-1A-1B-TRUSTED-QUALIFICATION-CODE-LEVEL-TZ.md) | Typed canonical CandidateFactSet, deny-by-default S2 gateway, trusted receipt, TX-Q/epoch/replay/Outbox | **QF-01–24**, main runtime cannot approve until trusted issuer/ABAC; source SHA recheck inside TX-Q |
| **CALC-R0** | [R0 code-level](./FIN-CALC-R0-CODE-LEVEL-TZ.md) + [Source→Fact](./FIN-CALC-R0-SOURCE-TO-FACT-PIPELINE-TZ.md) + [Partitioned fin_fact/166 forms](./FIN-FACT-R0-PARTITIONED-166-FORM-INGESTION-TZ.md) | FACT-0 storage already implemented; future F02 DAG, PostgreSQL queue, bounded multithread workers and Caffeine descriptor reuse | PostgreSQL 019–021 baseline GREEN; future 022+ gates, STF-01–16/PFT-01–18/A01–34; no production S2 |
| **CALC-R1** | [R1 code-level](./FIN-CALC-R1-CACHE-REDIS-CODE-LEVEL-TZ.md) | `CalculationCache` port, Caffeine node values L1, optional Redis L2 OFF by default; **R1-INGEST** adds ROWSET/EVIDENCE and later ReportGrid adapters with reviewed field semantics | Cache failure ≡ correct output; per-form mapping E2E separately, no automatic S2 or source mixing |
| **CALC-R2** | [R2 code-level](./FIN-CALC-R2-REDIS-BENCHMARK-CODE-LEVEL-TZ.md) | Reproducible OFF/L1/L1+L2 benchmark, exact fixture SHAs, performance + correctness report | Enable Redis **only** after measured benefit; `KEEP_DISABLED_NO_GAIN` is a valid outcome |

**Execution order:** FACT-1.A1 typed mapper → FACT-1.A2 independent fail-closed authority + FACT-0.H DB roles → FACT-1.B committer/receipt → separate real S2 acceptance → CALC-R0 worker/publisher. [Latest S1 review](../audits/FIN-FACT-FACT1-NEXT-S1-PREIMPLEMENTATION-REVIEW-2026-11-19.md). No changes to 001–021 migrations.

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
9. **166 forms do not mean 166 numeric mappers or 166 partitions:** 3 SINGLE_RECORD, 151 ROWSET, 12 EVIDENCE. Use 3 source strategies, reviewed per-formVersion mapping and monthly `RANGE(period_start)` partitioned numeric observations. Header/control unpartitioned. No automatic DROP of historical facts or SQL MERGE updates.

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
