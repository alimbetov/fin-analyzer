package kz.fin.formlib.api;

import kz.fin.formlib.calc.loan.LoanAmendmentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** UAT-only A05 R1.2 contract amendment SCENARIOS, never actual payment posting. */
@RestController
@RequestMapping("/api/v1/uat/a05/amendments")
@ConditionalOnProperty(name="fin.calc.a05-intake-uat.enabled",havingValue="true")
public class LoanAmendmentUatController {
  private final LoanAmendmentService service;
  public LoanAmendmentUatController(LoanAmendmentService service){this.service=service;}

  @PostMapping
  public LoanAmendmentService.View create(
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody LoanAmendmentService.Command command){
    return service.calculateAndCommit(command,key);
  }

  @GetMapping
  public List<LoanAmendmentService.Summary> list(
      @RequestParam UUID organizationId,@RequestParam UUID projectId,
      @RequestParam UUID parentScheduleRevisionId){
    return service.list(organizationId,projectId,parentScheduleRevisionId);
  }

  @GetMapping("/{exactAmendmentRevisionId}")
  public LoanAmendmentService.View read(
      @PathVariable UUID exactAmendmentRevisionId,
      @RequestParam UUID organizationId,@RequestParam UUID projectId){
    return service.readExact(organizationId,projectId,exactAmendmentRevisionId);
  }
}
