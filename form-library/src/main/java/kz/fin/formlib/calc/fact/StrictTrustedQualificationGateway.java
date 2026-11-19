package kz.fin.formlib.calc.fact;

/**
 * An unregistered adapter for a future independently approved deployment.
 * Deliberately absent from runtime dependency injection.
 */
final class StrictTrustedQualificationGateway implements TrustedQualificationGateway {
  private final AuthorityDecisionVerifier verifier;
  StrictTrustedQualificationGateway(AuthorityDecisionVerifier verifier){
    this.verifier=java.util.Objects.requireNonNull(verifier);
  }
  @Override public VerifiedQualification verifyCurrentDecision(
      QualificationDecision decision,CandidateFactSet candidate,
      TrustedRequestPrincipal principal) {
    return verifier.verifyExact(decision,candidate,principal);
  }
}
