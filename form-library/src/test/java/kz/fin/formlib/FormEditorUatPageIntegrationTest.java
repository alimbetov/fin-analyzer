package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.service.FormDraftService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Temporary UAT view must render and resolve the exact saved FIN form version.
 * Existing controller tests cover create/read/update/XLSX operations themselves.
 */
@SpringBootTest(properties="fin.uat.editor.enabled=true")
@AutoConfigureMockMvc
class FormEditorUatPageIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void rendersViewAndContainsOnlyExistingRestPaths() throws Exception {
    var page=mvc.perform(get("/uat/form-editor"))
      .andExpect(status().isOk())
      .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
      .andReturn().getResponse().getContentAsString();
    assertTrue(page.contains("FIN Form Library"));
    assertTrue(page.contains("id=\"save\""));
    assertTrue(page.contains("id=\"export\""));
    assertTrue(page.contains("id=\"preview\""));
    assertTrue(page.contains("id=\"commit\""));
    assertTrue(page.contains("/api/v1"));
    assertTrue(page.contains("/import-profiles"));
    assertTrue(page.contains("/export.xlsx"));
    assertTrue(page.contains("/uat-helper/drafts/"));
  }

  @Test
  void helperUsesOrganizationFilterAndReturnsPersistedFormVersion() throws Exception {
    UUID org=UUID.randomUUID();
    var draft=new FormDraftService.DraftRequest(null,org,
      UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"F02",
      "M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",0,
      Map.of("netRevenue","1234","cogs","500","operatingExpenses","100"),
      List.of(),"uat-test","create");
    JsonNode saved=json.readTree(mvc.perform(post("/api/v1/drafts")
      .header("Idempotency-Key","uat-create-"+org)
      .contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(draft)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    UUID id=UUID.fromString(saved.get("instanceId").asText());
    var helper="/api/v1/uat-helper/drafts/"+id+"/version";
    mvc.perform(get(helper).param("organizationId",UUID.randomUUID().toString()))
      .andExpect(status().isNotFound());
    JsonNode result=json.readTree(mvc.perform(get(helper)
      .param("organizationId",org.toString()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertNotNull(UUID.fromString(result.get("formVersionId").asText()));
    mvc.perform(get("/api/v1/drafts/"+id).param("organizationId",org.toString()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.revisionNo").value(1))
      .andExpect(jsonPath("$.values.netRevenue").value("1234"));
  }
}
