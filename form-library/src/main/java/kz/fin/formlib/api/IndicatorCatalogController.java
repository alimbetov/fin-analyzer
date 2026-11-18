package kz.fin.formlib.api;

import kz.fin.formlib.service.IndicatorNavigatorCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Public catalog metadata only: no tenant IDs, no FactSet, no calculations.
 * Draft mappings are surfaced with their explicit unqualified state.
 */
@RestController
@RequestMapping("/api/v1/indicator-catalog")
public class IndicatorCatalogController {
  private final IndicatorNavigatorCatalog catalog;

  public IndicatorCatalogController(IndicatorNavigatorCatalog catalog){
    this.catalog=catalog;
  }

  @GetMapping("/goals")
  public List<IndicatorNavigatorCatalog.Goal> goals(
      @RequestParam(defaultValue="ru") String locale){
    return catalog.goals(validLocale(locale));
  }

  @GetMapping("/goals/{code}")
  public IndicatorNavigatorCatalog.Goal goal(@PathVariable String code,
      @RequestParam(defaultValue="ru") String locale){
    return catalog.goal(code,validLocale(locale))
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"GOAL_NOT_FOUND"));
  }

  @GetMapping("/indicators")
  public List<IndicatorNavigatorCatalog.Indicator> indicators(
      @RequestParam(defaultValue="ru") String locale){
    return catalog.indicators(validLocale(locale));
  }

  @GetMapping("/indicators/{code}")
  public IndicatorNavigatorCatalog.Indicator indicator(@PathVariable String code,
      @RequestParam(defaultValue="ru") String locale){
    return catalog.indicator(code,validLocale(locale))
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"INDICATOR_NOT_FOUND"));
  }

  @GetMapping("/forms/{formCode}/used-by")
  public IndicatorNavigatorCatalog.FormUsage formUsage(@PathVariable String formCode){
    return catalog.formUsage(formCode)
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"FORM_NOT_FOUND"));
  }

  private static String validLocale(String locale){
    if(!List.of("ru","kk","en","zh-Hans").contains(locale))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UNSUPPORTED_LOCALE");
    return locale;
  }
}
