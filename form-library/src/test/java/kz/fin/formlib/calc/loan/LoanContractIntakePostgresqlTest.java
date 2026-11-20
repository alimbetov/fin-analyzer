package kz.fin.formlib.calc.loan;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Real A05 loan writer -> canonical fin_intake C05 exact revision in ONE PG16 TX. */
@SpringBootTest(properties={
  "fin.uat.editor.enabled=false",
  "fin.calc.a05-intake-uat.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class LoanContractIntakePostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("fin_a05_r10")
        .withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url",pg::getJdbcUrl);
    registry.add("spring.datasource.username",pg::getUsername);
    registry.add("spring.datasource.password",pg::getPassword);
    registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired LoanContractIntakeService loan;
  @Autowired FormDraftService drafts;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private record Scope(UUID org,UUID entity,UUID business,UUID project) {
    static Scope random(){
      return new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
    }
  }

  private static LoanContractIntakeService.Terms terms() {
    return new LoanContractIntakeService.Terms(
      "LOAN-001","AGR-2026-01","BANK-01",
      LocalDate.of(2026,10,20),LocalDate.of(2026,11,1),
      LocalDate.of(2026,11,1),LocalDate.of(2027,11,1),
      new BigDecimal("100000.00"),"KZ","BORROWER","ANNUITY",
      "MONTHLY",15,"SIMPLE","ACT_365_FIXED","NONE",2,
      "HALF_EVEN","ADJUST_LAST_PAYMENT","FIXED",new BigDecimal("18.0000000000"));
  }

  private static LoanContractIntakeService.Fee fee(String code,String type,
      String settlement,String basis,String amount,String frequency,String dateRule) {
    return new LoanContractIntakeService.Fee(code,type,settlement,basis,
      new BigDecimal(amount),frequency,dateRule,"UNREVIEWED","UNREVIEWED");
  }
  private static List<LoanContractIntakeService.Fee> fees() {
    return List.of(
      fee("SETUP","FIXED","UPFRONT_PAID","FIXED_AMOUNT","1000.00","ONCE","DISBURSEMENT_DATE"),
      fee("SERVICE","RECURRING_FIXED","RECURRING","FIXED_AMOUNT",
        "100.00","MONTHLY","SCHEDULE_DUE_DATE"));
  }
  private static FormDraftService.DraftRequest draft(Scope s,UUID instance,int expected,
      UUID rowId,String period,String outstanding) {
    var row=new FormDraftService.RowInput(rowId,10,Map.of(
      "creditor","BANK-01","agreementRef","AGR-2026-01","currency","KZT",
      "outstanding",outstanding,"interestRate","18","rateType","FIXED",
      "dueAt","2027-11-01","collateralRef","NONE"));
    return new FormDraftService.DraftRequest(instance,s.org,s.entity,s.business,
      s.project,"C05","M",period,"KZT","PERIOD_ONLY","MANAGEMENT",
      "SYNTHETIC",expected,Map.of(),List.of(row),"a05-test",
      "initial typed loan conditions");
  }
  private static LoanContractIntakeService.Command command(
      FormDraftService.DraftRequest d,List<LoanContractIntakeService.Fee> fees) {
    return new LoanContractIntakeService.Command(d,terms(),fees);
  }

  @Test void twoFeesAndExactC05RevisionAreSavedAtomicallyWithSHA() {
    var s=Scope.random();
    var d=draft(s,null,0,UUID.randomUUID(),"2026-11","100000");
    var cmd=command(d,fees());
    var saved=loan.save(cmd,"save-"+UUID.randomUUID());
    assertEquals(1,saved.revisionNo());
    assertEquals("UNQUALIFIED_DRAFT",saved.result());
    assertFalse(saved.financiallyApproved());
    assertFalse(saved.productionAdmitted());
    var current=drafts.read(saved.instanceId(),s.org());
    assertEquals("C05",current.formCode());
    assertEquals(saved.sourceRevisionId(),current.revisionId());
    assertEquals(saved.sourceContentSha256(),current.sha256());
    assertEquals(1,current.rows().size());
    var view=loan.readExact(s.org(),s.project(),saved.sourceRevisionId());
    assertEquals(saved.termsSha256(),view.receipt().termsSha256());
    assertEquals(saved.sourceRevisionId(),view.receipt().sourceRevisionId());
    assertEquals("SOURCE_SHA_REVERIFIED",view.sourceIntegrityState());
    assertEquals(2,view.fees().size());
    assertEquals("SERVICE",view.fees().getFirst().feeCode());
    assertEquals(0,view.terms().principal().compareTo(new BigDecimal("100000")));
    assertEquals(1,jdbc.queryForObject("""
      SELECT count(*) FROM fin_intake.a05_loan_contract_revision
      WHERE source_revision_id=?
      """,Integer.class,saved.sourceRevisionId()));
    assertEquals(2,jdbc.queryForObject("""
      SELECT count(*) FROM fin_intake.a05_loan_fee_rule_revision
      WHERE contract_revision_id=?
      """,Integer.class,saved.contractRevisionId()));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_fact.fact_set",Integer.class));
  }

  @Test void unchangedC05WithChangedFeesCreatesNewRevisionAndOldRemainsReplayable() {
    var s=Scope.random();UUID row=UUID.randomUUID();
    var d=draft(s,null,0,row,"2026-11","100000");
    var first=loan.save(command(d,fees()),"loan-first-"+UUID.randomUUID());
    var updated=List.of(fee("SETUP","FIXED","UPFRONT_PAID","FIXED_AMOUNT",
      "1100.00","ONCE","DISBURSEMENT_DATE"),
      fees().get(1));
    var next=draft(s,first.instanceId(),1,row,"2026-11","100000");
    var nextCommand=command(next,updated);
    String key="loan-next-"+UUID.randomUUID();
    var second=loan.save(nextCommand,key);
    assertEquals(2,second.revisionNo());
    assertNotEquals(first.sourceRevisionId(),second.sourceRevisionId());
    assertEquals(first.sourceContentSha256(),second.sourceContentSha256(),
      "C05 ROWSET financial content may stay identical when typed rules change");
    assertNotEquals(first.termsSha256(),second.termsSha256());
    assertEquals(2,loan.readExact(s.org(),s.project(),first.sourceRevisionId()).fees().size());
    var original=loan.readExact(s.org(),s.project(),first.sourceRevisionId());
    var revised=loan.readExact(s.org(),s.project(),second.sourceRevisionId());
    assertEquals(0,original.fees().get(1).amountOrRate()
      .compareTo(new BigDecimal("1000")));
    assertEquals(0,revised.fees().get(1).amountOrRate()
      .compareTo(new BigDecimal("1100")));
    var replay=loan.save(nextCommand,key);
    assertEquals("IDEMPOTENT_REPLAY",replay.result());
    assertEquals(second.contractRevisionId(),replay.contractRevisionId());
    assertEquals(2,drafts.read(first.instanceId(),s.org()).revisionNo());
  }

  @Test void rejectingNoChangeOrMutatedIdempotencyDoesNotCreatePhantomRevision() {
    var s=Scope.random();UUID row=UUID.randomUUID();
    var first=loan.save(command(draft(s,null,0,row,"2026-11","100000"),fees()),
      "first-"+UUID.randomUUID());
    var same=command(draft(s,first.instanceId(),1,row,"2026-11","100000"),fees());
    assertThrows(ResponseStatusException.class,()->
      loan.save(same,"same-"+UUID.randomUUID()));
    assertEquals(1,drafts.read(first.instanceId(),s.org()).revisionNo());
    String key="one-"+UUID.randomUUID();
    var modified=command(draft(s,first.instanceId(),1,row,"2026-11","100000"),
      List.of(fee("SETUP","FIXED","UPFRONT_PAID","FIXED_AMOUNT",
        "1002","ONCE","DISBURSEMENT_DATE")));
    loan.save(modified,key);
    var other=command(modified.draft(),List.of(fee("SETUP","FIXED",
      "UPFRONT_PAID","FIXED_AMOUNT","1003","ONCE","DISBURSEMENT_DATE")));
    var ex=assertThrows(ResponseStatusException.class,()->loan.save(other,key));
    assertEquals(409,ex.getStatusCode().value());
    assertEquals(2,drafts.read(first.instanceId(),s.org()).revisionNo());
  }

  @Test void duplicatedBusinessKeyInSecondC05SlotRejectsEntireTransaction() {
    var s=Scope.random();
    loan.save(command(draft(s,null,0,UUID.randomUUID(),"2026-11","100000"),fees()),
      "slot1-"+UUID.randomUUID());
    var d=draft(s,null,0,UUID.randomUUID(),"2026-12","100000");
    assertThrows(DataAccessException.class,()->
      loan.save(command(d,fees()),"slot2-"+UUID.randomUUID()));
    assertEquals(1,jdbc.queryForObject("""
      SELECT count(*) FROM fin_intake.lib_form_instance
      WHERE organization_id=? AND project_id=? AND form_id=
        (SELECT id FROM fin_lib.lib_form_definition WHERE form_code='C05')
      """,Integer.class,s.org(),s.project()));
  }

  @Test void inconsistentSourceRowAndFeeShapeFailBeforeAnyWrite() {
    var s=Scope.random();
    var d=draft(s,null,0,UUID.randomUUID(),"2026-11","100000");
    var badRow=new FormDraftService.RowInput(d.rows().getFirst().rowId(),10,Map.of(
      "creditor","OTHER_BANK","agreementRef","AGR-2026-01",
      "currency","KZT","outstanding","100000","interestRate","18",
      "rateType","FIXED","dueAt","2027-11-01"));
    var rowMismatch=new FormDraftService.DraftRequest(null,s.org(),s.entity(),
      s.business(),s.project(),"C05","M","2026-11","KZT","PERIOD_ONLY",
      "MANAGEMENT","SYNTHETIC",0,Map.of(),List.of(badRow),"test","mismatch");
    assertThrows(ResponseStatusException.class,()->
      loan.save(command(rowMismatch,fees()),"wrong-"+UUID.randomUUID()));
    var invalidFee=List.of(fee("BAD","RECURRING_FIXED","FINANCED",
      "FIXED_AMOUNT","100","ONCE","DISBURSEMENT_DATE"));
    assertThrows(ResponseStatusException.class,()->
      loan.save(command(d,invalidFee),"invalid-"+UUID.randomUUID()));
    assertEquals(0,jdbc.queryForObject("""
      SELECT count(*) FROM fin_intake.lib_form_instance
      WHERE organization_id=?
      """,Integer.class,s.org()));
  }

  @Test void genericC05WriterCannotCreateOrphanAfterA05IdentityExists() {
    var s=Scope.random();UUID row=UUID.randomUUID();
    var first=loan.save(command(draft(s,null,0,row,"2026-11","100000"),fees()),
      "first-"+UUID.randomUUID());
    var changed=draft(s,first.instanceId(),1,row,"2026-11","90000");
    assertThrows(DataAccessException.class,()->
      drafts.save(changed,"generic-"+UUID.randomUUID()));
    assertEquals(1,drafts.read(first.instanceId(),s.org()).revisionNo());
  }

  @Test void immutableTypedHistoryAndSourceSHAHaveDatabaseGuards() {
    var s=Scope.random();
    var first=loan.save(command(draft(s,null,0,UUID.randomUUID(),"2026-11","100000"),fees()),
      "first-"+UUID.randomUUID());
    assertThrows(DataAccessException.class,()->jdbc.update("""
      UPDATE fin_intake.a05_loan_contract_revision SET principal=1 WHERE id=?
      """,first.contractRevisionId()));
    assertThrows(DataAccessException.class,()->jdbc.update("""
      DELETE FROM fin_intake.a05_loan_fee_rule_revision WHERE contract_revision_id=?
      """,first.contractRevisionId()));
    assertThrows(DataAccessException.class,()->jdbc.update("""
      UPDATE fin_intake.a05_loan_contract_revision SET
        source_content_sha256=? WHERE id=?
      ""","a".repeat(64),first.contractRevisionId()));
    assertEquals(first.sourceContentSha256(),
      loan.readExact(s.org(),s.project(),first.sourceRevisionId())
        .receipt().sourceContentSha256());
  }

  @Test void restWritesExactTypedContractAndExposesReadOnlyHistory() throws Exception {
    var s=Scope.random();
    var cmd=command(draft(s,null,0,UUID.randomUUID(),"2026-11","100000"),fees());
    var response=mvc.perform(post("/api/v1/uat/a05/c05/revisions")
        .header("Idempotency-Key","api-"+UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(cmd)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.revisionNo").value(1))
      .andExpect(jsonPath("$.financiallyApproved").value(false))
      .andReturn().getResponse();
    var saved=json.readTree(response.getContentAsByteArray());
    mvc.perform(get("/api/v1/uat/a05/c05/revisions/"+saved.path("sourceRevisionId").asText())
        .param("organizationId",s.org().toString())
        .param("projectId",s.project().toString()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.terms.loanBusinessKey").value("LOAN-001"))
      .andExpect(jsonPath("$.fees.length()").value(2))
      .andExpect(jsonPath("$.sourceIntegrityState").value("SOURCE_SHA_REVERIFIED"));
  }

  @Test void crossOrganizationCannotReadTypedContract() {
    var s=Scope.random();
    var saved=loan.save(command(draft(s,null,0,UUID.randomUUID(),"2026-11","100000"),fees()),
      "first-"+UUID.randomUUID());
    assertThrows(RuntimeException.class,()->
      loan.readExact(UUID.randomUUID(),s.project(),saved.sourceRevisionId()));
    assertThrows(RuntimeException.class,()->
      loan.readExact(s.org(),UUID.randomUUID(),saved.sourceRevisionId()));
  }
}
