package kz.fin.formlib.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CALC-3C.1: append-only, version-pinned SOURCE capability; not calculation
 * approval. For current form versions, count actual MONEY/DECIMAL/INTEGER
 * fields, never infer numeric eligibility from ROWSET/SINGLE_RECORD.
 * Prior v1 model links and their inaccurate boolean remain immutable.
 */
@Component
@Order(10)
public class Calc3C1NumericSourceBootstrap implements ApplicationRunner {
  static final String CAPABILITY_VERSION="CALC3C1_NUMERIC_SOURCE_V1";
  private final JdbcTemplate jdbc;

  public Calc3C1NumericSourceBootstrap(JdbcTemplate jdbc){
    this.jdbc=Objects.requireNonNull(jdbc);
  }

  private record Candidate(String code,UUID version,String schema,String mode,
      int fieldCount,int numericCount) {}
  private record Stored(String form,UUID version,String schema,String mode,
      int fieldCount,int numericCount,boolean numeric,boolean reference,
      boolean evidence,String definitionSha,String bindingSha) {}

  @Override
  @Transactional
  public void run(ApplicationArguments args) throws Exception {
    String database=jdbc.execute((ConnectionCallback<String>) c->
        c.getMetaData().getDatabaseProductName());
    if(!"PostgreSQL".equals(database))return;

    var forms=jdbc.query("""
        SELECT f.form_code,v.id,v.schema_sha256,v.content_mode,
          count(ff.field_code)::integer AS field_count,
          count(ff.field_code) FILTER(
            WHERE ff.field_type IN ('MONEY','DECIMAL','INTEGER'))::integer
            AS numeric_count
        FROM fin_lib.lib_form_definition f
        JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
        LEFT JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
        GROUP BY f.form_code,v.id,v.schema_sha256,v.content_mode
        ORDER BY f.form_code
        """,(r,i)->new Candidate(r.getString(1),r.getObject(2,UUID.class),
          r.getString(3),r.getString(4),r.getInt(5),r.getInt(6)));
    if(forms.size()!=166)throw new IllegalStateException("CALC3C1_CATALOG_COUNT_NOT_166");
    int direct=(int)forms.stream().filter(f->f.numericCount()>0).count();
    int reference=(int)forms.stream().filter(f->f.numericCount()==0
        &&!"EVIDENCE".equals(f.mode())).count();
    int evidence=(int)forms.stream().filter(f->"EVIDENCE".equals(f.mode())).count();
    if(direct!=145||reference!=9||evidence!=12)
      throw new IllegalStateException("CALC3C1_SOURCE_CAPABILITY_BASELINE_DRIFT:"+
        direct+"/"+reference+"/"+evidence);

    List<Object[]> missing=new ArrayList<>();
    for(var f:forms){
      if(f.schema()==null||!f.schema().matches("[0-9a-f]{64}")
          ||f.numericCount()>f.fieldCount())
        throw new IllegalStateException("CALC3C1_FORM_SOURCE_CONTRACT_INVALID:"+f.code());
      Integer existing=jdbc.queryForObject("""
          SELECT count(*) FROM fin_calc.form_numeric_source_capability_revision
          WHERE form_version_id=? AND capability_version=?
          """,Integer.class,f.version(),CAPABILITY_VERSION);
      if(existing==null||existing>1)throw new IllegalStateException(
          "CALC3C1_DUPLICATE_CAPABILITY:"+f.code());
      if(existing==0){
        String definition=definition(f);
        String binding=binding(f,definition);
        missing.add(new Object[]{UUID.randomUUID(),f.code(),f.version(),f.schema(),
          CAPABILITY_VERSION,f.mode(),f.fieldCount(),f.numericCount(),
          f.numericCount()>0,f.numericCount()==0&&!"EVIDENCE".equals(f.mode()),
          "EVIDENCE".equals(f.mode()),definition,binding});
      }
    }
    if(!missing.isEmpty()){
      jdbc.batchUpdate("""
          INSERT INTO fin_calc.form_numeric_source_capability_revision(
            id,form_code,form_version_id,source_schema_sha256,capability_version,
            content_mode,source_field_count,direct_numeric_field_count,
            has_direct_numeric_source,reference_only,evidence_only,
            definition_sha256,binding_sha256)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,missing);
    }
    var persisted=jdbc.query("""
        SELECT c.form_code,c.form_version_id,c.source_schema_sha256,c.content_mode,
          c.source_field_count,c.direct_numeric_field_count,
          c.has_direct_numeric_source,c.reference_only,c.evidence_only,
          c.definition_sha256,c.binding_sha256
        FROM fin_calc.form_numeric_source_capability_revision c
        JOIN fin_lib.lib_form_definition f ON f.form_code=c.form_code
        WHERE c.form_version_id=f.current_version_id
          AND c.capability_version=?
          AND c.approval_state='NOT_S2_APPROVED'
        """,(r,i)->new Stored(r.getString(1),r.getObject(2,UUID.class),
          r.getString(3),r.getString(4),r.getInt(5),r.getInt(6),
          r.getBoolean(7),r.getBoolean(8),r.getBoolean(9),r.getString(10),
          r.getString(11)),CAPABILITY_VERSION);
    if(persisted.size()!=166)
      throw new IllegalStateException("CALC3C1_CURRENT_CAPABILITY_COVERAGE");
    Map<String,Stored> indexed=new HashMap<>();
    for(var p:persisted)
      if(indexed.putIfAbsent(p.form(),p)!=null)
        throw new IllegalStateException("CALC3C1_DUPLICATE_FORM_CAPABILITY");

    for(var f:forms){
      String sha=definition(f);
      Stored p=indexed.get(f.code());
      if(p==null||!p.version().equals(f.version())
          ||!p.schema().equals(f.schema())||!p.mode().equals(f.mode())
          ||p.fieldCount()!=f.fieldCount()||p.numericCount()!=f.numericCount()
          ||p.numeric()!=(f.numericCount()>0)
          ||p.reference()!=(f.numericCount()==0&&!"EVIDENCE".equals(f.mode()))
          ||p.evidence()!=("EVIDENCE".equals(f.mode()))
          ||!p.definitionSha().equals(sha)
          ||!p.bindingSha().equals(binding(f,sha)))
        throw new IllegalStateException("CALC3C1_CAPABILITY_VERSION_DRIFT:"+f.code());
    }
  }

  private static String definition(Candidate f){
    return Calc3BRequirementBootstrap.sha("CALC3C1-NUMERIC-SOURCE-V1|"+
      f.code()+"|"+f.mode()+"|"+f.fieldCount()+"|"+f.numericCount());
  }

  private static String binding(Candidate f,String definition){
    return Calc3BRequirementBootstrap.sha("CALC3C1-NUMERIC-SOURCE-BIND-V1|"+
      f.version()+"|"+f.schema()+"|"+definition);
  }
}
