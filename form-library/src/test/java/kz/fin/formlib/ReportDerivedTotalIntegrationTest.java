package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.service.ReportDerivedTotalService;
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
class ReportDerivedTotalIntegrationTest {
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  private String json(Object o)throws Exception{return mapper.writeValueAsString(o);}
  private UUID create(UUID org)throws Exception{
    var cfg=new ReportGridService.CreateReport(org,"TOTAL-"+UUID.randomUUID(),"Monthly revenue",
      "KZT","M",List.of("2026-01","2026-02"),List.of("F02.netRevenue"),"COLUMNS");
    var str=http.perform(post("/api/v1/report-grids").contentType(MediaType.APPLICATION_JSON)
      .content(json(cfg))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(mapper.readTree(str).get("reportId").asText());
  }
  private JsonNode view(UUID id,UUID org,String mode,String axis,Integer version)throws Exception {
    var request=get("/api/v1/report-grids/"+id+"/grid")
      .param("organizationId",org.toString()).param("viewMode",mode).param("timeAxis",axis);
    if(version!=null)request.param("derivedVersion",version.toString());
    var str=http.perform(request).andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();
    return mapper.readTree(str);
  }
  private void update(UUID id,UUID org,int expected,String op)throws Exception {
    var req=new ReportDerivedTotalService.UpdateRequest(org,expected,
      List.of(new ReportDerivedTotalService.RuleInput("F02.netRevenue",op)));
    http.perform(put("/api/v1/report-grids/"+id+"/derived-rules")
      .contentType(MediaType.APPLICATION_JSON).content(json(req)))
      .andExpect(status().isOk()).andExpect(jsonPath("$.derivedVersion").value(expected+1));
  }

  @Test void totalsAreHiddenForInputAndVisibleOnOutputWithUnchangedDataHash()throws Exception {
    UUID org=UUID.randomUUID(),id=create(org);
    String base="/api/v1/report-grids/"+id;
    var values=List.of(
      new ReportGridService.CellInput("F02.netRevenue","2026-01","PRESENT","1200.00"),
      new ReportGridService.CellInput("F02.netRevenue","2026-02","PRESENT","800.00"));
    http.perform(post(base+"/revisions").header("Idempotency-Key","first")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json(new ReportGridService.SaveReport(org,0,values,"tester","first","API"))))
      .andExpect(status().isOk());
    var initial=view(id,org,"VIEW","COLUMNS",null);
    assertEquals(2,initial.get("cells").size());
    String sha=initial.get("contentSha256").asText();
    update(id,org,1,"SUM");
    assertEquals(2,view(id,org,"INPUT","COLUMNS",null).get("cells").size());
    var result=view(id,org,"VIEW","COLUMNS",null);
    assertEquals(3,result.get("cells").size());
    assertEquals("__TOTAL__",result.get("columnHeaders").get(2).get("code").asText());
    assertEquals("Итого",result.get("columnHeaders").get(2).get("label").asText());
    assertEquals("2000",result.get("cells").get(2).get("value").asText());
    assertTrue(result.get("cells").get(2).get("derived").asBoolean());
    assertEquals(sha,result.get("contentSha256").asText());
    var rotated=view(id,org,"VIEW","ROWS",null);
    assertEquals("__TOTAL__",rotated.get("rowHeaders").get(2).get("code").asText());
    assertEquals("2000",rotated.get("cells").get(2).get("value").asText());
    assertEquals(sha,rotated.get("contentSha256").asText());
    update(id,org,2,"LAST_VALUE");
    assertEquals("800",view(id,org,"VIEW","COLUMNS",null)
      .get("cells").get(2).get("value").asText());
    assertEquals("2000",view(id,org,"VIEW","COLUMNS",2)
      .get("cells").get(2).get("value").asText());
    http.perform(get(base+"/derived-rules").param("organizationId",org.toString())
      .param("derivedVersion","2")).andExpect(status().isOk())
      .andExpect(jsonPath("$[0].operator").value("SUM"))
      .andExpect(jsonPath("$[0].reviewState").value("UNQUALIFIED_DRAFT"));
    var stale=new ReportDerivedTotalService.UpdateRequest(org,2,
      List.of(new ReportDerivedTotalService.RuleInput("F02.netRevenue","SUM")));
    http.perform(put(base+"/derived-rules").contentType(MediaType.APPLICATION_JSON)
      .content(json(stale))).andExpect(status().isConflict());
  }

  @Test void incompleteSourceDataNeverAutoZeroAndCannotSaveTotalAsSource()throws Exception {
    UUID org=UUID.randomUUID(),id=create(org);
    String base="/api/v1/report-grids/"+id;
    update(id,org,1,"SUM");
    http.perform(post(base+"/revisions").header("Idempotency-Key","partial")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json(new ReportGridService.SaveReport(org,0,List.of(
        new ReportGridService.CellInput("F02.netRevenue","2026-01","PRESENT","10")),
        "tester","partial","API"))))
      .andExpect(status().isOk());
    var result=view(id,org,"VIEW","COLUMNS",null);
    assertEquals("MISSING",result.get("cells").get(1).get("state").asText());
    assertEquals("INCOMPLETE",result.get("cells").get(2).get("state").asText());
    assertTrue(result.get("cells").get(2).get("value").isNull());
    assertEquals("неполные данные",result.get("cells").get(2).get("formattedValue").asText());
    http.perform(post(base+"/revisions").header("Idempotency-Key","illegal")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json(new ReportGridService.SaveReport(org,1,List.of(
        new ReportGridService.CellInput("F02.netRevenue","__TOTAL__","PRESENT","10")),
        "tester","illegal","API"))))
      .andExpect(status().isUnprocessableEntity());
    var unsupported=new ReportDerivedTotalService.UpdateRequest(org,2,
      List.of(new ReportDerivedTotalService.RuleInput("F02.netRevenue","AVG")));
    http.perform(put(base+"/derived-rules").contentType(MediaType.APPLICATION_JSON)
      .content(json(unsupported))).andExpect(status().isUnprocessableEntity());
  }
}
