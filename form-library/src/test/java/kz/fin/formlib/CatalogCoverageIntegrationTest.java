package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Every count comes from fin_lib, not a hardcoded four-language UI placeholder. */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogCoverageIntegrationTest {
 @Autowired MockMvc api;
 @Autowired ObjectMapper mapper;
 private JsonNode view(String path)throws Exception{
   return mapper.readTree(api.perform(get(path)).andExpect(status().isOk())
     .andReturn().getResponse().getContentAsString());
 }
 @Test void fourLanguagesShowPreciseFillBacklogAndUnapprovedSchemaStates()throws Exception{
   for(String locale:List.of("ru","kk","en","zh-Hans")){
     var coverage=view("/api/v1/catalog/ui/coverage?locale="+locale);
     assertEquals(166,coverage.get("forms").asInt());
     assertEquals(47,coverage.get("categories").asInt());
     assertEquals(47,coverage.get("categoriesTranslated").asInt());
     assertEquals(389,coverage.get("categoryFormLinks").asInt());
     assertEquals(122,coverage.get("typedDraftForms").asInt());
     assertEquals(12,coverage.get("evidenceOnlyForms").asInt());
     assertEquals(32,coverage.get("schemaNotApprovedForms").asInt());
     assertEquals(166,coverage.get("financiallyUnqualifiedForms").asInt());
     assertTrue(coverage.get("namedForms").asInt()>=12);
     assertTrue(coverage.get("purposefulForms").asInt()>=12);
     assertTrue(coverage.get("fullyDocumentedForms").asInt()<=coverage.get("purposefulForms").asInt());

     var page=view("/api/v1/catalog/ui/translation-gaps?locale="+locale+"&size=50");
     assertEquals(166-coverage.get("fullyDocumentedForms").asInt(),
       page.get("totalElements").asInt());
     assertEquals("LIST",page.get("viewMode").asText());
     assertEquals("CODE",page.get("sortMode").asText());
     assertTrue(page.get("items").size()<=50);
     Set<String> ids=new HashSet<>();
     for(var form:page.get("items")){
       assertTrue(ids.add(form.get("formCode").asText()));
       assertTrue(form.get("missingTitle").asBoolean()
         ||form.get("missingPurpose").asBoolean()
         ||form.get("missingDescription").asBoolean());
       assertFalse(form.get("formVersionId").asText().isBlank());
     }
   }
 }
 @Test void errorsAndPageLimitsRemainStrict()throws Exception{
   api.perform(get("/api/v1/catalog/ui/coverage").param("locale","xx"))
     .andExpect(status().isBadRequest());
   api.perform(get("/api/v1/catalog/ui/translation-gaps").param("locale","xx"))
     .andExpect(status().isBadRequest());
   api.perform(get("/api/v1/catalog/ui/translation-gaps").param("size","51"))
     .andExpect(status().isUnprocessableEntity());
   api.perform(get("/api/v1/catalog/ui/translation-gaps").param("page","-1"))
     .andExpect(status().isUnprocessableEntity());
 }
}
