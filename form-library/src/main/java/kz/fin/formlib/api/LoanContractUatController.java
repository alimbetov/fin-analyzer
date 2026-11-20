package kz.fin.formlib.api;

import kz.fin.formlib.calc.loan.LoanContractIntakeService;
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

/** Exact C05 fin_intake UAT command, NOT a separate POST /loans writer. */
@RestController
@RequestMapping("/api/v1/uat/a05")
@ConditionalOnProperty(name="fin.calc.a05-intake-uat.enabled",havingValue="true")
public class LoanContractUatController {
  private final LoanContractIntakeService loan;

  public LoanContractUatController(LoanContractIntakeService loan) {
    this.loan=loan;
  }

  @PostMapping("/c05/revisions")
  public LoanContractIntakeService.Receipt save(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody LoanContractIntakeService.Command command) {
    return loan.save(command,idempotencyKey);
  }

  @GetMapping("/c05/revisions/{exactSourceRevisionId}")
  public LoanContractIntakeService.ContractView read(
      @PathVariable UUID exactSourceRevisionId,
      @RequestParam UUID organizationId,@RequestParam UUID projectId) {
    return loan.readExact(organizationId,projectId,exactSourceRevisionId);
  }
}
