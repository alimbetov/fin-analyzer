package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.repository.FormInstanceRepository;
import kz.fin.formlib.repository.FormRevisionRepository;
import kz.fin.formlib.repository.LibReportDefinitionRepository;
import kz.fin.formlib.repository.LibReportRevisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FormUseCaseIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired FormInstanceRepository instances;
  @Autowired FormRevisionRepository revisions;
  @Autowired LibReportDefinitionRepository reports;
  @Autowired LibReportRevisionRepository reportRevisions;

  private JsonNode getJson(String path) throws Exception {
    return mapper.readTree(mvc.perform(get(path)).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }
  private static Set<String> codes(JsonNode items){
    Set<String> result=new HashSet<>();
    for(var f:items)assertTrue(result.add(f.get("formCode").asText()),
      "duplicate recommended form "+f.get("formCode").asText());
    return result;
  }

  @Test void sevenPurposesHaveVersionedCuratedNonMandatoryPackages() throws Exception {
    JsonNode list=getJson("/api/v1/catalog/use-cases");
    assertEquals(7,list.size());
    Set<String> names=new HashSet<>();
    for(var item:list){
      assertTrue(names.add(item.get("useCaseCode").asText()));
      assertEquals(1,item.get("version").asInt());
      assertEquals("CANDIDATE_NOT_S2",item.get("qualificationState").asText());
      assertTrue(item.get("titleRu").asText().length()>3);
      assertTrue(item.get("titleEn").asText().length()>3);
      assertTrue(item.get("coreCount").asInt()>0);
      assertTrue(item.get("modelCodes").size()>0);
      assertTrue(item.get("audience").asText().length()>3);
      assertTrue(item.get("deliverable").asText().length()>3);
    }
    assertEquals(Set.of("FINANCIAL_HEALTH","INVESTOR_READINESS","BUSINESS_PLAN",
      "BANK_FINANCING","INVESTMENT_APPRAISAL","MANAGEMENT_CONTROL","GRANT_PROGRAM"),names);
  }

  @Test void bankAndInvestorAndBusinessPlanGetSeparateEvidenceAndCoreForms()
      throws Exception {
    JsonNode bank=getJson("/api/v1/catalog/use-cases/BANK_FINANCING/forms");
    assertEquals(8,bank.get("useCase").get("coreCount").asInt());
    assertEquals(12,bank.get("useCase").get("evidenceCount").asInt());
    assertEquals(27,bank.get("forms").size());
    Set<String> bankCodes=codes(bank.get("forms"));
    for(var code:List.of("F01","F02","F03","C05","P03","P04","P09","P10","D01","D08","T04"))
      assertTrue(bankCodes.contains(code),code);
    for(var form:bank.get("forms")){
      assertEquals("SUGGESTED_NOT_MANDATORY",form.get("applicability").asText());
      assertEquals("CANDIDATE_UNQUALIFIED",form.get("qualificationState").asText());
      if(form.get("formCode").asText().equals("T04"))
        assertEquals("EVIDENCE",form.get("role").asText());
    }
    assertTrue(bank.get("warnings").toString().contains("NO_REGULATORY_OR_FINANCIAL_APPROVAL_INFERRED"));
    JsonNode investor=getJson("/api/v1/catalog/use-cases/INVESTOR_READINESS/forms");
    Set<String> investorCodes=codes(investor.get("forms"));
    assertTrue(investorCodes.containsAll(List.of("F01","F02","F03","P07","P08","D08")));
    assertFalse(investorCodes.contains("D01"));
    JsonNode business=getJson("/api/v1/catalog/use-cases/BUSINESS_PLAN/forms");
    Set<String> businessCodes=codes(business.get("forms"));
    assertTrue(businessCodes.containsAll(List.of("DF-CTX-01","DF-PRJ-01",
      "P01","P02","P03","DF-MKT-01")));
    assertFalse(businessCodes.contains("D08"));
  }

  @Test void purposeSectorAndAnalyticalPackCombineWithoutConcealingOtherForms()
      throws Exception {
    var result=mapper.readTree(mvc.perform(get("/api/v1/catalog/navigation")
      .param("locale","ru").param("sectorCodes","G","J")
      .param("analysisPack","CFO").param("useCaseCode","BANK_FINANCING"))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertEquals(166,result.get("allForms").size());
    assertEquals(7,result.get("useCases").size());
    assertEquals("BANK_FINANCING",result.get("selectedUseCase").asText());
    Set<String> rec=codes(result.get("recommended"));
    assertTrue(rec.containsAll(List.of("F01","P09","T04","SF-G01","SF-J01","DF-CSH-01")));
    var reason=new HashMap<String,String>();
    for(var item:result.get("recommended"))
      reason.put(item.get("formCode").asText(),item.get("recommendationReason").asText());
    assertEquals("SELECTED_USE_CASE",reason.get("F02"));
    assertEquals("SELECTED_USE_CASE",reason.get("T04"));
    assertEquals("SELECTED_SECTOR",reason.get("SF-G01"));
    assertEquals("ANALYSIS_MODEL_INPUT",reason.get("DF-CSH-01"));
    assertTrue(codes(result.get("allForms")).contains("SF-Q01"));
    var baseline=getJson("/api/v1/catalog/navigation");
    assertTrue(baseline.get("selectedUseCase").isNull());
    assertEquals(166,baseline.get("allForms").size());
  }

  @Test void invalidUseCaseOrLocaleRejectedWithoutFallback() throws Exception {
    mvc.perform(get("/api/v1/catalog/use-cases/UNKNOWN/forms"))
      .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/catalog/navigation").param("useCaseCode","UNKNOWN"))
      .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/catalog/use-cases/BANK_FINANCING/forms").param("locale","de"))
      .andExpect(status().isBadRequest());
    JsonNode zh=mapper.readTree(mvc.perform(
      get("/api/v1/catalog/use-cases/BANK_FINANCING/forms").param("locale","zh-Hans"))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertEquals("zh-Hans",zh.get("locale").asText());
    assertEquals(27,zh.get("forms").size());
    var lower=mapper.readTree(mvc.perform(
      get("/api/v1/catalog/use-cases/bank_financing/forms"))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertEquals("BANK_FINANCING",lower.get("useCase").get("useCaseCode").asText());
  }

  @Test void purposeCatalogReadsNeverWriteFinancialData() throws Exception {
    long beforeInstances=instances.count(),beforeRevisions=revisions.count();
    long beforeReports=reports.count(),beforeReportRevisions=reportRevisions.count();
    for(String code:List.of("FINANCIAL_HEALTH","INVESTOR_READINESS","BUSINESS_PLAN",
      "BANK_FINANCING","INVESTMENT_APPRAISAL","MANAGEMENT_CONTROL","GRANT_PROGRAM")){
      JsonNode item=getJson("/api/v1/catalog/use-cases/"+code+"/forms");
      assertTrue(item.get("forms").size()>0);
      codes(item.get("forms"));
      assertEquals("CANDIDATE_NOT_S2",
        item.get("useCase").get("qualificationState").asText());
    }
    assertEquals(beforeInstances,instances.count());
    assertEquals(beforeRevisions,revisions.count());
    assertEquals(beforeReports,reports.count());
    assertEquals(beforeReportRevisions,reportRevisions.count());
  }
}
