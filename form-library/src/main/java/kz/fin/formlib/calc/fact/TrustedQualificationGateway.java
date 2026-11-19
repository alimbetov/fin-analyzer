package kz.fin.formlib.calc.fact;

/** Server-side qualification gate. No client-supplied Boolean may authorize it. */
public interface TrustedQualificationGateway {
  VerifiedQualification verifyCurrentDecision(
      QualificationDecision decision,CandidateFactSet candidate,
      TrustedRequestPrincipal principal);
}
