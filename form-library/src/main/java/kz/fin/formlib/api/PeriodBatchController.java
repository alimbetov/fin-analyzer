package kz.fin.formlib.api;

import kz.fin.formlib.service.PeriodBatchPlanner;
import org.springframework.web.bind.annotation.*;

/**
 * Preview supported consecutive reporting periods, including weekends.
 * Pure computation; no financial facts are created.
 */
@RestController
@RequestMapping("/api/v1/period-batches")
public class PeriodBatchController {
  @GetMapping("/preview")
  public PeriodBatchPlanner.PeriodBatch preview(
      @RequestParam String periodType,@RequestParam String startPeriod,
      @RequestParam Integer count){
    return PeriodBatchPlanner.plan(periodType,startPeriod,count);
  }
}
