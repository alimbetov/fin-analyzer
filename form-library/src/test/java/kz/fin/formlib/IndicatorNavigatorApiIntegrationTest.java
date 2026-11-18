package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="fin.uat.editor.enabled=false")
@AutoConfigureMockMvc
class IndicatorNavigatorApiIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  private JsonNode fetch(String path) throws Exception {
    return mapper.readTree(mvc.perform(get(path))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
  }

  @Test
  void goalGraphResolvesSevenRealCasesWithoutCreatingFinanceRevisions() throws Exception {
    Integer before=jdbc.queryForObject(
      "SELECT count(*) FROM fin_intake.lib_form_revision",Integer.class);
    var goals=fetch("/api/v1/indicator-catalog/goals?locale=ru");
    assertEquals(7,goals.size());
    int links=0;
    for(JsonNode goal:goals){
      assertEquals("CANDIDATE_NOT_S2",goal.path("qualificationStatus").asText());
      assertTrue(goal.path("models").size()>0);
      assertTrue(goal.path("forms").size()>0);
      links+=goal.path("forms").size();
      for(JsonNode model:goal.path("models")){
        assertTrue(model.path("qualificationStatus").asText().matches(
          "DESIGN_ONLY|CALC_PILOT"));
      }
    }
    assertEquals(101,links);
    assertEquals(46,fetch("/api/v1/indicator-catalog/indicators").size());
    assertEquals(before,jdbc.queryForObject(
      "SELECT count(*) FROM fin_intake.lib_form_revision",Integer.class));
  }

  @Test
  void goalIndicatorAndFormUsageAreLinkedToSourceMetadata() throws Exception {
    var investor=fetch("/api/v1/indicator-catalog/goals/INVESTMENT_APPRAISAL");
    assertEquals("Оценка инвестиционного проекта",investor.path("title").asText());
    assertEquals("CANDIDATE_NOT_S2",investor.path("qualificationStatus").asText());
    var npv=fetch("/api/v1/indicator-catalog/indicators/PROJECT_NPV");
    assertEquals("CATALOG_UNQUALIFIED",npv.path("qualificationStatus").asText());
    assertEquals("CALC_PILOT",npv.path("models").get(0).path("qualificationStatus").asText());
    assertEquals("NOT_YET_MAPPED",fetch(
      "/api/v1/indicator-catalog/forms/DF-AST-01/used-by"
    ).path("qualificationStatus").asText());
    assertEquals("CANDIDATE_LINK_ONLY",fetch(
      "/api/v1/indicator-catalog/forms/P07/used-by"
    ).path("qualificationStatus").asText());
  }

  @Test
  void errorsAndTranslationsAreFailClosed() throws Exception {
    var kk=fetch("/api/v1/indicator-catalog/goals?locale=kk");
    assertEquals(7,kk.size());
    assertEquals("PENDING_TRANSLATION",kk.get(0).path("translationStatus").asText());
    mvc.perform(get("/api/v1/indicator-catalog/goals?locale=fr"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("UNSUPPORTED_LOCALE"));
    mvc.perform(get("/api/v1/indicator-catalog/goals/NOT_EXIST"))
      .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/indicator-catalog/indicators/NOT_EXIST"))
      .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/indicator-catalog/forms/NOT_EXIST/used-by"))
      .andExpect(status().isNotFound());
  }
}
