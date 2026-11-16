package kz.fin.backend.intake;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * FIN-BE-R1-X / X2: one confirmed revision -> exact, re-importable Apache POI V2.
 * X1 selects the source; changing display language cannot change the source.
 * No HTTP authorization boundary here: controllers MUST apply FIN_READ + FIN_SUBMIT.
 */
@Service
public class CatalogXlsxExportService {
    private record Words(String warning, String form, String period, String basis,
                         String coverage, String currency, String scale, String revision,
                         String revisionNumber, String status, String sourceLocale,
                         String displayLocale, String labelWarning, String evidenceNotice) {}

    private static final Map<String, Words> WORDS = Map.of(
        "ru", new Words(
            "Данные каталога не квалифицированы финансово (не утверждённая отчётность)",
            "Форма", "Период", "Основание", "Охват", "Валюта", "Масштаб",
            "Исходная ревизия", "Номер", "Статус", "Язык источника",
            "Язык отчёта", "Подписи полей предварительные или отсутствуют",
            "Реестр документов: оригинал не включён; для повторной загрузки нужен оригинал"),
        "kk", new Words(
            "Каталог деректері қаржылық тұрғыдан бекітілмеген (ресми есеп емес)",
            "Нысан", "Кезең", "Негіз", "Қамту", "Валюта", "Масштаб",
            "Дерек ревизиясы", "Нөмірі", "Мәртебе", "Дерек тілі",
            "Есеп тілі", "Өріс атаулары алдын ала берілген немесе жоқ",
            "Құжаттар тізілімі: түпнұсқа енгізілмеген; қайта жүктеуге түпнұсқа қажет"),
        "en", new Words(
            "UNQUALIFIED CATALOG DATA (NOT AN APPROVED FINANCIAL REPORT)",
            "Form", "Period", "Reporting basis", "Coverage", "Currency", "Amount scale",
            "Source revision", "Number", "Source status", "Source input locale",
            "Display locale", "Field labels are provisional or missing",
            "Evidence register: original not embedded; original required for re-upload"),
        "zh-Hans", new Words(
            "目录数据尚未通过财务审核（非正式财务报表）",
            "表单", "期间", "编制基础", "覆盖范围", "币种", "金额单位",
            "来源修订", "修订序号", "来源状态", "源输入语言",
            "显示语言", "字段标签尚未审核或缺失",
            "证据登记：未嵌入原件；重新上传需另附原件")
    );

    private final FinancialReportSnapshotService reports;
    private final CatalogReportPresentationService presentation;
    private final CatalogIssuedTemplateService issued;

    public CatalogXlsxExportService(FinancialReportSnapshotService reports,
                                    CatalogReportPresentationService presentation,
                                    CatalogIssuedTemplateService issued) {
        this.reports = Objects.requireNonNull(reports);
        this.presentation = Objects.requireNonNull(presentation);
        this.issued = Objects.requireNonNull(issued);
    }

    public record Exported(UUID exportId, UUID issuedTemplateId, UUID sourceRevisionId, UUID sourceDraftId,
                           int revisionNumber, String formCode, String periodCode,
                           String sourceInputLocale, String displayLocale, String currency,
                           int amountScale, String sourceState, String normalizedFingerprint,
                           String contentFingerprintV2,
                           String workbookSha256, byte[] workbook) {
        public Exported {
            workbook = workbook.clone();
        }
        @Override public byte[] workbook() { return workbook.clone(); }
    }

    // Internal package-only helper for existing PostgreSQL integration tests.
    Exported export(FinancialReportSnapshotService.Query query, String displayLocale) {
        return export(query, displayLocale, "INTERNAL_TEST");
    }

    public Exported export(FinancialReportSnapshotService.Query query,
                           String displayLocale, String verifiedActor) {
        CatalogReportLocale.requireDisplay(displayLocale);
        if (verifiedActor == null || verifiedActor.isBlank() || verifiedActor.length() > 200)
            throw new IllegalArgumentException("FIN_EXPORT_ACTOR_REQUIRED");
        var snapshot = reports.load(Objects.requireNonNull(query));
        var sourceContext = new CatalogXlsxRoundTripService.Context(
                snapshot.organizationId(), snapshot.economicEntityId(),
                snapshot.businessScopeId(), snapshot.projectCode(),
                snapshot.formCode(), snapshot.reportingBasis(), snapshot.periodType(),
                snapshot.periodCode(), snapshot.coverage(), snapshot.currency(),
                snapshot.amountScale(), snapshot.sourceInputLocale());

        // A source issued under ru/kk/en stays tied to its actual input locale.
        var localized = presentation.present(sourceContext, displayLocale);
        Words words = WORDS.get(displayLocale);
        List<String> instructions = new ArrayList<>();
        instructions.add("FIN-BE-R1-X / X2 — " + words.warning());
        instructions.add(words.form() + ": " + snapshot.formCode() + " | "
                + words.period() + ": " + snapshot.periodType() + " " + snapshot.periodCode());
        instructions.add(words.basis() + ": " + snapshot.reportingBasis() + " | "
                + words.coverage() + ": " + snapshot.coverage());
        instructions.add(words.currency() + ": " + snapshot.currency()
                + " | " + words.scale() + ": " + snapshot.amountScale());
        instructions.add(words.revision() + ": " + snapshot.sourceRevisionId()
                + " | " + words.revisionNumber() + ": " + snapshot.revisionNumber());
        instructions.add(words.status() + ": " + snapshot.sourceStatus()
                + " | " + words.sourceLocale() + ": " + snapshot.sourceInputLocale());
        instructions.add(words.displayLocale() + ": " + displayLocale
                + " | " + words.labelWarning());
        if (snapshot.documentSha256() != null)
            instructions.add(words.evidenceNotice());
        instructions.add("FIN_SNAPSHOT_CONTENT_V2: " + snapshot.contentFingerprintV2());
        for (var field : localized.fields()) {
            instructions.add(field.fieldCode() + ": " + field.label()
                    + " [" + field.status() + "]");
        }

        var issuedReport = issued.issuePopulated(snapshot.projectId(), sourceContext,
                snapshot.rows(), instructions,
                new CatalogIssuedTemplateService.ExportSource(
                        snapshot.sourceRevisionId(), snapshot.sourceDraftId(),
                        snapshot.normalizedFingerprint(), displayLocale, verifiedActor));
        var workbook = issuedReport.issued();
        return new Exported(issuedReport.exportId(), workbook.templateId(),
                snapshot.sourceRevisionId(), snapshot.sourceDraftId(),
                snapshot.revisionNumber(), snapshot.formCode(), snapshot.periodCode(),
                snapshot.sourceInputLocale(), displayLocale, snapshot.currency(),
                snapshot.amountScale(), snapshot.sourceStatus(),
                snapshot.normalizedFingerprint(), snapshot.contentFingerprintV2(),
                workbook.templateSha256(), workbook.workbook());
    }
}
