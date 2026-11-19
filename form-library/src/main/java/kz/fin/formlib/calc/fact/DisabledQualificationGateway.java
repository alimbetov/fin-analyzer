package kz.fin.formlib.calc.fact;

import org.springframework.stereotype.Component;

/**
 * ONLY runtime bean. No production S2 trust store or IAM/ABAC has been
 * deployed. Never enable a fake issuer with a config flag or test property.
 */
@Component
public final class DisabledQualificationGateway implements TrustedQualificationGateway {
  @Override
  public VerifiedQualification verifyCurrentDecision(
      QualificationDecision decision,CandidateFactSet candidate,
      TrustedRequestPrincipal principal) {
    throw new IllegalStateException("S2_UNAVAILABLE");
  }
}
