package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.repository.*;
import kz.fin.formlib.service.InputTemplatePreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InputTemplatePreviewIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired InputTemplatePreviewService service;
  @Autowired FormInstanceRepository instances;
  @Autowired FormRevisionRepository formRevisions;
  @Autowired LibReportDefinitionRepository reportDefinitions;
  @Autowired LibReportRevisionRepository reportRevisions;

  private JsonNode preview(String code,String locale,String style,String... periods)throws Exception{
    var req=get("/api/v1/templates/"+code+"/preview")
      .param("locale",locale).param("periodType","M").param("periodCodes",periods);
    if(style!=null)req.param("styleCode",style);
    return json.readTree(mvc.perform(req).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }
  private static Map<String,String> keys(JsonNode result){
    Map<String,String> keys=new TreeMap<>();
    for(JsonNode c:result.get("cells")){
      assertEquals("MISSING",c.get("state").asText());
      assertTrue(c.get("value").isNull());
      String key=c.get("metricCode").asText()+"|"+c.get("periodCode").asText();
      assertNull(keys.put(key,c.get("state").asText()),"duplicate financial key "+key);
    }
    return keys;
  }

  @Test void excelPreviewHasFixed36pxCenteredRowHeaderAndExplicitEditableCells()throws Exception{
    JsonNode v=preview("F02","ru","EXCEL_GRID","2026-01","2026-02");
    assertTrue(v.get("previewOnly").asBoolean());
    assertFalse(v.get("persisted").asBoolean());
    assertEquals("F02",v.get("formCode").asText());
    assertEquals("EXCEL_GRID",v.get("presentation").get("styleCode").asText());
    var p=v.get("presentation").get("rowNumberColumn");
    assertEquals(36,p.get("widthPx").asInt());
    assertEquals(36,p.get("minWidthPx").asInt());
    assertEquals(36,p.get("maxWidthPx").asInt());
    assertEquals("CENTER",p.get("horizontalAlign").asText());
    assertEquals("CENTER",p.get("verticalAlign").asText());
    assertFalse(p.get("resizable").asBoolean());
    assertEquals("LEFT",p.get("pinned").asText());
    assertEquals("ROWS",v.get("presentation").get("banding").asText());
    assertTrue(v.get("presentation").get("showGridlines").asBoolean());
    var scroll=v.get("presentation").get("scrolling");
    assertEquals(2,v.get("presentation").get("styleVersion").asInt());
    assertEquals("AUTO",scroll.get("overflowX").asText());
    assertEquals("AUTO",scroll.get("overflowY").asText());
    assertEquals("100%",scroll.get("viewportWidth").asText());
    assertEquals(520,scroll.get("maxViewportHeightPx").asInt());
    assertEquals(2,scroll.get("frozenHeaderRows").asInt());
    assertEquals(2,scroll.get("frozenLeadingColumns").asInt());
    assertEquals(28,scroll.get("columnLettersHeightPx").asInt());
    assertEquals(36,scroll.get("columnTitlesHeightPx").asInt());
    assertTrue(scroll.get("overscrollContain").asBoolean());
    assertTrue(scroll.get("keepActiveCellVisible").asBoolean());
    assertEquals("ACTIVE_BLUE_BORDER_2PX",
      v.get("presentation").get("highlights").get("active").asText());
    assertEquals("A",v.get("columnHeaders").get(0).get("excelColumn").asText());
    assertEquals("B",v.get("columnHeaders").get(1).get("excelColumn").asText());
    assertEquals("C",v.get("columnHeaders").get(2).get("excelColumn").asText());
    assertEquals(2,v.get("rowHeaders").get(0).get("excelRow").asInt());
    assertEquals(3,v.get("rowHeaders").get(1).get("excelRow").asInt());
    assertEquals(6,v.get("cells").size());
    assertEquals("B2",v.get("cells").get(0).get("excelAddress").asText());
    assertEquals("F02.netRevenue",v.get("cells").get(0).get("metricCode").asText());
    assertEquals("2026-01",v.get("cells").get(0).get("periodCode").asText());
    assertTrue(v.get("cells").get(0).get("editable").asBoolean());
    assertFalse(v.get("cells").get(0).get("banded").asBoolean());
    assertTrue(v.get("cells").get(2).get("banded").asBoolean());
    assertEquals(6,keys(v).size());
  }

  @Test void stylesAndFourLocalesShareSameCanonicalMetricPeriodSet()throws Exception{
    JsonNode base=preview("F02","ru","EXCEL_GRID","2026-01","2026-02");
    var baseKeys=keys(base);
    for(String locale:List.of("ru","kk","en","zh-Hans")){
      for(String style:List.of("EXCEL_GRID","TRANSPOSED_GRID")){
        var v=preview("F02",locale,style,"2026-01","2026-02");
        assertEquals(baseKeys,keys(v));
        assertEquals(36,v.get("presentation").get("rowNumberColumn").get("widthPx").asInt());
        if("TRANSPOSED_GRID".equals(style)){
          assertEquals("2026-01",v.get("rowHeaders").get(0).get("code").asText());
          assertEquals("F02.netRevenue",v.get("columnHeaders").get(1).get("code").asText());
        }
      }
      JsonNode one=preview("F02",locale,"SECTION_FORM","2026-01");
      assertEquals(3,keys(one).size());
      assertEquals("B2",one.get("cells").get(0).get("excelAddress").asText());
    }
  }

  @Test void rowsetAndEvidenceShowNoFabricatedRowsOrFacts()throws Exception{
    JsonNode rowset=preview("DF-CSH-01","ru",null,"2026-02");
    assertEquals("ROWSET_TABLE",rowset.get("presentation").get("styleCode").asText());
    assertEquals(0,rowset.get("cells").size());
    assertEquals(0,rowset.get("rowHeaders").size());
    assertTrue(rowset.get("columnHeaders").size()>1);
    assertEquals(36,rowset.get("presentation").get("rowNumberColumn").get("widthPx").asInt());
    var definition=service.preview("F02","ru","M",List.of("2026-01"),"SECTION_FORM");
    assertEquals("SECTION_FORM",definition.presentation().styleCode());
  }

  @Test void invalidRequestsFailClosed()throws Exception{
    mvc.perform(get("/api/v1/templates/F02/preview").param("locale","ru")
      .param("periodType","M").param("periodCodes","2026-01")
      .param("styleCode","UNKNOWN")).andExpect(status().isUnprocessableEntity());
    mvc.perform(get("/api/v1/templates/F02/preview").param("locale","ru")
      .param("periodType","M").param("periodCodes","2026-01","2026-02")
      .param("styleCode","SECTION_FORM")).andExpect(status().isUnprocessableEntity());
    mvc.perform(get("/api/v1/templates/F02/preview").param("locale","en")
      .param("periodType","M").param("periodCodes","2026-01","2026.01"))
      .andExpect(status().isUnprocessableEntity());
    mvc.perform(get("/api/v1/templates/F02/preview").param("locale","de")
      .param("periodType","M").param("periodCodes","2026-01"))
      .andExpect(status().isUnprocessableEntity());
    mvc.perform(get("/api/v1/templates/UNKNOWN-FORM/preview").param("locale","ru")
      .param("periodType","M").param("periodCodes","2026-01"))
      .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/templates/DF-CSH-01/preview").param("locale","ru")
      .param("periodType","M").param("periodCodes","2026-01")
      .param("styleCode","EXCEL_GRID")).andExpect(status().isUnprocessableEntity());
    assertEquals("AA",InputTemplatePreviewService.excelColumn(27));
    assertEquals("AZ",InputTemplatePreviewService.excelColumn(52));
    assertThrows(IllegalArgumentException.class,()->InputTemplatePreviewService.excelColumn(0));
  }

  @Test void scrollPolicyIsCompatibleWithAllTemplateStyles()throws Exception{
    for(String style:List.of("EXCEL_GRID","TRANSPOSED_GRID","SECTION_FORM")){
      JsonNode v=preview("F02","ru",style,"2026-01");
      var s=v.get("presentation").get("scrolling");
      assertEquals("AUTO",s.get("overflowY").asText());
      assertEquals(520,s.get("maxViewportHeightPx").asInt());
      assertEquals("100%",s.get("viewportWidth").asText());
      assertEquals("CENTER",v.get("presentation").get("rowNumberColumn")
        .get("horizontalAlign").asText());
      if("SECTION_FORM".equals(style)){
        assertEquals("HIDDEN",s.get("overflowX").asText());
        assertEquals(1,s.get("frozenHeaderRows").asInt());
        assertEquals(0,s.get("frozenLeadingColumns").asInt());
        assertFalse(v.get("presentation").get("freezeFirstColumn").asBoolean());
      }else{
        assertEquals("AUTO",s.get("overflowX").asText());
        assertEquals(2,s.get("frozenHeaderRows").asInt());
        assertEquals(2,s.get("frozenLeadingColumns").asInt());
        assertTrue(v.get("presentation").get("freezeFirstColumn").asBoolean());
      }
    }
    var rowset=preview("DF-CSH-01","ru","ROWSET_TABLE","2026-01");
    var scroll=rowset.get("presentation").get("scrolling");
    assertEquals("AUTO",scroll.get("overflowX").asText());
    assertEquals("AUTO",scroll.get("overflowY").asText());
    assertEquals(2,scroll.get("frozenLeadingColumns").asInt());
    assertEquals(36,rowset.get("presentation").get("rowNumberColumn").get("widthPx").asInt());
    assertEquals(0,rowset.get("cells").size());
  }

  @Test void maximum60PeriodsSupportHorizontalAndVerticalScrollingWithoutChangingKeys()throws Exception{
    var periods=new ArrayList<String>();
    for(int year=2022;year<=2026;year++)
      for(int month=1;month<=12;month++)
        periods.add(java.time.YearMonth.of(year,month).toString());
    assertEquals(60,periods.size());
    var columns=preview("F02","ru","EXCEL_GRID",periods.toArray(String[]::new));
    var rows=preview("F02","ru","TRANSPOSED_GRID",periods.toArray(String[]::new));
    assertEquals(61,columns.get("columnHeaders").size());
    assertEquals("BI",columns.get("columnHeaders").get(60).get("excelColumn").asText());
    assertEquals(60,rows.get("rowHeaders").size());
    assertEquals(61,rows.get("rowHeaders").get(59).get("excelRow").asInt());
    assertEquals(180,columns.get("cells").size());
    assertEquals(180,rows.get("cells").size());
    assertEquals(keys(columns),keys(rows));
    for(var v:List.of(columns,rows)){
      assertEquals("AUTO",v.get("presentation").get("scrolling").get("overflowX").asText());
      assertEquals("AUTO",v.get("presentation").get("scrolling").get("overflowY").asText());
      assertFalse(v.get("persisted").asBoolean());
      assertTrue(v.get("cells").get(0).get("value").isNull());
    }
  }

  @Test void readOnlyTemplatePreviewNeverWritesIntake()throws Exception{
    long beforeInstances=instances.count(),beforeFormRevisions=formRevisions.count();
    long beforeReports=reportDefinitions.count(),beforeReportRevisions=reportRevisions.count();
    for(var style:List.of("EXCEL_GRID","TRANSPOSED_GRID","SECTION_FORM"))
      preview("F02","ru",style,"2026-01");
    preview("DF-CSH-01","ru","ROWSET_TABLE","2026-01");
    assertEquals(beforeInstances,instances.count());
    assertEquals(beforeFormRevisions,formRevisions.count());
    assertEquals(beforeReports,reportDefinitions.count());
    assertEquals(beforeReportRevisions,reportRevisions.count());
  }
}
