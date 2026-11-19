package kz.fin.formlib.calc.ingest;

import kz.fin.formlib.service.FormAttachmentStore;
import kz.fin.formlib.service.FormDraftService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.util.CellReference;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in PostgreSQL 16 read-back gate for exact FIN-LIB-CONTENT-V1.
 * Each test uses a dedicated synthetic organization and fresh report slot.
 * NO fin_fact writes and NO independent financial S2.
 */
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
@AutoConfigureMockMvc
@SpringBootTest(properties={
    "fin.uat.editor.enabled=false",
    "fin.attachments.directory=./target/ingest2-verified-attachments"
})
class Ingest2PostgresqlIntegrityTest {
  @Container
  static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_ingest2_qualification")
      .withUsername("fin").withPassword("pg_ingest2_only");

  @DynamicPropertySource
  static void dataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url",db::getJdbcUrl);
    registry.add("spring.datasource.username",db::getUsername);
    registry.add("spring.datasource.password",db::getPassword);
    registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired FormDraftService drafts;
  @Autowired FormFactCandidateService candidates;
  @Autowired FormFactSourceReader sourceReader;
  @Autowired FormFactSourceIntegrityVerifier verifier;
  @Autowired FormAttachmentStore attachments;
  @Autowired JdbcTemplate sql;
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;

  private record Slot(UUID org,UUID entity,UUID scope,UUID project) {
    static Slot random() {
      return new Slot(UUID.randomUUID(),UUID.randomUUID(),
          UUID.randomUUID(),UUID.randomUUID());
    }
  }

  private static FormDraftService.DraftRequest request(
      Slot s,String formCode, UUID instance,int revision,
      Map<String,String> values,List<FormDraftService.RowInput> rows) {
    return new FormDraftService.DraftRequest(
        instance,s.org(),s.entity(),s.scope(),s.project(),formCode,
        "M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
        revision,values,rows,"ingest2-ci","source SHA rehash");
  }

  private FormDraftService.DraftReceipt saveViaHttp(FormDraftService.DraftRequest command,
      String key) throws Exception {
    var result=http.perform(post("/api/v1/drafts")
        .header("Idempotency-Key",key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(command)))
        .andExpect(status().isOk()).andReturn();
    return json.readValue(result.getResponse().getContentAsByteArray(),
        FormDraftService.DraftReceipt.class);
  }

  private FormDraftService.DraftReceipt attachmentViaHttp(FormDraftService.DraftRequest command,
      String key, byte[] bytes) throws Exception {
    var requestPart=new MockMultipartFile("draft","draft.json",
        "application/json",json.writeValueAsBytes(command));
    var filePart=new MockMultipartFile("file","proof-"+command.formCode()+".pdf",
        "application/pdf",bytes);
    var result=http.perform(multipart("/api/v1/drafts/with-attachment")
        .file(requestPart).file(filePart)
        .header("Idempotency-Key",key))
        .andExpect(status().isOk()).andReturn();
    return json.readValue(result.getResponse().getContentAsByteArray(),
        FormDraftService.DraftReceipt.class);
  }

  private static String nextXlsxValue(String type) {
    return switch(type) {
      case "MONEY" -> "200";
      case "DECIMAL" -> "200.5";
      case "INTEGER" -> "19";
      case "DATE" -> "2026-11-18";
      case "BOOLEAN" -> "true";
      case "REF","CODE","PERIOD","ENUM","TEXT" -> "R2_XLSX_REVISED";
      default -> throw new AssertionError("UNSUPPORTED_XLSX_TYPE:"+type);
    };
  }

  private record EditedXlsx(byte[] bytes,String fieldCode,String value) {}
  private static EditedXlsx changeFirstExcelBinding(byte[] file) throws Exception {
    try(var workbook=WorkbookFactory.create(new ByteArrayInputStream(file));
        var output=new ByteArrayOutputStream()) {
      var metadata=workbook.getSheet("PARAMETERS");
      assertNotNull(metadata);
      String binding=metadata.getRow(14).getCell(0).getStringCellValue();
      int separator=binding.lastIndexOf('|');
      String fieldCode=separator<0?binding:binding.substring(separator+1);
      String fieldType=metadata.getRow(14).getCell(2).getStringCellValue();
      String value=nextXlsxValue(fieldType);
      assertFalse(fieldCode.isBlank());
      String address=metadata.getRow(14).getCell(1).getStringCellValue();
      var location=new CellReference(address);
      var target=workbook.getSheet("REPORT").getRow(location.getRow())
          .getCell(location.getCol());
      target.setCellValue(value);
      workbook.write(output);
      return new EditedXlsx(output.toByteArray(),fieldCode,value);
    }
  }

  /**
   * One reportable PostgreSQL integration case per actual catalog form.
   * Each case exercises two immutable revisions, re-open/readback, replay,
   * full source SHA reconstruction and cross-organization access denial.
   * No financial S2, fact-set approval or UI/browser claim.
   */
  @org.junit.jupiter.api.TestFactory
  java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> all166FormLifecycleCases() {
    var physical=sql.query("""
        SELECT d.form_code, v.content_mode, ff.field_code, ff.field_type
        FROM fin_lib.lib_form_definition d
        JOIN fin_lib.lib_form_version v ON v.id=d.current_version_id
        JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
        ORDER BY d.form_code, ff.field_code
        """,(rs,i)->new String[]{
            rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)});
    Map<String,List<String[]>> byForm=new java.util.TreeMap<>();
    for(var row:physical)
      byForm.computeIfAbsent(row[0],k->new java.util.ArrayList<>()).add(row);
    assertEquals(166,byForm.size(),"CATALOG_166_COVERAGE");
    var certifiedRegistry=FormFactIngestionRegistry.loadDefault();
    assertEquals(certifiedRegistry.profiles().keySet(),byForm.keySet(),
        "POSTGRES_166_CATALOG_MUST_MATCH_REPORT_MANIFEST");
    for(var entry:byForm.entrySet())
      certifiedRegistry.require(entry.getKey(),entry.getValue().getFirst()[1]);
    assertEquals(877,physical.size(),"CATALOG_877_FIELD_CONTRACTS");
    assertEquals(3,byForm.values().stream().filter(v->
        "SINGLE_RECORD".equals(v.getFirst()[1])).count());
    assertEquals(151,byForm.values().stream().filter(v->
        "ROWSET".equals(v.getFirst()[1])).count());
    assertEquals(12,byForm.values().stream().filter(v->
        "EVIDENCE".equals(v.getFirst()[1])).count());
    return byForm.entrySet().stream().map(e->
        org.junit.jupiter.api.DynamicTest.dynamicTest(
            e.getKey()+" ["+e.getValue().getFirst()[1]+"]"
            +" save/reopen/history/SHA/tenant/negative",
            ()->verifyCompleteLifecycle(e.getKey(),e.getValue())));
  }

  private static String fixtureValue(String fieldType, boolean secondRow) {
    return switch(fieldType) {
      case "MONEY" -> secondRow?"0":"125.25";
      case "DECIMAL" -> secondRow?"0":"42.5";
      case "INTEGER" -> secondRow?"0":"7";
      case "DATE" -> secondRow?"2026-11-16":"2026-11-15";
      case "BOOLEAN" -> secondRow?"false":"true";
      case "REF","CODE","PERIOD","ENUM","TEXT" ->
          secondRow?"SYNTHETIC_ROW_2":"SYNTHETIC";
      default -> throw new AssertionError("UNSUPPORTED_PHYSICAL_FIELD_TYPE:"+fieldType);
    };
  }

  private static String changedValue(String fieldType) {
    return switch(fieldType) {
      case "MONEY" -> "126.25";
      case "DECIMAL" -> "43.5";
      case "INTEGER" -> "8";
      case "DATE" -> "2026-11-17";
      case "BOOLEAN" -> "false";
      case "REF","CODE","PERIOD","ENUM","TEXT" -> "SYNTHETIC_REVISED";
      default -> throw new AssertionError("UNSUPPORTED_FIELD_TYPE:"+fieldType);
    };
  }

  private void verifyCompleteLifecycle(String code,List<String[]> definition) throws Exception {
    String mode=definition.getFirst()[1];
    var catalogResponse=http.perform(get("/api/v1/forms/"+code))
        .andExpect(status().isOk()).andReturn();
    var detail=json.readTree(catalogResponse.getResponse().getContentAsByteArray());
    assertEquals(code,detail.path("summary").path("formCode").asText(),code);
    assertEquals(mode,detail.path("summary").path("contentMode").asText(),code);
    assertEquals(definition.size(),detail.path("fields").size(),code);
    for(String[] field:definition) {
      boolean found=false;
      for(var rendered:detail.path("fields")) {
        if(field[2].equals(rendered.path("fieldCode").asText())) {
          assertEquals(field[3],rendered.path("fieldType").asText(),code+"."+field[2]);
          found=true;
        }
      }
      assertTrue(found,"FORM_HTTP_FIELD_MISSING:"+code+"."+field[2]);
    }
    Slot tenant=Slot.random();
    Map<String,String> values=new java.util.TreeMap<>();
    for(String[] field:definition) {
      assertEquals(mode,field[1],"MIXED_CONTENT_MODES:"+code);
      if(values.putIfAbsent(field[2],fixtureValue(field[3],false))!=null)
        fail("DUPLICATE_PHYSICAL_FIELD:"+code+"."+field[2]);
    }
    List<FormDraftService.RowInput> inputRows=List.of();
    Map<String,String> top=values;
    if("ROWSET".equals(mode)) {
      Map<String,String> second=new java.util.TreeMap<>();
      for(String[] field:definition)
        second.put(field[2],fixtureValue(field[3],true));
      inputRows=List.of(
          new FormDraftService.RowInput(UUID.randomUUID(),10,values),
          new FormDraftService.RowInput(UUID.randomUUID(),20,second));
      top=Map.of();
    }else if(!"EVIDENCE".equals(mode)&&!"SINGLE_RECORD".equals(mode)){
      fail("UNEXPECTED_MODE:"+code+":"+mode);
    }
    var initialRequest=request(tenant,code,null,0,top,inputRows);
    String initialKey="all166-lifecycle-v1-"+code+"-"+tenant.org();
    FormDraftService.DraftReceipt first;
    byte[] originalEvidence=("%PDF-1.4\\nSynthetic test evidence for "+code)
        .getBytes(StandardCharsets.UTF_8);
    if("EVIDENCE".equals(mode))
      first=attachmentViaHttp(initialRequest,initialKey,originalEvidence);
    else
      first=saveViaHttp(initialRequest,initialKey);
    assertEquals(1,first.revisionNo(),code);
    assertEquals("UNQUALIFIED_DRAFT",first.result(),code);

    var opened=drafts.read(first.instanceId(),tenant.org());
    assertEquals(1,opened.revisionNo(),code);
    assertEquals(first.instanceId(),opened.instanceId(),code);
    assertEquals(code,opened.formCode(),code);
    assertEquals(mode,opened.contentMode(),code);
    assertEquals(first.contentSha256(),opened.sha256(),code);
    assertEquals(tenant.entity(),opened.economicEntityId(),code);
    assertEquals(tenant.scope(),opened.businessScopeId(),code);
    assertEquals(tenant.project(),opened.projectId(),code);
    assertEquals("KZT",opened.currency(),code);
    assertEquals("M",opened.periodType(),code);
    assertEquals("2026-11",opened.periodCode(),code);
    assertEquals(top,opened.values(),"PERSISTED_VALUES_CHANGED:"+code);
    var initialHttp=http.perform(get("/api/v1/drafts/"+first.instanceId())
        .param("organizationId",tenant.org().toString()))
        .andExpect(status().isOk()).andReturn();
    var initialPayload=json.readTree(initialHttp.getResponse().getContentAsByteArray());
    assertEquals(code,initialPayload.path("formCode").asText(),code);
    assertEquals(1,initialPayload.path("revisionNo").asInt(),code);
    assertEquals(first.contentSha256(),initialPayload.path("sha256").asText(),code);
    assertEquals(opened.rows().size(),initialPayload.path("rows").size(),code);
    assertEquals(inputRows.size(),opened.rows().size(),code);
    for(var expected:inputRows) {
      var actual=opened.rows().stream()
          .filter(r->r.rowId().equals(expected.rowId())).findFirst().orElseThrow();
      assertEquals(expected.rowPosition(),actual.rowPosition(),code);
      assertEquals(expected.values(),actual.values(),"PERSISTED_ROW_CHANGED:"+code);
    }
    var exactSlot=drafts.readExactSlot(tenant.org(),tenant.entity(),
        tenant.scope(),tenant.project(),code,"M","2026-11",
        "PERIOD_ONLY","KZT","MANAGEMENT","SYNTHETIC");
    assertEquals(first.instanceId(),exactSlot.instanceId(),code);
    assertEquals(first.contentSha256(),exactSlot.sha256(),code);
    if("EVIDENCE".equals(mode)) {
      assertNotNull(opened.attachment(),"EVIDENCE_ATTACHMENT_NOT_PERSISTED:"+code);
      assertTrue(opened.attachment().sizeBytes()>0,code);
    }else assertNull(opened.attachment(),code);

    var original=candidates.extractExact(first.revisionId(),tenant.org(),tenant.project());
    assertEquals("SOURCE_SHA_REVERIFIED",original.sourceIntegrityState(),code);
    assertEquals("UNQUALIFIED_SOURCE_PROJECTION",original.qualificationState(),code);
    assertEquals(first.revisionId(),original.revisionId(),code);
    assertEquals(first.instanceId(),original.instanceId(),code);
    assertEquals(mode,original.contentMode(),code);
    assertEquals(first.contentSha256(),original.sourceSha256Claim(),code);
    assertEquals(definition.size()*("ROWSET".equals(mode)?2:1),
        original.numeric().size()+original.attributes().size(),code);
    String storedOrder=sql.queryForObject(
        "SELECT row_order_sha256 FROM fin_intake.lib_form_revision WHERE id=?",
        String.class,first.revisionId());
    assertEquals(storedOrder,original.rowOrderSha256(),code);
    if("EVIDENCE".equals(mode)) {
      assertTrue(original.numeric().isEmpty(),code);
      assertEquals(1,original.evidence().size(),code);
    }
    if("ROWSET".equals(mode)) {
      var ids=inputRows.stream().map(FormDraftService.RowInput::rowId)
          .collect(java.util.stream.Collectors.toSet());
      assertTrue(original.numeric().stream().allMatch(n->ids.contains(n.rowId())),code);
    }

    // HTTP command replay, including attachment, must never allocate a second revision.
    var replay="EVIDENCE".equals(mode)
        ?attachmentViaHttp(initialRequest,initialKey,originalEvidence)
        :saveViaHttp(initialRequest,initialKey);
    assertEquals(first.revisionId(),replay.revisionId(),code);
    assertEquals("IDEMPOTENT_REPLAY",replay.result(),code);
    assertEquals(1,sql.queryForObject(
        "SELECT count(*) FROM fin_intake.lib_form_revision WHERE instance_id=?",
        Integer.class,first.instanceId()),code);
    var changedTop=new java.util.TreeMap<>(top);
    List<FormDraftService.RowInput> changedRows=inputRows;
    String changingField=definition.getFirst()[2];
    String nextValue=changedValue(definition.getFirst()[3]);
    if("ROWSET".equals(mode)) {
      changedRows=new java.util.ArrayList<>(inputRows);
      var sourceRow=inputRows.getFirst();
      var changedValues=new java.util.TreeMap<>(sourceRow.values());
      changedValues.put(changingField,nextValue);
      changedRows.set(0,new FormDraftService.RowInput(
          sourceRow.rowId(),sourceRow.rowPosition(),changedValues));
    }else {
      changedTop.put(changingField,nextValue);
    }

    var revised=saveViaHttp(request(tenant,code,first.instanceId(),1,
        changedTop,changedRows),"all166-lifecycle-v2-"+code+"-"+tenant.org());
    assertEquals(2,revised.revisionNo(),code);
    assertNotEquals(first.revisionId(),revised.revisionId(),code);
    assertNotEquals(first.contentSha256(),revised.contentSha256(),code);
    var current=drafts.read(first.instanceId(),tenant.org());
    assertEquals(2,current.revisionNo(),code);
    assertEquals(revised.contentSha256(),current.sha256(),code);
    if("ROWSET".equals(mode)) {
      assertEquals(2,current.rows().size(),code);
      UUID firstRowId=inputRows.getFirst().rowId();
      assertEquals(nextValue,current.rows().stream()
          .filter(r->r.rowId().equals(firstRowId))
          .findFirst().orElseThrow().values().get(changingField),code);
    }else {
      assertEquals(nextValue,current.values().get(changingField),code);
    }
    var historical=drafts.readRevision(first.instanceId(),tenant.org(),1);
    assertEquals(1,historical.revisionNo(),code);
    assertEquals(first.contentSha256(),historical.sha256(),code);
    assertEquals(opened.values(),historical.values(),code);
    assertEquals(opened.rows(),historical.rows(),code);
    var historicalHttp=http.perform(get("/api/v1/drafts/"+first.instanceId()+"/revisions/1")
        .param("organizationId",tenant.org().toString()))
        .andExpect(status().isOk()).andReturn();
    var historicalPayload=json.readTree(historicalHttp.getResponse().getContentAsByteArray());
    assertEquals(1,historicalPayload.path("revisionNo").asInt(),code);
    assertEquals(first.contentSha256(),historicalPayload.path("sha256").asText(),code);
    var currentHttp=http.perform(get("/api/v1/drafts/"+first.instanceId())
        .param("organizationId",tenant.org().toString()))
        .andExpect(status().isOk()).andReturn();
    assertEquals(revised.contentSha256(),
        json.readTree(currentHttp.getResponse().getContentAsByteArray())
          .path("sha256").asText(),code);
    // XLSX transport is intentionally limited to SINGLE_RECORD/ROWSET.
    // Evidence documents use their attachment-download transport instead.
    var xlsxRequest=get("/api/v1/drafts/"+first.instanceId()+"/export.xlsx")
        .param("organizationId",tenant.org().toString())
        .param("styleCode","EXCEL").param("locale","ru");
    int expectedRevisions=2;
    if("EVIDENCE".equals(mode)) {
      http.perform(xlsxRequest).andExpect(status().isUnprocessableEntity());
      var downloadRequest=get("/api/v1/drafts/"+first.instanceId()
          +"/revisions/1/attachment")
          .param("organizationId",tenant.org().toString())
          .param("projectId",tenant.project().toString());
      var downloaded=http.perform(downloadRequest).andExpect(status().isOk())
          .andReturn().getResponse().getContentAsByteArray();
      assertArrayEquals(originalEvidence,downloaded,"EVIDENCE_BYTES_CHANGED:"+code);
      assertEquals(opened.attachment().sha256(),FormAttachmentStore
          .validate("proof-"+code+".pdf",downloaded).sha256(),code);
      assertArrayEquals(downloaded,http.perform(get("/api/v1/drafts/"
          +first.instanceId()+"/revisions/2/attachment")
          .param("organizationId",tenant.org().toString())
          .param("projectId",tenant.project().toString()))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),code);
      var file=Path.of("target/ingest2-verified-attachments")
          .toAbsolutePath().normalize()
          .resolve(opened.attachment().id()+".bin");
      var originalBytes=Files.readAllBytes(file);
      try {
        Files.write(file,"CORRUPTED_EVIDENCE".getBytes(StandardCharsets.UTF_8));
        http.perform(downloadRequest).andExpect(status().isConflict());
      } finally {
        Files.write(file,originalBytes);
      }
      assertArrayEquals(originalEvidence,http.perform(downloadRequest)
          .andExpect(status().isOk()).andReturn()
          .getResponse().getContentAsByteArray(),code);
    }else{
      // HTTP XLSX export -> edit one server-pinned binding -> HTTP import.
      byte[] exported=http.perform(xlsxRequest).andExpect(status().isOk())
          .andReturn().getResponse().getContentAsByteArray();
      assertTrue(exported.length>100,"XLSX_EXPORT_EMPTY:"+code);
      var xlsxChange=changeFirstExcelBinding(exported);
      String changedField=xlsxChange.fieldCode();
      String thirdValue=xlsxChange.value();
      byte[] edited=xlsxChange.bytes();
      var part=new MockMultipartFile("file","roundtrip.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",edited);
      var importRequest=multipart("/api/v1/drafts/"+first.instanceId()+"/import.xlsx")
          .file(part).param("organizationId",tenant.org().toString())
          .param("actor","qa-r2").param("reason","166-form XLSX round trip")
          .header("Idempotency-Key","r2-xlsx-"+code+"-"+tenant.org());
      var importResponse=http.perform(importRequest).andExpect(status().isOk())
          .andReturn().getResponse().getContentAsByteArray();
      var imported=json.readValue(importResponse,FormDraftService.DraftReceipt.class);
      assertEquals(3,imported.revisionNo(),code);
      assertNotEquals(revised.contentSha256(),imported.contentSha256(),code);
      var third=drafts.read(first.instanceId(),tenant.org());
      assertEquals(3,third.revisionNo(),code);
      assertEquals(imported.contentSha256(),third.sha256(),code);
      if("ROWSET".equals(mode)) {
        assertEquals(2,third.rows().size(),code);
        UUID rowId=inputRows.getFirst().rowId();
        assertEquals(thirdValue,third.rows().stream()
            .filter(r->r.rowId().equals(rowId)).findFirst().orElseThrow()
            .values().get(changedField),code);
      }else assertEquals(thirdValue,third.values().get(changedField),code);
      assertEquals(first.contentSha256(),
          drafts.readRevision(first.instanceId(),tenant.org(),1).sha256(),code);
      assertEquals(revised.contentSha256(),
          drafts.readRevision(first.instanceId(),tenant.org(),2).sha256(),code);
      assertEquals(imported.contentSha256(),
          candidates.extractExact(imported.revisionId(),tenant.org(),tenant.project())
              .sourceSha256Claim(),code);
      // Stale workbook cannot silently create revision 4.
      var stale=new MockMultipartFile("file","stale.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",edited);
      http.perform(multipart("/api/v1/drafts/"+first.instanceId()+"/import.xlsx")
          .file(stale).param("organizationId",tenant.org().toString())
          .param("actor","qa-r2").param("reason","stale replay")
          .header("Idempotency-Key","r2-stale-"+code+"-"+tenant.org()))
          .andExpect(status().isConflict());
      expectedRevisions=3;
    }
    assertEquals(original.sourceSha256Claim(),
        candidates.extractExact(first.revisionId(),tenant.org(),tenant.project())
            .sourceSha256Claim(),code);
    var revisedProjection=candidates.extractExact(
        revised.revisionId(),tenant.org(),tenant.project());
    assertEquals(revised.contentSha256(),revisedProjection.sourceSha256Claim(),code);
    if("EVIDENCE".equals(mode)) {
      assertNotNull(current.attachment(),"EVIDENCE_ATTACHMENT_LOST_ON_REVISION:"+code);
      assertEquals(opened.attachment().sha256(),current.attachment().sha256(),code);
      assertEquals(1,revisedProjection.evidence().size(),code);
    }

    assertThrows(org.springframework.web.server.ResponseStatusException.class,
        ()->drafts.read(first.instanceId(),UUID.randomUUID()),code);
    http.perform(get("/api/v1/drafts/"+first.instanceId())
        .param("organizationId",UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
    assertThrows(IllegalArgumentException.class,()->
        candidates.extractExact(first.revisionId(),UUID.randomUUID(),tenant.project()),code);
    assertThrows(IllegalArgumentException.class,()->
        candidates.extractExact(first.revisionId(),tenant.org(),UUID.randomUUID()),code);

    // Unknown field must fail before a third revision is committed.
    Map<String,String> badTop=new java.util.TreeMap<>(changedTop);
    List<FormDraftService.RowInput> badRows=changedRows;
    if("ROWSET".equals(mode)) {
      var invalid=new java.util.ArrayList<>(changedRows);
      var item=invalid.getFirst();
      var badValues=new java.util.TreeMap<>(item.values());
      badValues.put("__forbidden_166_field__","100");
      invalid.set(0,new FormDraftService.RowInput(
          item.rowId(),item.rowPosition(),badValues));
      badRows=invalid;
    }else {
      badTop.put("__forbidden_166_field__","100");
    }
    var finalRows=badRows;
    var invalidRequest=request(tenant,code,first.instanceId(),expectedRevisions,
        badTop,finalRows);
    var rejected=http.perform(post("/api/v1/drafts")
        .header("Idempotency-Key","all166-reject-"+code+"-"+tenant.org())
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(invalidRequest)))
        .andExpect(status().isUnprocessableEntity()).andReturn();
    assertEquals("UNKNOWN_FIELD:__forbidden_166_field__",
        json.readTree(rejected.getResponse().getContentAsByteArray())
            .path("code").asText(),code);
    assertEquals(expectedRevisions,sql.queryForObject(
        "SELECT count(*) FROM fin_intake.lib_form_revision WHERE instance_id=?",
        Integer.class,first.instanceId()),code);
    assertEquals(0,sql.queryForObject(
        "SELECT count(*) FROM fin_fact.fact_set WHERE organization_id=?",
        Integer.class,tenant.org()),"UNQUALIFIED_FORM_WROTE_FIN_FACT:"+code);
  }

  @Test void f02ExactHistoricalRevisionIsVerifiedNotLatest() {
    var slot=Slot.random();
    var first=drafts.save(request(slot,"F02",null,0,
        Map.of("netRevenue","00120.50","cogs","40.00","operatingExpenses","10"),
        List.of()),"ingest2-f02-first-"+slot.org());
    var updated=drafts.save(request(slot,"F02",first.instanceId(),1,
        Map.of("netRevenue","220.5","cogs","40","operatingExpenses","10"),
        List.of()),"ingest2-f02-update-"+slot.org());
    assertNotEquals(first.contentSha256(),updated.contentSha256());

    var original=candidates.extractExact(first.revisionId(),slot.org(),slot.project());
    var latest=candidates.extractExact(updated.revisionId(),slot.org(),slot.project());
    assertEquals("SOURCE_SHA_REVERIFIED",original.sourceIntegrityState());
    assertEquals("UNQUALIFIED_SOURCE_PROJECTION",original.qualificationState());
    assertEquals(first.contentSha256(),original.sourceSha256Claim());
    assertEquals(3,original.numeric().size());
    assertEquals("120.5",original.numeric().stream()
        .filter(n->n.sourceFieldCode().equals("netRevenue"))
        .findFirst().orElseThrow().numericValue().toPlainString());
    assertEquals("220.5",latest.numeric().stream()
        .filter(n->n.sourceFieldCode().equals("netRevenue"))
        .findFirst().orElseThrow().numericValue().toPlainString());
    assertThrows(IllegalArgumentException.class,
        ()->candidates.extractExact(first.revisionId(),UUID.randomUUID(),slot.project()));
    assertThrows(IllegalArgumentException.class,
        ()->candidates.extractExact(first.revisionId(),slot.org(),UUID.randomUUID()));

    var source=sourceReader.readExact(first.revisionId(),slot.org(),slot.project());
    var corruptedValues=new java.util.TreeMap<>(source.values());
    corruptedValues.put("cogs","41");
    var forged=new FormFactCandidateExtractor.SourceSnapshot(
        source.revisionId(),source.instanceId(),source.formVersionId(),
        source.formCode(),source.contentMode(),source.claimedCanonicalContentSha256(),
        source.sourceSchemaSha256(),source.sourceMappingSha256(),source.scope(),
        source.fields(),corruptedValues,source.rows(),source.evidence());
    assertEquals("SOURCE_CONTENT_SHA_MISMATCH",assertThrows(
        IllegalStateException.class,()->verifier.verify(forged)).getMessage());

    // FACT-0 schema exists, but an ordinary form save MUST NOT materialize
    // financially qualified facts or enqueue a qualified calculation.
    // Migration 022 adds immutable receipt as the fourth fin_fact table.
    assertEquals(4,sql.queryForObject(
        "SELECT count(*) FROM information_schema.tables WHERE table_schema='fin_fact'",
        Integer.class));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_fact.fact_set",Integer.class));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_fact.fact_observation",Integer.class));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_job",Integer.class));
  }

  @Test void rowsetOrderAndValueHashesAreIndependentlyReconstructed() {
    var slot=Slot.random();
    UUID rowA=UUID.randomUUID(),rowB=UUID.randomUUID();
    Map<String,String> a=Map.of("paymentRef","PAY-A","postedAt","2026-11-17",
        "amount","100","purpose","Purchase","invoiceRef","INV-A");
    Map<String,String> b=Map.of("paymentRef","PAY-B","postedAt","2026-11-18",
        "amount","200","purpose","Purchase","invoiceRef","INV-B");
    var first=drafts.save(request(slot,"DF-CSH-01",null,0,Map.of(),
        List.of(new FormDraftService.RowInput(rowA,1,a),
            new FormDraftService.RowInput(rowB,2,b))),
        "ingest2-rowset-first-"+slot.org());
    var reordered=drafts.save(request(slot,"DF-CSH-01",first.instanceId(),1,
        Map.of(),List.of(new FormDraftService.RowInput(rowA,2,a),
            new FormDraftService.RowInput(rowB,1,b))),
        "ingest2-rowset-reorder-"+slot.org());
    assertEquals(first.contentSha256(),reordered.contentSha256(),
        "Presentation reorder must not affect economic content identity");
    String firstOrder=sql.queryForObject(
        "SELECT row_order_sha256 FROM fin_intake.lib_form_revision WHERE id=?",
        String.class,first.revisionId());
    String nextOrder=sql.queryForObject(
        "SELECT row_order_sha256 FROM fin_intake.lib_form_revision WHERE id=?",
        String.class,reordered.revisionId());
    assertNotEquals(firstOrder,nextOrder);

    var original=candidates.extractExact(first.revisionId(),slot.org(),slot.project());
    var changed=candidates.extractExact(reordered.revisionId(),slot.org(),slot.project());
    assertEquals("SOURCE_SHA_REVERIFIED",original.sourceIntegrityState());
    assertEquals("SOURCE_SHA_REVERIFIED",changed.sourceIntegrityState());
    assertEquals(2,original.numeric().size());
    assertEquals(2,changed.numeric().size());
    assertEquals(2,original.numeric().stream().map(n->n.rowId()).distinct().count());

    var src=sourceReader.readExact(first.revisionId(),slot.org(),slot.project());
    var swapped=List.of(
        new FormFactCandidateExtractor.Row(src.rows().get(0).rowId(),2,src.rows().get(0).values()),
        new FormFactCandidateExtractor.Row(src.rows().get(1).rowId(),1,src.rows().get(1).values()));
    var forged=new FormFactCandidateExtractor.SourceSnapshot(
        src.revisionId(),src.instanceId(),src.formVersionId(),src.formCode(),
        src.contentMode(),src.claimedCanonicalContentSha256(),src.sourceSchemaSha256(),
        src.sourceMappingSha256(),src.scope(),src.fields(),src.values(),swapped,src.evidence());
    assertEquals("SOURCE_ROW_ORDER_SHA_MISMATCH",
        assertThrows(IllegalStateException.class,()->verifier.verify(forged)).getMessage());
  }

  @Test void evidenceMetadataAndStoredBytesAreBothRequired() throws Exception {
    var slot=Slot.random();
    var bytes="%PDF-1.4\nsynthetic evidence content".getBytes(StandardCharsets.UTF_8);
    var upload=FormAttachmentStore.validate("bank-statement.pdf",bytes);
    var saved=drafts.saveWithAttachment(request(slot,"D01",null,0,
        Map.of("documentRef","REF-2026-11","documentType","BANK_STATEMENT",
            "documentDate","2026-11-19","version","1","issuerRef","BANK",
            "attachmentRef","FILE","status","VALID"),
        List.of()),"ingest2-evidence-"+slot.org(),upload);
    var projection=candidates.extractExact(saved.revisionId(),slot.org(),slot.project());
    assertEquals("SOURCE_SHA_REVERIFIED",projection.sourceIntegrityState());
    assertEquals("UNQUALIFIED_SOURCE_PROJECTION",projection.qualificationState());
    assertTrue(projection.numeric().isEmpty());
    assertEquals(7,projection.attributes().size());
    assertEquals(1,projection.evidence().size());

    var attachment=attachments.find(saved.revisionId()).orElseThrow();
    Path file=Path.of("target/ingest2-verified-attachments")
        .toAbsolutePath().normalize().resolve(attachment.id()+".bin");
    byte[] original=Files.readAllBytes(file);
    try {
      Files.write(file,"tampered financial proof".getBytes(StandardCharsets.UTF_8));
      assertThrows(org.springframework.web.server.ResponseStatusException.class,
          ()->candidates.extractExact(saved.revisionId(),slot.org(),slot.project()));
    } finally {
      Files.write(file,original);
    }
    assertEquals("SOURCE_SHA_REVERIFIED",
        candidates.extractExact(saved.revisionId(),slot.org(),slot.project()).sourceIntegrityState());
  }
}
