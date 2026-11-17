package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.repository.FormInstanceRepository;
import kz.fin.formlib.repository.FormRevisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FormNavigationIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired FormInstanceRepository instances;
  @Autowired FormRevisionRepository revisions;

  private JsonNode view(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
    throws Exception{
    return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }
  @Test void sixGroupsCoverExactly166And21SectorPacksWithoutDuplicates()throws Exception{
    long beforeInstances=instances.count(),beforeRevisions=revisions.count();
    var root=view(get("/api/v1/catalog/navigation"));
    assertEquals(166,root.get("totalForms").asInt());
    assertEquals(166,root.get("allForms").size());
    assertEquals(6,root.get("sections").size());
    assertEquals(21,root.get("sectors").size());
    assertEquals(42,root.get("totalModelDescriptors").asInt());
    assertEquals(47,root.get("coveredByModelCatalog").asInt());
    assertEquals(119,root.get("withoutModelReferences").asInt());
    int total=0;var groupCounts=new java.util.HashMap<String,Integer>();
    for(var group:root.get("sections")){
      int count=group.get("totalForms").asInt();
      groupCounts.put(group.get("groupCode").asText(),count);total+=count;
    }
    assertEquals(166,total);
    assertEquals(8,groupCounts.get("FINANCE"));
    assertEquals(14,groupCounts.get("LEDGERS"));
    assertEquals(10,groupCounts.get("PLANNING"));
    assertEquals(59,groupCounts.get("DETAIL"));
    assertEquals(63,groupCounts.get("SECTOR"));
    assertEquals(12,groupCounts.get("EVIDENCE"));
    Set<String> codes=new HashSet<>();
    int typed=0,evidence=0,unapproved=0;
    for(var form:root.get("allForms")){
      assertTrue(codes.add(form.get("formCode").asText()));
      assertEquals("CANDIDATE_UNQUALIFIED",form.get("qualificationState").asText());
      assertNotNull(form.get("primaryGroup"));
      assertNotNull(form.get("functionalDomain"));
      String capture=form.get("captureMode").asText();
      switch(capture){
        case "TYPED_DRAFT" -> typed++;
        case "EVIDENCE_ONLY" -> evidence++;
        case "SCHEMA_NOT_APPROVED" -> unapproved++;
        default -> fail("Unexpected capture mode "+capture);
      }
    }
    assertEquals(122,typed);
    assertEquals(12,evidence);
    assertEquals(32,unapproved);
    for(var sector:root.get("sectors")){
      assertEquals(3,sector.get("totalForms").asInt());
      assertTrue(sector.get("titleRu").asText().length()>3);
      assertTrue(sector.get("titleEn").asText().length()>3);
    }
    assertEquals(beforeInstances,instances.count());
    assertEquals(beforeRevisions,revisions.count());
  }

  @Test void diversifiedCompanyCanSelectTwoSectorsAndSeeWholeCatalog()throws Exception{
    var response=view(get("/api/v1/catalog/navigation")
      .param("sectorCodes","G","J").param("locale","ru"));
    assertEquals(166,response.get("allForms").size());
    assertEquals("G",response.get("selectedSectors").get(0).asText());
    assertEquals("J",response.get("selectedSectors").get(1).asText());
    var codes=new HashSet<String>();int sectors=0,starters=0;
    for(var form:response.get("recommended")){
      codes.add(form.get("formCode").asText());
      var reason=form.get("recommendationReason").asText();
      if("SELECTED_SECTOR".equals(reason)){
        sectors++;
        assertTrue(Set.of("G","J").contains(form.get("sectorCode").asText()));
      }else if("COMMON_STARTER".equals(reason))starters++;
      else fail("unexpected recommendation: "+reason);
    }
    assertEquals(6,sectors);
    assertEquals(4,starters);
    assertTrue(codes.contains("SF-G01"));
    assertTrue(codes.contains("SF-J03"));
    assertFalse(codes.contains("SF-Q01"));
    assertTrue(codes.contains("F02"));
    // Recommendation does not remove other sector forms from discovery.
    assertTrue(java.util.stream.StreamSupport.stream(response.get("allForms").spliterator(),false)
      .anyMatch(n->"SF-Q01".equals(n.get("formCode").asText())));
  }

  @Test void analysisPackAndSectorAreIndependentFacetsNotPermissions()throws Exception{
    var v=view(get("/api/v1/catalog/navigation").param("analysisPack","CFO")
      .param("sectorCodes","F"));
    assertEquals(166,v.get("allForms").size());
    assertEquals("CFO",v.get("selectedAnalysisPack").asText());
    assertEquals(5,v.get("analysisPacks").size());
    Set<String> suggested=new HashSet<>();
    for(var item:v.get("recommended")){
      suggested.add(item.get("formCode").asText());
      assertNotEquals("CATALOG_ONLY",item.get("recommendationReason").asText());
    }
    assertTrue(suggested.contains("SF-F01"));
    assertTrue(suggested.contains("F07"));
    assertTrue(suggested.contains("P03"));
    assertTrue(suggested.contains("DF-CSH-01"));
    for(var item:v.get("allForms")){
      if("F02".equals(item.get("formCode").asText())){
        assertEquals("CANDIDATE_UNQUALIFIED",item.get("qualificationState").asText());
      }
    }
    // A locale with missing translations must not invent names.
    var zh=view(get("/api/v1/catalog/navigation")
      .param("locale","zh-Hans").param("sectorCodes","Q"));
    assertEquals(166,zh.get("allForms").size());
    assertEquals("zh-Hans",zh.get("locale").asText());
  }

  @Test void rejectsUnknownSectorPackAndLocale()throws Exception{
    mvc.perform(get("/api/v1/catalog/navigation").param("sectorCodes","ZZ"))
      .andExpect(status().isUnprocessableEntity());
    mvc.perform(get("/api/v1/catalog/navigation").param("analysisPack","MADE_UP"))
      .andExpect(status().isUnprocessableEntity());
    mvc.perform(get("/api/v1/catalog/navigation").param("locale","xx"))
      .andExpect(status().isBadRequest());
    // Deduplicate a sector selected twice in different case.
    var v=view(get("/api/v1/catalog/navigation").param("sectorCodes","g","G"));
    assertEquals(1,v.get("selectedSectors").size());
    assertEquals(166,v.get("allForms").size());
  }
}
