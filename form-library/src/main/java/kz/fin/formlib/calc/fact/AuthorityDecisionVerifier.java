package kz.fin.formlib.calc.fact;

/** Signature, economic binding, server IAM and live authority epoch checks. */
public interface AuthorityDecisionVerifier {
  VerifiedQualification verifyExact(
      QualificationDecision decision,CandidateFactSet candidate,
      TrustedRequestPrincipal principal);
}
