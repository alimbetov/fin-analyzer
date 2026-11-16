package kz.fin.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Two independent JWT subjects in the SAME tenant read one shared revision chain.
 * Uses Spring Security test JWT (not a proof of real Keycloak onboarding).
 * Latest is a moving read model; a private Preview is immutable.
 */
@SpringBootTest(properties={
    "fin.security.enabled=true",
    "fin.security.issuer=https://iam.example/realms/fin",
    "fin.security.jwks-uri=https://iam.example/realms/fin/protocol/openid-connect/certs",
    "fin.security.audience=fin-analyzer-api"
})
@AutoConfigureMockMvc
@Testcontainers
class FormV5MultiUserRefreshIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
    user(String subject,UUID org,String... permissions){
        return jwt().jwt(token->token.subject(subject)
            .claim("fin_access",List.of(Map.of("organizationId",org.toString(),
                    "permissions",List.of(permissions)))));
    }

    private JsonNode postJson(String path,String body,String subject,UUID org,String... permissions)
        throws Exception {
        var response=mvc.perform(post(path).with(user(subject,org,permissions))
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString());
    }

    private static String input(String value){
        return """
          {"periodCode":"2026.10","inputLocale":"en","amountScale":1,
           "cells":[
             {"rowId":"pnl","fieldCode":"netRevenue","decimalText":"%s"},
             {"rowId":"pnl","fieldCode":"cogs","decimalText":"260"},
             {"rowId":"pnl","fieldCode":"operatingExpenses","decimalText":"90"}
           ]}
          """.formatted(value);
    }
    private static String confirm(JsonNode preview,String reason){
        return """
          {"confirmationDigest":"%s","expectedRevision":%d,"reason":"%s"}
          """.formatted(preview.path("confirmationDigest").asText(),
                preview.path("expectedRevision").asInt(),reason);
    }

    @Test void latestFollowsSavedRevisionsButDoesNotReplaceAnOpenPreview() throws Exception {
        UUID org=UUID.randomUUID(),otherOrg=UUID.randomUUID();
        String base="/api/v1/financial/organizations/"+org+"/projects-v5";
        var project=postJson(base, """
          {"economicEntityId":"%s","businessScopeId":"%s",
           "projectCode":"FIN_SHARED","accountingCurrency":"KZT"}
          """.formatted(UUID.randomUUID(),UUID.randomUUID()),"alice",org,"FIN_SUBMIT");
        UUID projectId=UUID.fromString(project.path("id").asText());
        String root=base+"/"+projectId+"/forms/F02";
        String current=root+"/current?periodCode=2026.10";

        // B can see organization-scoped latest, but not a private unconfirmed upload.
        mvc.perform(get(current).with(user("bob",org,"FIN_READ")))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.latestRevision").value(0))
            .andExpect(jsonPath("$.cells.length()").value(0));
        var initial=postJson(root+"/previews",input("400"),"alice",org,"FIN_SUBMIT");
        String alicePreviewId=initial.path("id").asText();
        mvc.perform(get(current).with(user("bob",org,"FIN_READ")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.latestRevision").value(0));
        mvc.perform(get(root+"/previews/"+alicePreviewId+"/freshness")
                .with(user("alice",org,"FIN_SUBMIT")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.stale").value(false));
        mvc.perform(get(root+"/previews/"+alicePreviewId+"/freshness")
                .with(user("bob",org,"FIN_READ")))
            .andExpect(status().isForbidden());

        postJson(root+"/previews/"+alicePreviewId+"/confirm",confirm(initial,""),
            "alice",org,"FIN_SUBMIT");
        mvc.perform(get(current).with(user("bob",org,"FIN_READ")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.latestRevision").value(1))
            .andExpect(jsonPath("$.savedBy").value("alice"))
            .andExpect(jsonPath("$.uploadedBy").value("alice"))
            .andExpect(jsonPath("$.uploadedAt").isNotEmpty())
            .andExpect(jsonPath("$.savedAt").isNotEmpty())
            .andExpect(jsonPath("$.cells[0].decimalText").value("400"));

        // Alice opens a new preview and leaves it open; Carol confirms first.
        var pending=postJson(root+"/previews",input("420"),"alice",org,"FIN_SUBMIT");
        var carol=postJson(root+"/previews",input("430"),"carol",org,"FIN_SUBMIT");
        postJson(root+"/previews/"+carol.path("id").asText()+"/confirm",
            confirm(carol,"Corrected accounting source"),"carol",org,"FIN_SUBMIT");
        mvc.perform(get(current).with(user("bob",org,"FIN_READ")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.latestRevision").value(2))
            .andExpect(jsonPath("$.savedBy").value("carol"))
            .andExpect(jsonPath("$.cells[0].decimalText").value("430"));
        mvc.perform(get(root+"/previews/"+pending.path("id").asText()+"/freshness")
                .with(user("alice",org,"FIN_SUBMIT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expectedRevision").value(1))
            .andExpect(jsonPath("$.currentRevision").value(2))
            .andExpect(jsonPath("$.stale").value(true))
            .andExpect(jsonPath("$.currentSavedBy").value("carol"))
            .andExpect(jsonPath("$.currentSavedAt").isNotEmpty());
        mvc.perform(post(root+"/previews/"+pending.path("id").asText()+"/confirm")
                .with(user("alice",org,"FIN_SUBMIT"))
                .contentType(MediaType.APPLICATION_JSON).content(confirm(pending,"My correction")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("FIN_V5_REVISION_CONFLICT"));

        // Alice's private preview values and owner are not leaked to Carol/Bob.
        mvc.perform(get(root+"/previews/"+pending.path("id").asText()+"/freshness")
                .with(user("carol",org,"FIN_SUBMIT")))
            .andExpect(status().isNotFound());
        mvc.perform(get(root+"/history?periodCode=2026.10")
                .with(user("bob",org,"FIN_READ")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].revisionNumber").value(2))
            .andExpect(jsonPath("$[0].savedBy").value("carol"))
            .andExpect(jsonPath("$[1].revisionNumber").value(1));
        // B cannot write, nor can a JWT restricted to another organization read.
        mvc.perform(post(root+"/previews").with(user("bob",org,"FIN_READ"))
                .contentType(MediaType.APPLICATION_JSON).content(input("500")))
            .andExpect(status().isForbidden());
        mvc.perform(get(current).with(user("mallory",otherOrg,"FIN_READ")))
            .andExpect(status().isForbidden());
        mvc.perform(get(current)).andExpect(status().isUnauthorized());
    }
}
