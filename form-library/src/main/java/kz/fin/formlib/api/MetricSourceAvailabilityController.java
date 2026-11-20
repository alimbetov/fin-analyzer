package kz.fin.formlib.api;

import kz.fin.formlib.calc.graph.MetricSourceAvailabilityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read-only MG-R0.3: POST is a preview/query, never persists or runs finance. */
@RestController
@RequestMapping("/api/v1/metric-graph/source-availability")
@ConditionalOnProperty(name="fin.calc.metric-source-uat.enabled",havingValue="true")
public class MetricSourceAvailabilityController {
  private final MetricSourceAvailabilityService service;
  public MetricSourceAvailabilityController(MetricSourceAvailabilityService service){
    this.service=service;
  }

  @PostMapping("/resolve")
  public MetricSourceAvailabilityService.Availability resolve(
      @RequestBody MetricSourceAvailabilityService.Request request){
    return service.resolve(request);
  }

  @GetMapping("/a05/amendments/{exactAmendmentRevisionId}")
  public MetricSourceAvailabilityService.A05AmendmentNode amendment(
      @PathVariable UUID exactAmendmentRevisionId,
      @RequestParam UUID organizationId,@RequestParam UUID projectId){
    return service.a05AmendmentExact(organizationId,projectId,
      exactAmendmentRevisionId);
  }

  @GetMapping("/a05/{exactScheduleRevisionId}")
  public MetricSourceAvailabilityService.A05Node a05(
      @PathVariable UUID exactScheduleRevisionId,
      @RequestParam UUID organizationId,@RequestParam UUID projectId){
    return service.a05Exact(organizationId,projectId,exactScheduleRevisionId);
  }
}
