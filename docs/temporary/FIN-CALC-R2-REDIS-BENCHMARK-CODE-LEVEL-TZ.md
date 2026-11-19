# TEMP / FIN CALC-R2 — Multi-Worker Cache Benchmark and Redis Activation Gate (Code-Level TЗ)

> **Status:** DESIGN ONLY / BENCHMARK NOT RUN.  
> **Prerequisites:** R0 and R1 implementation/test gates GREEN on known commit SHA.  
> **Branch:** `feat/fin-form-library-standalone`.  
> **Goal:** Decide **with measured evidence** whether optional Redis L2 should be enabled for FIN HTAP calculations. No performance claim without real measurement.

## 1. Decision to be made

Compare **three identical workloads** under the same PostgreSQL, input data, pinned algorithm versions and bounded multithreaded worker configuration:

- **MODE_OFF:** Node-value cache disabled; descriptor graph Caffeine may remain loaded equally in all modes. Reuse values from immutable PostgreSQL only.
- **MODE_L1:** Bounded local Caffeine node-value cache; no Redis.
- **MODE_L1_L2:** Same Caffeine plus optional Redis L2; PostgreSQL/compute fallback.

Redis has **no** role in S2 authority, calc job queue, distributed locking, current result publication or source financial state. If MODE_L1_L2 does not convincingly improve latency and PostgreSQL load, **leave Redis disabled**.

**Important:** R0's F02 three-operator formula is extremely cheap. An honest R2 benchmark may find Redis slower. This is an acceptable result, not a failure requiring architectural complexity. To model expensive candidate methodologies, use **labelled synthetic CPU tasks with deterministic digest and work budget in the benchmark harness only**, not published FIN methodology contracts or finance data.

## 2. Concrete code/artifact plan (new files)

```text
form-library/src/test/java/kz/fin/formlib/calc/perf/
    CalcBenchmarkFixtureFactory.java
    CalcBenchmarkDataVerifier.java
    CalcBenchmarkMeasurementExporter.java
    CalcBenchCorrectnessIntegrationTest.java
scripts/fin-calc/
    run-r2-benchmark.sh
    calc-r2-benchmark.js              # k6 optional HTTP runner
    collect-pg-statistics.sql
    verify-benchmark-manifest.py
docs/temporary/
    FIN-CALC-R2-RESULTS-TEMPLATE.md   # results are empty until a real run
```

Use a pinned toolchain/container manifest; do not invent bench output values. All benchmark scripts must fail on missing PostgreSQL/Redis/Java executable or mismatched input/algorithm SHA; no hard-coded secrets and no production endpoints.

### 2.1 Fixture generation signatures

```java
public record BenchmarkFixtureSpec(
    long seed,
    int tenantCount, int projectCount,
    int periodsPerProject, int formRevisionsPerPeriod,
    int jobs, int parallelWorkers,
    double targetWarmHitRatio,
    double changedOperandRatio,
    String algorithmDescriptorSha256) {}

public interface BenchmarkFixtureFactory {
  BenchmarkManifest generate(BenchmarkFixtureSpec spec);
}

public record BenchmarkManifest(
    String datasetSha256,
    String gitSha, String databaseMigrationSha,
    String algorithmDescriptorSha256,
    long expectedPlans, long expectedImmutableResults,
    long expectedQualifiedTestOnlyBindings) {}
```

Synthetic organizations/projects must be **stable UUIDs from deterministic seed**; explicitly scope them to test-only datasets. Inputs use the real F02 fields `netRevenue/cogs/operatingExpenses` and `PeriodResolver` codes/intervals; synthetic qualified issuer only under test profile. Every measured qualified job must read **persisted `fin_fact.fact_observation`**, and benchmark manifests record factset SHA, exactly 3 observations per F02 test FactSet, exact mapping version, and source revision SHA. Never benchmark an unqualified raw-JSON shortcut. **The benchmark must compare cache modes against the same monthly-partitioned `fin_fact.fact_observation` schema**, include `period_start` filtering and `EXPLAIN (ANALYZE, BUFFERS)` pruning evidence; compare partitioned vs unpartitioned separately to avoid attributing partitioning gains to Redis. A multi-year 36-month fixture must pre-create all required partitions. Record partition count and fact observation rows per month in benchmark manifest.

For the same benchmark manifest, MODE_OFF/L1/L1_L2 **must produce exactly the same sorted output values and `outputValuesSha256`** per qualified execution binding; `resultSha256` may differ because full immutable result metadata/lineage includes cache-mode-dependent `REUSED` flags or timing. Compare exact economic outputs and source binding identities, not cache execution provenance. Reuse markers and timings are excluded from the economic values hash (record separately in audit metadata). Each run must start from a restored **equivalent PostgreSQL snapshot** (or carefully controlled reset+reseed) and isolated new cache namespace; warm runs must use the same deterministic warm-up manifest. Never compare one mode with a fresh DB and the other with hot DB by accident.

### 2.2 Benchmark levels

| Level | Purpose | Workload |
|---|---|---|
| **MICRO** | Pure `BigDecimal` / SHA / cache lookup overhead; optional JMH | One F02 calculation and one node cache hit; no SQL |
| **INTEGRATION** | Job queue + DAG + worker and PostgreSQL / Redis calls | Same synthetic revision/FactSet jobs across 3 cache modes |
| **SYSTEM** | HTTP preview concurrent with qualified jobs | Multiple independent user preview requests while background worker pool is saturated |
| **RECOVERY** | Redis timeout/restart, PG contention and late worker | Bounded fallback; zero wrong CURRENT, exact lineage |

## 3. Parameterized load matrix

Minimum prescribed matrix, with **same** inputs/configuration across cache modes:

| Dimension | Values / variants |
|---|---|
| Worker threads | `1, 2, 4` (8 only if host has CPU and available DB pool) |
| Concurrent jobs | `25, 100, 500` initially; scale higher only if stable |
| Active tenant count | `1, 10, 50`; independent tenant scope, no cross-tenant reuse |
| Historical periods | `1, 12, 36` per project |
| Warm cache target | `0%, 50%, 90%` effective reusable-node opportunity; measure observed actual hit ratio |
| Edited input | `operatingExpenses`, `cogs`, `netRevenue` plus unchanged exact replay |
| Work complexity | F02 tiny DAG; labelled synthetic medium/heavy CPU node (benchmark-only, not FIN S2) |
| Cache scenario | cold start, warm repeat, TTL expired, version bump, high churn |
| Failure | Redis unavailable, +network latency, corrupt payload, worker reclaim, stale approval |

Avoid materializing an enormous full Cartesian product. Define a documented **core subset** covering all axes and one representative factorial/stress run. Always publish the exact matrix executed and skipped cases with rationale; no invented saturation throughput.

**Benchmark run duration:** at least a bounded warmup and statistically sufficient measurement window to collect stable p95/p99; choose duration by actual host speed, not a claimed universal seconds-based SLA. Collect at least 5 independent runs in each mode with order randomized or rotated to mitigate thermal/DB cache order bias; report median and confidence intervals. Cold and warm samples must never be mixed in one percentile.

## 4. Java/Spring configuration and actual workload controls

```yaml
fin:
  calc:
    workers:
      max: 4
      per-tenant-in-flight: 2
    cache:
      node-values:
        enabled: true
        maximum-weight-bytes: 33554432
        ttl: PT5M
    redis:
      enabled: false # switch to true ONLY for MODE_L1_L2
```

For `MODE_OFF`: set `fin.calc.cache.node-values.enabled=false` and Redis OFF. For `MODE_L1`: node-values ON, Redis OFF. For `MODE_L1_L2`: node-values ON, Redis ON with same TTL/weight assumptions. All other flags, lease durations, synthetic issuer and input policies remain unchanged.

**Important resource accounting:** keep `workers.max`, Hikari max pool size, DB CPU/IO, PostgreSQL shared buffers, Redis memory, JVM heap/GC, host CPU and Redis network latency in the benchmark manifest. Do not treat Redis running on a different machine as free infrastructure; record total system CPU/memory and operating cost. No `unlimited virtual threads`; CPU-bound work uses bounded pool.

### Load script CLI contract

```bash
# Proposed commands; scripts are not created until R2 implementation.
scripts/fin-calc/run-r2-benchmark.sh --mode OFF --dataset baseline-f02 --workers 4 --runs 5
scripts/fin-calc/run-r2-benchmark.sh --mode L1 --dataset baseline-f02 --workers 4 --runs 5
scripts/fin-calc/run-r2-benchmark.sh --mode L1_L2 --dataset baseline-f02 --workers 4 --runs 5
```

All scripts must:
1. Start/connect to isolated PostgreSQL 16 and optional Redis via Docker Compose/Testcontainers; reject production hostnames and non-test database name.
2. Verify exact git commit, migration checksums, JVM version, descriptor SHA and fixture manifest.
3. Restore equal immutable input fixtures and reset queue result state **only inside isolated test DB**, never DELETE financial data in persistent environment.
4. Separate warmup from measurement; export structured JSON/CSV metrics per run with timestamps and mode.
5. Compare **semantic outputs** by signed manifest; fail if any result differs or invalid CURRENT is published.
6. Restore default `fin.calc.redis.enabled=false` after tests.

## 5. Metric contracts — no misleading cache-only success

Record at minimum:

| Metric | Measurement source | Why |
|---|---|---|
| `calc_end_to_end_latency_ms` p50/p95/p99 | Qualified trusted event → committed immutable result + separately to CURRENT | User-visible latency; distinguishes queue and CPU |
| `calc_preview_latency_ms` p95/p99 | HTTP preview | Interactive responsiveness under load |
| `calc_node_compute_ms` | Worker node execution | Real calculation saving |
| `calc_nodes_reused_total`, `calc_nodes_recomputed_total` | DAG | Verify incremental benefit |
| `calc_cache_get_total{tier,outcome}` | Caffeine/Redis | L1 vs L2 hits/misses, wrong/malformed payload |
| `calc_redis_rtt_ms`, `calc_cache_timeout_total` | Redis adapter | Cost of network cache |
| `calc_db_reads_per_result`, query time, shared buffer hits | JDBC + `pg_stat_statements` if available | DB load reduction |
| `calc_db_pool_active`, wait time | Hikari | Avoid hidden connection starvation |
| `calc_worker_cpu_utilization`, queue depth, queue lag | JVM + PG | Fairness/backpressure |
| `calc_total_errors`, retries, fence rejects | Job state metrics | Correctness under load |
| `jvm_gc_pause_ms`, heap and RSS | JVM/process | Cache memory overhead |
| Redis memory, evictions, network traffic | Redis exporter / INFO (test only) | Operation cost |

Do not expose tenant IDs, raw numbers, source financial content, HMAC secrets or SHA values as high-cardinality metric labels. Link traces through redacted IDs in restricted logs only.

## 6. Activation gate (explicit engineering hypothesis)

Default **Redis OFF**. Proposal for quantitative acceptance under representative **warm shared workloads**:

1. Correctness: **zero** cross-tenant cache reads, wrong canonical output hashes, unauthorized current publications, stale-fence wins or unbounded queue failures in all modes. Any such violation = **BLOCK** regardless of performance.
2. Material gain vs MODE_L1 at **same worker count**, same DB hardware and equivalent warm dataset: aim for **≥15% lower end-to-end p95**, **≥10% lower p99**, and **≥20% fewer PostgreSQL reads per accepted result**; improvements must be repeatable across at least 5 runs and not explained by one outlier. These are **initial decision thresholds**, subject to documented revision before the experiment, not achieved figures.
3. No material regression in interactive preview p95/p99 or increased error/retry rate. No hidden memory/network overhead disproportionate to benefit; report total resource cost.
4. Redis outage + latency injection must still complete valid calculation using PG/CPU fallback, with bounded additional delay and no misleading SUCCESS for cached stale approval.
5. If performance effect confidence interval overlaps zero or workload has almost no reusable expensive nodes, keep Redis OFF; do not tune benchmark by inventing a hit ratio without reporting observed hits.

**Decision outcomes:** `ENABLE_WITH_EVIDENCE`, `KEEP_DISABLED_NO_GAIN`, `KEEP_DISABLED_UNCERTAIN`, `BLOCK_CORRECTNESS`. Only first outcome allows a controlled opt-in in non-production; production still requires actual independent financial S2 + IAM.

## 7. Benchmark report artifact requirements

Create a populated `docs/temporary/FIN-CALC-R2-RESULTS-<date>-<gitsha>.md` after each real run; include:

```text
Timestamp/timezone, Git SHA, Java/Spring version, PostgreSQL image,
Redis image, CPU/memory/OS, database migration SHA, dataset SHA,
algorithm artifact/config/params SHAs, worker/queue/pool limits,
mode OFF/L1/L1_L2, cold/warm/repeat description,
five runs per mode, raw metric file paths, aggregation method,
p50/p95/p99, DB reads, reused nodes, Redis RTT, error/fencing stats,
`outputValuesSha256` equivalence validation, separately recorded `resultSha256` per run, activation decision and reviewer.
```

The report must link test CI artifacts and raw metrics. No screenshot-only or hand-entered invented latency evidence. Preserve reproducible seeds and exact load parameters.

## 8. Required tests

| ID | Test | Expected |
|---|---|---|
| B01 | Same dataset, config, SHA, 3 cache modes | Exactly equal canonical financial outputs and `outputValuesSha256`; exact source binding identities; technical reuse lineage may differ |
| B02 | New revision touches only operatingExpenses | Only dependent node recomputed; all modes same full output |
| B03 | Tenant isolation under 50-tenant load | No shared key/value reuse across tenants |
| B04 | One Redis miss/outage mid-run | Correct PG fallback and bounded queue |
| B05 | Redis wrong HMAC/wrong scale/payload oversized | Cache miss, never publish corrupted value |
| B06 | Worker lease/fencing under 4 concurrent worker claimants | No late-owner success |
| B07 | Revocation/config mutation while cached data available | No CURRENT after revoke/old algorithm |
| B08 | Saturated worker pool and preview traffic | Independent preview budget; record p95/p99 |
| B09 | Cold vs warm independent runs | No mixed percentile reporting |
| B10 | Activation decision thresholds + confidence intervals | Machine-readable decision with explicit no-gain outcome |
| B11 | Redis OFF by default after benchmark | Green boot and calculations without Redis |
| B12 | Synthetic medium/heavy tasks labeled not financial methods | No fake method approval/financial output |

## 9. Delivery plan and freeze boundary

- **R2.A**: fixture builder + exact SHA manifest + SQL metric collector, correctness comparer. Gate B01/B02.
- **R2.B**: three-mode runner + randomized/rotated runs + controlled cold/warm + worker matrix. Gate B03/B08/B09.
- **R2.C**: fault injection, HMAC/cache validation and revoke/fence negative tests. Gate B04–B07/B11/B12.
- **R2.D**: raw metrics/report, quantitative release decision and sign-off. Gate B10 and complete R0/R1 regression.

**No code is implied by this temporary TЗ**. A benchmark decision is only supported after scripts, fixtures, tests and metrics have actually run on the same exact SHA. If Redis is not materially faster, ship Caffeine+PostgreSQL without Redis; that is an intended valid outcome.
