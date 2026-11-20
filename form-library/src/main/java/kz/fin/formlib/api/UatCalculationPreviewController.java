package kz.fin.formlib.api;

import kz.fin.formlib.calc.ingest.UatCalculationPreview;
import kz.fin.formlib.calc.ingest.UatCalculationPreviewService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * DC-R2.5: disabled-by-default, strictly isolated UAT preview.
 * No financial S2, authorization by this endpoint, or production admission.
 * The existing /uat/calculations/f02/preview endpoint is kept compatible.
 */
@RestController
@RequestMapping("/api/v1/uat/calculations")
@ConditionalOnProperty(name="fin.calc.intake-uat.enabled",havingValue="true")
public class UatCalculationPreviewController {
  public record Request(String formCode,UUID organizationId,
                        UUID projectId,UUID exactRevisionId) {}
  private final UatCalculationPreviewService service;

  public UatCalculationPreviewController(UatCalculationPreviewService service){
    this.service=service;
  }

  @PostMapping("/preview")
  public UatCalculationPreview preview(@RequestBody Request r){
    if(r==null||r.formCode()==null||r.organizationId()==null
        ||r.projectId()==null||r.exactRevisionId()==null)
      throw new IllegalArgumentException("CALC_UAT_EXACT_SOURCE_REQUIRED");
    return service.preview(r.organizationId(),r.projectId(),r.exactRevisionId(),
        r.formCode());
  }
}
