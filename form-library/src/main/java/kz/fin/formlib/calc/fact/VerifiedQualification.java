package kz.fin.formlib.calc.fact;

import java.time.Instant;
import java.util.UUID;

/**
 * Non-serializable proof marker, constructible only by trusted Java code in
 * this package. NOT a financial fact, a production S2 approval, or SQL receipt.
 */
public final class VerifiedQualification {
  private final UUID decisionId;
  private final String issuerId;
  private final String principalId;
  private final String candidateSha256;
  private final String bindingSha256;
  private final String scopeKey;
  private final long issuerEpoch;
  private final Instant verifiedAt;

  VerifiedQualification(QualificationDecision decision,
      TrustedRequestPrincipal principal,Instant verifiedAt) {
    this.decisionId=decision.decisionId();
    this.issuerId=decision.issuerId();
    this.principalId=principal.subjectId();
    this.candidateSha256=decision.candidateFactsetSha256();
    this.bindingSha256=decision.bindingSha256();
    this.scopeKey=decision.stableScopeKey();
    this.issuerEpoch=decision.issuerEpoch();
    this.verifiedAt=verifiedAt;
  }

  public UUID decisionId(){return decisionId;}
  public String issuerId(){return issuerId;}
  public String principalId(){return principalId;}
  public String candidateSha256(){return candidateSha256;}
  public String bindingSha256(){return bindingSha256;}
  public String scopeKey(){return scopeKey;}
  public long issuerEpoch(){return issuerEpoch;}
  public Instant verifiedAt(){return verifiedAt;}
  public boolean matches(CandidateFactSet candidate,TrustedRequestPrincipal principal){
    return candidate!=null&&principal!=null
        &&candidateSha256.equals(candidate.candidateFactsetSha256())
        &&scopeKey.equals(candidate.scope().stableScopeKey())
        &&principalId.equals(principal.subjectId())
        &&principal.permittedScope().equals(candidate.scope());
  }
}
