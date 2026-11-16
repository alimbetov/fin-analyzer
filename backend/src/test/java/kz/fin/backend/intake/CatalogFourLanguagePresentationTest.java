package kz.fin.backend.intake;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * X0: 166 actual form schemas x 4 display locales; no fabricated approvals.
 * Chinese rendering is independent of the original tri-language XLSX intake.
 */
class CatalogFourLanguagePresentationTest {
    private static final CatalogReportPresentationService presentation =
            new CatalogReportPresentationService();
    private static final Map<String, Map<String, Object>> PROVEN_CASES =
            new ConcurrentHashMap<>();

    private static CatalogXlsxRoundTripService.Context context(String formCode,
                                                                 String inputLocale) {
        return new CatalogXlsxRoundTripService.Context(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "X0_PROJECT", formCode, "MANAGEMENT",
                ReportingPeriodV5.Type.MONTH, "2026.10",
                ReportingPeriodV5.Coverage.PERIOD_ONLY, "KZT", 1000, inputLocale);
    }

    /** Independent view of the exact on-disk TSV slots; not derived from the
     * implementation's resolved labels, statuses, or coverage calculations. */
    private static Map<String, String[]> rawInventory() {
        var input = CatalogFourLanguagePresentationTest.class.getResourceAsStream(
                "/fin/form-field-presentation-review-r6.tsv");
        assertNotNull(input);
        var result = new HashMap<String, String[]>();
        try (var reader = new BufferedReader(new InputStreamReader(input,
                StandardCharsets.UTF_8))) {
            assertEquals("formCode\tfieldCode\tfieldType\tlabelRu\tlabelKk\tlabelEn"
                    + "\tlabelZhHans\treviewStatus\trequiredness\tunitPolicy",
                    reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split("\t", -1);
                assertEquals(10, values.length);
                assertNull(result.put(values[0] + ":" + values[1], values));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("FIN_X0_TEST_INVENTORY_READ", e);
        }
        assertEquals(794, result.size());
        return Map.copyOf(result);
    }

    @TestFactory
    Stream<DynamicTest> actualCatalogFormsHaveFourCorrectlyStatusedDisplayViews() {
        var raw = rawInventory();
        return CatalogCaptureRegistry.all().stream().flatMap(form ->
                CatalogReportLocale.DISPLAY.stream().map(language ->
                        DynamicTest.dynamicTest("FIN-X0-" + form.code() + "-" + language, () -> {
                            var source = context(form.code(), "ru");
                            var view = presentation.present(source, language);
                            assertEquals(form.code(), view.formCode());
                            assertEquals(form.code(), view.formTitle().label());
                            assertEquals(
                                    CatalogReportPresentationService.Status.MISSING,
                                    view.formTitle().status());
                            assertEquals("ru", view.sourceInputLocale());
                            assertEquals(language, view.displayLocale());
                            assertEquals("UNQUALIFIED_DRAFT", view.sourceStatus());
                            assertEquals("KZT", view.currency());
                            assertEquals(1000, view.amountScale());
                            var fields = CatalogXlsxRoundTripService.fields(form.code());
                            assertEquals(fields.size(), view.fields().size());

                            int translated = 0;
                            int missing = 0;
                            for (int i = 0; i < fields.size(); i++) {
                                var schemaField = fields.get(i);
                                var presented = view.fields().get(i);
                                assertEquals(schemaField.code(), presented.fieldCode());
                                assertEquals(schemaField.type(), presented.fieldType());
                                assertNotNull(presented.help());
                                assertFalse(presented.help().isBlank());
                                assertNotEquals(
                                        CatalogReportPresentationService.Status.APPROVED,
                                        presented.status());
                                assertEquals("PENDING_METHOD_REVIEW", presented.requiredness());
                                String[] record = raw.get(form.code() + ":" + schemaField.code());
                                if (form.captureMode() ==
                                        CatalogCaptureRegistry.Mode.EVIDENCE_ONLY) {
                                    assertNull(record, "Evidence register fields are not approved numeric sources");
                                    assertEquals(schemaField.code(), presented.label());
                                    assertEquals(
                                            CatalogReportPresentationService.Status.MISSING,
                                            presented.status());
                                    missing++;
                                } else {
                                    assertNotNull(record, form.code() + "/" + schemaField.code());
                                    assertEquals(schemaField.type().name(), record[2]);
                                    int langIndex = switch (language) {
                                        case "ru" -> 3;
                                        case "kk" -> 4;
                                        case "en" -> 5;
                                        case "zh-Hans" -> 6;
                                        default -> throw new IllegalStateException(language);
                                    };
                                    if (record[langIndex].isBlank()) {
                                        assertEquals(schemaField.code(), presented.label());
                                        assertEquals(
                                                CatalogReportPresentationService.Status.MISSING,
                                                presented.status());
                                        missing++;
                                    } else {
                                        assertEquals(record[langIndex], presented.label());
                                        assertEquals(
                                                CatalogReportPresentationService.Status.PROVISIONAL,
                                                presented.status());
                                        translated++;
                                    }
                                    assertEquals(record[8], presented.requiredness());
                                    assertEquals(record[9], presented.unitPolicy());
                                }
                                if (schemaField.type() ==
                                        CatalogCaptureRegistry.FieldType.MONEY) {
                                    assertEquals("KZT x 1000", presented.unitContext());
                                }
                            }
                            var proof = new LinkedHashMap<String, Object>();
                            proof.put("formCode", form.code());
                            proof.put("displayLocale", language);
                            proof.put("schemaFieldCount", fields.size());
                            proof.put("translatedFields", translated);
                            proof.put("missingFields", missing);
                            proof.put("approvedFields", 0);
                            proof.put("formTitleStatus", "MISSING");
                            proof.put("sourceInputLocale", "ru");
                            proof.put("state", "PROVISIONAL_OR_MISSING_ONLY");
                            String key = form.code() + "|" + language;
                            assertNull(PROVEN_CASES.putIfAbsent(key, Map.copyOf(proof)),
                                    "duplicate locale matrix case " + key);
                        })));
    }

    @Test
    void actualChineseLabelsAreProvisionalAndUntranslatedLabelsUseFieldCode() {
        var cn = presentation.present(context("DF-AST-01", "ru"), "zh-Hans");
        assertEquals("资产标识", cn.fields().getFirst().label());
        assertEquals("产能（单位待确认）", cn.fields().getLast().label());
        assertEquals(6, cn.fields().stream().filter(x -> x.status() ==
                CatalogReportPresentationService.Status.PROVISIONAL).count());
        var missing = presentation.present(context("DF-AST-02", "ru"), "zh-Hans");
        assertTrue(missing.fields().stream().allMatch(x ->
                x.status() ==
                        CatalogReportPresentationService.Status.MISSING));
        assertEquals("assetRef", missing.fields().getFirst().label());
        assertTrue(cn.fields().getFirst().help().contains("独立审核"));
        assertEquals("PENDING_UNIT_REVIEW", cn.fields().getFirst().unitPolicy());
        assertEquals("KZT x 1000", cn.fields().get(3).unitContext());
    }

    @Test
    void measuredCoverageIsExactly794AndNeverClaimsApprovedTranslations() {
        var expected = Map.of("ru", 9, "kk", 9, "en", 9, "zh-Hans", 6);
        for (var locale : CatalogReportLocale.DISPLAY) {
            var entry = presentation.coverage(locale);
            assertEquals(794, entry.knownFields());
            assertEquals(expected.get(locale), entry.labelled());
            assertEquals(794 - expected.get(locale), entry.missing());
            assertEquals(CatalogReportPresentationService.Status.MISSING,
                    presentation.formTitle("F01", locale).status());
        }
    }

    @Test
    void inputLocaleAndDisplayLocaleAreDifferentValidationDomains() {
        for (String alias : List.of("zh", "zh-CN", "ZH-hans", "zh-Hant", "de", "", "RU")) {
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogReportLocale.requireDisplay(alias));
        }
        assertThrows(IllegalArgumentException.class,
                () -> CatalogReportLocale.requireDisplay(null));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogReportLocale.requireSourceInput("zh-Hans"));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogReportLocale.requireSourceInput(null));
        for (String input : CatalogReportLocale.SOURCE_INPUT) {
            var original = context("DF-AST-01", input);
            for (String display : CatalogReportLocale.DISPLAY) {
                var output = presentation.present(original, display);
                assertEquals(input, original.locale());
                assertEquals(input, output.sourceInputLocale());
                assertEquals(display, output.displayLocale());
                assertEquals(original.formCode(), output.formCode());
                assertEquals(original.currency(), output.currency());
                assertEquals(original.amountScale(), output.amountScale());
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> context("DF-AST-01", "zh-Hans"));
    }

    @Test
    void displayChangesDoNotModifyIssuedV2SourceMetadataOrExactDecimals() throws Exception {
        var xlsx = new CatalogXlsxRoundTripService(new JdbcTemplate(),
                new ObjectMapper(), new CatalogDocumentPreflight());
        final String exact = "12345678901234567890123456.123456789012";
        for (String sourceLang : CatalogReportLocale.SOURCE_INPUT) {
            var source = context("DF-AST-01", sourceLang);
            UUID issued = UUID.randomUUID();
            byte[] issuedBytes = xlsx.generateIssued(source, issued);
            byte[] populated;
            try (var book = new XSSFWorkbook(new ByteArrayInputStream(issuedBytes));
                 var out = new ByteArrayOutputStream()) {
                var row = book.getSheet("FIN_Data").getRow(1);
                row.createCell(1).setCellValue("资产甲");
                row.createCell(4).setCellValue(exact);
                assertEquals(sourceLang, book.getSheet("_FIN_Metadata")
                        .getRow(14).getCell(1).getStringCellValue());
                assertEquals(issued.toString(), book.getSheet("_FIN_Metadata")
                        .getRow(16).getCell(1).getStringCellValue());
                book.write(out);
                populated = out.toByteArray();
            }
            var parsed = xlsx.parseIssued(source, populated, issued);
            assertEquals(exact, parsed.rows().getFirst().cells().get("cost"));
            assertEquals("资产甲", parsed.rows().getFirst().cells().get("assetRef"));
            for (String language : CatalogReportLocale.DISPLAY) {
                var view = presentation.present(source, language);
                assertEquals(sourceLang, view.sourceInputLocale());
                assertEquals(language, view.displayLocale());
                assertEquals(exact, parsed.rows().getFirst().cells().get("cost"));
                assertEquals("资产甲", parsed.rows().getFirst().cells().get("assetRef"));
                // The source locale, original workbook and normalized values
                // remain unchanged by switching the presentation language.
                assertEquals(sourceLang, source.locale());
            }
            assertThrows(IllegalArgumentException.class,
                    () -> xlsx.parse(source, populated), "V2 must not be silently accepted as V1");
        }
    }

    @AfterAll
    static void writeMeasuredX0CoverageEvidence() throws Exception {
        assertEquals(166 * 4, PROVEN_CASES.size(),
                "All 166 forms must have 4 genuinely passed display cases");
        var forms = new ArrayList<Map<String, Object>>();
        for (var form : CatalogCaptureRegistry.all()) {
            for (String locale : CatalogReportLocale.DISPLAY) {
                var record = PROVEN_CASES.get(form.code() + "|" + locale);
                assertNotNull(record, "Missing per-form/locale proof " + form.code() + "/" + locale);
                forms.add(record);
            }
        }
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("schemaVersion", "FIN_BE_R1_X0_FOUR_LANGUAGE_CATALOG_V1");
        evidence.put("gitSha", System.getenv().getOrDefault("GITHUB_SHA", "LOCAL_TEST"));
        evidence.put("catalogForms", 166);
        evidence.put("structuredFieldSlots", 794);
        evidence.put("sourceInputLocales",
                CatalogReportLocale.SOURCE_INPUT);
        evidence.put("displayLocales",
                CatalogReportLocale.DISPLAY);
        evidence.put("formLocaleCasesPassed", forms.size());
        evidence.put("translationStatus", "PROVISIONAL_OR_MISSING_ONLY");
        evidence.put("financialS2Approved", false);
        Map<String, Object> measured = new LinkedHashMap<>();
        for (String locale : CatalogReportLocale.DISPLAY) {
            var actual = presentation.coverage(locale);
            measured.put(locale, Map.of(
                    "displayLocale", locale,
                    "totalFields", actual.knownFields(),
                    "translatedFields", actual.labelled(),
                    "provisionalFields", actual.labelled(),
                    "approvedFields", 0,
                    "missingFields", actual.missing(),
                    "translatedFormTitles", 0,
                    "missingFormTitles", 166));
        }
        evidence.put("coverage", measured);
        evidence.put("forms", forms);
        Path base = Path.of(System.getProperty("basedir",
                Files.exists(Path.of("backend/pom.xml")) ? "backend" : "."));
        Path destination = base.resolve(
                "target/fin-evidence/fin-be-r1-x0-four-language-coverage.json");
        Files.createDirectories(destination.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                destination.toFile(), evidence);
    }
}
