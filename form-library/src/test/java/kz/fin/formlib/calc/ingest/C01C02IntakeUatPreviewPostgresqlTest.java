package kz.fin.formlib.calc.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real exact-revision ROWSET -> INGEST-2 SHA -> financial kernel.
 * Financially UNQUALIFIED; no fin_fact write or automatic ECL/aging.
 */
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
@AutoConfigureMockMvc
@SpringBootTest(properties={
    "fin.uat.editor.enabled=false",
    "fin.calc.intake-uat.enabled=true"
})
class C01C02IntakeUatPreviewPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("fin_dc_r24_asof")
          .withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void pgProps(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired FormDraftService drafts;
  @Autowired FinIntakeUatSourceAdapter intake;
  @Autowired UatCalculationPreviewService preview;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;

  private record Slot(UUID org,UUID entity,UUID business,UUID project) {
    static Slot random() {
      return new Slot(UUID.randomUUID(),UUID.randomUUID(),
        UUID.randomUUID(),UUID.randomUUID());
    }
  }

  private static FormDraftService.RowInput row(UUID id,int position,
      Map<String,String> values){
    return new FormDraftService.RowInput(id,position,values);
  }
  private static FormDraftService.RowInput row(int position,Map<String,String> values){
    return row(UUID.randomUUID(),position,values);
  }
  private static Map<String,String> c01(String gross,String allowance,
      String overdue,String dueAt){
    return Map.of("counterparty","CLIENT_1","gross",gross,
      "allowance",allowance,"overdue",overdue,"dueAt",dueAt);
  }
  private static Map<String,String> c02(String outstanding,String overdue,
      String dueAt){
    return Map.of("counterparty","SUPPLIER_1",
      "outstanding",outstanding,"overdue",overdue,"dueAt",dueAt);
  }

  private FormDraftService.DraftReceipt save(Slot s,String formCode,UUID instance,
      int expected,List<FormDraftService.RowInput> rows){
    return drafts.save(new FormDraftService.DraftRequest(
      instance,s.org(),s.entity(),s.business(),s.project(),formCode,
      "M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
      expected,Map.of(),rows,"r24-asof-pg16","C01/C02 exact saved calculation"),
      "r24-asof-"+UUID.randomUUID());
  }
  private UatCalculationPreview calc(Slot s,UUID revision,String formCode){
    return preview.preview(s.org(),s.project(),revision,formCode);
  }
  private static void equal(String value,String expected){
    assertEquals(0,new BigDecimal(value).compareTo(new BigDecimal(expected)));
  }

  @Test void c01GrossAllowanceOverdueAndDateAreCalculatedFromRealRowset(){
    var s=Slot.random();
    var saved=save(s,"C01",null,0,List.of(
      row(10,c01("100","10","20","2026-11-10")),
      row(20,c01("50","5","10","2026-11-05"))));
    var pinned=intake.readExact(s.org(),s.project(),saved.revisionId(),"C01");
    assertEquals(2,pinned.sourceRowIds().size());
    assertEquals("SOURCE_SHA_REVERIFIED",pinned.integrityState());
    var out=calc(s,saved.revisionId(),"C01");
    assertEquals(saved.revisionId(),out.sourceRevisionId());
    assertEquals(saved.contentSha256(),out.sourceContentSha256());
    assertEquals(LocalDate.of(2026,11,30),out.asOfDate());
    assertEquals(2,out.sourceRowCount());
    equal(out.outputs().get("C01.gross").value(),"150");
    equal(out.outputs().get("C01.allowance").value(),"15");
    equal(out.outputs().get("C01.netReceivablesProposed").value(),"135");
    equal(out.outputs().get("C01.declaredOverdue").value(),"30");
    equal(out.outputs().get("C01.declaredOverdueShare").value(),"0.2");
    assertTrue(out.diagnostics().contains(
      "DECLARED_OVERDUE_NOT_RECONSTRUCTED_FROM_PAYMENT_EVENTS"));
    assertEquals(out.previewSha256(),calc(s,saved.revisionId(),"C01").previewSha256());
    assertFalse(out.financiallyApproved());
    assertFalse(out.productionAdmitted());
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_fact.fact_set WHERE organization_id=?",
      Integer.class,s.org()));
  }

  @Test void c02OutstandingAndOverdueShareUseSameSourceAndCutoff(){
    var s=Slot.random();
    var saved=save(s,"C02",null,0,List.of(
      row(10,c02("60","10","2026-11-02")),
      row(20,c02("40","10","2026-11-03"))));
    var out=calc(s,saved.revisionId(),"C02");
    equal(out.outputs().get("C02.totalOutstandingProposed").value(),"100");
    equal(out.outputs().get("C02.declaredOverdue").value(),"20");
    equal(out.outputs().get("C02.declaredOverdueShare").value(),"0.2");
    assertEquals(LocalDate.of(2026,11,30),out.asOfDate());
    assertEquals("MANAGEMENT",out.reportingBasis());
  }

  @Test void emptyOrIncompleteRowsCannotSilentlyDisappearFromTotal(){
    var s=Slot.random();
    var saved=save(s,"C01",null,0,List.of(
      row(10,c01("100","10","0","2026-12-10")),
      row(20,Map.of("counterparty","UNFILLED"))));
    var ex=assertThrows(IllegalStateException.class,
      ()->calc(s,saved.revisionId(),"C01"));
    assertEquals("ASOF_INTAKE_REQUIRED_ROW_MONEY_MISSING",ex.getMessage());
  }

  @Test void missingOptionalOverdueDoesNotTurnIntoZeroAndZeroDenominatorUndefined(){
    var s=Slot.random();
    var saved=save(s,"C01",null,0,List.of(
      row(10,Map.of("gross","100","allowance","10","dueAt","2026-11-01")),
      row(20,c01("50","0","0","2026-11-10"))));
    var result=calc(s,saved.revisionId(),"C01");
    equal(result.outputs().get("C01.netReceivablesProposed").value(),"140");
    assertEquals("MISSING",result.outputs().get("C01.declaredOverdue").state());
    assertEquals("MISSING",
      result.outputs().get("C01.declaredOverdueShare").state());

    var t=Slot.random();
    var zero=save(t,"C02",null,0,List.of(
      row(10,c02("0","0","2026-12-10"))));
    var empty=calc(t,zero.revisionId(),"C02");
    equal(empty.outputs().get("C02.totalOutstandingProposed").value(),"0");
    assertEquals("UNDEFINED",empty.outputs()
      .get("C02.declaredOverdueShare").state());
  }

  @Test void futureDueAndMissingDueDateRejectPositiveOverdue(){
    var s=Slot.random();
    var future=save(s,"C02",null,0,List.of(
      row(10,c02("100","20","2026-12-01"))));
    assertEquals("ASOF_FUTURE_OR_TODAY_DUE_CANNOT_BE_OVERDUE",
      assertThrows(IllegalStateException.class,
        ()->calc(s,future.revisionId(),"C02")).getMessage());

    var t=Slot.random();
    var missing=save(t,"C01",null,0,List.of(
      row(10,Map.of("gross","100","allowance","5","overdue","20"))));
    assertEquals("ASOF_DUE_AT_REQUIRED_FOR_DECLARED_OVERDUE",
      assertThrows(IllegalStateException.class,
        ()->calc(t,missing.revisionId(),"C01")).getMessage());
  }

  @Test void creditBalanceOrAllowanceBeyondGrossRequiresReview(){
    var s=Slot.random();
    var credit=save(s,"C02",null,0,List.of(
      row(10,c02("-10","0","2026-11-10"))));
    assertEquals("ASOF_CREDIT_BALANCE_CLASSIFICATION_REVIEW_REQUIRED",
      assertThrows(IllegalStateException.class,
        ()->calc(s,credit.revisionId(),"C02")).getMessage());
    var t=Slot.random();
    var allowance=save(t,"C01",null,0,List.of(
      row(10,c01("100","110","0","2026-11-10"))));
    assertEquals("ASOF_ALLOWANCE_EXCEEDS_GROSS_REVIEW_REQUIRED",
      assertThrows(IllegalStateException.class,
        ()->calc(t,allowance.revisionId(),"C01")).getMessage());
  }

  @Test void immutableTwoRevisionReplayAndCrossProjectRejection(){
    var s=Slot.random();
    UUID a=UUID.randomUUID(),b=UUID.randomUUID();
    var first=save(s,"C02",null,0,List.of(
      row(a,10,c02("100","20","2026-11-10")),
      row(b,20,c02("30","10","2026-11-05"))));
    var old=calc(s,first.revisionId(),"C02");
    var next=save(s,"C02",first.instanceId(),1,List.of(
      row(a,10,c02("70","20","2026-11-10")),
      row(b,20,c02("30","10","2026-11-05"))));
    var now=calc(s,next.revisionId(),"C02");
    equal(old.outputs().get("C02.totalOutstandingProposed").value(),"130");
    equal(now.outputs().get("C02.totalOutstandingProposed").value(),"100");
    assertNotEquals(old.previewSha256(),now.previewSha256());
    assertEquals(old.previewSha256(),
      calc(s,first.revisionId(),"C02").previewSha256());
    assertThrows(IllegalArgumentException.class,
      ()->preview.preview(UUID.randomUUID(),s.project(),next.revisionId(),"C02"));
    assertThrows(IllegalArgumentException.class,
      ()->preview.preview(s.org(),UUID.randomUUID(),next.revisionId(),"C02"));
    assertThrows(IllegalStateException.class,
      ()->calc(s,next.revisionId(),"C01"));
  }

  @Test void unifiedHttpExposesExactScopeLineageAndUnqualifiedOutputs() throws Exception {
    var s=Slot.random();
    var rev=save(s,"C01",null,0,List.of(
      row(10,c01("100","10","20","2026-11-10"))));
    var request=Map.of("formCode","C01","organizationId",s.org(),
      "projectId",s.project(),"exactRevisionId",rev.revisionId());
    var res=mvc.perform(post("/api/v1/uat/calculations/preview")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsBytes(request)))
      .andExpect(status().isOk()).andReturn();
    var body=json.readTree(res.getResponse().getContentAsByteArray());
    assertEquals("C01",body.path("formCode").asText());
    assertEquals(rev.revisionId().toString(),body.path("sourceRevisionId").asText());
    assertEquals(rev.contentSha256(),body.path("sourceContentSha256").asText());
    assertEquals("2026-11-30",body.path("asOfDate").asText());
    equal(body.path("outputs").path("C01.netReceivablesProposed").path("value").asText(),"90");
    assertFalse(body.path("financiallyApproved").asBoolean(true));
    assertFalse(body.path("productionAdmitted").asBoolean(true));
  }

  @Test void unifiedF02RemainsCompatibleWithExistingPinnedKernel(){
    var s=Slot.random();
    var saved=drafts.save(new FormDraftService.DraftRequest(
      null,s.org(),s.entity(),s.business(),s.project(),"F02",
      "M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
      0,Map.of("netRevenue","200","cogs","100","operatingExpenses","50"),
      List.of(),"r24-asof-test","F02 compatibility"),
      "r24-f02-"+UUID.randomUUID());
    var out=calc(s,saved.revisionId(),"F02");
    equal(out.outputs().get("grossProfit").value(),"100");
    equal(out.outputs().get("grossMargin").value(),"0.5");
    assertEquals("F02",out.formCode());
    assertNull(out.asOfDate());
    assertFalse(out.financiallyApproved());
  }
}
