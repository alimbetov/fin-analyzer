package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.repository.LibReportRevisionRepository;
import kz.fin.formlib.service.ReportGridService;
import kz.fin.formlib.service.ReportInputValidationService;
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
class ReportInputValidationIntegrationTest {
  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired LibReportRevisionRepository revisions;
  private String json(Object data)throws Exception{return mapper.writeValueAsString(data);}
  private UUID create(UUID org)throws Exception{
    var create=new ReportGridService.CreateReport(org,"VAL-"+UUID.randomUUID(),"Validation",
      "KZT","M",List.of("2026-01","2026-02"),List.of("F02.netRevenue"),"COLUMNS");
    String result=http.perform(post("/api/v1/report-grids")
      .contentType(MediaType.APPLICATION_JSON).content(json(create)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(mapper.readTree(result).get("reportId").asText());
  }
  private void rules(String base,UUID org,int version,boolean allowMissing,
                     boolean allowNa,String sign,String min,String max)throws Exception{
    var rule=new ReportInputValidationService.RuleInput("F02.netRevenue",
      allowMissing,allowNa,sign,min,max,38,2);
    http.perform(put(base+"/validation").contentType(MediaType.APPLICATION_JSON)
      .content(json(new ReportInputValidationService.UpdateRequest(org,version,List.of(rule)))))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.validationVersion").value(version+1));
  }

  @Test void noAutoZeroAndExplicitNonNegativeRule()throws Exception{
    UUID org=UUID.randomUUID(),id=create(org);
    String base="/api/v1/report-grids/"+id;
    http.perform(get(base+"/validation").param("organizationId",org.toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].allowMissing").value(true));
    http.perform(post(base+"/revisions").header("Idempotency-Key","empty")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json(new ReportGridService.SaveReport(org,0,List.of(),"tester","empty","API"))))
      .andExpect(status().isOk());
    http.perform(get(base+"/grid").param("organizationId",org.toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.cells[0].state").value("MISSING"));
    rules(base,org,1,false,false,"NON_NEGATIVE",null,null);
    String missing=http.perform(post(base+"/validate").contentType(MediaType.APPLICATION_JSON)
      .content(json(Map.of("organizationId",org,"cells",List.of()))))
      .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
      .andReturn().getResponse().getContentAsString();
    assertTrue(missing.contains("MISSING_REQUIRED_CELL"));
    var invalid=List.of(
      new ReportGridService.CellInput("F02.netRevenue","2026-01","PRESENT","-1"),
      new ReportGridService.CellInput("F02.netRevenue","2026-02","NOT_APPLICABLE",null));
    var preview=http.perform(post(base+"/validate").contentType(MediaType.APPLICATION_JSON)
      .content(json(Map.of("organizationId",org,"cells",invalid))))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertTrue(preview.contains("SIGN_RULE_VIOLATION"));
    assertTrue(preview.contains("NOT_APPLICABLE_FORBIDDEN"));
    http.perform(post(base+"/revisions").header("Idempotency-Key","negative")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json(new ReportGridService.SaveReport(org,1,invalid,"tester","bad","API"))))
      .andExpect(status().isUnprocessableEntity());
    var allowed=List.of(
      new ReportGridService.CellInput("F02.netRevenue","2026-01","PRESENT","0"),
      new ReportGridService.CellInput("F02.netRevenue","2026-02","PRESENT","125.50"));
    var saved=http.perform(post(base+"/revisions").header("Idempotency-Key","accepted")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json(new ReportGridService.SaveReport(org,1,allowed,"tester","good","API"))))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    UUID revisionId=UUID.fromString(mapper.readTree(saved).get("revisionId").asText());
    var revision=revisions.findById(revisionId).orElseThrow();
    assertEquals(2,revision.getValidationVersion());
    assertEquals(64,revision.getValidationSha256().length());
    rules(base,org,2,false,false,"POSITIVE","1","1000");
    http.perform(post(base+"/revisions").header("Idempotency-Key","zero")
      .contentType(MediaType.APPLICATION_JSON)
      .content(json(new ReportGridService.SaveReport(org,2,allowed,"tester","bad zero","API"))))
      .andExpect(status().isUnprocessableEntity());
    http.perform(get(base+"/validation").param("organizationId",org.toString())
      .param("validationVersion","2")).andExpect(status().isOk())
      .andExpect(jsonPath("$[0].signRule").value("NON_NEGATIVE"));
    assertEquals(2,revisions.findById(revisionId).orElseThrow().getValidationVersion());
  }

  @Test void invalidBoundsStaleVersionAndIntegerScaleFailClosed()throws Exception{
    UUID org=UUID.randomUUID(),id=create(org);
    String base="/api/v1/report-grids/"+id;
    var impossible=new ReportInputValidationService.RuleInput("F02.netRevenue",
      true,true,"POSITIVE","20","10",38,2);
    http.perform(put(base+"/validation").contentType(MediaType.APPLICATION_JSON)
      .content(json(new ReportInputValidationService.UpdateRequest(org,1,List.of(impossible)))))
      .andExpect(status().isUnprocessableEntity());
    rules(base,org,1,true,true,"ANY",null,null);
    var stale=new ReportInputValidationService.RuleInput("F02.netRevenue",
      true,true,"ANY",null,null,38,2);
    http.perform(put(base+"/validation").contentType(MediaType.APPLICATION_JSON)
      .content(json(new ReportInputValidationService.UpdateRequest(org,1,List.of(stale)))))
      .andExpect(status().isConflict());
    var excessive=new ReportInputValidationService.RuleInput("F02.netRevenue",
      true,true,"ANY",null,null,38,5);
    http.perform(put(base+"/validation").contentType(MediaType.APPLICATION_JSON)
      .content(json(new ReportInputValidationService.UpdateRequest(org,2,List.of(excessive)))))
      .andExpect(status().isUnprocessableEntity());
  }
}
