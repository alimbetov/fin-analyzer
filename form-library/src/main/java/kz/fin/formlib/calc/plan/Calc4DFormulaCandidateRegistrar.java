package kz.fin.formlib.calc.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-4D.3: internal append-only candidate authoring for ANY of the 154 data
 * forms. This is methodology registration, NEVER an inferred business rule
 * or signed finance S2 approval. Not an HTTP endpoint or job admission path.
 */
@Service
public class Calc4DFormulaCandidateRegistrar {
  public record InputSource(String metricCode,UUID exactFormVersionId,
      UUID exactFormulaRevisionId) {}
  public record Definition(String targetFormCode,UUID exactTargetFormVersionId,
      String outputMetricCode,String formulaVersion,String operation,
      String periodSemantics,List<InputSource> inputs,String authoredEvidence) {
    public Definition{inputs=inputs==null?List.of():List.copyOf(inputs);}
  }
  public record Receipt(UUID revisionId,String definitionSha256,
      String bindingSha256,boolean replayed,boolean financiallyApproved) {}
  private record Form(String code,UUID version,String schema,String mode) {}
  private record Resolved(String metric,String kind,UUID formVersion,
      String schema,String field,String type,UUID derivedId,String period,
      String bindingSha) {}
  private record Source(String form,String metric,UUID version,String schema,
      String field,String type) {}
  private record Derived(String metric,UUID version,String schema,String period) {}
  private record Stored(UUID id,String definitionSha,String bindingSha,
      String review,String sourceJson) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  public Calc4DFormulaCandidateRegistrar(JdbcTemplate jdbc,ObjectMapper json){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.json=Objects.requireNonNull(json);
  }

  @Transactional
  public Receipt register(Definition requested) throws Exception {
    if(requested==null||requested.targetFormCode()==null
        ||requested.exactTargetFormVersionId()==null
        ||requested.outputMetricCode()==null||requested.formulaVersion()==null
        ||requested.operation()==null||requested.periodSemantics()==null
        ||requested.authoredEvidence()==null)
      throw new IllegalArgumentException("CALC4D_FORMULA_DEFINITION_REQUIRED");
    if(!requested.targetFormCode().matches("[A-Z][A-Z0-9_-]{1,63}")
        ||!requested.formulaVersion().matches("[A-Z][A-Z0-9_-]{1,63}")
        ||!requested.outputMetricCode().matches("[A-Za-z0-9_.-]{3,160}")
        ||!requested.outputMetricCode().startsWith(requested.targetFormCode()+".")
        ||requested.authoredEvidence().isBlank()
        ||requested.authoredEvidence().length()>160
        ||!Set.of("AS_OF_CANDIDATE","INTERVAL_CANDIDATE",
            "INTERVAL_REQUIRED").contains(requested.periodSemantics())
        ||!Set.of("SUBTRACT","RATIO","RECONCILE","ROLL_FORWARD",
            "GROUP_BY","RATIO_OF_SUMS").contains(requested.operation()))
      throw new IllegalArgumentException("CALC4D_FORMULA_CONTRACT_INVALID");
    int arity=switch(requested.operation()){
      case "SUBTRACT","RATIO","RATIO_OF_SUMS"->2;
      case "RECONCILE"->3;
      case "ROLL_FORWARD"->5;
      case "GROUP_BY"->1;
      default->throw new IllegalArgumentException("CALC4D_OP_NOT_WHITELISTED");
    };
    if(requested.inputs().size()!=arity
        ||new HashSet<>(requested.inputs().stream()
          .map(InputSource::metricCode).toList()).size()!=arity)
      throw new IllegalArgumentException("CALC4D_FORMULA_INPUT_ARITY_OR_DUPLICATE");

    var form=jdbc.query("""
      SELECT f.form_code,v.id,v.schema_sha256,v.content_mode
      FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      WHERE f.form_code=? AND v.id=?
        AND v.content_mode IN ('ROWSET','SINGLE_RECORD')
      """,(r,i)->new Form(r.getString(1),r.getObject(2,UUID.class),
        r.getString(3),r.getString(4)),requested.targetFormCode(),
        requested.exactTargetFormVersionId());
    if(form.size()!=1)throw new IllegalStateException("CALC4D_FORM_VERSION_NOT_CURRENT");
    var f=form.getFirst();
    Integer physicalCollision=jdbc.queryForObject("""
      SELECT count(*) FROM fin_lib.lib_metric_definition
      WHERE metric_code=? AND form_code=? AND metric_kind='SOURCE_FIELD'
      """,Integer.class,requested.outputMetricCode(),f.code());
    if(physicalCollision==null||physicalCollision!=0)
      throw new IllegalArgumentException("CALC4D_OUTPUT_CANNOT_SHADOW_SOURCE_METRIC");
    var metricNames=new ArrayList<String>();
    var sources=new ArrayList<Resolved>();

    for(var input:requested.inputs()){
      if(input==null||input.metricCode()==null||input.exactFormVersionId()==null
          ||!input.metricCode().matches("[A-Za-z0-9_.-]{3,160}"))
        throw new IllegalArgumentException("CALC4D_SOURCE_IDENTITY_INVALID");
      metricNames.add(input.metricCode());
      if(input.exactFormulaRevisionId()!=null){
        var dependent=jdbc.query("""
          SELECT output_metric_code,form_version_id,source_schema_sha256,period_semantics
          FROM fin_calc.formula_contract_revision
          WHERE id=? AND financial_approval_state='NOT_S2_APPROVED'
            AND review_state IN ('CANDIDATE_UNQUALIFIED','UAT_TEST_ONLY')
          """,(r,i)->new Derived(r.getString(1),r.getObject(2,UUID.class),
          r.getString(3),r.getString(4)),input.exactFormulaRevisionId());
        if(dependent.size()!=1
            ||!dependent.getFirst().metric().equals(input.metricCode())
            ||!dependent.getFirst().version().equals(input.exactFormVersionId())
            ||!dependent.getFirst().period().equals(requested.periodSemantics()))
          throw new IllegalStateException("CALC4D_DERIVED_DEPENDENCY_NOT_EXACT");
        var dep=dependent.getFirst();
        sources.add(new Resolved(input.metricCode(),"FORMULA_CANDIDATE",
          dep.version(),dep.schema(),null,null,input.exactFormulaRevisionId(),
          requested.periodSemantics(),null));
      }else {
        var field=jdbc.query("""
          SELECT fd.form_code,m.metric_code,v.id,v.schema_sha256,
            ff.field_code,ff.field_type
          FROM fin_lib.lib_metric_definition m
          JOIN fin_lib.lib_form_definition fd ON fd.form_code=m.form_code
          JOIN fin_lib.lib_form_version v ON v.id=fd.current_version_id
          JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
            AND ff.field_code=m.source_field_code
          JOIN fin_lib.lib_field_binding_contract b ON b.form_code=fd.form_code
            AND b.field_code=ff.field_code AND b.metric_code=m.metric_code
            AND b.source_schema_sha256=v.schema_sha256
            AND b.review_state='CANDIDATE_UNQUALIFIED'
          WHERE m.metric_code=? AND v.id=? AND m.metric_kind='SOURCE_FIELD'
            AND ff.field_type IN ('MONEY','DECIMAL','INTEGER')
          """,(r,i)->new Source(r.getString(1),r.getString(2),
            r.getObject(3,UUID.class),r.getString(4),
            r.getString(5),r.getString(6)),
          input.metricCode(),input.exactFormVersionId());
        if(field.size()!=1)
          throw new IllegalStateException("CALC4D_PHYSICAL_FORMULA_SOURCE_INVALID:"+input.metricCode());
        var s=field.getFirst();
        sources.add(new Resolved(input.metricCode(),"PHYSICAL_NUMERIC_FIELD",
          s.version(),s.schema(),s.field(),s.type(),null,
          requested.periodSemantics(),null));
      }
    }
    String sourceJson=json.writeValueAsString(metricNames);
    String definitionSha=Calc4DTypedDagCompiler.sha(
      "FIN-CALC4D-FORMULA-CANDIDATE-V1|"+f.code()+"|"+f.version()
      +"|"+f.schema()+"|"+requested.outputMetricCode()
      +"|"+requested.formulaVersion()+"|"+requested.operation()
      +"|"+requested.periodSemantics()+"|"+sourceJson+"|"
      +requested.authoredEvidence()+"|"
      +sources.stream().map(x->x.formVersion()+"|"+x.schema()+"|"
        +x.kind()+"|"+x.field()+"|"+x.type()+"|"+x.derivedId())
        .reduce("",(a,b)->a+"|"+b));
    String formulaBinding=Calc4DTypedDagCompiler.sha(
      "FIN-CALC154-FORMULA-BINDING-V1|"+f.version()+"|"+f.schema()+"|"+definitionSha);

    UUID newId=UUID.randomUUID();
    String zero=Set.of("RATIO","RATIO_OF_SUMS").contains(requested.operation())
      ?"INVALID_CANDIDATE":"NOT_APPLICABLE";
    int inserted=jdbc.update("""
      INSERT INTO fin_calc.formula_contract_revision(
        id,form_code,form_version_id,source_schema_sha256,
        output_metric_code,formula_version,operation_code,source_metrics_jsonb,
        period_semantics,dimension_policy,currency_policy,unit_policy,
        rounding_policy,missing_policy,zero_policy,review_state,blocker_codes,
        source_evidence,definition_sha256,binding_sha256)
      VALUES (?,?,?,?,?,?,?,?::jsonb,?,'UNREVIEWED','UNREVIEWED',
        'FORM_CURRENCY_REVIEW_REQUIRED','UNREVIEWED','REQUIRE_PRESENT_CANDIDATE',
        ?,'CANDIDATE_UNQUALIFIED',
        'FINANCE_S2;OUTPUT_REQUIREDNESS;POLICIES_UNREVIEWED;CONTROL_EXAMPLES',
        ?,?,?)
      ON CONFLICT (form_version_id,output_metric_code,formula_version)
      DO NOTHING
      """,newId,f.code(),f.version(),f.schema(),requested.outputMetricCode(),
      requested.formulaVersion(),requested.operation(),sourceJson,
      requested.periodSemantics(),zero,requested.authoredEvidence(),
      definitionSha,formulaBinding);
    var saved=jdbc.query("""
      SELECT id,definition_sha256,binding_sha256,review_state,
        source_metrics_jsonb::text
      FROM fin_calc.formula_contract_revision
      WHERE form_version_id=? AND output_metric_code=? AND formula_version=?
      """,(r,i)->new Stored(r.getObject(1,UUID.class),r.getString(2),
        r.getString(3),r.getString(4),r.getString(5)),
      f.version(),requested.outputMetricCode(),requested.formulaVersion());
    if(saved.size()!=1||!definitionSha.equals(saved.getFirst().definitionSha())
        ||!formulaBinding.equals(saved.getFirst().bindingSha())
        ||!"CANDIDATE_UNQUALIFIED".equals(saved.getFirst().review())
        ||!json.readTree(sourceJson).equals(json.readTree(saved.getFirst().sourceJson())))
      throw new IllegalStateException("CALC4D_FORMULA_REVISION_REPLAY_CONFLICT");
    UUID id=saved.getFirst().id();
    if(inserted==1){
      for(int i=0;i<sources.size();i++){
        var s=sources.get(i);
        String identity=s.derivedId()==null?s.field()+"|"+s.type():s.derivedId().toString();
        String inputBinding=Calc4DTypedDagCompiler.sha(
          "CALC3B-TYPED-INPUT-V1|"+id+"|"+i+"|"+s.metric()+"|"+s.kind()
          +"|"+s.formVersion()+"|"+s.schema()+"|"+identity);
        jdbc.update("""
          INSERT INTO fin_calc.formula_typed_input_binding(
            formula_revision_id,ordinal,source_metric_code,source_kind,
            source_form_version_id,source_schema_sha256,source_field_code,
            field_type,source_formula_revision_id,period_policy,
            requiredness_state,binding_sha256)
          VALUES(?,?,?,?,?,?,?,?,?,?,'REVIEW_REQUIRED',?)
          """,id,i,s.metric(),s.kind(),s.formVersion(),s.schema(),
          s.field(),s.type(),s.derivedId(),s.period(),inputBinding);
      }
    }
    Integer count=jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.formula_typed_input_binding
      WHERE formula_revision_id=?
      """,Integer.class,id);
    if(count==null||count!=sources.size())
      throw new IllegalStateException("CALC4D_REGISTERED_FORMULA_INPUT_COUNT_MISMATCH");
    for(int i=0;i<sources.size();i++){
      var s=sources.get(i);
      var original=jdbc.query("""
        SELECT source_metric_code,source_kind,source_form_version_id,
          source_schema_sha256,source_field_code,field_type,
          source_formula_revision_id,binding_sha256
        FROM fin_calc.formula_typed_input_binding
        WHERE formula_revision_id=? AND ordinal=?
        """,(r,j)->new Object[]{r.getString(1),r.getString(2),
          r.getObject(3,UUID.class),r.getString(4),r.getString(5),
          r.getString(6),r.getObject(7,UUID.class),r.getString(8)},id,i);
      String expectedBinding=Calc4DTypedDagCompiler.sha(
        "CALC3B-TYPED-INPUT-V1|"+id+"|"+i+"|"+s.metric()+"|"+s.kind()
        +"|"+s.formVersion()+"|"+s.schema()+"|"+
        (s.derivedId()==null?s.field()+"|"+s.type():s.derivedId().toString()));
      if(original.size()!=1||!s.metric().equals(original.getFirst()[0])
          ||!s.kind().equals(original.getFirst()[1])
          ||!s.formVersion().equals(original.getFirst()[2])
          ||!s.schema().equals(original.getFirst()[3])
          ||!Objects.equals(s.field(),original.getFirst()[4])
          ||!Objects.equals(s.type(),original.getFirst()[5])
          ||!Objects.equals(s.derivedId(),original.getFirst()[6])
          ||!expectedBinding.equals(original.getFirst()[7]))
        throw new IllegalStateException("CALC4D_FORMULA_INPUT_REPLAY_CONFLICT");
    }
    return new Receipt(id,definitionSha,formulaBinding,inserted==0,false);
  }
}
