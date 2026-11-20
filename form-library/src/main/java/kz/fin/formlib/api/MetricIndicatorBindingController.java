package kz.fin.formlib.api;

import kz.fin.formlib.calc.graph.MetricIndicatorBindingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** MG-R0.2 read-only exact editorial->typed metric binding discovery. */
@RestController
@RequestMapping("/api/v1/metric-graph/indicator-bindings")
public class MetricIndicatorBindingController {
  private final MetricIndicatorBindingService service;

  public MetricIndicatorBindingController(MetricIndicatorBindingService service) {
    this.service=service;
  }

  @GetMapping
  public List<MetricIndicatorBindingService.Binding> variants(
      @RequestParam String goalCode,@RequestParam String indicatorCode) {
    return service.variants(goalCode,indicatorCode);
  }

  @GetMapping("/{exactBindingRevisionId}")
  public MetricIndicatorBindingService.LinkedGraph exact(
      @PathVariable UUID exactBindingRevisionId) {
    return service.requireExact(exactBindingRevisionId);
  }
}
