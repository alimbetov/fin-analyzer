package kz.fin.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.intake.CatalogCaptureRegistry;
import kz.fin.backend.intake.CatalogXlsxRoundTripService;
import kz.fin.backend.intake.FormDraftRevisionV5Service;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "fin.security.enabled=true",
        "fin.security.issuer=https://iam.example/realms/fin",
        "fin.security.jwks-uri=https://iam.example/realms/fin/protocol/openid-connect/certs",
        "fin.security.audience=fin-analyzer-api"
})
@AutoConfigureMockMvc
@Testcontainers
class CatalogRevisionHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FormDraftRevisionV5Service projects;

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
    user(UUID org, String... permissions) {
        return jwt().jwt(j -> j.subject("fin-test-user").claim("fin_access",
                List.of(Map.of("organizationId", org.toString(),
                        "permissions", List.of(permissions)))));
    }

    private static String root(UUID org, UUID project, String form) {
        return "/api/v1/financial/organizations/" + org
                + "/projects/" + project + "/forms/" + form;
    }

    private static String sample(CatalogCaptureRegistry.FieldType type) {
        return switch (type) {
            case REF -> "REF001";
            case CODE -> "CATEGORY_A";
            case DATE -> "2026-10-31";
            case MONEY, DECIMAL -> "100.0000";
            case PERIOD -> "2026.10";
            case ENUM -> "DRAFT";
            case INTEGER -> "2";
            case TEXT -> "Unqualified test row";
            case BOOLEAN -> "false";
        };
    }

    private static byte[] populated(byte[] template, String form) throws Exception {
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(template));
             var out = new ByteArrayOutputStream()) {
            var row = book.getSheet("FIN_Data").getRow(1);
            row.getCell(0).setCellValue("ROW001");
            var fields = CatalogXlsxRoundTripService.fields(form);
            for (int i = 0; i < fields.size(); i++) {
                row.createCell(i + 1).setCellValue(sample(fields.get(i).type()));
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void securedHttpIssueUploadConfirmDuplicateAndHistoricalRead() throws Exception {
        UUID org = UUID.randomUUID(), entity = UUID.randomUUID(), scope = UUID.randomUUID();
        var project = projects.createProject(org, entity, scope,
                "HTTP_R1X6", "KZT", "fin-test-user");
        String form = "DF-AST-01";
        String base = root(org, project.id(), form);
        String request = """
                {"economicEntityId":"%s","businessScopeId":"%s",
                "projectCode":"HTTP_R1X6","reportingBasis":"MANAGEMENT",
                "periodType":"MONTH","periodCode":"2026.10","coverage":"PERIOD_ONLY",
                "currency":"KZT","amountScale":1,"inputLocale":"ru"}
                """.formatted(entity, scope);

        mvc.perform(post(base + "/templates").contentType(MediaType.APPLICATION_JSON)
                .content(request)).andExpect(status().isUnauthorized());
        mvc.perform(post(base + "/templates").with(user(UUID.randomUUID(), "FIN_SUBMIT"))
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        mvc.perform(post(base + "/templates").with(user(org, "FIN_READ"))
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());

        var issue = mvc.perform(post(base + "/templates").with(user(org, "FIN_SUBMIT"))
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-FIN-Issued-Template"))
                .andReturn().getResponse();
        UUID templateId = UUID.fromString(issue.getHeader("X-FIN-Issued-Template"));
        byte[] filled = populated(issue.getContentAsByteArray(), form);
        var attachment = new MockMultipartFile("file", "draft.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", filled);

        mvc.perform(multipart(base + "/uploads").file(attachment)
                .param("templateId", templateId.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart(base + "/uploads").file(attachment)
                .with(user(UUID.randomUUID(), "FIN_SUBMIT"))
                .param("templateId", templateId.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(root(org, project.id(), "DF-LIA-01") + "/uploads")
                .file(attachment).with(user(org, "FIN_SUBMIT"))
                .param("templateId", templateId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIN_CATALOG_ISSUED_FORM_MISMATCH"));

        var upload = mvc.perform(multipart(base + "/uploads")
                .file(attachment).with(user(org, "FIN_SUBMIT"))
                .param("templateId", templateId.toString()))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode receipt = json.readTree(upload.getContentAsString());
        String draftId = receipt.path("id").asText();
        String digest = receipt.path("normalizedFingerprint").asText();
        assertEquals(64, digest.length());

        String confirm = "{\"normalizedFingerprint\":\"" + digest
                + "\",\"expectedRevision\":0,\"changeReason\":\"\"}";
        var committed = mvc.perform(post(base + "/drafts/" + draftId + "/confirm")
                .with(user(org, "FIN_SUBMIT")).contentType(MediaType.APPLICATION_JSON)
                .content(confirm)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(1))
                .andReturn().getResponse();
        JsonNode revision = json.readTree(committed.getContentAsString());
        String slotId = revision.path("slotId").asText();
        String revisionId = revision.path("revisionId").asText();

        mvc.perform(get(base + "/slots/" + slotId + "/current")
                .with(user(org, "FIN_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value(revisionId));
        mvc.perform(get(base + "/slots/" + slotId + "/revisions")
                .with(user(org, "FIN_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(root(org, project.id(), "DF-LIA-01")
                + "/slots/" + slotId + "/revisions").with(user(org, "FIN_READ")))
                .andExpect(status().isNotFound());
        mvc.perform(get(base + "/slots/" + slotId + "/current")
                .with(user(UUID.randomUUID(), "FIN_READ")))
                .andExpect(status().isForbidden());

        // Duplicate content is not a new financial revision and needs no change reason.
        var again = mvc.perform(multipart(base + "/uploads").file(attachment)
                .with(user(org, "FIN_SUBMIT")).param("templateId", templateId.toString()))
                .andExpect(status().isOk()).andReturn().getResponse();
        var repeated = json.readTree(again.getContentAsString());
        String duplicateConfirm = "{\"normalizedFingerprint\":\"" + digest
                + "\",\"expectedRevision\":1,\"changeReason\":\"\"}";
        mvc.perform(post(base + "/drafts/" + repeated.path("id").asText() + "/confirm")
                .with(user(org, "FIN_SUBMIT")).contentType(MediaType.APPLICATION_JSON)
                .content(duplicateConfirm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(1));
        mvc.perform(get(base + "/slots/" + slotId + "/revisions")
                .with(user(org, "FIN_READ")))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
