package kz.fin.backend.execution;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Independent primitive for authenticating a shipped artifact + dependency lock.
 *
 * The caller must supply a public key from a trusted deployment trust store:
 * never trust a signer key embedded in a client-provided manifest. The signed
 * message is domain-separated and includes PostgreSQL's manifest fingerprint.
 * Verification does not issue any execution permission.
 */
public final class HtapFin01ArtifactVerifier {
    private HtapFin01ArtifactVerifier() {}
    public record Verified(String manifestSha256, String artifactSha256,
                           String dependencyLockSha256, boolean permitsExecution) {
        public Verified {
            if(permitsExecution)throw new IllegalArgumentException("FIN01_ARTIFACT_VERIFICATION_NOT_PERMIT");
        }
    }
    public static Verified verify(byte[] installedArtifact, byte[] installedDependencyLock,
                                  String manifestSha256, String artifactSha256,
                                  String dependencyLockSha256, String signatureBase64,
                                  String trustedEd25519PublicKeyBase64) {
        Objects.requireNonNull(installedArtifact);
        Objects.requireNonNull(installedDependencyLock);
        if(installedArtifact.length==0 || installedDependencyLock.length==0
                || !shaShape(manifestSha256) || !shaShape(artifactSha256)
                || !shaShape(dependencyLockSha256))
            throw new IllegalArgumentException("FIN01_ARTIFACT_EVIDENCE_INVALID");
        if(!artifactSha256.equals(sha(installedArtifact))
                || !dependencyLockSha256.equals(sha(installedDependencyLock)))
            throw new IllegalStateException("FIN01_INSTALLED_ARTIFACT_OR_DEPENDENCIES_DIFFER");
        try {
            PublicKey trustedKey=KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(
                            Objects.requireNonNull(trustedEd25519PublicKeyBase64))));
            Signature verifier=Signature.getInstance("Ed25519");
            verifier.initVerify(trustedKey);
            verifier.update(message(manifestSha256,artifactSha256,dependencyLockSha256));
            byte[] bytes=Base64.getDecoder().decode(Objects.requireNonNull(signatureBase64));
            if(!verifier.verify(bytes))
                throw new IllegalStateException("FIN01_BUILD_SIGNATURE_INVALID");
            return new Verified(manifestSha256,artifactSha256,dependencyLockSha256,false);
        }catch(IllegalStateException e) {
            throw e;
        }catch(Exception e) {
            throw new IllegalArgumentException("FIN01_BUILD_TRUST_ANCHOR_OR_SIGNATURE_INVALID",e);
        }
    }
    /** Exact canonical bytes for independent build-side Ed25519 signing. */
    public static byte[] message(String manifest,String artifact,String dependencies) {
        if(!shaShape(manifest)||!shaShape(artifact)||!shaShape(dependencies))
            throw new IllegalArgumentException("FIN01_SIGNED_DIGEST_INVALID");
        return ("FIN:HTAP:ARTIFACT:PG16-V1|"+manifest+"|"+artifact+"|"+dependencies)
                .getBytes(StandardCharsets.US_ASCII);
    }
    public static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(bytes)));
        }catch(java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("FIN01_SHA256_UNAVAILABLE",e);
        }
    }
    private static boolean shaShape(String h) {
        return h!=null && h.matches("[0-9a-f]{64}");
    }
}
