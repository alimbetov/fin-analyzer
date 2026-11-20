package kz.fin.formlib.api;

import kz.fin.formlib.calc.graph.MetricGraphUatExecutionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * MG-R0.4: synthetic intake-only UAT. This route cannot approve S2 or
 * admit any production CalcPlan. No latest-source fallback.
 */
@RestController
@RequestMapping("/api/v1/uat/metric-graph")
@ConditionalOnProperty(
  name={"fin.calc.mg-r04-uat.enabled","fin.calc.intake-uat.enabled"},
  havingValue="true")
public class MetricGraphUatExecutionController {
  private final MetricGraphUatExecutionService service;
  public MetricGraphUatExecutionController(MetricGraphUatExecutionService service){
    this.service=service;
  }

  @PostMapping("/execute")
  public MetricGraphUatExecutionService.Receipt execute(
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody MetricGraphUatExecutionService.Request command){
    return service.execute(command,key);
  }

  @GetMapping("/plans/{exactPlanId}")
  public MetricGraphUatExecutionService.Receipt exact(
      @PathVariable UUID exactPlanId,
      @RequestParam UUID organizationId,@RequestParam UUID projectId){
    return service.readExact(organizationId,projectId,exactPlanId);
  }
}
