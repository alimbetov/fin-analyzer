package kz.fin.backend.intake;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure, independent V2 regression: orders and document bytes are not
 * interchangeable, unlike the legacy rowId-sorted normalized SHA.
 */
class FinancialReportContentIdentityTest {

    private static FinancialReportSnapshotService.Snapshot baseline() {
        var fields = CatalogXlsxRoundTripService.fields("DF-AST-01");
        List<CatalogCaptureRegistry.EntryRow> rows = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var cells = new LinkedHashMap<String, String>();
            for (var field : fields) {
                String value = switch (field.type()) {
                    case MONEY, DECIMAL -> i == 0
                            ? "12345678901234567890123456.123456789012" : "-105.23";
                    case INTEGER -> Integer.toString(i + 1);
                    case DATE -> "2026-10-31";
                    case BOOLEAN -> "false";
                    case PERIOD -> "2026.10";
                    case REF -> "ASSET-" + i;
                    case CODE -> "A";
                    case ENUM -> "DRAFT";
                    case TEXT -> "Typed";
                };
                cells.put(field.code(), value);
            }
            rows.add(new CatalogCaptureRegistry.EntryRow(
                    "ROW-" + (i + 1), Map.copyOf(cells)));
        }
        return new FinancialReportSnapshotService.Snapshot(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                UUID.fromString("40000000-0000-0000-0000-000000000004"),
                "PROJECT_X5", "DF-AST-01", "TYPED_DRAFT",
                "MANAGEMENT", ReportingPeriodV5.Type.MONTH, "2026.10",
                ReportingPeriodV5.Coverage.PERIOD_ONLY,
                "KZT", 1000, "ru",
                UUID.fromString("50000000-0000-0000-0000-000000000005"),
                1, UUID.fromString("60000000-0000-0000-0000-000000000006"),
                "a".repeat(64), CatalogXlsxRoundTripService.schemaFingerprint("DF-AST-01"),
                "b".repeat(64), null, "CONFIRMED_UNQUALIFIED_DRAFT",
                fields, rows);
    }

    private static FinancialReportSnapshotService.Snapshot changed(
            FinancialReportSnapshotService.Snapshot s,
            List<CatalogCaptureRegistry.EntryRow> rows, String locale,
            String documentSha, UUID revision, String originalSha) {
        return new FinancialReportSnapshotService.Snapshot(
                s.organizationId(), s.projectId(), s.economicEntityId(), s.businessScopeId(),
                s.projectCode(), s.formCode(), s.captureMode(), s.reportingBasis(),
                s.periodType(), s.periodCode(), s.coverage(), s.currency(),
                s.amountScale(), locale, revision, s.revisionNumber(), s.sourceDraftId(),
                s.normalizedFingerprint(), s.schemaFingerprint(), originalSha,
                documentSha, s.sourceStatus(), s.fields(), rows);
    }

    @Test void stableDigestIsHexSha256AndCannotDependOnExportRevisionOrRawWorkbook() {
        var s = baseline();
        assertTrue(s.contentFingerprintV2().matches("[a-f0-9]{64}"));
        assertEquals(FinancialReportContentIdentity.digest(s), s.contentFingerprintV2());
        var copy = changed(s, s.rows(), "ru", null, UUID.randomUUID(), "c".repeat(64));
        assertEquals(s.contentFingerprintV2(), copy.contentFingerprintV2());
        assertEquals(FinancialReportContentIdentity.VERSION, "FIN-SNAPSHOT-CONTENT-V2");
    }

    @Test void rowPermutationChangesV2ButNotLegacyStoredFingerprint() {
        var s = baseline();
        var reversed = changed(s, List.of(s.rows().get(1), s.rows().get(0)),
                "ru", null, s.sourceRevisionId(), s.originalXlsxSha256());
        assertEquals(s.normalizedFingerprint(), reversed.normalizedFingerprint());
        assertNotEquals(s.contentFingerprintV2(), reversed.contentFingerprintV2());
    }

    @Test void sourceLocaleAndOriginalDocumentHashChangeIdentity() {
        var s = baseline();
        assertNotEquals(s.contentFingerprintV2(),
                changed(s, s.rows(), "kk", null, s.sourceRevisionId(),
                        s.originalXlsxSha256()).contentFingerprintV2());
        assertNotEquals(s.contentFingerprintV2(),
                changed(s, s.rows(), "ru", "d".repeat(64), s.sourceRevisionId(),
                        s.originalXlsxSha256()).contentFingerprintV2());
        assertNotEquals(changed(s, s.rows(), "ru", "d".repeat(64), s.sourceRevisionId(),
                        s.originalXlsxSha256()).contentFingerprintV2(),
                changed(s, s.rows(), "ru", "e".repeat(64), s.sourceRevisionId(),
                        s.originalXlsxSha256()).contentFingerprintV2());
    }

    @Test void changingOnlyOneExactDecimalChangesContentFingerprint() {
        var s = baseline();
        var first = s.rows().getFirst();
        var cells = new LinkedHashMap<>(first.cells());
        cells.put("cost", "12345678901234567890123456.123456789011");
        var alteredRows = List.of(new CatalogCaptureRegistry.EntryRow(first.rowId(), cells),
                s.rows().get(1));
        assertNotEquals(s.contentFingerprintV2(),
                changed(s, alteredRows, "ru", null, s.sourceRevisionId(),
                        s.originalXlsxSha256()).contentFingerprintV2());
    }

    @Test void displayLanguageCannotChangeCrossFormatIdentity() {
        var s = baseline();
        var presentation = new CatalogReportPresentationService();
        var model = new FinancialReportRenderModel(presentation);
        var html = new FinancialReportHtmlRenderer(model);
        for (var locale : CatalogReportLocale.DISPLAY) {
            var rendered = html.render(s, locale, java.time.Instant.parse(
                    "2026-11-17T00:00:00Z"));
            assertEquals(s.contentFingerprintV2(), rendered.contentFingerprintV2());
            assertEquals(s.sourceRevisionId(), rendered.sourceRevisionId());
        }
    }
}
