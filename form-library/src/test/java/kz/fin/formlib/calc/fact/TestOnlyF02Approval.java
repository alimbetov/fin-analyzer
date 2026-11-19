package kz.fin.formlib.calc.fact;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Synthetic trust fixture in src/test ONLY; NEVER production S2. */
public final class TestOnlyF02Approval {
  private TestOnlyF02Approval(){}
  public record Admission(TrustedRequestPrincipal principal,
      VerifiedQualification verified){}

  public static Admission create(CandidateFactSet c,UUID user,long epoch){
    var principal=new TrustedRequestPrincipal(user.toString(),"FIN_TEST_ONLY",
        c.scope(),UUID.randomUUID().toString(),
        Set.of(TrustedRequestPrincipal.QUALIFY_ACTION));
    UUID decisionId=UUID.randomUUID();
    Instant at=Instant.now().minusSeconds(1);
    String blank="0".repeat(64);
    var initial=new QualificationDecision(decisionId,"FIN_TEST_ONLY_ISSUER",
        "SYNTHETIC_R02","Ed25519",blank,"NOT_SIGNED_TEST_ONLY",
        QualificationDecision.AUDIENCE,principal.expectedNonce(),
        at,at,at.plusSeconds(180),c.scope().stableScopeKey(),
        c.candidateFactsetSha256(),c.sourceSetSha256(),c.descriptorSha256(),
        c.mappingBundleSha256(),blank,epoch,QualificationDecision.TEST_ONLY_MODE);
    var decision=new QualificationDecision(decisionId,initial.issuerId(),
        initial.keyId(),initial.signatureAlgorithm(),initial.signedPayload(),
        initial.signatureBase64(),initial.audience(),initial.nonce(),
        initial.issuedAt(),initial.notBefore(),initial.expiresAt(),
        initial.stableScopeKey(),initial.candidateFactsetSha256(),
        initial.sourceSetSha256(),initial.descriptorSha256(),
        initial.mappingBundleSha256(),initial.canonicalBinding(),
        epoch,initial.qualificationMode());
    return new Admission(principal,new VerifiedQualification(decision,principal,Instant.now()));
  }
}
