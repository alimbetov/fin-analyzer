package kz.fin.formlib.calc.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.loan.LoanAmendmentCalculator;
import kz.fin.formlib.calc.loan.LoanAmendmentService;
import kz.fin.formlib.calc.loan.LoanContractIntakeService;
import kz.fin.formlib.calc.loan.LoanScheduleService;
import kz.fin.formlib.service.FormDraftService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MG-R0.4: real PostgreSQL 16, exact saved C05/F02 and existing kernels. */
@SpringBootTest(properties={
  "fin.uat.editor.enabled=false",
  "fin.calc.intake-uat.enabled=true",
  "fin.calc.a05-intake-uat.enabled=true",
  "fin.calc.mg-r04-uat.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class MetricGraphUatExecutionPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
    new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_mg_r04")
      .withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void database(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired FormDraftService drafts;
  @Autowired LoanContractIntakeService loans;
  @Autowired LoanScheduleService loanSchedules;
  @Autowired LoanAmendmentService loanAmendments;
  @Autowired MetricGraphUatExecutionService mg;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private record Scope(UUID org,UUID entity,UUID business,UUID project){
    static Scope fresh(){return new Scope(UUID.randomUUID(),UUID.randomUUID(),
      UUID.randomUUID(),UUID.randomUUID());}
  }
  private static BigDecimal n(String s){return new BigDecimal(s);}
  private static LocalDate date(String s){return LocalDate.parse(s);}

  private UUID method(String output){
    return db.queryForObject("""
      SELECT id FROM fin_calc.formula_contract_revision
      WHERE output_metric_code=? AND formula_version='F02_TEST_ONLY_V1'
      """,UUID.class,output);
  }
  private FormDraftService.DraftReceipt f02(Scope scope,UUID instance,int expected,
      String revenue,String cost,String opex) {
    return drafts.save(new FormDraftService.DraftRequest(
      instance,scope.org(),scope.entity(),scope.business(),scope.project(),
      "F02","M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
      expected,Map.of("netRevenue",revenue,"cogs",cost,
        "operatingExpenses",opex),List.of(),
      "mg-r04-uat","Exact F02 graph metric source"),
      "mg-r04-f02-"+UUID.randomUUID());
  }
  private MetricGraphUatExecutionService.Request f02Request(Scope s,
      UUID sourceId,String metric){
    return new MetricGraphUatExecutionService.Request(s.org(),s.project(),
      metric,sourceId,method(metric),null,
      "M","2026-11","KZT","MANAGEMENT","SYNTHETIC");
  }
  private MetricGraphUatExecutionService.Request a05Request(Scope s,
      UUID sourceId,String metric,UUID calculation){
    return new MetricGraphUatExecutionService.Request(s.org(),s.project(),
      metric,sourceId,null,calculation,"M","2026-11","KZT",
      "MANAGEMENT","SYNTHETIC");
  }
  private record LoanSource(Scope scope,LoanContractIntakeService.Receipt loan,
      LoanScheduleService.ScheduleView base) {}
  private LoanSource a05(){
    Scope s=Scope.fresh();
    LocalDate start=date("2027-01-01"),end=date("2027-03-01");
    var terms=new LoanContractIntakeService.Terms(
      "LOAN-"+UUID.randomUUID(),"AGR-"+UUID.randomUUID(),"BANK-1",
      start,start,start,end,n("1000.00"),
      "KZ","BORROWER","EQUAL_PRINCIPAL","MONTHLY",1,"SIMPLE",
      "ACT_365_FIXED","NONE",2,"HALF_EVEN","ADJUST_LAST_PAYMENT",
      "FIXED",n("0"));
    var source=new FormDraftService.DraftRequest(
      null,s.org(),s.entity(),s.business(),s.project(),"C05",
      "M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
      0,Map.of(),List.of(new FormDraftService.RowInput(UUID.randomUUID(),1,
        Map.of("creditor","BANK-1","agreementRef",terms.agreementRef(),
          "currency","KZT","outstanding","1000","interestRate","0",
          "rateType","FIXED","dueAt","2027-03-01","collateralRef","NONE"))),
      "mg-r04-test","Exact C05 typed loan");
    var receipt=loans.save(new LoanContractIntakeService.Command(
      source,terms,List.of()),"mg-r04-loan-"+UUID.randomUUID());
    var baseline=loanSchedules.calculateAndCommit(
      new LoanScheduleService.ScheduleCommand(s.org(),s.project(),
        receipt.sourceRevisionId(),List.of()),"mg-r04-cash-"+UUID.randomUUID());
    return new LoanSource(s,receipt,baseline);
  }
  private void noProductionWrites(){
    assertEquals(0,db.queryForObject(
      "SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
    assertEquals(0,db.queryForObject(
      "SELECT count(*) FROM fin_calc.calc_job",Integer.class));
    assertEquals(0,db.queryForObject(
      "SELECT count(*) FROM fin_calc.calc_result",Integer.class));
    assertEquals(0,db.queryForObject(
      "SELECT count(*) FROM fin_fact.fact_set",Integer.class));
  }

  @Test void f02MetricCompilesActualDagAndPersists100KztWithPinnedLineage(){
    Scope s=Scope.fresh();
    var draft=f02(s,null,0,"200","100","50");
    var command=f02Request(s,draft.revisionId(),"F02.grossProfit");
    var result=mg.execute(command,"mg-r04-first-"+UUID.randomUUID());
    assertEquals("UAT_COMMITTED",result.commandResult());
    assertEquals("F02_TYPED_DAG",result.executionFamily());
    assertEquals(command.exactMethodRevisionId(),result.formulaRevisionId());
    assertEquals("PRESENT",result.valueState());
    assertEquals("100.00",result.valueText());
    assertEquals("KZT",result.valueUnit());
    assertEquals(draft.contentSha256(),result.sourceSha256());
    assertEquals(64,result.planSha256().length());
    assertEquals(64,result.graphSha256().length());
    assertEquals(64,result.methodBindingSha256().length());
    assertEquals(64,result.resultSha256().length());
    assertEquals("UAT_UNQUALIFIED",result.state());
    assertFalse(result.financiallyApproved());
    assertFalse(result.productionAdmitted());
    assertEquals(1,db.queryForObject(
      "SELECT count(*) FROM fin_calc.mg_r04_uat_plan WHERE id=?",
      Integer.class,result.planId()));
    assertEquals(result.resultSha256(),mg.readExact(s.org(),s.project(),
      result.planId()).resultSha256());
    noProductionWrites();
  }

  @Test void grossMarginAndOperatingResultExecuteExistingThreeInputF02Kernel(){
    var s=Scope.fresh();
    var rev=f02(s,null,0,"200","100","50");
    var margin=mg.execute(f02Request(s,rev.revisionId(),"F02.grossMargin"),
      "mg-r04-margin-"+UUID.randomUUID());
    var op=mg.execute(f02Request(s,rev.revisionId(),"F02.operatingResult"),
      "mg-r04-operating-"+UUID.randomUUID());
    assertEquals("0.50000000",margin.valueText());
    assertEquals("RATIO",margin.valueUnit());
    assertEquals("50.00",op.valueText());
    assertNotEquals(margin.graphSha256(),op.graphSha256());
    assertEquals("F02_TYPED_DAG",op.executionFamily());
    assertEquals("100.00",mg.execute(
      f02Request(s,rev.revisionId(),"F02.grossProfit"),
      "mg-r04-profit-"+UUID.randomUUID()).valueText());
    noProductionWrites();
  }

  @Test void zeroRevenueYieldsExplicitInvalidRatioNotFakeZero(){
    var s=Scope.fresh();
    var rev=f02(s,null,0,"0","0","0");
    var result=mg.execute(f02Request(s,rev.revisionId(),"F02.grossMargin"),
      "mg-r04-zero-"+UUID.randomUUID());
    assertEquals("INVALID",result.valueState());
    assertNull(result.valueText());
    assertEquals("DIVISION_BY_ZERO",result.errorCode());
    assertEquals("INVALID",mg.readExact(s.org(),s.project(),
      result.planId()).valueState());
    noProductionWrites();
  }

  @Test void sameKeyReplayAndNewKeySamePlanStayIdempotent(){
    var s=Scope.fresh();
    var rev=f02(s,null,0,"200","100","50");
    var command=f02Request(s,rev.revisionId(),"F02.grossProfit");
    String key="mg-r04-idem-"+UUID.randomUUID();
    var first=mg.execute(command,key);
    var again=mg.execute(command,key);
    assertEquals("IDEMPOTENT_REPLAY",again.commandResult());
    assertEquals(first.planId(),again.planId());
    assertEquals(first.resultId(),again.resultId());
    var other=mg.execute(command,"mg-r04-other-"+UUID.randomUUID());
    assertEquals("EXISTING_PLAN_REPLAY",other.commandResult());
    assertEquals(first.planId(),other.planId());
    assertEquals(1,db.queryForObject("""
      SELECT count(*) FROM fin_calc.mg_r04_uat_result WHERE plan_id=?
      """,Integer.class,first.planId()));
    assertEquals(2,db.queryForObject("""
      SELECT count(*) FROM fin_calc.mg_r04_uat_command_receipt WHERE plan_id=?
      """,Integer.class,first.planId()));
  }

  @Test void changedExactF02RevisionCreatesAnotherImmutablePlanAndReplaysOld(){
    var s=Scope.fresh();
    var first=f02(s,null,0,"200","100","50");
    var old=mg.execute(f02Request(s,first.revisionId(),"F02.grossProfit"),
      "mg-r04-old-"+UUID.randomUUID());
    var next=f02(s,first.instanceId(),1,"300","100","50");
    var updated=mg.execute(f02Request(s,next.revisionId(),"F02.grossProfit"),
      "mg-r04-new-"+UUID.randomUUID());
    assertNotEquals(old.planId(),updated.planId());
    assertNotEquals(old.planSha256(),updated.planSha256());
    assertEquals("100.00",mg.readExact(s.org(),s.project(),old.planId()).valueText());
    assertEquals("200.00",mg.readExact(s.org(),s.project(),updated.planId()).valueText());
    noProductionWrites();
  }

  @Test void wrongScopeFormulaMetricOrUnsupportedEditorialCannotExecute(){
    var s=Scope.fresh();
    var f=f02(s,null,0,"200","100","50");
    var target=f02Request(s,f.revisionId(),"F02.grossProfit");
    assertThrows(RuntimeException.class,()->mg.execute(
      new MetricGraphUatExecutionService.Request(UUID.randomUUID(),s.project(),
        target.targetMetricCode(),target.exactSourceRevisionId(),
        target.exactMethodRevisionId(),null,"M","2026-11","KZT",
        "MANAGEMENT","SYNTHETIC"),"wrong-org-"+UUID.randomUUID()));
    assertThrows(RuntimeException.class,()->mg.execute(
      new MetricGraphUatExecutionService.Request(s.org(),UUID.randomUUID(),
        target.targetMetricCode(),target.exactSourceRevisionId(),
        target.exactMethodRevisionId(),null,"M","2026-11","KZT",
        "MANAGEMENT","SYNTHETIC"),"wrong-project-"+UUID.randomUUID()));
    var wrong=new MetricGraphUatExecutionService.Request(
      s.org(),s.project(),"F02.grossProfit",f.revisionId(),
      method("F02.grossMargin"),null,
      "M","2026-11","KZT","MANAGEMENT","SYNTHETIC");
    assertThrows(RuntimeException.class,()->mg.execute(wrong,
      "wrong-method-"+UUID.randomUUID()));
    var unsupported=new MetricGraphUatExecutionService.Request(
      s.org(),s.project(),"NET_PROFIT",f.revisionId(),null,null,
      "M","2026-11","KZT","MANAGEMENT","SYNTHETIC");
    assertThrows(ResponseStatusException.class,()->mg.execute(unsupported,
      "unqualified-netprofit-"+UUID.randomUUID()));
    noProductionWrites();
  }

  @Test void periodMismatchAndIdempotencyKeyMutationFailWithZeroPhantoms(){
    var s=Scope.fresh();var f=f02(s,null,0,"200","100","50");
    var r=f02Request(s,f.revisionId(),"F02.grossProfit");
    var wrong=new MetricGraphUatExecutionService.Request(s.org(),s.project(),
      r.targetMetricCode(),r.exactSourceRevisionId(),r.exactMethodRevisionId(),
      null,"M","2026-10","KZT","MANAGEMENT","SYNTHETIC");
    assertThrows(ResponseStatusException.class,()->mg.execute(
      wrong,"wrong-month-"+UUID.randomUUID()));
    String key="mg-r04-same-"+UUID.randomUUID();
    var ok=mg.execute(r,key);
    var other=new MetricGraphUatExecutionService.Request(
      s.org(),s.project(),"F02.grossMargin",f.revisionId(),
      method("F02.grossMargin"),null,
      "M","2026-11","KZT","MANAGEMENT","SYNTHETIC");
    var conflict=assertThrows(ResponseStatusException.class,()->
      mg.execute(other,key));
    assertEquals(409,conflict.getStatusCode().value());
    assertEquals(1,db.queryForObject("""
      SELECT count(*) FROM fin_calc.mg_r04_uat_plan WHERE organization_id=?
      """,Integer.class,s.org()));
    assertEquals(ok.planId(),mg.readExact(s.org(),s.project(),ok.planId()).planId());
  }

  @Test void appA05ScheduleAndAmendedCashAreAttachedAsExactMetricNodes(){
    var s=a05();
    var base=mg.execute(a05Request(s.scope(),s.loan().sourceRevisionId(),
      "CALC-A05.CONTRACT_CASH",s.base().scheduleRevisionId()),
      "mg-r04-base-"+UUID.randomUUID());
    assertEquals("A05_SCHEDULE",base.executionFamily());
    assertEquals("1000",base.valueText());
    assertEquals(s.base().resultSha256(),
      base.lineage().get("upstreamResultSha256"));
    var change=loanAmendments.calculateAndCommit(
      new LoanAmendmentService.Command(s.scope().org(),s.scope().project(),
        s.base().scheduleRevisionId(),
        new LoanAmendmentCalculator.Event("PREPAYMENT",date("2027-02-01"),
          n("100.00"),"REDUCE_INSTALLMENT",null,null,null)),
      "mg-r04-amend-"+UUID.randomUUID());
    var modified=mg.execute(a05Request(s.scope(),s.loan().sourceRevisionId(),
      "CALC-A05.AMENDED_CONTRACT_CASH",change.amendmentRevisionId()),
      "mg-r04-modified-"+UUID.randomUUID());
    assertEquals("A05_AMENDMENT",modified.executionFamily());
    assertEquals("1000",modified.valueText());
    assertEquals(s.base().resultSha256(),
      modified.lineage().get("parentResultSha256"));
    assertNotEquals(base.planSha256(),modified.planSha256());
    assertEquals(modified.resultSha256(),mg.readExact(s.scope().org(),
      s.scope().project(),modified.planId()).resultSha256());
    noProductionWrites();
  }

  @Test void forgedResultAndPlanMutationBlockedByPostgresAndRehash(){
    var s=Scope.fresh();
    var f=f02(s,null,0,"200","100","50");
    var receipt=mg.execute(f02Request(s,f.revisionId(),"F02.grossProfit"),
      "mg-r04-tamper-"+UUID.randomUUID());
    assertThrows(DataAccessException.class,()->db.update("""
      UPDATE fin_calc.mg_r04_uat_result SET value_text='999.00' WHERE id=?
      """,receipt.resultId()));
    assertThrows(DataAccessException.class,()->db.update("""
      UPDATE fin_calc.mg_r04_uat_plan SET method_binding_sha256=? WHERE id=?
      ""","a".repeat(64),receipt.planId()));
    assertThrows(DataAccessException.class,()->db.update("""
      DELETE FROM fin_calc.mg_r04_uat_command_receipt WHERE plan_id=?
      """,receipt.planId()));
    assertEquals("100.00",mg.readExact(s.org(),s.project(),receipt.planId()).valueText());
  }

  @Test void restUsesExactPlanAndDisclosesOnlyUatExecution() throws Exception{
    var s=Scope.fresh();
    var f=f02(s,null,0,"200","100","50");
    var req=f02Request(s,f.revisionId(),"F02.grossProfit");
    var response=mvc.perform(post("/api/v1/uat/metric-graph/execute")
      .header("Idempotency-Key","http-mg-r04-"+UUID.randomUUID())
      .contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsBytes(req)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.valueText").value("100.00"))
      .andExpect(jsonPath("$.financiallyApproved").value(false))
      .andExpect(jsonPath("$.productionAdmitted").value(false))
      .andReturn().getResponse().getContentAsByteArray();
    var tree=json.readTree(response);
    String planId=tree.path("planId").asText();
    mvc.perform(get("/api/v1/uat/metric-graph/plans/"+planId)
      .param("organizationId",s.org().toString())
      .param("projectId",s.project().toString()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.planSha256").value(tree.path("planSha256").asText()))
      .andExpect(jsonPath("$.valueText").value("100.00"));
    mvc.perform(get("/api/v1/uat/metric-graph/plans/"+planId)
      .param("organizationId",UUID.randomUUID().toString())
      .param("projectId",s.project().toString()))
      .andExpect(status().isNotFound());
  }

  @Test void directSqlCannotForgeSourceTenantOrUnpinnedFormulaSha(){
    var s=Scope.fresh();
    var f=f02(s,null,0,"200","100","50");
    var saved=mg.execute(f02Request(s,f.revisionId(),"F02.grossProfit"),
      "mg-r04-owner-guard-"+UUID.randomUUID());
    // Even a JDBC writer bypassing the Java service cannot pin the exact
    // source revision into a different organization.
    assertThrows(DataAccessException.class,()->db.update("""
      INSERT INTO fin_calc.mg_r04_uat_plan(
        id,organization_id,project_id,target_metric_code,execution_family,
        source_form_code,exact_source_revision_id,formula_revision_id,
        schedule_revision_id,amendment_revision_id,application_contract_revision_id,
        source_content_sha256,source_schema_sha256,source_projection_sha256,
        method_binding_sha256,graph_sha256,engine_sha256,plan_sha256,
        period_type,period_code,currency_code,reporting_basis,data_space,
        source_lineage_sha256)
      SELECT ?,?,project_id,target_metric_code,execution_family,source_form_code,
        exact_source_revision_id,formula_revision_id,schedule_revision_id,
        amendment_revision_id,application_contract_revision_id,
        source_content_sha256,source_schema_sha256,source_projection_sha256,
        method_binding_sha256,graph_sha256,engine_sha256,?,
        period_type,period_code,currency_code,reporting_basis,data_space,
        source_lineage_sha256
      FROM fin_calc.mg_r04_uat_plan WHERE id=?
      """,UUID.randomUUID(),UUID.randomUUID(),
      MetricGraphUatExecutionService.sha("MG-FOREIGN-ORG-"+UUID.randomUUID()),
      saved.planId()));
    assertThrows(DataAccessException.class,()->db.update("""
      INSERT INTO fin_calc.mg_r04_uat_plan(
        id,organization_id,project_id,target_metric_code,execution_family,
        source_form_code,exact_source_revision_id,formula_revision_id,
        schedule_revision_id,amendment_revision_id,application_contract_revision_id,
        source_content_sha256,source_schema_sha256,source_projection_sha256,
        method_binding_sha256,graph_sha256,engine_sha256,plan_sha256,
        period_type,period_code,currency_code,reporting_basis,data_space,
        source_lineage_sha256)
      SELECT ?,organization_id,project_id,target_metric_code,execution_family,
        source_form_code,exact_source_revision_id,formula_revision_id,
        schedule_revision_id,amendment_revision_id,application_contract_revision_id,
        source_content_sha256,source_schema_sha256,source_projection_sha256,
        ?,graph_sha256,engine_sha256,?,
        period_type,period_code,currency_code,reporting_basis,data_space,
        source_lineage_sha256
      FROM fin_calc.mg_r04_uat_plan WHERE id=?
      """,UUID.randomUUID(),"a".repeat(64),
      MetricGraphUatExecutionService.sha("MG-FORGED-METHOD-"+UUID.randomUUID()),
      saved.planId()));
    assertEquals(1,db.queryForObject("""
      SELECT count(*) FROM fin_calc.mg_r04_uat_plan WHERE organization_id=?
      """,Integer.class,s.org()));
    assertEquals("100.00",mg.readExact(s.org(),s.project(),saved.planId()).valueText());
  }

  @Test void directSqlCannotCreateForeignOrMismatchedResultReceipt(){
    var s=Scope.fresh();
    var f=f02(s,null,0,"200","100","50");
    var first=mg.execute(f02Request(s,f.revisionId(),"F02.grossProfit"),
      "mg-r04-valid-receipt-"+UUID.randomUUID());
    var margin=mg.execute(f02Request(s,f.revisionId(),"F02.grossMargin"),
      "mg-r04-other-result-"+UUID.randomUUID());
    assertThrows(DataAccessException.class,()->db.update("""
      INSERT INTO fin_calc.mg_r04_uat_command_receipt
       (id,organization_id,idempotency_key,command_sha256,plan_id,result_id)
      VALUES(?,?,?,?,?,?)
      """,UUID.randomUUID(),UUID.randomUUID(),"mg-r04-forged-org",
      "b".repeat(64),first.planId(),first.resultId()));
    assertThrows(DataAccessException.class,()->db.update("""
      INSERT INTO fin_calc.mg_r04_uat_command_receipt
       (id,organization_id,idempotency_key,command_sha256,plan_id,result_id)
      VALUES(?,?,?,?,?,?)
      """,UUID.randomUUID(),s.org(),"mg-r04-forged-result",
      "c".repeat(64),first.planId(),margin.resultId()));
    assertEquals(1,db.queryForObject("""
      SELECT count(*) FROM fin_calc.mg_r04_uat_command_receipt WHERE plan_id=?
      """,Integer.class,first.planId()));
    noProductionWrites();
  }

  @Test void concurrentSameCommandKeyCommitsOnePlanAndOneReceipt() throws Exception{
    var s=Scope.fresh();var f=f02(s,null,0,"200","100","50");
    var request=f02Request(s,f.revisionId(),"F02.grossProfit");
    String key="mg-r04-race-"+UUID.randomUUID();
    try(var pool=Executors.newFixedThreadPool(2)){
      var ready=new CountDownLatch(2);
      var start=new CountDownLatch(1);
      var first=pool.submit(()->{
        ready.countDown();start.await();
        return mg.execute(request,key);
      });
      var second=pool.submit(()->{
        ready.countDown();start.await();
        return mg.execute(request,key);
      });
      assertTrue(ready.await(12,TimeUnit.SECONDS));
      start.countDown();
      var a=first.get(40,TimeUnit.SECONDS);
      var b=second.get(40,TimeUnit.SECONDS);
      assertEquals(a.planId(),b.planId());
      assertEquals(a.resultId(),b.resultId());
      assertEquals(1,db.queryForObject("""
        SELECT count(*) FROM fin_calc.mg_r04_uat_command_receipt
        WHERE organization_id=? AND idempotency_key=?
        """,Integer.class,s.org(),key));
      assertEquals(1,db.queryForObject("""
        SELECT count(*) FROM fin_calc.mg_r04_uat_plan
        WHERE organization_id=?
        """,Integer.class,s.org()));
    }
    noProductionWrites();
  }
}
