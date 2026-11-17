package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.domain.FormDefinition;
import kz.fin.formlib.repository.*;
import kz.fin.formlib.service.CatalogManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogManagementIntegrationTest {
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired FormDefinitionRepository definitions;
  @Autowired FormVersionRepository versions;
  @Autowired FormFieldRepository fieldRepository;
  @Autowired LibFormEditEventRepository events;

  private String json(Object x)throws Exception{return mapper.writeValueAsString(x);}
  private JsonNode get(String path)throws Exception{
    return mapper.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }
  private JsonNode post(String path,Object body)throws Exception{
    return mapper.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON)
      .content(json(body))).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }
  private JsonNode put(String path,Object body)throws Exception{
    return mapper.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON)
      .content(json(body))).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }
  private String base(UUID draft){return "/api/v1/catalog-admin/drafts/"+draft;}

  @Test void cloneEditTranslationsReorderAndPublishWithoutSemanticDrift()throws Exception{
    String code="DF-CSH-01";
    FormDefinition original=definitions.findByFormCode(code).orElseThrow();
    UUID baseVersionId=original.getCurrentVersionId();
    String originalTitle=get("/api/v1/catalog/forms/"+code+"?locale=ru")
      .get("form").get("title").asText();
    String originalSchema=versions.findById(baseVersionId).orElseThrow().getSchemaSha256();
    String originalPresentation=versions.findById(baseVersionId).orElseThrow().getPresentationSha256();
    String originalMapping=versions.findById(baseVersionId).orElseThrow().getMappingSha256();

    JsonNode opened=post("/api/v1/catalog-admin/forms/"+code+"/drafts",
      new CatalogManagementService.BeginRequest(baseVersionId,"local-editor"));
    UUID id=UUID.fromString(opened.get("editSessionId").asText());
    UUID draftVersionId=UUID.fromString(opened.get("draftVersionId").asText());
    assertEquals(0,opened.get("editRevision").asInt());
    assertNotEquals(baseVersionId,draftVersionId);
    assertEquals(baseVersionId,definitions.findByFormCode(code).orElseThrow().getCurrentVersionId());

    // Public catalog still resolves only the originally published definition.
    assertEquals(originalTitle,get("/api/v1/catalog/forms/"+code+"?locale=ru")
      .get("form").get("title").asText());

    var tr=new CatalogManagementService.TranslationUpdate(0,"FORM",null,
      "Журнал платежных операций (редакция R7)", "Уточнённое описание для локального теста",
      "local-editor");
    JsonNode revised=put(base(id)+"/translations/ru",tr);
    assertEquals(1,revised.get("editRevision").asInt());
    assertEquals(originalTitle,get("/api/v1/catalog/forms/"+code+"?locale=ru")
      .get("form").get("title").asText());

    // A new translation for an existing indicator, without code generation or renaming.
    JsonNode detail=get(base(id));
    var fields=detail.get("fields");
    assertTrue(fields.size()>=2);
    String fieldCode=fields.get(0).get("fieldCode").asText();
    put(base(id)+"/translations/zh-Hans",
      new CatalogManagementService.TranslationUpdate(1,"FIELD",fieldCode,
        "测试字段", "未经过财务审核的字段说明", "local-editor"))
      .get("editRevision");

    // Reverse all field orders in the original section; unique display_order must survive swap.
    List<CatalogManagementService.FieldPosition> positions=new ArrayList<>();
    for(int n=0;n<fields.size();n++){
      positions.add(new CatalogManagementService.FieldPosition(
        fields.get(n).get("fieldCode").asText(),(fields.size()-n)*10));
    }
    JsonNode reorder=put(base(id)+"/fields/order",
      new CatalogManagementService.FieldOrderUpdate(2,"MAIN",positions,"local-editor"));
    assertEquals(3,reorder.get("editRevision").asInt());

    var sessionDetail=get(base(id));
    assertEquals("DRAFT",sessionDetail.get("session").get("status").asText());
    assertEquals(3,sessionDetail.get("session").get("editRevision").asInt());
    assertEquals(baseVersionId,definitions.findByFormCode(code).orElseThrow().getCurrentVersionId());

    JsonNode published=post(base(id)+"/publish",
      new CatalogManagementService.FinishRequest(3,baseVersionId,"local-publisher"));
    assertEquals("PUBLISHED",published.get("status").asText());
    assertEquals(4,published.get("editRevision").asInt());
    assertEquals(draftVersionId,definitions.findByFormCode(code).orElseThrow().getCurrentVersionId());
    var newVersion=versions.findById(draftVersionId).orElseThrow();
    assertEquals(originalSchema,newVersion.getSchemaSha256());
    assertEquals(originalMapping,newVersion.getMappingSha256());
    assertNotEquals(originalPresentation,newVersion.getPresentationSha256());
    assertEquals("CANDIDATE_UNQUALIFIED",newVersion.getQualificationState());
    assertEquals(originalSchema,versions.findById(baseVersionId).orElseThrow().getSchemaSha256());
    assertEquals(originalPresentation,
      versions.findById(baseVersionId).orElseThrow().getPresentationSha256());
    JsonNode active=get("/api/v1/catalog/forms/"+code+"?locale=ru");
    assertEquals("Журнал платежных операций (редакция R7)",
      active.get("form").get("title").asText());
    assertEquals("CANDIDATE_UNQUALIFIED",
      active.get("form").get("qualificationState").asText());
    assertEquals(fieldCode,get("/api/v1/catalog/forms/"+code+"?locale=zh-Hans")
      .get("fields").get(fields.size()-1).get("fieldCode").asText());
    JsonNode history=get(base(id)+"/events");
    assertEquals(5,history.size());
    assertEquals("CREATED",history.get(0).get("type").asText());
    assertEquals("CATALOG_PUBLISHED",history.get(4).get("type").asText());
    JsonNode page0=get(base(id)+"/events/page?page=0&size=2");
    JsonNode page1=get(base(id)+"/events/page?page=1&size=2");
    JsonNode page2=get(base(id)+"/events/page?page=2&size=2");
    assertEquals(5,page0.get("totalElements").asInt());
    assertEquals(3,page0.get("totalPages").asInt());
    assertEquals(2,page0.get("items").size());
    assertEquals("CREATED",page0.get("items").get(0).get("type").asText());
    assertEquals(2,page1.get("items").size());
    assertEquals(1,page2.get("items").size());
    assertEquals("CATALOG_PUBLISHED",page2.get("items").get(0).get("type").asText());
    assertFalse(page2.get("hasNext").asBoolean());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base(id)+"/publish").contentType(MediaType.APPLICATION_JSON)
      .content(json(new CatalogManagementService.FinishRequest(4,baseVersionId,"retry"))))
      .andExpect(status().isConflict());
  }

  @Test void editRevisionAndDraftUniquenessFailClosed()throws Exception{
    String code="C02";
    UUID current=definitions.findByFormCode(code).orElseThrow().getCurrentVersionId();
    JsonNode draft=post("/api/v1/catalog-admin/forms/"+code+"/drafts",
      new CatalogManagementService.BeginRequest(current,"test"));
    UUID id=UUID.fromString(draft.get("editSessionId").asText());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/catalog-admin/forms/"+code+"/drafts")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json(new CatalogManagementService.BeginRequest(current,"duplicate"))))
      .andExpect(status().isConflict());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base(id)+"/translations/ru").contentType(MediaType.APPLICATION_JSON)
      .content(json(new CatalogManagementService.TranslationUpdate(0,"FIELD",
        "UNKNOWN_FIELD","Test","Test","test"))))
      .andExpect(status().isUnprocessableEntity());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base(id)+"/translations/de").contentType(MediaType.APPLICATION_JSON)
      .content(json(new CatalogManagementService.TranslationUpdate(0,"FORM",
        null,"Test","Test","test"))))
      .andExpect(status().isUnprocessableEntity());
    put(base(id)+"/translations/ru",
      new CatalogManagementService.TranslationUpdate(0,"FORM",
        null,"Изменение для теста","Описание","tester"));
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base(id)+"/translations/ru").contentType(MediaType.APPLICATION_JSON)
      .content(json(new CatalogManagementService.TranslationUpdate(0,"FORM",
        null,"Устаревшая редакция","Описание","stale"))))
      .andExpect(status().isConflict());
    JsonNode detail=get(base(id));
    var positions=List.of(new CatalogManagementService.FieldPosition("UNKNOWN_FIELD",10));
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base(id)+"/fields/order").contentType(MediaType.APPLICATION_JSON)
      .content(json(new CatalogManagementService.FieldOrderUpdate(1,"MAIN",positions,"tester"))))
      .andExpect(status().isUnprocessableEntity());
    JsonNode discarded=post(base(id)+"/discard",
      new CatalogManagementService.FinishRequest(1,current,"tester"));
    assertEquals("DISCARDED",discarded.get("status").asText());
    assertEquals(current,definitions.findByFormCode(code).orElseThrow().getCurrentVersionId());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base(id)+"/translations/ru").contentType(MediaType.APPLICATION_JSON)
      .content(json(new CatalogManagementService.TranslationUpdate(2,"FORM",null,
        "После закрытия","Описание","tester"))))
      .andExpect(status().isConflict());
    assertEquals(3,events.findByEditSessionIdOrderByEditRevisionAsc(id).size());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
      base(id)+"/events/page?size=1000")).andExpect(status().isUnprocessableEntity());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
      "/api/v1/catalog-admin/drafts/"+UUID.randomUUID()+"/events/page"))
      .andExpect(status().isNotFound());
  }
}
