package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.bootstrap.FormEditorialDraftBootstrap;
import kz.fin.formlib.repository.FormInstanceRepository;
import kz.fin.formlib.repository.FormRevisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The original 166 form identities remain unchanged. This suite verifies actual
 * 664 DB rows, editor-ready four-language metadata, provisional provenance,
 * no change to immutable form versions and no writes into fin_intake.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FormEditorialDraftIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired FormEditorialDraftBootstrap loader;
  @Autowired FormInstanceRepository instances;
  @Autowired FormRevisionRepository revisions;

  private long count(String sql,Object... args){
    return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));
  }
  private JsonNode read(String path)throws Exception{
    return json.readTree(mvc.perform(get(path)).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }

  @Test void all166DistinctFormCodesHaveExactlyFourProvisionalLocalizedRecords(){
    assertEquals(166,count("SELECT COUNT(*) FROM fin_lib.lib_form_definition"));
    assertEquals(664,count("SELECT COUNT(*) FROM fin_lib.lib_form_editorial_draft"));
    assertEquals(166,count("SELECT COUNT(DISTINCT form_code) FROM fin_lib.lib_form_editorial_draft"));
    assertEquals(1,count("SELECT COUNT(*) FROM fin_lib.lib_form_editorial_seed"));
    assertEquals(0,count("""
      SELECT COUNT(*) FROM (
        SELECT form_code FROM fin_lib.lib_form_editorial_draft
        GROUP BY form_code HAVING COUNT(*)<>4
      ) wrong
      """));
    assertEquals(0,count("""
      SELECT COUNT(*) FROM fin_lib.lib_form_definition f
      LEFT JOIN fin_lib.lib_form_editorial_draft d ON d.form_code=f.form_code
      WHERE d.form_code IS NULL
      """));
    assertEquals(0,count("""
      SELECT COUNT(*) FROM fin_lib.lib_form_editorial_draft
      WHERE title IS NULL OR trim(title)='' OR purpose IS NULL OR trim(purpose)=''
        OR description IS NULL OR trim(description)='' OR length(source_contract_sha256)<>64
        OR review_status<>'AUTO_DRAFT_REVIEW_REQUIRED'
      """));
    for(String l:List.of("ru","kk","en","zh-Hans"))
      assertEquals(166,count("""
        SELECT COUNT(*) FROM fin_lib.lib_form_editorial_draft WHERE locale=?
        """,l));
  }

  @Test void localizedCardsAreNonBlankForAll166WhileActiveVersionsRemainUnchanged()
      throws Exception {
    long beforeInstances=instances.count(),beforeRevisions=revisions.count();
    var previousSha=db.queryForObject("""
      SELECT v.schema_sha256 FROM fin_lib.lib_form_version v
      JOIN fin_lib.lib_form_definition f ON f.current_version_id=v.id
      WHERE f.form_code='F02'
      """,String.class);
    for(String locale:List.of("ru","kk","en","zh-Hans")){
      var forms=read("/api/v1/catalog/forms?locale="+locale);
      assertEquals(166,forms.size());
      Set<String> codes=new HashSet<>();
      for(var form:forms){
        assertTrue(codes.add(form.get("formCode").asText()));
        assertFalse(form.get("title").asText().isBlank());
        assertFalse(form.get("purpose").asText().isBlank());
        assertFalse(form.get("description").asText().isBlank());
        assertTrue(Set.of("AUTO_DRAFT_REVIEW_REQUIRED","DRAFT_UNREVIEWED",
          "PROVISIONAL_SOURCE_TEXT").contains(form.get("translationStatus").asText()));
        assertTrue(Set.of("AUTO_DRAFT_REVIEW_REQUIRED","DRAFT_UNREVIEWED")
          .contains(form.get("purposeTranslationStatus").asText()));
        assertEquals("CANDIDATE_UNQUALIFIED",form.get("qualificationState").asText());
      }
      var coverage=read("/api/v1/catalog/ui/coverage?locale="+locale);
      assertEquals(166,coverage.get("editorialDraftForms").asInt());
      assertEquals(166,coverage.get("displayCompleteForms").asInt());
      assertEquals(166-coverage.get("fullyDocumentedForms").asInt(),
        coverage.get("needVersionedEditorialReviewForms").asInt());
      assertEquals(47,coverage.get("categories").asInt());
      assertEquals(389,coverage.get("categoryFormLinks").asInt());
    }
    assertEquals(previousSha,db.queryForObject("""
      SELECT v.schema_sha256 FROM fin_lib.lib_form_version v
      JOIN fin_lib.lib_form_definition f ON f.current_version_id=v.id
      WHERE f.form_code='F02'
      """,String.class));
    assertEquals(beforeInstances,instances.count());
    assertEquals(beforeRevisions,revisions.count());
  }

  @Test void untranslatedVersionedRecordsAreNotChangedByEditorialOverlay()throws Exception{
    var active=db.queryForMap("""
      SELECT t.title,t.purpose,t.description,t.review_status
      FROM fin_lib.lib_form_translation t
      JOIN fin_lib.lib_form_definition f ON f.current_version_id=t.form_version_id
      WHERE f.form_code='T04' AND t.locale='zh-Hans'
      """);
    assertNull(active.get("title"));
    assertNull(active.get("purpose"));
    assertNull(active.get("description"));
    var presented=read("/api/v1/catalog/forms/T04?locale=zh-Hans").get("form");
    assertFalse(presented.get("title").asText().isBlank());
    assertFalse(presented.get("purpose").asText().isBlank());
    assertFalse(presented.get("description").asText().isBlank());
    assertEquals("AUTO_DRAFT_REVIEW_REQUIRED",presented.get("translationStatus").asText());
    assertEquals("AUTO_DRAFT_REVIEW_REQUIRED",presented.get("purposeTranslationStatus").asText());
    assertEquals("医疗操作与成本",db.queryForObject("""
      SELECT title FROM fin_lib.lib_form_editorial_draft
      WHERE form_code='SF-Q02' AND locale='zh-Hans'
      """,String.class));
  }

  @Test void repeatedInitializationDoesNotDuplicateOrReassignFormCodes()throws Exception{
    long formCount=count("SELECT COUNT(*) FROM fin_lib.lib_form_definition");
    long translationCount=count("SELECT COUNT(*) FROM fin_lib.lib_form_translation");
    var sha=db.queryForObject("""
      SELECT source_sha256 FROM fin_lib.lib_form_editorial_seed
      """,String.class);
    loader.run(new DefaultApplicationArguments(new String[0]));
    assertEquals(formCount,count("SELECT COUNT(*) FROM fin_lib.lib_form_definition"));
    assertEquals(664,count("SELECT COUNT(*) FROM fin_lib.lib_form_editorial_draft"));
    assertEquals(translationCount,count("SELECT COUNT(*) FROM fin_lib.lib_form_translation"));
    assertEquals(sha,db.queryForObject("""
      SELECT source_sha256 FROM fin_lib.lib_form_editorial_seed
      """,String.class));
  }
}
