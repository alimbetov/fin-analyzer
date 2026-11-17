package kz.fin.formlib.api;

import kz.fin.formlib.service.CatalogUiNavigationService;
import org.springframework.web.bind.annotation.*;

/**
 * R11.A -- read-only navigation for the Catalog -> Form -> Period -> Grid flow.
 * Company access, payments, sharing and archive are separate trusted services.
 */
@RestController
@RequestMapping("/api/v1/catalog/ui")
public class CatalogUiNavigationController {
  private final CatalogUiNavigationService service;
  public CatalogUiNavigationController(CatalogUiNavigationService service){
    this.service=service;
  }
  @GetMapping("/categories")
  public CatalogUiNavigationService.CategoriesView categories(
      @RequestParam(defaultValue="ru") String locale){
    return service.listCategories(locale);
  }
  @GetMapping("/categories/{categoryCode}/forms")
  public CatalogUiNavigationService.CategoryFormsView forms(
      @PathVariable String categoryCode,
      @RequestParam(defaultValue="ru") String locale){
    return service.listForms(categoryCode,locale);
  }
  @GetMapping("/forms/{formCode}/entry")
  public CatalogUiNavigationService.LaunchView entry(
      @PathVariable String formCode,
      @RequestParam(defaultValue="ru") String locale){
    return service.launch(formCode,locale);
  }
}
