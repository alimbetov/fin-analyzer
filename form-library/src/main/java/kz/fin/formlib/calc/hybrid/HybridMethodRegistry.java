package kz.fin.formlib.calc.hybrid;

import kz.fin.formlib.calc.fact.FactDigest;
import kz.fin.formlib.calc.plan.F02CalcPlanDescriptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * HYB-2A: read/verify typed, immutable method registry from real PostgreSQL.
 *
 * The registry starts EMPTY. TEST_ONLY_REFERENCE is not financial approval:
 * this class deliberately has no public write or production admission API.
 * Its verified test-only entry may be used solely by the isolated F02
 * comparative executor; never by a production job producer.
 */
@Service
public class HybridMethodRegistry {
  public enum RegistryState { CANDIDATE_UNQUALIFIED, TEST_ONLY_REFERENCE }
  public enum Strategy { JAVA_ONLY, SQL_ONLY, SQL_AGG_JAVA_FINAL }

  public record Input(int ordinal,UUID formVersionId,String fieldCode,
      String metricCode,String schemaSha256,String unitPolicy,
      String periodSemantics,String aggregationPolicy,String missingPolicy) {}
  public record Output(int ordinal,String outputCode,String unitCode,
      int numericScale,String missingPolicy) {}
  public record Revision(UUID id,String methodCode,String methodVersion,
      RegistryState state,String sourceContractSha256,
      String outputContractSha256,String dagSha256,
      String algorithmArtifactSha256,String configSha256,String paramsSha256,
      String sqlArtifactSha256,boolean artifactAttested,
      List<Input> inputs,List<Output> outputs) {
    public Revision {
      inputs=List.copyOf(inputs);
      outputs=List.copyOf(outputs);
    }
    public boolean financiallyApproved(){return false;}
  }

  private record InputRow(Input value,String actualSchema,String actualForm,
      String metricForm,String metricField,String metricKind,
      String metricUnit,String contractMetric,String contractUnit,
      String contractReview,String contractSchema) {}

  private final JdbcTemplate jdbc;

  public HybridMethodRegistry(JdbcTemplate jdbc){this.jdbc=jdbc;}

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Revision requireVerified(UUID revisionId){
    if(revisionId==null)throw new IllegalArgumentException("HYB_METHOD_ID_REQUIRED");
    var matches=jdbc.query("""
        SELECT id,method_code,method_version,registry_state,
          source_contract_sha256,output_contract_sha256,dag_sha256,
          algorithm_artifact_sha256,config_sha256,params_sha256,
          sql_artifact_sha256,artifact_attested
        FROM fin_calc.method_revision WHERE id=?
        """,(r,i)->new Object[]{
            r.getObject("id",UUID.class),r.getString("method_code"),
            r.getString("method_version"),r.getString("registry_state"),
            r.getString("source_contract_sha256"),
            r.getString("output_contract_sha256"),r.getString("dag_sha256"),
            r.getString("algorithm_artifact_sha256"),
            r.getString("config_sha256"),r.getString("params_sha256"),
            r.getString("sql_artifact_sha256"),r.getBoolean("artifact_attested")
          },revisionId);
    if(matches.size()!=1)throw new IllegalArgumentException("HYB_METHOD_NOT_REGISTERED");
    var m=matches.getFirst();
    var rowInputs=jdbc.query("""
        SELECT b.ordinal,b.form_version_id,b.field_code,b.metric_code,
          b.source_schema_sha256,b.unit_policy,b.period_semantics,
          b.aggregation_policy,b.missing_policy,
          v.schema_sha256 actual_schema, f.form_code actual_form,
          md.form_code metric_form,md.source_field_code metric_field,
          md.metric_kind,md.unit_policy metric_unit,
          c.metric_code contract_metric,c.unit_policy contract_unit,
          c.review_state contract_review,c.source_schema_sha256 contract_schema
        FROM fin_calc.method_input_binding b
        JOIN fin_lib.lib_form_version v ON v.id=b.form_version_id
        JOIN fin_lib.lib_form_definition f ON f.id=v.form_id
        JOIN fin_lib.lib_form_field ff
          ON ff.form_version_id=b.form_version_id
          AND ff.field_code=b.field_code
        JOIN fin_lib.lib_metric_definition md ON md.metric_code=b.metric_code
        LEFT JOIN fin_lib.lib_field_binding_contract c
          ON c.form_code=f.form_code AND c.field_code=b.field_code
        WHERE b.method_revision_id=?
        ORDER BY b.ordinal
        """,(r,i)->new InputRow(new Input(r.getInt("ordinal"),
            r.getObject("form_version_id",UUID.class),r.getString("field_code"),
            r.getString("metric_code"),r.getString("source_schema_sha256"),
            r.getString("unit_policy"),r.getString("period_semantics"),
            r.getString("aggregation_policy"),r.getString("missing_policy")),
            r.getString("actual_schema"),r.getString("actual_form"),
            r.getString("metric_form"),r.getString("metric_field"),
            r.getString("metric_kind"),r.getString("metric_unit"),
            r.getString("contract_metric"),r.getString("contract_unit"),
            r.getString("contract_review"),r.getString("contract_schema")),
        revisionId);
    if(rowInputs.isEmpty())throw new IllegalStateException("HYB_METHOD_INPUTS_MISSING");
    var inputs=new ArrayList<Input>();
    for(int i=0;i<rowInputs.size();i++){
      var b=rowInputs.get(i);
      var x=b.value();
      if(x.ordinal()!=i||!x.schemaSha256().equals(b.actualSchema())
          ||!x.schemaSha256().equals(b.contractSchema())
          ||!b.actualForm().equals(b.metricForm())
          ||!x.fieldCode().equals(b.metricField())
          ||!x.metricCode().equals(b.contractMetric())
          ||!x.unitPolicy().equals(b.metricUnit())
          ||!x.unitPolicy().equals(b.contractUnit())
          ||!"SOURCE_FIELD".equals(b.metricKind())
          ||!"CANDIDATE_UNQUALIFIED".equals(b.contractReview()))
        throw new IllegalStateException("HYB_METHOD_INPUT_CONTRACT_DRIFT");
      inputs.add(x);
    }
    var outputs=jdbc.query("""
        SELECT ordinal,output_code,unit_code,numeric_scale,missing_policy
        FROM fin_calc.method_output_binding
        WHERE method_revision_id=? ORDER BY ordinal
        """,(r,i)->new Output(r.getInt(1),r.getString(2),
            r.getString(3),r.getInt(4),r.getString(5)),revisionId);
    if(outputs.isEmpty())throw new IllegalStateException("HYB_METHOD_OUTPUTS_MISSING");
    for(int i=0;i<outputs.size();i++)
      if(outputs.get(i).ordinal()!=i)
        throw new IllegalStateException("HYB_METHOD_OUTPUT_ORDINAL_GAP");

    if(!sourceDigest(inputs).equals(m[4])
        ||!outputDigest(outputs).equals(m[5]))
      throw new IllegalStateException("HYB_METHOD_CONTRACT_SHA_MISMATCH");
    var state=RegistryState.valueOf((String)m[3]);
    if((boolean)m[11])
      throw new IllegalStateException("HYB_METHOD_BUILD_ATTESTATION_NOT_SUPPORTED");
    return new Revision((UUID)m[0],(String)m[1],(String)m[2],state,
        (String)m[4],(String)m[5],(String)m[6],(String)m[7],(String)m[8],
        (String)m[9],(String)m[10],(boolean)m[11],inputs,outputs);
  }

  /**
   * The only executable pilot allowlist. Reference state, SHA and source
   * version must match F02 exactly. No strategy is authorized for new forms.
   */
  public Revision requireF02TestOnly(UUID methodId,UUID exactFormVersion){
    if(exactFormVersion==null)
      throw new IllegalArgumentException("HYB_FORM_VERSION_REQUIRED");
    var m=requireVerified(methodId);
    if(m.state()!=RegistryState.TEST_ONLY_REFERENCE
        ||!F02CalcPlanDescriptor.METHOD.equals(m.methodCode())
        ||!"1".equals(m.methodVersion())
        ||!F02CalcPlanDescriptor.ARTIFACT_SHA.equals(m.algorithmArtifactSha256())
        ||!F02CalcPlanDescriptor.CONFIG_SHA.equals(m.configSha256())
        ||!F02CalcPlanDescriptor.PARAMS_SHA.equals(m.paramsSha256())
        ||m.inputs().size()!=3||m.outputs().size()!=3
        ||m.sqlArtifactSha256()==null)
      throw new IllegalStateException("HYB_METHOD_NOT_ADMITTED_TEST_ONLY");
    var in=m.inputs();
    if(!in.stream().allMatch(i->i.formVersionId().equals(exactFormVersion)
        &&"INTERVAL".equals(i.periodSemantics())
        &&"NONE".equals(i.aggregationPolicy())
        &&"REQUIRE_PRESENT".equals(i.missingPolicy()))
        ||!in.stream().map(Input::metricCode).toList()
          .equals(List.of("F02.cogs","F02.netRevenue","F02.operatingExpenses")))
      throw new IllegalStateException("HYB_F02_INPUT_SHAPE_INVALID");
    var out=m.outputs();
    if(!out.stream().map(Output::outputCode).toList()
        .equals(List.of("grossProfit","grossMargin","operatingResult"))
        ||!out.stream().map(Output::unitCode).toList()
          .equals(List.of("KZT","RATIO","KZT"))
        ||!out.stream().map(Output::numericScale).toList()
          .equals(List.of(2,8,2)))
      throw new IllegalStateException("HYB_F02_OUTPUT_SHAPE_INVALID");
    return m;
  }

  public static String sourceDigest(List<Input> inputs){
    if(inputs==null||inputs.isEmpty())throw new IllegalArgumentException("HYB_INPUTS_REQUIRED");
    var parts=new ArrayList<String>();
    for(var a:inputs){
      if(a==null)throw new IllegalArgumentException("HYB_INPUT_NULL");
      parts.addAll(List.of(Integer.toString(a.ordinal()),a.formVersionId().toString(),
          a.fieldCode(),a.metricCode(),a.schemaSha256(),a.unitPolicy(),
          a.periodSemantics(),a.aggregationPolicy(),a.missingPolicy()));
    }
    return FactDigest.sha("FIN-FACT-HYB-INPUT-CONTRACT-V1",parts.toArray(String[]::new));
  }

  public static String outputDigest(List<Output> outputs){
    if(outputs==null||outputs.isEmpty())throw new IllegalArgumentException("HYB_OUTPUTS_REQUIRED");
    var parts=new ArrayList<String>();
    for(var a:outputs){
      if(a==null)throw new IllegalArgumentException("HYB_OUTPUT_NULL");
      parts.addAll(List.of(Integer.toString(a.ordinal()),a.outputCode(),
          a.unitCode(),Integer.toString(a.numericScale()),a.missingPolicy()));
    }
    return FactDigest.sha("FIN-FACT-HYB-OUTPUT-CONTRACT-V1",parts.toArray(String[]::new));
  }
}
