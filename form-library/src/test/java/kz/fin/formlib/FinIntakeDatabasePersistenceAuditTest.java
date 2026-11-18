package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.service.FormDraftService;
import kz.fin.formlib.service.ReportGridService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP -> real JPA transaction -> fin_intake rows -> read back, no cache. */
@SpringBootTest
@AutoConfigureMockMvc
class FinIntakeDatabasePersistenceAuditTest {
 @Autowired MockMvc http;
 @Autowired ObjectMapper mapper;
 @Autowired JdbcTemplate db;
 private String json(Object o)throws Exception{return mapper.writeValueAsString(o);}
 private JsonNode success(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b)
     throws Exception {
   return mapper.readTree(http.perform(b).andExpect(status().isOk())
     .andReturn().getResponse().getContentAsString());
 }
 private long number(String sql,Object... args){
   return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));
 }
 private FormDraftService.DraftRequest req(UUID instanceId,UUID org,UUID project,
     String form,int revision,Map<String,String> values,List<FormDraftService.RowInput> rows){
   return new FormDraftService.DraftRequest(instanceId,org,
     UUID.fromString("00000000-0000-0000-0000-000000000002"),
     UUID.fromString("00000000-0000-0000-0000-000000000003"),project,
     form,"M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
     revision,values,rows,"local-qa","persistence proof");
 }
 private JsonNode saveDraft(FormDraftService.DraftRequest req,String key)throws Exception {
   return success(post("/api/v1/drafts").header("Idempotency-Key",key)
     .contentType(MediaType.APPLICATION_JSON).content(json(req)));
 }
 private JsonNode readDraft(UUID id,UUID org,Integer revision)throws Exception{
   String url="/api/v1/drafts/"+id+(revision==null?"":"/revisions/"+revision);
   return success(get(url).param("organizationId",org.toString()));
 }

 @Test void existingFinancialSlotRequiresExplicitRevisionAndCanBeRecoveredByScope()
     throws Exception {
   UUID org=UUID.randomUUID(),project=UUID.randomUUID();
   var request=req(null,org,project,"F02",0,Map.of(
       "netRevenue","100","cogs","30","operatingExpenses","10"),List.of());
   JsonNode initial=saveDraft(request,"slot-initial-"+org);
   UUID id=UUID.fromString(initial.get("instanceId").asText());
   assertEquals(1,initial.get("revisionNo").asInt());

   // A new idempotency key does NOT authorize a second financial instance.
   http.perform(post("/api/v1/drafts")
     .header("Idempotency-Key","slot-duplicate-"+org)
     .contentType(MediaType.APPLICATION_JSON).content(json(request)))
     .andExpect(status().isConflict())
     .andExpect(jsonPath("$.code").value("REPORT_SLOT_ALREADY_EXISTS"));

   JsonNode existing=success(get("/api/v1/drafts/slot")
     .param("organizationId",org.toString())
     .param("economicEntityId","00000000-0000-0000-0000-000000000002")
     .param("businessScopeId","00000000-0000-0000-0000-000000000003")
     .param("projectId",project.toString())
     .param("formCode","F02").param("periodType","M")
     .param("periodCode","2026-11").param("coverage","PERIOD_ONLY")
     .param("currency","KZT").param("reportingBasis","MANAGEMENT")
     .param("dataSpace","SYNTHETIC"));
   assertEquals(id.toString(),existing.get("instanceId").asText());
   assertEquals(1,existing.get("revisionNo").asInt());

   // Changing even one scoping dimension must never leak another slot.
   http.perform(get("/api/v1/drafts/slot")
     .param("organizationId",UUID.randomUUID().toString())
     .param("economicEntityId","00000000-0000-0000-0000-000000000002")
     .param("businessScopeId","00000000-0000-0000-0000-000000000003")
     .param("projectId",project.toString())
     .param("formCode","F02").param("periodType","M")
     .param("periodCode","2026-11").param("coverage","PERIOD_ONLY")
     .param("currency","KZT").param("reportingBasis","MANAGEMENT")
     .param("dataSpace","SYNTHETIC")).andExpect(status().isNotFound());

   var second=req(id,org,project,"F02",1,Map.of(
       "netRevenue","200","cogs","30","operatingExpenses","10"),List.of());
   JsonNode updated=saveDraft(second,"slot-revision-2-"+org);
   assertEquals(id.toString(),updated.get("instanceId").asText());
   assertEquals(2,updated.get("revisionNo").asInt());
   assertEquals(1,number("SELECT count(*) FROM fin_intake.lib_form_instance WHERE id=?",id));
   assertEquals(2,number("SELECT count(*) FROM fin_intake.lib_form_revision WHERE instance_id=?",id));
   assertEquals("100",readDraft(id,org,1).get("values").get("netRevenue").asText());
   assertEquals("200",readDraft(id,org,2).get("values").get("netRevenue").asText());
 }

 @Test void f02StoredValueShaProjectRevisionAndIdempotencyAreVisibleInDatabase()
     throws Exception {
   UUID org=UUID.randomUUID(),project=UUID.randomUUID();
   var initial=req(null,org,project,"F02",0,Map.of(
     "netRevenue","12345.6700","cogs","0.00","operatingExpenses","18.90"),List.of());
   JsonNode receipt=saveDraft(initial,"f02-"+org);
   UUID id=UUID.fromString(receipt.get("instanceId").asText());
   UUID revisionId=UUID.fromString(receipt.get("revisionId").asText());
   assertEquals(1,receipt.get("revisionNo").asInt());
   String sha=receipt.get("contentSha256").asText();
   assertEquals(64,sha.length());
   assertEquals(project,db.queryForObject(
     "select project_id from fin_intake.lib_form_instance where id=?",
     UUID.class,id));
   assertEquals(org,db.queryForObject(
     "select organization_id from fin_intake.lib_form_instance where id=?",
     UUID.class,id));
   assertEquals(sha,db.queryForObject(
     "select canonical_content_sha256 from fin_intake.lib_form_revision where id=?",
     String.class,revisionId));
   String stored=db.queryForObject(
     "select values_json from fin_intake.lib_form_revision where id=?",
     String.class,revisionId);
   JsonNode data=mapper.readTree(stored);
   assertEquals("12345.67",data.get("netRevenue").asText());
   assertEquals("0",data.get("cogs").asText());
   assertEquals("18.9",data.get("operatingExpenses").asText());
   assertEquals(sha,readDraft(id,org,null).get("sha256").asText());
   assertEquals("12345.67",readDraft(id,org,null).get("values").get("netRevenue").asText());
   JsonNode repeat=saveDraft(initial,"f02-"+org);
   assertEquals("IDEMPOTENT_REPLAY",repeat.get("result").asText());
   assertEquals(revisionId.toString(),repeat.get("revisionId").asText());
   assertEquals(1,number("select count(*) from fin_intake.lib_form_revision where instance_id=?",id));

   var changed=req(id,org,project,"F02",1,Map.of(
     "netRevenue","20000","cogs","10000","operatingExpenses","0"),List.of());
   JsonNode updated=saveDraft(changed,"f02-v2-"+org);
   assertEquals(2,updated.get("revisionNo").asInt());
   assertNotEquals(sha,updated.get("contentSha256").asText());
   assertEquals("12345.67",readDraft(id,org,1).get("values").get("netRevenue").asText());
   assertEquals(sha,readDraft(id,org,1).get("sha256").asText());
   assertEquals("20000",readDraft(id,org,null).get("values").get("netRevenue").asText());
   assertEquals(2,number("select count(*) from fin_intake.lib_form_revision where instance_id=?",id));
   http.perform(post("/api/v1/drafts").header("Idempotency-Key","stale-"+org)
     .contentType(MediaType.APPLICATION_JSON).content(json(changed)))
     .andExpect(status().isConflict());
   http.perform(post("/api/v1/drafts").header("Idempotency-Key","wrong-project-"+org)
     .contentType(MediaType.APPLICATION_JSON)
     .content(json(req(id,org,UUID.randomUUID(),"F02",2,
       Map.of("netRevenue","1","cogs","1","operatingExpenses","1"),List.of()))))
     .andExpect(status().isConflict());
   http.perform(get("/api/v1/drafts/"+id)
     .param("organizationId",UUID.randomUUID().toString()))
     .andExpect(status().isNotFound());
   assertEquals(2,number("select count(*) from fin_intake.lib_form_revision where instance_id=?",id));
 }

 @Test void rowsetRejectsTopLevelValuesAndNullRowsWithoutCreatingFinancialRecords()
     throws Exception {
   UUID org=UUID.randomUUID(),project=UUID.randomUUID();
   var row=new FormDraftService.RowInput(UUID.randomUUID(),10,
     Map.of("paymentRef","PAY-1","postedAt","2026-11-17",
       "amount","1.25","purpose","Purchase","invoiceRef","INV-1"));
   var invalid=req(null,org,project,"DF-CSH-01",0,
     Map.of("amount","999"),List.of(row));
   http.perform(post("/api/v1/drafts")
     .header("Idempotency-Key","rowset-top-values-"+org)
     .contentType(MediaType.APPLICATION_JSON).content(json(invalid)))
     .andExpect(status().isUnprocessableEntity())
     .andExpect(jsonPath("$.code").value("ROWSET_TOP_LEVEL_VALUES_FORBIDDEN"));

   var nullRows=java.util.Arrays.asList((FormDraftService.RowInput)null);
   http.perform(post("/api/v1/drafts")
     .header("Idempotency-Key","rowset-null-row-"+org)
     .contentType(MediaType.APPLICATION_JSON)
     .content(json(req(null,org,project,"DF-CSH-01",0,Map.of(),nullRows))))
     .andExpect(status().isUnprocessableEntity())
     .andExpect(jsonPath("$.code").value("INVALID_ROW"));

   assertEquals(0,number("""
     SELECT count(*) FROM fin_intake.lib_form_instance
     WHERE organization_id=? AND project_id=?
     """,org,project));
 }

 @Test void twoHundredRowClipboardSavesAllIdsValuesAndOrderingWithOneCommittedRevision()
     throws Exception {
   UUID org=UUID.randomUUID(),project=UUID.randomUUID();
   var inputs=new ArrayList<FormDraftService.RowInput>();
   for(int i=0;i<200;i++)inputs.add(new FormDraftService.RowInput(
      UUID.randomUUID(),(i+1)*10,Map.of(
        "paymentRef","PAY-"+i,"postedAt","2026-11-17",
        "amount",i==0?"0.00":String.valueOf(i)+".25",
        "purpose","Purchase","invoiceRef","INV-"+i)));
   JsonNode created=saveDraft(req(null,org,project,"DF-CSH-01",0,Map.of(),inputs),
     "rowset-"+org);
   UUID id=UUID.fromString(created.get("instanceId").asText());
   UUID revision=UUID.fromString(created.get("revisionId").asText());
   assertEquals(200,number("select count(*) from fin_intake.lib_form_revision_row where revision_id=?",
     revision));
   var rows=db.queryForList("""
     select row_id,row_position,values_json from fin_intake.lib_form_revision_row
     where revision_id=? order by row_position
     """,revision);
   assertEquals(200,rows.size());
   for(int i=0;i<200;i++){
     assertEquals(inputs.get(i).rowId(),rows.get(i).get("row_id"));
     assertEquals((i+1)*10,((Number)rows.get(i).get("row_position")).intValue());
     JsonNode v=mapper.readTree(String.valueOf(rows.get(i).get("values_json")));
     assertEquals("PAY-"+i,v.get("paymentRef").asText());
     assertEquals(i==0?"0":i+".25",v.get("amount").asText());
   }
   JsonNode loaded=readDraft(id,org,null);
   assertEquals(200,loaded.get("rows").size());
   assertEquals(created.get("contentSha256").asText(),loaded.get("sha256").asText());
   assertEquals("0",loaded.get("rows").get(0).get("values").get("amount").asText());
   assertEquals("199.25",loaded.get("rows").get(199).get("values").get("amount").asText());
   var retry=saveDraft(req(null,org,project,"DF-CSH-01",0,Map.of(),inputs),"rowset-"+org);
   assertEquals("IDEMPOTENT_REPLAY",retry.get("result").asText());
   assertEquals(1,number("select count(*) from fin_intake.lib_form_revision where instance_id=?",id));
   assertEquals(200,number("select count(*) from fin_intake.lib_form_revision_row where revision_id=?",revision));
 }

 @Test void nonApprovedFormCannotWriteSyntheticFactsDespiteBeingInCatalog()throws Exception{
   UUID org=UUID.randomUUID();
   var request=req(null,org,UUID.randomUUID(),"F01",0,Map.of(),List.of());
   // F01 has a valid catalog identity but no approved typed capture schema.
   // The denial is based on captureMode, not the locale or UI category.
   http.perform(post("/api/v1/drafts").header("Idempotency-Key","unapproved-"+org)
      .contentType(MediaType.APPLICATION_JSON).content(json(request)))
      .andExpect(status().isUnprocessableEntity());
 }

 @Test void gridThirtyOneDaysWritesCanonicalCellsDirectlyIntoIntakeAndNotApplicableIsNotZero()
      throws Exception{
   UUID org=UUID.randomUUID();
   var create=new kz.fin.formlib.api.ReportGridController.CreateFromRange(
     org,"PROOF-"+UUID.randomUUID(),"Thirty one days","KZT",
     "D","2026-11-01",31,List.of("F02.netRevenue"),"COLUMNS");
   var receipt=success(post("/api/v1/report-grids/from-period-range")
     .contentType(MediaType.APPLICATION_JSON).content(json(create)));
   UUID report=UUID.fromString(receipt.get("reportId").asText());
   var cells=new ArrayList<ReportGridService.CellInput>();
   for(int i=1;i<=31;i++){
     var date=java.time.LocalDate.of(2026,11,1).plusDays(i-1).toString();
     if(i==7)cells.add(new ReportGridService.CellInput(
       "F02.netRevenue",date,"NOT_APPLICABLE",null));
     else cells.add(new ReportGridService.CellInput("F02.netRevenue",date,
       "PRESENT",i==1?"0.00":i+".50"));
   }
   var request=new ReportGridService.SaveReport(org,0,cells,"qa","31 day proof","CLIPBOARD");
   JsonNode saved=success(post("/api/v1/report-grids/"+report+"/revisions")
     .header("Idempotency-Key","grid-"+org)
     .contentType(MediaType.APPLICATION_JSON).content(json(request)));
   UUID revision=UUID.fromString(saved.get("revisionId").asText());
   assertEquals(31,number("select count(*) from fin_intake.lib_report_revision_cell where revision_id=?",revision));
   assertEquals(1,number("""
     select count(*) from fin_intake.lib_report_revision_cell
     where revision_id=? and period_code='2026-11-07'
       and value_state='NOT_APPLICABLE' and decimal_value is null
     """,revision));
   assertEquals(1,number("""
     select count(*) from fin_intake.lib_report_revision_cell
     where revision_id=? and period_code='2026-11-01'
       and value_state='PRESENT' and decimal_value='0'
     """,revision));
   String sha=db.queryForObject("""
     select canonical_content_sha256 from fin_intake.lib_report_revision where id=?
     """,String.class,revision);
   assertEquals(saved.get("contentSha256").asText(),sha);
   var pivot=success(get("/api/v1/report-grids/"+report+"/grid")
     .param("organizationId",org.toString()).param("timeAxis","ROWS"));
   assertEquals(sha,pivot.get("contentSha256").asText());
   assertEquals(31,pivot.get("cells").size());
   assertEquals("NOT_APPLICABLE",pivot.get("cells").get(6).get("state").asText());
   var replay=success(post("/api/v1/report-grids/"+report+"/revisions")
     .header("Idempotency-Key","grid-"+org)
     .contentType(MediaType.APPLICATION_JSON).content(json(request)));
   assertEquals("IDEMPOTENT_REPLAY",replay.get("status").asText());
   assertEquals(1,number("select count(*) from fin_intake.lib_report_revision where report_id=?",report));
 }
}
