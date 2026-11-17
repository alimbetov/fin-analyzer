package kz.fin.backend.execution;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Read-only provenance of loaded .class bytes (NOT a signed CI/JAR artifact).
 * This cannot replace a reproducible deployment manifest, artifact attestation,
 * effective configuration hash or independent financial method S2.
 */
public final class HtapRuntimeClassDigest {
    public record Evidence(String implementationClass, String classSha256,
                           String trustLevel, boolean executionPermitted) {
        public Evidence {
            Objects.requireNonNull(implementationClass);
            if (classSha256 == null || !classSha256.matches("[0-9a-f]{64}")
                    || !"CLASS_BYTES_ONLY_UNATTESTED".equals(trustLevel)
                    || executionPermitted)
                throw new IllegalArgumentException("FIN_HTAP_ARTIFACT_EVIDENCE_UNQUALIFIED");
        }
    }

    private HtapRuntimeClassDigest() {}

    public static Evidence inspect(Class<?> implementation) {
        Objects.requireNonNull(implementation);
        if (implementation.isInterface() || implementation.isPrimitive()
                || implementation.isArray())
            throw new IllegalArgumentException("FIN_HTAP_IMPLEMENTATION_CLASS_INVALID");
        String resource=implementation.getSimpleName()+".class";
        try (InputStream bytes=implementation.getResourceAsStream(resource)) {
            if (bytes == null)
                throw new IllegalStateException("FIN_HTAP_CLASS_BYTES_MISSING");
            var digest=MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes());
            return new Evidence(implementation.getName(),HexFormat.of().formatHex(digest),
                    "CLASS_BYTES_ONLY_UNATTESTED",false);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("FIN_HTAP_CLASS_DIGEST_FAILURE",e);
        }
    }
}
