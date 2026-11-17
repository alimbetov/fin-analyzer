package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.domain.FormDefinition;
import kz.fin.formlib.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;

@SpringBootTest
@AutoConfigureMockMvc
class FormLibraryIntegrationTest {
  @Autowired MockMvc web;
  @Autowired ObjectMapper json;
  @Autowired FormDefinitionRepository forms;
  @Autowired FormFieldRepository fields;
  @Autowired FormVersionRepository versions;

  @Test void all166AreSeededUnqualifiedWithPilotF02Order() throws Exception {
    assertEquals(166,forms.count());
    var list=web.perform(get("/api/v1/forms")).andExpect(status().isOk()).andReturn();
    JsonNode items=json.readTree(list.getResponse().getContentAsString());
    assertEquals(166,items.size());
    assertEquals(122,java.util.stream.StreamSupport.stream(items.spliterator(),false)
      .filter(x->x.get("captureMode").asText().equals("TYPED_DRAFT")).count());
    assertEquals(12,java.util.stream.StreamSupport.stream(items.spliterator(),false)
      .filter(x->x.get("captureMode").asText().equals("EVIDENCE_ONLY")).count());
    assertEquals(32,java.util.stream.StreamSupport.stream(items.spliterator(),false)
      .filter(x->x.get("captureMode").asText().equals("SCHEMA_NOT_APPROVED")).count());
    web.perform(get("/api/v1/forms/F02")).andExpect(status().isOk())
      .andExpect(jsonPath("$.fields[0].fieldCode").value("netRevenue"))
      .andExpect(jsonPath("$.fields[1].fieldCode").value("cogs"))
      .andExpect(jsonPath("$.fields[2].fieldCode").value("operatingExpenses"))
      .andExpect(jsonPath("$.fields[0].displayOrder").value(10))
      .andExpect(jsonPath("$.summary.qualificationState").value("CANDIDATE_UNQUALIFIED"));
  }
  private String request(UUID instanceId,UUID org,String code,int expected,Map<String,String> values,
      String rowsJson) {
    String valueJson;
    try {valueJson=json.writeValueAsString(values);}catch(Exception e){throw new RuntimeException(e);}
    return """
      {"instanceId":%s,"organizationId":"%s","economicEntityId":"00000000-0000-0000-0000-000000000002",
      "businessScopeId":"00000000-0000-0000-0000-000000000003",
      "projectId":"00000000-0000-0000-0000-000000000004",
      "formCode":"%s","periodType":"MONTH","periodCode":"2026.02",
      "currency":"KZT","coverage":"PERIOD_ONLY","reportingBasis":"MANAGEMENT",
      "dataSpace":"SYNTHETIC","expectedRevision":%d,"values":%s,
      "rows":%s,"createdBy":"tester","changeReason":"integration"}
      """.formatted(instanceId==null?"null":"\""+instanceId+"\"",org,code,expected,valueJson,rowsJson);
  }
  @Test void f02CreateReadRetryAndStaleExpectedRevision() throws Exception {
    UUID org=UUID.randomUUID();
    var payload=request(null,org,"F02",0,
      Map.of("netRevenue","12000000.00","cogs","7000000.00","operatingExpenses","2000000.00"),"[]");
    String receipt=web.perform(post("/api/v1/drafts")
        .header("Idempotency-Key","f02-"+org).contentType(MediaType.APPLICATION_JSON).content(payload))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    JsonNode result=json.readTree(receipt);
    UUID id=UUID.fromString(result.get("instanceId").asText());
    web.perform(get("/api/v1/drafts/"+id).param("organizationId",org.toString()))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.values.netRevenue").value("12000000"))
       .andExpect(jsonPath("$.revisionNo").value(1));
    web.perform(post("/api/v1/drafts")
        .header("Idempotency-Key","f02-"+org).contentType(MediaType.APPLICATION_JSON).content(payload))
        .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("IDEMPOTENT_REPLAY"));
    web.perform(get("/api/v1/drafts/"+id).param("organizationId",UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
    String stale=request(id,org,"F02",0,
        Map.of("netRevenue","100","cogs","50","operatingExpenses","10"),"[]");
    web.perform(post("/api/v1/drafts").header("Idempotency-Key","other-"+org)
        .contentType(MediaType.APPLICATION_JSON).content(stale)).andExpect(status().isConflict());
  }
  @Test void rowsetSaveAndReopenPreservesPosition() throws Exception {
    UUID org=UUID.randomUUID(),rowId1=UUID.randomUUID(),rowId2=UUID.randomUUID();
    String rows="[{\"rowId\":\""+rowId1+"\",\"rowPosition\":20,\"values\":{\"paymentRef\":\"PAY-2\",\"postedAt\":\"2026-02-03\",\"amount\":\"25\",\"purpose\":\"B\",\"invoiceRef\":\"INV-2\"}},{\"rowId\":\""+rowId2+"\",\"rowPosition\":10,\"values\":{\"paymentRef\":\"PAY-1\",\"postedAt\":\"2026-02-02\",\"amount\":\"15\",\"purpose\":\"A\",\"invoiceRef\":\"INV-1\"}}]";
    String receipt=web.perform(post("/api/v1/drafts").header("Idempotency-Key","rows-"+org)
        .contentType(MediaType.APPLICATION_JSON)
        .content(request(null,org,"DF-CSH-01",0,Map.of(),rows)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    UUID id=UUID.fromString(json.readTree(receipt).get("instanceId").asText());
    web.perform(get("/api/v1/drafts/"+id).param("organizationId",org.toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].rowPosition").value(10))
        .andExpect(jsonPath("$.rows[0].rowId").value(rowId2.toString()))
        .andExpect(jsonPath("$.rows[1].rowPosition").value(20))
        .andExpect(jsonPath("$.rows[1].rowId").value(rowId1.toString()));
  }
  @Test void reorderingRowsetChangesPresentationButNotFinancialContentHash() throws Exception {
    UUID org=UUID.randomUUID(), a=UUID.randomUUID(), b=UUID.randomUUID();
    String va="""
      {"paymentRef":"P-A","postedAt":"2026-02-01","amount":"100",
       "purpose":"RECEIPT","invoiceRef":"I-A"}
      """.trim();
    String vb="""
      {"paymentRef":"P-B","postedAt":"2026-02-02","amount":"200",
       "purpose":"RECEIPT","invoiceRef":"I-B"}
      """.trim();
    String initial="""
      [{"rowId":"%s","rowPosition":10,"values":%s},
       {"rowId":"%s","rowPosition":20,"values":%s}]
      """.formatted(a,va,b,vb);
    String created=web.perform(post("/api/v1/drafts").header("Idempotency-Key","first-"+org)
      .contentType(MediaType.APPLICATION_JSON)
      .content(request(null,org,"DF-CSH-01",0,Map.of(),initial)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    JsonNode receipt=json.readTree(created);
    UUID instanceId=UUID.fromString(receipt.get("instanceId").asText());
    String reordered="""
      [{"rowId":"%s","rowPosition":20,"values":%s},
       {"rowId":"%s","rowPosition":10,"values":%s}]
      """.formatted(a,va,b,vb);
    String updated=web.perform(post("/api/v1/drafts").header("Idempotency-Key","second-"+org)
      .contentType(MediaType.APPLICATION_JSON)
      .content(request(instanceId,org,"DF-CSH-01",1,Map.of(),reordered)))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertEquals(receipt.get("contentSha256").asText(),
      json.readTree(updated).get("contentSha256").asText());
    web.perform(get("/api/v1/drafts/"+instanceId).param("organizationId",org.toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.revisionNo").value(2))
      .andExpect(jsonPath("$.rows[0].rowId").value(b.toString()));
    web.perform(get("/api/v1/drafts/"+instanceId+"/revisions/1")
      .param("organizationId",org.toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.revisionNo").value(1))
      .andExpect(jsonPath("$.rows[0].rowId").value(a.toString()));
  }

}
