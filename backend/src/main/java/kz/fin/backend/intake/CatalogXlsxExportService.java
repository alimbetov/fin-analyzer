package kz.fin.backend.intake;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * FIN-BE-R1-X / X2: one confirmed revision -> exact, re-importable Apache POI V2.
 * X1 selects the source; changing display language cannot change the source.
 * No HTTP authorization boundary here: controllers MUST apply FIN_READ + FIN_SUBMIT.
 */
@Service
public class CatalogXlsxExportService {
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
        List<String> instructions = new ArrayList<>();
        instructions.add("FIN-BE-R1-X / X2 - UNQUALIFIED CATALOG REPORT (NOT APPROVED ACTUAL)");
        instructions.add("Form: " + snapshot.formCode() + " | Period: "
                + snapshot.periodType() + " " + snapshot.periodCode());
        instructions.add("Reporting basis: " + snapshot.reportingBasis()
                + " | Coverage: " + snapshot.coverage());
        instructions.add("Currency: " + snapshot.currency()
                + " | Amount scale: " + snapshot.amountScale());
        instructions.add("Source revision: " + snapshot.sourceRevisionId()
                + " | Number: " + snapshot.revisionNumber());
        instructions.add("Source state: " + snapshot.sourceStatus()
                + " | Source input locale: " + snapshot.sourceInputLocale());
        instructions.add("Display locale: " + displayLocale
                + " | Human labels are unqualified and may be MISSING");
        if (snapshot.documentSha256() != null)
            instructions.add("Evidence-only register: the original document is NOT embedded. "
                    + "A separate authorized original is required for re-upload.");
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
                snapshot.normalizedFingerprint(), workbook.templateSha256(),
                workbook.workbook());
    }
}
