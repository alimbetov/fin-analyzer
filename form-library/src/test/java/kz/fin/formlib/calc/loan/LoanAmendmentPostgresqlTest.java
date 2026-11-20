package kz.fin.formlib.calc.loan;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.loan.LoanAmendmentCalculator.Event;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real immutable R1.2 amendment atop actual C05 -> A05-R1.1 PG16 chain. */
@SpringBootTest(properties={
 "fin.uat.editor.enabled=false",
 "fin.calc.a05-intake-uat.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class LoanAmendmentPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
    new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_a05_r12").withUsername("fin").withPassword("test_only");
  @DynamicPropertySource static void datasource(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired LoanContractIntakeService loan;
  @Autowired LoanScheduleService schedules;
  @Autowired LoanAmendmentService amendments;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  private static BigDecimal n(String v){return new BigDecimal(v);}
  private static LocalDate d(String v){return LocalDate.parse(v);}
  private record Scope(UUID org,UUID entity,UUID business,UUID project){
    static Scope fresh(){return new Scope(UUID.randomUUID(),UUID.randomUUID(),
      UUID.randomUUID(),UUID.randomUUID());}
  }
  private record Parent(Scope scope,LoanContractIntakeService.Receipt loan,
      LoanScheduleService.ScheduleView schedule) {}

  private Parent parent(String method,String rate) {
    var s=Scope.fresh();
    var t=new LoanContractIntakeService.Terms(
      "LOAN-"+UUID.randomUUID(),"AGR-"+UUID.randomUUID(),"BANK-01",
      d("2027-01-01"),d("2027-01-01"),d("2027-01-01"),d("2027-05-01"),
      n("1000.00"),"KZ","BORROWER",method,"MONTHLY",1,
      "SIMPLE","ACT_365_FIXED","NONE",2,"HALF_EVEN",
      "ADJUST_LAST_PAYMENT","FIXED",n(rate));
    var req=new FormDraftService.DraftRequest(null,s.org,s.entity,s.business,
      s.project,"C05","M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT",
      "SYNTHETIC",0,Map.of(),List.of(new FormDraftService.RowInput(
        UUID.randomUUID(),1,Map.of("creditor","BANK-01",
          "agreementRef",t.agreementRef(),"currency","KZT",
          "outstanding","1000","interestRate",rate,"rateType","FIXED",
          "dueAt","2027-05-01","collateralRef","NONE"))),
      "a05-r12-test","Amendment scenario input only");
    var receipt=loan.save(new LoanContractIntakeService.Command(
      req,t,List.of()),"a05-r12-loan-"+UUID.randomUUID());
    var first=schedules.calculateAndCommit(new LoanScheduleService.ScheduleCommand(
      s.org,s.project,receipt.sourceRevisionId(),List.of()),
      "a05-r12-base-"+UUID.randomUUID());
    return new Parent(s,receipt,first);
  }
  private static Event prepay(String policy){
    return new Event("PREPAYMENT",d("2027-02-01"),n("400"),
      policy,null,null,null);
  }
  private LoanAmendmentService.Command command(Parent p,Event e) {
    return new LoanAmendmentService.Command(p.scope.org,p.scope.project,
      p.schedule.scheduleRevisionId(),e);
  }
  private LoanAmendmentService.View calc(Parent p,Event e){
    return amendments.calculateAndCommit(command(p,e),
      "a05-r12-"+UUID.randomUUID());
  }
  private void noActualPosts(){
    assertEquals(0,db.queryForObject("SELECT count(*) FROM fin_fact.fact_set",Integer.class));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
  }
  @Test void reduceTermCopiesExactParentAndPreservesOldScheduleSHA(){
    var p=parent("EQUAL_PRINCIPAL","0");
    var oldSha=p.schedule.resultSha256();
    var updated=calc(p,prepay("REDUCE_TERM"));
    assertEquals(p.schedule.scheduleRevisionId(),updated.parentScheduleRevisionId());
    assertEquals(p.loan.sourceRevisionId(),updated.exactSourceRevisionId());
    assertEquals(p.schedule.sourceContentSha256(),updated.sourceSha256());
    assertEquals(oldSha,updated.parentResultSha256());
    assertEquals(d("2027-04-01"),updated.result().actualMaturityDate());
    assertEquals(3,updated.result().lines().size());
    assertEquals(0,updated.result().totalExtraPrincipal().compareTo(n("400")));
    assertEquals(0,updated.result().finalPrincipal().compareTo(BigDecimal.ZERO));
    assertEquals(oldSha,schedules.readExact(p.scope.org,p.scope.project,
      p.schedule.scheduleRevisionId()).resultSha256());
    assertEquals(3,db.queryForObject("""
      SELECT count(*) FROM fin_calc.a05_amendment_line WHERE amendment_revision_id=?
      """,Integer.class,updated.amendmentRevisionId()));
    assertEquals(updated.resultSha256(),amendments.readExact(
      p.scope.org,p.scope.project,updated.amendmentRevisionId()).resultSha256());
    noActualPosts();
  }
  @Test void reduceInstallmentAndReduceTermAreIndependentImmutableVariants(){
    var p=parent("ANNUITY","12");
    var term=calc(p,prepay("REDUCE_TERM"));
    var lower=calc(p,prepay("REDUCE_INSTALLMENT"));
    assertNotEquals(term.amendmentRevisionId(),lower.amendmentRevisionId());
    assertNotEquals(term.resultSha256(),lower.resultSha256());
    assertTrue(term.result().actualMaturityDate().isBefore(
      lower.result().actualMaturityDate()));
    assertEquals(d("2027-05-01"),lower.result().actualMaturityDate());
    assertEquals(2,amendments.list(p.scope.org,p.scope.project,
      p.schedule.scheduleRevisionId()).size());
    assertEquals(term.resultSha256(),amendments.readExact(p.scope.org,
      p.scope.project,term.amendmentRevisionId()).resultSha256());
  }
  @Test void capitalizationAndMidIntervalRateResetPersistWithoutOverwritingC05(){
    var p=parent("EQUAL_PRINCIPAL","12");
    var cap=calc(p,new Event("GRACE",d("2027-02-01"),null,null,
      d("2027-03-01"),"FULL_CAPITALIZE",null));
    assertEquals(0,cap.result().lines().getFirst().cashInterestDue()
      .compareTo(BigDecimal.ZERO));
    assertTrue(cap.result().totalCapitalizedInterest().signum()>0);
    assertEquals(0,cap.result().totalScheduledPrincipal().compareTo(
      n("1000").add(cap.result().totalCapitalizedInterest())));
    var step=calc(p,new Event("RATE_STEP",d("2027-01-15"),null,
      null,null,null,n("24")));
    assertEquals(0,step.result().lines().getFirst().cashInterestDue()
      .compareTo(n("15.78")));
    assertNotEquals(cap.resultSha256(),step.resultSha256());
    assertEquals(p.loan.sourceRevisionId(),
      loan.readExact(p.scope.org,p.scope.project,p.loan.sourceRevisionId())
        .receipt().sourceRevisionId());
    noActualPosts();
  }
  @Test void idempotencyAndVariantReplayNoPhantomAmendment(){
    var p=parent("EQUAL_PRINCIPAL","0");
    var cmd=command(p,prepay("REDUCE_TERM"));
    String key="a05-r12-repeat-"+UUID.randomUUID();
    var initial=amendments.calculateAndCommit(cmd,key);
    var replay=amendments.calculateAndCommit(cmd,key);
    assertEquals("IDEMPOTENT_REPLAY",replay.commandResult());
    assertEquals(initial.amendmentRevisionId(),replay.amendmentRevisionId());
    var different=amendments.calculateAndCommit(cmd,
      "a05-r12-other-"+UUID.randomUUID());
    assertEquals("EXISTING_VARIANT_REPLAY",different.commandResult());
    assertEquals(initial.amendmentRevisionId(),different.amendmentRevisionId());
    var rejected=assertThrows(ResponseStatusException.class,()->
      amendments.calculateAndCommit(command(p,prepay("REDUCE_INSTALLMENT")),key));
    assertEquals(409,rejected.getStatusCode().value());
    assertEquals(1,db.queryForObject("""
      SELECT count(*) FROM fin_calc.a05_amendment_revision WHERE parent_schedule_revision_id=?
      """,Integer.class,p.schedule.scheduleRevisionId()));
    assertEquals(2,db.queryForObject("""
      SELECT count(*) FROM fin_calc.a05_amendment_command_receipt
      WHERE amendment_revision_id=?
      """,Integer.class,initial.amendmentRevisionId()));
  }
  @Test void immutabilityAndCrossScopeDoNotLeakAmendments(){
    var p=parent("EQUAL_PRINCIPAL","0");
    var result=calc(p,prepay("REDUCE_TERM"));
    assertThrows(DataAccessException.class,()->db.update("""
      UPDATE fin_calc.a05_amendment_line SET extra_principal=1
      WHERE amendment_revision_id=?
      """,result.amendmentRevisionId()));
    assertThrows(DataAccessException.class,()->db.update("""
      DELETE FROM fin_calc.a05_amendment_revision WHERE id=?
      """,result.amendmentRevisionId()));
    assertThrows(ResponseStatusException.class,()->amendments.readExact(
      UUID.randomUUID(),p.scope.project,result.amendmentRevisionId()));
    assertThrows(ResponseStatusException.class,()->amendments.readExact(
      p.scope.org,UUID.randomUUID(),result.amendmentRevisionId()));
    assertEquals(result.resultSha256(),amendments.readExact(
      p.scope.org,p.scope.project,result.amendmentRevisionId()).resultSha256());
  }
  @Test void restEndToEndReturnsPinnedSourceAndUnqualifiedStatus() throws Exception {
    var p=parent("EQUAL_PRINCIPAL","0");
    var request=command(p,prepay("REDUCE_TERM"));
    var response=mvc.perform(post("/api/v1/uat/a05/amendments")
        .header("Idempotency-Key","a05-r12-api-"+UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsBytes(request)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.result.actualMaturityDate").value("2027-04-01"))
      .andExpect(jsonPath("$.result.finalPrincipal").value("0.00"))
      .andExpect(jsonPath("$.financiallyApproved").value(false))
      .andReturn().getResponse().getContentAsByteArray();
    var body=mapper.readTree(response);
    String id=body.path("amendmentRevisionId").asText();
    var qs="?organizationId="+p.scope.org+"&projectId="+p.scope.project;
    mvc.perform(get("/api/v1/uat/a05/amendments/"+id+qs))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.parentScheduleRevisionId")
        .value(p.schedule.scheduleRevisionId().toString()));
    mvc.perform(get("/api/v1/uat/a05/amendments"+qs+
        "&parentScheduleRevisionId="+p.schedule.scheduleRevisionId()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].amendmentRevisionId").value(id));
    noActualPosts();
  }
}
