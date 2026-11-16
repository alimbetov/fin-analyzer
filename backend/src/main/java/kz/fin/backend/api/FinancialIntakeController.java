package kz.fin.backend.api;

import kz.fin.backend.persistence.ReviewedFinancialIntakeService;
import kz.fin.backend.persistence.TrustedFinancialFormMapper;
import kz.fin.backend.security.FinanceAccessPolicy;
import kz.fin.backend.security.FinanceAccessPolicy.Permission;
import kz.fin.backend.domain.EconomicKernel;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

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

    public FinancialIntakeController(FinanceAccessPolicy access,
                                     ReviewedFinancialIntakeService workflow){
        this.access=access;this.workflow=workflow;
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
