package kz.fin.formlib.calc.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.loan.LoanContractIntakeService;
import kz.fin.formlib.calc.loan.LoanScheduleService;
import kz.fin.formlib.calc.loan.LoanAmendmentService;
import kz.fin.formlib.calc.loan.LoanAmendmentCalculator;
import kz.fin.formlib.service.FormDraftService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MG-R0.3 source availability is exact-source-only and NO S2. */
@SpringBootTest(properties={
 "fin.uat.editor.enabled=false","fin.calc.a05-intake-uat.enabled=true",
 "fin.calc.metric-source-uat.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class MetricSourceAvailabilityPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
    new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_mg_r03").withUsername("fin").withPassword("test_only");
  @DynamicPropertySource static void db(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired MetricSourceAvailabilityService resolver;
  @Autowired MetricIndicatorBindingService bindings;
  @Autowired LoanContractIntakeService loan;
  @Autowired LoanScheduleService schedules;
  @Autowired LoanAmendmentService amendments;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private static MetricSourceAvailabilityService.Request request(UUID binding,
      UUID org,UUID project,Map<String,UUID> sources){
    return new MetricSourceAvailabilityService.Request(binding,org,project,
      sources,"M","2026-11","KZT","MANAGEMENT","SYNTHETIC");
  }
  private static UUID org(){return UUID.randomUUID();}
  private void noFinanceWrites(){
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_fact.fact_set",Integer.class));
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
  }

  @Test void missingF03CashEndSourceIsExplicitAndCannotBeFilledByLatest(){
    UUID binding=bindings.variants("BANK_FINANCING","CASH_END").getFirst().id();
    var out=resolver.resolve(request(binding,org(),org(),Map.of()));
    assertEquals("REQUIRED_INPUTS_UNRESOLVED",out.overallState());
    assertEquals(1,out.inputs().size());
    assertEquals("MISSING_SOURCE",out.inputs().getFirst().state());
    assertEquals("F03",out.inputs().getFirst().formCode());
    assertEquals("closingCash",out.inputs().getFirst().fieldCode());
    assertNull(out.inputs().getFirst().typedValue());
    assertFalse(out.executable());
    assertFalse(out.financiallyQualified());
    noFinanceWrites();
  }
  @Test void derivedNetProfitNeverInventsPhysicalInputOrExecution(){
    UUID binding=bindings.variants("BANK_FINANCING","NET_PROFIT").getFirst().id();
    var out=resolver.resolve(request(binding,org(),org(),Map.of()));
    assertEquals("METHOD_OR_METRIC_NOT_AUTHORED",out.overallState());
    assertEquals("DERIVED_METHOD_NOT_AUTHORED",out.bindingState());
    assertTrue(out.inputs().isEmpty());
    noFinanceWrites();
  }
  @Test void unexpectedSourceAndUnknownExactRevisionFailClosed(){
    UUID binding=bindings.variants("BANK_FINANCING","CASH_END").getFirst().id();
    var unrelated=request(binding,org(),org(),Map.of("C05",UUID.randomUUID()));
    assertThrows(ResponseStatusException.class,()->resolver.resolve(unrelated));
    var nonexistent=request(binding,org(),org(),Map.of("F03",UUID.randomUUID()));
    assertThrows(RuntimeException.class,()->resolver.resolve(nonexistent));
    noFinanceWrites();
  }
  @Test void exactA05ScheduleProjectsReadOnlyVerifiedGraphNode() throws Exception {
    var organization=org();var entity=org();var business=org();var project=org();
    var begin=LocalDate.parse("2027-01-01");
    var maturity=LocalDate.parse("2027-03-01");
    var t=new LoanContractIntakeService.Terms(
      "LOAN-MG03","AGR-MG03","BANK-MG03",
      begin,begin,begin,maturity,new BigDecimal("1000.00"),
      "KZ","BORROWER","EQUAL_PRINCIPAL","MONTHLY",1,
      "SIMPLE","ACT_365_FIXED","NONE",2,"HALF_EVEN",
      "ADJUST_LAST_PAYMENT","FIXED",BigDecimal.ZERO);
    var draft=new FormDraftService.DraftRequest(
      null,organization,entity,business,project,
      "C05","M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT",
      "SYNTHETIC",0,Map.of(),List.of(new FormDraftService.RowInput(
        UUID.randomUUID(),1,Map.of(
          "creditor","BANK-MG03","agreementRef","AGR-MG03",
          "currency","KZT","outstanding","1000","interestRate","0",
          "rateType","FIXED","dueAt","2027-03-01","collateralRef","NONE"))),
      "mg-r03-uat","pinned A05 graph node");
    var saved=loan.save(new LoanContractIntakeService.Command(draft,t,List.of()),
      "mg-r03-loan-"+UUID.randomUUID());
    var schedule=schedules.calculateAndCommit(
      new LoanScheduleService.ScheduleCommand(
        organization,project,saved.sourceRevisionId(),List.of()),
      "mg-r03-schedule-"+UUID.randomUUID());
    var node=resolver.a05Exact(organization,project,schedule.scheduleRevisionId());
    assertEquals("SPECIALIZED_CALC_RESULT",node.nodeKind());
    assertEquals("CALC-A05.CONTRACT_CASH",node.metricCode());
    assertEquals(saved.sourceRevisionId(),node.sourceRevisionId());
    assertEquals(schedule.resultSha256(),node.resultSha256());
    assertEquals(schedule.sourceContentSha256(),node.sourceSha256());
    assertEquals(2,node.dependencies().size());
    assertFalse(node.executable()); assertFalse(node.financiallyQualified());
    mvc.perform(get("/api/v1/metric-graph/source-availability/a05/"+
          schedule.scheduleRevisionId())
        .param("organizationId",organization.toString())
        .param("projectId",project.toString()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.resultSha256").value(schedule.resultSha256()))
      .andExpect(jsonPath("$.financiallyQualified").value(false));
    assertThrows(ResponseStatusException.class,()->resolver.a05Exact(
      organization,org(),schedule.scheduleRevisionId()));
    var amendment=amendments.calculateAndCommit(new LoanAmendmentService.Command(
      organization,project,schedule.scheduleRevisionId(),
      new LoanAmendmentCalculator.Event("PREPAYMENT",
        LocalDate.parse("2027-02-01"),new BigDecimal("400"),"REDUCE_TERM",
        null,null,null)), "mg-r03-amendment-"+UUID.randomUUID());
    var child=resolver.a05AmendmentExact(organization,project,
      amendment.amendmentRevisionId());
    assertEquals("CALC-A05.AMENDED_CONTRACT_CASH",child.metricCode());
    assertEquals(schedule.scheduleRevisionId(),child.parentScheduleRevisionId());
    assertEquals(schedule.resultSha256(),child.parentResultSha256());
    assertEquals(amendment.resultSha256(),child.resultSha256());
    assertEquals(3,child.dependencies().size());
    assertFalse(child.financiallyQualified());
    mvc.perform(get("/api/v1/metric-graph/source-availability/a05/amendments/"+
        amendment.amendmentRevisionId())
        .param("organizationId",organization.toString())
        .param("projectId",project.toString()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.parentResultSha256").value(schedule.resultSha256()))
      .andExpect(jsonPath("$.financiallyQualified").value(false));
    assertThrows(ResponseStatusException.class,()->resolver.a05AmendmentExact(
      org(),project,amendment.amendmentRevisionId()));
    noFinanceWrites();
  }
  @Test void readOnlyHttpPreviewNeverClaimsS2() throws Exception {
    var binding=bindings.variants("BANK_FINANCING","CASH_END").getFirst().id();
    var data=request(binding,org(),org(),Map.of());
    mvc.perform(post("/api/v1/metric-graph/source-availability/resolve")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsBytes(data)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.overallState").value("REQUIRED_INPUTS_UNRESOLVED"))
      .andExpect(jsonPath("$.inputs[0].state").value("MISSING_SOURCE"))
      .andExpect(jsonPath("$.financiallyQualified").value(false));
    noFinanceWrites();
  }
}
