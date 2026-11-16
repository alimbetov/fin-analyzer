package kz.fin.backend.api;

import kz.fin.backend.intake.CatalogReportLocale;
import kz.fin.backend.intake.CatalogReportPresentationService;
import kz.fin.backend.intake.CatalogXlsxExportService;
import kz.fin.backend.intake.CatalogXlsxRoundTripService;
import kz.fin.backend.intake.FinancialReportSnapshotService;
import kz.fin.backend.intake.ReportingPeriodV5;
import kz.fin.backend.security.FinanceAccessPolicy;
import kz.fin.backend.security.FinanceAccessPolicy.Permission;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * X2: an authorized read-only preview and an explicit, auditable XLSX issuance
 * command. This does NOT publish FACT/ACTUAL or approve any source methodology.
 */
@RestController
@RequestMapping("/api/v1/financial/organizations/{organizationId}"
        + "/projects/{projectId}/forms/{formCode}/exports")
public class CatalogXlsxExportController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final FinanceAccessPolicy access;
    private final FinancialReportSnapshotService snapshots;
    private final CatalogReportPresentationService presentation;
    private final CatalogXlsxExportService xlsx;

    public CatalogXlsxExportController(FinanceAccessPolicy access,
                                       FinancialReportSnapshotService snapshots,
                                       CatalogReportPresentationService presentation,
                                       CatalogXlsxExportService xlsx) {
        this.access = access;
        this.snapshots = snapshots;
        this.presentation = presentation;
        this.xlsx = xlsx;
    }

    public record Request(ReportingPeriodV5.Type periodType, String periodCode,
                          String reportingBasis, ReportingPeriodV5.Coverage coverage,
                          String currency, Integer amountScale, String displayLocale,
                          UUID sourceRevisionId, UUID expectedRevisionId) {}

    public record Preview(UUID sourceRevisionId, int revisionNumber, String formCode,
                          String periodType, String periodCode, String currency,
                          int amountScale, String sourceInputLocale, String displayLocale,
                          String sourceStatus, String normalizedFingerprint,
                          String schemaFingerprint, int provisionalLabels,
                          int missingLabels) {}

    private static FinancialReportSnapshotService.Query query(
            UUID organizationId, UUID projectId, String formCode, Request r) {
        if (r == null)
            throw new IllegalArgumentException("FIN_EXPORT_INVALID_CONTEXT");
        return new FinancialReportSnapshotService.Query(
                organizationId, projectId, formCode, r.periodType(), r.periodCode(),
                r.reportingBasis(), r.coverage(), r.currency(), r.amountScale(),
                r.sourceRevisionId(), r.expectedRevisionId());
    }

    @GetMapping("/preview")
    public ResponseEntity<Preview> preview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId, @PathVariable UUID projectId,
            @PathVariable String formCode,
            @RequestParam ReportingPeriodV5.Type periodType,
            @RequestParam String periodCode,
            @RequestParam(required = false) String reportingBasis,
            @RequestParam(required = false) ReportingPeriodV5.Coverage coverage,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) Integer amountScale,
            @RequestParam(defaultValue = "ru") String displayLocale,
            @RequestParam(required = false) UUID sourceRevisionId,
            @RequestParam(required = false) UUID expectedRevisionId) {
        access.require(jwt, organizationId, Permission.FIN_READ);
        CatalogReportLocale.requireDisplay(displayLocale);
        var source = snapshots.load(query(organizationId, projectId, formCode,
                new Request(periodType, periodCode, reportingBasis, coverage, currency,
                        amountScale, displayLocale, sourceRevisionId, expectedRevisionId)));
        var context = new CatalogXlsxRoundTripService.Context(
                source.organizationId(), source.economicEntityId(), source.businessScopeId(),
                source.projectCode(), source.formCode(), source.reportingBasis(),
                source.periodType(), source.periodCode(), source.coverage(),
                source.currency(), source.amountScale(), source.sourceInputLocale());
        var labels = presentation.present(context, displayLocale).fields();
        int provisional = (int) labels.stream().filter(f ->
                f.status() == CatalogReportPresentationService.Status.PROVISIONAL).count();
        var response = new Preview(source.sourceRevisionId(), source.revisionNumber(),
                source.formCode(), source.periodType().name(), source.periodCode(),
                source.currency(), source.amountScale(), source.sourceInputLocale(),
                displayLocale, source.sourceStatus(), source.normalizedFingerprint(),
                source.schemaFingerprint(), provisional, labels.size() - provisional);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(response);
    }

    @PostMapping(value = "/latest.xlsx",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId, @PathVariable UUID projectId,
            @PathVariable String formCode, @RequestBody Request request) {
        access.require(jwt, organizationId, Permission.FIN_READ);
        access.require(jwt, organizationId, Permission.FIN_SUBMIT);
        if (request == null)
            throw new IllegalArgumentException("FIN_EXPORT_INVALID_CONTEXT");
        CatalogReportLocale.requireDisplay(request.displayLocale());
        var report = xlsx.export(query(organizationId, projectId, formCode, request),
                request.displayLocale(), access.subject(jwt));
        String filename = "FIN-" + report.formCode() + "-" + report.periodCode()
                + "-" + report.displayLocale() + ".xlsx";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-FIN-Source-Revision", report.sourceRevisionId().toString())
                .header("X-FIN-Export-ID", report.exportId().toString())
                .header("X-FIN-Issued-Template", report.issuedTemplateId().toString())
                .header("X-FIN-Workbook-SHA256", report.workbookSha256())
                .headers(headers -> headers.setContentDisposition(
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8).build()))
                .contentType(XLSX)
                .body(report.workbook());
    }
}
