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
import java.util.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
        "fin.security.enabled=true",
        "fin.security.issuer=https://iam.example/realms/fin",
        "fin.security.jwks-uri=https://iam.example/realms/fin/protocol/openid-connect/certs",
        "fin.security.audience=fin-analyzer-api"
})
@AutoConfigureMockMvc
@Testcontainers
class FinancialApiSecurityIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private final UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID();

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor user(
            String subject,UUID tenant,String... permissions){
        return jwt().jwt(jwt->jwt.subject(subject)
                .claim("fin_access",List.of(Map.of("organizationId",tenant.toString(),
                        "permissions",List.of(permissions)))));
    }
    private String payload(){
        return """
            {"economicEntityId":"%s","businessScopeId":"%s","formCode":"F02",
             "reportingBasis":"MANAGEMENT","currencyCode":"KZT","amountScale":1,
             "cells":[
               {"sourceRowId":"pnl","sourceField":"netRevenue","period":"2026-10","enteredAmount":400},
               {"sourceRowId":"pnl","sourceField":"cogs","period":"2026-10","enteredAmount":260},
               {"sourceRowId":"pnl","sourceField":"operatingExpenses","period":"2026-10","enteredAmount":90}
             ]}
            """.formatted(entity,scope);
    }
    private String endpoint(){return "/api/v1/financial/organizations/"+org+"/submissions";}

    @Test void noBearerOrWrongOrgOrWrongPermissionCannotSubmit() throws Exception{
        mvc.perform(post(endpoint()).contentType(MediaType.APPLICATION_JSON).content(payload()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint()).with(user("a",UUID.randomUUID(),"FIN_SUBMIT"))
                .contentType(MediaType.APPLICATION_JSON).content(payload()))
                .andExpect(status().isForbidden());
        mvc.perform(post(endpoint()).with(user("a",org,"FIN_READ"))
                .contentType(MediaType.APPLICATION_JSON).content(payload()))
                .andExpect(status().isForbidden());
    }
    @Test void skuUploadReviewAndOriginalDownloadDemandSeparateOrgScopedJwtGrants()
            throws Exception {
        String skuPath="/api/v1/financial/organizations/"+org+"/sku-populations";
        var fixture=new org.springframework.mock.web.MockMultipartFile(
                "file","sku-ledger.json","application/json",
                "{\"period\":\"2026-02\",\"currency\":\"KZT\",\"events\":[]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart(skuPath).file(fixture)
                .param("economicEntityId",entity.toString())
                .param("businessScopeId",scope.toString())
                .param("factSetRevisionId",UUID.randomUUID().toString())
                .param("sourceSystem","TEST_ERP"))
            .andExpect(status().isUnauthorized());
        mvc.perform(multipart(skuPath).file(fixture)
                .with(user("unrelated",UUID.randomUUID(),"FIN_SUBMIT"))
                .param("economicEntityId",entity.toString())
                .param("businessScopeId",scope.toString())
                .param("factSetRevisionId",UUID.randomUUID().toString())
                .param("sourceSystem","TEST_ERP"))
            .andExpect(status().isForbidden());
        mvc.perform(multipart(skuPath).file(fixture)
                .with(user("viewer",org,"FIN_READ"))
                .param("economicEntityId",entity.toString())
                .param("businessScopeId",scope.toString())
                .param("factSetRevisionId",UUID.randomUUID().toString())
                .param("sourceSystem","TEST_ERP"))
            .andExpect(status().isForbidden());
        UUID population=UUID.randomUUID();
        mvc.perform(post(skuPath+"/"+population+"/review")
                .with(user("author",org,"FIN_SUBMIT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approved\":true,\"reason\":\"unauthorized\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get(skuPath+"/"+population+"/original")
                .with(user("reviewer",org,"FIN_REVIEW")))
            .andExpect(status().isForbidden());
        mvc.perform(get(skuPath+"/"+population+"/original")
                .with(user("viewer",UUID.randomUUID(),"FIN_READ")))
            .andExpect(status().isForbidden());
    }

    @Test void onlyDistinctReviewerCanApproveAndPublisherNeedsSeparateRole() throws Exception {
        String content=mvc.perform(post(endpoint()).with(user("author",org,"FIN_SUBMIT"))
                        .contentType(MediaType.APPLICATION_JSON).content(payload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING_INDEPENDENT_REVIEW"))
                .andReturn().getResponse().getContentAsString();
        JsonNode submitted=json.readTree(content);
        String id=submitted.path("id").asText();
        assertFalse(id.isBlank());
        String reviewPath=endpoint()+"/"+id+"/review";
        mvc.perform(post(reviewPath).with(user("author",org,"FIN_REVIEW"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approved\":true,\"reason\":\"Self approved\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INTAKE_SELF_REVIEW_FORBIDDEN"));
        mvc.perform(post(reviewPath).with(user("author",org,"FIN_SUBMIT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approved\":true,\"reason\":\"Forbidden\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(reviewPath).with(user("reviewer",org,"FIN_REVIEW"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approved\":true,\"reason\":\"Independently reviewed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVED"));
        mvc.perform(get(endpoint()+"/"+id).with(user("viewer",org,"FIN_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.cells.length()").value(3));
        mvc.perform(get(endpoint()+"/"+id).with(user("viewer",UUID.randomUUID(),"FIN_READ")))
                .andExpect(status().isForbidden());
        String publish="/api/v1/financial/organizations/"+org+"/fact-sets";
        String body="{\"economicEntityId\":\""+entity+"\",\"businessScopeId\":\""+scope+
                "\",\"reportingBasis\":\"MANAGEMENT\",\"approvedSourceIds\":[\""+id+"\"]}";
        mvc.perform(post(publish).with(user("reviewer",org,"FIN_REVIEW"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(publish).with(user("publisher",org,"FIN_PUBLISH"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotHash").isNotEmpty());
    }
}
