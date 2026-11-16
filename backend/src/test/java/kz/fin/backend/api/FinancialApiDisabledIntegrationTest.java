package kz.fin.backend.api;

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

import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FIN P0 production default: OAuth2 resource-server is NOT enabled by accident. */
@SpringBootTest(properties={"fin.security.enabled=false",
        "fin.diagnostics.actual-preview-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class FinancialApiDisabledIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired MockMvc mvc;

    @Test void noConfiguredKeycloakMeansEvenAnInjectedAuthenticatedJwtCannotUseApi()
            throws Exception {
        String base="/api/v1/financial/organizations/"+UUID.randomUUID();
        mvc.perform(get(base+"/submissions/"+UUID.randomUUID()))
                .andExpect(status().isForbidden());
        mvc.perform(get(base+"/submissions/"+UUID.randomUUID()).with(jwt()))
                .andExpect(status().isForbidden());
        mvc.perform(post(base+"/submissions").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(base+"/fact-sets/"+UUID.randomUUID()+"/assessments/2026-10")
                        .with(jwt()))
                .andExpect(status().isForbidden());
        // New V5 server revision endpoints must obey the same fail-closed IAM gate.
        String project=base+"/projects-v5/"+UUID.randomUUID();
        mvc.perform(post(base+"/projects-v5").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(project+"/forms/F02/previews").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(project+"/forms/F02/previews/"+UUID.randomUUID()+"/confirm").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(project+"/forms/F02/history?periodCode=2026.10").with(jwt()))
                .andExpect(status().isForbidden());
        mvc.perform(get(project+"/forms/F02/history/1?periodCode=2026.10"))
                .andExpect(status().isForbidden());
        mvc.perform(get(project+"/forms/F02/uploads?periodCode=2026.10").with(jwt()))
                .andExpect(status().isForbidden());
        mvc.perform(get(project+"/forms/F02/current?periodCode=2026.10").with(jwt()))
                .andExpect(status().isForbidden());
        mvc.perform(get(project+"/forms/F02/previews/"+UUID.randomUUID()+"/freshness").with(jwt()))
                .andExpect(status().isForbidden());
    }
}
