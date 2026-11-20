package kz.fin.formlib.api;

import kz.fin.formlib.calc.ingest.F02IntakeUatPreviewService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * DC-R2 local UAT endpoint, disabled unless explicitly opted in.
 * No public production availability, financial S2 or persistent result write.
 * Do not enable outside an isolated test tenant / localhost environment.
 */
@RestController
@RequestMapping("/api/v1/uat/calculations/f02")
@ConditionalOnProperty(name="fin.calc.intake-uat.enabled",havingValue="true")
public class F02IntakeUatPreviewController {
  public record Request(UUID organizationId, UUID projectId, UUID exactRevisionId) {}
  private final F02IntakeUatPreviewService service;

  public F02IntakeUatPreviewController(F02IntakeUatPreviewService service){
    this.service=service;
  }

  @PostMapping("/preview")
  public F02IntakeUatPreviewService.Preview preview(@RequestBody Request r) {
    if(r==null || r.organizationId()==null||r.projectId()==null
        ||r.exactRevisionId()==null)
      throw new IllegalArgumentException("F02_INTAKE_EXACT_REVISION_REQUIRED");
    return service.preview(r.organizationId(),r.projectId(),r.exactRevisionId());
  }
}
