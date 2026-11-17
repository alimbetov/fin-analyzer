package kz.fin.formlib.api;
import kz.fin.formlib.service.ReportGridService;
import kz.fin.formlib.service.ReportFormatService;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/report-grids")
public class ReportGridController {
  private final ReportGridService service;
  private final ReportFormatService formats;
  public ReportGridController(ReportGridService service,ReportFormatService formats){
    this.service=service;this.formats=formats;
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
      @RequestParam(defaultValue="CODE") String periodLabelStyle){
    return service.grid(id,organizationId,timeAxis,locale,null,formatVersion,periodLabelStyle);
  }
  @GetMapping("/{id}/revisions/{number}/grid")
  public ReportGridService.GridView oldRevision(@PathVariable UUID id,
      @PathVariable int number,@RequestParam UUID organizationId,
      @RequestParam(required=false) String timeAxis,
      @RequestParam(defaultValue="ru") String locale,
      @RequestParam(required=false) Integer formatVersion,
      @RequestParam(defaultValue="CODE") String periodLabelStyle){
    return service.grid(id,organizationId,timeAxis,locale,number,formatVersion,periodLabelStyle);
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
}
