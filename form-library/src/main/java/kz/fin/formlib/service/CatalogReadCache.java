package kz.fin.formlib.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Bounded per-namespace single-node cache for PUBLIC catalog projections only.
 *
 * A category projection calls other cached reference projections. Using one
 * Caffeine cache for both can trigger ConcurrentHashMap "Recursive update" from
 * computeIfAbsent (even with different keys in the same hash bin). Separate
 * caches by a fixed namespace safely support nested read dependencies.
 *
 * Never use for tenant facts, mutable drafts, payment/access grants, sharing,
 * financial revisions or caller identity. Single node: other nodes converge
 * by bounded TTL; event-based multi-node invalidation is future work.
 */
@Component
public class CatalogReadCache {
  private static final java.util.Set<String> NAMESPACES=java.util.Set.of(
    "currencies","period-types","metrics","metric-detail","forms","form-detail",
    "legacy-form-summaries","legacy-form-detail","ui-categories",
    "ui-category-forms","ui-launch","use-case-forms");
  private final Map<String,Cache<String,Object>> namespaceCaches=new ConcurrentHashMap<>();
  private final Duration ttl;
  private final long maxEntries;
  private final Ticker ticker;

  public CatalogReadCache(){this(Duration.ofMinutes(5),512,Ticker.systemTicker());}
  /** Explicit policy for deterministic expiry and LRU/size tests. */
  public CatalogReadCache(Duration ttl,long maxEntries,Ticker ticker){
    if(ttl==null||ttl.isNegative()||ttl.isZero()||maxEntries<1)
      throw new IllegalArgumentException("INVALID_CACHE_POLICY");
    this.ttl=ttl;this.maxEntries=maxEntries;this.ticker=Objects.requireNonNull(ticker);
  }
  private Cache<String,Object> cache(String namespace){
    if(namespace==null||!NAMESPACES.contains(namespace))
      throw new IllegalArgumentException("UNAPPROVED_CATALOG_CACHE_NAMESPACE");
    return namespaceCaches.computeIfAbsent(namespace,key->Caffeine.newBuilder()
      .maximumSize(maxEntries).expireAfterWrite(ttl).ticker(ticker)
      .recordStats().build());
  }
  @SuppressWarnings("unchecked")
  public <T> T get(String namespace,String key,Supplier<T> loader){
    if(key==null||loader==null)throw new IllegalArgumentException("INVALID_CACHE_KEY");
    return (T)cache(namespace).get(key,ignored->loader.get());
  }
  public void invalidateAll(){namespaceCaches.values().forEach(Cache::invalidateAll);}
  public void invalidateAfterCommit(){
    if(TransactionSynchronizationManager.isSynchronizationActive()){
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
        @Override public void afterCommit(){invalidateAll();}
      });
    }else invalidateAll();
  }
  public CacheStats stats(){
    return namespaceCaches.values().stream().map(Cache::stats)
      .reduce(CacheStats.empty(),CacheStats::plus);
  }
  public long estimatedSize(){
    return namespaceCaches.values().stream().mapToLong(Cache::estimatedSize).sum();
  }
  public long estimatedSize(String namespace){return cache(namespace).estimatedSize();}
}
