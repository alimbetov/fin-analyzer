package kz.fin.backend.api;

import kz.fin.backend.intake.CatalogIssuedTemplateService;
import kz.fin.backend.intake.CatalogXlsxRevisionService;
import kz.fin.backend.intake.CatalogXlsxRoundTripService;
import kz.fin.backend.intake.ReportingPeriodV5;
import kz.fin.backend.security.FinanceAccessPolicy;
import kz.fin.backend.security.FinanceAccessPolicy.Permission;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * FIN-BE-R1-X6: secured catalog XLSX V2 lifecycle for all 166 catalog codes.
 * Draft confirmation is NOT financial approval or ACTUAL publication.
 * The issued template ID is scoped to organization and project by the service.
 */
@RestController
@RequestMapping("/api/v1/financial/organizations/{organizationId}/projects/{projectId}/forms/{formCode}")
public class CatalogRevisionHttpController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final long MAX_XLSX_SIZE = 2_000_000L;
    private static final long MAX_DOCUMENT_SIZE = 10_000_000L;

    private final FinanceAccessPolicy access;
    private final CatalogIssuedTemplateService issued;
    private final CatalogXlsxRevisionService revisions;

    public CatalogRevisionHttpController(FinanceAccessPolicy access,
                                         CatalogIssuedTemplateService issued,
                                         CatalogXlsxRevisionService revisions) {
        this.access = access;
        this.issued = issued;
        this.revisions = revisions;
    }

    public record TemplateRequest(UUID economicEntityId, UUID businessScopeId,
                                  String projectCode, String reportingBasis,
                                  ReportingPeriodV5.Type periodType, String periodCode,
                                  ReportingPeriodV5.Coverage coverage, String currency,
                                  int amountScale, String inputLocale) {}

    public record ConfirmRequest(String normalizedFingerprint, int expectedRevision,
                                 String changeReason) {}

    private static CatalogXlsxRoundTripService.Context context(UUID organizationId,
            String formCode, TemplateRequest request) {
        if (request == null)
            throw new IllegalArgumentException("FIN_CATALOG_TEMPLATE_CONTEXT_REQUIRED");
        return new CatalogXlsxRoundTripService.Context(organizationId,
                request.economicEntityId(), request.businessScopeId(),
                request.projectCode(), formCode, request.reportingBasis(),
                request.periodType(), request.periodCode(), request.coverage(),
                request.currency(), request.amountScale(), request.inputLocale());
    }

    private static byte[] workbook(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > MAX_XLSX_SIZE)
            throw new IllegalArgumentException("CATALOG_XLSX_SIZE_LIMIT");
        return file.getBytes();
    }

    @PostMapping(value = "/templates", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> issue(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId, @PathVariable UUID projectId,
            @PathVariable String formCode, @RequestBody TemplateRequest body) {
        access.require(jwt, organizationId, Permission.FIN_SUBMIT);
        var template = issued.issue(projectId, context(organizationId, formCode, body));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-FIN-Issued-Template", template.templateId().toString())
                .headers(headers -> headers.setContentDisposition(
                        ContentDisposition.attachment().filename(
                                "FIN-" + formCode + "-V2.xlsx", StandardCharsets.UTF_8).build()))
                .contentType(XLSX).body(template.workbook());
    }

    @PostMapping(value = "/previews", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CatalogIssuedTemplateService.Preview> preview(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
            @PathVariable UUID projectId, @PathVariable String formCode,
            @RequestParam UUID templateId, @RequestPart("file") MultipartFile file)
            throws IOException {
        access.require(jwt, organizationId, Permission.FIN_SUBMIT);
        var result = issued.preview(organizationId, projectId, templateId, workbook(file));
        requireForm(formCode, result.parsed().context().formCode());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CatalogXlsxRoundTripService.Receipt> upload(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
            @PathVariable UUID projectId, @PathVariable String formCode,
            @RequestParam UUID templateId,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "original", required = false) MultipartFile original)
            throws IOException {
        access.require(jwt, organizationId, Permission.FIN_SUBMIT);
        var preview = issued.preview(organizationId, projectId, templateId, workbook(file));
        requireForm(formCode, preview.parsed().context().formCode());
        CatalogXlsxRoundTripService.OriginalDocument document = null;
        if (original != null) {
            if (original.isEmpty() || original.getSize() > MAX_DOCUMENT_SIZE)
                throw new IllegalArgumentException("FIN_CATALOG_ORIGINAL_SIZE_LIMIT");
            document = new CatalogXlsxRoundTripService.OriginalDocument(
                    Objects.requireNonNullElse(original.getOriginalFilename(), ""),
                    Objects.requireNonNullElse(original.getContentType(), ""),
                    original.getBytes());
        }
        var receipt = issued.upload(organizationId, projectId, templateId,
                workbook(file), document);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(receipt);
    }

    private static void requireForm(String routeCode, String issuedCode) {
        if (!routeCode.equals(issuedCode))
            throw new IllegalArgumentException("FIN_CATALOG_ISSUED_FORM_MISMATCH");
    }

    @PostMapping(value = "/drafts/{draftId}/confirm",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CatalogXlsxRevisionService.Confirmed> confirm(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
            @PathVariable UUID projectId, @PathVariable String formCode,
            @PathVariable UUID draftId, @RequestBody ConfirmRequest body) {
        access.require(jwt, organizationId, Permission.FIN_SUBMIT);
        if (body == null) throw new IllegalArgumentException("FIN_CATALOG_CONFIRM_INVALID_INPUT");
        // The service locks the slot and checks the entire project identity.
        // Form route mismatch is validated against the persisted draft before mutation.
        var result = revisions.confirm(organizationId, projectId, draftId,
                body.normalizedFingerprint(), body.expectedRevision(),
                access.subject(jwt), body.changeReason());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/slots/{slotId}/current")
    public ResponseEntity<CatalogXlsxRevisionService.RevisionView> current(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
            @PathVariable UUID projectId, @PathVariable String formCode,
            @PathVariable UUID slotId) {
        access.require(jwt, organizationId, Permission.FIN_READ);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(revisions.current(organizationId, projectId, slotId));
    }

    @GetMapping("/slots/{slotId}/revisions")
    public ResponseEntity<List<CatalogXlsxRevisionService.RevisionView>> history(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
            @PathVariable UUID projectId, @PathVariable String formCode,
            @PathVariable UUID slotId) {
        access.require(jwt, organizationId, Permission.FIN_READ);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(revisions.history(organizationId, projectId, slotId));
    }
}
