package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.repository.FormInstanceRepository;
import kz.fin.formlib.repository.LibReportDefinitionRepository;
import kz.fin.formlib.service.ReportGridService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PeriodBatchIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired FormInstanceRepository instances;
  @Autowired LibReportDefinitionRepository reports;
  private String json(Object v)throws Exception{return mapper.writeValueAsString(v);}
  private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r)
      throws Exception{
    return mapper.readTree(mvc.perform(r).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }
  @Test void preview31DaysIncludesWeekdayWeekendMetadataInBothOrientationsWithoutWrites()
      throws Exception{
    long beforeInstances=instances.count(),beforeReports=reports.count();
    JsonNode calendar=response(get("/api/v1/period-batches/preview")
      .param("periodType","D").param("startPeriod","2026-11-01").param("count","31"));
    assertEquals(31,calendar.get("periods").size());
    assertEquals(31,calendar.get("maxPeriodCount").asInt());
    assertEquals("2026-12-01",calendar.get("periods").get(30).get("periodCode").asText());
    assertEquals("WEEKEND_SUNDAY",
      calendar.get("periods").get(0).get("calendarStyleToken").asText());
    assertEquals("WEEKEND_SATURDAY",
      calendar.get("periods").get(6).get("calendarStyleToken").asText());

    for(String style:List.of("EXCEL_GRID","TRANSPOSED_GRID")){
      JsonNode sheet=response(get("/api/v1/templates/F02/preview")
        .param("locale","ru").param("periodType","D")
        .param("startPeriod","2026-11-01").param("periodCount","31")
        .param("styleCode",style));
      assertEquals(31,sheet.get("periodCalendar").size());
      assertEquals(93,sheet.get("cells").size());
      assertEquals(3,sheet.get("fields").size());
      assertEquals("MISSING",sheet.get("cells").get(0).get("state").asText());
      assertTrue(sheet.get("cells").get(0).get("value").isNull());
      assertEquals("WEEKEND_SUNDAY",
        sheet.get("periodCalendar").get(0).get("calendarStyleToken").asText());
      assertEquals("WEEKEND_SATURDAY",
        sheet.get("periodCalendar").get(6).get("calendarStyleToken").asText());
      if("EXCEL_GRID".equals(style)){
        assertEquals(32,sheet.get("columnHeaders").size());
      }else{
        assertEquals(31,sheet.get("rowHeaders").size());
      }
    }
    assertEquals(beforeInstances,instances.count());
    assertEquals(beforeReports,reports.count());
  }

  @Test void rangeCreationUsesExistingReportGridAndImmutableRevisionWriter()throws Exception{
    UUID org=UUID.randomUUID();
    JsonNode created=response(post("/api/v1/report-grids/from-period-range")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json(new kz.fin.formlib.api.ReportGridController.CreateFromRange(
        org,"RANGE-"+UUID.randomUUID(),"Day ledger","KZT","D","2026-11-01",31,
        List.of("F02.netRevenue"),"COLUMNS"))));
    assertEquals(31,created.get("periodCount").asInt());
    UUID report=UUID.fromString(created.get("reportId").asText());
    JsonNode grid=response(get("/api/v1/report-grids/"+report+"/grid")
      .param("organizationId",org.toString()).param("timeAxis","COLUMNS"));
    assertEquals(31,grid.get("cells").size());
    assertEquals("2026-11-01",grid.get("columnHeaders").get(0).get("code").asText());
    assertEquals("2026-12-01",grid.get("columnHeaders").get(30).get("code").asText());
    var payload=new ReportGridService.SaveReport(org,0,List.of(
      new ReportGridService.CellInput("F02.netRevenue","2026-11-07","PRESENT","750"),
      new ReportGridService.CellInput("F02.netRevenue","2026-11-08","PRESENT","1200")
    ),"local-tester","test","MANUAL");
    JsonNode saved=response(post("/api/v1/report-grids/"+report+"/revisions")
      .header("Idempotency-Key","RANGE-TEST-"+org)
      .contentType(MediaType.APPLICATION_JSON).content(json(payload)));
    assertEquals(1,saved.get("revisionNo").asInt());
    String sha=saved.get("contentSha256").asText();
    JsonNode rotated=response(get("/api/v1/report-grids/"+report+"/grid")
      .param("organizationId",org.toString()).param("timeAxis","ROWS"));
    assertEquals("2026-11-07",rotated.get("rowHeaders").get(6).get("code").asText());
    assertEquals("750",rotated.get("cells").get(6).get("value").asText());
    assertEquals("1200",rotated.get("cells").get(7).get("value").asText());
    assertEquals(sha,rotated.get("contentSha256").asText());
  }

  @Test void rangeCreationValidatesMonthly12AndRejectsOversizedRanges()throws Exception{
    UUID org=UUID.randomUUID();
    JsonNode created=response(post("/api/v1/report-grids/from-period-range")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json(new kz.fin.formlib.api.ReportGridController.CreateFromRange(
        org,"MONTH-"+UUID.randomUUID(),"12 months","KZT","M","2026-03",12,
        List.of("F02.netRevenue","F02.cogs"),"ROWS"))));
    assertEquals(12,created.get("periodCount").asInt());
    UUID id=UUID.fromString(created.get("reportId").asText());
    JsonNode result=response(get("/api/v1/report-grids/"+id+"/grid")
      .param("organizationId",org.toString()).param("timeAxis","ROWS"));
    assertEquals(12,result.get("rowHeaders").size());
    assertEquals("2027-02",result.get("rowHeaders").get(11).get("code").asText());
    assertEquals(24,result.get("cells").size());
    for(var args:List.of(
      new kz.fin.formlib.api.ReportGridController.CreateFromRange(org,"NEG-"+UUID.randomUUID(),
        "invalid","KZT","M","2026-01",13,List.of("F02.netRevenue"),"ROWS"),
      new kz.fin.formlib.api.ReportGridController.CreateFromRange(org,"NEG-"+UUID.randomUUID(),
        "invalid","KZT","D","2026-01-01",32,List.of("F02.netRevenue"),"ROWS")
    ))mvc.perform(post("/api/v1/report-grids/from-period-range")
      .contentType(MediaType.APPLICATION_JSON).content(json(args)))
      .andExpect(status().isUnprocessableEntity());
  }

  @Test void mutuallyExclusiveAndMalformedPeriodSourcesFailClosed()throws Exception{
    mvc.perform(get("/api/v1/templates/F02/preview").param("periodType","M")
      .param("periodCodes","2026-01").param("startPeriod","2026-02")
      .param("periodCount","3")).andExpect(status().isUnprocessableEntity());
    mvc.perform(get("/api/v1/templates/F02/preview").param("periodType","M"))
      .andExpect(status().isUnprocessableEntity());
    mvc.perform(get("/api/v1/templates/F02/preview").param("periodType","M")
      .param("startPeriod","2026-01")).andExpect(status().isUnprocessableEntity());
    mvc.perform(get("/api/v1/period-batches/preview").param("periodType","D")
      .param("startPeriod","2026-11-01").param("count","0"))
      .andExpect(status().isUnprocessableEntity());
  }
}
