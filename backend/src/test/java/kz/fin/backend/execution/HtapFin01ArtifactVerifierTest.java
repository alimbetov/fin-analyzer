package kz.fin.backend.execution;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class HtapFin01ArtifactVerifierTest {
    @Test void authenticInstalledArtifactAndLockAreVerifiedWithoutGrantingExecution() throws Exception {
        var keypair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] jar="real-installed-test-jar".getBytes(StandardCharsets.UTF_8);
        byte[] dependencies="real-test-dependency-lock".getBytes(StandardCharsets.UTF_8);
        String manifestSha=HtapFin01ArtifactVerifier.sha("canonical-manifest".getBytes(StandardCharsets.UTF_8));
        String artifactSha=HtapFin01ArtifactVerifier.sha(jar);
        String lockSha=HtapFin01ArtifactVerifier.sha(dependencies);
        Signature signer=Signature.getInstance("Ed25519");
        signer.initSign(keypair.getPrivate());
        signer.update(HtapFin01ArtifactVerifier.message(manifestSha,artifactSha,lockSha));
        String signature=Base64.getEncoder().encodeToString(signer.sign());
        String publicKey=Base64.getEncoder().encodeToString(keypair.getPublic().getEncoded());
        var verified=HtapFin01ArtifactVerifier.verify(jar,dependencies,manifestSha,
                artifactSha,lockSha,signature,publicKey);
        assertFalse(verified.permitsExecution());
        assertEquals(artifactSha,verified.artifactSha256());
        assertThrows(IllegalStateException.class,()->HtapFin01ArtifactVerifier.verify(
                "changed".getBytes(StandardCharsets.UTF_8),dependencies,
                manifestSha,artifactSha,lockSha,signature,publicKey));
        assertThrows(IllegalStateException.class,()->HtapFin01ArtifactVerifier.verify(
                jar,"other lock".getBytes(StandardCharsets.UTF_8),
                manifestSha,artifactSha,lockSha,signature,publicKey));
        assertThrows(IllegalStateException.class,()->HtapFin01ArtifactVerifier.verify(
                jar,dependencies,HtapFin01ArtifactVerifier.sha("altered".getBytes(StandardCharsets.UTF_8)),
                artifactSha,lockSha,signature,publicKey));
        var wrongKey=KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        assertThrows(IllegalStateException.class,()->HtapFin01ArtifactVerifier.verify(
                jar,dependencies,manifestSha,artifactSha,lockSha,signature,
                Base64.getEncoder().encodeToString(wrongKey.getEncoded())));
        assertThrows(IllegalArgumentException.class,()->HtapFin01ArtifactVerifier.verify(
                jar,dependencies,manifestSha,artifactSha,lockSha,"garbage",publicKey));
    }
}
