package kz.fin.formlib.api;

import kz.fin.formlib.service.CatalogManagementService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/**
 * LOCAL DEVELOPMENT ONLY. This API has no authentication/authorization.
 * Publication means catalog presentation activation, NEVER FIN S2 approval.
 */
@RestController
@RequestMapping("/api/v1/catalog-admin")
public class CatalogManagementController {
  private final CatalogManagementService service;
  public CatalogManagementController(CatalogManagementService service){this.service=service;}

  @PostMapping("/forms/{formCode}/drafts")
  public CatalogManagementService.EditReceipt begin(@PathVariable String formCode,
      @RequestBody CatalogManagementService.BeginRequest req){
    return service.begin(formCode,req);
  }

  @GetMapping("/drafts/{id}")
  public CatalogManagementService.DraftDetail detail(@PathVariable UUID id){
    return service.detail(id);
  }

  @GetMapping("/drafts/{id}/events")
  public List<CatalogManagementService.HistoryView> events(@PathVariable UUID id){
    return service.history(id);
  }

  @PutMapping("/drafts/{id}/translations/{locale}")
  public CatalogManagementService.EditReceipt translation(@PathVariable UUID id,
      @PathVariable String locale,@RequestBody CatalogManagementService.TranslationUpdate req){
    return service.updateTranslation(id,locale,req);
  }

  @PutMapping("/drafts/{id}/fields/order")
  public CatalogManagementService.EditReceipt reorderFields(@PathVariable UUID id,
      @RequestBody CatalogManagementService.FieldOrderUpdate req){
    return service.reorderFields(id,req);
  }

  @PutMapping("/drafts/{id}/sections/order")
  public CatalogManagementService.EditReceipt reorderSections(@PathVariable UUID id,
      @RequestBody CatalogManagementService.SectionOrderUpdate req){
    return service.reorderSections(id,req);
  }

  @PostMapping("/drafts/{id}/publish")
  public CatalogManagementService.EditReceipt publish(@PathVariable UUID id,
      @RequestBody CatalogManagementService.FinishRequest req){
    return service.publish(id,req);
  }

  @PostMapping("/drafts/{id}/discard")
  public CatalogManagementService.EditReceipt discard(@PathVariable UUID id,
      @RequestBody CatalogManagementService.FinishRequest req){
    return service.discard(id,req);
  }
}
