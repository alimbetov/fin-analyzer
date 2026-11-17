package kz.fin.formlib.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bounded single-node cache for PUBLIC/REFERENCE catalog projections only.
 * Never cache tenant facts, permissions, paywall/sharing grants, draft status,
 * previews with arbitrary request values or current financial revisions.
 */
@Component
public class CatalogReadCache {
  private final Cache<String,Object> cache;

  public CatalogReadCache() {
    this(Duration.ofMinutes(5),512,Ticker.systemTicker());
  }
  /** Testable exact bounds/expiry without sleeping. */
  public CatalogReadCache(Duration ttl,long maxEntries,Ticker ticker) {
    if(ttl==null||ttl.isNegative()||ttl.isZero()||maxEntries<1)
      throw new IllegalArgumentException("INVALID_CACHE_POLICY");
    cache=Caffeine.newBuilder().maximumSize(maxEntries)
      .expireAfterWrite(ttl).ticker(Objects.requireNonNull(ticker))
      .recordStats().build();
  }
  @SuppressWarnings("unchecked")
  public <T> T get(String namespace,String key,Supplier<T> loader) {
    if(namespace==null||namespace.isBlank()||key==null||loader==null)
      throw new IllegalArgumentException("INVALID_CACHE_KEY");
    // Keys include locale, record identity, and any stable public facet.
    // Free-form searches and arbitrary page-number combinations are NOT cached.
    return (T)cache.get(namespace+"|"+key,k->loader.get());
  }
  public void invalidateAll(){cache.invalidateAll();}
  public void invalidateAfterCommit(){
    if(TransactionSynchronizationManager.isSynchronizationActive()){
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
        @Override public void afterCommit(){cache.invalidateAll();}
      });
    }else cache.invalidateAll();
  }
  public CacheStats stats(){return cache.stats();}
  public long estimatedSize(){return cache.estimatedSize();}
}
