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
    @Autowired kz.fin.backend.intake.FormDraftRevisionV5Service x2Projects;
    @Autowired kz.fin.backend.intake.CatalogXlsxRoundTripService x2SourceXlsx;
    @Autowired kz.fin.backend.intake.CatalogXlsxRevisionService x2Revisions;
    @Autowired kz.fin.backend.intake.CatalogIssuedTemplateService x2Issued;
    @Autowired org.springframework.jdbc.core.JdbcTemplate x2Jdbc;
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

    @Test
    void x2HttpRequiresTenantScopedReadAndSubmitAndExportsRealLatestWorkbook()
            throws Exception {
        var c = new kz.fin.backend.intake.CatalogXlsxRoundTripService.Context(
                org, entity, scope, "X2_HTTP", "DF-AST-01", "MANAGEMENT",
                kz.fin.backend.intake.ReportingPeriodV5.Type.MONTH, "2026.10",
                kz.fin.backend.intake.ReportingPeriodV5.Coverage.PERIOD_ONLY,
                "KZT", 1, "ru");
        var project = x2Projects.createProject(org, entity, scope,
                c.projectCode(), c.currency(), "test-author");
        byte[] workbook = x2SourceXlsx.generate(c);
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(workbook));
             var out = new java.io.ByteArrayOutputStream()) {
            var row = book.getSheet("FIN_Data").getRow(1);
            row.getCell(0).setCellValue("ROW001");
            row.createCell(1).setCellValue("ASSET001");
            row.createCell(2).setCellValue("CATEGORY_A");
            row.createCell(3).setCellValue("2026-10-31");
            row.createCell(4).setCellValue("12345678901234567890123456.123456789012");
            row.createCell(5).setCellValue("210.0000");
            row.createCell(6).setCellValue("1.0000");
            book.write(out);
            workbook = out.toByteArray();
        }
        var saved = x2SourceXlsx.saveDraft(c, workbook, null);
        var confirmed = x2Revisions.confirm(
                org, project.id(), saved.id(), saved.normalizedFingerprint(),
                0, "test-author", "");
        String endpoint = "/api/v1/financial/organizations/" + org
                + "/projects/" + project.id() + "/forms/DF-AST-01/exports";
        String previewPath = endpoint + "/preview";
        String parameters = "{\"periodType\":\"MONTH\",\"periodCode\":\"2026.10\","
                + "\"reportingBasis\":\"MANAGEMENT\",\"coverage\":\"PERIOD_ONLY\","
                + "\"currency\":\"KZT\",\"amountScale\":1,"
                + "\"displayLocale\":\"zh-Hans\","
                + "\"sourceRevisionId\":\"" + confirmed.revisionId() + "\"}";

        mvc.perform(get(previewPath)
                .param("periodType", "MONTH").param("periodCode", "2026.10")
                .param("reportingBasis", "MANAGEMENT").param("coverage", "PERIOD_ONLY")
                .param("currency", "KZT").param("amountScale", "1")
                .param("displayLocale", "zh-Hans"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(previewPath).with(user("viewer", UUID.randomUUID(), "FIN_READ"))
                .param("periodType", "MONTH").param("periodCode", "2026.10"))
                .andExpect(status().isForbidden());
        mvc.perform(get(previewPath).with(user("viewer", org, "FIN_READ"))
                .param("periodType", "MONTH").param("periodCode", "2026.10")
                .param("displayLocale", "zh-Hans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceRevisionId").value(
                        confirmed.revisionId().toString()))
                .andExpect(jsonPath("$.sourceInputLocale").value("ru"))
                .andExpect(jsonPath("$.displayLocale").value("zh-Hans"))
                .andExpect(jsonPath("$.sourceStatus").value("CONFIRMED_UNQUALIFIED_DRAFT"))
                .andExpect(jsonPath("$.provisionalLabels").value(6))
                .andExpect(header().string("Cache-Control", "no-store"));

        mvc.perform(post(endpoint + "/latest.xlsx").contentType(MediaType.APPLICATION_JSON)
                .content(parameters)).andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint + "/latest.xlsx")
                .with(user("foreign", UUID.randomUUID(), "FIN_READ", "FIN_SUBMIT"))
                .contentType(MediaType.APPLICATION_JSON).content(parameters))
                .andExpect(status().isForbidden());
        mvc.perform(post(endpoint + "/latest.xlsx")
                .with(user("viewer", org, "FIN_READ"))
                .contentType(MediaType.APPLICATION_JSON).content(parameters))
                .andExpect(status().isForbidden());
        mvc.perform(post(endpoint + "/latest.xlsx")
                .with(user("author", org, "FIN_SUBMIT"))
                .contentType(MediaType.APPLICATION_JSON).content(parameters))
                .andExpect(status().isForbidden());

        var response = mvc.perform(post(endpoint + "/latest.xlsx")
                .with(user("exporter", org, "FIN_READ", "FIN_SUBMIT"))
                .contentType(MediaType.APPLICATION_JSON).content(parameters))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-FIN-Source-Revision",
                        confirmed.revisionId().toString()))
                .andExpect(header().exists("X-FIN-Issued-Template"))
                .andExpect(header().exists("X-FIN-Export-ID"))
                .andReturn().getResponse();
        UUID issuedId = UUID.fromString(response.getHeader("X-FIN-Issued-Template"));
        UUID exportId = UUID.fromString(response.getHeader("X-FIN-Export-ID"));
        byte[] bytes = response.getContentAsByteArray();
        assertTrue(bytes.length > 100);
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(bytes))) {
            assertEquals(3, book.getNumberOfSheets());
            assertEquals("ru", book.getSheet("_FIN_Metadata")
                    .getRow(14).getCell(1).getStringCellValue());
            assertEquals("12345678901234567890123456.123456789012",
                    book.getSheet("FIN_Data").getRow(1).getCell(4).getStringCellValue());
            assertEquals("@", book.getSheet("FIN_Data").getRow(1)
                    .getCell(4).getCellStyle().getDataFormatString());
            assertTrue(book.getSheet("Instructions").getRow(10).getCell(0)
                    .getStringCellValue().contains("zh-Hans"));
        }
        assertEquals("12345678901234567890123456.123456789012",
                x2Issued.preview(org, project.id(), issuedId, bytes).parsed()
                        .rows().getFirst().cells().get("cost"));
        assertEquals(confirmed.revisionId(), x2Jdbc.queryForObject("""
                SELECT source_revision_id
                FROM fin_intake.catalog_xlsx_export_receipt
                WHERE id=? AND generated_by='exporter' AND state='GENERATED'
                """, UUID.class, exportId));
        assertEquals(1L, x2Jdbc.queryForObject("""
                SELECT count(*) FROM fin_intake.catalog_xlsx_revision
                WHERE organization_id=?
                """, Long.class, org));

        mvc.perform(post(endpoint + "/latest.xlsx")
                .with(user("exporter", org, "FIN_READ", "FIN_SUBMIT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(parameters.replace("zh-Hans", "zh-CN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIN_REPORT_DISPLAY_LOCALE_UNSUPPORTED"));
        assertEquals(1L, x2Jdbc.queryForObject("""
                SELECT count(*) FROM fin_intake.catalog_xlsx_export_receipt
                WHERE project_id=?
                """, Long.class, project.id()));

        // X3/X4 must remain read-only FIN_READ operations using an exact,
        // already resolved sourceRevisionId. A separate latest query per
        // format would not guarantee cross-format source identity.
        for (String suffix : List.of(".html", ".pdf")) {
            String path = endpoint + "/latest" + suffix;
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(parameters)).andExpect(status().isUnauthorized());
            mvc.perform(post(path)
                    .with(user("other-org", UUID.randomUUID(), "FIN_READ"))
                    .contentType(MediaType.APPLICATION_JSON).content(parameters))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path)
                    .with(user("submit-only", org, "FIN_SUBMIT"))
                    .contentType(MediaType.APPLICATION_JSON).content(parameters))
                    .andExpect(status().isForbidden());
            String unpinned = parameters.replace(
                    ",\"sourceRevisionId\":\"" + confirmed.revisionId() + "\"", "");
            mvc.perform(post(path)
                    .with(user("viewer", org, "FIN_READ"))
                    .contentType(MediaType.APPLICATION_JSON).content(unpinned))
                    .andExpect(status().isBadRequest());
            var responseRead = mvc.perform(post(path)
                    .with(user("viewer", org, "FIN_READ"))
                    .contentType(MediaType.APPLICATION_JSON).content(parameters))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-FIN-Source-Revision",
                            confirmed.revisionId().toString()))
                    .andExpect(header().string("X-FIN-Source-State",
                            "CONFIRMED_UNQUALIFIED_DRAFT"))
                    .andReturn().getResponse();
            byte[] output = responseRead.getContentAsByteArray();
            assertTrue(output.length > 100);
            if (suffix.equals(".html")) {
                assertTrue(responseRead.getContentType().startsWith("text/html"));
                assertTrue(responseRead.getHeader("Content-Security-Policy")
                        .contains("default-src 'none'"));
                assertTrue(new String(output, java.nio.charset.StandardCharsets.UTF_8)
                        .contains(confirmed.revisionId().toString()));
            } else {
                assertEquals("application/pdf", responseRead.getContentType());
                try (var parsed = org.apache.pdfbox.Loader.loadPDF(output)) {
                    var extracted = new org.apache.pdfbox.text.PDFTextStripper().getText(parsed);
                    assertTrue(extracted.contains(confirmed.revisionId().toString()));
                    assertTrue(extracted.replaceAll("\\s+", "")
                            .contains("12345678901234567890123456.123456789012"));
                }
            }
        }
        assertEquals(1L, x2Jdbc.queryForObject("""
                SELECT count(*) FROM fin_intake.catalog_xlsx_export_receipt
                WHERE project_id=?
                """, Long.class, project.id()),
                "Read-only HTML/PDF must not issue XLSX or mutate export receipts");
    }

}
