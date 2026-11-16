package kz.fin.backend.intake;

import java.util.List;

/**
 * X0: the source input locale is a persisted parser contract, while the
 * report display locale only controls human-readable labels. A zh-Hans
 * rendering must never rewrite a previously issued ru/kk/en workbook.
 */
public final class CatalogReportLocale {
    public static final List<String> SOURCE_INPUT = List.of("ru", "kk", "en");
    public static final List<String> DISPLAY = List.of("ru", "kk", "en", "zh-Hans");

    private CatalogReportLocale() {}

    public static String requireSourceInput(String locale) {
        if (locale == null || !SOURCE_INPUT.contains(locale))
            throw new IllegalArgumentException("FIN_REPORT_SOURCE_LOCALE_UNSUPPORTED");
        return locale;
    }

    public static String requireDisplay(String locale) {
        if (locale == null || !DISPLAY.contains(locale))
            throw new IllegalArgumentException("FIN_REPORT_DISPLAY_LOCALE_UNSUPPORTED");
        return locale;
    }

    public record Selection(String sourceInputLocale, String displayLocale) {
        public Selection {
            sourceInputLocale = requireSourceInput(sourceInputLocale);
            displayLocale = requireDisplay(displayLocale);
        }
    }
}
