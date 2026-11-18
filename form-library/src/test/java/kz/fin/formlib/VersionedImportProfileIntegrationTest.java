package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.repository.FormDefinitionRepository;
import kz.fin.formlib.service.FormDraftService;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP -> Profile -> Preview -> existing FormDraftService -> fin_intake. */
@SpringBootTest
@AutoConfigureMockMvc
class VersionedImportProfileIntegrationTest {
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @Autowired FormDefinitionRepository forms;
  @Autowired JdbcTemplate jdbc;

  private JsonNode response(org.springframework.test.web.servlet.RequestBuilder req)
      throws Exception {
    return json.readTree(http.perform(req).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }
  private record Created(UUID id,UUID org,UUID version) {}

  private Created create() throws Exception {
    UUID org=UUID.randomUUID();
    var payload=new FormDraftService.DraftRequest(null,org,UUID.randomUUID(),
      UUID.randomUUID(),UUID.randomUUID(),"F02","M","2026-11",
      "KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",0,
      Map.of("netRevenue","1000","cogs","450","operatingExpenses","100"),
      List.of(),"qa","initial report");
    JsonNode result=response(post("/api/v1/drafts")
      .header("Idempotency-Key","profile-setup-"+org)
      .contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(payload)));
    UUID version=forms.findByFormCode("F02").orElseThrow().getCurrentVersionId();
    return new Created(UUID.fromString(result.get("instanceId").asText()),org,version);
  }
  private JsonNode register(UUID version)throws Exception{
    return response(post("/api/v1/import-profiles")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(Map.of("formVersionId",version))));
  }
  private byte[] export(Created c,String style)throws Exception{
    return http.perform(get("/api/v1/drafts/"+c.id()+"/export.xlsx")
      .param("organizationId",c.org().toString())
      .param("styleCode",style))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
  }
  private static byte[] changed(byte[] file,String value)throws Exception{
    try(var book=WorkbookFactory.create(new ByteArrayInputStream(file));
        var out=new ByteArrayOutputStream()){
      book.getSheet("REPORT").getRow(4).getCell(1).setCellValue(value);
      book.write(out);
      return out.toByteArray();
    }
  }
  private static MockMultipartFile part(byte[] file) {
    return new MockMultipartFile("file","report.xlsx",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",file);
  }
  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      previewRequest(UUID profileId,Created c,byte[] file) {
    return multipart("/api/v1/import-profiles/"+profileId+"/preview")
      .file(part(file)).param("instanceId",c.id().toString())
      .param("organizationId",c.org().toString())
      .param("actor","qa").param("reason","import preview");
  }
  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      commitRequest(UUID ticket,Created c,byte[] file,String key) {
    return multipart("/api/v1/import-profiles/previews/"+ticket+"/commit")
      .file(part(file)).param("instanceId",c.id().toString())
      .param("organizationId",c.org().toString())
      .param("actor","qa").param("reason","import commit")
      .header("Idempotency-Key",key);
  }
  private long revisionCount(Created c){
    return Objects.requireNonNull(jdbc.queryForObject(
      "SELECT COUNT(*) FROM fin_intake.lib_form_revision WHERE instance_id=?",
      Long.class,c.id()));
  }

  @Test
  void previewHasNoWritesThenCommitCreatesImmutableRevision()throws Exception{
    Created c=create();
    JsonNode profile=register(c.version());
    assertEquals("LOCAL_TEST_ONLY",profile.get("lifecycleState").asText());
    assertEquals(profile.get("id").asText(),register(c.version()).get("id").asText(),
      "registration with unchanged schema must be idempotent");
    UUID profileId=UUID.fromString(profile.get("id").asText());
    byte[] file=changed(export(c,"EXCEL"),"1500.25");
    assertEquals(1,revisionCount(c));
    JsonNode preview=response(previewRequest(profileId,c,file));
    assertEquals(1,preview.get("expectedRevision").asInt());
    assertEquals("1500.25",preview.get("values").get("netRevenue").asText());
    assertEquals(1,revisionCount(c),"Preview must not persist financial facts");
    UUID ticket=UUID.fromString(preview.get("previewId").asText());

    JsonNode committed=response(commitRequest(ticket,c,file,"profile-commit-"+c.org()));
    assertEquals(2,committed.get("revisionNo").asInt());
    assertEquals(2,revisionCount(c));
    JsonNode latest=response(get("/api/v1/drafts/"+c.id())
      .param("organizationId",c.org().toString()));
    assertEquals("1500.25",latest.get("values").get("netRevenue").asText());
    assertEquals("450",latest.get("values").get("cogs").asText());
    JsonNode historical=response(get("/api/v1/drafts/"+c.id()+"/revisions/1")
      .param("organizationId",c.org().toString()));
    assertEquals("1000",historical.get("values").get("netRevenue").asText());
    http.perform(commitRequest(ticket,c,file,"other-"+c.org()))
      .andExpect(status().isConflict());
  }

  @Test
  void changedFileAfterPreviewCannotBeCommitted()throws Exception{
    Created c=create();
    UUID profile=UUID.fromString(register(c.version()).get("id").asText());
    byte[] file=changed(export(c,"EXCEL"),"1500");
    UUID ticket=UUID.fromString(response(previewRequest(profile,c,file))
      .get("previewId").asText());
    http.perform(commitRequest(ticket,c,changed(file,"2000"),"tampered-"+c.org()))
      .andExpect(status().isConflict());
    assertEquals(1,revisionCount(c));
  }

  @Test
  void previewNormalizesDecimalCommaFromRealisticF02ValuesThenCommits()throws Exception{
    Created c=create();
    UUID profile=UUID.fromString(register(c.version()).get("id").asText());
    byte[] edited=changed(export(c,"EXCEL"),"88,56");
    JsonNode preview=response(previewRequest(profile,c,edited));
    assertEquals("88.56",preview.get("values").get("netRevenue").asText());
    assertEquals(1,revisionCount(c));
    UUID ticket=UUID.fromString(preview.get("previewId").asText());
    JsonNode receipt=response(commitRequest(ticket,c,edited,"comma-decimal-"+c.org()));
    assertEquals(2,receipt.get("revisionNo").asInt());
    JsonNode current=response(get("/api/v1/drafts/"+c.id())
      .param("organizationId",c.org().toString()));
    assertEquals("88.56",current.get("values").get("netRevenue").asText());
    assertEquals(2,revisionCount(c));
  }

  @Test
  void previewReturnsActionableFieldErrorForAmbiguousNumber()throws Exception{
    Created c=create();
    UUID profile=UUID.fromString(register(c.version()).get("id").asText());
    byte[] ambiguous=changed(export(c,"EXCEL"),"1,234");
    var response=http.perform(previewRequest(profile,c,ambiguous))
      .andExpect(status().isUnprocessableEntity()).andReturn();
    JsonNode body=json.readTree(response.getResponse().getContentAsString());
    assertTrue(body.get("code").asText().contains(
      "IMPORT_FIELD:netRevenue:AMBIGUOUS_NUMBER"));
    assertEquals(1,revisionCount(c));
  }

  @Test
  void previewCannotImportToAnotherOrganization()throws Exception{
    Created c=create();
    UUID profile=UUID.fromString(register(c.version()).get("id").asText());
    byte[] file=export(c,"EXCEL");
    http.perform(multipart("/api/v1/import-profiles/"+profile+"/preview")
      .file(part(file)).param("instanceId",c.id().toString())
      .param("organizationId",UUID.randomUUID().toString())
      .param("actor","qa").param("reason","wrong organization"))
      .andExpect(status().isNotFound());
    assertEquals(1,revisionCount(c));
  }

  @Test
  void invalidNumericValueRejectedAtPreviewWithoutWrites()throws Exception{
    Created c=create();
    UUID profile=UUID.fromString(register(c.version()).get("id").asText());
    byte[] invalid=changed(export(c,"EXCEL"),"not-a-number");
    http.perform(previewRequest(profile,c,invalid))
      .andExpect(status().isUnprocessableEntity());
    assertEquals(1,revisionCount(c));
  }
}
