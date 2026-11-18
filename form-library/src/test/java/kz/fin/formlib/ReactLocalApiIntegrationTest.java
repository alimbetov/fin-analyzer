package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * React consumes the same real REST contracts as the previous MVC UAT page.
 * The separate localhost helper remains available when Thymeleaf is disabled.
 */
@SpringBootTest(properties="fin.uat.editor.enabled=false")
@AutoConfigureMockMvc
class ReactLocalApiIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void allFortySevenCatalogCategoriesAndOneHundredSixtySixPrimaryFormLinks()
      throws Exception {
    var response=mvc.perform(get("/api/v1/catalog/category-tree")
      .param("locale","ru"))
      .andExpect(status().isOk()).andReturn().getResponse();
    JsonNode result=json.readTree(response.getContentAsByteArray());
    assertEquals(47,result.path("categories").size());
    assertEquals(389,result.path("links").size());
    long primary=0;
    for(JsonNode link:result.path("links"))
      if("PRIMARY".equals(link.path("role").asText()))primary++;
    assertEquals(166,primary);
    assertEquals(166,json.readTree(mvc.perform(get("/api/v1/catalog/navigation")
      .param("locale","ru")).andExpect(status().isOk()).andReturn()
      .getResponse().getContentAsByteArray()).path("totalForms").asInt());
  }

  @Test
  void formDetailAndReferenceDataWorkWithoutThymeleafAndWithoutAuth() throws Exception {
    JsonNode form=json.readTree(mvc.perform(get("/api/v1/catalog/forms/F02")
      .param("locale","ru")).andExpect(status().isOk()).andReturn()
      .getResponse().getContentAsByteArray());
    assertEquals("F02",form.path("form").path("formCode").asText());
    assertTrue(form.path("fields").size()>0);
    assertEquals(11,json.readTree(mvc.perform(get("/api/v1/catalog/currencies")
      .param("locale","ru")).andExpect(status().isOk()).andReturn()
      .getResponse().getContentAsByteArray()).size());
    mvc.perform(get("/uat/form-editor")).andExpect(status().isNotFound());
  }
}
