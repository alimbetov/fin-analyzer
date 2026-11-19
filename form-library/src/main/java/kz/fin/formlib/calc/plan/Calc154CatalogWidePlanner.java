package kz.fin.formlib.calc.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-4B / catalog-wide deterministic calculation PLANNING for every numeric
 * data form. This is a read model and never a runnable CalcPlan or approval.
 *
 * No fabricated financial formulas: a source field is not a derived output,
 * and an operation idea in a TSV is not a published calculation contract.
 * New authored formula revisions become visible automatically without
 * adding a per-form service or hard-coded list of F01/F02/F03.
 */
@Service
public class Calc154CatalogWidePlanner {
  public enum Status {
    BLOCKED_NO_FORMULA_CONTRACT,
    BLOCKED_REQUIRED_OUTPUT_REVIEW,
    BLOCKED_SOURCE_BINDING,
    BLOCKED_FINANCIAL_S2
  }
  public record SourceMetric(String metricCode,String fieldType,String sourceField) {}
  public record FormulaCandidate(UUID id,String outputMetric,String formulaVersion,
      String operation,List<String> sourceMetrics,String bindingSha256,
      String reviewState) {
    public FormulaCandidate { sourceMetrics=List.copyOf(sourceMetrics); }
  }
  public record FormPlan(String formCode,UUID formVersionId,String schemaSha256,
      String contentMode,int directNumericFields,List<SourceMetric> sources,
      List<FormulaCandidate> candidates,Status status,
      List<String> blockers,String manifestSha256,boolean financiallyApproved,
      boolean executable) {
    public FormPlan {
      sources=List.copyOf(sources);
      candidates=List.copyOf(candidates);
      blockers=List.copyOf(blockers);
    }
  }
  public record Inventory(List<FormPlan> forms,int formsWithNumericSources,
      int referenceOnlyForms,int candidateFormCount,int candidateFormulaCount,
      int financiallyApprovedCount,String inventorySha256) {
    public Inventory { forms=List.copyOf(forms); }
    public FormPlan require(String formCode){
      return forms.stream().filter(f->f.formCode().equals(formCode))
          .findFirst().orElseThrow(()->new IllegalArgumentException(
            "CALC154_FORM_UNREGISTERED:"+formCode));
    }
  }
  private record Form(String code,UUID id,String schema,String mode,
      int numeric,int sourceFields,boolean reference) {}
  private record Physical(String code,String metric,String type,String field) {}
  private record Candidate(UUID id,String form,String metric,String version,
      String operation,String json,String schema,UUID formVersion,
      String binding,String review,String approval) {}
  private record Bound(UUID formula,int ordinal,String metric,String kind,
      UUID sourceVersion,String schema,UUID dependency,String fieldType) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  public Calc154CatalogWidePlanner(JdbcTemplate jdbc,ObjectMapper mapper){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.mapper=Objects.requireNonNull(mapper);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Inventory inventory(){
    var forms=jdbc.query("""
      SELECT f.form_code,v.id,v.schema_sha256,v.content_mode,
        c.direct_numeric_field_count,c.source_field_count,c.reference_only
      FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      JOIN fin_calc.form_numeric_source_capability_revision c
        ON c.form_code=f.form_code AND c.form_version_id=v.id
        AND c.source_schema_sha256=v.schema_sha256
        AND c.capability_version='CALC3C1_NUMERIC_SOURCE_V1'
        AND c.approval_state='NOT_S2_APPROVED'
      WHERE v.content_mode IN ('ROWSET','SINGLE_RECORD')
      ORDER BY f.form_code
      """,(r,i)->new Form(r.getString(1),r.getObject(2,UUID.class),
        r.getString(3),r.getString(4),r.getInt(5),
        r.getInt(6),r.getBoolean(7)));
    if(forms.size()!=154)
      throw new IllegalStateException("CALC154_NUMERIC_FORM_CATALOG_DRIFT");
    var physical=jdbc.query("""
      SELECT f.form_code,m.metric_code,ff.field_type,ff.field_code
      FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
      JOIN fin_lib.lib_metric_definition m ON m.form_code=f.form_code
        AND m.source_field_code=ff.field_code AND m.metric_kind='SOURCE_FIELD'
      JOIN fin_lib.lib_field_binding_contract b ON b.form_code=f.form_code
        AND b.metric_code=m.metric_code AND b.field_code=ff.field_code
        AND b.source_schema_sha256=v.schema_sha256
        AND b.review_state='CANDIDATE_UNQUALIFIED'
      WHERE v.content_mode IN ('ROWSET','SINGLE_RECORD')
        AND ff.field_type IN ('MONEY','DECIMAL','INTEGER')
      ORDER BY f.form_code,m.metric_code
      """,(r,i)->new Physical(r.getString(1),r.getString(2),
        r.getString(3),r.getString(4)));
    Map<String,List<SourceMetric>> sources=new HashMap<>();
    Map<String,Physical> metricMap=new HashMap<>();
    for(var p:physical){
      if(metricMap.putIfAbsent(p.metric(),p)!=null)
        throw new IllegalStateException("CALC154_DUPLICATE_SOURCE_METRIC:"+p.metric());
      sources.computeIfAbsent(p.code(),ignored->new ArrayList<>())
        .add(new SourceMetric(p.metric(),p.type(),p.field()));
    }

    var formulas=jdbc.query("""
      SELECT c.id,c.form_code,c.output_metric_code,c.formula_version,
        c.operation_code,c.source_metrics_jsonb::text,c.source_schema_sha256,
        c.form_version_id,c.binding_sha256,c.review_state,c.financial_approval_state
      FROM fin_calc.formula_contract_revision c
      JOIN fin_lib.lib_form_definition f ON f.form_code=c.form_code
        AND f.current_version_id=c.form_version_id
      ORDER BY c.form_code,c.output_metric_code,c.formula_version
      """,(r,i)->new Candidate(r.getObject(1,UUID.class),r.getString(2),
        r.getString(3),r.getString(4),r.getString(5),r.getString(6),
        r.getString(7),r.getObject(8,UUID.class),r.getString(9),
        r.getString(10),r.getString(11)));
    var bindings=jdbc.query("""
      SELECT i.formula_revision_id,i.ordinal,i.source_metric_code,
        i.source_kind,i.source_form_version_id,i.source_schema_sha256,
        i.source_formula_revision_id,i.field_type
      FROM fin_calc.formula_typed_input_binding i
      JOIN fin_calc.formula_contract_revision c ON c.id=i.formula_revision_id
      JOIN fin_lib.lib_form_definition f ON f.current_version_id=c.form_version_id
      ORDER BY i.formula_revision_id,i.ordinal
      """,(r,i)->new Bound(r.getObject(1,UUID.class),r.getInt(2),
        r.getString(3),r.getString(4),r.getObject(5,UUID.class),
        r.getString(6),r.getObject(7,UUID.class),r.getString(8)));
    Map<UUID,List<Bound>> bound=new HashMap<>();
    for(var b:bindings)bound.computeIfAbsent(b.formula(),ignored->new ArrayList<>()).add(b);
    Map<UUID,Candidate> byFormula=new HashMap<>();
    for(var c:formulas)if(byFormula.putIfAbsent(c.id(),c)!=null)
      throw new IllegalStateException("CALC154_DUPLICATE_FORMULA_REVISION");
    Map<String,List<FormulaCandidate>> byForm=new HashMap<>();
    for(var c:formulas){
      if(!"NOT_S2_APPROVED".equals(c.approval())
        ||!Set.of("UAT_TEST_ONLY","CANDIDATE_UNQUALIFIED").contains(c.review())
        ||!c.binding().matches("[0-9a-f]{64}"))
        throw new IllegalStateException("CALC154_UNEXPECTED_FORMULA_APPROVAL");
      var declared=readInputs(c.json());
      var actual=bound.getOrDefault(c.id(),List.of());
      if(actual.size()!=declared.size())
        throw new IllegalStateException("CALC154_UNTYPED_FORMULA_INPUT:"+c.metric());
      for(int i=0;i<actual.size();i++){
        var b=actual.get(i);
        if(b.ordinal()!=i||!b.metric().equals(declared.get(i))
          ||!b.sourceVersion().equals(c.formVersion())||!b.schema().equals(c.schema()))
          throw new IllegalStateException("CALC154_FORMULA_INPUT_VERSION_DRIFT:"+c.metric());
        if("PHYSICAL_NUMERIC_FIELD".equals(b.kind())){
          var p=metricMap.get(b.metric());
          if(p==null||!p.code().equals(c.form())
            ||!p.type().equals(b.fieldType())||b.dependency()!=null)
            throw new IllegalStateException("CALC154_FORMULA_PHYSICAL_SOURCE_DRIFT");
        }else if("FORMULA_CANDIDATE".equals(b.kind())){
          var other=byFormula.get(b.dependency());
          if(other==null||!other.metric().equals(b.metric())
             ||!other.formVersion().equals(c.formVersion()))
            throw new IllegalStateException("CALC154_DERIVED_DEPENDENCY_DRIFT");
        }else throw new IllegalStateException("CALC154_UNKNOWN_SOURCE_KIND");
      }
      byForm.computeIfAbsent(c.form(),ignored->new ArrayList<>())
        .add(new FormulaCandidate(c.id(),c.metric(),c.version(),c.operation(),
          declared,c.binding(),c.review()));
    }
    List<FormPlan> plans=new ArrayList<>();
    var digest=new StringBuilder("FIN-CALC154-CATALOG-WIDE-V1");
    int numeric=0,reference=0,active=0,formulaCount=0;
    for(var f:forms){
      var ps=List.copyOf(sources.getOrDefault(f.code(),List.of()));
      if(ps.size()!=f.numeric())
        throw new IllegalStateException("CALC154_FORM_SOURCE_COUNT_DRIFT:"+f.code());
      if(f.reference()!=(f.numeric()==0))
        throw new IllegalStateException("CALC154_FORM_REFERENCE_CLASSIFICATION_DRIFT");
      if(f.numeric()>0)numeric++;else reference++;
      var candidates=List.copyOf(byForm.getOrDefault(f.code(),List.of()));
      if(!candidates.isEmpty())active++;
      formulaCount+=candidates.size();
      var blockers=new ArrayList<String>();
      blockers.add("MANDATORY_OUTPUT_SET_NOT_AUTHORED");
      blockers.add("PERIOD_DIMENSION_CURRENCY_POLICY_UNREVIEWED");
      blockers.add("FINANCIAL_S2_NOT_APPROVED");
      if(f.numeric()==0)blockers.add("NO_DIRECT_NUMERIC_SOURCE");
      if(candidates.isEmpty())blockers.add("FORMULA_CONTRACT_NOT_AUTHORED");
      StringBuilder canonical=new StringBuilder("CALC154-FORM-V1|")
        .append(f.code()).append('|').append(f.id()).append('|')
        .append(f.schema()).append('|').append(f.mode()).append('|')
        .append(f.numeric());
      for(var p:ps)canonical.append("|S:").append(p.metricCode())
        .append(':').append(p.fieldType()).append(':').append(p.sourceField());
      for(var c:candidates)canonical.append("|F:").append(c.id()).append(':')
        .append(c.outputMetric()).append(':').append(c.formulaVersion())
        .append(':').append(c.operation()).append(':')
        .append(c.bindingSha256()).append(':')
        .append(String.join(",",c.sourceMetrics()));
      String formSha=sha(canonical.toString());
      Status status=candidates.isEmpty()?Status.BLOCKED_NO_FORMULA_CONTRACT:
        Status.BLOCKED_REQUIRED_OUTPUT_REVIEW;
      plans.add(new FormPlan(f.code(),f.id(),f.schema(),f.mode(),
        f.numeric(),ps,candidates,status,blockers,formSha,false,false));
      digest.append('|').append(f.code()).append(':').append(formSha);
    }
    if(numeric!=145||reference!=9)
      throw new IllegalStateException("CALC154_SOURCE_CLASSIFICATION_DRIFT");
    return new Inventory(plans,numeric,reference,active,formulaCount,0,
      sha(digest.toString()));
  }

  private List<String> readInputs(String raw){
    try{
      String[] data=mapper.readValue(raw,String[].class);
      if(data.length<1||data.length>16)
        throw new IllegalStateException("CALC154_FORMULA_INPUT_ARITY");
      return List.of(data);
    }catch(com.fasterxml.jackson.core.JsonProcessingException e){
      throw new IllegalStateException("CALC154_FORMULA_JSON_INVALID",e);
    }
  }

  private static String sha(String value){
    try{
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception e){throw new IllegalStateException("CALC154_SHA_UNAVAILABLE",e);}
  }
}
