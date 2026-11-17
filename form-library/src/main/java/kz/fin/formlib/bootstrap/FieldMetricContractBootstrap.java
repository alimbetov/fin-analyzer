package kz.fin.formlib.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.io.ClassPathResource;

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
 * FIN R16.A: audit and persist explicit draft bindings for each ACTIVE physical
 * field; do not silently turn source catalog hints into qualified finance facts.
 *
 * 793 physical numeric/operational source fields + 84 evidence metadata fields.
 * F02.netProfit is an unbound FORM_DERIVED_CANDIDATE, never an editable source.
 */
@Component
@Order(5)
public class FieldMetricContractBootstrap implements ApplicationRunner {
  private static final String SEED="FIN_FIELD_METRIC_R16A_V1";
  private static final Set<String> TYPES=Set.of(
    "REF","CODE","DATE","MONEY","DECIMAL","PERIOD","ENUM","INTEGER","TEXT","BOOLEAN");
  private final JdbcTemplate db;
  public FieldMetricContractBootstrap(JdbcTemplate db){this.db=db;}

  private record Field(String form,String code,String schemaSha,String type,
      String mode,boolean required,String capture) {}
  private record Metric(String code,String form,String sourceField,String kind,
      String unitPolicy) {}
  private record Entry(String form,String code,String schemaSha,String type,
      String mode,boolean required,String unitPolicy,String role,
      String metricCode,String sha) {}
  private static String digest(String text){
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
       .digest(text.getBytes(StandardCharsets.UTF_8)));}
    catch(Exception e){throw new IllegalStateException("SHA256_UNAVAILABLE",e);}
  }
  private long count(String table){
    return Objects.requireNonNull(db.queryForObject(
       "SELECT COUNT(*) FROM fin_lib."+table,Long.class));
  }
  private static Map<String,String> declaredTypes(){
    var map=new HashMap<String,String>();
    try(var in=new BufferedReader(new InputStreamReader(
        new ClassPathResource("catalog/form-field-presentation-review-r6.tsv")
          .getInputStream(),StandardCharsets.UTF_8))){
      if(in.readLine()==null)throw new IllegalStateException("FIELD_CATALOG_EMPTY");
      String line;
      while((line=in.readLine())!=null){
        if(line.isBlank())continue;
        var col=line.split("\t",-1);
        if(col.length!=10)throw new IllegalStateException("FIELD_CATALOG_BAD_COLUMN_COUNT");
        if(map.putIfAbsent(col[0]+"."+col[1],col[2])!=null)
          throw new IllegalStateException("DUPLICATE_FIELD_CATALOG_ENTRY");
      }
    }catch(java.io.IOException e){throw new IllegalStateException("FIELD_CATALOG_UNREADABLE",e);}
    if(map.size()!=794)throw new IllegalStateException("FIELD_CATALOG_DRIFT");
    return Map.copyOf(map);
  }
  @Transactional
  @Override public void run(ApplicationArguments ignored){
    List<Field> fields=db.query("""
      SELECT f.form_code, v.schema_sha256, f.legacy_capture_mode,
             ff.field_code, ff.field_type, ff.input_mode, ff.required
      FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
      ORDER BY f.form_code,ff.field_code
      """,(rs,i)->new Field(
      rs.getString("form_code"),rs.getString("field_code"),
      rs.getString("schema_sha256"),rs.getString("field_type"),
      rs.getString("input_mode"),rs.getBoolean("required"),
      rs.getString("legacy_capture_mode")));
    if(fields.size()!=877)throw new IllegalStateException("FIELD_COUNT_DRIFT");

    Map<String,Metric> metrics=new TreeMap<>();
    db.query("""
       SELECT metric_code,form_code,source_field_code,metric_kind,unit_policy
       FROM fin_lib.lib_metric_definition
       """,rs->{
      Metric m=new Metric(rs.getString("metric_code"),rs.getString("form_code"),
        rs.getString("source_field_code"),rs.getString("metric_kind"),
        rs.getString("unit_policy"));
      if(metrics.putIfAbsent(m.code(),m)!=null)
        throw new IllegalStateException("DUPLICATE_METRIC");
    });
    if(metrics.size()!=840)throw new IllegalStateException("METRIC_COUNT_DRIFT");
    var types=declaredTypes();
    Map<String,Entry> entries=new TreeMap<>();
    Set<String> bound=new HashSet<>();
    int sources=0,evidence=0;
    for(var f:fields){
      if(!TYPES.contains(f.type())||!"MANUAL".equals(f.mode())
          ||f.schemaSha()==null||f.schemaSha().length()!=64)
        throw new IllegalStateException("INVALID_FIELD_CONTRACT:"+f.form()+"."+f.code());
      String id=f.form()+"."+f.code();
      boolean metadata="EVIDENCE_ONLY".equals(f.capture());
      String role=metadata?"EVIDENCE_METADATA":"SOURCE_FIELD";
      Metric m=metrics.get(id);
      if(metadata){
        if(m!=null||types.containsKey(id))
          throw new IllegalStateException("EVIDENCE_METRIC_NOT_ALLOWED:"+id);
        evidence++;
      }else{
        if(m==null||!"SOURCE_FIELD".equals(m.kind())
            ||!f.form().equals(m.form())||!f.code().equals(m.sourceField())
            ||!f.type().equals(types.get(id)))
          throw new IllegalStateException("FIELD_METRIC_CONTRACT_MISMATCH:"+id);
        if(!bound.add(id))throw new IllegalStateException("DUPLICATE_BINDING:"+id);
        sources++;
      }
      String unit=m==null?"NOT_A_FINANCIAL_METRIC":m.unitPolicy();
      String contractSha=digest(String.join("|","FIN-R16-FIELD-BINDING-V1",
        f.form(),f.code(),f.schemaSha(),f.type(),f.mode(),
        String.valueOf(f.required()),role,unit));
      if(entries.putIfAbsent(id,new Entry(f.form(),f.code(),f.schemaSha(),
        f.type(),f.mode(),f.required(),unit,role,metadata?null:id,contractSha))!=null)
        throw new IllegalStateException("DUPLICATE_PHYSICAL_FIELD:"+id);
    }
    if(sources!=793||evidence!=84||entries.size()!=877)
      throw new IllegalStateException("PHYSICAL_FIELD_COVERAGE_INVALID");
    var orphan=new TreeSet<>(types.keySet());
    orphan.removeAll(bound);
    if(!orphan.equals(Set.of("F02.netProfit")))
      throw new IllegalStateException("ORPHAN_SOURCE_METRICS:"+orphan);
    var extra=metrics.get("F02.netProfit");
    if(extra==null||!"SOURCE_FIELD".equals(extra.kind())
        ||!"F02".equals(extra.form())
        ||!"netProfit".equals(extra.sourceField()))
      throw new IllegalStateException("F02_DERIVED_CANDIDATE_CONTRACT_INVALID");

    String fingerprint=digest("FIN-R16-BINDINGS-V1\n"+
      entries.values().stream().map(Entry::sha).collect(Collectors.joining("\n"))+
      "\nF02.netProfit|FORM_DERIVED_CANDIDATE|netRevenue-cogs-operatingExpenses");
    long existing=count("lib_field_binding_contract"),
         exceptional=count("lib_metric_binding_exception"),
         manifest=count("lib_field_contract_seed_manifest");
    if(existing>0||exceptional>0||manifest>0){
      if(existing!=877||exceptional!=1||manifest!=1)
        throw new IllegalStateException("FIELD_CONTRACT_SEED_PARTIAL");
      var old=db.queryForObject("""
        SELECT source_sha256 FROM fin_lib.lib_field_contract_seed_manifest
        WHERE seed_code=?
        """,String.class,SEED);
      if(!fingerprint.equals(old))
        throw new IllegalStateException("FIELD_CONTRACT_SEED_DRIFT");
      return;
    }
    var data=List.copyOf(entries.values());
    db.batchUpdate("""
      INSERT INTO fin_lib.lib_field_binding_contract
      (form_code,field_code,source_schema_sha256,field_type,input_mode,
       required,unit_policy,semantic_role,metric_code,contract_sha256,review_state)
      VALUES(?,?,?,?,?,?,?,?,?,?,'CANDIDATE_UNQUALIFIED')
      """,new BatchPreparedStatementSetter(){
      @Override public int getBatchSize(){return data.size();}
      @Override public void setValues(PreparedStatement p,int i)throws SQLException{
        var e=data.get(i);
        p.setString(1,e.form());p.setString(2,e.code());
        p.setString(3,e.schemaSha());p.setString(4,e.type());
        p.setString(5,e.mode());p.setBoolean(6,e.required());
        p.setString(7,e.unitPolicy());p.setString(8,e.role());
        p.setString(9,e.metricCode());p.setString(10,e.sha());
      }
    });
    db.update("""
      INSERT INTO fin_lib.lib_metric_binding_exception
      (metric_code,semantic_role,formula_expression,review_state)
      VALUES('F02.netProfit','FORM_DERIVED_CANDIDATE',
      'netRevenue - cogs - operatingExpenses','CANDIDATE_UNQUALIFIED')
      """);
    db.update("""
      INSERT INTO fin_lib.lib_field_contract_seed_manifest
        (seed_code,source_sha256,seeded_at) VALUES(?,?,?)
      """,SEED,fingerprint,Timestamp.from(Instant.now()));
    if(count("lib_field_binding_contract")!=877
        ||count("lib_metric_binding_exception")!=1)
      throw new IllegalStateException("FIELD_CONTRACT_POSTCONDITION_FAILED");
  }
}
