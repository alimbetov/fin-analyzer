package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.repository.*;
import kz.fin.formlib.service.*;
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
class CatalogPagingCacheIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired CatalogReadCache cache;
  @Autowired FormDefinitionRepository forms;
  @Autowired FormInstanceRepository instances;
  @Autowired FormRevisionRepository revisions;

  private JsonNode getJson(String url)throws Exception{
    return json.readTree(mvc.perform(get(url)).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }
  private JsonNode postJson(String url,Object payload)throws Exception{
    return json.readTree(mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(payload))).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }
  private JsonNode putJson(String url,Object payload)throws Exception{
    return json.readTree(mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(payload))).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }

  @Test void page166UniqueFormCardsWithStableSortingAndTwoLayouts()throws Exception{
    long beforeInstances=instances.count(),beforeRevisions=revisions.count();
    Set<String> codes=new HashSet<>();
    for(int page=0;page<7;page++){
      var v=getJson("/api/v1/catalog/ui/forms/page?locale=ru&sort=CODE&page="+page+"&size=25");
      assertEquals(166,v.get("totalElements").asInt());
      assertEquals(7,v.get("totalPages").asInt());
      assertEquals("CARDS",v.get("viewMode").asText());
      assertEquals("CODE",v.get("sortMode").asText());
      assertEquals(page,v.get("page").asInt());
      assertEquals(page<6,v.get("hasNext").asBoolean());
      assertEquals(page==6?16:25,v.get("items").size());
      for(var item:v.get("items")){
        assertTrue(codes.add(item.get("form").get("formCode").asText()));
        assertFalse(item.get("form").get("formVersionId").asText().isBlank());
        assertTrue(Set.of("file-spreadsheet","table-2","file-check-2")
          .contains(item.get("iconKey").asText()));
      }
    }
    assertEquals(166,codes.size());
    var list=getJson("/api/v1/catalog/ui/forms/page?locale=ru&sort=CODE&page=0&size=25&viewMode=LIST");
    assertEquals("LIST",list.get("viewMode").asText());
    assertEquals(166,list.get("totalElements").asInt());
    assertEquals(25,list.get("items").size());
    var noMore=getJson("/api/v1/catalog/ui/forms/page?page=7&size=25");
    assertEquals(0,noMore.get("items").size());
    assertEquals(beforeInstances,instances.count());
    assertEquals(beforeRevisions,revisions.count());
  }

  @Test void paginateCategoriesPurposesAndExistingCatalogEndpoints()throws Exception{
    JsonNode categories=getJson("/api/v1/catalog/ui/categories/page?size=10&locale=kk");
    assertEquals(47,categories.get("totalElements").asInt());
    assertEquals(5,categories.get("totalPages").asInt());
    assertEquals(10,categories.get("items").size());
    for(var c:categories.get("items")){
      assertNotNull(c.get("iconKey"));
      assertEquals("kk",c.get("category").get("locale").asText());
    }
    JsonNode finance=getJson("/api/v1/catalog/ui/categories/GROUP_FINANCE/forms/page?page=0&size=4");
    assertEquals(8,finance.get("totalElements").asInt());
    assertEquals(2,finance.get("totalPages").asInt());
    assertEquals(4,finance.get("items").size());
    JsonNode bank=getJson("/api/v1/catalog/use-cases/BANK_FINANCING/forms/page?page=0&size=5");
    assertEquals(27,bank.get("totalElements").asInt());
    assertEquals(6,bank.get("totalPages").asInt());
    assertEquals(5,bank.get("items").size());
    var catalog=getJson("/api/v1/catalog/forms/page?size=50");
    assertEquals(166,catalog.get("totalElements").asInt());
    assertEquals(50,catalog.get("items").size());
    var legacy=getJson("/api/v1/forms/page?size=50");
    assertEquals(166,legacy.get("totalElements").asInt());
    assertEquals(50,legacy.get("items").size());
    var metrics=getJson("/api/v1/catalog/metrics/page?size=5");
    assertTrue(metrics.get("totalElements").asInt()>=5);
    assertEquals(5,metrics.get("items").size());
    var search=getJson("/api/v1/catalog/ui/forms/page?q=F02&sort=CODE&size=50");
    assertTrue(search.get("totalElements").asInt()>=1);
    boolean f02=false;
    for(var row:search.get("items"))
      if("F02".equals(row.get("form").get("formCode").asText()))f02=true;
    assertTrue(f02);
  }

  @Test void boundValidationRejectsExpensiveOrInvalidCalls()throws Exception{
    for(String url:List.of(
      "/api/v1/catalog/ui/forms/page?size=0",
      "/api/v1/catalog/ui/forms/page?size=51",
      "/api/v1/catalog/ui/forms/page?page=-1",
      "/api/v1/catalog/ui/categories/page?viewMode=UNKNOWN",
      "/api/v1/catalog/ui/forms/page?sort=RANDOM()",
      "/api/v1/catalog/forms/page?size=9999",
      "/api/v1/catalog/use-cases/BANK_FINANCING/forms/page?page=-5",
      "/api/v1/catalog/metrics/page?size=1000"
    ))mvc.perform(get(url)).andExpect(status().isUnprocessableEntity());
    mvc.perform(get("/api/v1/catalog/ui/forms/page?locale=de"))
      .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/catalog/ui/forms/page?categoryCode=UNKNOWN"))
      .andExpect(status().isNotFound());
  }

  @Test void warmCacheUsesHitsAndSeparatesLocales()throws Exception{
    long h=cache.stats().hitCount(),m=cache.stats().missCount();
    var ru=getJson("/api/v1/catalog/forms?locale=ru");
    getJson("/api/v1/catalog/forms?locale=ru");
    var en=getJson("/api/v1/catalog/forms?locale=en");
    assertEquals(166,ru.size());assertEquals(166,en.size());
    assertTrue(cache.stats().hitCount()>h);
    assertTrue(cache.stats().missCount()>=m);
    assertTrue(cache.estimatedSize()>0);
  }

  @Test void publishPresentationInvalidatesEveryCachedLocaleAndCategory()throws Exception{
    String code="SF-S03";
    UUID oldVersion=forms.findByFormCode(code).orElseThrow().getCurrentVersionId();
    var before=getJson("/api/v1/catalog/ui/forms/"+code+"/entry?locale=ru");
    getJson("/api/v1/catalog/ui/categories/GROUP_SECTOR/forms?locale=ru");
    assertEquals(oldVersion.toString(),before.get("formVersionId").asText());
    var begin=postJson("/api/v1/catalog-admin/forms/"+code+"/drafts",
      new CatalogManagementService.BeginRequest(oldVersion,"local-cache-qa"));
    UUID edit=UUID.fromString(begin.get("editSessionId").asText());
    var updated=putJson("/api/v1/catalog-admin/drafts/"+edit+"/translations/ru",
      new CatalogManagementService.TranslationUpdate(0,"FORM",null,
        "Обновлённая форма для кеш-теста","Новая редакция описания",
        "local-cache-qa","Назначение после публикации"));
    assertEquals(1,updated.get("editRevision").asInt());
    // No premature invalidation: active snapshot is unchanged until commit.
    assertEquals(oldVersion.toString(),
      getJson("/api/v1/catalog/ui/forms/"+code+"/entry?locale=ru")
        .get("formVersionId").asText());
    var published=postJson("/api/v1/catalog-admin/drafts/"+edit+"/publish",
      new CatalogManagementService.FinishRequest(1,oldVersion,"local-cache-qa"));
    assertEquals("PUBLISHED",published.get("status").asText());
    var after=getJson("/api/v1/catalog/ui/forms/"+code+"/entry?locale=ru");
    assertNotEquals(oldVersion.toString(),after.get("formVersionId").asText());
    assertEquals("Обновлённая форма для кеш-теста",after.get("title").asText());
    assertEquals("Назначение после публикации",after.get("purpose").asText());
    assertEquals(after.get("formVersionId").asText(),
      getJson("/api/v1/catalog/ui/forms/"+code+"/entry?locale=en")
        .get("formVersionId").asText());
  }
}
