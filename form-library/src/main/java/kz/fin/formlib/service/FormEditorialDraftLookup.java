package kz.fin.formlib.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import java.util.*;

/** Read-only 166 x four-language candidate display metadata, not a schema edition. */
@Service
public class FormEditorialDraftLookup {
  public record Candidate(String formCode,String locale,String title,String purpose,
      String description,String reviewStatus,String sourceContractSha256) {}
  private final JdbcTemplate jdbc;
  private final CatalogReadCache cache;
  public FormEditorialDraftLookup(JdbcTemplate jdbc,CatalogReadCache cache){
    this.jdbc=jdbc;this.cache=cache;
  }
  public Map<String,Candidate> byLocale(String locale){
    if(!Set.of("ru","kk","en","zh-Hans").contains(locale))
      throw new IllegalArgumentException("UNSUPPORTED_LOCALE");
    return cache.get("editorial-draft-forms",locale,()->{
      Map<String,Candidate> result=new HashMap<>();
      jdbc.query("""
        SELECT form_code,locale,title,purpose,description,
               review_status,source_contract_sha256
        FROM fin_lib.lib_form_editorial_draft
        WHERE locale=?
        """,(RowCallbackHandler)rs->{
        var c=new Candidate(rs.getString("form_code"),rs.getString("locale"),
          rs.getString("title"),rs.getString("purpose"),
          rs.getString("description"),rs.getString("review_status"),
          rs.getString("source_contract_sha256"));
        if(result.putIfAbsent(c.formCode(),c)!=null)
          throw new IllegalStateException("DUPLICATE_EDITORIAL_FORM");
      },locale);
      if(result.size()!=166)throw new IllegalStateException("EDITORIAL_COVERAGE_INCOMPLETE");
      return Map.copyOf(result);
    });
  }
}
