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
    @Autowired FormDraftRevisionV5Service projectService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private static final Map<String, Map<String,Object>> EVIDENCE = new ConcurrentHashMap<>();

    private static CatalogXlsxRoundTripService.Context context(String code) {
        return new CatalogXlsxRoundTripService.Context(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PROJECT_R1",
                code, "MANAGEMENT", ReportingPeriodV5.Type.MONTH, "2026.10",
                ReportingPeriodV5.Coverage.PERIOD_ONLY, "KZT", 1, "ru");
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
                    row.createCell(col + 1).setCellValue(value(fields.get(col)));
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
                            assertEquals(CatalogCaptureRegistry.canonical(
                                    field.type(), value(field), context.locale()),
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
                    var confirmed = revisionService.confirm(
                            context.organizationId(), project.id(), saved.id(),
                            parsed.normalizedFingerprint(), 0, "catalog-author", "");
                    assertEquals(1, confirmed.revisionNumber());
                    assertEquals(saved.id(), confirmed.draftId());
                    assertEquals(confirmed.revisionId(),
                            revisionService.current(context.organizationId(),
                                    project.id(), confirmed.slotId()).revisionId());
                    assertEquals(1, revisionService.history(context.organizationId(),
                            project.id(), confirmed.slotId()).size());
                    assertEquals("CONFIRMED_UNQUALIFIED_DRAFT",
                            revisionService.current(context.organizationId(),
                                    project.id(), confirmed.slotId()).state());
                    assertThrows(IllegalArgumentException.class,
                            () -> revisionService.confirm(context.organizationId(),
                                    project.id(), saved.id(), parsed.normalizedFingerprint(),
                                    0, "catalog-author", "duplicate"));
                    assertTrue(revisionService.history(UUID.randomUUID(),
                            project.id(), confirmed.slotId()).isEmpty());

                    // Each of the 166 codes proves fail-closed workbook context
                    // and absence of partial persistence.
                    byte[] tampered = edit(populated, book -> book.getSheet("_FIN_Metadata")
                            .getRow(10).getCell(1).setCellValue("2026.09"));
                    assertThrows(IllegalArgumentException.class,
                            () -> xlsx.saveDraft(context, tampered, original));
                    assertEquals(1L, jdbc.queryForObject("""
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
                    proof.put("state", "PASSED_UNQUALIFIED_DRAFT");
                    EVIDENCE.put(definition.code(), Map.copyOf(proof));
                }));
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

        var draftA = xlsx.saveDraft(context, populated, null);
        var draftB = xlsx.saveDraft(context, populated, null);
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
}
