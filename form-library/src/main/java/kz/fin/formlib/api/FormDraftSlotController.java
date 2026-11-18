package kz.fin.formlib.api;

import kz.fin.formlib.service.FormDraftService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Local UAT recovery of an existing financial report by its exact scope.
 * A catalog selection or an empty instanceId never authorizes an implicit
 * modification of an existing report.
 */
@RestController
@RequestMapping("/api/v1/drafts")
public class FormDraftSlotController {
  private final FormDraftService drafts;

  public FormDraftSlotController(FormDraftService drafts) {
    this.drafts=drafts;
  }

  @GetMapping("/catalog")
  public List<FormDraftService.SavedReport> listSavedReports(
      @RequestParam UUID organizationId,
      @RequestParam UUID economicEntityId,
      @RequestParam UUID businessScopeId,
      @RequestParam UUID projectId,
      @RequestParam String formCode) {
    return drafts.listSavedReports(organizationId,economicEntityId,businessScopeId,projectId,formCode);
  }

  @GetMapping("/slot")
  public FormDraftService.DraftView readSlot(
      @RequestParam UUID organizationId,
      @RequestParam UUID economicEntityId,
      @RequestParam UUID businessScopeId,
      @RequestParam UUID projectId,
      @RequestParam String formCode,
      @RequestParam String periodType,
      @RequestParam String periodCode,
      @RequestParam String coverage,
      @RequestParam String currency,
      @RequestParam String reportingBasis,
      @RequestParam String dataSpace) {
    return drafts.readExactSlot(organizationId,economicEntityId,businessScopeId,
        projectId,formCode,periodType,periodCode,coverage,currency,
        reportingBasis,dataSpace);
  }
}
