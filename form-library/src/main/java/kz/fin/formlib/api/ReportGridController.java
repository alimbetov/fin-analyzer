package kz.fin.formlib.api;
import kz.fin.formlib.service.ReportGridService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/report-grids")
public class ReportGridController {
  private final ReportGridService service;
  public ReportGridController(ReportGridService service){this.service=service;}
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
      @RequestParam(defaultValue="ru") String locale){
    return service.grid(id,organizationId,timeAxis,locale,null);
  }
  @GetMapping("/{id}/revisions/{number}/grid")
  public ReportGridService.GridView oldRevision(@PathVariable UUID id,
      @PathVariable int number,@RequestParam UUID organizationId,
      @RequestParam(required=false) String timeAxis,
      @RequestParam(defaultValue="ru") String locale){
    return service.grid(id,organizationId,timeAxis,locale,number);
  }
  @PutMapping("/{id}/layout")
  public ReportGridService.LayoutReceipt changeLayout(@PathVariable UUID id,
      @RequestBody ReportGridService.LayoutRequest req){
    return service.changeLayout(id,req);
  }
}
