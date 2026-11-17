package kz.fin.formlib.api;

import kz.fin.formlib.service.FormUseCaseCatalogService;
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
  @GetMapping("/{useCaseCode}/forms")
  public FormUseCaseCatalogService.UseCaseDetails forms(@PathVariable String useCaseCode,
      @RequestParam(defaultValue="ru") String locale){
    return service.details(useCaseCode,locale);
  }
}
