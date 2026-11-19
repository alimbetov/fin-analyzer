package kz.fin.formlib.calc.security;

import kz.fin.formlib.calc.hybrid.RequiredOutputReviewDraftService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Explicitly opt-in, JWT-protected, pre-S2-only endpoints. No approval route.
 * An author/reviewer identity CANNOT be supplied in any request body.
 */
@RestController
@RequestMapping("/internal/fin-calc/financial-review")
@ConditionalOnProperty(prefix="fin.financial-review",name="api-enabled",havingValue="true")
public class FinancialReviewIntakeController {
  public record DraftBody(String formCode,UUID expectedFormVersionId,
      UUID expectedPredecessorId,
      List<RequiredOutputReviewDraftService.OutputDecision> decisions,
      String rationale) {}

  private final TrustedFinancialReviewIntakeService service;
  public FinancialReviewIntakeController(TrustedFinancialReviewIntakeService service){
    this.service=service;
  }

  @PostMapping("/drafts")
  @ResponseStatus(HttpStatus.CREATED)
  public TrustedFinancialReviewIntakeService.AuthorDraft create(@RequestBody DraftBody body)
      throws Exception {
    if(body==null)throw new IllegalArgumentException("CALC3C3_DRAFT_BODY_REQUIRED");
    return service.authorDraft(body.formCode(),body.expectedFormVersionId(),
      body.expectedPredecessorId(),body.decisions(),body.rationale());
  }

  @PostMapping("/drafts/{draftId}/submit-for-independent-s2")
  @ResponseStatus(HttpStatus.CREATED)
  public TrustedFinancialReviewIntakeService.PreS2Request submit(
      @PathVariable UUID draftId) throws Exception {
    return service.submitForIndependentS2(draftId);
  }
}
