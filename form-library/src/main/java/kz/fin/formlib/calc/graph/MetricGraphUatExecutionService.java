package kz.fin.formlib.calc.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.ingest.FinIntakeUatSourceAdapter;
import kz.fin.formlib.calc.ingest.UatCalculationPreviewService;
import kz.fin.formlib.calc.loan.LoanAmendmentService;
import kz.fin.formlib.calc.loan.LoanScheduleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * MG-R0.4 UAT bridge: metric -> EXISTING typed DAG / CALC-A05 app ->
 * SHA-reverified exact fin_intake -> EXISTING financial kernel ->
 * immutable plan/result. No new math engine or production CalcPlan admission.
 *
 * Narrow verified executable adapters only: three F02 TEST_ONLY outputs and
 * two already-persisted A05 debt-service projection outputs.
 */
@Service
@ConditionalOnProperty(
  name={"fin.calc.mg-r04-uat.enabled","fin.calc.intake-uat.enabled"},
  havingValue="true")
public class MetricGraphUatExecutionService {
  public static final String STATE="UAT_UNQUALIFIED";
  public record Request(UUID organizationId,UUID projectId,
      String targetMetricCode,UUID exactSourceRevisionId,
      UUID exactMethodRevisionId,UUID exactCalculationRevisionId,
      String expectedPeriodType,String expectedPeriodCode,
      String expectedCurrency,String expectedReportingBasis,
      String expectedDataSpace) {}
  public record Receipt(UUID planId,UUID resultId,String targetMetricCode,
      String executionFamily,UUID sourceRevisionId,UUID formulaRevisionId,
      UUID calculationRevisionId,String planSha256,String resultSha256,
      String graphSha256,String sourceSha256,String methodBindingSha256,
      String valueState,String valueUnit,String valueText,String errorCode,
      String state,String commandResult,Map<String,Object> lineage,
      boolean financiallyApproved,boolean productionAdmitted) {
    public Receipt{lineage=Map.copyOf(lineage);}
  }

  private record Computed(String family,String sourceForm,UUID formula,
      UUID calculation,UUID application,String sourceSha,String schemaSha,
      String projectionSha,String methodSha,String graphSha,String engineSha,
      String lineageSha,String valueState,String unit,String value,
      String error,String upstreamSha,Map<String,Object> evidence,
      String planSha,String resultSha) {}
  private record PlanRow(UUID id,UUID org,UUID project,String metric,String family,
      String sourceForm,UUID source,UUID formula,UUID schedule,UUID amendment,
      UUID application,String sourceSha,String schemaSha,String projectionSha,
      String methodSha,String graphSha,String engineSha,String planSha,
      String periodType,String periodCode,String currency,String basis,
      String dataSpace,String lineageSha,String state) {}
  private record ResultRow(UUID id,UUID plan,String metric,String state,
      String unit,String value,String error,String upstreamSha,
      String resultSha,String evidenceJson,String admission) {}

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final MetricGraphResolver graph;
  private final FinIntakeUatSourceAdapter sources;
  private final UatCalculationPreviewService f02Preview;
  private final LoanScheduleService schedules;
  private final LoanAmendmentService amendments;

  public MetricGraphUatExecutionService(JdbcTemplate db,ObjectMapper json,
      MetricGraphResolver graph,FinIntakeUatSourceAdapter sources,
      UatCalculationPreviewService f02Preview,LoanScheduleService schedules,
      LoanAmendmentService amendments){
    this.db=Objects.requireNonNull(db);
    this.json=Objects.requireNonNull(json);
    this.graph=Objects.requireNonNull(graph);
    this.sources=Objects.requireNonNull(sources);
    this.f02Preview=Objects.requireNonNull(f02Preview);
    this.schedules=Objects.requireNonNull(schedules);
    this.amendments=Objects.requireNonNull(amendments);
  }

  private static ResponseStatusException bad(String code){
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  private static void check(boolean condition,String code){
    if(!condition)throw bad(code);
  }
  static String sha(String value){
    try{
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception e){throw new IllegalStateException("MG_R04_SHA_UNAVAILABLE",e);}
  }
  private String toJson(Object value){
    try{return json.writeValueAsString(value);}
    catch(JsonProcessingException e){throw new IllegalStateException("MG_R04_JSON_ENCODING_FAILED",e);}
  }
  private static String required(String v){
    return v==null?"":v.trim();
  }
  private static String pair(String a,String b){return a+"="+b;}
  private static String decimal(java.math.BigDecimal v){
    return v.stripTrailingZeros().toPlainString();
  }

  private void validate(Request request) {
    check(request!=null&&request.organizationId()!=null&&request.projectId()!=null
      &&request.exactSourceRevisionId()!=null
      &&request.targetMetricCode()!=null
      &&request.targetMetricCode().matches("[A-Za-z][A-Za-z0-9_.-]{1,159}")
      &&Set.of("M").contains(request.expectedPeriodType())
      &&request.expectedPeriodCode()!=null
      &&request.expectedPeriodCode().matches("[0-9]{4}-(0[1-9]|1[0-2])")
      &&"KZT".equals(request.expectedCurrency())
      &&"MANAGEMENT".equals(request.expectedReportingBasis())
      &&"SYNTHETIC".equals(request.expectedDataSpace()),
      "MG_R04_EXACT_SYNTHETIC_SCOPE_REQUIRED");
  }

  private String applicationSha(UUID app){
    var rows=db.query("""
      SELECT contract_sha256,app_code,revision_number,contract_state,
             financial_s2_approved,production_admitted
      FROM fin_calc.calc_application_contract_revision
      WHERE id=?
      """,(r,i)->List.of(r.getString(1).trim(),r.getString(2),
        Integer.toString(r.getInt(3)),r.getString(4),
        Boolean.toString(r.getBoolean(5)),Boolean.toString(r.getBoolean(6))),app);
    check(rows.size()==1,"MG_R04_A05_APPLICATION_CONTRACT_MISSING");
    var v=rows.getFirst();
    check("CALC-A05".equals(v.get(1))
        &&"1".equals(v.get(2))&&"SCHEMA_CANDIDATE".equals(v.get(3))
        &&"false".equals(v.get(4))&&"false".equals(v.get(5)),
        "MG_R04_APPLICATION_CONTRACT_NOT_UAT_PINNED");
    return v.getFirst();
  }

  private Computed compute(Request r) {
    validate(r);
    String family=switch(r.targetMetricCode()){
      case "F02.grossProfit","F02.grossMargin","F02.operatingResult" -> "F02_TYPED_DAG";
      case "CALC-A05.CONTRACT_CASH" -> "A05_SCHEDULE";
      case "CALC-A05.AMENDED_CONTRACT_CASH" -> "A05_AMENDMENT";
      default -> throw bad("MG_R04_EXECUTABLE_METHOD_NOT_AUTHORED");
    };
    boolean isF02="F02_TYPED_DAG".equals(family);
    check(isF02
        ?r.exactMethodRevisionId()!=null&&r.exactCalculationRevisionId()==null
        :r.exactMethodRevisionId()==null&&r.exactCalculationRevisionId()!=null,
      "MG_R04_EXACT_METHOD_OR_RESULT_REVISION_REQUIRED");
    String sourceForm=isF02?"F02":"C05";
    var verified=sources.readExact(r.organizationId(),r.projectId(),
      r.exactSourceRevisionId(),sourceForm);
    var p=verified.projection();
    var scope=p.scope();
    check(r.expectedPeriodType().equals(scope.periodType())
        &&r.expectedPeriodCode().equals(scope.periodCode())
        &&r.expectedCurrency().equals(scope.currencyCode())
        &&r.expectedReportingBasis().equals(scope.reportingBasis())
        &&r.expectedDataSpace().equals(scope.dataSpace())
        &&"PERIOD_ONLY".equals(scope.coverage()),
      "MG_R04_SOURCE_PERIOD_CURRENCY_OR_BASIS_MISMATCH");
    String sourceSha=p.sourceSha256Claim();
    String schemaSha=p.sourceSchemaSha256();
    String projectionSha=verified.pinnedSourceSha256();
    UUID app=null;
    UUID formula=null;
    String methodSha;
    String graphSha;
    String engineSha;
    String sourceLineageSha;
    String valueState;
    String unit;
    String value;
    String error;
    String upstream;
    var evidence=new LinkedHashMap<String,Object>();
    evidence.put("sourceRevisionId",r.exactSourceRevisionId().toString());
    evidence.put("sourceFormCode",sourceForm);
    evidence.put("sourceContentSha256",sourceSha);
    evidence.put("sourceProjectionSha256",projectionSha);
    evidence.put("sourceSchemaSha256",schemaSha);
    evidence.put("sourceIntegrity","SOURCE_SHA_REVERIFIED");
    evidence.put("periodType",scope.periodType());
    evidence.put("periodCode",scope.periodCode());
    evidence.put("currency",scope.currencyCode());
    evidence.put("reportingBasis",scope.reportingBasis());
    evidence.put("dataSpace",scope.dataSpace());

    if(isF02) {
      var resolved=graph.resolve(r.targetMetricCode(),null,r.exactMethodRevisionId());
      check("DAG_COMPILED_PREVIEW_ONLY".equals(resolved.state())
          &&r.exactMethodRevisionId().equals(resolved.selectedMethodRevisionId())
          &&resolved.graphSha256()!=null&&!resolved.orderedNodes().isEmpty(),
          "MG_R04_EXACT_TYPED_DAG_UNAVAILABLE");
      var method=resolved.methodRevisions().stream()
        .filter(m->m.id().equals(r.exactMethodRevisionId()))
        .findFirst().orElseThrow(()->bad("MG_R04_METHOD_REVISION_NOT_FOUND"));
      check(method.currentSchema()&&"UAT_TEST_ONLY".equals(method.reviewState())
          &&"F02_TEST_ONLY_V1".equals(method.formulaVersion())
          &&p.formVersionId().equals(method.formVersionId())
          &&schemaSha.equals(method.schemaSha256()),
          "MG_R04_STALE_F02_METHOD_OR_SCHEMA");
      check(resolved.orderedNodes().stream().anyMatch(
        n->r.exactMethodRevisionId().equals(n.formulaRevisionId())
          &&r.targetMetricCode().equals(n.outputMetricCode())),
        "MG_R04_SELECTED_OUTPUT_NOT_IN_DAG");
      for(var input:resolved.requiredPhysicalInputs()) {
        check("F02".equals(input.formCode())
            &&p.formVersionId().equals(input.formVersionId())
            &&schemaSha.equals(input.schemaSha256()),
            "MG_R04_PHYSICAL_INPUT_SCHEMA_DRIFT");
      }
      // Existing F02 kernel requires all three pilot inputs. The DAG may
      // require only two for grossProfit; never relax kernel requirements.
      var raw=f02Preview.preview(r.organizationId(),r.projectId(),
        r.exactSourceRevisionId(),"F02");
      check(raw.formVersionId().equals(p.formVersionId())
          &&raw.sourceContentSha256().equals(sourceSha)
          &&raw.sourceSchemaSha256().equals(schemaSha)
          &&raw.pinnedSourceSha256().equals(projectionSha)
          &&"F02_TEST_ONLY_V1".equals(raw.methodCode())
          &&!raw.financiallyApproved()&&!raw.productionAdmitted(),
          "MG_R04_F02_KERNEL_SOURCE_DRIFT");
      String outputName=r.targetMetricCode().substring("F02.".length());
      var node=raw.outputs().get(outputName);
      check(node!=null&&Set.of("PRESENT","INVALID").contains(node.state()),
        "MG_R04_F02_TYPED_OUTPUT_MISSING");
      valueState=node.state();value=node.value();unit=node.unit();
      error=node.errorCode();
      formula=r.exactMethodRevisionId();
      methodSha=method.bindingSha256();
      graphSha=resolved.graphSha256();
      engineSha=raw.calculationMethodSha256();
      sourceLineageSha=raw.pinnedSourceSha256();
      upstream=raw.resultValuesSha256();
      evidence.put("methodRevisionId",formula.toString());
      evidence.put("methodDefinitionSha256",method.definitionSha256());
      evidence.put("methodBindingSha256",methodSha);
      evidence.put("dagSha256",graphSha);
      evidence.put("engineMethodSha256",engineSha);
      evidence.put("kernelResultSha256",upstream);
      evidence.put("kernelPreviewSha256",raw.previewSha256());
      evidence.put("requiredFields",List.of("netRevenue","cogs","operatingExpenses"));
      evidence.put("nodeCount",resolved.orderedNodes().size());
    } else {
      check(p.formCode().equals("C05"),"MG_R04_A05_REQUIRES_C05");
      if("A05_SCHEDULE".equals(family)){
        var view=schedules.readExact(r.organizationId(),r.projectId(),
          r.exactCalculationRevisionId());
        check(view.exactSourceRevisionId().equals(r.exactSourceRevisionId())
            &&view.sourceContentSha256().equals(sourceSha),
          "MG_R04_A05_SCHEDULE_SOURCE_REVISION_DRIFT");
        app=view.appContractRevisionId();
        methodSha=applicationSha(app);
        engineSha=view.engineDescriptorSha256();
        sourceLineageSha=view.resultSha256();
        upstream=view.resultSha256();
        value=decimal(view.result().totalScheduledCash());
        evidence.put("contractRevisionId",view.contractRevisionId().toString());
        evidence.put("calculationRevisionId",view.scheduleRevisionId().toString());
        evidence.put("applicationContractRevisionId",app.toString());
        evidence.put("typedTermsSha256",view.termsSha256());
        evidence.put("upstreamResultSha256",upstream);
        evidence.put("loanMethod",view.result().repaymentMethod());
      } else {
        var view=amendments.readExact(r.organizationId(),r.projectId(),
          r.exactCalculationRevisionId());
        var parent=schedules.readExact(r.organizationId(),r.projectId(),
          view.parentScheduleRevisionId());
        check(view.exactSourceRevisionId().equals(r.exactSourceRevisionId())
            &&view.sourceSha256().equals(sourceSha)
            &&view.parentResultSha256().equals(parent.resultSha256())
            &&parent.exactSourceRevisionId().equals(r.exactSourceRevisionId()),
          "MG_R04_AMENDMENT_PARENT_REVISION_DRIFT");
        app=parent.appContractRevisionId();
        methodSha=applicationSha(app);
        engineSha=view.engineDescriptorSha256();
        sourceLineageSha=view.resultSha256();
        upstream=view.resultSha256();
        value=decimal(view.result().totalCashDue());
        evidence.put("calculationRevisionId",view.amendmentRevisionId().toString());
        evidence.put("parentScheduleRevisionId",view.parentScheduleRevisionId().toString());
        evidence.put("parentResultSha256",view.parentResultSha256());
        evidence.put("amendmentEventSha256",view.eventSha256());
        evidence.put("applicationContractRevisionId",app.toString());
        evidence.put("typedTermsSha256",view.termsSha256());
        evidence.put("upstreamResultSha256",upstream);
      }
      valueState="PRESENT";unit="KZT";error=null;
      graphSha=sha("FIN-MG-R04-A05-GRAPH-NODE-V1|"+family+"|"+
        r.exactSourceRevisionId()+"|"+r.exactCalculationRevisionId()+"|"+
        app+"|"+methodSha+"|"+upstream);
      evidence.put("dagSha256",graphSha);
      evidence.put("methodBindingSha256",methodSha);
      evidence.put("engineMethodSha256",engineSha);
    }
    var key=new StringBuilder("FIN-MG-R04-IMMUTABLE-UAT-PLAN-V1|")
      .append(r.organizationId()).append('|').append(r.projectId())
      .append('|').append(r.targetMetricCode()).append('|').append(family)
      .append('|').append(r.exactSourceRevisionId())
      .append('|').append(r.exactMethodRevisionId())
      .append('|').append(r.exactCalculationRevisionId())
      .append('|').append(app).append('|').append(sourceSha)
      .append('|').append(schemaSha).append('|').append(projectionSha)
      .append('|').append(methodSha).append('|').append(graphSha)
      .append('|').append(engineSha).append('|').append(sourceLineageSha)
      .append('|').append(scope.periodType()).append('|').append(scope.periodCode())
      .append('|').append(scope.currencyCode()).append('|')
      .append(scope.reportingBasis()).append('|').append(scope.dataSpace());
    String planSha=sha(key.toString());
    String resultSha=sha("FIN-MG-R04-RESULT-V1|"+planSha+"|"+
      r.targetMetricCode()+"|"+valueState+"|"+unit+"|"+required(value)+
      "|"+required(error)+"|"+upstream+"|"+sha(toJson(evidence)));
    return new Computed(family,sourceForm,formula,r.exactCalculationRevisionId(),
      app,sourceSha,schemaSha,projectionSha,methodSha,graphSha,engineSha,
      sourceLineageSha,valueState,unit,value,error,upstream,
      Map.copyOf(evidence),planSha,resultSha);
  }

  private static PlanRow planRow(java.sql.ResultSet r,int i)throws java.sql.SQLException {
    return new PlanRow(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
      r.getObject(3,UUID.class),r.getString(4),r.getString(5),r.getString(6),
      r.getObject(7,UUID.class),r.getObject(8,UUID.class),
      r.getObject(9,UUID.class),r.getObject(10,UUID.class),
      r.getObject(11,UUID.class),r.getString(12).trim(),r.getString(13).trim(),
      r.getString(14).trim(),r.getString(15).trim(),r.getString(16).trim(),
      r.getString(17).trim(),r.getString(18).trim(),r.getString(19),
      r.getString(20),r.getString(21),r.getString(22),r.getString(23),
      r.getString(24).trim(),r.getString(25));
  }
  private PlanRow loadPlan(UUID org,UUID project,UUID id){
    var found=db.query("""
      SELECT id,organization_id,project_id,target_metric_code,execution_family,
        source_form_code,exact_source_revision_id,formula_revision_id,
        schedule_revision_id,amendment_revision_id,application_contract_revision_id,
        source_content_sha256,source_schema_sha256,source_projection_sha256,
        method_binding_sha256,graph_sha256,engine_sha256,plan_sha256,
        period_type,period_code,currency_code,reporting_basis,data_space,
        source_lineage_sha256,state
      FROM fin_calc.mg_r04_uat_plan
      WHERE id=? AND organization_id=? AND project_id=?
      """,MetricGraphUatExecutionService::planRow,id,org,project);
    if(found.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
      "MG_R04_EXACT_PLAN_NOT_FOUND");
    return found.getFirst();
  }
  private static ResultRow resultRow(java.sql.ResultSet r,int i)throws java.sql.SQLException{
    return new ResultRow(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
      r.getString(3),r.getString(4),r.getString(5),r.getString(6),
      r.getString(7),r.getString(8).trim(),r.getString(9).trim(),
      r.getString(10),r.getString(11));
  }
  private ResultRow loadResult(UUID planId){
    var results=db.query("""
      SELECT id,plan_id,target_metric_code,value_state,value_unit,
        value_text,error_code,upstream_result_sha256,result_sha256,
        evidence_jsonb::text,state
      FROM fin_calc.mg_r04_uat_result WHERE plan_id=?
      """,MetricGraphUatExecutionService::resultRow,planId);
    if(results.size()!=1)
      throw new IllegalStateException("MG_R04_IMMUTABLE_RESULT_MISSING");
    return results.getFirst();
  }
  private static void consistent(PlanRow p,Computed v){
    if(!"UAT_ONLY_NOT_S2".equals(p.state())
      ||!p.family().equals(v.family())||!p.sourceForm().equals(v.sourceForm())
      ||!Objects.equals(p.formula(),v.formula())
      ||!p.sourceSha().equals(v.sourceSha())
      ||!p.schemaSha().equals(v.schemaSha())
      ||!p.projectionSha().equals(v.projectionSha())
      ||!p.methodSha().equals(v.methodSha())
      ||!p.graphSha().equals(v.graphSha())
      ||!p.engineSha().equals(v.engineSha())
      ||!p.planSha().equals(v.planSha())
      ||!p.lineageSha().equals(v.lineageSha()))
      throw new IllegalStateException("MG_R04_PLAN_SHA_OR_METHOD_DRIFT");
  }

  @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
  public Receipt execute(Request request,String idempotencyKey) {
    validate(request);
    check(idempotencyKey!=null&&!idempotencyKey.isBlank()
      &&idempotencyKey.length()<=200,
      "MG_R04_IDEMPOTENCY_KEY_REQUIRED");
    String commandSha=sha("FIN-MG-R04-COMMAND-V1|"+request.organizationId()+
      "|"+request.projectId()+"|"+request.targetMetricCode()+"|"+
      request.exactSourceRevisionId()+"|"+request.exactMethodRevisionId()+"|"+
      request.exactCalculationRevisionId()+"|"+request.expectedPeriodType()+
      "|"+request.expectedPeriodCode()+"|"+request.expectedCurrency()+"|"+
      request.expectedReportingBasis()+"|"+request.expectedDataSpace());
    // Serialize retries on exact organization and key, not on a tenant-wide mutex.
    lock("MG-R04-KEY|"+request.organizationId()+"|"+idempotencyKey);
    var old=db.query("""
      SELECT plan_id,command_sha256
      FROM fin_calc.mg_r04_uat_command_receipt
      WHERE organization_id=? AND idempotency_key=?
      """,(r,i)->new String[]{r.getString(1),r.getString(2).trim()},
      request.organizationId(),idempotencyKey);
    if(!old.isEmpty()){
      if(!commandSha.equals(old.getFirst()[1]))
        throw new ResponseStatusException(HttpStatus.CONFLICT,
          "MG_R04_IDEMPOTENCY_KEY_REUSED");
      return readExact(request.organizationId(),request.projectId(),
        UUID.fromString(old.getFirst()[0]),"IDEMPOTENT_REPLAY");
    }
    var value=compute(request);
    // Second lock closes same-plan/different-idempotency-key insert races.
    lock("MG-R04-PLAN|"+request.organizationId()+"|"+value.planSha());
    UUID planId=UUID.randomUUID();
    int created=db.update("""
      INSERT INTO fin_calc.mg_r04_uat_plan(
        id,organization_id,project_id,target_metric_code,execution_family,
        source_form_code,exact_source_revision_id,formula_revision_id,
        schedule_revision_id,amendment_revision_id,application_contract_revision_id,
        source_content_sha256,source_schema_sha256,source_projection_sha256,
        method_binding_sha256,graph_sha256,engine_sha256,plan_sha256,
        period_type,period_code,currency_code,reporting_basis,data_space,
        source_lineage_sha256)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(organization_id,plan_sha256) DO NOTHING
      """,planId,request.organizationId(),request.projectId(),
      request.targetMetricCode(),value.family(),value.sourceForm(),
      request.exactSourceRevisionId(),value.formula(),
      "A05_SCHEDULE".equals(value.family())?value.calculation():null,
      "A05_AMENDMENT".equals(value.family())?value.calculation():null,
      value.application(),value.sourceSha(),value.schemaSha(),
      value.projectionSha(),value.methodSha(),value.graphSha(),
      value.engineSha(),value.planSha(),request.expectedPeriodType(),
      request.expectedPeriodCode(),request.expectedCurrency(),
      request.expectedReportingBasis(),request.expectedDataSpace(),
      value.lineageSha());
    if(created==0) {
      var found=db.query("""
        SELECT id FROM fin_calc.mg_r04_uat_plan
        WHERE organization_id=? AND plan_sha256=?
        """,(r,i)->r.getObject(1,UUID.class),
        request.organizationId(),value.planSha());
      if(found.size()!=1)throw new IllegalStateException("MG_R04_PLAN_CONFLICT");
      planId=found.getFirst();
    }
    var stored=loadPlan(request.organizationId(),request.projectId(),planId);
    consistent(stored,value);
    UUID resultId=UUID.randomUUID();
    db.update("""
      INSERT INTO fin_calc.mg_r04_uat_result(
        id,plan_id,organization_id,project_id,target_metric_code,
        value_state,value_unit,value_text,error_code,
        upstream_result_sha256,result_sha256,evidence_jsonb)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
      ON CONFLICT(plan_id) DO NOTHING
      """,resultId,planId,request.organizationId(),request.projectId(),
      request.targetMetricCode(),value.valueState(),value.unit(),value.value(),
      value.error(),value.upstreamSha(),value.resultSha(),toJson(value.evidence()));
    var persisted=loadResult(planId);
    consistentResult(persisted,stored,value);
    db.update("""
      INSERT INTO fin_calc.mg_r04_uat_command_receipt(
        id,organization_id,idempotency_key,command_sha256,plan_id,result_id)
      VALUES(?,?,?,?,?,?)
      """,UUID.randomUUID(),request.organizationId(),idempotencyKey,
      commandSha,planId,persisted.id());
    return readExact(request.organizationId(),request.projectId(),planId,
      created==1?"UAT_COMMITTED":"EXISTING_PLAN_REPLAY");
  }
  private void lock(String key){
    db.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
      (r,i)->r.getString(1),key);
  }
  private void consistentResult(ResultRow row,PlanRow plan,Computed computed){
    if(!row.plan().equals(plan.id())
        ||!row.metric().equals(plan.metric())
        ||!row.state().equals(computed.valueState())
        ||!row.unit().equals(computed.unit())
        ||!Objects.equals(row.value(),computed.value())
        ||!Objects.equals(row.error(),computed.error())
        ||!row.upstreamSha().equals(computed.upstreamSha())
        ||!row.resultSha().equals(computed.resultSha())
        ||!"UAT_UNQUALIFIED".equals(row.admission()))
      throw new IllegalStateException("MG_R04_RESULT_VALUE_OR_SHA_DRIFT");
    try{
      if(!json.readTree(row.evidenceJson())
          .equals(json.readTree(toJson(computed.evidence()))))
        throw new IllegalStateException("MG_R04_RESULT_EVIDENCE_DRIFT");
    }catch(JsonProcessingException e){
      throw new IllegalStateException("MG_R04_RESULT_EVIDENCE_NOT_JSON",e);
    }
  }
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Receipt readExact(UUID organizationId,UUID projectId,UUID planId){
    return readExact(organizationId,projectId,planId,"READ_ONLY");
  }
  private Receipt readExact(UUID org,UUID project,UUID planId,String action){
    if(org==null||project==null||planId==null)
      throw bad("MG_R04_EXACT_RESULT_SCOPE_REQUIRED");
    PlanRow p=loadPlan(org,project,planId);
    UUID calc="A05_SCHEDULE".equals(p.family())?p.schedule():p.amendment();
    var request=new Request(org,project,p.metric(),p.source(),
      p.formula(),calc,p.periodType(),p.periodCode(),p.currency(),
      p.basis(),p.dataSpace());
    var verified=compute(request);
    consistent(p,verified);
    var result=loadResult(p.id());
    consistentResult(result,p,verified);
    return new Receipt(p.id(),result.id(),p.metric(),p.family(),p.source(),
      p.formula(),calc,p.planSha(),result.resultSha(),p.graphSha(),
      p.sourceSha(),p.methodSha(),result.state(),result.unit(),
      result.value(),result.error(),STATE,action,verified.evidence(),
      false,false);
  }
}
