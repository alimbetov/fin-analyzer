package kz.fin.formlib.api;

import kz.fin.formlib.service.FormUseCaseCatalogService;
import kz.fin.formlib.service.CatalogPaging;
import java.util.ArrayList;
import java.util.Comparator;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Read-only purpose-based navigation; not a suitability or approval decision. */
@RestController
@RequestMapping("/api/v1/catalog/use-cases")
public class FormUseCaseController {
  private final FormUseCaseCatalogService service;
  public FormUseCaseController(FormUseCaseCatalogService service){this.service=service;}
  @GetMapping
  public List<FormUseCaseCatalogService.UseCaseSummary> list(){return service.all();}
  @GetMapping("/{useCaseCode}/forms/page")
  public CatalogPaging.Page<FormUseCaseCatalogService.SuggestedForm> formsPage(
      @PathVariable String useCaseCode,
      @RequestParam(defaultValue="ru") String locale,
      @RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="24") int size,
      @RequestParam(required=false) String q,
      @RequestParam(defaultValue="CARDS") String viewMode,
      @RequestParam(defaultValue="RELEVANCE") String sort) {
    String search=CatalogPaging.normalizedSearch(q);
    String order=CatalogPaging.sortMode(sort);
    var filtered=new ArrayList<>(service.details(useCaseCode,locale).forms().stream()
      .filter(f->CatalogPaging.matches(search,f.formCode(),f.title())).toList());
    if("CODE".equals(order))filtered.sort(Comparator.comparing(
      FormUseCaseCatalogService.SuggestedForm::formCode));
    if("TITLE".equals(order))filtered.sort(Comparator.comparing(
      (FormUseCaseCatalogService.SuggestedForm f)->f.title()==null?"":f.title(),
      String.CASE_INSENSITIVE_ORDER).thenComparing(
        FormUseCaseCatalogService.SuggestedForm::formCode));
    return CatalogPaging.page(filtered,page,size,viewMode,order);
  }
  @GetMapping("/{useCaseCode}/forms")
  public FormUseCaseCatalogService.UseCaseDetails forms(@PathVariable String useCaseCode,
      @RequestParam(defaultValue="ru") String locale){
    return service.details(useCaseCode,locale);
  }
}
