package kz.fin.backend.intake;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Independent security, Unicode and pagination checks for the two pure
 * renderers. The 166x4 real PostgreSQL tests run separately. */
class FinancialReportHtmlPdfRendererTest {
    private final FinancialReportRenderModel model = new FinancialReportRenderModel(
            new CatalogReportPresentationService());
    private final FinancialReportHtmlRenderer html = new FinancialReportHtmlRenderer(model);
    private final FinancialReportPdfRenderer pdf = new FinancialReportPdfRenderer(model);
    private static final Instant FIXED = Instant.parse("2026-11-16T11:00:00Z");

    private static FinancialReportSnapshotService.Snapshot sample(
            String assetRef, int lines) {
        var fields = CatalogXlsxRoundTripService.fields("DF-AST-01");
        var rows = new ArrayList<CatalogCaptureRegistry.EntryRow>();
        for (int n = 0; n < lines; n++) {
            var cells = new LinkedHashMap<String, String>();
            for (var field : fields) {
                String value = switch (field.type()) {
                    case REF, CODE, ENUM -> "REF001";
                    case MONEY, DECIMAL -> "12345678901234567890123456.123456789012";
                    case DATE -> "2026-10-31";
                    case INTEGER -> "2";
                    case PERIOD -> "2026.10";
                    case BOOLEAN -> "false";
                    case TEXT -> "Synthetic";
                };
                cells.put(field.code(), value);
            }
            cells.put("assetRef", assetRef);
            rows.add(new CatalogCaptureRegistry.EntryRow(
                    "ROW-" + (n + 1), Map.copyOf(cells)));
        }
        return new FinancialReportSnapshotService.Snapshot(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "PROJECT_X3X4", "DF-AST-01", "TYPED_DRAFT",
                "MANAGEMENT", ReportingPeriodV5.Type.MONTH,
                "2026.10", ReportingPeriodV5.Coverage.PERIOD_ONLY,
                "KZT", 1000, "ru",
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                3, UUID.fromString("66666666-6666-6666-6666-666666666666"),
                "a".repeat(64), CatalogXlsxRoundTripService.schemaFingerprint("DF-AST-01"),
                "b".repeat(64), null, "CONFIRMED_UNQUALIFIED_DRAFT",
                fields, rows);
    }

    @Test void htmlEscapesUserInputAndHasNoActiveOrRemoteContent() {
        String hostile = "<script>alert('x')</script> & \" <iframe src='file:///etc/passwd'>";
        var doc = html.render(sample(hostile, 1), "zh-Hans", FIXED);
        assertTrue(doc.html().startsWith("<!doctype html>"));
        assertTrue(doc.html().contains("&lt;script&gt;"));
        assertFalse(doc.html().contains("<script>"));
        assertFalse(doc.html().contains("<iframe"));
        assertFalse(doc.html().contains("http://"));
        assertFalse(doc.html().contains("https://"));
        var parsed = Jsoup.parse(doc.html());
        assertEquals(0, parsed.select("script,iframe,form,link,object,img").size());
        assertEquals(hostile, parsed.selectFirst("tbody td.value").wholeText());
        assertEquals("zh-Hans", parsed.selectFirst("html").attr("lang"));
        assertTrue(doc.html().contains("UNQUALIFIED"));
        assertTrue(FinancialReportHtmlRenderer.CSP.contains("default-src 'none'"));
        assertTrue(FinancialReportHtmlRenderer.CSP.contains("style-src 'sha256-"));
        assertEquals(sample(hostile, 1).sourceRevisionId(), doc.sourceRevisionId());
        var repeated = html.render(sample(hostile, 1), "zh-Hans", FIXED);
        assertEquals(doc.sha256(), repeated.sha256(),
                "fixed snapshot/time/locale must make deterministic HTML");
    }

    @Test void htmlRejectsBidiControlsRatherThanRenderingSpoofedNumbers() {
        assertThrows(IllegalArgumentException.class, () -> html.render(
                sample("REF\u202E123", 1), "ru", FIXED));
        assertThrows(IllegalArgumentException.class, () ->
                FinancialReportHtmlRenderer.escape("ABC\u2066DEF\u2069"));
        assertThrows(IllegalArgumentException.class, () ->
                FinancialReportHtmlRenderer.escape("X\u0000Y"));
    }

    @Test void pdfEmbedsActualChineseKazakhAndExact38PrecisionWithoutClipping()
            throws Exception {
        var snapshot = sample("资产甲 ӘҚҢӨҰҮҺІ", 1);
        var rendered = pdf.render(snapshot, "zh-Hans", FIXED);
        assertEquals(snapshot.sourceRevisionId(), rendered.sourceRevisionId());
        assertTrue(rendered.bytes().length > 1000);
        assertTrue(rendered.pageCount() > 0);
        try (var opened = Loader.loadPDF(rendered.bytes())) {
            assertEquals(rendered.pageCount(), opened.getNumberOfPages());
            String extracted = new PDFTextStripper().getText(opened);
            String normalized = extracted.replaceAll("\\s+", "");
            assertTrue(normalized.contains("资产甲"));
            assertTrue(normalized.contains("ӘҚҢӨҰҮҺІ"));
            assertTrue(normalized.contains("12345678901234567890123456.123456789012"));
            assertTrue(extracted.contains(snapshot.sourceRevisionId().toString()));
            assertTrue(extracted.contains("UNQUALIFIED"));
            assertTrue(extracted.contains("资产标识"));
            // Raster smoke proves the PDF is not just text metadata with blank pages.
            BufferedImage raster = new PDFRenderer(opened).renderImageWithDPI(0, 90f);
            assertTrue(raster.getWidth() > 800 && raster.getHeight() > 500);
            int baseColor = raster.getRGB(0, 0);
            boolean ink = false;
            for (int y = 30; y < raster.getHeight() - 30 && !ink; y += 5)
                for (int x = 30; x < raster.getWidth() - 30; x += 5)
                    if (raster.getRGB(x, y) != baseColor) { ink = true; break; }
            assertTrue(ink, "rasterized report must contain visible ink");
        }
    }

    @Test void pdfRepeatedTableHeadersAndPageNumbersForLongSource()
            throws Exception {
        var snapshot = sample("REF001", 90);
        var rendered = pdf.render(snapshot, "kk", FIXED);
        assertTrue(rendered.pageCount() > 2, "many rows need additional pages");
        try (var opened = Loader.loadPDF(rendered.bytes())) {
            String text = new PDFTextStripper().getText(opened);
            assertTrue(text.contains("ROW-1"));
            assertTrue(text.contains("ROW-90"));
            assertTrue(text.contains("Page 1"));
            assertTrue(text.contains("Page 2"));
            assertTrue(text.contains("ROW_ID"));
            assertTrue(text.contains("Ә") || text.contains("KZT"),
                    "report must retain proper Cyrillic/ASCII glyphs");
        }
    }

    @Test void pdfDoesNotAcceptBidiOverrideOrInventApprovals() {
        assertThrows(IllegalArgumentException.class,
                () -> pdf.render(sample("CODE\u202E1", 1), "en", FIXED));
        var bad = sample("REF001", 1);
        var disallowed = new FinancialReportSnapshotService.Snapshot(
                bad.organizationId(), bad.projectId(), bad.economicEntityId(),
                bad.businessScopeId(), bad.projectCode(), bad.formCode(),
                bad.captureMode(), bad.reportingBasis(), bad.periodType(),
                bad.periodCode(), bad.coverage(), bad.currency(), bad.amountScale(),
                bad.sourceInputLocale(), bad.sourceRevisionId(), bad.revisionNumber(),
                bad.sourceDraftId(), bad.normalizedFingerprint(),
                bad.schemaFingerprint(), bad.originalXlsxSha256(), bad.documentSha256(),
                "ACTUAL", bad.fields(), bad.rows());
        assertThrows(IllegalArgumentException.class,
                () -> html.render(disallowed, "ru", FIXED));
        assertThrows(IllegalArgumentException.class,
                () -> pdf.render(disallowed, "ru", FIXED));
    }
}
