# TEMP / FIN CALC-R0 — Code-Level Implementation Specification

> **Status:** DRAFT FOR CODE IMPLEMENTATION; not a report of completed work. Supersede this document after R0 acceptance.  
> **Branch:** `feat/fin-form-library-standalone` only. **Module:** `form-library` (Spring Boot 3.5.7, Java 21, PostgreSQL 16, Liquibase).  
> **Normative architecture:** [FIN CALC-R0 master TЗ](../FIN-CALC-R0-INCREMENTAL-CALCULATION-ARCHITECTURE-TZ.md). The master S1 safety gates take precedence.  
> **Delivery:** `CALC-R0 = PostgreSQL + incremental DAG + executionFingerprint/nodeFingerprint + bounded Caffeine L1 + multithreaded workers + immutable result`. **No Redis, Kafka, DSL or full fact warehouse**. R0 **does require minimal persisted fin_fact pilot tables** (3 tables in migration 020) for a real source→fact→worker E2E. Local `TEST_QUALIFIED` only until an independent S2 authority exists.

## 0. Real integration points, never invent existing implementation

| Source file / DB object | Verified state | R0 integration rule |
|---|---|---|
| `service/FormDraftService.java` | Public `@Transactional save(req,key)` and `saveWithAttachment`; private `saveCommand`; idempotent receipt and `fin_intake` immutable logical revision | Inject an `OutboxAppender` and invoke once **after** persisted revision+receipt, **within same transaction**. Never emit on idempotent replay, no-op or rollback |
| `fin_intake.lib_form_revision` | `values_json TEXT`, pinned `form_version_id`, `canonical_content_sha256`; instance has organization/entity/businessScope/project/period/currency/basis/coverage/dataSpace | Read by **revision UUID**, validate instance scope and bound SHA; do not read `current_revision_id` when evaluating pinned plan |
| `bootstrap/CatalogBootstrap.java` | F02 pilot `FIN-FORM-V2` with `netRevenue,cogs,operatingExpenses`, 3 MONEY values | Pin actual UUID of form version + schema SHA at planning time; never hard-code UUID |
| `service/FieldMetricContractGuard.java` | Structural field-binding consistency only | Not an independent financial approval |
| `service/CatalogReadCache.java` | Already caches public catalog projections; no tenant data by design | **Do not modify/reuse it for financial nodes**; create separate bounded calc cache namespace/class |
| `form-library/pom.xml` | Caffeine dependency already present | Do not add Redis dependency in R0 |
| `db/changelog/db.changelog-master.yaml` | Includes migrations 001–018 | Add additive `019-fin-calc-r0-postgresql.sql` and include at the end, PostgreSQL-only; preserve existing checksums. No DB changes to `fin_intake` except a tested hook in existing service |
| `src/test/.../ReactLocalPostgresIntegrationTest.java` | Opt-in PostgreSQL 16 Testcontainers and concurrent save examples | Add isolated `CalcR0PostgresqlIntegrationTest` with equivalent opt-in policy; never run tests against developer persistent DB |
| `application.yml` | Local loopback server 127.0.0.1:8086, `ddl-auto: validate` | Keep default `fin.calc.enabled=false`; allow explicit local test enablement |

## 1. Implementation increments and exact classes

Use package root `kz.fin.formlib.calc`; **all names below are NEW** (not claims about existing code).

| Increment | New files/classes | Mandatory result |
|---|---|---|
| **R0.B1 — Pure kernel** | `domain/TypedValue.java`, `domain/ValueState.java`, `domain/CalcScope.java`, `domain/NodeDescriptor.java`, `domain/FormulaDescriptor.java`, `domain/NodeResult.java`, `kernel/F02CalculationKernel.java`, `kernel/DagValidator.java`, `kernel/DagPlanner.java`, `kernel/FingerprintService.java` | No Spring/DB dependency in pure kernel; identical operands+versions yield identical SHA; changed operatingExpenses affects only operatingResult |
| **R0.B2 — Preview** | `api/CalculationPreviewController.java`, `service/PreviewCalculationService.java`, `config/CalculationProperties.java` | Explicit loopback-only and feature flag, bound request body, no DB writes; frontend sequence/debounce protection |
| **R0.B3 — DB/outbox** | Liquibase 019, `persistence/CalcJdbcRepository.java`, `outbox/OutboxAppender.java`, `outbox/CalcOutboxDispatcher.java` | Same TX as revision; five additive PostgreSQL calc tables; idempotent draft event, no qualified job without FactSet |
| **R0.B3F — Source→Fact pilot (P0)** | Liquibase **020**, `ingest/SourceRevisionReader.java`, `SourceFactAssembler.java`, `TrustedQualificationGateway.java`, `FactSetCommitter.java`, `QualifiedFactReader.java`; 3 fact tables | Reads pinned F02 source, validates exact canonical source/field/mapping SHA; commits TEST_ONLY FactSet + 3 observations + trusted outbox in TX-Q; worker reads facts, not draft JSON; F01–F16 PG tests |
| **R0.B4 — Worker** | `worker/CalcWorkerScheduler.java`, `worker/CalcJobClaimer.java`, `worker/CalcJobExecutor.java`, `worker/CalcLeaseHeartbeat.java`, `worker/CalcJobReaper.java`, `worker/CalcPublicationGate.java` | Bounded 2–4-way parallel execution; **QualifiedFactReader** pins trusted observations for CPU calculation; claim, heartbeat, fenced commit and separate publication; recovery |
| **R0.C1 — Reuse** | `service/NodeChangeImpactService.java`, `cache/CalcDescriptorCache.java`, `service/PreviousResultNodeLookup.java` | Caffeine **descriptor/graph only**. Node value reuse from verified PostgreSQL immutable result, different output snapshot for new FactSet |
| **R0.C2 — Guard/metrics** | `admission/TrustedQualificationGateway.java`, `admission/RejectingQualificationGateway.java`, `admission/TestOnlyQualificationGateway.java` (**test source set only**), `service/CalcObservability.java` | No HTTP approve; production default fail closed; trace retries/latency without PII |
| **R0.D — Gate** | `test/.../F02KernelTest.java`, `FingerprintServiceTest.java`, `CalcR0PostgresqlIntegrationTest.java`, `SourceToFactPostgresqlIntegrationTest.java`, browser acceptance test | All A01–A25,A27,A29–A31,F01–F16 and legacy regression GREEN on exact SHA |

No new Kafka, Redis, Flink, ClickHouse, web sockets, general-purpose formula parser, multi-tenancy IAM simulator or 166-form runtime generator.

## 2. Typed Java contracts (skeleton signatures, implementation required)

```java
package kz.fin.formlib.calc.domain;

public enum ValueState { PRESENT, MISSING, NOT_APPLICABLE, INVALID }

public record CalcScope(
    UUID organizationId, UUID economicEntityId,
    UUID businessScopeId, UUID projectId,
    String periodType, String periodCode,
    LocalDate periodStart, LocalDate periodEndExclusive,
    String currency, String coverage,
    String reportingBasis, String dataSpace) {}

public record TypedValue(
    ValueState state, BigDecimal numericValue,
    String unitCode) {
  // INVARIANT: PRESENT requires numericValue; other states forbid it.
}

public record NodeDescriptor(
    String nodeCode, String operatorCode,
    String operatorVersion, List<String> inputNodeCodes,
    String outputUnitCode, int outputScale, RoundingMode rounding) {}

public record NodeResult(
    String nodeCode, ValueState state, BigDecimal numericValue,
    String unitCode, String nodeFingerprint,
    String provenanceMode) {} // RECOMPUTED or REUSED

public interface FingerprintService {
  String executionFingerprint(ExecutionBinding exactBinding);
  String nodeFingerprint(NodeDescriptor op, List<TypedValue> operands,
                         List<String> upstreamNodeFingerprints,
                         CalcScope scope, String algorithmArtifactSha);
}

public interface DagPlanner {
  List<NodeDescriptor> topologicallyOrder(List<NodeDescriptor> allNodes);
  Set<String> impactedNodes(Set<String> changedInputs,
                            List<NodeDescriptor> allNodes);
}
```

`ExecutionBinding` is an immutable record with exact `factSetId`, `factSetSha`, `qualificationBindingSha`, authority epoch, pinned revisionId/formVersionId/contentSha, F02 schema+mapping SHA, formula/variant/config/params/artifact hashes, and `CalcScope`.

- Canonical hashing: version-tagged, length-delimited or canonical JSON serialization; lexicographically ordered map keys, numeric strings normalized under approved precision rules, explicit `PRESENT/MISSING/NOT_APPLICABLE`, units, period and tenant. Never rely on `Map.toString()`, `double` or default JVM locale.
- `executionFingerprint` includes complete **qualified binding** and should change on any new revision/FactSet/authority epoch.
- `nodeFingerprint` includes only **consumed typed operands**, upstream node digests, operation version, relevant policy hashes, scope and unit/time semantics; NOT entire revision SHA, new FactSet ID or unrelated input values.
- Fingerprints are **identities**, not approval signatures; SHA matches do not grant permission.

### F02 deterministic DAG

```text
INPUTS:
  netRevenue: MONEY(KZT), cogs: MONEY(KZT), operatingExpenses: MONEY(KZT)
DERIVED:
  grossProfit     = SUBTRACT(netRevenue, cogs)
  grossMargin     = DIVIDE(grossProfit, netRevenue)      // stored ratio 0..n, not percentage
  operatingResult = SUBTRACT(grossProfit, operatingExpenses)
```

Input rule: pilot costs stored as **positive expense amounts**, no implicit sign inversion. If not accepted by future financial S2, rename/replace the pilot variant rather than silently changing semantics. `DIVIDE(x,0)` returns `INVALID/DIVISION_BY_ZERO`; absence never becomes zero. Exact `MathContext` and scale/rounding pinned to immutable versioned descriptor (proposed synthetic default: money scale from currency, ratio scale 8, HALF_EVEN; not yet approved finance policy).

```java
final class F02CalculationKernel {
  F02Result evaluate(F02Input input, F02Descriptor version,
                     Optional<PreviousNodeSnapshot> reusable) {
    // 1. validate typed values, unit, period, scope
    // 2. topological plan GP -> {grossMargin,operatingResult}
    // 3. compute nodeFingerprint for each node
    // 4. if prior immutable node hash matches, reuse scalar only
    // 5. otherwise run BigDecimal operator with pinned policy
    // 6. build COMPLETE new output snapshot and NEW lineage
    // No transaction, no cache mutation, no remote approval calls.
    throw new UnsupportedOperationException("Implement in R0.B1");
  }
}
```

**Expected impact assertions:** change only `operatingExpenses` → 1 recomputed / 2 reused; change `cogs` → 3 recomputed; change `netRevenue` → 3 recomputed. Compare canonical typed inputs, not raw JSON key order.

### Parallel DAG without nondeterministic results

- R0's three F02 nodes are cheap; execute **sequentially inside each job**. R0 **must** run several independent jobs in parallel through a bounded dedicated pool.
- General graph planner may partition ready nodes by topological layer. For future expensive nodes, execute an independent layer using a separate bounded per-job budget, gather outcomes into a deterministic `TreeMap<nodeCode,NodeResult>` **after** all tasks finish. No `parallelStream()`, common ForkJoin pool, mutable shared hash builder or arbitrary thread-per-node.
- Limit nested parallelism: `totalActiveCpuTasks <= fin.calc.workers.max`; do not allocate `maxWorkers * maxNodeParallelism` blindly. Preview has its own small queue/pool. On any required node failure, stop publishing entire snapshot.

## 3. PostgreSQL 16 migration 019 — physical contract

Create `form-library/src/main/resources/db/changelog/019-fin-calc-r0-postgresql.sql` with Liquibase `dbms:postgresql` and include at end of master YAML. New schema `fin_calc`. **Then** create `020-fin-fact-r0-pilot-postgresql.sql` for `fin_fact.fact_set`, `fin_fact.fact_observation`, `fin_fact.factset_control` and add FK `fin_calc.calc_plan.factset_id → fin_fact.fact_set.id`. Detailed columns, strict TEST_ONLY qualification, and transactional tests: [Source-to-Fact contract](./FIN-CALC-R0-SOURCE-TO-FACT-PIPELINE-TZ.md). R0 has **5 calc + 3 fact = 8 physical tables**. **JDBC-only mappings** (no new Hibernate entities) so H2 can remain supported when calc is OFF. Never edit changelogs 001–018.

Five tables required; this is a **column-level specification**, not a claim that the SQL exists.

1. `fin_calc.calc_outbox_event`: `id uuid pk`, `producer varchar(40)`, `producer_event_id varchar(150)`, `event_type varchar(64)`, `aggregate_id uuid`, `revision_id uuid null`, `payload_sha256 char(64)`, `payload_json jsonb`, `state varchar(24)` CHECK(PENDING,PROCESSING,PROCESSED,FAILED_RETRYABLE,FAILED_FINAL), `available_at timestamptz`, `processed_at timestamptz null`, `attempts int`, `last_error_code varchar(100) null`, `created_at timestamptz`; `UNIQUE(producer,producer_event_id)`. Preserve immutable payload on state updates.
2. `fin_calc.calc_plan`: `id uuid pk`, **full CalcScope columns**, `execution_fingerprint char(64)`, `factset_id uuid`, `factset_sha256 char(64)`, `qualification_binding_sha256 char(64)`, `qualification_epoch bigint`, `method_code/version`, `config_sha256`, `params_sha256`, `artifact_sha256`, `dag_snapshot_json jsonb`, `dag_sha256`, `status varchar(32)`, `created_at`; UNIQUE(`organization_id,execution_fingerprint`), since fingerprint already commits to exact full scope. Immutable binding columns after insert.
3. `fin_calc.calc_job`: `id uuid pk`, `plan_id uuid fk`, `node_group_key varchar(128)`, `execution_fingerprint char(64)`, `status varchar(32)`, `priority int`, `attempt_count int`, `available_at`, `lease_owner varchar(128) null`, `lease_until timestamptz null`, `fencing_token bigint`, `last_error_code varchar(100) null`, `result_id uuid null`, `created_at`, `updated_at`, `UNIQUE(plan_id,node_group_key)`, indexes `(priority DESC,available_at,created_at) WHERE status IN ('PENDING','FAILED_RETRYABLE')` and `(status,lease_until) WHERE status='RUNNING'`.
4. `fin_calc.calc_result`: `id uuid pk`, `organization_id uuid`, full scope, `plan_id uuid fk`, `execution_fingerprint char(64)`, `output_values_sha256 char(64)`, `result_sha256 char(64)`, `result_json jsonb` (all outputs, node SHA/value/state/reuse marker), `lineage_json jsonb` (this exact binding), `computed_at`, `qualification_mode varchar(32)`; UNIQUE(`organization_id,execution_fingerprint`). PostgreSQL append-only UPDATE/DELETE deny trigger; validate FK to plan and scope in service + negative tests.
5. `fin_calc.calc_current_result`: stable full scope + `method_code/version policy`, `result_id uuid fk`, `pointer_version bigint`, `approval_epoch bigint`, `activated_at`; UNIQUE(stable full scope including method variant). CAS and trusted authority guard; **no caching of this table**.

**Mandatory constraints:** non-null for identity columns; `CHECK(attempt_count>=0, fencing_token>=0, pointer_version>=0)`; SHA-256 regexp/length checks where suitable; checked status transitions in code+tests; FK to plan/result; result immutability enforced by PG trigger or restricted DB role; **no inserts to `fin_fact` except via trusted `FactSetCommitter` TX-Q**. `fin_fact.factset_control` is the required LOCAL_TEST_ONLY authority epoch lock; **do not treat calc_plan as authorization state** or pretend this is a production external approval transaction.

### Outbox integration

Inside `FormDraftService.saveCommand` after `receipts.saveAndFlush(receipt)`, before return (same `@Transactional` outer call):

```java
outboxAppender.revisionSaved(
    revision.getId(), inst.getId(), inst.getOrganizationId(),
    revision.getCanonicalContentSha256(), version.getId());
```

`OutboxAppender` uses same Spring-managed DataSource/JdbcTemplate + transaction; fail save on failed outbox insert so **no committed revision lacks an event**. Idempotent replay exits earlier, emits zero events. On H2 with calc disabled, use `NoopOutboxAppender` (no hidden network calls). With PostgreSQL and calc enabled, real appender. Outbox payload references IDs/digests only. Local dispatcher uses `FOR UPDATE SKIP LOCKED`; after **TX-Q** has committed persisted `fin_fact` rows and `FACTSET_QUALIFIED` outbox, dispatcher verifies FactSet digest+binding+epoch and creates plan+job in one TX with event processed. Replay dedup via producer event ID. A draft event is never a qualification event; never enqueue on mere `READY_FOR_REVIEW`.

### Job claim SQL sketch

```sql
WITH candidate AS (
  SELECT j.id FROM fin_calc.calc_job j
  WHERE j.status IN ('PENDING','FAILED_RETRYABLE')
    AND j.available_at <= now()
  ORDER BY j.priority DESC, j.available_at, j.created_at
  FOR UPDATE SKIP LOCKED LIMIT :max_claim
)
UPDATE fin_calc.calc_job j
SET status='RUNNING',
    lease_owner=:worker_id,
    lease_until=now() + interval '30 seconds',
    fencing_token=j.fencing_token+1,
    attempt_count=j.attempt_count+1,
    updated_at=now()
FROM candidate c WHERE c.id=j.id
RETURNING j.id,j.plan_id,j.fencing_token;
```

**One short TX for claim; COMMIT before CPU computation.** R0 single-process scheduler with bounded worker pool; multiple PostgreSQL connections can claim distinct jobs concurrently. A second process must still respect DB fencing; distributed **global** tenant fairness is NOT proven by JVM-local counters and remains production-gated unless a DB-global admission design is added.

### Commit and publication

```text
TX-Q  after verified TEST_ONLY decision: INSERT immutable factset+3 observations
      + update locked factset_control + append FACTSET_QUALIFIED outbox, COMMIT
TX-B  verify persisted factset+digest+active epoch, enqueue job + process outbox, COMMIT
TX-C  claim a job + fence; COMMIT
CPU   load pinned fin_fact observations (not raw F02 draft) + validate digest; evaluate DAG
TX-D  lock job; test lease_owner, fencing_token, lease_until > now(), status=RUNNING
      lock/validate plan; INSERT immutable result; set job.result_id,status=SUCCEEDED
      COMMIT (no result if ownership check fails)
TX-E  independent transaction; lock plan+fin_fact.factset_control in prescribed order
      assert active TEST_ONLY factset and epoch, config and fact binding unchanged
      CAS current_result pointer, mark plan PUBLISHED; COMMIT
```

Retry TX-E after crash without duplicate result. A revoked/obsolete plan remains historically explainable but cannot become CURRENT. PostgreSQL row locking in test authority and publication MUST use same lock order; external authority production integration remains HOLD.

### Concurrency resource properties (initial **hypotheses**, tune by benchmark)

```yaml
fin:
  calc:
    enabled: false
    preview:
      enabled: false
      max-in-flight: 2
      queue-capacity: 32
      timeout: 500ms
    workers:
      max: 4
      max-queued-local: 0 # jobs remain in PostgreSQL, no unlimited JVM buffer
      per-tenant-in-flight: 2
      lease: 30s
      heartbeat: 10s
      max-attempts: 5
      poll-interval: 250ms
    dag:
      max-parallel-nodes: 1 # F02 pilot sequential within each job
    cache:
      descriptor:
        max-entries: 512
        ttl: PT10M
      redis:
        enabled: false
```

Do not enable Redis starter/dependency as a consequence of a config stub. Production profile may override `workers.max`; still bounded by CPU and DB pool. Lease and heartbeat numbers above are tuning assumptions. Reaper must increase fencing on reclaim; blocked/revoked jobs must not be requeued forever.

## 4. Caffeine L1, already on classpath

Implement **separate** `CalcDescriptorCache`, not `CatalogReadCache`:

```java
Cache<DescriptorKey, FormulaDescriptor> formulaCache =
    Caffeine.newBuilder()
        .maximumSize(512)
        .expireAfterWrite(Duration.ofMinutes(10))
        .recordStats()
        .build();
```

Key includes `descriptor SHA + algorithm artifact SHA + version + relevant scope`; cache immutable descriptor/validated DAG only. Do not cache `ApprovedFactSet`, IAM, revocation epochs, `current_result`, mutable drafts, or `calc_job`. Do not cache calc node values in R0 unless PostgreSQL-reuse is proven and a separate reviewed value-store contract exists.

**Hash semantics:** `output_values_sha256` hashes only canonical financial outputs (nodeCode, state, numeric value, unit, pinned calculation policy), independent of cache hits/reuse markers. `result_sha256` hashes the full immutable result/lineage including provenance; it may differ between cache modes. Do not use `result_sha256` to claim economic values differ when the cache path changes.

**PostgreSQL node reuse without new table:** lookup recent complete immutable `calc_result` for same exact tenant/scope and same compatible method/algorithm (bounded indexed query via `calc_current_result` or explicit recent prior successful plan). Read `result_json` node array and compare each stored `nodeFingerprint` with freshly computed fingerprint. If identical, copy scalar value into the **new** output snapshot with `REUSED` marker and lineage to the NEW pinned sources. Missing/invalid old results → compute, never guess.

## 5. Code-level tests, commands and acceptance

| Test class / case | Checks |
|---|---|
| `F02KernelTest` | Decimal arithmetic and canonical formatting; ZERO division; 3 required inputs; precision/rounding; unchanged source maps immutable |
| `DagPlannerTest` | Stable topological order; missing dependency; cycle; one-input impact; unsupported operator |
| `FingerprintServiceTest` | Same typed values same node SHA; changing unrelated opEx leaves grossProfit/margin nodes; new qualified FactSet always different execution SHA; different tenant/period/algorithm changes keys |
| `CalcDescriptorCacheTest` | versioned key, bounded L1, expiry, no mutable authority; no leakage from public `CatalogReadCache` |
| `CalcR0PostgresqlIntegrationTest` | Real PG16: same-TX outbox, save rollback, replay, 2–4 simultaneous worker claims, lease timeout/reaper, fenced late writer, crash before TX-D/after TX-D-before-TX-E, concurrent local revoke vs publish, DB UPDATE/DELETE denied for immutable results |
| `CalcPreviewApiIntegrationTest` | preview only on configured loopback, max body/3 fields, does not write any `fin_intake`/`fin_calc` row, no HTTP approval route |
| `CalcR0BrowserE2E` | React F02 immediate preview, stale responses rejected, save/reopen history still correct, UI not showing TEST_ONLY result as S2-approved |
| `CalcR0ConcurrencyTest` | Latch-based overlap of distinct jobs proves real CPU concurrency (not merely concurrent HTTP); output SHA stable vs sequential execution; bounded queue/backpressure |

Required local commands:

```bash
mvn -f form-library/pom.xml clean verify
mvn -f form-library/pom.xml -Dtest=CalcR0PostgresqlIntegrationTest test -Dfin.postgres.uat.enabled=true
cd frontend && npm ci && npm run check
```

The final CI must use **one exact commit SHA**, with real PostgreSQL migration **001–020** on a clean DB and a documented upgrade path from 018. `mvn verify` in H2 alone is not proof of PG concurrency.

**R0 acceptance gates:** A01–A25/A27/A29–A31 and Source-to-Fact F01–F16 in master/addendum; 0 invalid published current pointers; 0 committed revisions without outbox on the enabled PostgreSQL path; 0 duplicate immutable results for the same exact full binding; 100% determinism; 2–4 **actual concurrently executing** jobs; rollback/no-op produces no outbox event; no cache influence on admission. No performance number is considered achieved without measured test evidence.

## 6. Handoff checklist for Codex

1. Work only on `feat/fin-form-library-standalone`; capture initial HEAD, don't merge or change old migrations.
2. Deliver B1 first and run pure unit tests; then B2 preview; B3 calc schema+outbox; **B3F fact migration/assembler/qualification TX-Q**; B4 bounded workers consuming `fin_fact`; C1 node reuse; C2 resilience; D qualification. R0 B1 pure kernel may GREEN independently, but full end-to-end must NOT be marked GREEN before FactSet.
3. After each slice post: changed file list, exact SHA, tests executed/not executed, SQL negative evidence, bugs fixed.
4. Do **not** represent a synthetic `TEST_QUALIFIED` as independent financial S2. Do not mark release READY without real authority and IAM/ABAC.
5. On ambiguity, preserve source data and fail closed rather than adding hidden approval shortcuts.

**Supersession:** after code qualification, replace this temporary implementation plan with actual migration/API contract and link exact CI evidence. Never call a TODO implemented.
