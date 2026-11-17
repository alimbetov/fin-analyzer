package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.bootstrap.FieldMetricDisplayDraftBootstrap;
import kz.fin.formlib.repository.FormInstanceRepository;
import kz.fin.formlib.repository.FormRevisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Provisional display coverage is not editorial or financial approval. */
@SpringBootTest
@AutoConfigureMockMvc
class FieldMetricDisplayDraftIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired MockMvc api;
  @Autowired ObjectMapper mapper;
  @Autowired FieldMetricDisplayDraftBootstrap seed;
  @Autowired FormInstanceRepository instances;
  @Autowired FormRevisionRepository revisions;

  private long count(String sql,Object...args){
    return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));
  }
  private JsonNode getJson(String url)throws Exception{
    return mapper.readTree(api.perform(get(url)).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }

  @Test void allPhysicalFieldsAndMetricsHaveFourDraftLabelsWithNoFakeApproval(){
    assertEquals(3508,count("SELECT COUNT(*) FROM fin_lib.lib_field_display_candidate"));
    assertEquals(3360,count("SELECT COUNT(*) FROM fin_lib.lib_metric_display_candidate"));
    assertEquals(1,count("SELECT COUNT(*) FROM fin_lib.lib_field_display_seed_manifest"));
    assertEquals(877,count("""
      SELECT COUNT(DISTINCT form_code || '.' || field_code)
      FROM fin_lib.lib_field_display_candidate
      """));
    assertEquals(840,count("""
      SELECT COUNT(DISTINCT metric_code) FROM fin_lib.lib_metric_display_candidate
      """));
    assertEquals(0,count("""
      SELECT COUNT(*) FROM fin_lib.lib_field_display_candidate
      WHERE title IS NULL OR trim(title)='' OR review_status<>'AUTO_DRAFT_REVIEW_REQUIRED'
      """));
    assertEquals(0,count("""
      SELECT COUNT(*) FROM fin_lib.lib_metric_display_candidate
      WHERE title IS NULL OR trim(title)='' OR review_status<>'AUTO_DRAFT_REVIEW_REQUIRED'
      """));
    assertEquals(0,count("""
      SELECT COUNT(*) FROM (
        SELECT form_code,field_code FROM fin_lib.lib_field_display_candidate
        GROUP BY form_code,field_code HAVING COUNT(*)<>4
      ) missing_locale
      """));
    assertEquals(0,count("""
      SELECT COUNT(*) FROM (
        SELECT metric_code FROM fin_lib.lib_metric_display_candidate
        GROUP BY metric_code HAVING COUNT(*)<>4
      ) missing_locale
      """));
    for(String locale:List.of("ru","kk","en","zh-Hans")){
      assertEquals(877,count("""
        SELECT COUNT(*) FROM fin_lib.lib_field_display_candidate WHERE locale=?
        """,locale));
      assertEquals(840,count("""
        SELECT COUNT(*) FROM fin_lib.lib_metric_display_candidate WHERE locale=?
        """,locale));
    }
  }

  @Test void allFourLanguagesRenderCandidateLabelsAndKeepFinancialRevisionsIntact()
      throws Exception{
    long before=instances.count(),revs=revisions.count();
    for(String lang:List.of("ru","kk","en","zh-Hans")){
      var metrics=getJson("/api/v1/catalog/metrics?locale="+lang);
      assertEquals(840,metrics.size());
      for(var metric:metrics){
        assertFalse(metric.get("title").asText().isBlank());
        assertEquals("CATALOG_UNQUALIFIED",metric.get("qualificationState").asText());
      }
      var f02=getJson("/api/v1/catalog/forms/F02?locale="+lang);
      assertEquals(3,f02.get("fields").size());
      for(var field:f02.get("fields")){
        assertFalse(field.get("title").asText().isBlank());
        assertFalse(field.get("translationStatus").asText().isBlank());
      }
      var evidence=getJson("/api/v1/catalog/contracts/forms/D01?locale="+lang);
      assertEquals(7,evidence.get("fields").size());
      for(var field:evidence.get("fields")){
        assertEquals("EVIDENCE_METADATA",field.get("semanticRole").asText());
        assertFalse(field.get("title").asText().isBlank());
        assertEquals("AUTO_DRAFT_REVIEW_REQUIRED",
          field.get("translationStatus").asText());
      }
      var template=getJson("/api/v1/templates/F02/preview?locale="+lang+
        "&periodType=M&periodCodes=2026-01&styleCode=EXCEL_GRID");
      assertEquals(3,template.get("fields").size());
      for(var field:template.get("fields")){
        assertFalse(field.get("title").asText().isBlank());
        assertTrue(Set.of("PROVISIONAL_NOT_S2","AUTO_DRAFT_REVIEW_REQUIRED")
          .contains(field.get("translationStatus").asText()));
      }
      assertTrue(template.get("warnings").toString().contains("UNQUALIFIED_TEMPLATE"));
    }
    assertEquals(before,instances.count());
    assertEquals(revs,revisions.count());
  }

  @Test void displayCandidateSeedIsIdempotentAndVersionTranslationsUntouched() {
    long old=count("SELECT COUNT(*) FROM fin_lib.lib_form_field_translation");
    long oldMetrics=count("SELECT COUNT(*) FROM fin_lib.lib_metric_translation");
    String fingerprint=db.queryForObject("""
      SELECT source_sha256 FROM fin_lib.lib_field_display_seed_manifest
      """,String.class);
    seed.run(new DefaultApplicationArguments(new String[0]));
    assertEquals(3508,count("SELECT COUNT(*) FROM fin_lib.lib_field_display_candidate"));
    assertEquals(3360,count("SELECT COUNT(*) FROM fin_lib.lib_metric_display_candidate"));
    assertEquals(old,count("SELECT COUNT(*) FROM fin_lib.lib_form_field_translation"));
    assertEquals(oldMetrics,count("SELECT COUNT(*) FROM fin_lib.lib_metric_translation"));
    assertEquals(fingerprint,db.queryForObject("""
      SELECT source_sha256 FROM fin_lib.lib_field_display_seed_manifest
      """,String.class));
  }

  @Test void databaseRejectsInventedMetricAndUnsupportedLocale() {
    assertThrows(DataIntegrityViolationException.class,()->db.update("""
      INSERT INTO fin_lib.lib_metric_display_candidate
      (metric_code,locale,title,review_status) VALUES(?,?,?,?)
      ""","F02.NOT_A_METRIC","ru","Подмена","AUTO_DRAFT_REVIEW_REQUIRED"));
    assertThrows(DataIntegrityViolationException.class,()->db.update("""
      INSERT INTO fin_lib.lib_field_display_candidate
      (form_code,field_code,locale,title,review_status,source_contract_sha256)
      VALUES(?,?,?,?,?,?)
      ""","F02","netRevenue","de","Invalid",
      "AUTO_DRAFT_REVIEW_REQUIRED","0".repeat(64)));
    assertEquals(3508,count("SELECT COUNT(*) FROM fin_lib.lib_field_display_candidate"));
    assertEquals(3360,count("SELECT COUNT(*) FROM fin_lib.lib_metric_display_candidate"));
  }
}
