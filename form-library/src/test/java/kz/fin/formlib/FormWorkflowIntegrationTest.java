package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.repository.FormStatusEventRepository;
import kz.fin.formlib.service.FormDraftService;
import kz.fin.formlib.service.FormWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FormWorkflowIntegrationTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired FormStatusEventRepository eventRepo;
 private String json(Object v)throws Exception{return mapper.writeValueAsString(v);}
 private JsonNode ok(RequestBuilder b)throws Exception{
   return mapper.readTree(mvc.perform(b).andExpect(status().isOk())
     .andReturn().getResponse().getContentAsString());
 }
 private FormDraftService.DraftRequest draft(UUID id,UUID org,UUID project,int expected,String amount){
   return new FormDraftService.DraftRequest(id,org,UUID.fromString("00000000-0000-0000-0000-000000000002"),
     UUID.fromString("00000000-0000-0000-0000-000000000003"),project,
     "F02","M","2026-04","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",expected,
     Map.of("netRevenue",amount,"cogs","200","operatingExpenses","100"),
     List.of(),"test-editor","local workflow test");
 }
 private JsonNode draftSave(FormDraftService.DraftRequest r,String key)throws Exception{
   return ok(post("/api/v1/drafts").header("Idempotency-Key",key)
     .contentType(MediaType.APPLICATION_JSON).content(json(r)));
 }
 private String path(UUID id){return "/api/v1/drafts/"+id;}
 private JsonNode viewStatus(UUID id,UUID org)throws Exception{
   return ok(get(path(id)+"/status").param("organizationId",org.toString()));
 }
 private JsonNode transition(UUID id,UUID org,int revision,long version,String target)throws Exception{
   var request=new FormWorkflowService.TransitionRequest(org,revision,version,target,
     "local-operator","manual state change");
   return ok(post(path(id)+"/status-transitions").contentType(MediaType.APPLICATION_JSON)
     .content(json(request)));
 }

 @Test void afterEditBecomesReadyThenReopenedWithoutChangingExistingFinancialSha()throws Exception{
   UUID org=UUID.randomUUID(),project=UUID.randomUUID();
   JsonNode first=draftSave(draft(null,org,project,0,"1000"),"new-"+org);
   UUID id=UUID.fromString(first.get("instanceId").asText());
   String contentSha=first.get("contentSha256").asText();
   assertEquals(0,viewStatus(id,org).get("statusVersion").asInt());
   assertEquals("DRAFT",viewStatus(id,org).get("status").asText());
   assertTrue(viewStatus(id,org).get("editable").asBoolean());
   JsonNode ready=transition(id,org,1,0,"READY_FOR_REVIEW");
   assertEquals("READY_FOR_REVIEW",ready.get("status").asText());
   assertEquals(1,ready.get("statusVersion").asInt());
   assertFalse(ready.get("editable").asBoolean());
   assertEquals(contentSha,ok(get(path(id)).param("organizationId",org.toString()))
     .get("sha256").asText());
   mvc.perform(post("/api/v1/drafts").header("Idempotency-Key","locked-"+org)
     .contentType(MediaType.APPLICATION_JSON)
     .content(json(draft(id,org,project,1,"1200")))).andExpect(status().isConflict());
   assertEquals(1,viewStatus(id,org).get("revisionNo").asInt());
   JsonNode reopened=transition(id,org,1,1,"DRAFT");
   assertTrue(reopened.get("editable").asBoolean());
   assertEquals(2,reopened.get("statusVersion").asInt());
   JsonNode edited=draftSave(draft(id,org,project,1,"1200"),"newer-"+org);
   assertEquals(2,edited.get("revisionNo").asInt());
   assertEquals("DRAFT",viewStatus(id,org).get("status").asText());
   assertEquals(2,viewStatus(id,org).get("statusVersion").asInt());
   assertEquals(contentSha,ok(get(path(id)+"/revisions/1")
      .param("organizationId",org.toString())).get("sha256").asText());
   assertEquals("1000",ok(get(path(id)+"/revisions/1")
      .param("organizationId",org.toString())).get("values").get("netRevenue").asText());
   assertEquals("1200",ok(get(path(id))
      .param("organizationId",org.toString())).get("values").get("netRevenue").asText());
   JsonNode events=ok(get(path(id)+"/status/events")
      .param("organizationId",org.toString()));
   assertEquals(2,events.size());
   assertEquals("DRAFT",events.get(0).get("fromStatus").asText());
   assertEquals("READY_FOR_REVIEW",events.get(0).get("toStatus").asText());
   assertEquals("DRAFT",events.get(1).get("toStatus").asText());
   assertEquals(2,eventRepo.findByInstanceIdOrderByStatusVersionAsc(id).size());
 }

 @Test void staleCrossOrganizationAndInvalidTransitionsFailClosed()throws Exception{
   UUID org=UUID.randomUUID(),project=UUID.randomUUID(),wrongOrg=UUID.randomUUID();
   JsonNode created=draftSave(draft(null,org,project,0,"500"),"test-"+org);
   UUID id=UUID.fromString(created.get("instanceId").asText());
   mvc.perform(get(path(id)+"/status").param("organizationId",wrongOrg.toString()))
     .andExpect(status().isNotFound());
   mvc.perform(get(path(id)+"/status/events").param("organizationId",wrongOrg.toString()))
     .andExpect(status().isNotFound());
   mvc.perform(post(path(id)+"/status-transitions")
     .contentType(MediaType.APPLICATION_JSON)
     .content(json(new FormWorkflowService.TransitionRequest(wrongOrg,1,0,
       "READY_FOR_REVIEW","tester","wrong owner")))).andExpect(status().isNotFound());
   mvc.perform(post(path(id)+"/status-transitions")
     .contentType(MediaType.APPLICATION_JSON)
     .content(json(new FormWorkflowService.TransitionRequest(org,0,0,
       "READY_FOR_REVIEW","tester","stale data")))).andExpect(status().isConflict());
   mvc.perform(post(path(id)+"/status-transitions")
     .contentType(MediaType.APPLICATION_JSON)
     .content(json(new FormWorkflowService.TransitionRequest(org,1,0,
       "APPROVED","tester","untrusted approval")))).andExpect(status().isConflict());
   mvc.perform(post(path(id)+"/status-transitions")
     .contentType(MediaType.APPLICATION_JSON)
     .content(json(new FormWorkflowService.TransitionRequest(org,1,0,
       "READY_FOR_REVIEW"," ","missing actor")))).andExpect(status().isUnprocessableEntity());
   transition(id,org,1,0,"READY_FOR_REVIEW");
   mvc.perform(post(path(id)+"/status-transitions")
     .contentType(MediaType.APPLICATION_JSON)
     .content(json(new FormWorkflowService.TransitionRequest(org,1,0,
       "DRAFT","tester","stale version")))).andExpect(status().isConflict());
   mvc.perform(post(path(id)+"/status-transitions")
     .contentType(MediaType.APPLICATION_JSON)
     .content(json(new FormWorkflowService.TransitionRequest(org,1,1,
       "READY_FOR_REVIEW","tester","duplicate")))).andExpect(status().isConflict());
   assertEquals(1,eventRepo.findByInstanceIdOrderByStatusVersionAsc(id).size());
   assertEquals("READY_FOR_REVIEW",viewStatus(id,org).get("status").asText());
 }
}
