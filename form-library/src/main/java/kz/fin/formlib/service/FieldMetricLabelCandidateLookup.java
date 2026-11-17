package kz.fin.formlib.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Only public editorial draft display text. Every label requires human review.
 * Financial source and pinned form schema do not derive from these labels.
 */
@Service
public class FieldMetricLabelCandidateLookup {
  public record Label(String title,String reviewStatus) {}
  private final JdbcTemplate db;
  private final CatalogReadCache cache;
  public FieldMetricLabelCandidateLookup(JdbcTemplate db,CatalogReadCache cache){
    this.db=db;this.cache=cache;
  }
  private static String locale(String l){
    if(!Set.of("ru","kk","en","zh-Hans").contains(l))
      throw new IllegalArgumentException("UNSUPPORTED_LOCALE");
    return l;
  }
  public Map<String,Label> fields(String formCode,String language){
    String l=locale(language);
    if(formCode==null||formCode.isBlank())throw new IllegalArgumentException("FORM_REQUIRED");
    return cache.get("field-label-candidates",formCode+"|"+l,()->{
      Map<String,Label> result=new LinkedHashMap<>();
      db.query("""
        SELECT field_code,title,review_status FROM fin_lib.lib_field_display_candidate
        WHERE form_code=? AND locale=?
        """,(RowCallbackHandler)rs->{
        if(result.putIfAbsent(rs.getString("field_code"),
          new Label(rs.getString("title"),rs.getString("review_status")))!=null)
          throw new IllegalStateException("DUPLICATE_FIELD_LABEL");
      },formCode,l);
      return Map.copyOf(result);
    });
  }
  public Map<String,Label> metrics(String language){
    String l=locale(language);
    return cache.get("metric-label-candidates",l,()->{
      Map<String,Label> result=new LinkedHashMap<>();
      db.query("""
        SELECT metric_code,title,review_status FROM fin_lib.lib_metric_display_candidate
        WHERE locale=?
        """,(RowCallbackHandler)rs->{
        if(result.putIfAbsent(rs.getString("metric_code"),
            new Label(rs.getString("title"),rs.getString("review_status")))!=null)
          throw new IllegalStateException("DUPLICATE_METRIC_LABEL");
      },l);
      if(result.size()!=840)throw new IllegalStateException("METRIC_DRAFT_LABELS_DRIFT");
      return Map.copyOf(result);
    });
  }
}
