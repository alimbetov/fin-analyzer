package kz.fin.backend.intake;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CatalogReportLocaleAndPresentationTest {
    private final CatalogReportPresentationService presentation =
            new CatalogReportPresentationService();

    @Test
    void fourDisplayLocalesDoNotChangeThreePersistedSourceLocales() {
        assertEquals(Set.of("ru", "kk", "en"), Set.copyOf(CatalogReportLocale.SOURCE_INPUT));
        assertEquals(Set.of("ru", "kk", "en", "zh-Hans"),
                Set.copyOf(CatalogReportLocale.DISPLAY));
        for (String source : CatalogReportLocale.SOURCE_INPUT) {
            for (String display : CatalogReportLocale.DISPLAY) {
                var selection = new CatalogReportLocale.Selection(source, display);
                assertEquals(source, selection.sourceInputLocale());
                assertEquals(display, selection.displayLocale());
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogReportLocale.Selection("zh-Hans", "zh-Hans"));
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogReportLocale.Selection("ru", "zh"));
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogReportLocale.Selection("en", null));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogReportLocale.requireSourceInput("ru-RU"));
    }

    @Test
    void all166FormsHaveFourSafePresentationSlotsWithoutInventedTranslations() {
        assertEquals(166, CatalogCaptureRegistry.all().size());
        for (var locale : CatalogReportLocale.DISPLAY) {
            int total = 0;
            for (var form : CatalogCaptureRegistry.all()) {
                var labels = presentation.formLabels(form.code(), locale);
                assertEquals(CatalogXlsxRoundTripService.fields(form.code()).size(),
                        labels.size(), form.code());
                for (var label : labels) {
                    total++;
                    assertNotNull(label.label());
                    assertNotEquals(CatalogReportPresentationService.Status.APPROVED,
                            label.status(), "No financial method approval exists");
                    if (label.status() == CatalogReportPresentationService.Status.MISSING)
                        assertEquals(label.fieldCode(), label.label());
                }
            }
            assertEquals(794 + 12 * 7, total);
            var coverage = presentation.coverage(locale);
            assertEquals(794, coverage.knownFields());
            assertEquals("zh-Hans".equals(locale) ? 6 : 9, coverage.labelled());
            assertEquals(794 - coverage.labelled(), coverage.missing());
        }
    }

    @Test
    void chinesePilotIsProvisionalAndOtherFieldsSafelyFallbackToCodes() {
        var provisional = presentation.label("DF-AST-01", "assetRef", "zh-Hans");
        assertEquals("资产标识", provisional.label());
        assertEquals(CatalogReportPresentationService.Status.PROVISIONAL,
                provisional.status());
        var missing = presentation.label("F02", "netRevenue", "zh-Hans");
        assertEquals("netRevenue", missing.label());
        assertEquals(CatalogReportPresentationService.Status.MISSING, missing.status());
        assertEquals(CatalogReportPresentationService.Status.MISSING,
                presentation.label("D01", "attachmentRef", "ru").status());
        assertThrows(IllegalArgumentException.class,
                () -> presentation.label("F02", "unknownField", "ru"));
        assertThrows(IllegalArgumentException.class,
                () -> presentation.label("F02", "netRevenue", "zh"));
    }

    @Test
    void sourceDecimalRulesAreUnaffectedByChineseDisplaySelection() {
        var ctx = new CatalogReportLocale.Selection("ru", "zh-Hans");
        assertEquals("1234.5", CatalogCaptureRegistry.canonical(
                CatalogCaptureRegistry.FieldType.MONEY, "1234,5000",
                ctx.sourceInputLocale()));
        assertThrows(IllegalArgumentException.class, () ->
                CatalogCaptureRegistry.canonical(
                        CatalogCaptureRegistry.FieldType.MONEY, "1234,5000",
                        ctx.displayLocale()));
    }
}
