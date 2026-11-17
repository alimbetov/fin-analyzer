package kz.fin.formlib.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FIN R16.B: prefill candidate UI captions for every physical field and metric
 * (877*4 + 840*4). They are AUTOMATIC EDITORIAL DRAFTS, not approved translations.
 * Existing immutable versioned human translations always take precedence.
 */
@Component
@Order(6)
public class FieldMetricDisplayDraftBootstrap implements ApplicationRunner {
  private static final String SEED="FIN_FIELD_DISPLAY_R16B_V1";
  private static final List<String> LOCALES=List.of("ru","kk","en","zh-Hans");
  private static final String PROVISIONAL="AUTO_DRAFT_REVIEW_REQUIRED";
  private final JdbcTemplate db;
  public FieldMetricDisplayDraftBootstrap(JdbcTemplate db){this.db=db;}

  private record Phrase(String text,String status) {}
  private record Field(String form,String code,String contractSha) {}
  private record FieldLabel(String form,String code,String locale,String title,
      String status,String sourceSha) {}
  private record MetricLabel(String metric,String locale,String title,String status) {}

  private static String sha(String text){
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8)));}
    catch(Exception e){throw new IllegalStateException("SHA256_UNAVAILABLE",e);}
  }
  private static void valid(String text,String entity){
    if(text==null||text.isBlank()||text.length()>255)
      throw new IllegalStateException("FIELD_LABEL_INVALID:"+entity);
  }
  private long number(String table){
    return Objects.requireNonNull(db.queryForObject(
      "SELECT COUNT(*) FROM fin_lib."+table,Long.class));
  }

  private static Map<String,String[]> glossary(){
    Map<String,String[]> result=new TreeMap<>();
    try(var in=new BufferedReader(new InputStreamReader(
        new ClassPathResource("catalog/field-label-glossary-r16.tsv")
          .getInputStream(),StandardCharsets.UTF_8))){
      if(in.readLine()==null)throw new IllegalStateException("GLOSSARY_EMPTY");
      String line;
      while((line=in.readLine())!=null){
        if(line.isBlank())continue;
        String[] fields=line.split("\t",-1);
        if(fields.length!=4||fields[0].isBlank()
            ||fields[1].isBlank()||fields[2].isBlank()||fields[3].isBlank())
          throw new IllegalStateException("BAD_GLOSSARY_ROW");
        if(result.putIfAbsent(fields[0],new String[]{fields[1],fields[2],fields[3]})!=null)
          throw new IllegalStateException("DUPLICATE_GLOSSARY_TOKEN:"+fields[0]);
      }
    }catch(java.io.IOException ex){throw new IllegalStateException("GLOSSARY_MISSING",ex);}
    if(result.size()<400)throw new IllegalStateException("GLOSSARY_UNDERSIZED");
    return Map.copyOf(result);
  }
  private static List<String> tokenize(String code){
    if(code==null||code.isBlank())throw new IllegalStateException("EMPTY_FIELD_CODE");
    String normalized=code
      .replaceAll("([a-z0-9])([A-Z])","$1 $2")
      .replaceAll("([A-Z])([A-Z][a-z])","$1 $2")
      .replaceAll("[^A-Za-z0-9]+"," ");
    var out=Arrays.stream(normalized.split(" "))
      .filter(x->!x.isBlank()).map(x->x.toLowerCase(Locale.ROOT)).toList();
    if(out.isEmpty())throw new IllegalStateException("EMPTY_FIELD_TOKENS:"+code);
    return out;
  }
  private static Phrase phrase(String code,String locale,Map<String,String[]> glossary){
    List<String> tokens=tokenize(code);
    if("en".equals(locale)){
      var text=String.join(" ",tokens);
      valid(text,code);
      return new Phrase(Character.toUpperCase(text.charAt(0))+text.substring(1),PROVISIONAL);
    }
    int index=LOCALES.indexOf(locale);
    if(index<0||index==2)throw new IllegalStateException("INVALID_FIELD_LOCALE");
    int column=index>2?2:index;
    boolean incomplete=false;
    var parts=new ArrayList<String>();
    for(var token:tokens){
      var translations=glossary.get(token);
      if(translations==null){
        incomplete=true;parts.add(token);
      }else parts.add(translations[column]);
    }
    String text=String.join("zh-Hans".equals(locale)?"":" ",parts);
    valid(text,code);
    return new Phrase(text,incomplete?"PARTIAL_DRAFT":PROVISIONAL);
  }
  @Transactional
  @Override public void run(ApplicationArguments ignored){
    var glossary=glossary();
    var fields=db.query("""
      SELECT form_code,field_code,contract_sha256
      FROM fin_lib.lib_field_binding_contract
      ORDER BY form_code,field_code
      """,(rs,i)->new Field(rs.getString("form_code"),
      rs.getString("field_code"),rs.getString("contract_sha256")));
    if(fields.size()!=877||number("lib_metric_definition")!=840)
      throw new IllegalStateException("DISPLAY_SOURCE_COUNT_DRIFT");
    var metricCodes=db.queryForList("""
      SELECT metric_code,source_field_code FROM fin_lib.lib_metric_definition
      ORDER BY metric_code
      """,(rs,i)->new String[]{rs.getString("metric_code"),
         rs.getString("source_field_code")});
    var fl=new ArrayList<FieldLabel>(3508);
    for(var field:fields)for(var locale:LOCALES){
      var label=phrase(field.code(),locale,glossary);
      fl.add(new FieldLabel(field.form(),field.code(),locale,
        label.text(),label.status(),field.contractSha()));
    }
    var ml=new ArrayList<MetricLabel>(3360);
    for(var m:metricCodes)for(var locale:LOCALES){
      String key=m[1]!=null?m[1]:m[0];
      var label=phrase(key,locale,glossary);
      ml.add(new MetricLabel(m[0],locale,label.text(),label.status()));
    }
    if(fl.size()!=3508||ml.size()!=3360)
      throw new IllegalStateException("DISPLAY_DRAFT_COVERAGE_DRIFT");
    String fingerprint=sha("FIN-R16B-DISPLAY-V1\n"+
      fl.stream().map(x->String.join("|",x.form(),x.code(),x.locale(),
        x.title(),x.status(),x.sourceSha())).collect(Collectors.joining("\n"))+
      "\n--METRIC--\n"+
      ml.stream().map(x->String.join("|",x.metric(),x.locale(),x.title(),
        x.status())).collect(Collectors.joining("\n")));
    long fcount=number("lib_field_display_candidate"),
      mcount=number("lib_metric_display_candidate"),
      manifest=number("lib_field_display_seed_manifest");
    if(fcount>0||mcount>0||manifest>0){
      if(fcount!=3508||mcount!=3360||manifest!=1)
        throw new IllegalStateException("DISPLAY_SEED_PARTIAL");
      String existing=db.queryForObject("""
        SELECT source_sha256 FROM fin_lib.lib_field_display_seed_manifest
        WHERE seed_code=?
        """,String.class,SEED);
      if(!fingerprint.equals(existing))
        throw new IllegalStateException("DISPLAY_SEED_DIFFERENT_SOURCE");
      return;
    }
    db.batchUpdate("""
      INSERT INTO fin_lib.lib_field_display_candidate
      (form_code,field_code,locale,title,review_status,source_contract_sha256)
      VALUES(?,?,?,?,?,?)
      """,new BatchPreparedStatementSetter(){
      @Override public int getBatchSize(){return fl.size();}
      @Override public void setValues(PreparedStatement p,int i)throws SQLException{
        var x=fl.get(i);
        p.setString(1,x.form());p.setString(2,x.code());p.setString(3,x.locale());
        p.setString(4,x.title());p.setString(5,x.status());p.setString(6,x.sourceSha());
      }
    });
    db.batchUpdate("""
      INSERT INTO fin_lib.lib_metric_display_candidate
      (metric_code,locale,title,review_status) VALUES(?,?,?,?)
      """,new BatchPreparedStatementSetter(){
      @Override public int getBatchSize(){return ml.size();}
      @Override public void setValues(PreparedStatement p,int i)throws SQLException{
        var x=ml.get(i);
        p.setString(1,x.metric());p.setString(2,x.locale());
        p.setString(3,x.title());p.setString(4,x.status());
      }
    });
    db.update("""
      INSERT INTO fin_lib.lib_field_display_seed_manifest
      (seed_code,source_sha256,seeded_at) VALUES(?,?,?)
      """,SEED,fingerprint,Timestamp.from(Instant.now()));
    if(number("lib_field_display_candidate")!=3508
        ||number("lib_metric_display_candidate")!=3360)
      throw new IllegalStateException("DISPLAY_SEED_POSTCONDITION");
  }
}
