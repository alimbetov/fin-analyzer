package kz.fin.formlib.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CALC-3B inventory governance, not financial authorization.
 *
 * Executes after @Order(7) Calc154FormulaContractBootstrap. The absence of
 * reviewed mandatory outputs is represented as SQL NULL, NOT an empty array.
 * Typed dependency edges are pinned to a real form version and source schema.
 * Cross-form model inputs are handled separately by Calc3BModelBootstrap.
 */
@Component
@Order(8)
public class Calc3BRequirementBootstrap implements ApplicationRunner {
  static final String VERSION = "CALC3B_REVIEW_V1";
  static final String BLOCKERS = "MANDATORY_OUTPUT_SET_NOT_AUTHORED;FIELD_REQUIREDNESS_UNREVIEWED;" +
      "PERIOD_AND_AGGREGATION_POLICY_UNREVIEWED;FINANCE_S2";
  private final JdbcTemplate sql;
  private final ObjectMapper mapper;

  public Calc3BRequirementBootstrap(JdbcTemplate sql, ObjectMapper mapper) {
    this.sql = Objects.requireNonNull(sql);
    this.mapper = Objects.requireNonNull(mapper);
  }
  static String sha(String input) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("CALC3B_SHA256_MISSING",e);
    }
  }
  private record FormVersion(String code, UUID id, String schema, String mode) {}
  private record Formula(UUID id, String form, UUID version, String schema,
                         String output, String sourceJson) {}
  private record Physical(String form, UUID version, String schema,
                          String metric, String field, String type) {}

  @Override
  @Transactional
  public void run(ApplicationArguments arguments) throws Exception {
    String database = sql.execute((ConnectionCallback<String>) c ->
        c.getMetaData().getDatabaseProductName());
    if (!"PostgreSQL".equals(database)) return;

    List<FormVersion> all = sql.query("""
        SELECT f.form_code,v.id,v.schema_sha256,v.content_mode
        FROM fin_lib.lib_form_definition f
        JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
        ORDER BY f.form_code
        """, (r,i) -> new FormVersion(r.getString(1),r.getObject(2,UUID.class),
              r.getString(3),r.getString(4)));
    if (all.size()!=166) throw new IllegalStateException("CALC3B_FORM_CATALOG_NOT_166");
    var numeric = all.stream().filter(f -> !"EVIDENCE".equals(f.mode())).toList();
    if (numeric.size()!=154 || numeric.stream().filter(f -> "SINGLE_RECORD".equals(f.mode())).count()!=3
        || numeric.stream().filter(f -> "ROWSET".equals(f.mode())).count()!=151)
      throw new IllegalStateException("CALC3B_FORM_MODE_DRIFT");
    var byForm = new HashMap<String,FormVersion>();
    for (FormVersion v:all) {
      if (byForm.putIfAbsent(v.code(),v)!=null ||
          !v.schema().matches("[0-9a-f]{64}"))
        throw new IllegalStateException("CALC3B_FORM_VERSION_INVALID:"+v.code());
    }
    seedRequirements(numeric);

    List<Formula> formulas = sql.query("""
        SELECT c.id,c.form_code,c.form_version_id,c.source_schema_sha256,
          c.output_metric_code,c.source_metrics_jsonb::text
        FROM fin_calc.formula_contract_revision c
        JOIN fin_lib.lib_form_definition f ON f.current_version_id=c.form_version_id
        WHERE c.formula_version IN ('F01_CANDIDATE_V1','F02_TEST_ONLY_V1',
          'F03_CANDIDATE_V1','CALC154_CANDIDATE_V1')
        ORDER BY c.output_metric_code
        """,(r,i)->new Formula(r.getObject(1,UUID.class),r.getString(2),
          r.getObject(3,UUID.class),r.getString(4),r.getString(5),r.getString(6)));
    if (formulas.size()!=10) throw new IllegalStateException("CALC3B_EXPECT_10_FORMULAS");
    var byOutput = new HashMap<String,Formula>();
    for (Formula f:formulas) {
      var v=byForm.get(f.form());
      if(v==null || !v.id().equals(f.version()) || !v.schema().equals(f.schema())
          || byOutput.putIfAbsent(f.output(),f)!=null)
        throw new IllegalStateException("CALC3B_FORMULA_VERSION_DRIFT:"+f.output());
    }

    List<Physical> physical = sql.query("""
        SELECT m.form_code, v.id, v.schema_sha256, m.metric_code,
          m.source_field_code, ff.field_type
        FROM fin_lib.lib_metric_definition m
        JOIN fin_lib.lib_form_definition f ON f.form_code=m.form_code
        JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
        JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
          AND ff.field_code=m.source_field_code
        JOIN fin_lib.lib_field_binding_contract b ON b.form_code=m.form_code
          AND b.field_code=m.source_field_code
          AND b.metric_code=m.metric_code
          AND b.source_schema_sha256=v.schema_sha256
          AND b.review_state='CANDIDATE_UNQUALIFIED'
        WHERE m.metric_kind='SOURCE_FIELD'
        """,(r,i)->new Physical(r.getString(1),r.getObject(2,UUID.class),
           r.getString(3),r.getString(4),r.getString(5),r.getString(6)));
    var byMetric=new HashMap<String,Physical>();
    for(Physical p:physical)
      if(byMetric.putIfAbsent(p.metric(),p)!=null)
        throw new IllegalStateException("CALC3B_DUPLICATE_PHYSICAL_METRIC:"+p.metric());
    if(physical.size()!=793)throw new IllegalStateException("CALC3B_EXPECT_793_PHYSICAL_FIELDS");

    seedTypedInputs(formulas,byOutput,byMetric);
  }

  private void seedRequirements(List<FormVersion> numeric){
    String insert="""
        INSERT INTO fin_calc.form_calculation_requirement_revision(
          id,form_code,form_version_id,source_schema_sha256,requirement_version,
          mandatory_output_state,mandatory_outputs_jsonb,obligation_blockers,
          approval_state,source_definition_sha256,binding_sha256)
        VALUES(?,?,?,?,'CALC3B_REVIEW_V1','REVIEW_REQUIRED',NULL,?,
          'NOT_S2_APPROVED',?,?)
        """;
    // Seed by exact form-version key. Historical requirement revisions and
    // independently authored review drafts are not overwritten or counted.
    List<Object[]> rows=new ArrayList<>();
    for(var v:numeric){
      Integer exists=sql.queryForObject("""
        SELECT count(*) FROM fin_calc.form_calculation_requirement_revision
        WHERE form_version_id=? AND requirement_version=?
        """,Integer.class,v.id(),VERSION);
      if(exists==null||exists>1)
        throw new IllegalStateException("CALC3B_DUPLICATE_REQUIREMENT:"+v.code());
      if(exists==0){
        String source=sha("CALC3B-REQUIREMENT-V1|"+v.code()+"|REVIEW_REQUIRED|"+BLOCKERS);
        String bind=sha("CALC3B-REQUIREMENT-BIND-V1|"+v.id()+"|"+v.schema()+"|"+source);
        rows.add(new Object[]{UUID.randomUUID(),v.code(),v.id(),v.schema(),BLOCKERS,source,bind});
      }
    }
    if(!rows.isEmpty())sql.batchUpdate(insert,rows);

    var saved=sql.query("""
        SELECT r.form_code,r.form_version_id,r.source_schema_sha256,
          r.requirement_version,r.mandatory_output_state,r.mandatory_outputs_jsonb IS NULL,
          r.obligation_blockers,r.approval_state,r.source_definition_sha256,r.binding_sha256
        FROM fin_calc.form_calculation_requirement_revision r
        JOIN fin_lib.lib_form_definition f ON f.form_code=r.form_code
          AND f.current_version_id=r.form_version_id
        WHERE r.requirement_version='CALC3B_REVIEW_V1'
        """,(r,i)->new String[]{r.getString(1),r.getObject(2,UUID.class).toString(),
          r.getString(3),r.getString(4),r.getString(5),String.valueOf(r.getBoolean(6)),
          r.getString(7),r.getString(8),r.getString(9),r.getString(10)});
    if(saved.size()!=154)throw new IllegalStateException("CALC3B_FORM_REQUIREMENT_COUNT_DRIFT");
    Map<String,String[]> indexed=new HashMap<>();
    for(var entry:saved)
      if(indexed.putIfAbsent(entry[0],entry)!=null)
        throw new IllegalStateException("CALC3B_DUPLICATE_FORM_REQUIREMENT");
    for(var v:numeric){
      String source=sha("CALC3B-REQUIREMENT-V1|"+v.code()+"|REVIEW_REQUIRED|"+BLOCKERS);
      String bind=sha("CALC3B-REQUIREMENT-BIND-V1|"+v.id()+"|"+v.schema()+"|"+source);
      String[] a=indexed.get(v.code());
      if(a==null || !Arrays.equals(a,new String[]{
          v.code(),v.id().toString(),v.schema(),VERSION,"REVIEW_REQUIRED","true",
          BLOCKERS,"NOT_S2_APPROVED",source,bind
      }))throw new IllegalStateException("CALC3B_FORM_REQUIREMENT_DRIFT:"+v.code());
    }
  }

  private void seedTypedInputs(List<Formula> formulas,Map<String,Formula> byOutput,
      Map<String,Physical> byMetric) throws Exception{
    Integer existing=sql.queryForObject("""
        SELECT count(*) FROM fin_calc.formula_typed_input_binding i
        JOIN fin_calc.formula_contract_revision c ON c.id=i.formula_revision_id
        JOIN fin_lib.lib_form_definition f ON f.current_version_id=c.form_version_id
        WHERE c.formula_version IN ('F01_CANDIDATE_V1','F02_TEST_ONLY_V1',
          'F03_CANDIDATE_V1','CALC154_CANDIDATE_V1')
        """,Integer.class);
    if(existing==null || existing!=0 && existing!=21)
      throw new IllegalStateException("CALC3B_FORMULA_INPUT_PARTIAL");
    List<Object[]> inserts=new ArrayList<>();
    for(var parent:formulas) {
      String[] inputs=mapper.readValue(parent.sourceJson(),String[].class);
      if(inputs.length<1||inputs.length>16)throw new IllegalStateException("CALC3B_FORMULA_INPUT_LENGTH");
      for(int i=0;i<inputs.length;i++){
        String metric=inputs[i];
        String kind,field=null,type=null;
        UUID linked=null;
        var derived=byOutput.get(metric);
        if(derived!=null){
          if(!derived.form().equals(parent.form()) || !derived.version().equals(parent.version()))
            throw new IllegalStateException("CALC3B_CROSS_FORMULA_UNREVIEWED:"+metric);
          kind="FORMULA_CANDIDATE";
          linked=derived.id();
        }else{
          var p=byMetric.get(metric);
          if(p==null||!p.form().equals(parent.form())||!p.version().equals(parent.version())
              ||!p.schema().equals(parent.schema())||
              !List.of("MONEY","DECIMAL","INTEGER").contains(p.type()))
            throw new IllegalStateException("CALC3B_SOURCE_NOT_TYPED:"+metric);
          kind="PHYSICAL_NUMERIC_FIELD";
          field=p.field();type=p.type();
        }
        String binding=sha("CALC3B-TYPED-INPUT-V1|"+parent.id()+"|"+i+"|"+metric+"|"+kind+
            "|"+parent.version()+"|"+parent.schema()+"|"+
            (linked==null?field+"|"+type:linked.toString()));
        inserts.add(new Object[]{parent.id(),i,metric,kind,parent.version(),parent.schema(),
            field,type,linked,binding});
      }
    }
    if(inserts.size()!=21) throw new IllegalStateException("CALC3B_TYPED_INPUT_COUNT_DRIFT");

    if(existing==0){
      sql.batchUpdate("""
          INSERT INTO fin_calc.formula_typed_input_binding(
            formula_revision_id,ordinal,source_metric_code,source_kind,
            source_form_version_id,source_schema_sha256,source_field_code,field_type,
            source_formula_revision_id,period_policy,requiredness_state,binding_sha256)
          SELECT ?,?,?,?,?,?,?,?,?,c.period_semantics,'REVIEW_REQUIRED',?
          FROM fin_calc.formula_contract_revision c WHERE c.id=?
          """, inserts.stream().map(a->new Object[]{a[0],a[1],a[2],a[3],a[4],a[5],
          a[6],a[7],a[8],a[9],a[0]}).toList());
    }
    var actual=sql.query("""
        SELECT i.formula_revision_id,i.ordinal,i.source_metric_code,i.source_kind,
          i.source_form_version_id,i.source_schema_sha256,i.source_field_code,i.field_type,
          i.source_formula_revision_id,i.binding_sha256,i.requiredness_state
        FROM fin_calc.formula_typed_input_binding i
        JOIN fin_calc.formula_contract_revision c ON c.id=i.formula_revision_id
        JOIN fin_lib.lib_form_definition f ON f.current_version_id=c.form_version_id
        WHERE c.formula_version IN ('F01_CANDIDATE_V1','F02_TEST_ONLY_V1',
          'F03_CANDIDATE_V1','CALC154_CANDIDATE_V1')
        """,(r,i)->new Object[]{r.getObject(1,UUID.class),r.getInt(2),r.getString(3),
          r.getString(4),r.getObject(5,UUID.class),r.getString(6),r.getString(7),
          r.getString(8),r.getObject(9,UUID.class),r.getString(10),r.getString(11)});
    if(actual.size()!=21)throw new IllegalStateException("CALC3B_TYPED_INPUT_COUNT");
    Map<String,Object[]> index=new HashMap<>();
    for(var a:actual){
      String key=a[0]+"|"+a[1];
      if(index.putIfAbsent(key,a)!=null)throw new IllegalStateException("CALC3B_DUPLICATE_TYPED_INPUT");
    }
    for(var expected:inserts){
      Object[] got=index.get(expected[0]+"|"+expected[1]);
      if(got==null || !Objects.equals(got[2],expected[2]) || !Objects.equals(got[3],expected[3])
          || !Objects.equals(got[4],expected[4]) || !Objects.equals(got[5],expected[5])
          || !Objects.equals(got[6],expected[6]) || !Objects.equals(got[7],expected[7])
          || !Objects.equals(got[8],expected[8]) || !Objects.equals(got[9],expected[9])
          || !"REVIEW_REQUIRED".equals(got[10]))
        throw new IllegalStateException("CALC3B_TYPED_INPUT_DRIFT:"+expected[2]);
    }
  }
}
