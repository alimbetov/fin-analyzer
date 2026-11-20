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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DC-R2.1–R2.3: Actual fin_intake save/read/rehash -> F02 math -> HTTP preview.
 * No fake FactSet is committed or swapped for user-entered source values.
 */
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
@AutoConfigureMockMvc
@SpringBootTest(properties={
  "fin.uat.editor.enabled=false",
  "fin.calc.intake-uat.enabled=true"
})
class F02IntakeUatPreviewPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
      new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_dc_r2_saved_f02")
      .withUsername("fin").withPassword("test_only");
  @DynamicPropertySource static void postgres(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired FormDraftService drafts;
  @Autowired FinIntakeUatSourceAdapter adapter;
  @Autowired F02IntakeUatPreviewService preview;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;

  private record Slot(UUID org,UUID entity,UUID business,UUID project) {
    static Slot create() {
      return new Slot(UUID.randomUUID(),UUID.randomUUID(),
        UUID.randomUUID(),UUID.randomUUID());
    }
  }
  private FormDraftService.DraftReceipt save(
      Slot s,UUID instance,int expectedRevision,
      String revenue,String cogs,String opex) {
    return drafts.save(new FormDraftService.DraftRequest(
      instance,s.org(),s.entity(),s.business(),s.project(),"F02",
      "M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
      expectedRevision,Map.of("netRevenue",revenue,"cogs",cogs,
        "operatingExpenses",opex),List.of(),"dc-r2-f02-uat",
      "Exact-revision calculation UAT"),
      "dc-r2-f02-"+UUID.randomUUID());
  }

  @Test void savedF02IsReverifiedAndCalculatedWithoutFinFactWrite(){
    var s=Slot.create();
    var draft=save(s,null,0,"200","100","50");
    var source=adapter.readExact(s.org(),s.project(),draft.revisionId(),"F02");
    assertEquals("SOURCE_SHA_REVERIFIED",source.integrityState());
    assertEquals("UNQUALIFIED_SOURCE_PROJECTION",source.qualificationState());
    assertEquals(draft.contentSha256(),source.projection().sourceSha256Claim());
    var result=preview.preview(s.org(),s.project(),draft.revisionId());
    assertEquals(draft.revisionId(),result.sourceRevisionId());
    assertEquals(draft.contentSha256(),result.sourceContentSha256());
    assertEquals("100.00",result.outputs().get("grossProfit").value());
    assertEquals("0.50000000",result.outputs().get("grossMargin").value());
    assertEquals("50.00",result.outputs().get("operatingResult").value());
    assertEquals(F02IntakeUatPreviewService.STATE,result.executionState());
    assertFalse(result.financiallyApproved());
    assertFalse(result.productionAdmitted());
    assertEquals(result.previewSha256(),
      preview.preview(s.org(),s.project(),draft.revisionId()).previewSha256());
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_fact.fact_set WHERE organization_id=?",
      Integer.class,s.org()));
  }

  @Test void twoSavedRevisionsKeepHistoricalOutputPinned(){
    var s=Slot.create();
    var first=save(s,null,0,"200","100","50");
    var result1=preview.preview(s.org(),s.project(),first.revisionId());
    var second=save(s,first.instanceId(),1,"300","100","50");
    var result2=preview.preview(s.org(),s.project(),second.revisionId());
    assertNotEquals(first.contentSha256(),second.contentSha256());
    assertNotEquals(result1.previewSha256(),result2.previewSha256());
    assertEquals("100.00",result1.outputs().get("grossProfit").value());
    assertEquals("200.00",result2.outputs().get("grossProfit").value());
    assertEquals("100.00",preview.preview(
      s.org(),s.project(),first.revisionId()).outputs().get("grossProfit").value());
  }

  @Test void zeroDenominatorIsUndefinedButOtherOutputsRemainPresent(){
    var s=Slot.create();
    var revision=save(s,null,0,"0","0","0");
    var result=preview.preview(s.org(),s.project(),revision.revisionId());
    assertEquals("INVALID",result.outputs().get("grossMargin").state());
    assertEquals("DIVISION_BY_ZERO",result.outputs().get("grossMargin").errorCode());
    assertNull(result.outputs().get("grossMargin").value());
    assertEquals("0.00",result.outputs().get("operatingResult").value());
  }

  @Test void wrongOrganizationProjectOrFormCannotAccessExactRevision(){
    var s=Slot.create();
    var revision=save(s,null,0,"200","100","50");
    assertThrows(IllegalArgumentException.class,()->adapter.readExact(
      UUID.randomUUID(),s.project(),revision.revisionId(),"F02"));
    assertThrows(IllegalArgumentException.class,()->adapter.readExact(
      s.org(),UUID.randomUUID(),revision.revisionId(),"F02"));
    assertThrows(IllegalStateException.class,()->adapter.readExact(
      s.org(),s.project(),revision.revisionId(),"C01"));
  }

  @Test void HTTPPreviewUsesRealSavedRevisionAndContainsLineage() throws Exception {
    var s=Slot.create();
    var revision=save(s,null,0,"200","100","50");
    var payload=Map.of("organizationId",s.org(),"projectId",s.project(),
      "exactRevisionId",revision.revisionId());
    var response=mvc.perform(post("/api/v1/uat/calculations/f02/preview")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(payload)))
      .andExpect(status().isOk()).andReturn();
    var body=json.readTree(response.getResponse().getContentAsByteArray());
    assertEquals(revision.revisionId().toString(),body.path("sourceRevisionId").asText());
    assertEquals(revision.contentSha256(),body.path("sourceContentSha256").asText());
    assertEquals("100.00",body.path("outputs").path("grossProfit").path("value").asText());
    assertEquals("FIN_INTAKE_UAT_PREVIEW_UNQUALIFIED",body.path("executionState").asText());
    assertFalse(body.path("financiallyApproved").asBoolean(true));
  }
}
