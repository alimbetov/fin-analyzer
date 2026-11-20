package kz.fin.formlib.calc.loan;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.loan.LoanPaymentScheduleCalculator.CustomInstallment;
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

/** A05-R1.1 real PostgreSQL 16 and exact C05+R1.0 source lineage. */
@SpringBootTest(properties={
  "fin.uat.editor.enabled=false",
  "fin.calc.a05-intake-uat.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class LoanSchedulePostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("fin_a05_r11")
        .withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void dbConfig(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired LoanScheduleService schedules;
  @Autowired LoanContractIntakeService loans;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  private static BigDecimal n(String value){return new BigDecimal(value);}
  private record Scope(UUID org,UUID entity,UUID business,UUID project){
    static Scope fresh(){return new Scope(UUID.randomUUID(),UUID.randomUUID(),
      UUID.randomUUID(),UUID.randomUUID());}
  }
  private static LoanContractIntakeService.Terms terms(String method,String amount,
      String annualRate,String accrualFrom,String maturity,String dayCount){
    LocalDate from=LocalDate.parse(accrualFrom);
    return new LoanContractIntakeService.Terms(
      "LOAN-001","AGR-A05-R11","BANK-A05",from,from,from,
      LocalDate.parse(maturity),n(amount),"KZ","BORROWER",method,"MONTHLY",1,
      "SIMPLE",dayCount,"NONE",2,"HALF_EVEN",
      "ADJUST_LAST_PAYMENT","FIXED",n(annualRate));
  }
  private static LoanContractIntakeService.Fee fee(String code,String type,
      String settlement,String basis,String amount,String freq,String date){
    return new LoanContractIntakeService.Fee(code,type,settlement,basis,
      n(amount),freq,date,"UNREVIEWED","UNREVIEWED");
  }
  private static List<LoanContractIntakeService.Fee> mixedFees(){
    return List.of(
      fee("FIN","FIXED","FINANCED","FIXED_AMOUNT","50","ONCE","DISBURSEMENT_DATE"),
      fee("HOLD","FIXED","WITHHELD","FIXED_AMOUNT","20","ONCE","DISBURSEMENT_DATE"),
      fee("UP","FIXED","UPFRONT_PAID","FIXED_AMOUNT","10","ONCE","DISBURSEMENT_DATE"),
      fee("RUN","RECURRING_FIXED","RECURRING","FIXED_AMOUNT","5","MONTHLY","SCHEDULE_DUE_DATE"),
      fee("END","FIXED","AT_MATURITY","FIXED_AMOUNT","30","ONCE","MATURITY_DATE"));
  }
  private static FormDraftService.DraftRequest c05(Scope s,UUID instance,
      int expected,UUID rowId,String rate,String outstanding,String due){
    return new FormDraftService.DraftRequest(instance,s.org,s.entity,s.business,s.project,
      "C05","M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
      expected,Map.of(),List.of(new FormDraftService.RowInput(rowId,1,Map.of(
        "creditor","BANK-A05","agreementRef","AGR-A05-R11","currency","KZT",
        "outstanding",outstanding,"interestRate",rate,"rateType","FIXED",
        "dueAt",due,"collateralRef","NONE"))),
      "a05-r11-test","A05-R1.1 exact dated schedule");
  }
  private record Saved(Scope scope,LoanContractIntakeService.Receipt loanReceipt,
      LoanContractIntakeService.Terms terms,UUID rowId) {}

  private Saved save(String method,String amount,String rate,String start,
      String maturity,String dayCount,List<LoanContractIntakeService.Fee> fees){
    Scope s=Scope.fresh();
    var t=terms(method,amount,rate,start,maturity,dayCount);
    UUID row=UUID.randomUUID();
    var receipt=loans.save(new LoanContractIntakeService.Command(
      c05(s,null,0,row,rate,amount,maturity),t,fees),
      "a05-loan-"+UUID.randomUUID());
    return new Saved(s,receipt,t,row);
  }
  private static LoanScheduleService.ScheduleCommand req(Saved saved,
      List<CustomInstallment> custom) {
    return new LoanScheduleService.ScheduleCommand(saved.scope.org,
      saved.scope.project,saved.loanReceipt.sourceRevisionId(),custom);
  }
  private LoanScheduleService.ScheduleView calculate(Saved saved,
      List<CustomInstallment> custom){
    return schedules.calculateAndCommit(req(saved,custom),
      "a05-schedule-"+UUID.randomUUID());
  }

  private void noPostedFinancialFacts(){
    assertEquals(0,db.queryForObject(
      "SELECT count(*) FROM fin_fact.fact_set",Integer.class));
    assertEquals(0,db.queryForObject(
      "SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
  }

  @Test void annuityExactC05ToTyped12MonthRowsWithPinnedApplicationAndSHA(){
    var saved=save("ANNUITY","1000.00","12","2027-01-01",
      "2027-04-01","ACT_365_FIXED",List.of());
    var r=calculate(saved,List.of());
    assertEquals("UAT_COMMITTED",r.commandResult());
    assertEquals("UAT_UNQUALIFIED",r.state());
    assertEquals(saved.loanReceipt.sourceRevisionId(),r.exactSourceRevisionId());
    assertEquals(saved.loanReceipt.sourceContentSha256(),r.sourceContentSha256());
    assertEquals(saved.loanReceipt.termsSha256(),r.termsSha256());
    assertEquals(64,r.engineDescriptorSha256().length());
    assertEquals(64,r.variantSha256().length());
    assertEquals(64,r.resultSha256().length());
    assertEquals(3,r.result().lines().size());
    assertEquals(0,r.result().finalBalance().compareTo(BigDecimal.ZERO));
    assertFalse(r.financiallyApproved());assertFalse(r.productionAdmitted());
    assertEquals(3,db.queryForObject("""
      SELECT count(*) FROM fin_calc.a05_schedule_line
      WHERE schedule_revision_id=?
      """,Integer.class,r.scheduleRevisionId()));
    assertEquals(0,db.queryForObject("""
      SELECT count(*) FROM fin_calc.a05_schedule_line_fee
      WHERE schedule_revision_id=?
      """,Integer.class,r.scheduleRevisionId()));
    var read=schedules.readExact(saved.scope.org,saved.scope.project,
      r.scheduleRevisionId());
    assertEquals(r.resultSha256(),read.resultSha256());
    assertEquals("READ_ONLY",read.commandResult());
    noPostedFinancialFacts();
  }

  @Test void zeroRateThreeHundredThirtyThreePointThirtyFourLastPrincipal(){
    var saved=save("EQUAL_PRINCIPAL","1000.00","0",
      "2027-01-01","2027-04-01","ACT_365_FIXED",List.of());
    var r=calculate(saved,List.of());
    assertEquals(List.of("333.33","333.33","333.34"),
      r.result().lines().stream().map(l->l.principalDue().toPlainString()).toList());
    assertEquals(0,r.result().totalContractualInterest().compareTo(BigDecimal.ZERO));
    assertEquals(0,r.result().totalScheduledCash().compareTo(n("1000")));
    noPostedFinancialFacts();
  }

  @Test void oneOffAndPeriodicFeesProduceExactFundingAndCashBreakdown(){
    var saved=save("EQUAL_PRINCIPAL","1000.00","0",
      "2027-01-01","2027-03-01","ACT_360",mixedFees());
    var r=calculate(saved,List.of());
    assertEquals(0,r.result().funding().openingLiability().compareTo(n("1050")));
    assertEquals(0,r.result().funding().netCashProceeds().compareTo(n("970")));
    assertEquals(0,r.result().totalScheduledFees().compareTo(n("40")));
    assertEquals(0,r.result().totalScheduledCash().compareTo(n("1090")));
    assertEquals(3,db.queryForObject("""
      SELECT count(*) FROM fin_calc.a05_schedule_line_fee
      WHERE schedule_revision_id=?
      """,Integer.class,r.scheduleRevisionId()));
    assertEquals(0,schedules.readExact(saved.scope.org,saved.scope.project,
      r.scheduleRevisionId()).result().finalBalance().compareTo(BigDecimal.ZERO));
    noPostedFinancialFacts();
  }

  @Test void customDatedInstallmentsPreservedAndOverpaymentRejectedAtomically(){
    var saved=save("CUSTOM","1000.00","12",
      "2027-01-01","2027-04-01","ACT_360",List.of());
    var custom=List.of(
      new CustomInstallment(LocalDate.parse("2027-01-31"),n("100")),
      new CustomInstallment(LocalDate.parse("2027-03-02"),n("0")),
      new CustomInstallment(LocalDate.parse("2027-04-01"),n("900")));
    var r=calculate(saved,custom);
    assertEquals(3,r.result().lines().size());
    assertEquals(3,db.queryForObject("""
      SELECT count(*) FROM fin_calc.a05_schedule_custom_installment
      WHERE schedule_revision_id=?
      """,Integer.class,r.scheduleRevisionId()));
    assertEquals(0,r.result().finalBalance().compareTo(BigDecimal.ZERO));
    int before=db.queryForObject("""
      SELECT count(*) FROM fin_calc.a05_schedule_revision
      WHERE source_revision_id=?
      """,Integer.class,saved.loanReceipt.sourceRevisionId());
    assertThrows(ResponseStatusException.class,()->calculate(saved,List.of(
      new CustomInstallment(LocalDate.parse("2027-04-01"),n("1100")))));
    assertEquals(before,db.queryForObject("""
      SELECT count(*) FROM fin_calc.a05_schedule_revision WHERE source_revision_id=?
      """,Integer.class,saved.loanReceipt.sourceRevisionId()));
  }

  @Test void sameRequestIdempotentAndDistinctKeyReusesImmutableVariant(){
    var saved=save("ANNUITY","1000.00","12",
      "2027-01-01","2027-04-01","ACT_365_FIXED",List.of());
    var cmd=req(saved,List.of());String key="same-"+UUID.randomUUID();
    var first=schedules.calculateAndCommit(cmd,key);
    var replay=schedules.calculateAndCommit(cmd,key);
    assertEquals("IDEMPOTENT_REPLAY",replay.commandResult());
    assertEquals(first.scheduleRevisionId(),replay.scheduleRevisionId());
    var next=schedules.calculateAndCommit(cmd,"other-"+UUID.randomUUID());
    assertEquals("EXISTING_VARIANT_REPLAY",next.commandResult());
    assertEquals(first.scheduleRevisionId(),next.scheduleRevisionId());
    assertEquals(1,db.queryForObject("""
      SELECT count(*) FROM fin_calc.a05_schedule_revision WHERE source_revision_id=?
      """,Integer.class,saved.loanReceipt.sourceRevisionId()));
    // Same-key replay MUST NOT create a receipt; a second distinct key
    // legitimately gets one receipt pointing at the immutable same variant.
    assertEquals(2,db.queryForObject("""
      SELECT count(*) FROM fin_calc.a05_schedule_command_receipt WHERE schedule_revision_id=?
      """,Integer.class,first.scheduleRevisionId()));
  }

  @Test void differentPayloadReusingKeyFailsWithoutNewSchedule(){
    var saved=save("CUSTOM","1000.00","0",
      "2027-01-01","2027-03-01","ACT_365_FIXED",List.of());
    var a=new LoanScheduleService.ScheduleCommand(saved.scope.org,saved.scope.project,
      saved.loanReceipt.sourceRevisionId(),List.of(
        new CustomInstallment(LocalDate.parse("2027-02-01"),n("300")),
        new CustomInstallment(LocalDate.parse("2027-03-01"),n("700"))));
    String key="custom-"+UUID.randomUUID();
    schedules.calculateAndCommit(a,key);
    var b=new LoanScheduleService.ScheduleCommand(saved.scope.org,saved.scope.project,
      saved.loanReceipt.sourceRevisionId(),List.of(
        new CustomInstallment(LocalDate.parse("2027-02-01"),n("400")),
        new CustomInstallment(LocalDate.parse("2027-03-01"),n("600"))));
    var ex=assertThrows(ResponseStatusException.class,
      ()->schedules.calculateAndCommit(b,key));
    assertEquals(409,ex.getStatusCode().value());
    assertEquals(1,db.queryForObject("""
      SELECT count(*) FROM fin_calc.a05_schedule_revision WHERE source_revision_id=?
      """,Integer.class,saved.loanReceipt.sourceRevisionId()));
  }

  @Test void immutableResultRowsCannotBeUpdatedDeletedOrSpoofed(){
    var saved=save("EQUAL_PRINCIPAL","1000.00","0",
      "2027-01-01","2027-03-01","ACT_360",mixedFees());
    var result=calculate(saved,List.of());
    assertThrows(DataAccessException.class,()->db.update("""
      UPDATE fin_calc.a05_schedule_line SET principal_due=1
      WHERE schedule_revision_id=? AND installment_no=1
      """,result.scheduleRevisionId()));
    assertThrows(DataAccessException.class,()->db.update("""
      DELETE FROM fin_calc.a05_schedule_line_fee WHERE schedule_revision_id=?
      """,result.scheduleRevisionId()));
    assertThrows(DataAccessException.class,()->db.update("""
      UPDATE fin_calc.a05_schedule_revision SET result_sha256=?
      WHERE id=?
      ""","f".repeat(64),result.scheduleRevisionId()));
    assertEquals(result.resultSha256(),schedules.readExact(
      saved.scope.org,saved.scope.project,result.scheduleRevisionId()).resultSha256());
  }

  @Test void organizationProjectScopedAndHttpRestReturnsOnlyExactSchedule() throws Exception {
    var saved=save("ANNUITY","1000.00","12",
      "2027-01-01","2027-04-01","ACT_365_FIXED",List.of());
    var command=req(saved,List.of());
    var response=mvc.perform(post("/api/v1/uat/a05/schedules")
        .header("Idempotency-Key","http-"+UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsBytes(command)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.state").value("UAT_UNQUALIFIED"))
      .andExpect(jsonPath("$.result.lines.length()").value(3))
      .andExpect(jsonPath("$.financiallyApproved").value(false))
      .andReturn().getResponse().getContentAsByteArray();
    String id=mapper.readTree(response).path("scheduleRevisionId").asText();
    mvc.perform(get("/api/v1/uat/a05/schedules/"+id)
        .param("organizationId",saved.scope.org.toString())
        .param("projectId",saved.scope.project.toString()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.result.finalBalance").value("0.00"));
    mvc.perform(get("/api/v1/uat/a05/schedules/"+id)
        .param("organizationId",UUID.randomUUID().toString())
        .param("projectId",saved.scope.project.toString()))
      .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/uat/a05/schedules/"+id)
        .param("organizationId",saved.scope.org.toString())
        .param("projectId",UUID.randomUUID().toString()))
      .andExpect(status().isNotFound());
    noPostedFinancialFacts();
  }

  @Test void oldSourceRevisionScheduleReplaysEvenAfterFeeOnlyNewC05Revision(){
    var saved=save("ANNUITY","1000.00","12",
      "2027-01-01","2027-03-01","ACT_365_FIXED",List.of());
    var original=calculate(saved,List.of());
    var old=loans.readExact(saved.scope.org,saved.scope.project,
      saved.loanReceipt.sourceRevisionId());
    var revised=loans.save(new LoanContractIntakeService.Command(
        c05(saved.scope,saved.loanReceipt.instanceId(),1,saved.rowId,
          "12","1000","2027-03-01"),
        saved.terms,List.of(
          fee("SERVICE","RECURRING_FIXED","RECURRING","FIXED_AMOUNT",
            "50","MONTHLY","SCHEDULE_DUE_DATE"))),
      "a05-updated-"+UUID.randomUUID());
    assertEquals(saved.loanReceipt.sourceContentSha256(),revised.sourceContentSha256());
    assertNotEquals(saved.loanReceipt.termsSha256(),revised.termsSha256());
    var v2=schedules.calculateAndCommit(
      new LoanScheduleService.ScheduleCommand(saved.scope.org,saved.scope.project,
        revised.sourceRevisionId(),List.of()),"a05-v2-"+UUID.randomUUID());
    assertNotEquals(original.resultSha256(),v2.resultSha256());
    var again=schedules.readExact(saved.scope.org,saved.scope.project,
      original.scheduleRevisionId());
    assertEquals(original.resultSha256(),again.resultSha256());
    assertEquals(0,again.result().totalScheduledFees().compareTo(n("0")));
    assertEquals(0,v2.result().totalScheduledFees().compareTo(n("100")));
    assertEquals(old.receipt().sourceRevisionId(),again.exactSourceRevisionId());
    noPostedFinancialFacts();
  }
}
