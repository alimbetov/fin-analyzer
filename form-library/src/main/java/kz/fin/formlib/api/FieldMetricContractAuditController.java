package kz.fin.formlib.api;

import kz.fin.formlib.service.FieldMetricContractAuditService;
import org.springframework.web.bind.annotation.*;

/** Read-only audit of physical form fields and their source metric contracts. */
@RestController
@RequestMapping("/api/v1/catalog/contracts")
public class FieldMetricContractAuditController {
  private final FieldMetricContractAuditService service;
  public FieldMetricContractAuditController(FieldMetricContractAuditService service){
    this.service=service;
  }
  @GetMapping("/coverage")
  public FieldMetricContractAuditService.Coverage coverage(){
    return service.coverage();
  }
  @GetMapping("/forms/{formCode}")
  public FieldMetricContractAuditService.FormView form(
      @PathVariable String formCode,@RequestParam(defaultValue="ru") String locale){
    return service.form(formCode,locale);
  }
}
