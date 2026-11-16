package kz.fin.backend.api;

import kz.fin.backend.intake.CatalogReportLocale;
import kz.fin.backend.intake.CatalogReportPresentationService;
import kz.fin.backend.intake.CatalogXlsxExportService;
import kz.fin.backend.intake.CatalogXlsxRoundTripService;
import kz.fin.backend.intake.FinancialReportSnapshotService;
import kz.fin.backend.intake.FinancialReportHtmlRenderer;
import kz.fin.backend.intake.FinancialReportPdfRenderer;
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
import java.time.Instant;
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
    private final FinancialReportHtmlRenderer html;
    private final FinancialReportPdfRenderer pdf;

    public CatalogXlsxExportController(FinanceAccessPolicy access,
                                       FinancialReportSnapshotService snapshots,
                                       CatalogReportPresentationService presentation,
                                       CatalogXlsxExportService xlsx,
                                       FinancialReportHtmlRenderer html,
                                       FinancialReportPdfRenderer pdf) {
        this.access = access;
        this.snapshots = snapshots;
        this.presentation = presentation;
        this.xlsx = xlsx;
        this.html = html;
        this.pdf = pdf;
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
        // All three downloadable formats must be tied to the exact revision
        // returned by /preview. "Latest" remains available for discovery in
        // the read-only preview, never independently during a file download.
        requirePinnedRead(request);
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
                .header("X-FIN-Content-Fingerprint-V2", report.contentFingerprintV2())
                .headers(headers -> headers.setContentDisposition(
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8).build()))
                .contentType(XLSX)
                .body(report.workbook());
    }

    /**
     * X3/X4 are read-only exports, gated by FIN_READ. Cross-format callers
     * must pin the revision returned by /preview; otherwise independent
     * "latest" requests could silently switch source between formats.
     * Rendering never confirms a draft or issues an XLSX template.
     */
    private static void requirePinnedRead(Request request) {
        if (request == null || request.sourceRevisionId() == null ||
                request.expectedRevisionId() != null) {
            throw new IllegalArgumentException("FIN_REPORT_EXACT_REVISION_REQUIRED");
        }
        CatalogReportLocale.requireDisplay(request.displayLocale());
    }

    @PostMapping(value = "/latest.html",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> exportHtml(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable String formCode,
            @RequestBody Request request) {
        access.require(jwt, organizationId, Permission.FIN_READ);
        requirePinnedRead(request);
        var snapshot = snapshots.load(query(organizationId, projectId, formCode, request));
        var result = html.render(snapshot, request.displayLocale(), Instant.now());
        String filename = "FIN-" + snapshot.formCode() + "-"
                + snapshot.periodCode() + "-" + request.displayLocale() + ".html";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", FinancialReportHtmlRenderer.CSP)
                .header("X-FIN-Source-Revision", result.sourceRevisionId().toString())
                .header("X-FIN-Content-SHA256", result.sha256())
                .header("X-FIN-Content-Fingerprint-V2", result.contentFingerprintV2())
                .header("X-FIN-Source-State", snapshot.sourceStatus())
                .headers(h -> h.setContentDisposition(ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build()))
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(result.utf8());
    }

    @PostMapping(value = "/latest.pdf",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable String formCode,
            @RequestBody Request request) {
        access.require(jwt, organizationId, Permission.FIN_READ);
        requirePinnedRead(request);
        var snapshot = snapshots.load(query(organizationId, projectId, formCode, request));
        var result = pdf.render(snapshot, request.displayLocale(), Instant.now());
        String filename = "FIN-" + snapshot.formCode() + "-"
                + snapshot.periodCode() + "-" + request.displayLocale() + ".pdf";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-FIN-Source-Revision", result.sourceRevisionId().toString())
                .header("X-FIN-Content-SHA256", result.sha256())
                .header("X-FIN-Content-Fingerprint-V2", result.contentFingerprintV2())
                .header("X-FIN-Source-State", snapshot.sourceStatus())
                .headers(h -> h.setContentDisposition(ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build()))
                .contentType(MediaType.APPLICATION_PDF)
                .body(result.bytes());
    }
}
