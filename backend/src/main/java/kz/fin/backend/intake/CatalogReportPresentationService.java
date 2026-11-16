package kz.fin.backend.intake;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * X0 presentation-only inventory. These are unqualified draft translations,
 * not source-language parsing rules or approved financial definitions.
 */
@Service
public class CatalogReportPresentationService {
    private static final String RESOURCE = "/fin/form-field-presentation-review-r6.tsv";
    private static final String HEADER = "formCode\tfieldCode\tfieldType\tlabelRu\tlabelKk"
            + "\tlabelEn\tlabelZhHans\treviewStatus\trequiredness\tunitPolicy";

    public enum Status { APPROVED, PROVISIONAL, MISSING }
    public record Label(String fieldCode, String label, Status status) {}
    public record Coverage(String locale, int knownFields, int labelled, int missing) {}

    private record Inventory(String type, Map<String, String> labels, String reviewStatus,
                             String requiredness, String unitPolicy) {
        Inventory {
            labels = Map.copyOf(labels);
        }
    }

    private final Map<String, Inventory> inventory;

    public CatalogReportPresentationService() {
        inventory = load();
    }

    private static String key(String formCode, String fieldCode) {
        return formCode + ":" + fieldCode;
    }

    private static Map<String, Inventory> load() {
        var result = new LinkedHashMap<String, Inventory>();
        try (var input = CatalogReportPresentationService.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("FIN_REPORT_TRANSLATIONS_MISSING");
            try (var lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                if (!HEADER.equals(lines.readLine()))
                    throw new IllegalStateException("FIN_REPORT_TRANSLATIONS_HEADER_DRIFT");
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isBlank()) throw new IllegalStateException("FIN_REPORT_TRANSLATIONS_EMPTY_ROW");
                    String[] row = line.split("\t", -1);
                    if (row.length != 10 || !row[0].matches("[A-Z][A-Z0-9-]*")
                            || !row[1].matches("[A-Za-z][A-Za-z0-9_]*")
                            || !"PENDING_METHOD_REVIEW".equals(row[8]) || row[9].isBlank()
                            || !Set.of("PROVISIONAL_NOT_S2", "PENDING_TRANSLATION").contains(row[7]))
                        throw new IllegalStateException("FIN_REPORT_TRANSLATIONS_INVALID_ROW");
                    String[] languages = {"ru", "kk", "en", "zh-Hans"};
                    Map<String, String> labels = new HashMap<>();
                    boolean present = false;
                    for (int i = 0; i < languages.length; i++) {
                        if (!row[3 + i].isBlank()) {
                            present = true;
                            labels.put(languages[i], row[3 + i]);
                        }
                    }
                    if (("PENDING_TRANSLATION".equals(row[7]) && present)
                            || ("PROVISIONAL_NOT_S2".equals(row[7])
                            && row[3].isBlank() && row[4].isBlank() && row[5].isBlank()))
                        throw new IllegalStateException("FIN_REPORT_TRANSLATIONS_UNREVIEWED_LABEL");
                    String k = key(row[0], row[1]);
                    if (result.putIfAbsent(k, new Inventory(row[2], labels, row[7], row[8], row[9])) != null)
                        throw new IllegalStateException("FIN_REPORT_TRANSLATIONS_DUPLICATE");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("FIN_REPORT_TRANSLATIONS_READ_FAILED", e);
        }
        if (result.size() != 794)
            throw new IllegalStateException("FIN_REPORT_TRANSLATIONS_COVERAGE_DRIFT");
        Set<String> expected = new HashSet<>();
        for (var form : CatalogCaptureRegistry.all()) {
            if (form.captureMode() == CatalogCaptureRegistry.Mode.EVIDENCE_ONLY) continue;
            for (var field : CatalogXlsxRoundTripService.fields(form.code())) {
                expected.add(key(form.code(), field.code()));
                var entry = result.get(key(form.code(), field.code()));
                if (entry == null || !entry.type().equals(field.type().name()))
                    throw new IllegalStateException("FIN_REPORT_TRANSLATIONS_SCHEMA_DRIFT");
            }
        }
        if (!expected.equals(result.keySet()))
            throw new IllegalStateException("FIN_REPORT_TRANSLATIONS_UNKNOWN_FIELD");
        return Map.copyOf(result);
    }

    public Label label(String formCode, String fieldCode, String displayLocale) {
        CatalogReportLocale.requireDisplay(displayLocale);
        Objects.requireNonNull(fieldCode);
        var fields = CatalogXlsxRoundTripService.fields(formCode);
        if (fields.stream().noneMatch(f -> f.code().equals(fieldCode)))
            throw new IllegalArgumentException("FIN_REPORT_UNKNOWN_FIELD");
        var item = inventory.get(key(formCode, fieldCode));
        String value = item == null ? null : item.labels().get(displayLocale);
        if (value == null || value.isBlank())
            return new Label(fieldCode, fieldCode, Status.MISSING);
        // The currently committed inventory has no independently APPROVED labels.
        return new Label(fieldCode, value, Status.PROVISIONAL);
    }

    public List<Label> formLabels(String formCode, String displayLocale) {
        CatalogReportLocale.requireDisplay(displayLocale);
        var labels = new ArrayList<Label>();
        for (var field : CatalogXlsxRoundTripService.fields(formCode))
            labels.add(label(formCode, field.code(), displayLocale));
        return List.copyOf(labels);
    }


    /** Presentation-only view. Source locale remains the immutable ru/kk/en
     * input/parser coordinate while Chinese is display-only. No DB reads or writes. */
    public record FieldView(
            String fieldCode, CatalogCaptureRegistry.FieldType fieldType,
            String label, Status status, String requiredness, String unitPolicy,
            String unitContext, String help) {}
    public record FormView(
            String formCode, Label formTitle, String captureMode,
            String sourceInputLocale, String displayLocale,
            String currency, int amountScale, String sourceStatus,
            List<FieldView> fields) {
        public FormView { fields = List.copyOf(fields); }
    }

    private static final Map<String, String> HELP = Map.of(
        "ru", "Черновая форма. Обязательность, единицы измерения и экономический смысл требуют независимой проверки.",
        "kk", "Алдын ала нысан. Міндеттілік, өлшем бірліктері және экономикалық мәні тәуелсіз тексеруді талап етеді.",
        "en", "Provisional form. Requiredness, measurement units and accounting meaning require independent review.",
        "zh-Hans", "临时表单。必填性、计量单位及会计含义仍须经独立审核。"
    );

    /** No audited financial form-title translations exist. Return a truthful
     * MISSING code fallback rather than inventing a localized form title. */
    public Label formTitle(String formCode, String displayLocale) {
        CatalogReportLocale.requireDisplay(displayLocale);
        CatalogCaptureRegistry.require(formCode);
        return new Label(formCode, formCode, Status.MISSING);
    }

    public FormView present(CatalogXlsxRoundTripService.Context source,
                            String displayLocale) {
        Objects.requireNonNull(source, "source");
        var selection = new CatalogReportLocale.Selection(source.locale(), displayLocale);
        var form = CatalogCaptureRegistry.require(source.formCode());
        var fields = new ArrayList<FieldView>();
        for (var field : CatalogXlsxRoundTripService.fields(form.code())) {
            var label = label(form.code(), field.code(), selection.displayLocale());
            var info = inventory.get(key(form.code(), field.code()));
            if (info == null && form.captureMode() != CatalogCaptureRegistry.Mode.EVIDENCE_ONLY)
                throw new IllegalStateException("FIN_REPORT_TRANSLATIONS_SCHEMA_DRIFT");
            String unitPolicy = info == null ? "PENDING_UNIT_REVIEW" : info.unitPolicy();
            String requiredness = info == null ? "PENDING_METHOD_REVIEW" : info.requiredness();
            // No assumed KZT. Money unit/scale is always issued source context,
            // while other fields retain an explicitly unqualified unit policy.
            String unit = field.type() == CatalogCaptureRegistry.FieldType.MONEY
                    ? source.currency() + " x " + source.amountScale() : unitPolicy;
            fields.add(new FieldView(field.code(), field.type(), label.label(),
                    label.status(), requiredness, unitPolicy, unit,
                    HELP.get(selection.displayLocale())));
        }
        return new FormView(form.code(), formTitle(form.code(), displayLocale),
                form.captureMode().name(), selection.sourceInputLocale(),
                selection.displayLocale(), source.currency(), source.amountScale(),
                "UNQUALIFIED_DRAFT", fields);
    }

    public Coverage coverage(String displayLocale) {
        CatalogReportLocale.requireDisplay(displayLocale);
        int count = 0;
        for (var entry : inventory.values())
            if (entry.labels().containsKey(displayLocale)) count++;
        return new Coverage(displayLocale, inventory.size(), count, inventory.size() - count);
    }
}
