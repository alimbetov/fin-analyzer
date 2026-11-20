package kz.fin.formlib.api;

import kz.fin.formlib.calc.graph.MetricGraphResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * FIN MG-R0.1 read-only candidate lineage.
 * Does not calculate values, select a financial policy, or provide admission.
 */
@RestController
@RequestMapping("/api/v1/metric-graph")
public class MetricGraphController {
  private final MetricGraphResolver resolver;

  public MetricGraphController(MetricGraphResolver resolver) {
    this.resolver=resolver;
  }

  @GetMapping("/metrics/{metricCode}")
  public MetricGraphResolver.Resolution resolve(
      @PathVariable String metricCode,
      @RequestParam(required=false) String goalCode,
      @RequestParam(required=false) UUID exactMethodRevisionId) {
    return resolver.resolve(metricCode,goalCode,exactMethodRevisionId);
  }
}
