package kz.fin.formlib;

import com.github.benmanes.caffeine.cache.Ticker;
import kz.fin.formlib.service.CatalogReadCache;
import kz.fin.formlib.service.CatalogPaging;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class CatalogCachePagingUnitTest {
  @Test void boundedCacheExpiresWithoutSleepAndKeepsLocalesIndependent(){
    AtomicLong nanos=new AtomicLong();
    Ticker ticker=nanos::get;
    var cache=new CatalogReadCache(Duration.ofMinutes(5),2,ticker);
    AtomicInteger loads=new AtomicInteger();
    assertEquals("ru-1",cache.get("forms","ru",()->"ru-"+loads.incrementAndGet()));
    assertEquals("ru-1",cache.get("forms","ru",()->"wrong"));
    assertEquals("en-2",cache.get("forms","en",()->"en-"+loads.incrementAndGet()));
    assertEquals(2,loads.get());
    assertTrue(cache.stats().hitCount()>=1);
    assertTrue(cache.estimatedSize("forms")<=2);
    nanos.addAndGet(Duration.ofMinutes(6).toNanos());
    assertEquals("ru-3",cache.get("forms","ru",()->"ru-"+loads.incrementAndGet()));
    assertEquals(3,loads.get());
    assertThrows(IllegalArgumentException.class,
      ()->cache.get("financial-revisions","private",()->"must not cache"));
    cache.invalidateAll();
    assertEquals("ru-4",cache.get("forms","ru",()->"ru-"+loads.incrementAndGet()));
    assertEquals(4,loads.get());
  }

  @Test void publicationEpochPreventsCachedOldGenerationFromBeingReused(){
    var cache=new CatalogReadCache(Duration.ofMinutes(1),30,Ticker.systemTicker());
    assertEquals("old",cache.get("forms","ru",()->"old"));
    cache.invalidateAfterCommit(); // without an active transaction, invalidate now
    assertEquals("new",cache.get("forms","ru",()->"new"));
    assertEquals("new",cache.get("forms","ru",()->"unexpected"));
  }

  @Test void nestedLookupsAcrossNamespacesNeverCauseRecursiveUpdate(){
    var cache=new CatalogReadCache(Duration.ofMinutes(1),30,Ticker.systemTicker());
    var result=cache.get("ui-launch","ru|F02",()->
      cache.get("form-detail","ru|F02",()->
        cache.get("forms","ru",()->"F02")));
    assertEquals("F02",result);
    assertEquals(3,cache.estimatedSize());
    assertEquals("F02",cache.get("ui-launch","ru|F02",()->"not-evaluated"));
  }

  @Test void paginationBoundsAndSliceDeterministically(){
    var first=CatalogPaging.page(List.of("a","b","c","d","e"),0,2,"CARDS","CODE");
    assertEquals(List.of("a","b"),first.items());
    assertEquals(5,first.totalElements());
    assertEquals(3,first.totalPages());
    assertTrue(first.hasNext());assertFalse(first.hasPrevious());
    var last=CatalogPaging.page(List.of("a","b","c","d","e"),2,2,"LIST","RELEVANCE");
    assertEquals(List.of("e"),last.items());
    assertFalse(last.hasNext());assertTrue(last.hasPrevious());
    var after=CatalogPaging.page(List.of("a","b"),1_000_000,50,"LIST","TITLE");
    assertEquals(0,after.items().size());
    assertEquals(2,after.totalElements());
    assertThrows(ResponseStatusException.class,
      ()->CatalogPaging.page(List.of("a"),0,0,"CARDS","CODE"));
    assertThrows(ResponseStatusException.class,
      ()->CatalogPaging.page(List.of("a"),0,51,"CARDS","CODE"));
    assertThrows(ResponseStatusException.class,
      ()->CatalogPaging.page(List.of("a"),-1,10,"CARDS","CODE"));
    assertThrows(ResponseStatusException.class,
      ()->CatalogPaging.page(List.of("a"),Integer.MAX_VALUE,2,"CARDS","CODE"));
    assertThrows(ResponseStatusException.class,
      ()->CatalogPaging.page(List.of("a"),0,10,"UNKNOWN","CODE"));
    assertThrows(ResponseStatusException.class,
      ()->CatalogPaging.page(List.of("a"),0,10,"CARDS","DROP TABLE"));
    assertThrows(ResponseStatusException.class,
      ()->CatalogPaging.normalizedSearch("x".repeat(101)));
    assertTrue(CatalogPaging.matches("f02","F02","Other"));
    assertFalse(CatalogPaging.matches("f02","F01","Other"));
  }
}
