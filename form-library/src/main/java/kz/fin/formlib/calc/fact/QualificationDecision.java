package kz.fin.formlib.calc.fact;

import java.time.Instant;
import java.util.UUID;

/**
 * Untrusted transport for a signed independent S2 decision. Constructing this
 * record never grants authorization; only the verifier can issue a marker.
 */
public record QualificationDecision(
    UUID decisionId, String issuerId, String keyId, String signatureAlgorithm,
    String signedPayload, String signatureBase64, String audience, String nonce,
    Instant issuedAt, Instant notBefore, Instant expiresAt,
    String stableScopeKey, String candidateFactsetSha256,
    String sourceSetSha256, String descriptorSha256,
    String mappingBundleSha256, String bindingSha256,
    long issuerEpoch, String qualificationMode) {

  public static final String AUDIENCE = "fin-form-library/fact-qualification/v1";
  public static final String TEST_ONLY_MODE = "TEST_QUALIFIED";

  public QualificationDecision {
    if (decisionId == null || issuedAt == null || notBefore == null
        || expiresAt == null || issuerEpoch <= 0
        || !issuedAt.isBefore(expiresAt)
        || notBefore.isBefore(issuedAt) || !notBefore.isBefore(expiresAt)) {
      throw new IllegalArgumentException("QUALIFICATION_DECISION_INVALID");
    }
    FactDigest.required(issuerId,"QUALIFICATION_ISSUER_REQUIRED");
    FactDigest.required(keyId,"QUALIFICATION_KEY_REQUIRED");
    FactDigest.required(signatureAlgorithm,"QUALIFICATION_ALGORITHM_REQUIRED");
    FactDigest.required(signatureBase64,"QUALIFICATION_SIGNATURE_REQUIRED");
    FactDigest.required(audience,"QUALIFICATION_AUDIENCE_REQUIRED");
    if (nonce == null || nonce.length() < 16 || nonce.length() > 128) {
      throw new IllegalArgumentException("QUALIFICATION_NONCE_INVALID");
    }
    FactDigest.requireSha(signedPayload,"QUALIFICATION_PAYLOAD_INVALID");
    FactDigest.requireSha(stableScopeKey,"QUALIFICATION_SCOPE_SHA_INVALID");
    FactDigest.requireSha(candidateFactsetSha256,"QUALIFICATION_CANDIDATE_SHA_INVALID");
    FactDigest.requireSha(sourceSetSha256,"QUALIFICATION_SOURCE_SHA_INVALID");
    FactDigest.requireSha(descriptorSha256,"QUALIFICATION_DESCRIPTOR_SHA_INVALID");
    FactDigest.requireSha(mappingBundleSha256,"QUALIFICATION_MAPPING_SHA_INVALID");
    FactDigest.requireSha(bindingSha256,"QUALIFICATION_BINDING_SHA_INVALID");
    FactDigest.required(qualificationMode,"QUALIFICATION_MODE_REQUIRED");
  }

  /** Domain-separated, canonical binding across all economic identities. */
  public String canonicalBinding() {
    return FactDigest.sha("FIN-FACT-AUTH-BINDING-V1",
        decisionId.toString(),issuerId,audience,nonce,stableScopeKey,
        candidateFactsetSha256,sourceSetSha256,descriptorSha256,
        mappingBundleSha256,Integer.toString(1),
        Long.toString(issuerEpoch),qualificationMode);
  }

  /** Signed message is exactly this SHA-256 hex encoded in US-ASCII. */
  public String canonicalPayload() {
    return FactDigest.sha("FIN-FACT-AUTH-CLAIMS-V1",
        decisionId.toString(),issuerId,keyId,signatureAlgorithm,audience,nonce,
        issuedAt.toString(),notBefore.toString(),expiresAt.toString(),
        stableScopeKey,candidateFactsetSha256,sourceSetSha256,
        descriptorSha256,mappingBundleSha256,bindingSha256,
        Long.toString(issuerEpoch),qualificationMode,"1");
  }
}
