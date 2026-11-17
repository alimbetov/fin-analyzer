package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.repository.FormDefinitionRepository;
import kz.fin.formlib.service.CatalogManagementService;
import kz.fin.formlib.service.FormDraftService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogFactSeparationIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired FormDefinitionRepository forms;
  private String json(Object o) throws Exception { return mapper.writeValueAsString(o); }
  private JsonNode ok(RequestBuilder request) throws Exception {
    return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
  }
  private FormDraftService.DraftRequest draft(UUID instanceId, UUID org, UUID project,
      int expectedRevision, String revenue) {
    return new FormDraftService.DraftRequest(instanceId, org,
        UUID.fromString("00000000-0000-0000-0000-000000000002"),
        UUID.fromString("00000000-0000-0000-0000-000000000003"),
        project, "F02", "M", "2026-02", "KZT", "PERIOD_ONLY",
        "MANAGEMENT", "SYNTHETIC", expectedRevision,
        Map.of("netRevenue", revenue, "cogs", "40", "operatingExpenses", "20"),
        List.of(), "author-local", "data update");
  }

  @Test void existingDataRevisionKeepsOriginalFormVersionAfterCatalogPublication() throws Exception {
    UUID org=UUID.randomUUID(), project=UUID.randomUUID();
    String intake="/api/v1/drafts";
    JsonNode first=ok(post(intake).header("Idempotency-Key", "first-"+org)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(draft(null,org,project,0,"100"))));
    UUID instanceId=UUID.fromString(first.get("instanceId").asText());
    JsonNode originalView=ok(get(intake+"/"+instanceId).param("organizationId",org.toString()));
    String originalFormVersion=originalView.get("formVersion").asText();

    UUID base=forms.findByFormCode("F02").orElseThrow().getCurrentVersionId();
    JsonNode opened=ok(post("/api/v1/catalog-admin/forms/F02/drafts")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(new CatalogManagementService.BeginRequest(base,"local-editor"))));
    UUID editId=UUID.fromString(opened.get("editSessionId").asText());
    String editing="/api/v1/catalog-admin/drafts/"+editId;
    ok(put(editing+"/translations/ru").contentType(MediaType.APPLICATION_JSON)
        .content(json(new CatalogManagementService.TranslationUpdate(0,"FORM",null,
            "F02 — редакция шаблона", "Изменено только отображение", "local-editor"))));
    ok(post(editing+"/publish").contentType(MediaType.APPLICATION_JSON)
        .content(json(new CatalogManagementService.FinishRequest(1,base,"local-editor"))));

    assertNotEquals(base,forms.findByFormCode("F02").orElseThrow().getCurrentVersionId());
    JsonNode second=ok(post(intake).header("Idempotency-Key","second-"+org)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(draft(instanceId,org,project,1,"110"))));
    assertEquals(2,second.get("revisionNo").asInt());
    JsonNode existing=ok(get(intake+"/"+instanceId).param("organizationId",org.toString()));
    assertEquals(originalFormVersion,existing.get("formVersion").asText());
    assertEquals("110",existing.get("values").get("netRevenue").asText());
    JsonNode historical=ok(get(intake+"/"+instanceId+"/revisions/1")
        .param("organizationId",org.toString()));
    assertEquals(originalFormVersion,historical.get("formVersion").asText());
    assertEquals("100",historical.get("values").get("netRevenue").asText());

    // New instances created after catalog activation use the newly active presentation version.
    JsonNode newest=ok(post(intake).header("Idempotency-Key","third-"+org)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(draft(null,org,UUID.randomUUID(),0,"200"))));
    UUID newestId=UUID.fromString(newest.get("instanceId").asText());
    JsonNode newView=ok(get(intake+"/"+newestId).param("organizationId",org.toString()));
    assertNotEquals(originalFormVersion,newView.get("formVersion").asText());
  }
}
