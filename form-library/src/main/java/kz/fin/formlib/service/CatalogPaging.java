package kz.fin.formlib.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * Stable, bounded zero-based offset paging for 166-reference-code catalog.
 * Paging is over a fully materialized PUBLIC read snapshot (max 166 items).
 * Query-driven views are not cached to avoid high-cardinality cache keys.
 */
public final class CatalogPaging {
  private CatalogPaging(){}
  public record Page<T>(List<T> items,int page,int size,long totalElements,
      int totalPages,boolean hasPrevious,boolean hasNext,
      String viewMode,String sortMode) {}
  public static String viewMode(String raw){
    String s=raw==null?"CARDS":raw.trim().toUpperCase(Locale.ROOT);
    if(!s.equals("CARDS")&&!s.equals("LIST"))
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_VIEW_MODE");
    return s;
  }
  public static String sortMode(String raw){
    String s=raw==null?"RELEVANCE":raw.trim().toUpperCase(Locale.ROOT);
    if(!List.of("RELEVANCE","CODE","TITLE").contains(s))
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_SORT_MODE");
    return s;
  }
  public static String normalizedSearch(String raw){
    if(raw==null)return "";
    String s=raw.strip().toLowerCase(Locale.ROOT);
    if(s.length()>100)
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"SEARCH_TOO_LONG");
    return s;
  }
  public static boolean matches(String q,String... values){
    if(q.isEmpty())return true;
    for(var value:values)
      if(value!=null&&value.toLowerCase(Locale.ROOT).contains(q))return true;
    return false;
  }
  public static <T> Page<T> page(List<T> items,int page,int size,
      String view,String sort){
    if(items==null||page<0||page>1_000_000||size<1||size>50)
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_PAGE_REQUEST");
    String mode=viewMode(view),order=sortMode(sort);
    int total=items.size(),pages=total==0?0:(total+size-1)/size;
    long requested=(long)page*size;
    int from=(int)Math.min(requested,total);
    int to=Math.min(total,from+size);
    return new Page<>(List.copyOf(items.subList(from,to)),page,size,total,pages,
      page>0,page+1<pages,mode,order);
  }
}
