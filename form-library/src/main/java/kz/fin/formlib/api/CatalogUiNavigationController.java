package kz.fin.formlib.api;

import kz.fin.formlib.service.CatalogUiNavigationService;
import org.springframework.web.bind.annotation.*;
import kz.fin.formlib.service.CatalogPaging;

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
  @GetMapping("/coverage")
  public CatalogUiNavigationService.TranslationCoverage coverage(
      @RequestParam(defaultValue="ru") String locale){
    return service.coverage(locale);
  }
  @GetMapping("/translation-gaps")
  public CatalogPaging.Page<CatalogUiNavigationService.TranslationGap> translationGaps(
      @RequestParam(defaultValue="ru") String locale,
      @RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="24") int size){
    return service.translationGaps(locale,page,size);
  }
  @GetMapping("/categories/page")
  public CatalogPaging.Page<CatalogUiNavigationService.CategoryTile> categoriesPage(
      @RequestParam(defaultValue="ru") String locale,
      @RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="24") int size,
      @RequestParam(required=false) String q,
      @RequestParam(defaultValue="CARDS") String viewMode,
      @RequestParam(defaultValue="RELEVANCE") String sort){
    return service.pageCategories(locale,page,size,q,viewMode,sort);
  }
  @GetMapping("/forms/page")
  public CatalogPaging.Page<CatalogUiNavigationService.FormTile> allFormsPage(
      @RequestParam(required=false) String categoryCode,
      @RequestParam(defaultValue="ru") String locale,
      @RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="24") int size,
      @RequestParam(required=false) String q,
      @RequestParam(defaultValue="CARDS") String viewMode,
      @RequestParam(defaultValue="RELEVANCE") String sort){
    return service.pageForms(categoryCode,locale,page,size,q,viewMode,sort);
  }
  @GetMapping("/categories/{categoryCode}/forms/page")
  public CatalogPaging.Page<CatalogUiNavigationService.FormTile> categoryFormsPage(
      @PathVariable String categoryCode,
      @RequestParam(defaultValue="ru") String locale,
      @RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="24") int size,
      @RequestParam(required=false) String q,
      @RequestParam(defaultValue="CARDS") String viewMode,
      @RequestParam(defaultValue="RELEVANCE") String sort){
    return service.pageForms(categoryCode,locale,page,size,q,viewMode,sort);
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
