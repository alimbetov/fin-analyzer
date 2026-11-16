package kz.fin.backend.intake;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIN-BE-R1: 166 independent dynamic PostgreSQL tests.
 * Each code is actually generated, opened by POI, filled, parsed,
 * stored as an immutable unqualified draft, read back and tamper-rejected.
 * These tests do NOT approve financial methodology or ACTUAL facts.
 */
@SpringBootTest
@Testcontainers
class CatalogXlsxRoundTripIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired CatalogXlsxRoundTripService xlsx;
    @Autowired CatalogXlsxRevisionService revisionService;
    @Autowired CatalogIssuedTemplateService issuedService;
    @Autowired FinancialReportSnapshotService reportSnapshotService;
    @Autowired CatalogXlsxExportService xlsxExportService;
    @Autowired FormDraftRevisionV5Service projectService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private static final Map<String, Map<String,Object>> EVIDENCE = new ConcurrentHashMap<>();
    private static final Map<String, Map<String,Object>> X2_EXPORT_EVIDENCE = new ConcurrentHashMap<>();

    private static CatalogXlsxRoundTripService.Context context(String code) {
        return new CatalogXlsxRoundTripService.Context(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PROJECT_R1",
                code, "MANAGEMENT", ReportingPeriodV5.Type.MONTH, "2026.10",
                ReportingPeriodV5.Coverage.PERIOD_ONLY, "KZT", 1, "ru");
    }

    private static FinancialReportSnapshotService.Query snapshotQuery(
            CatalogXlsxRoundTripService.Context context, UUID projectId,
            Integer scale, UUID exactRevisionId, UUID expectedLatestRevisionId) {
        return new FinancialReportSnapshotService.Query(
                context.organizationId(), projectId, context.formCode(),
                context.periodType(), context.periodCode(), context.reportingBasis(),
                context.coverage(), context.currency(), scale,
                exactRevisionId, expectedLatestRevisionId);
    }

    private static String value(CatalogCaptureRegistry.Field field) {
        if ("attachmentRef".equals(field.code())) return "evidence.pdf";
        return switch (field.type()) {
            case REF -> "REF001";
            case CODE -> "CATEGORY_A";
            case DATE -> "2026-10-31";
            case MONEY, DECIMAL -> "0.0000";
            case PERIOD -> "2026.10";
            case ENUM -> "DRAFT";
            case INTEGER -> "0002";
            case TEXT -> "Synthetic evidence only";
            case BOOLEAN -> "false";
        };
    }

    /**
     * Independent value oracle: literals below are reviewed expected type
     * representations, NOT computed through CatalogCaptureRegistry.canonical().
     * Schema candidates preserve neutral numeric values because their
     * preliminary balancing rules are not methodologically qualified.
     */
    private static String sample(String code, CatalogCaptureRegistry.Field field, int row) {
        if (CatalogCaptureRegistry.require(code).captureMode() !=
                CatalogCaptureRegistry.Mode.TYPED_DRAFT) return value(field);
        return switch (field.type()) {
            case MONEY, DECIMAL -> row == 1
                    ? "12345678901234567890123456.123456789012" : "-105.2300";
            case INTEGER -> row == 1 ? "0002" : "-3";
            case DATE -> row == 1 ? "2026-10-31" : "2026-10-01";
            case BOOLEAN -> row == 1 ? "true" : "false";
            case PERIOD -> "2026.10";
            case REF -> "REF001";
            case CODE -> "CATEGORY_A";
            case ENUM -> "DRAFT";
            case TEXT -> "Synthetic evidence only";
        };
    }

    private static String independentExpected(String code, CatalogCaptureRegistry.Field field,
                                             int row) {
        if (CatalogCaptureRegistry.require(code).captureMode() !=
                CatalogCaptureRegistry.Mode.TYPED_DRAFT) {
            return switch (field.type()) {
                case MONEY, DECIMAL -> "0";
                case INTEGER -> "2";
                default -> value(field);
            };
        }
        return switch (field.type()) {
            case MONEY, DECIMAL -> row == 1
                    ? "12345678901234567890123456.123456789012" : "-105.23";
            case INTEGER -> row == 1 ? "2" : "-3";
            case DATE -> row == 1 ? "2026-10-31" : "2026-10-01";
            case BOOLEAN -> row == 1 ? "true" : "false";
            case PERIOD -> "2026.10";
            case REF -> "REF001";
            case CODE -> "CATEGORY_A";
            case ENUM -> "DRAFT";
            case TEXT -> "Synthetic evidence only";
        };
    }

    private static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static CatalogXlsxRoundTripService.OriginalDocument original() {
        return new CatalogXlsxRoundTripService.OriginalDocument(
                "evidence.pdf", "application/pdf", pdf());
    }

    private static byte[] edit(byte[] bytes,
                               java.util.function.Consumer<XSSFWorkbook> change) {
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes));
             var out = new ByteArrayOutputStream()) {
            change.accept(book);
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("TEST_XLSX_EDIT_FAILED", e);
        }
    }

    private static byte[] filled(String code, byte[] template) {
        var definition = CatalogCaptureRegistry.require(code);
        var fields = CatalogXlsxRoundTripService.fields(code);
        int rows = definition.captureMode() == CatalogCaptureRegistry.Mode.EVIDENCE_ONLY ? 1 : 2;
        return edit(template, book -> {
            var sheet = book.getSheet("FIN_Data");
            for (int i = 1; i <= rows; i++) {
                var row = sheet.getRow(i);
                if (row == null) row = sheet.createRow(i);
                row.getCell(0, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)
                        .setCellValue("ROW00" + i);
                for (int col = 0; col < fields.size(); col++) {
                    row.createCell(col + 1).setCellValue(sample(code, fields.get(col), i));
                }
            }
        });
    }

    @Test void serverRegistriesCoverExactly166CodesAnd794TypedSourceFields() {
        assertEquals(166, CatalogCaptureRegistry.all().size());
        assertEquals(166, CatalogCaptureRegistry.all().stream()
                .map(CatalogCaptureRegistry.Definition::code).distinct().count());
        assertEquals(122, CatalogCaptureRegistry.all().stream()
                .filter(f -> f.captureMode() == CatalogCaptureRegistry.Mode.TYPED_DRAFT).count());
        assertEquals(32, CatalogCaptureRegistry.all().stream()
                .filter(f -> f.captureMode() == CatalogCaptureRegistry.Mode.SCHEMA_NOT_APPROVED).count());
        assertEquals(12, CatalogCaptureRegistry.all().stream()
                .filter(f -> f.captureMode() == CatalogCaptureRegistry.Mode.EVIDENCE_ONLY).count());
        assertEquals(794, CatalogCaptureRegistry.all().stream()
                .filter(f -> f.captureMode() != CatalogCaptureRegistry.Mode.EVIDENCE_ONLY)
                .mapToInt(f -> CatalogXlsxRoundTripService.fields(f.code()).size()).sum());
        assertEquals(166, new HashSet<>(CatalogCaptureRegistry.all().stream()
                .map(f -> CatalogXlsxRoundTripService.schemaFingerprint(f.code())).toList()).size());
    }

    @TestFactory
    Stream<DynamicTest> all166HaveGeneratedXlsxAndActualPostgresRoundTrip() {
        return CatalogCaptureRegistry.all().stream().map(definition ->
                DynamicTest.dynamicTest("BE-R1-ALL-" + definition.code(), () -> {
                    var context = context(definition.code());
                    var fields = CatalogXlsxRoundTripService.fields(definition.code());
                    byte[] template = xlsx.generate(context);
                    assertTrue(template.length > 100);
                    try (var book = new XSSFWorkbook(new ByteArrayInputStream(template))) {
                        assertEquals(3, book.getNumberOfSheets());
                        var header = book.getSheet("FIN_Data").getRow(0);
                        assertEquals("ROW_ID", header.getCell(0).getStringCellValue());
                        assertEquals(fields.size() + 1, header.getLastCellNum());
                        for (int i = 0; i < fields.size(); i++) {
                            assertEquals(fields.get(i).code(),
                                    header.getCell(i + 1).getStringCellValue());
                        }
                    }
                    assertTrue(xlsx.parse(context, template).empty(),
                            "Empty template must not invent a financial zero");
                    byte[] populated = filled(definition.code(), template);
                    var parsed = xlsx.parse(context, populated);
                    int expectedRows = definition.captureMode() ==
                            CatalogCaptureRegistry.Mode.EVIDENCE_ONLY ? 1 : 2;
                    assertEquals(expectedRows, parsed.rows().size());
                    assertEquals(definition.captureMode().name(), parsed.captureMode());
                    assertEquals(CatalogXlsxRoundTripService.schemaFingerprint(
                            definition.code()), parsed.schemaFingerprint());
                    for (var row : parsed.rows()) {
                        assertEquals(fields.size(), row.cells().size());
                        for (var field : fields) {
                            int sourceRow = Integer.parseInt(row.rowId().substring(3));
                            assertEquals(independentExpected(definition.code(), field, sourceRow),
                                    row.cells().get(field.code()),
                                    definition.code() + "/" + row.rowId() + "/" + field.code());
                        }
                    }
                    boolean evidence = definition.captureMode() ==
                            CatalogCaptureRegistry.Mode.EVIDENCE_ONLY;
                    var original = evidence ? original() : null;
                    var saved = xlsx.saveDraft(context, populated, original);
                    var stored = xlsx.loadDraft(context.organizationId(), saved.id());
                    assertEquals(definition.code(), stored.formCode());
                    assertEquals(parsed.captureMode(), stored.captureMode());
                    assertEquals(parsed.schemaFingerprint(), stored.schemaFingerprint());
                    assertEquals(parsed.normalizedFingerprint(), stored.normalizedFingerprint());
                    assertEquals(saved.rawSha256(), stored.rawSha256());
                    assertEquals(json.valueToTree(parsed.rows()), stored.normalizedRows());
                    assertArrayEquals(populated, jdbc.queryForObject(
                            "SELECT original_xlsx FROM fin_intake.catalog_xlsx_draft WHERE id=?",
                            byte[].class, saved.id()));
                    assertEquals("UNQUALIFIED_DRAFT", jdbc.queryForObject(
                            "SELECT state FROM fin_intake.catalog_xlsx_draft WHERE id=?",
                            String.class, saved.id()));
                    if (evidence) {
                        assertNotNull(saved.documentSha256());
                        assertEquals(saved.documentSha256(), stored.documentSha256());
                        assertArrayEquals(pdf(), jdbc.queryForObject(
                                "SELECT document_original FROM fin_intake.catalog_xlsx_draft WHERE id=?",
                                byte[].class, saved.id()));
                        assertThrows(IllegalArgumentException.class,
                                () -> xlsx.saveDraft(context, populated, null));
                    } else {
                        assertNull(saved.documentSha256());
                        assertNull(stored.documentSha256());
                    }
                    assertThrows(IllegalArgumentException.class,
                            () -> xlsx.loadDraft(UUID.randomUUID(), saved.id()));

                    // Reuse the existing immutable V5 project, but keep catalog
                    // confirmation separate from F02 review and FACT publication.
                    var project = projectService.createProject(
                            context.organizationId(), context.entityId(),
                            context.businessScopeId(), context.projectCode(),
                            context.currency(), "catalog-author");
                    // The strict issued V2 path is the one that gets confirmed
                    // for each of the 166 codes; legacy V1 is read back only.
                    // The strictly server-issued V2 path is independently tested
                    // for every one of the 166 codes. The V1 compatibility path
                    // above remains for old test fixtures and legacy clients.
                    var issued = issuedService.issue(project.id(), context);
                    assertEquals(context.formCode(), issued.formCode());
                    assertEquals(context.periodCode(), issued.periodCode());
                    var issuedPopulated = filled(definition.code(), issued.workbook());
                    assertThrows(IllegalArgumentException.class,
                            () -> xlsx.parse(context, issuedPopulated));
                    var issuedPreview = issuedService.preview(context.organizationId(),
                            project.id(), issued.templateId(), issuedPopulated);
                    assertEquals(parsed.normalizedFingerprint(),
                            issuedPreview.parsed().normalizedFingerprint());
                    var issuedSaved = issuedService.upload(context.organizationId(),
                            project.id(), issued.templateId(), issuedPopulated, original);
                    assertEquals(issued.templateId(), jdbc.queryForObject("""
                            SELECT issued_template_id FROM fin_intake.catalog_xlsx_draft
                            WHERE id=?
                            """, UUID.class, issuedSaved.id()));
                    assertArrayEquals(issuedPopulated, jdbc.queryForObject("""
                            SELECT original_xlsx FROM fin_intake.catalog_xlsx_draft
                            WHERE id=?
                            """, byte[].class, issuedSaved.id()));

                    assertEquals("UNQUALIFIED_DRAFT", jdbc.queryForObject("""
                            SELECT state FROM fin_intake.catalog_xlsx_draft
                            WHERE id=?
                            """, String.class, issuedSaved.id()));
                    var confirmed = revisionService.confirm(
                            context.organizationId(), project.id(), issuedSaved.id(),
                            issuedPreview.parsed().normalizedFingerprint(),
                            0, "catalog-author", "");
                    assertEquals(1, confirmed.revisionNumber());
                    assertEquals(issuedSaved.id(), confirmed.draftId());
                    assertEquals(confirmed.revisionId(),
                            revisionService.current(context.organizationId(),
                                    project.id(), confirmed.slotId()).revisionId());
                    assertEquals(1, revisionService.history(context.organizationId(),
                            project.id(), confirmed.slotId()).size());
                    // X1: prove the SQL snapshot resolves the actual confirmed
                    // revision and the original canonical rows for all 166 codes.
                    var snapshot = reportSnapshotService.load(snapshotQuery(
                            context, project.id(), context.amountScale(), null, null));
                    assertEquals(confirmed.revisionId(), snapshot.sourceRevisionId());
                    assertEquals(confirmed.draftId(), snapshot.sourceDraftId());
                    assertEquals(confirmed.normalizedFingerprint(),
                            snapshot.normalizedFingerprint());
                    assertEquals(context.locale(), snapshot.sourceInputLocale());
                    assertEquals(context.entityId(), snapshot.economicEntityId());
                    assertEquals(context.businessScopeId(), snapshot.businessScopeId());
                    assertEquals(context.projectCode(), snapshot.projectCode());
                    assertEquals(context.currency(), snapshot.currency());
                    assertEquals(context.amountScale(), snapshot.amountScale());
                    assertEquals(parsed.rows().size(), snapshot.rows().size());
                    assertEquals(json.valueToTree(parsed.rows()),
                            json.valueToTree(snapshot.rows()));
                    assertEquals("CONFIRMED_UNQUALIFIED_DRAFT", snapshot.sourceStatus());
                    assertThrows(UnsupportedOperationException.class, () ->
                            snapshot.rows().getFirst().cells().put("injected", "123"));
                    for (var display : CatalogReportLocale.DISPLAY) {
                        var selection = new CatalogReportLocale.Selection(
                                snapshot.sourceInputLocale(), display);
                        assertEquals(context.locale(), selection.sourceInputLocale());
                    }

                    // X2: all four report display languages must produce a
                    // populated SERVER-issued V2 workbook from this exact
                    // confirmed Postgres revision, with no value drift.
                    for (String display : CatalogReportLocale.DISPLAY) {
                        var exported = xlsxExportService.export(snapshotQuery(
                                context, project.id(), context.amountScale(),
                                confirmed.revisionId(), null), display);
                        assertEquals(confirmed.revisionId(), exported.sourceRevisionId());
                        assertEquals(confirmed.draftId(), exported.sourceDraftId());
                        assertEquals(display, exported.displayLocale());
                        assertEquals(context.locale(), exported.sourceInputLocale());
                        assertEquals(context.currency(), exported.currency());
                        assertEquals(context.amountScale(), exported.amountScale());
                        assertEquals("CONFIRMED_UNQUALIFIED_DRAFT", exported.sourceState());
                        assertEquals(parsed.normalizedFingerprint(),
                                exported.normalizedFingerprint());
                        byte[] reportBytes = exported.workbook();
                        try (var report = new XSSFWorkbook(new ByteArrayInputStream(reportBytes))) {
                            assertEquals(3, report.getNumberOfSheets());
                            assertEquals(org.apache.poi.ss.usermodel.SheetVisibility.VERY_HIDDEN,
                                    report.getSheetVisibility(2));
                            assertEquals(context.locale(), report.getSheet("_FIN_Metadata")
                                    .getRow(14).getCell(1).getStringCellValue());
                            assertEquals(exported.issuedTemplateId().toString(),
                                    report.getSheet("_FIN_Metadata").getRow(16)
                                            .getCell(1).getStringCellValue());
                            assertTrue(report.getSheet("Instructions").getRow(7)
                                    .getCell(0).getStringCellValue().contains("KZT"));
                            assertTrue(report.getSheet("Instructions").getRow(8)
                                    .getCell(0).getStringCellValue()
                                    .contains(confirmed.revisionId().toString()));
                            assertTrue(report.getSheet("Instructions").getRow(10)
                                    .getCell(0).getStringCellValue().contains(display));
                            var data = report.getSheet("FIN_Data");
                            assertEquals(snapshot.rows().size(), data.getLastRowNum());
                            for (int line = 0; line < snapshot.rows().size(); line++) {
                                var source = snapshot.rows().get(line);
                                var outRow = data.getRow(line + 1);
                                assertEquals(source.rowId(),
                                        outRow.getCell(0).getStringCellValue());
                                for (int col = 0; col < fields.size(); col++) {
                                    var cell = outRow.getCell(col + 1);
                                    assertEquals(org.apache.poi.ss.usermodel.CellType.STRING,
                                            cell.getCellType());
                                    assertEquals("@", cell.getCellStyle().getDataFormatString());
                                    assertEquals(source.cells().get(fields.get(col).code()),
                                            cell.getStringCellValue());
                                }
                            }
                        }
                        var reimported = issuedService.preview(context.organizationId(),
                                project.id(), exported.issuedTemplateId(), reportBytes);
                        assertEquals(snapshot.rows(), reimported.parsed().rows());
                        assertEquals(snapshot.normalizedFingerprint(),
                                reimported.parsed().normalizedFingerprint());
                        String storedManifestSha = jdbc.queryForObject("""
                                SELECT original_template_sha
                                  FROM fin_intake.catalog_xlsx_issued_template
                                 WHERE id=? AND organization_id=? AND project_id=?
                                """, String.class, exported.issuedTemplateId(),
                                context.organizationId(), project.id());
                        assertEquals(exported.workbookSha256(), storedManifestSha);
                        var exportProof = new LinkedHashMap<String, Object>();
                        exportProof.put("formCode", definition.code());
                        exportProof.put("displayLocale", display);
                        exportProof.put("mode", definition.captureMode().name());
                        exportProof.put("sourceRevisionId", confirmed.revisionId().toString());
                        exportProof.put("rowCount", snapshot.rows().size());
                        exportProof.put("fieldCount", fields.size());
                        exportProof.put("testCaseId", "FIN-X2-" + definition.code() + "-" + display);
                        exportProof.put("issuedPopulated", true);
                        exportProof.put("strictV2Parsed", true);
                        exportProof.put("valuesEqual", true);
                        exportProof.put("sourceContextUnchanged", true);
                        exportProof.put("manifestDigestVerified", true);
                        exportProof.put("sourceStatus", "CONFIRMED_UNQUALIFIED_DRAFT");
                        exportProof.put("artifactSha256", exported.workbookSha256());
                        assertNull(X2_EXPORT_EVIDENCE.putIfAbsent(
                                definition.code() + "|" + display, Map.copyOf(exportProof)));
                    }

                    assertEquals("CONFIRMED_UNQUALIFIED_DRAFT",
                            revisionService.current(context.organizationId(),
                                    project.id(), confirmed.slotId()).state());
                    assertThrows(IllegalArgumentException.class,
                            () -> revisionService.confirm(context.organizationId(),
                                    project.id(), issuedSaved.id(),
                                    issuedPreview.parsed().normalizedFingerprint(),
                                    0, "catalog-author", "duplicate"));
                    assertTrue(revisionService.history(UUID.randomUUID(),
                            project.id(), confirmed.slotId()).isEmpty());

                    // Each of the 166 codes proves fail-closed workbook context
                    // and absence of partial persistence.
                    byte[] tampered = edit(populated, book -> book.getSheet("_FIN_Metadata")
                            .getRow(10).getCell(1).setCellValue("2026.09"));
                    assertThrows(IllegalArgumentException.class,
                            () -> xlsx.saveDraft(context, tampered, original));
                    assertEquals(2L, jdbc.queryForObject("""
                            SELECT count(*) FROM fin_intake.catalog_xlsx_draft
                            WHERE organization_id=? AND form_code=?
                            """, Long.class, context.organizationId(), definition.code()));
                    Map<String,Object> proof = new LinkedHashMap<>();
                    proof.put("formCode", definition.code());
                    proof.put("mode", definition.captureMode().name());
                    proof.put("fieldCount", fields.size());
                    proof.put("testCaseId", "BE-R1-ALL-" + definition.code());
                    proof.put("generated", true);
                    proof.put("poiOpened", true);
                    proof.put("filledRoundTrip", true);
                    proof.put("postgresPersisted", true);
                    proof.put("negativeContextRejected", true);
                    proof.put("revisionConfirmed", true);
                    proof.put("documentOriginalPersisted", evidence);
                    proof.put("issuedManifestVerified", true);
                    proof.put("independentFieldOracle", true);
                    proof.put("state", "PASSED_UNQUALIFIED_DRAFT");
                    EVIDENCE.put(definition.code(), Map.copyOf(proof));
                }));
    }

    @AfterAll
    static void writeX2ExportEvidence() throws Exception {
        assertEquals(166 * 4, X2_EXPORT_EVIDENCE.size(),
                "Every form and each of four display locales requires actual XLSX round-trip");
        var cases = new ArrayList<Map<String, Object>>();
        for (var def : CatalogCaptureRegistry.all()) {
            for (String locale : CatalogReportLocale.DISPLAY) {
                var row = X2_EXPORT_EVIDENCE.get(def.code() + "|" + locale);
                assertNotNull(row, "Missing X2 export " + def.code() + "/" + locale);
                cases.add(row);
            }
        }
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schemaVersion", "FIN_BE_R1_X2_ISSUED_XLSX_EXPORT_V1");
        manifest.put("gitSha", System.getenv().getOrDefault("GITHUB_SHA", "LOCAL_TEST"));
        manifest.put("formCount", 166);
        manifest.put("displayLocales", CatalogReportLocale.DISPLAY);
        manifest.put("format", "XLSX");
        manifest.put("casesPassed", cases.size());
        manifest.put("financialQualified", false);
        manifest.put("cases", cases);
        Path base = Path.of(System.getProperty("basedir",
                Files.exists(Path.of("backend/pom.xml")) ? "backend" : "."));
        Path dest = base.resolve("target/fin-evidence/fin-be-r1-x2-xlsx-export-coverage.json");
        Files.createDirectories(dest.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(dest.toFile(), manifest);
    }

    @AfterAll
    static void writeMandatoryCoverageEvidence() throws Exception {
        assertEquals(166, EVIDENCE.size(),
                "Every catalog form must have passed the full XLSX/PostgreSQL/confirm proof");
        var all = CatalogCaptureRegistry.all().stream()
                .map(form -> EVIDENCE.get(form.code())).toList();
        assertFalse(all.contains(null));
        int fields = all.stream().mapToInt(x -> (Integer)x.get("fieldCount")).sum();
        assertEquals(794 + 12 * CatalogXlsxRoundTripService.fields("D01").size(), fields);
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("schemaVersion", "FIN_BE_R1_166_POSTGRES_ROUNDTRIP_V1");
        evidence.put("gitSha", System.getenv().getOrDefault("GITHUB_SHA", "LOCAL_TEST"));
        evidence.put("expectedFormCount", 166);
        evidence.put("passedFormCount", EVIDENCE.size());
        evidence.put("typedFields", 794);
        evidence.put("catalogVersion", CatalogCaptureRegistry.VERSION);
        evidence.put("forms", all);
        Path basedir = Path.of(System.getProperty("basedir",
                Files.exists(Path.of("backend/pom.xml")) ? "backend" : "."));
        Path destination = basedir.resolve("target/fin-evidence/fin-be-r1-form-upload-coverage.json");
        Files.createDirectories(destination.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), evidence);
    }

    @Test
    void snapshotSelectsLatestConfirmedNotNewestDraftAndCanPinHistoricalRevision() {
        var c = context("DF-AST-01");
        var project = projectService.createProject(c.organizationId(), c.entityId(),
                c.businessScopeId(), c.projectCode(), c.currency(), "author");
        byte[] populated = filled(c.formCode(), xlsx.generate(c));
        var savedFirst = xlsx.saveDraft(c, populated, null);
        var first = revisionService.confirm(c.organizationId(), project.id(), savedFirst.id(),
                savedFirst.normalizedFingerprint(), 0, "author", "");
        var initial = reportSnapshotService.load(snapshotQuery(
                c, project.id(), c.amountScale(), null, null));
        assertEquals(first.revisionId(), initial.sourceRevisionId());

        byte[] updated = edit(populated, b -> b.getSheet("FIN_Data")
                .getRow(1).getCell(0).setCellValue("NEW-ROW"));
        var pending = xlsx.saveDraft(c, updated, null);
        assertEquals(first.revisionId(), reportSnapshotService.load(snapshotQuery(
                c, project.id(), c.amountScale(), null, null)).sourceRevisionId());
        var second = revisionService.confirm(c.organizationId(), project.id(), pending.id(),
                pending.normalizedFingerprint(), 1, "author", "revised row");
        var latest = reportSnapshotService.load(snapshotQuery(
                c, project.id(), c.amountScale(), null, null));
        assertEquals(second.revisionId(), latest.sourceRevisionId());
        assertEquals("NEW-ROW", latest.rows().getFirst().rowId());

        var historical = reportSnapshotService.load(snapshotQuery(
                c, project.id(), c.amountScale(), first.revisionId(), null));
        assertEquals(first.revisionId(), historical.sourceRevisionId());
        assertEquals(initial.normalizedFingerprint(), historical.normalizedFingerprint());
        assertNotEquals(latest.normalizedFingerprint(), historical.normalizedFingerprint());
        assertThrows(IllegalArgumentException.class, () ->
                reportSnapshotService.load(snapshotQuery(
                        c, project.id(), c.amountScale(), null, first.revisionId())));
        assertEquals(second.revisionId(), reportSnapshotService.load(snapshotQuery(
                c, project.id(), c.amountScale(), null, second.revisionId()))
                .sourceRevisionId());
        assertThrows(IllegalArgumentException.class, () ->
                reportSnapshotService.load(new FinancialReportSnapshotService.Query(
                        UUID.randomUUID(), project.id(), c.formCode(), c.periodType(),
                        c.periodCode(), c.reportingBasis(), c.coverage(),
                        c.currency(), c.amountScale(), first.revisionId(), null)));
        assertThrows(IllegalArgumentException.class, () ->
                reportSnapshotService.load(new FinancialReportSnapshotService.Query(
                        c.organizationId(), UUID.randomUUID(), c.formCode(), c.periodType(),
                        c.periodCode(), c.reportingBasis(), c.coverage(),
                        c.currency(), c.amountScale(), first.revisionId(), null)));
    }

    @Test
    void snapshotRejectsAmbiguousSlotsUnlessCoordinatesAreComplete() {
        var c = context("DF-AST-01");
        var project = projectService.createProject(c.organizationId(), c.entityId(),
                c.businessScopeId(), c.projectCode(), c.currency(), "author");
        for (int scale : List.of(1, 1000)) {
            var selected = new CatalogXlsxRoundTripService.Context(
                    c.organizationId(), c.entityId(), c.businessScopeId(),
                    c.projectCode(), c.formCode(), c.reportingBasis(), c.periodType(),
                    c.periodCode(), c.coverage(), c.currency(), scale, c.locale());
            var saved = xlsx.saveDraft(selected,
                    filled(selected.formCode(), xlsx.generate(selected)), null);
            revisionService.confirm(c.organizationId(), project.id(), saved.id(),
                    saved.normalizedFingerprint(), 0, "author", "");
        }
        assertThrows(IllegalArgumentException.class, () ->
                reportSnapshotService.load(snapshotQuery(c, project.id(), null, null, null)));
        assertEquals(1000, reportSnapshotService.load(snapshotQuery(
                c, project.id(), 1000, null, null)).amountScale());
        assertEquals(1, reportSnapshotService.load(snapshotQuery(
                c, project.id(), 1, null, null)).amountScale());
    }

    @Test
    void x2LatestHonorsHeadAndExactRevisionEvenWhenNewDraftExists() {
        var c = context("DF-AST-01");
        var project = projectService.createProject(c.organizationId(), c.entityId(),
                c.businessScopeId(), c.projectCode(), c.currency(), "author");
        byte[] initialWorkbook = filled(c.formCode(), xlsx.generate(c));
        var firstDraft = xlsx.saveDraft(c, initialWorkbook, null);
        var first = revisionService.confirm(c.organizationId(), project.id(), firstDraft.id(),
                firstDraft.normalizedFingerprint(), 0, "author", "");
        var unconfirmed = edit(initialWorkbook, book -> book.getSheet("FIN_Data")
                .getRow(1).getCell(0).setCellValue("SECOND-ROW"));
        var laterDraft = xlsx.saveDraft(c, unconfirmed, null);
        var query = snapshotQuery(c, project.id(), c.amountScale(), null, null);
        var latest = xlsxExportService.export(query, "zh-Hans");
        assertEquals(first.revisionId(), latest.sourceRevisionId());
        var second = revisionService.confirm(c.organizationId(), project.id(),
                laterDraft.id(), laterDraft.normalizedFingerprint(),
                1, "author", "Updated source row");
        assertEquals(second.revisionId(),
                xlsxExportService.export(query, "kk").sourceRevisionId());
        var old = xlsxExportService.export(snapshotQuery(
                c, project.id(), c.amountScale(), first.revisionId(), null), "en");
        assertEquals(first.revisionId(), old.sourceRevisionId());
        assertEquals(firstDraft.id(), old.sourceDraftId());
        assertThrows(IllegalArgumentException.class, () -> xlsxExportService.export(
                snapshotQuery(c, project.id(), c.amountScale(), null, first.revisionId()),
                "ru"));
        assertEquals(2L, jdbc.queryForObject("""
                SELECT count(*) FROM fin_intake.catalog_xlsx_revision
                 WHERE organization_id=?
                """, Long.class, c.organizationId()));
    }

    @Test
    void x2RejectsWrongTenantAndTamperingWithoutGeneratingSourceDrafts() {
        var c = context("DF-AST-01");
        var project = projectService.createProject(c.organizationId(), c.entityId(),
                c.businessScopeId(), c.projectCode(), c.currency(), "author");
        var draft = xlsx.saveDraft(c, filled(c.formCode(), xlsx.generate(c)), null);
        var confirmed = revisionService.confirm(c.organizationId(), project.id(), draft.id(),
                draft.normalizedFingerprint(), 0, "author", "");
        var exported = xlsxExportService.export(snapshotQuery(
                c, project.id(), c.amountScale(), null, null), "zh-Hans");
        assertThrows(IllegalArgumentException.class, () ->
                issuedService.preview(UUID.randomUUID(), project.id(),
                        exported.issuedTemplateId(), exported.workbook()));
        assertThrows(IllegalArgumentException.class, () ->
                issuedService.preview(c.organizationId(), UUID.randomUUID(),
                        exported.issuedTemplateId(), exported.workbook()));
        byte[] editedMetadata = edit(exported.workbook(),
                book -> book.getSheet("_FIN_Metadata").getRow(13)
                        .getCell(1).setCellValue("1000"));
        assertThrows(IllegalArgumentException.class, () ->
                issuedService.preview(c.organizationId(), project.id(),
                        exported.issuedTemplateId(), editedMetadata));
        byte[] injectedFormula = edit(exported.workbook(),
                book -> book.getSheet("FIN_Data").getRow(1)
                        .getCell(2).setCellFormula("1+1"));
        assertThrows(IllegalArgumentException.class, () ->
                issuedService.preview(c.organizationId(), project.id(),
                        exported.issuedTemplateId(), injectedFormula));
        assertThrows(IllegalArgumentException.class, () -> xlsxExportService.export(
                snapshotQuery(c, UUID.randomUUID(), c.amountScale(), null, null), "ru"));
        assertThrows(IllegalArgumentException.class, () -> xlsxExportService.export(
                snapshotQuery(c, project.id(), c.amountScale(), null, null), "zh-CN"));
        assertEquals(confirmed.revisionId(), reportSnapshotService.load(snapshotQuery(
                c, project.id(), c.amountScale(), null, null)).sourceRevisionId());
        assertEquals(1L, jdbc.queryForObject("""
                SELECT count(*) FROM fin_intake.catalog_xlsx_draft WHERE organization_id=?
                """, Long.class, c.organizationId()));
    }

    @Test void everyDraftIsImmutableAndInvalidOriginalCannotBeStored() {
        var context = context("D01");
        byte[] populated = filled("D01", xlsx.generate(context));
        assertThrows(IllegalArgumentException.class,
                () -> xlsx.saveDraft(context, populated,
                        new CatalogXlsxRoundTripService.OriginalDocument(
                                "evidence.pdf", "application/pdf",
                                "not-a-pdf-original".getBytes(StandardCharsets.US_ASCII))));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM fin_intake.catalog_xlsx_draft WHERE organization_id=?
                """, Long.class, context.organizationId()));
        var saved = xlsx.saveDraft(context, populated, original());
        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE fin_intake.catalog_xlsx_draft SET state=state WHERE id=?",
                        saved.id()));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("DELETE FROM fin_intake.catalog_xlsx_draft WHERE id=?",
                        saved.id()));
    }

    @Test void duplicateSourceRowAndHeaderMutationAreRejected() {
        var context = context("DF-AST-01");
        byte[] populated = filled("DF-AST-01", xlsx.generate(context));
        byte[] duplicate = edit(populated, b -> b.getSheet("FIN_Data")
                .getRow(2).getCell(0).setCellValue("ROW001"));
        assertThrows(IllegalArgumentException.class, () -> xlsx.parse(context, duplicate));
        byte[] changedField = edit(populated, b -> b.getSheet("FIN_Data")
                .getRow(0).getCell(1).setCellValue("unapprovedNewField"));
        assertThrows(IllegalArgumentException.class, () -> xlsx.parse(context, changedField));
        byte[] formula = edit(populated, b -> b.getSheet("FIN_Data")
                .getRow(1).getCell(1).setCellFormula("1+1"));
        assertThrows(IllegalArgumentException.class, () -> xlsx.parse(context, formula));
        byte[] sparse = edit(populated, b -> b.getSheet("FIN_Data")
                .createRow(900).createCell(1).setCellValue("surprise"));
        assertThrows(IllegalArgumentException.class, () -> xlsx.parse(context, sparse));
    }

    @Test void oldServerPilotIsStillASeparateVersionedContract() {
        var old = new kz.fin.backend.persistence.PilotXlsxTemplateService.Context(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "F02", "MANAGEMENT", "KZT", 1);
        var legacy = new kz.fin.backend.persistence.PilotXlsxTemplateService();
        byte[] oldTemplate = legacy.template(old);
        var context = context("F02");
        assertThrows(IllegalArgumentException.class, () -> xlsx.parse(context, oldTemplate));
        byte[] newTemplate = xlsx.generate(context);
        assertThrows(IllegalArgumentException.class, () -> legacy.parse(old, newTemplate));
    }

    @Test void confirmedDraftCreatesImmutableHistoryAndRejectsStaleRevision() {
        var context = context("DF-AST-01");
        var project = projectService.createProject(
                context.organizationId(), context.entityId(), context.businessScopeId(),
                context.projectCode(), context.currency(), "author");
        byte[] template = xlsx.generate(context);
        byte[] populated = filled("DF-AST-01", template);
        var firstDraft = xlsx.saveDraft(context, populated, null);
        var first = revisionService.confirm(context.organizationId(), project.id(),
                firstDraft.id(), firstDraft.normalizedFingerprint(), 0, "author", "");
        assertNull(first.parentRevisionId());

        byte[] updated = edit(populated, b -> b.getSheet("FIN_Data")
                .getRow(1).getCell(2).setCellValue("UPDATED_CODE"));
        var secondDraft = xlsx.saveDraft(context, updated, null);
        var second = revisionService.confirm(context.organizationId(), project.id(),
                secondDraft.id(), secondDraft.normalizedFingerprint(), 1,
                "author", "Corrected the recorded field");
        assertEquals(2, second.revisionNumber());
        assertEquals(first.slotId(), second.slotId());
        assertEquals(first.revisionId(), second.parentRevisionId());
        assertEquals(second.revisionId(), revisionService.current(
                context.organizationId(), project.id(), second.slotId()).revisionId());
        var history = revisionService.history(context.organizationId(),
                project.id(), second.slotId());
        assertEquals(2, history.size());
        assertEquals(first.revisionId(), history.getFirst().revisionId());
        assertEquals("CONFIRMED_UNQUALIFIED_DRAFT", history.getFirst().state());
        assertEquals(second.revisionId(), history.get(1).revisionId());
        assertThrows(IllegalArgumentException.class, () -> revisionService.confirm(
                context.organizationId(), project.id(), firstDraft.id(),
                firstDraft.normalizedFingerprint(), 0, "author", "stale"));
        assertThrows(IllegalArgumentException.class, () -> revisionService.confirm(
                context.organizationId(), project.id(), firstDraft.id(),
                "0".repeat(64), 2, "author", "wrong fingerprint"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM fin_intake.catalog_xlsx_revision WHERE id=?",
                first.revisionId()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE fin_intake.catalog_xlsx_revision SET state=state WHERE id=?",
                second.revisionId()));
    }

    @Test void twoConcurrentConfirmationsHaveExactlyOneWinner() throws Exception {
        var context = context("DF-AST-02");
        var project = projectService.createProject(
                context.organizationId(), context.entityId(), context.businessScopeId(),
                context.projectCode(), context.currency(), "author");
        byte[] populated = filled("DF-AST-02", xlsx.generate(context));
        var initial = xlsx.saveDraft(context, populated, null);
        var first = revisionService.confirm(context.organizationId(), project.id(),
                initial.id(), initial.normalizedFingerprint(), 0, "author", "");

        byte[] variantA = edit(populated, b -> b.getSheet("FIN_Data")
                .getRow(1).getCell(0).setCellValue("A-REV"));
        byte[] variantB = edit(populated, b -> b.getSheet("FIN_Data")
                .getRow(1).getCell(0).setCellValue("B-REV"));
        var draftA = xlsx.saveDraft(context, variantA, null);
        var draftB = xlsx.saveDraft(context, variantB, null);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futureA = executor.submit(() -> {
                start.await();
                try {
                    revisionService.confirm(context.organizationId(), project.id(),
                            draftA.id(), draftA.normalizedFingerprint(), 1,
                            "authorA", "new revision A");
                    return true;
                } catch (IllegalArgumentException conflict) {
                    return false;
                }
            });
            var futureB = executor.submit(() -> {
                start.await();
                try {
                    revisionService.confirm(context.organizationId(), project.id(),
                            draftB.id(), draftB.normalizedFingerprint(), 1,
                            "authorB", "new revision B");
                    return true;
                } catch (IllegalArgumentException conflict) {
                    return false;
                }
            });
            start.countDown();
            int successes = (futureA.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (futureB.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        }
        var history = revisionService.history(context.organizationId(),
                project.id(), first.slotId());
        assertEquals(2, history.size());
        assertEquals(2, revisionService.current(
                context.organizationId(), project.id(), first.slotId()).revisionNumber());
    }

    @Test void documentEvidenceSupportsSixOfflineFormatsAndRejectsMismatches() throws Exception {
        var context = context("D01");
        var candidate = new java.util.ArrayList<CatalogXlsxRoundTripService.OriginalDocument>();
        candidate.add(original());
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Synthetic document");
            doc.write(out);
            candidate.add(new CatalogXlsxRoundTripService.OriginalDocument(
                    "evidence.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    out.toByteArray()));
        }
        candidate.add(new CatalogXlsxRoundTripService.OriginalDocument(
                "evidence.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx.generate(context)));
        candidate.add(new CatalogXlsxRoundTripService.OriginalDocument(
                "evidence.xml", "application/xml",
                "<?xml version=\"1.0\"?><evidence>synthetic</evidence>"
                        .getBytes(StandardCharsets.UTF_8)));
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (var out = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(img, "png", out));
            candidate.add(new CatalogXlsxRoundTripService.OriginalDocument(
                    "evidence.png", "image/png", out.toByteArray()));
        }
        try (var out = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(img, "jpeg", out));
            candidate.add(new CatalogXlsxRoundTripService.OriginalDocument(
                    "evidence.jpg", "image/jpeg", out.toByteArray()));
        }
        byte[] template = xlsx.generate(context);
        for (var originalFile : candidate) {
            byte[] populated = edit(filled("D01", template), book ->
                    book.getSheet("FIN_Data").getRow(1).getCell(6)
                            .setCellValue(originalFile.fileName()));
            var saved = xlsx.saveDraft(context, populated, originalFile);
            assertNotNull(saved.documentSha256());
            assertEquals(originalFile.mediaType(), jdbc.queryForObject(
                    "SELECT document_media_type FROM fin_intake.catalog_xlsx_draft WHERE id=?",
                    String.class, saved.id()));
            assertArrayEquals(originalFile.bytes(), jdbc.queryForObject(
                    "SELECT document_original FROM fin_intake.catalog_xlsx_draft WHERE id=?",
                    byte[].class, saved.id()));
        }
        assertEquals(6L, jdbc.queryForObject("""
                SELECT count(*) FROM fin_intake.catalog_xlsx_draft
                WHERE organization_id=? AND capture_mode='EVIDENCE_ONLY'
                """, Long.class, context.organizationId()));

        byte[] pdfWorkbook = filled("D01", template);
        for (var bad : List.of(
                new CatalogXlsxRoundTripService.OriginalDocument(
                        "evidence.pdf", "application/pdf",
                        "NOT_PDF_MALFORMED___".getBytes(StandardCharsets.US_ASCII)),
                new CatalogXlsxRoundTripService.OriginalDocument(
                        "evidence.pdf", "application/pdf",
                        "%PDF-1.4\nmissing EOF\n".getBytes(StandardCharsets.US_ASCII)),
                new CatalogXlsxRoundTripService.OriginalDocument(
                        "evidence.pdf", "application/xml", pdf()),
                new CatalogXlsxRoundTripService.OriginalDocument(
                        "evidence.pdf", "application/pdf",
                        "<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]><x>&a;</x>"
                                .getBytes(StandardCharsets.US_ASCII)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> xlsx.saveDraft(context, pdfWorkbook, bad));
        }
    }

    @Test void issuedTemplateContextCannotBeBorrowedOrMutated() {
        var context = context("DF-AST-01");
        var project = projectService.createProject(context.organizationId(),
                context.entityId(), context.businessScopeId(),
                context.projectCode(), context.currency(), "author");
        var issued = issuedService.issue(project.id(), context);
        byte[] populated = filled(context.formCode(), issued.workbook());
        assertThrows(IllegalArgumentException.class,
                () -> issuedService.upload(UUID.randomUUID(), project.id(),
                        issued.templateId(), populated, null));
        assertThrows(IllegalArgumentException.class,
                () -> issuedService.upload(context.organizationId(), UUID.randomUUID(),
                        issued.templateId(), populated, null));
        assertThrows(IllegalArgumentException.class,
                () -> issuedService.upload(context.organizationId(), project.id(),
                        UUID.randomUUID(), populated, null));
        byte[] foreignId = edit(populated, book ->
                book.getSheet("_FIN_Metadata").getRow(16).getCell(1)
                        .setCellValue(UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class,
                () -> issuedService.upload(context.organizationId(), project.id(),
                        issued.templateId(), foreignId, null));
        byte[] foreignPeriod = edit(populated, book ->
                book.getSheet("_FIN_Metadata").getRow(10).getCell(1)
                        .setCellValue("2026.09"));
        assertThrows(IllegalArgumentException.class,
                () -> issuedService.upload(context.organizationId(), project.id(),
                        issued.templateId(), foreignPeriod, null));
        byte[] foreignSchema = edit(populated, book ->
                book.getSheet("_FIN_Metadata").getRow(3).getCell(1)
                        .setCellValue("0".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> issuedService.upload(context.organizationId(), project.id(),
                        issued.templateId(), foreignSchema, null));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM fin_intake.catalog_xlsx_draft
                WHERE organization_id=? AND issued_template_id=?
                """, Long.class, context.organizationId(), issued.templateId()));
        assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM fin_intake.catalog_xlsx_issued_template WHERE id=?",
                        issued.templateId()));

        var valid = issuedService.upload(context.organizationId(), project.id(),
                issued.templateId(), populated, null);
        assertNotNull(valid.id());
        assertEquals(1L, jdbc.queryForObject("""
                SELECT count(*) FROM fin_intake.catalog_xlsx_draft
                WHERE organization_id=? AND issued_template_id=?
                """, Long.class, context.organizationId(), issued.templateId()));
    }

    @Test void catalogSlotHeadCannotBypassRevisionChain() {
        var context = context("DF-AST-01");
        var project = projectService.createProject(context.organizationId(),
                context.entityId(), context.businessScopeId(), context.projectCode(),
                context.currency(), "author");
        var data = filled(context.formCode(), xlsx.generate(context));
        var receipt = xlsx.saveDraft(context, data, null);
        var confirmed = revisionService.confirm(context.organizationId(), project.id(),
                receipt.id(), receipt.normalizedFingerprint(), 0, "author", "");
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                UPDATE fin_intake.catalog_xlsx_slot SET latest_revision=latest_revision+1
                WHERE id=?
                """, confirmed.slotId()));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                UPDATE fin_intake.catalog_xlsx_slot SET latest_revision=latest_revision+2
                WHERE id=?
                """, confirmed.slotId()));
        assertEquals(1, revisionService.current(context.organizationId(),
                project.id(), confirmed.slotId()).revisionNumber());
    }

    @Test void repeatedStructuredDraftIsIdempotentButChangedSourceCreatesNewRevision() {
        var context = context("DF-AST-01");
        var project = projectService.createProject(context.organizationId(),
                context.entityId(), context.businessScopeId(), context.projectCode(),
                context.currency(), "author");
        byte[] workbook = filled("DF-AST-01", xlsx.generate(context));
        var saved = xlsx.saveDraft(context, workbook, null);
        var first = revisionService.confirm(context.organizationId(), project.id(),
                saved.id(), saved.normalizedFingerprint(), 0, "author", "");

        // Repeated upload retains its own raw source, but must not silently
        // advance the logical revision when the normalized contents match.
        var same = xlsx.saveDraft(context, workbook, null);
        var duplicate = revisionService.confirm(context.organizationId(), project.id(),
                same.id(), same.normalizedFingerprint(), 1, "author", "same source");
        assertEquals(first.revisionId(), duplicate.revisionId());
        assertEquals(first.draftId(), duplicate.draftId());
        assertEquals(1, duplicate.revisionNumber());
        assertEquals(1, revisionService.history(context.organizationId(),
                project.id(), first.slotId()).size());

        byte[] changedWorkbook = edit(workbook, b -> b.getSheet("FIN_Data")
                .getRow(1).getCell(0).setCellValue("NEW-ROW"));
        var changed = xlsx.saveDraft(context, changedWorkbook, null);
        var second = revisionService.confirm(context.organizationId(), project.id(),
                changed.id(), changed.normalizedFingerprint(), 1, "author",
                "changed the canonical row identity");
        assertEquals(2, second.revisionNumber());
        assertEquals(first.revisionId(), second.parentRevisionId());
        assertEquals(2, revisionService.history(context.organizationId(),
                project.id(), first.slotId()).size());
    }

    @Test void originalDocumentChecksumParticipatesInDuplicateDetection() {
        var context = context("D01");
        var project = projectService.createProject(context.organizationId(),
                context.entityId(), context.businessScopeId(), context.projectCode(),
                context.currency(), "author");
        byte[] register = filled("D01", xlsx.generate(context));
        var originalDraft = xlsx.saveDraft(context, register, original());
        var first = revisionService.confirm(context.organizationId(), project.id(),
                originalDraft.id(), originalDraft.normalizedFingerprint(), 0, "author", "");

        var repeated = xlsx.saveDraft(context, register, original());
        var same = revisionService.confirm(context.organizationId(), project.id(),
                repeated.id(), repeated.normalizedFingerprint(), 1, "author", "same PDF");
        assertEquals(first.revisionId(), same.revisionId());

        byte[] changedPdf = (
                "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 1 0 R >>\nendobj\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
        var changedOriginal = new CatalogXlsxRoundTripService.OriginalDocument(
                "evidence.pdf", "application/pdf", changedPdf);
        var changed = xlsx.saveDraft(context, register, changedOriginal);
        assertEquals(originalDraft.normalizedFingerprint(),
                changed.normalizedFingerprint(), "same register cells");
        assertNotEquals(originalDraft.documentSha256(), changed.documentSha256(),
                "changed original document is a new source");
        var revision = revisionService.confirm(context.organizationId(), project.id(),
                changed.id(), changed.normalizedFingerprint(), 1, "author",
                "original document replaced");
        assertEquals(2, revision.revisionNumber());
        assertEquals(first.revisionId(), revision.parentRevisionId());
        assertEquals(changed.id(), revision.draftId());
        assertEquals(2, revisionService.history(context.organizationId(),
                project.id(), first.slotId()).size());
    }
}
