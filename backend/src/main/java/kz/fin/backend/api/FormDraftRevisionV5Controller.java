package kz.fin.backend.api;

import kz.fin.backend.intake.FormDraftRevisionV5Service;
import kz.fin.backend.intake.FormSchemaV5;
import kz.fin.backend.security.FinanceAccessPolicy;
import kz.fin.backend.security.FinanceAccessPolicy.Permission;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant-authorized V5 draft endpoints. Project scope is checked in the service.
 * There is deliberately NO anonymous endpoint, admin review or ACTUAL publishing here.
 * The Excel server parser is a separate gate; manual values only in this slice.
 */
@RestController
@RequestMapping("/api/v1/financial/organizations/{organizationId}/projects-v5")
public class FormDraftRevisionV5Controller {
    private final FinanceAccessPolicy access;
    private final FormDraftRevisionV5Service service;

    public FormDraftRevisionV5Controller(FinanceAccessPolicy access,FormDraftRevisionV5Service service){
        this.access=access;this.service=service;
    }
    public record CreateProjectBody(UUID economicEntityId,UUID businessScopeId,
                                    String projectCode,String accountingCurrency) {}
    public record PreviewBody(String periodCode,String inputLocale,int amountScale,
                              List<FormSchemaV5.Input> cells) {}
    public record ConfirmBody(String confirmationDigest,int expectedRevision,String reason) {}

    @PostMapping
    public FormDraftRevisionV5Service.Project createProject(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @RequestBody CreateProjectBody body) {
        access.require(jwt,organizationId,Permission.FIN_SUBMIT);
        Objects.requireNonNull(body);
        return service.createProject(organizationId,body.economicEntityId(),body.businessScopeId(),
                body.projectCode(),body.accountingCurrency(),access.subject(jwt));
    }

    @PostMapping("/{projectId}/forms/F02/previews")
    public FormDraftRevisionV5Service.Preview previewManual(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @PathVariable UUID projectId,@RequestBody PreviewBody body) {
        access.require(jwt,organizationId,Permission.FIN_SUBMIT);
        Objects.requireNonNull(body);
        return service.previewManual(organizationId,projectId,body.periodCode(),
                body.inputLocale(),body.amountScale(),body.cells(),access.subject(jwt));
    }

    @PostMapping("/{projectId}/forms/F02/previews/{previewId}/confirm")
    public FormDraftRevisionV5Service.Confirmation confirm(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @PathVariable UUID projectId,@PathVariable UUID previewId,
            @RequestBody ConfirmBody body) {
        access.require(jwt,organizationId,Permission.FIN_SUBMIT);
        Objects.requireNonNull(body);
        return service.confirm(organizationId,projectId,previewId,body.confirmationDigest(),
                body.expectedRevision(),access.subject(jwt),body.reason());
    }

    @GetMapping("/{projectId}/forms/F02/history")
    public List<FormDraftRevisionV5Service.DraftHistory> history(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @PathVariable UUID projectId,@RequestParam String periodCode) {
        access.require(jwt,organizationId,Permission.FIN_READ);
        return service.history(organizationId,projectId,periodCode);
    }

    @GetMapping("/{projectId}/forms/F02/history/{revisionNumber}")
    public FormDraftRevisionV5Service.RevisionDetail revision(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @PathVariable UUID projectId,@PathVariable int revisionNumber,
            @RequestParam String periodCode) {
        access.require(jwt,organizationId,Permission.FIN_READ);
        return service.readRevision(organizationId,projectId,periodCode,revisionNumber);
    }

    @GetMapping("/{projectId}/forms/F02/uploads")
    public List<FormDraftRevisionV5Service.UploadHistory> uploads(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @PathVariable UUID projectId,@RequestParam String periodCode) {
        access.require(jwt,organizationId,Permission.FIN_READ);
        return service.uploads(organizationId,projectId,periodCode);
    }
}
