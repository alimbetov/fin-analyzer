package kz.fin.formlib.api;

import kz.fin.formlib.calc.loan.LoanScheduleService;
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
import java.util.List;

/** A05-R1.1: exact-source UAT cash schedule. Not EIR/GESV/covenant/F03. */
@RestController
@RequestMapping("/api/v1/uat/a05/schedules")
@ConditionalOnProperty(name="fin.calc.a05-intake-uat.enabled",havingValue="true")
public class LoanScheduleUatController {
  private final LoanScheduleService schedules;

  public LoanScheduleUatController(LoanScheduleService schedules) {
    this.schedules=schedules;
  }

  @PostMapping
  public LoanScheduleService.ScheduleView calculate(
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody LoanScheduleService.ScheduleCommand payload) {
    return schedules.calculateAndCommit(payload,key);
  }

  @GetMapping
  public List<LoanScheduleService.SavedSchedule> list(
      @RequestParam UUID organizationId,@RequestParam UUID projectId,
      @RequestParam UUID exactSourceRevisionId) {
    return schedules.listForSource(organizationId,projectId,exactSourceRevisionId);
  }

  @GetMapping("/{scheduleRevisionId}")
  public LoanScheduleService.ScheduleView read(
      @PathVariable UUID scheduleRevisionId,
      @RequestParam UUID organizationId,@RequestParam UUID projectId) {
    return schedules.readExact(organizationId,projectId,scheduleRevisionId);
  }
}
