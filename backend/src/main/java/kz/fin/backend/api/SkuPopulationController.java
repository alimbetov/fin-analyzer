package kz.fin.backend.api;

import kz.fin.backend.persistence.ReviewedSkuIntakeService;
import kz.fin.backend.security.FinanceAccessPolicy;
import kz.fin.backend.security.FinanceAccessPolicy.Permission;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * Review is restricted to FIN_REVIEW and organization-scoped JWT grants.
 * Raw source bytes are retained and can be downloaded only with FIN_READ.
 * Client-provided organization/actor/approval flags are never used as authority.
 */
@RestController
@RequestMapping("/api/v1/financial/organizations/{organizationId}/sku-populations")
public final class SkuPopulationController {
    private final FinanceAccessPolicy access;
    private final ReviewedSkuIntakeService intake;

    public SkuPopulationController(FinanceAccessPolicy access,ReviewedSkuIntakeService intake){
        this.access=access;this.intake=intake;
    }
    public record ReviewBody(boolean approved,String reason) {}

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReviewedSkuIntakeService.Receipt upload(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @RequestParam UUID economicEntityId,
            @RequestParam UUID businessScopeId,
            @RequestParam UUID factSetRevisionId,
            @RequestParam String sourceSystem,
            @RequestPart("file") MultipartFile file) throws IOException {
        access.require(jwt,organizationId,Permission.FIN_SUBMIT);
        Objects.requireNonNull(file,"file");
        if(file.getSize()<2 || file.getSize()>2_097_152)
            throw new IllegalArgumentException("SKU_SOURCE_FILE_SIZE_INVALID");
        return intake.submit(access.subject(jwt),
                new ReviewedSkuIntakeService.Upload(organizationId,economicEntityId,
                        businessScopeId,factSetRevisionId,sourceSystem,file.getBytes()));
    }

    @PostMapping("/{populationId}/review")
    public ReviewedSkuIntakeService.Review review(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @PathVariable UUID populationId,@RequestBody ReviewBody body) {
        access.require(jwt,organizationId,Permission.FIN_REVIEW);
        Objects.requireNonNull(body,"review");
        return intake.review(access.subject(jwt),organizationId,populationId,
                body.approved(),body.reason());
    }

    @GetMapping("/{populationId}")
    public ReviewedSkuIntakeService.Info detail(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @PathVariable UUID populationId) {
        access.require(jwt,organizationId,Permission.FIN_READ);
        return intake.read(organizationId,populationId);
    }

    @GetMapping(value="/{populationId}/original",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> original(
            @AuthenticationPrincipal Jwt jwt,@PathVariable UUID organizationId,
            @PathVariable UUID populationId) {
        access.require(jwt,organizationId,Permission.FIN_READ);
        byte[] content=intake.readOriginal(organizationId,populationId);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"sku-population-"+populationId+".json\"")
                .contentType(MediaType.APPLICATION_JSON).body(content);
    }
}
