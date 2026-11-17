package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FieldMetricContractApiIntegrationTest {
  @Autowired MockMvc api;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate db;

  private JsonNode getJson(String path)throws Exception{
    return mapper.readTree(api.perform(get(path)).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }
  @Test void all166LiveFormContractViewsHaveExactly877SafeBindings()throws Exception {
    JsonNode coverage=getJson("/api/v1/catalog/contracts/coverage");
    assertEquals(166,coverage.get("forms").asInt());
    assertEquals(877,coverage.get("physicalFields").asInt());
    assertEquals(793,coverage.get("sourceBindings").asInt());
    assertEquals(84,coverage.get("evidenceMetadataFields").asInt());
    assertEquals(1,coverage.get("unboundDerivedCandidates").asInt());
    assertEquals(46,coverage.get("analyticalOutputs").asInt());
    assertEquals(0,coverage.get("financialQualifiedBindings").asInt());

    var codes=db.queryForList(
      "SELECT form_code FROM fin_lib.lib_form_definition ORDER BY form_code",String.class);
    assertEquals(166,codes.size());
    Set<String> seen=new HashSet<>();
    int physical=0,metricSource=0,metadata=0;
    for(var code:codes){
      var report=getJson("/api/v1/catalog/contracts/forms/"+code+"?locale=ru");
      assertEquals(code,report.get("formCode").asText());
      assertFalse(report.get("formVersionId").asText().isBlank());
      assertEquals("CANDIDATE_UNQUALIFIED",report.get("qualificationState").asText());
      for(var field:report.get("fields")){
        String id=code+"."+field.get("fieldCode").asText();
        assertTrue(seen.add(id),"duplicate "+id);
        assertEquals("MATCHED_UNQUALIFIED",field.get("bindingState").asText());
        assertEquals("CANDIDATE_UNQUALIFIED",field.get("reviewState").asText());
        assertEquals(64,field.get("contractSha256").asText().length());
        assertFalse(field.get("fieldType").asText().isBlank());
        physical++;
        if("EVIDENCE_METADATA".equals(field.get("semanticRole").asText())){
          metadata++;
          assertTrue(field.get("metricCode").isNull());
        }else{
          metricSource++;
          assertEquals(id,field.get("metricCode").asText());
        }
      }
    }
    assertEquals(877,physical);
    assertEquals(793,metricSource);
    assertEquals(84,metadata);
    assertEquals(877,seen.size());
    var f02=getJson("/api/v1/catalog/contracts/forms/F02?locale=ru");
    assertEquals(3,f02.get("fields").size());
    for(var field:f02.get("fields"))
      assertNotEquals("netProfit",field.get("fieldCode").asText());
  }
  @Test void semanticMetricRoleDisambiguatesFormDerivedProfitFromPhysicalInput()
      throws Exception{
    var derived=getJson("/api/v1/catalog/contracts/metrics/F02.netProfit");
    assertEquals("SOURCE_FIELD",derived.get("legacyMetricKind").asText());
    assertEquals("FORM_DERIVED_CANDIDATE",derived.get("semanticRole").asText());
    assertEquals("netRevenue - cogs - operatingExpenses",
      derived.get("formulaCandidate").asText());
    assertFalse(derived.get("hasPhysicalBinding").asBoolean());
    assertFalse(derived.get("financialQualified").asBoolean());
    var source=getJson("/api/v1/catalog/contracts/metrics/F02.netRevenue");
    assertEquals("SOURCE_FIELD",source.get("semanticRole").asText());
    assertTrue(source.get("hasPhysicalBinding").asBoolean());
    assertFalse(source.get("financialQualified").asBoolean());
    var analytic=getJson("/api/v1/catalog/contracts/metrics/NET_PROFIT");
    assertEquals("ANALYTICAL_OUTPUT",analytic.get("semanticRole").asText());
    api.perform(get("/api/v1/catalog/contracts/metrics/UNRECOGNIZED"))
      .andExpect(status().isNotFound());
  }
  @Test void localeIsStrictAndUnknownFormDoesNotExposeAnyContracts()throws Exception{
    var zh=getJson("/api/v1/catalog/contracts/forms/D01?locale=zh-Hans");
    assertEquals("zh-Hans",zh.get("locale").asText());
    assertEquals(7,zh.get("fields").size());
    api.perform(get("/api/v1/catalog/contracts/forms/D01?locale=es"))
      .andExpect(status().isBadRequest());
    api.perform(get("/api/v1/catalog/contracts/forms/DOES_NOT_EXIST"))
      .andExpect(status().isNotFound());
  }
}
