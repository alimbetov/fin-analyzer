package kz.fin.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.intake.CatalogCaptureRegistry;
import kz.fin.backend.intake.CatalogXlsxRoundTripService;
import kz.fin.backend.intake.FormDraftRevisionV5Service;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import java.util.stream.Stream;
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

    private static String sample(CatalogCaptureRegistry.Field field) {
        if ("attachmentRef".equals(field.code())) return "evidence.pdf";
        var type = field.type();
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
                row.createCell(i + 1).setCellValue(sample(fields.get(i)));
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    @TestFactory
    Stream<DynamicTest> all166ExposeScopedIssuedV2TemplateAndReadOnlyPreview() {
        return CatalogCaptureRegistry.all().stream().map(form ->
                DynamicTest.dynamicTest("HTTP-CATALOG-" + form.code(), () -> {
                    UUID org = UUID.randomUUID(), entity = UUID.randomUUID(),
                            scope = UUID.randomUUID();
                    String projectCode = "X6_" + UUID.randomUUID().toString()
                            .replace("-", "").substring(0, 14);
                    var project = projects.createProject(org, entity, scope,
                            projectCode, "KZT", "fin-test-user");
                    String base = root(org, project.id(), form.code());
                    String request = """
                            {"economicEntityId":"%s","businessScopeId":"%s",
                            "projectCode":"%s","reportingBasis":"MANAGEMENT",
                            "periodType":"MONTH","periodCode":"2026.10",
                            "coverage":"PERIOD_ONLY","currency":"KZT",
                            "amountScale":1,"inputLocale":"ru"}
                            """.formatted(entity, scope, projectCode);
                    var result = mvc.perform(post(base + "/templates")
                            .with(user(org, "FIN_SUBMIT"))
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                            .andExpect(status().isOk())
                            .andExpect(header().exists("X-FIN-Issued-Template"))
                            .andReturn().getResponse();
                    UUID templateId = UUID.fromString(
                            result.getHeader("X-FIN-Issued-Template"));
                    byte[] workbook = result.getContentAsByteArray();
                    assertTrue(workbook.length > 100);
                    var file = new MockMultipartFile("file", "issued.xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            workbook);
                    mvc.perform(multipart(base + "/previews").file(file)
                            .with(user(org, "FIN_SUBMIT"))
                            .param("templateId", templateId.toString()))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.parsed.context.formCode")
                                    .value(form.code()))
                            .andExpect(jsonPath("$.parsed.schemaFingerprint").isNotEmpty());

                    // HTTP upload is exercised for each of the 166 modes.
                    byte[] filled = populated(workbook, form.code());
                    var filledFile = new MockMultipartFile("file", "filled.xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            filled);
                    var uploaded = multipart(base + "/uploads").file(filledFile);
                    uploaded.with(user(org, "FIN_SUBMIT"));
                    uploaded.param("templateId", templateId.toString());
                    if (form.captureMode() == CatalogCaptureRegistry.Mode.EVIDENCE_ONLY) {
                        byte[] document;
                        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument();
                             var out = new ByteArrayOutputStream()) {
                            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
                            pdf.save(out);
                            document = out.toByteArray();
                        }
                        uploaded.file(new MockMultipartFile("original", "evidence.pdf",
                                "application/pdf", document));
                    }
                    mvc.perform(uploaded)
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.formCode").value(form.code()))
                            .andExpect(jsonPath("$.normalizedFingerprint").isNotEmpty());
                }));
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
        mvc.perform(multipart(root(org, project.id(), "C01") + "/uploads")
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

        mvc.perform(get(base + "/revisions/" + revisionId + "/schema")
                .with(user(org, "FIN_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPATIBLE"))
                .andExpect(jsonPath("$.sourceFormat").value("FIN-BE-CATALOG-XLSX-V2"));
        mvc.perform(get(base + "/revisions/" + revisionId + "/schema")
                .with(user(UUID.randomUUID(), "FIN_READ")))
                .andExpect(status().isForbidden());
        mvc.perform(get(root(org, project.id(), "C01")
                + "/revisions/" + revisionId + "/schema")
                .with(user(org, "FIN_READ")))
                .andExpect(status().isNotFound());
        mvc.perform(post(base + "/drafts/" + draftId + "/confirm")
                .with(user(org, "FIN_SUBMIT")).contentType(MediaType.APPLICATION_JSON)
                .content(confirm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(1));

        mvc.perform(get(base + "/slots/" + slotId + "/current")
                .with(user(org, "FIN_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value(revisionId));
        mvc.perform(get(base + "/slots/" + slotId + "/revisions")
                .with(user(org, "FIN_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(root(org, project.id(), "C01")
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

        // A change must require a reason, append v2 and leave historical v1 intact.
        byte[] corrected;
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(filled));
             var output = new ByteArrayOutputStream()) {
            book.getSheet("FIN_Data").getRow(1).getCell(4).setCellValue("125.5000");
            book.write(output);
            corrected = output.toByteArray();
        }
        var correctedFile = new MockMultipartFile("file", "revised.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", corrected);
        var correctedUpload = mvc.perform(multipart(base + "/uploads").file(correctedFile)
                .with(user(org, "FIN_SUBMIT")).param("templateId", templateId.toString()))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode next = json.readTree(correctedUpload.getContentAsString());
        String nextDraft = next.path("id").asText();
        String nextHash = next.path("normalizedFingerprint").asText();
        String missingReason = """
                {"normalizedFingerprint":"%s","expectedRevision":1,"changeReason":""}
                """.formatted(nextHash);
        mvc.perform(post(base + "/drafts/" + nextDraft + "/confirm")
                .with(user(org, "FIN_SUBMIT")).contentType(MediaType.APPLICATION_JSON)
                .content(missingReason))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIN_CATALOG_CHANGE_REASON_REQUIRED"));
        String correctedConfirm = """
                {"normalizedFingerprint":"%s","expectedRevision":1,"changeReason":"Source correction"}
                """.formatted(nextHash);
        mvc.perform(post(base + "/drafts/" + nextDraft + "/confirm")
                .with(user(org, "FIN_SUBMIT")).contentType(MediaType.APPLICATION_JSON)
                .content(correctedConfirm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(2));
        mvc.perform(get(base + "/slots/" + slotId + "/revisions")
                .with(user(org, "FIN_READ")))
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(get(base + "/revisions/" + revisionId)
                .with(user(org, "FIN_READ"))
                .param("periodType", "MONTH").param("periodCode", "2026.10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNumber").value(1))
                .andExpect(jsonPath("$.rows[0].cells.cost").value("100"));
        mvc.perform(get(base + "/revisions/" + revisionId)
                .with(user(UUID.randomUUID(), "FIN_READ"))
                .param("periodType", "MONTH").param("periodCode", "2026.10"))
                .andExpect(status().isForbidden());
    }
}
