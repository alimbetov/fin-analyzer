package kz.fin.backend.api;

import kz.fin.backend.persistence.ReviewedFinancialIntakeService;
import kz.fin.backend.persistence.TrustedFinancialFormMapper;
import kz.fin.backend.persistence.PilotXlsxTemplateService;
import kz.fin.backend.persistence.TrustedCrossFormDiagnosticService;
import java.time.YearMonth;
import kz.fin.backend.security.FinanceAccessPolicy;
import kz.fin.backend.security.FinanceAccessPolicy.Permission;
import kz.fin.backend.domain.EconomicKernel;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

import java.util.*;

/**
 * P0 HTTP entry: no user-supplied trust flags, approver names or user org claims.
 * A real JWT with org-specific permissions is required for every operation.
 *
 * XLSX parsing and ACTUAL cross-form method approval are separate release gates.
 */
@RestController
@RequestMapping("/api/v1/financial/organizations/{organizationId}")
public class FinancialIntakeController {
    private final FinanceAccessPolicy access;
    private final ReviewedFinancialIntakeService workflow;
    private final PilotXlsxTemplateService xlsx;
    private final TrustedCrossFormDiagnosticService diagnostic;

    public FinancialIntakeController(FinanceAccessPolicy access,
                                     ReviewedFinancialIntakeService workflow,
                                     PilotXlsxTemplateService xlsx,
                                     TrustedCrossFormDiagnosticService diagnostic){
        this.access=access;this.workflow=workflow;this.xlsx=xlsx;this.diagnostic=diagnostic;
    }
    public record SubmitBody(UUID economicEntityId,UUID businessScopeId,String formCode,
                             String reportingBasis,String currencyCode,int amountScale,
                             List<TrustedFinancialFormMapper.Cell> cells) {}
    public record ReviewBody(boolean approved,String reason) {}
    public record PublishBody(UUID economicEntityId,UUID businessScopeId,
                              EconomicKernel.ReportingBasis reportingBasis,
                              List<UUID> approvedSourceIds,UUID expectedCurrentRevisionId) {}

    @PostMapping("/submissions")
    public ReviewedFinancialIntakeService.Submitted submit(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @RequestBody SubmitBody body) {
        access.require(jwt,organizationId,Permission.FIN_SUBMIT);
        Objects.requireNonNull(body);
        return workflow.submitManual(access.subject(jwt),
                new ReviewedFinancialIntakeService.IntakeRequest(
                        organizationId,body.economicEntityId(),body.businessScopeId(),
                        body.formCode(),body.reportingBasis(),body.currencyCode(),
                        body.amountScale(),body.cells()));
    }
    @GetMapping(value="/templates/{formCode}",
                produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> template(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId,@PathVariable String formCode,
            @RequestParam UUID economicEntityId,@RequestParam UUID businessScopeId,
            @RequestParam(defaultValue="MANAGEMENT") String reportingBasis,
            @RequestParam(defaultValue="KZT") String currencyCode,
            @RequestParam(defaultValue="1") int amountScale) {
        access.require(jwt,organizationId,Permission.FIN_SUBMIT);
        byte[] file=xlsx.template(new PilotXlsxTemplateService.Context(
                organizationId,economicEntityId,businessScopeId,formCode,
                reportingBasis,currencyCode,amountScale));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"FIN-"+formCode+"-FIN-PILOT-V1.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @PostMapping(value="/submissions/xlsx",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReviewedFinancialIntakeService.Submitted submitXlsx(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @RequestParam UUID economicEntityId,@RequestParam UUID businessScopeId,
            @RequestParam String formCode,@RequestParam(defaultValue="MANAGEMENT") String reportingBasis,
            @RequestParam(defaultValue="KZT") String currencyCode,
            @RequestParam(defaultValue="1") int amountScale,
            @RequestPart("file") MultipartFile file) throws IOException {
        access.require(jwt,organizationId,Permission.FIN_SUBMIT);
        if(file==null || file.isEmpty() || file.getSize()>2_000_000)
            throw new IllegalArgumentException("XLSX_SIZE_LIMIT");
        if(file.getOriginalFilename()==null ||
                !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw new IllegalArgumentException("XLSX_EXTENSION_REQUIRED");
        return workflow.submitXlsx(access.subject(jwt),
                new PilotXlsxTemplateService.Context(organizationId,economicEntityId,
                        businessScopeId,formCode,reportingBasis,currencyCode,amountScale),
                file.getBytes());
    }

    @GetMapping(value="/submissions/{submissionId}/original",
                produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> original(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId,@PathVariable UUID submissionId) {
        access.require(jwt,organizationId,Permission.FIN_READ);
        byte[] bytes=workflow.readRawXlsx(organizationId,submissionId);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"FIN-reviewed-original-"+submissionId+".xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @GetMapping("/submissions/{submissionId}")
    public ReviewedFinancialIntakeService.SubmissionInfo read(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @PathVariable UUID submissionId) {
        access.require(jwt,organizationId,Permission.FIN_READ);
        return workflow.read(organizationId,submissionId);
    }
    @PostMapping("/submissions/{submissionId}/review")
    public ReviewedFinancialIntakeService.Reviewed review(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @PathVariable UUID submissionId,@RequestBody ReviewBody body) {
        access.require(jwt,organizationId,Permission.FIN_REVIEW);
        Objects.requireNonNull(body);
        return workflow.review(access.subject(jwt),organizationId,submissionId,
                body.approved(),body.reason());
    }
    @PostMapping("/fact-sets/{revisionId}/assessments/{month}")
    public TrustedCrossFormDiagnosticService.Stored provisionalActualAssessment(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @PathVariable UUID revisionId,@PathVariable String month) {
        access.require(jwt,organizationId,Permission.FIN_ANALYZE);
        YearMonth evaluatedMonth;
        try{evaluatedMonth=YearMonth.parse(month);}
        catch(RuntimeException e){throw new IllegalArgumentException("INVALID_DIAGNOSTIC_MONTH",e);}
        var receipt=diagnostic.assessAndPublishProvisionalActual(
                organizationId,revisionId,evaluatedMonth);
        return diagnostic.loadProvisionalActual(organizationId,receipt.id());
    }

    @GetMapping("/assessments/{assessmentId}")
    public TrustedCrossFormDiagnosticService.Stored readProvisionalAssessment(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @PathVariable UUID assessmentId) {
        access.require(jwt,organizationId,Permission.FIN_READ);
        return diagnostic.loadProvisionalActual(organizationId,assessmentId);
    }

    @PostMapping("/fact-sets")
    public kz.fin.backend.persistence.FactSetPublicationService.Publication publish(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @RequestBody PublishBody body) {
        access.require(jwt,organizationId,Permission.FIN_PUBLISH);
        Objects.requireNonNull(body);
        return workflow.publishReviewedActual(new ReviewedFinancialIntakeService.PublishRequest(
                organizationId,body.economicEntityId(),body.businessScopeId(),
                body.reportingBasis(),body.approvedSourceIds(),body.expectedCurrentRevisionId()));
    }
}
