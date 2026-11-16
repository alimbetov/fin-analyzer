package kz.fin.backend.intake;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Single rendering projection of ONE immutable X1 snapshot. Pure and read-only:
 * no database, FX, rounding, translation promotion, or revision resolution.
 * X3 and X4 both consume precisely this model.
 */
@Component
public final class FinancialReportRenderModel {
    private final CatalogReportPresentationService presentation;

    public FinancialReportRenderModel(CatalogReportPresentationService presentation) {
        this.presentation = Objects.requireNonNull(presentation);
    }

    public record View(FinancialReportSnapshotService.Snapshot snapshot,
                       CatalogReportPresentationService.FormView labels,
                       String displayLocale, Instant generatedAt) {
        public View {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(labels);
            Objects.requireNonNull(displayLocale);
            Objects.requireNonNull(generatedAt);
            if (!snapshot.formCode().equals(labels.formCode()) ||
                    !snapshot.sourceInputLocale().equals(labels.sourceInputLocale()) ||
                    !displayLocale.equals(labels.displayLocale()) ||
                    !snapshot.currency().equals(labels.currency()) ||
                    snapshot.amountScale() != labels.amountScale() ||
                    snapshot.fields().size() != labels.fields().size())
                throw new IllegalArgumentException("FIN_RENDER_SNAPSHOT_PRESENTATION_MISMATCH");
            for (int i = 0; i < snapshot.fields().size(); i++) {
                var field = snapshot.fields().get(i);
                var label = labels.fields().get(i);
                if (!field.code().equals(label.fieldCode()) || field.type() != label.fieldType())
                    throw new IllegalArgumentException("FIN_RENDER_FIELD_SCHEMA_MISMATCH");
            }
            if (!"CONFIRMED_UNQUALIFIED_DRAFT".equals(snapshot.sourceStatus()))
                throw new IllegalArgumentException("FIN_RENDER_UNQUALIFIED_SOURCE_REQUIRED");
        }
        public UUID revision() { return snapshot.sourceRevisionId(); }
        public boolean evidenceOnly() {
            return "EVIDENCE_ONLY".equals(snapshot.captureMode());
        }
        public int provisionalLabels() {
            return (int) labels.fields().stream()
                    .filter(x -> x.status() == CatalogReportPresentationService.Status.PROVISIONAL)
                    .count();
        }
        public int missingLabels() { return labels.fields().size() - provisionalLabels(); }
    }

    public View prepare(FinancialReportSnapshotService.Snapshot snapshot,
                        String displayLocale, Instant generatedAt) {
        Objects.requireNonNull(snapshot, "snapshot");
        CatalogReportLocale.requireDisplay(displayLocale);
        var input = new CatalogXlsxRoundTripService.Context(
                snapshot.organizationId(), snapshot.economicEntityId(),
                snapshot.businessScopeId(), snapshot.projectCode(),
                snapshot.formCode(), snapshot.reportingBasis(),
                snapshot.periodType(), snapshot.periodCode(), snapshot.coverage(),
                snapshot.currency(), snapshot.amountScale(), snapshot.sourceInputLocale());
        return new View(snapshot, presentation.present(input, displayLocale),
                displayLocale, generatedAt);
    }
}
