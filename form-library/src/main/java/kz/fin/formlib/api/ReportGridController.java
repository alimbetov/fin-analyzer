package kz.fin.formlib.api;
import kz.fin.formlib.service.ReportGridService;
import kz.fin.formlib.service.ReportFormatService;
import kz.fin.formlib.service.ReportInputValidationService;
import kz.fin.formlib.service.ReportDerivedTotalService;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/report-grids")
public class ReportGridController {
  private final ReportGridService service;
  private final ReportFormatService formats;
  private final ReportInputValidationService validation;
  private final ReportDerivedTotalService derived;
  public ReportGridController(ReportGridService service,ReportFormatService formats,
      ReportInputValidationService validation,ReportDerivedTotalService derived){
    this.service=service;this.formats=formats;this.validation=validation;this.derived=derived;
  }
  @PostMapping
  public ReportGridService.CreateReceipt create(@RequestBody ReportGridService.CreateReport req){
    return service.create(req);
  }
  @PostMapping("/{id}/revisions")
  public ReportGridService.SaveReceipt save(@PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody ReportGridService.SaveReport req){
    return service.save(id,req,idempotencyKey);
  }
  @GetMapping("/{id}/grid")
  public ReportGridService.GridView latest(@PathVariable UUID id,
      @RequestParam UUID organizationId,
      @RequestParam(required=false) String timeAxis,
      @RequestParam(defaultValue="ru") String locale,
      @RequestParam(required=false) Integer formatVersion,
      @RequestParam(defaultValue="CODE") String periodLabelStyle,
      @RequestParam(defaultValue="VIEW") String viewMode,
      @RequestParam(required=false) Integer derivedVersion){
    return service.grid(id,organizationId,timeAxis,locale,null,formatVersion,periodLabelStyle,
      viewMode,derivedVersion);
  }
  @GetMapping("/{id}/revisions/{number}/grid")
  public ReportGridService.GridView oldRevision(@PathVariable UUID id,
      @PathVariable int number,@RequestParam UUID organizationId,
      @RequestParam(required=false) String timeAxis,
      @RequestParam(defaultValue="ru") String locale,
      @RequestParam(required=false) Integer formatVersion,
      @RequestParam(defaultValue="CODE") String periodLabelStyle,
      @RequestParam(defaultValue="VIEW") String viewMode,
      @RequestParam(required=false) Integer derivedVersion){
    return service.grid(id,organizationId,timeAxis,locale,number,formatVersion,periodLabelStyle,
      viewMode,derivedVersion);
  }
  @PutMapping("/{id}/layout")
  public ReportGridService.LayoutReceipt changeLayout(@PathVariable UUID id,
      @RequestBody ReportGridService.LayoutRequest req){
    return service.changeLayout(id,req);
  }

  @GetMapping("/{id}/formats")
  public List<ReportFormatService.FormatView> formats(@PathVariable UUID id,
      @RequestParam UUID organizationId,@RequestParam(required=false) Integer formatVersion){
    return formats.getFormats(id,organizationId,formatVersion);
  }

  @PutMapping("/{id}/formats")
  public ReportFormatService.UpdateFormatReceipt updateFormats(@PathVariable UUID id,
      @RequestBody ReportFormatService.UpdateFormatRequest request){
    return formats.update(id,request);
  }

  @GetMapping("/{id}/validation")
  public List<ReportInputValidationService.RuleView> rules(@PathVariable UUID id,
      @RequestParam UUID organizationId,@RequestParam(required=false) Integer validationVersion) {
    return validation.getRules(id,organizationId,validationVersion);
  }

  @PutMapping("/{id}/validation")
  public ReportInputValidationService.UpdateReceipt updateRules(@PathVariable UUID id,
      @RequestBody ReportInputValidationService.UpdateRequest request) {
    return validation.update(id,request);
  }

  public record PreviewRequest(UUID organizationId,List<ReportGridService.CellInput> cells) {}
  @PostMapping("/{id}/validate")
  public ReportInputValidationService.ValidationResult previewValidation(@PathVariable UUID id,
      @RequestBody PreviewRequest request) {
    return validation.validateSnapshot(id,request.organizationId(),request.cells());
  }

  @GetMapping("/{id}/derived-rules")
  public List<ReportDerivedTotalService.RuleView> derivedRules(@PathVariable UUID id,
      @RequestParam UUID organizationId,@RequestParam(required=false) Integer derivedVersion){
    return derived.getRules(id,organizationId,derivedVersion);
  }

  @PutMapping("/{id}/derived-rules")
  public ReportDerivedTotalService.UpdateReceipt updateDerivedRules(@PathVariable UUID id,
      @RequestBody ReportDerivedTotalService.UpdateRequest request){
    return derived.update(id,request);
  }
}
