package kz.fin.formlib.api;

import kz.fin.formlib.service.FormNavigationService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Read-only catalog navigation for multi-sector companies.
 * No auth or entitlement decisions are made here.
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class FormNavigationController {
  private final FormNavigationService service;
  public FormNavigationController(FormNavigationService service){this.service=service;}

  @GetMapping("/navigation")
  public FormNavigationService.NavigationView navigation(
      @RequestParam(defaultValue="ru") String locale,
      @RequestParam(required=false) List<String> sectorCodes,
      @RequestParam(required=false) String analysisPack,
      @RequestParam(required=false) String useCaseCode){
    return service.navigation(locale,sectorCodes,analysisPack,useCaseCode);
  }
}
