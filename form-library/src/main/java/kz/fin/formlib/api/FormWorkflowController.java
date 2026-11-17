package kz.fin.formlib.api;
import kz.fin.formlib.service.FormWorkflowService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
/** Local-only intake workflow. Do not confuse with catalog or FIN S2 approval. */
@RestController
@RequestMapping("/api/v1/drafts/{instanceId}")
public class FormWorkflowController {
 private final FormWorkflowService service;
 public FormWorkflowController(FormWorkflowService service){this.service=service;}
 @GetMapping("/status")
 public FormWorkflowService.StatusView current(@PathVariable UUID instanceId,
     @RequestParam UUID organizationId){return service.current(instanceId,organizationId);}
 @GetMapping("/status/events")
 public List<FormWorkflowService.EventView> history(@PathVariable UUID instanceId,
     @RequestParam UUID organizationId){return service.history(instanceId,organizationId);}
 @PostMapping("/status-transitions")
 public FormWorkflowService.StatusView transition(@PathVariable UUID instanceId,
     @RequestBody FormWorkflowService.TransitionRequest req){
     return service.transition(instanceId,req);
 }
}
