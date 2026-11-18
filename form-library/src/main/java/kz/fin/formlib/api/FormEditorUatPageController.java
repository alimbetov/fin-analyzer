package kz.fin.formlib.api;

import kz.fin.formlib.repository.FormInstanceRepository;
import kz.fin.formlib.repository.FormRevisionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.UUID;

/**
 * Temporary local-only UAT harness. No separate business write service.
 * Disabled by default; remove after acceptance.
 */
@Controller
@ConditionalOnProperty(prefix="fin.uat.editor",name="enabled",havingValue="true")
public class FormEditorUatPageController {
  private final FormInstanceRepository instances;
  private final FormRevisionRepository revisions;
  public FormEditorUatPageController(FormInstanceRepository instances,
      FormRevisionRepository revisions){
    this.instances=instances;this.revisions=revisions;
  }
  @GetMapping("/uat/form-editor")
  public String editor(Model model){
    model.addAttribute("pageTitle","FIN Form Library — Editor UAT");
    return "uat/form-editor";
  }
  @ResponseBody
  @GetMapping("/api/v1/uat-helper/drafts/{id}/version")
  public Map<String,UUID> version(@PathVariable UUID id,@RequestParam UUID organizationId){
    var instance=instances.findByIdAndOrganizationId(id,organizationId)
      .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"INSTANCE_NOT_FOUND"));
    if(instance.getCurrentRevisionId()==null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"REVISION_NOT_FOUND");
    var revision=revisions.findById(instance.getCurrentRevisionId()).orElseThrow();
    if(!id.equals(revision.getInstanceId()))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"REVISION_INSTANCE_INTEGRITY");
    return Map.of("formVersionId",revision.getFormVersionId());
  }
}
