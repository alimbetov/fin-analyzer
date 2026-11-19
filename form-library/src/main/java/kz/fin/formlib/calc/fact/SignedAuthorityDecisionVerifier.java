package kz.fin.formlib.calc.fact;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * Pure cryptographic and live-authority verifier. NOT a Spring bean and never
 * wired in production until separately reviewed S2, IAM/ABAC and revocation.
 * The public key is independent configuration, NEVER from the decision.
 */
public final class SignedAuthorityDecisionVerifier implements AuthorityDecisionVerifier {
  public interface TrustedIssuerKeys {
    Optional<PublicKey> lookup(String issuerId,String keyId);
  }
  public interface LiveAuthority {
    AuthoritySnapshot readCurrent(String issuerId,String scopeKey);
  }
  public record AuthoritySnapshot(long epoch,Instant checkedAt,Set<String> revokedKeyIds) {
    public AuthoritySnapshot {
      if(epoch<1||checkedAt==null||revokedKeyIds==null)
        throw new IllegalArgumentException("S2_UNAVAILABLE");
      revokedKeyIds=Set.copyOf(revokedKeyIds);
    }
  }

  private final TrustedIssuerKeys keys;
  private final LiveAuthority authority;
  private final Clock clock;

  public SignedAuthorityDecisionVerifier(
      TrustedIssuerKeys keys,LiveAuthority authority,Clock clock) {
    this.keys=java.util.Objects.requireNonNull(keys);
    this.authority=java.util.Objects.requireNonNull(authority);
    this.clock=java.util.Objects.requireNonNull(clock);
  }

  @Override
  public VerifiedQualification verifyExact(QualificationDecision decision,
      CandidateFactSet candidate,TrustedRequestPrincipal principal) {
    if(principal==null||!principal.permits(TrustedRequestPrincipal.QUALIFY_ACTION))
      throw new IllegalArgumentException("SCOPE_FORBIDDEN");
    if(candidate==null||!principal.permittedScope().equals(candidate.scope()))
      throw new IllegalArgumentException("SCOPE_FORBIDDEN");
    F02CandidateIntegrity.verify(candidate);
    if(decision==null||!QualificationDecision.AUDIENCE.equals(decision.audience())
        ||!QualificationDecision.TEST_ONLY_MODE.equals(decision.qualificationMode())
        ||!principal.expectedNonce().equals(decision.nonce())
        ||!candidate.scope().stableScopeKey().equals(decision.stableScopeKey())
        ||!candidate.candidateFactsetSha256().equals(decision.candidateFactsetSha256())
        ||!candidate.sourceSetSha256().equals(decision.sourceSetSha256())
        ||!candidate.descriptorSha256().equals(decision.descriptorSha256())
        ||!candidate.mappingBundleSha256().equals(decision.mappingBundleSha256())
        ||!decision.canonicalBinding().equals(decision.bindingSha256())
        ||!decision.canonicalPayload().equals(decision.signedPayload())) {
      throw new IllegalArgumentException("SOURCE_BINDING_MISMATCH");
    }
    Instant now=clock.instant();
    if(now.isBefore(decision.notBefore())||!now.isBefore(decision.expiresAt())
        ||now.isBefore(decision.issuedAt())
        ||Duration.between(decision.issuedAt(),decision.expiresAt()).compareTo(Duration.ofMinutes(5))>0)
      throw new IllegalArgumentException("QUALIFICATION_WINDOW_INVALID");
    if(!"Ed25519".equals(decision.signatureAlgorithm()))
      throw new IllegalArgumentException("INVALID_QUALIFICATION_SIGNATURE");
    PublicKey key=keys.lookup(decision.issuerId(),decision.keyId())
        .orElseThrow(()->new IllegalArgumentException("INVALID_QUALIFICATION_SIGNATURE"));
    try{
      var verifier=Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(decision.signedPayload().getBytes(StandardCharsets.US_ASCII));
      if(!verifier.verify(Base64.getDecoder().decode(decision.signatureBase64())))
        throw new IllegalArgumentException("INVALID_QUALIFICATION_SIGNATURE");
    }catch(IllegalArgumentException ex){throw ex;}
    catch(Exception ex){throw new IllegalArgumentException("INVALID_QUALIFICATION_SIGNATURE",ex);}
    // Always check LIVE authority after crypto. Cached/unknown snapshots deny.
    AuthoritySnapshot snapshot;
    try{snapshot=authority.readCurrent(decision.issuerId(),decision.stableScopeKey());}
    catch(Exception ex){throw new IllegalStateException("S2_UNAVAILABLE",ex);}
    if(snapshot==null||snapshot.checkedAt().isAfter(now)
        ||Duration.between(snapshot.checkedAt(),now).compareTo(Duration.ofSeconds(10))>0)
      throw new IllegalStateException("S2_UNAVAILABLE");
    if(snapshot.revokedKeyIds().contains(decision.keyId()))
      throw new IllegalArgumentException("INVALID_QUALIFICATION_SIGNATURE");
    if(snapshot.epoch()!=decision.issuerEpoch())
      throw new IllegalArgumentException("AUTHORITY_EPOCH_STALE");
    return new VerifiedQualification(decision,principal,now);
  }
}
