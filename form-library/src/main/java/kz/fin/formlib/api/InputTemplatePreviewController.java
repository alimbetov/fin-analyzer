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
      @RequestParam(required=false) List<String> periodCodes,
      @RequestParam(required=false) String startPeriod,
      @RequestParam(required=false) Integer periodCount,
      @RequestParam(required=false) String styleCode){
    boolean hasCodes=periodCodes!=null&&!periodCodes.isEmpty();
    boolean hasRange=startPeriod!=null||periodCount!=null;
    if(hasCodes==hasRange)
      throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"CHOOSE_PERIOD_CODES_OR_RANGE");
    if(hasCodes)return previews.preview(formCode,locale,periodType,periodCodes,styleCode);
    var batch=kz.fin.formlib.service.PeriodBatchPlanner.plan(
      periodType,startPeriod,periodCount);
    return previews.preview(formCode,locale,batch.periodType(),
      batch.periods().stream().map(p->p.periodCode()).toList(),styleCode);
  }
}
