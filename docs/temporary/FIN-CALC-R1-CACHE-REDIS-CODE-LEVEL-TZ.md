# TEMP / FIN CALC-R1 — CalculationCache Port + Optional Redis L2 (Code-Level TЗ)

> **Status:** DESIGN ONLY; implement **after** R0 PostgreSQL concurrency + immutable result GREEN.  
> **Branch:** `feat/fin-form-library-standalone`; package root `kz.fin.formlib.calc`.  
> **Owner contract:** [R0 implementation TЗ](./FIN-CALC-R0-CODE-LEVEL-TZ.md) and [Master CALC-R0](../FIN-CALC-R0-INCREMENTAL-CALCULATION-ARCHITECTURE-TZ.md).  
> **Target chain:** `PostgreSQL source/authority → DAG impact → nodeFingerprint → Caffeine L1 → optional Redis L2 → verified PostgreSQL node fallback → Worker → NEW immutable result → guarded publication`.

## 1. Scope and non-goals

R1 introduces **one typed caching port** and optional Redis adapter to reduce repeated execution of **already trusted, immutable computational values**. **Prerequisite:** R0 `fin_fact.fact_set` + **monthly partitioned** `fact_observation` are physically committed and read by QualifiedFactReader using pinned period-range predicates; cache must never fall back to raw `fin_intake` draft JSON to fill a missing qualified observation. R1-INGEST ROWSET/EVIDENCE adapters are **separate scope and gates** from Redis caching; never infer all 166 forms financially approved. It does not change PostgreSQL as source of truth, planner correctness, execution fingerprint, lease/fencing, qualified FactSet admission or immutable result schema. R1 adds **node-value Caffeine** beyond R0 descriptor/graph Caffeine, without inserting tenant financial values into `CatalogReadCache`.

- New `CalculationCache` port; `CaffeineNodeValueCache`, `RedisNodeValueCache`, `NoopNodeValueCache`, `TieredCalculationCache`.
- Redis disabled by default; startup with no Redis endpoint must be GREEN, and fallback must remain correct.
- No Redis distributed lock, Redis queue, Redis Pub/Sub-based publication, remote approval cache, current-result pointer cache, job-owner cache, user-input raw JSON cache.
- Redis is **best-effort**, never a dependency for successful financial computation.
- Shared immutable values may be computationally reusable between exact qualified plans of the same scope, but each new binding still gets a **NEW full immutable `calc_result`** with new lineage. No cross-tenant reuse.

## 2. Implementation slices

| ID | Deliverables | Gate |
|---|---|---|
| **R1.A — Port/contracts** | Value/key DTOs, deterministic codec, `CalculationCache`, Noop and Caffeine adapters, validation | Unit tests of canonical keys/values, TTL/eviction, same-tenant only |
| **R1.B — Redis adapter** | Optional `StringRedisTemplate` adapter, lazy connection, timeouts/TLS/ACL, disabled-by-default config, signed payload | Redis Testcontainers integration; default boot without Redis |
| **R1.C — Tiered lookup** | L1 → L2 → verified PostgreSQL reuse → recompute; backfill upper tiers after PostgreSQL result commit; circuit breaker | Fallback, poisoning/timeout/invalidation regression |
| **R1.D — Concurrency/observability** | Single-flight local coalescing, bounded caches, tenant quotas, metrics, docs | Concurrent consistency and no new current publication path |
| **R1.E — Qualification** | Exact SHA Java/PG/Redis integration regression; rollback/kill Redis tests; test-only FactSet | All R1 acceptance tests GREEN; Redis OFF remains default |

### Planned Java source tree (all files NEW)

```text
form-library/src/main/java/kz/fin/formlib/calc/
  cache/
    CalculationCache.java
    NodeCacheKey.java
    NodeCacheValue.java
    NodeCacheCodec.java
    NoopCalculationCache.java
    CaffeineNodeValueCache.java
    RedisNodeValueCache.java
    TieredCalculationCache.java
    NodeReuseService.java
    LocalSingleFlight.java
    CalcCacheMetrics.java
  config/
    CalculationCacheProperties.java
    RedisCalculationCacheConfiguration.java
form-library/src/test/java/kz/fin/formlib/calc/cache/
  CalculationCacheContractTest.java
  CaffeineNodeValueCacheTest.java
  TieredCacheFallbackTest.java
  RedisNodeValueCachePostgresqlTest.java
  CacheIsolationConcurrencyTest.java
```

## 3. Exact port and value DTOs

```java
package kz.fin.formlib.calc.cache;

public record NodeCacheKey(
    String schemaVersion, String tenantScopeDigest,
    String nodeFingerprint) {
  // Validate allowlisted schemaVersion and 64 lowercase hex digests.
}

public record NodeCacheValue(
    String schemaVersion, String nodeFingerprint,
    String valueState, String canonicalNumericValue,
    String unitCode, String descriptorSha256,
    String sourceCommittedResultSha256,
    String payloadSha256,
    String authenticationTag) {
  // VALIDATE valueState+number coherence, scale, unit, descriptor,
  // exact fingerprint, value domain and signature.
}

public interface CalculationCache {
  Optional<NodeCacheValue> get(NodeCacheKey key);
  void put(NodeCacheKey key, NodeCacheValue committedValue);
  void invalidateLocalDescriptor(String descriptorSha256);
}
```

**Semantics:** `get` is **best effort** and returns a reusable **calculation value only**, never authority/eligibility/lineage; it may be a miss. `put` is best-effort after successful `calc_result` TX-D COMMIT, not inside an uncommitted transaction. Cache `put` must never fail or roll back an already committed financial result. `invalidateLocalDescriptor` targets descriptor cache only, and **must not delete immutable node-value history by period**.

- Domain-separate hashes: `FIN-CALC-NODE-CACHE-V1`, `FIN-CALC-OP-V1`, tenant digest. Validate max key length and exact SHA hex length.
- NodeCacheValue contains **no** FactSet approval flag, `current_result`, actor, JWT, original source JSON, customer metadata, PII or old plan ID. Old lineage is NOT reused.
- Numeric is BigDecimal canonical **string**, not Java binary serialization, floating point, locale text or arbitrary polymorphic JSON.
- If value is `MISSING`/`INVALID`, do **not** cache by default. Only persist successful `PRESENT` deterministic values whose exact algorithm/params/operand hashes are committed to nodeFingerprint.
- Cached payload must include `sourceCommittedResultSha256` as provenance integrity hint. It is **not** current authority: do not require old result to remain current if new qualified operands match exactly.
- Where Redis is not trusted against malicious writes, protect cache payload with **HMAC-SHA256** using application-managed secret; plain SHA only detects accidental corruption. Never store HMAC secret in Redis; support key ID / rotation or treat old signature as miss. This is performance cache, not financial S2 security proof.
- Cache metadata fields should be bounded and canonicalized; reject payloads over configured max bytes.

## 4. Cache key policy and invalidation

Example logical key (no plaintext tenant ID):

```text
fin:calc:node:v1:{tenantScopeDigest}:{nodeFingerprint}
```

`tenantScopeDigest = HMAC-SHA256(cacheKeySecret, canonical(organizationId,economicEntityId,businessScopeId,projectId,currency,coverage,reportingBasis,dataSpace))`.

`nodeFingerprint` already contains period/typed consumed operands/versioned algorithm+rounding policy. Never key on `projectId`, `formCode` or period alone. No actor/PII inside key; Redis ACL+TLS protect storage. Scope hashing is **not** an authorization check.

**Invalidation rule:** when F02 operatingExpenses changes, new `executionFingerprint` is created; only `operatingResult` nodeFingerprint changes. Gross profit and margin may still hit prior cache. New `calc_result` must store copied stable node values **with new qualified binding and lineage**. Never delete all October keys as a correctness mechanism. TTL/eviction controls memory; revocation controls current-result visibility independently.

A revised method SHA, effective config, rounding or input unit must alter relevant nodeFingerprint; old cache values become naturally unreachable. Cache `invalidateLocalDescriptor` helps memory convergence but is not a security gate. Make these guarantees executable via tests.

## 5. Tiering algorithm and single-flight

```java
final class NodeReuseService {
  NodeResult resolveNode(TrustedExecutionContext context,
                         NodeDescriptor op, List<TypedValue> inputs,
                         List<NodeResult> parents) {
    // context must already be admitted and pinned.
    var key = fingerprintService.nodeKey(context, op, inputs, parents);
    var cached = cache.get(key);              // L1, optional L2; bounded
    if (cached.isPresent() && codec.validate(cached.get(), key, context)) {
      return copyValueWithNewBinding(cached.get(), context, "REUSED");
    }
    var previous = pgLookup.findCommittedCompatibleNode(context, key);
    if (previous.isPresent()) {
      return copyValueWithNewBinding(previous.get(), context, "REUSED");
    }
    return executeDeterministicOperator(context, op, inputs, parents);
    // Caller materializes new immutable result with NEW exact lineage.
    // Cache population is AFTER successful PostgreSQL TX-D COMMIT.
  }
}
```

**Lookup:** `Caffeine L1 → Redis L2 if enabled → PostgreSQL compatible immutable node → recompute`. Cache miss is NOT an exception. Return exactly one `NodeResult` with marker, and preserve deterministic final output ordering.

**Concurrency:** use bounded `LocalSingleFlight` keyed by scoped node hash **only for pure computation**; do not hold a DB lock while awaiting a single-flight future. Multiple processes may compute same node; DB uniqueness and fencing still enforce at-most-one accepted immutable result per exact execution. On single-flight failure, remove future, propagate typed failure and permit bounded retry. Do not turn a cache hit into `SUCCEEDED` without job state + fencing.

**Poisoning:** `NodeCacheCodec.validate` verifies schema, fingerprint, payload HMAC, normalized numeric precision/state/unit and descriptor signature. Reject wrong tenant digest, wrong algorithm hash, corrupt JSON, oversized payload, invalid HMAC; treat as miss, increment aggregate metric; optionally evict corrupt key. Do not silently coerce malformed values to zero.

## 6. Spring Boot wiring (only instantiate Redis client when enabled)

Existing `form-library/pom.xml` already has Caffeine; R1 **may add** Spring Data Redis starter and Lettuce with tested versions from the Spring Boot BOM (no forced version), but the application must not require Redis to start or to save F02.

```java
@Configuration
@EnableConfigurationProperties(CalculationCacheProperties.class)
class RedisCalculationCacheConfiguration {
  @Bean
  @ConditionalOnProperty(
      prefix = "fin.calc.redis", name = "enabled", havingValue = "true")
  CalculationCache redisNodeValueCache(StringRedisTemplate template,
                                      NodeCacheCodec codec) {
    return new RedisNodeValueCache(template, codec);
  }
}
```

In actual implementation **avoid bean ambiguity**: define named L1 `CaffeineNodeValueCache`, optional L2 `RedisNodeValueCache`, and one `@Primary TieredCalculationCache` port. If Redis disabled, the tiered adapter contains only L1+PG fallback; `NoopCalculationCache` is test baseline. Use `@ConditionalOnProperty` and explicit optional injection, not bean replacement by ambiguity. Redis startup, network handshake and slow reconnection must be lazy/bounded; if enabling Redis fails, configured policy uses degraded cache mode, not exit from financial calculations.

Proposed feature config:

```yaml
fin:
  calc:
    cache:
      node-values:
        enabled: true
        maximum-weight-bytes: 33554432 # 32 MiB L1 budget, tune
        ttl: PT5M
      single-flight:
        max-keys: 256
      payload:
        max-bytes: 2048
    redis:
      enabled: false
      ttl: PT15M
      connect-timeout: 150ms
      command-timeout: 50ms
      breaker-failures: 5
      breaker-open: PT30S
      key-prefix: "fin:calc:node:v1"
      # credentials, TLS and HMAC key via environment/secret store ONLY
```

**Canonical feature flag:** `fin.calc.redis.enabled=false` (same as the master R0 contract). Do not introduce an alias `fin.calc.cache.redis.enabled`; there must be exactly one authoritative Redis enablement flag.

Required operational controls: TLS where Redis is remote; ACL least privilege; authenticated credentials from environment/secret store; disable potentially dangerous Redis commands for application identity; max key/TTL, no user-controlled raw prefixes; bounded Lettuce pending command queue and timeouts. Redis must be treated as semi-trusted transient storage.

## 7. PostgreSQL interaction: no new authority tables

R1 adds **zero mandatory financial source tables**. R0 `fin_calc.calc_result` already contains full canonical node outputs (including node hashes); PG lookup must be indexed/bounded to a prior successful plan for same scope and compatible descriptor. Do not brute-force JSONB across all projects/forms.

Optionally add an **index/materialized node-value lookup only after EXPLAIN ANALYZE** shows expensive PG fallback; such an index is a cache read model and must be derived/rebuildable from immutable PG results. If it becomes necessary, propose it as a separate migration and migration gate, not a hidden sixth mandatory R0 table.

Result commit:
- TX-D **always** persists new immutable result + lineages, even on 100% cache hits when exact qualified binding is new.
- After commit, backfill cache from signed canonical committed node outputs; asynchronous cache failure does not change result.
- TX-E always rechecks approved binding/epoch; Redis cannot authorize or advance current pointer.

## 8. Tests and failure injection (R1 required)

| ID | Scenario | Required evidence |
|---|---|---|
| C01 | Redis disabled and endpoint unavailable | Spring startup/save/preview/worker GREEN; zero Redis connection attempts |
| C02 | Two tenants same numeric input | Different key; no cross-tenant read or leakage |
| C03 | New revision changes only operatingExpenses | Same nodeFingerprint for GP/margin, different operatingResult; NEW execution/result lineage |
| C04 | Wrong unit/descriptor SHA, old payload schema | Cache miss and correct PG/CPU fallback |
| C05 | Tamper with Redis JSON/value/signature | HMAC verification fails; no published corrupted output |
| C06 | Redis suddenly disappears mid-calculation | Connection timeout bounded; fallback works; job status correct |
| C07 | Redis returns stale cached old node after method version change | New key, no stale reuse |
| C08 | 4 concurrent workers same node key | Bounded local single-flight; no duplicate result/current despite cross-process misses |
| C09 | TTL/eviction/L1 memory pressure | Correct recomputation, stable SHA; no unbounded heap growth |
| C10 | Cache PUT fails after PostgreSQL TX-D COMMIT | financial result remains committed; cache error is observed only |
| C11 | Revocation between cache HIT and TX-E | publication denied, old result historical only |
| C12 | Disabled/failed L2 impact to p95 | Report measurements, not unsubstantiated speedup claim |

### Example opt-in commands

```bash
mvn -f form-library/pom.xml clean verify
mvn -f form-library/pom.xml -Dtest=CalculationCacheContractTest,TieredCacheFallbackTest test
mvn -f form-library/pom.xml -Dtest=RedisNodeValueCachePostgresqlTest test -Dfin.redis.uat.enabled=true
```

Redis Testcontainers can use `GenericContainer<>("redis:7-alpine")` with real authentication/TLS test settings when configured. Use PostgreSQL 16 Testcontainers for financial integration; H2 does not prove publication/lease correctness. Test no-Redis bootstrap independently, not just with a mock that always returns a value.

## 9. DoD and rollback

**DoD R1:** all C01–C12 GREEN, PostgreSQL R0 scenarios unchanged, optional Redis default OFF, no approval/current/status cache, no financial value leakage, bounded memory/timeouts, new FactSet ⇒ new full immutable result. Capture exact HEAD, PostgreSQL and Redis image/dataset versions, test logs and outcome.

**Rollback:** set `fin.calc.redis.enabled=false`; the system immediately falls back to Caffeine/PG/compute without schema rollback. Disable L1 node values separately if required. No Redis flush is necessary for correctness; old versioned keys expire by TTL. Financial source, job and result state remain authoritative in PostgreSQL.

**Release note:** code-level plan only; introducing R1 must not silently close independent S2/IAM/ABAC production HOLD.
