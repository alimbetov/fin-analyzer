package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.service.ReportGridService;
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
class ReportGridIntegrationTest {
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  private String json(Object data) throws Exception {return mapper.writeValueAsString(data);}
  private UUID create(UUID org,String currency,String periodType,List<String> periods,
                      List<String> indicators,String timeAxis) throws Exception {
    var req=new ReportGridService.CreateReport(org,"REPORT-"+UUID.randomUUID(),"Test report",
      currency,periodType,periods,indicators,timeAxis);
    String result=http.perform(post("/api/v1/report-grids")
      .contentType(MediaType.APPLICATION_JSON).content(json(req)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(mapper.readTree(result).get("reportId").asText());
  }
  private JsonNode read(UUID reportId,UUID org,String timeAxis) throws Exception {
    String result=http.perform(get("/api/v1/report-grids/"+reportId+"/grid")
      .param("organizationId",org.toString()).param("timeAxis",timeAxis).param("locale","en"))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return mapper.readTree(result);
  }

  @Test void createSavePivotReopenAndImmutableHistoricalRevision() throws Exception {
    UUID org=UUID.randomUUID();
    UUID id=create(org,"KZT","M",List.of("2026-01","2026.02","2026-03"),
      List.of("F02.netRevenue","F02.cogs","F02.operatingExpenses"),"COLUMNS");
    String endpoint="/api/v1/report-grids/"+id;
    JsonNode empty=read(id,org,"COLUMNS");
    assertEquals(0,empty.get("revisionNo").asInt());
    assertEquals(9,empty.get("cells").size());
    assertEquals("MISSING",empty.get("cells").get(0).get("state").asText());
    assertEquals("2026-02",empty.get("columnHeaders").get(1).get("code").asText());
    var cells=List.of(
      new ReportGridService.CellInput("F02.netRevenue","2026-02","PRESENT","1200.00"),
      new ReportGridService.CellInput("F02.netRevenue","2026-01","PRESENT","1000"),
      new ReportGridService.CellInput("F02.cogs","2026-02","PRESENT","0"),
      new ReportGridService.CellInput("F02.operatingExpenses","2026-02","NOT_APPLICABLE",null)
    );
    var save=new ReportGridService.SaveReport(org,0,cells,"tester","original","CLIPBOARD");
    JsonNode receipt=mapper.readTree(http.perform(post(endpoint+"/revisions")
      .header("Idempotency-Key","original")
      .contentType(MediaType.APPLICATION_JSON).content(json(save)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.revisionNo").value(1))
      .andReturn().getResponse().getContentAsString());
    String originalSha=receipt.get("contentSha256").asText();
    http.perform(post(endpoint+"/revisions").header("Idempotency-Key","original")
      .contentType(MediaType.APPLICATION_JSON).content(json(save)))
      .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IDEMPOTENT_REPLAY"));
    JsonNode horizontal=read(id,org,"COLUMNS"),vertical=read(id,org,"ROWS");
    assertEquals(originalSha,horizontal.get("contentSha256").asText());
    assertEquals(originalSha,vertical.get("contentSha256").asText());
    assertEquals("F02.netRevenue",horizontal.get("rowHeaders").get(0).get("code").asText());
    assertEquals("2026-01",vertical.get("rowHeaders").get(0).get("code").asText());
    assertEquals("F02.netRevenue",vertical.get("columnHeaders").get(0).get("code").asText());
    assertEquals("1200",horizontal.get("cells").get(1).get("value").asText());
    assertEquals("0",horizontal.get("cells").get(4).get("value").asText());
    assertEquals("NOT_APPLICABLE",horizontal.get("cells").get(7).get("state").asText());
    assertTrue(horizontal.get("cells").get(7).get("value").isNull());
    http.perform(put(endpoint+"/layout").contentType(MediaType.APPLICATION_JSON)
       .content(json(new ReportGridService.LayoutRequest(org,1,"ROWS"))))
       .andExpect(status().isOk()).andExpect(jsonPath("$.layoutVersion").value(2))
       .andExpect(jsonPath("$.latestRevisionNo").value(1));
    http.perform(get(endpoint+"/grid").param("organizationId",org.toString()))
       .andExpect(status().isOk()).andExpect(jsonPath("$.timeAxis").value("ROWS"))
       .andExpect(jsonPath("$.contentSha256").value(originalSha));
    var second=new ReportGridService.SaveReport(org,1,List.of(
        new ReportGridService.CellInput("F02.netRevenue","2026-02","PRESENT","1300")),
      "tester","new revision","MANUAL");
    http.perform(post(endpoint+"/revisions").header("Idempotency-Key","second")
       .contentType(MediaType.APPLICATION_JSON).content(json(second)))
       .andExpect(status().isOk()).andExpect(jsonPath("$.revisionNo").value(2));
    http.perform(get(endpoint+"/revisions/1/grid").param("organizationId",org.toString())
       .param("timeAxis","COLUMNS"))
       .andExpect(status().isOk()).andExpect(jsonPath("$.contentSha256").value(originalSha))
       .andExpect(jsonPath("$.cells[1].value").value("1200"));
    assertEquals("1300",read(id,org,"COLUMNS").get("cells").get(1).get("value").asText());
    http.perform(get(endpoint+"/grid").param("organizationId",UUID.randomUUID().toString()))
       .andExpect(status().isNotFound());
  }

  @Test void unknownOrDuplicateCellsAndCurrencyPrecisionAreRejected() throws Exception {
    UUID org=UUID.randomUUID();
    UUID id=create(org,"JPY","Q",List.of("2026-Q1","2026-Q2"),
      List.of("F02.netRevenue"),"ROWS");
    String url="/api/v1/report-grids/"+id+"/revisions";
    var invalid=new ReportGridService.SaveReport(org,0,List.of(
      new ReportGridService.CellInput("F02.netRevenue","2026-Q1","PRESENT","1.25")),
      "tester","invalid","API");
    http.perform(post(url).header("Idempotency-Key","minor")
       .contentType(MediaType.APPLICATION_JSON).content(json(invalid)))
       .andExpect(status().isUnprocessableEntity());
    var duplicate=new ReportGridService.SaveReport(org,0,List.of(
      new ReportGridService.CellInput("F02.netRevenue","2026-Q1","PRESENT","1"),
      new ReportGridService.CellInput("F02.netRevenue","2026-Q1","PRESENT","2")),
      "tester","dup","API");
    http.perform(post(url).header("Idempotency-Key","dup")
       .contentType(MediaType.APPLICATION_JSON).content(json(duplicate)))
       .andExpect(status().isUnprocessableEntity());
    var outside=new ReportGridService.SaveReport(org,0,List.of(
      new ReportGridService.CellInput("F02.netRevenue","2026-Q4","PRESENT","1")),
      "tester","outside","API");
    http.perform(post(url).header("Idempotency-Key","outside")
       .contentType(MediaType.APPLICATION_JSON).content(json(outside)))
       .andExpect(status().isUnprocessableEntity());
    var valid=new ReportGridService.SaveReport(org,0,List.of(
      new ReportGridService.CellInput("F02.netRevenue","2026-Q1","PRESENT","100")),
      "tester","valid","API");
    http.perform(post(url).header("Idempotency-Key","valid")
       .contentType(MediaType.APPLICATION_JSON).content(json(valid)))
       .andExpect(status().isOk());
    http.perform(post(url).header("Idempotency-Key","stale")
       .contentType(MediaType.APPLICATION_JSON).content(json(new ReportGridService.SaveReport(
         org,0,List.of(),"tester","stale","API"))))
       .andExpect(status().isConflict());
    http.perform(post(url).header("Idempotency-Key","valid")
       .contentType(MediaType.APPLICATION_JSON).content(json(new ReportGridService.SaveReport(
         org,0,List.of(),"tester","different payload","API"))))
       .andExpect(status().isConflict());
  }

  @Test void periodTypeValidationAndNonSourceMetricsAreFailClosed() throws Exception {
    UUID org=UUID.randomUUID();
    var unsupported=new ReportGridService.CreateReport(org,"NEG-"+UUID.randomUUID(),"Bad",
      "KZT","M",List.of("2026-01"),List.of("NET_PROFIT"),"ROWS");
    http.perform(post("/api/v1/report-grids").contentType(MediaType.APPLICATION_JSON)
       .content(json(unsupported))).andExpect(status().isUnprocessableEntity());
    var week53=new ReportGridService.CreateReport(org,"NEG-"+UUID.randomUUID(),"Bad",
      "KZT","W",List.of("2026-W54"),List.of("F02.netRevenue"),"ROWS");
    http.perform(post("/api/v1/report-grids").contentType(MediaType.APPLICATION_JSON)
       .content(json(week53))).andExpect(status().isUnprocessableEntity());
    UUID id=create(org,"KZT","DEC",List.of("2026-02-1","2026-02-2","2026-02-3"),
      List.of("F02.netRevenue"),"ROWS");
    JsonNode view=read(id,org,"ROWS");
    assertEquals("DEC",view.get("periodType").asText());
    assertEquals(3,view.get("rowHeaders").size());
  }
}
