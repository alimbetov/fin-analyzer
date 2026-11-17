package kz.fin.formlib.api;

import kz.fin.formlib.service.InputTemplatePreviewService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Read-only form preview. No data, project, actor or style writes.
 * Financial intake remains owned by fin_intake services.
 */
@RestController
@RequestMapping("/api/v1/templates")
public class InputTemplatePreviewController {
  private final InputTemplatePreviewService previews;
  public InputTemplatePreviewController(InputTemplatePreviewService previews){
    this.previews=previews;
  }
  @GetMapping("/{formCode}/preview")
  public InputTemplatePreviewService.TemplatePreview preview(@PathVariable String formCode,
      @RequestParam(defaultValue="ru") String locale,
      @RequestParam String periodType,
      @RequestParam List<String> periodCodes,
      @RequestParam(required=false) String styleCode){
    return previews.preview(formCode,locale,periodType,periodCodes,styleCode);
  }
}
