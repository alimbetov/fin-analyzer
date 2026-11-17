package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class CatalogUiNavigationIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired FormInstanceRepository instances;
  @Autowired FormRevisionRepository revisions;
  @Autowired LibReportDefinitionRepository reports;
  @Autowired LibReportRevisionRepository reportRevisions;
  @Autowired FormDefinitionRepository definitions;

  private JsonNode getView(String url)throws Exception{
    return mapper.readTree(mvc.perform(get(url)).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }
  private String json(Object input)throws Exception{return mapper.writeValueAsString(input);}

  @Test void categoryHierarchyIsCompleteAndLocalizedInAllFourLanguages()throws Exception{
    Set<String> refCodes=null;
    for(String locale:List.of("ru","kk","en","zh-Hans")){
      JsonNode tree=getView("/api/v1/catalog/ui/categories?locale="+locale);
      assertEquals(47,tree.get("totalCategories").asInt());
      assertEquals(166,tree.get("totalForms").asInt());
      assertEquals(389,tree.get("totalAssociations").asInt());
      assertEquals(1,tree.get("catalogVersion").asInt());
      assertEquals(47,tree.get("categories").size());
      Set<String> codes=new HashSet<>();int roots=0;
      for(var c:tree.get("categories")){
        String code=c.get("categoryCode").asText();
        assertTrue(codes.add(code),"duplicate "+code);
        assertEquals("DRAFT_UNREVIEWED",c.get("translationStatus").asText());
        assertTrue(c.get("title").asText().length()>2);
        assertTrue(c.get("purpose").asText().length()>2);
        assertTrue(c.get("description").asText().length()>4);
        assertTrue(c.get("distinctFormCount").asInt()>0,code);
        if("ROOT".equals(c.get("categoryType").asText())){
          roots++;
          assertTrue(c.get("parentCategoryCode").isNull());
        }else{
          assertFalse(c.get("parentCategoryCode").asText().isBlank());
        }
      }
      assertEquals(7,roots);
      assertTrue(codes.containsAll(List.of("GROUP_FINANCE","GROUP_PURPOSE",
        "DETAIL_CASH_AND_DEBT","SECTOR_G","PURPOSE_BANK_FINANCING")));
      if(refCodes==null)refCodes=codes;else assertEquals(refCodes,codes);
    }
  }

  @Test void categorySelectionReturnsVersionedActualCatalogFormsAndDistinctPurposes()
      throws Exception{
    JsonNode finance=getView("/api/v1/catalog/ui/categories/GROUP_FINANCE/forms?locale=ru");
    assertEquals(8,finance.get("totalForms").asInt());
    assertEquals("Финансовая отчётность",
      finance.get("category").get("title").asText());
    Set<String> financeCodes=new HashSet<>();
    for(var item:finance.get("forms")){
      assertTrue(financeCodes.add(item.get("formCode").asText()));
      assertFalse(item.get("formVersionId").asText().isBlank());
      assertEquals("PRIMARY",item.get("associationRole").asText());
    }
    assertTrue(financeCodes.containsAll(List.of("F01","F02","F03","F08")));

    JsonNode bank=getView("/api/v1/catalog/ui/categories/PURPOSE_BANK_FINANCING/forms?locale=ru");
    assertEquals(27,bank.get("totalForms").asInt());
    Set<String> bankCodes=new HashSet<>();
    for(var item:bank.get("forms")){
      assertTrue(bankCodes.add(item.get("formCode").asText()));
      assertTrue(Set.of("CORE","SUPPORTING","EVIDENCE")
        .contains(item.get("associationRole").asText()));
    }
    assertTrue(bankCodes.containsAll(List.of("F01","P09","D01","T04")));
    var allGoals=getView("/api/v1/catalog/ui/categories/GROUP_PURPOSE/forms?locale=ru");
    Set<String> distinct=new HashSet<>();
    for(var item:allGoals.get("forms"))
      assertTrue(distinct.add(item.get("formCode").asText()));
    assertEquals(distinct.size(),allGoals.get("totalForms").asInt());
    assertEquals(6,getView("/api/v1/catalog/ui/categories/DETAIL_CASH_AND_DEBT/forms?locale=ru")
      .get("totalForms").asInt());
    assertEquals(3,getView("/api/v1/catalog/ui/categories/SECTOR_G/forms?locale=ru")
      .get("totalForms").asInt());
  }

  @Test void actualFourLanguageFormTitlePurposeAndDescriptionAreSeparate()throws Exception{
    var expected=new HashMap<String,String>();
    expected.put("ru","Оценить доходность и финансовый результат");
    expected.put("kk","Табыстылық пен қаржылық нәтижені бағалау");
    expected.put("en","Assess profitability and financial results");
    expected.put("zh-Hans","评估盈利能力与财务业绩");
    String formVersionId=null;
    for(var locale:List.of("ru","kk","en","zh-Hans")){
      var list=getView("/api/v1/catalog/ui/categories/GROUP_FINANCE/forms?locale="+locale);
      JsonNode f02=null;
      for(var f:list.get("forms"))
        if("F02".equals(f.get("formCode").asText()))f02=f;
      assertNotNull(f02);
      assertEquals(expected.get(locale),f02.get("purpose").asText());
      assertEquals("DRAFT_UNREVIEWED",f02.get("purposeTranslationStatus").asText());
      assertTrue(f02.get("title").asText().length()>3);
      assertTrue(f02.get("description").asText().length()>3);
      assertEquals("PERIOD_AND_STYLE_SELECTOR",f02.get("nextScreen").asText());
      if(formVersionId==null)formVersionId=f02.get("formVersionId").asText();
      else assertEquals(formVersionId,f02.get("formVersionId").asText());
      var entry=getView("/api/v1/catalog/ui/forms/F02/entry?locale="+locale);
      assertEquals(formVersionId,entry.get("formVersionId").asText());
      assertEquals(3,entry.get("displayStyles").size());
      assertEquals("NOT_REVIEWED",entry.get("periodPolicyStatus").asText());
      assertEquals(0,entry.get("approvedPeriodTypes").size());
    }

    // Untranslated form must stay explicitly null, not a fabricated name/purpose.
    var pending=getView("/api/v1/catalog/ui/forms/T04/entry?locale=zh-Hans");
    assertTrue(pending.get("title").isNull());
    assertTrue(pending.get("purpose").isNull());
    assertTrue(pending.get("description").isNull());
  }

  @Test void errorsFailClosedAndEvidenceRouteSkipsPeriodSelector()throws Exception{
    var evidence=getView("/api/v1/catalog/ui/forms/D01/entry?locale=ru");
    assertEquals("EVIDENCE_VIEWER",evidence.get("nextScreen").asText());
    assertEquals("EVIDENCE_INFO",evidence.get("displayStyles").get(0).asText());
    mvc.perform(get("/api/v1/catalog/ui/categories/NOT_PRESENT/forms"))
      .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/catalog/ui/forms/NOT_PRESENT/entry"))
      .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/catalog/ui/categories?locale=de"))
      .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/catalog/ui/categories/GROUP_FINANCE/forms?locale=de"))
      .andExpect(status().isBadRequest());
  }

  @Test void translationPurposeIsEditedOnlyInNewCatalogDraft()throws Exception{
    var f=definitions.findByFormCode("SF-U03").orElseThrow();
    UUID original=f.getCurrentVersionId();
    JsonNode begin=mapper.readTree(mvc.perform(post("/api/v1/catalog-admin/forms/SF-U03/drafts")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json(new CatalogManagementService.BeginRequest(original,"local-qa"))))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    UUID edit=UUID.fromString(begin.get("editSessionId").asText());
    JsonNode edited=mapper.readTree(mvc.perform(
      put("/api/v1/catalog-admin/drafts/"+edit+"/translations/kk")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(new CatalogManagementService.TranslationUpdate(
          0,"FORM",null,"Грант түсімдері","Қаражат туралы нысан",
          "local-qa","Бағдарламаны қаржыландыруды талдау"))))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertEquals(1,edited.get("editRevision").asInt());
    JsonNode detail=getView("/api/v1/catalog-admin/drafts/"+edit);
    boolean seen=false;
    for(var tr:detail.get("translations"))
      if("FORM".equals(tr.get("subjectType").asText())&&
          "kk".equals(tr.get("locale").asText())){
        assertEquals("Бағдарламаны қаржыландыруды талдау",tr.get("purpose").asText());
        assertEquals("DRAFT_UNREVIEWED",tr.get("purposeReviewStatus").asText());
        seen=true;
      }
    assertTrue(seen);
    assertEquals(original,definitions.findByFormCode("SF-U03").orElseThrow().getCurrentVersionId());
    mvc.perform(post("/api/v1/catalog-admin/drafts/"+edit+"/discard")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json(new CatalogManagementService.FinishRequest(1,original,"local-qa"))))
      .andExpect(status().isOk());
    assertEquals(original,definitions.findByFormCode("SF-U03").orElseThrow().getCurrentVersionId());
  }

  @Test void readOnlyFlowDoesNotWriteIntake()throws Exception{
    long a=instances.count(),b=revisions.count(),c=reports.count(),d=reportRevisions.count();
    for(String l:List.of("ru","kk","en","zh-Hans")){
      getView("/api/v1/catalog/ui/categories?locale="+l);
      getView("/api/v1/catalog/ui/categories/PURPOSE_BUSINESS_PLAN/forms?locale="+l);
      getView("/api/v1/catalog/ui/forms/F02/entry?locale="+l);
    }
    assertEquals(a,instances.count());
    assertEquals(b,revisions.count());
    assertEquals(c,reports.count());
    assertEquals(d,reportRevisions.count());
  }
}
