package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.service.ReportFormatService;
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
class ReportFormatIntegrationTest {
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  private String body(Object obj)throws Exception{return json.writeValueAsString(obj);}
  private UUID create(UUID org,String currency,String periodType,List<String> periods,
                      List<String> metricCodes)throws Exception{
    var req=new ReportGridService.CreateReport(org,"FORMAT-"+UUID.randomUUID(),
      "Formatting pilot",currency,periodType,periods,metricCodes,"COLUMNS");
    JsonNode result=json.readTree(http.perform(post("/api/v1/report-grids")
      .contentType(MediaType.APPLICATION_JSON).content(body(req)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    return UUID.fromString(result.get("reportId").asText());
  }
  private JsonNode grid(UUID id,UUID org,String extraQueryKey,String extraQueryValue)throws Exception{
    var request=get("/api/v1/report-grids/"+id+"/grid")
      .param("organizationId",org.toString()).param("locale","en");
    if(extraQueryKey!=null)request.param(extraQueryKey,extraQueryValue);
    return json.readTree(http.perform(request).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString());
  }

  @Test void layoutFormattingIsVersionedWithoutRewritingStoredMoneyOrSha() throws Exception{
    UUID org=UUID.randomUUID();
    UUID id=create(org,"KZT","M",List.of("2026-01","2026-02"),List.of("F02.netRevenue"));
    String url="/api/v1/report-grids/"+id;
    var input=new ReportGridService.SaveReport(org,0,
      List.of(new ReportGridService.CellInput("F02.netRevenue","2026-01","PRESENT","-1234567.89")),
      "tester","init","API");
    http.perform(post(url+"/revisions").header("Idempotency-Key","initial")
      .contentType(MediaType.APPLICATION_JSON).content(body(input)))
      .andExpect(status().isOk());
    JsonNode first=grid(id,org,null,null);
    assertEquals(1,first.get("formatVersion").asInt());
    String sha=first.get("contentSha256").asText();
    assertEquals("-1234567.89",first.get("cells").get(0).get("value").asText());
    assertEquals("-1,234,567.89 KZT",first.get("cells").get(0).get("formattedValue").asText());
    assertEquals("—",first.get("cells").get(1).get("formattedValue").asText());
    var newProfile=new ReportFormatService.FormatInput("F02.netRevenue","MONEY",1,1000,
      "HALF_EVEN",true,"PARENTHESES","CODE");
    var update=new ReportFormatService.UpdateFormatRequest(org,1,List.of(newProfile));
    http.perform(put(url+"/formats").contentType(MediaType.APPLICATION_JSON).content(body(update)))
      .andExpect(status().isOk()).andExpect(jsonPath("$.formatVersion").value(2))
      .andExpect(jsonPath("$.dataRevisionNo").value(1));
    JsonNode after=grid(id,org,null,null);
    assertEquals(sha,after.get("contentSha256").asText());
    assertEquals("-1234567.89",after.get("cells").get(0).get("value").asText());
    assertEquals("(1,234.6) thousand KZT",after.get("cells").get(0).get("formattedValue").asText());
    JsonNode previous=grid(id,org,"formatVersion","1");
    assertEquals("-1,234,567.89 KZT",previous.get("cells").get(0).get("formattedValue").asText());
    assertEquals(1,previous.get("formatVersion").asInt());
    http.perform(get(url+"/formats").param("organizationId",org.toString())
      .param("formatVersion","1")).andExpect(status().isOk())
      .andExpect(jsonPath("$[0].scaleDivisor").value(1));
    http.perform(get(url+"/formats").param("organizationId",org.toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].scaleDivisor").value(1000));
    http.perform(put(url+"/formats").contentType(MediaType.APPLICATION_JSON).content(body(update)))
      .andExpect(status().isConflict());
    http.perform(get(url+"/grid").param("organizationId",org.toString())
      .param("locale","bad")).andExpect(status().isUnprocessableEntity());
  }

  @Test void explicitRatioPercentAndDateLabelsDoNotMutateRawValue() throws Exception{
    UUID org=UUID.randomUUID();
    UUID id=create(org,"USD","D",List.of("2026-11-17"),
      List.of("DF-AST-01.capacity"));
    String url="/api/v1/report-grids/"+id;
    http.perform(post(url+"/revisions").header("Idempotency-Key","init")
      .contentType(MediaType.APPLICATION_JSON)
      .content(body(new ReportGridService.SaveReport(org,0,List.of(
        new ReportGridService.CellInput("DF-AST-01.capacity","2026-11-17","PRESENT","0.125")
      ),"tester","init","API"))))
      .andExpect(status().isOk());
    String originalSha=grid(id,org,null,null).get("contentSha256").asText();
    var p=new ReportFormatService.FormatInput("DF-AST-01.capacity","RATIO_PERCENT",1,1,
      "HALF_UP",false,"MINUS","NONE");
    http.perform(put(url+"/formats").contentType(MediaType.APPLICATION_JSON)
      .content(body(new ReportFormatService.UpdateFormatRequest(org,1,List.of(p)))))
      .andExpect(status().isOk());
    JsonNode formatted=grid(id,org,"periodLabelStyle","SHORT");
    assertEquals(originalSha,formatted.get("contentSha256").asText());
    assertEquals("0.125",formatted.get("cells").get(0).get("value").asText());
    assertEquals("12.5 %",formatted.get("cells").get(0).get("formattedValue").asText());
    assertNotEquals("2026-11-17",formatted.get("columnHeaders").get(0).get("label").asText());
    http.perform(get(url+"/grid").param("organizationId",org.toString())
      .param("periodLabelStyle","INVALID")).andExpect(status().isUnprocessableEntity());
    var bad=new ReportFormatService.FormatInput("DF-AST-01.capacity","MONEY",2,1,
      "HALF_UP",true,"MINUS","CODE");
    http.perform(put(url+"/formats").contentType(MediaType.APPLICATION_JSON)
      .content(body(new ReportFormatService.UpdateFormatRequest(org,2,List.of(bad)))))
      .andExpect(status().isUnprocessableEntity());
    var invalidScale=new ReportFormatService.FormatInput("DF-AST-01.capacity","RATIO_PERCENT",
      2,1000,"HALF_UP",true,"MINUS","NONE");
    http.perform(put(url+"/formats").contentType(MediaType.APPLICATION_JSON)
      .content(body(new ReportFormatService.UpdateFormatRequest(org,2,List.of(invalidScale)))))
      .andExpect(status().isUnprocessableEntity());
  }

  @Test void fourLocalesPreserveRawAndNeverGuessFinancialTranslation() throws Exception{
    UUID org=UUID.randomUUID();
    UUID id=create(org,"KZT","M",List.of("2026-02"),
      List.of("F02.netRevenue","F02.cogs"));
    String url="/api/v1/report-grids/"+id;
    http.perform(post(url+"/revisions").header("Idempotency-Key","test")
      .contentType(MediaType.APPLICATION_JSON)
      .content(body(new ReportGridService.SaveReport(org,0,List.of(
        new ReportGridService.CellInput("F02.netRevenue","2026-02","PRESENT","1250.50"),
        new ReportGridService.CellInput("F02.cogs","2026-02","NOT_APPLICABLE",null)
      ),"tester","test","MANUAL"))))
      .andExpect(status().isOk());
    for(String locale:List.of("ru","kk","en","zh-Hans")){
      JsonNode data=json.readTree(http.perform(get(url+"/grid")
        .param("organizationId",org.toString()).param("locale",locale)
        .param("periodLabelStyle","LONG"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
      assertEquals("1250.5",data.get("cells").get(0).get("value").asText());
      assertTrue(data.get("cells").get(0).get("formattedValue").asText().endsWith("KZT"));
      assertEquals("NOT_APPLICABLE",data.get("cells").get(1).get("state").asText());
      assertFalse(data.get("cells").get(1).get("formattedValue").asText().isBlank());
    }
  }
}
