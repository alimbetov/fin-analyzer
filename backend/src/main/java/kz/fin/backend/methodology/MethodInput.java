package kz.fin.backend.methodology;

import kz.fin.backend.execution.MethodTaskAdapter;
import java.util.List;
import java.util.Objects;

/**
 * Immutable method inputs, always pinned to a server-owned published revision.
 * This is not an execution permit. Fingerprints are canonical SHA-256 values.
 */
public record MethodInput(
        MethodTaskAdapter.TrustedRevision source,
        String methodCode, String variantVersion,
        String inputFingerprint, String parametersFingerprint,
        List<SourceFact> facts) {
    public record SourceFact(java.util.UUID assertionId, java.util.UUID sourceRevisionId,
                             String metricCode, String metricVersion,
                             java.time.LocalDate startInclusive, java.time.LocalDate endExclusive,
                             String currency, String valueState, java.math.BigDecimal amount) {
        public SourceFact {
            Objects.requireNonNull(assertionId); Objects.requireNonNull(sourceRevisionId);
            Objects.requireNonNull(metricCode); Objects.requireNonNull(metricVersion);
            Objects.requireNonNull(startInclusive); Objects.requireNonNull(endExclusive);
            Objects.requireNonNull(currency); Objects.requireNonNull(valueState);
            Objects.requireNonNull(amount);
            if (!startInclusive.isBefore(endExclusive) || !currency.matches("[A-Z]{3}")
                    || !java.util.Set.of("PRESENT", "EXPLICIT_ZERO").contains(valueState)
                    || (valueState.equals("EXPLICIT_ZERO") && amount.signum()!=0))
                throw new IllegalArgumentException("R2_INVALID_METHOD_FACT");
        }
    }
    public MethodInput {
        Objects.requireNonNull(source);
        Objects.requireNonNull(source.organizationId());
        Objects.requireNonNull(source.economicEntityId());
        Objects.requireNonNull(source.businessScopeId());
        Objects.requireNonNull(source.factSetRevisionId());
        if (source.snapshotHash()==null || !source.snapshotHash().matches("[a-f0-9]{64}")
                || !java.util.Set.of("SYNTHETIC","ACTUAL").contains(source.dataSpace()))
            throw new IllegalArgumentException("R2_METHOD_SOURCE_INVALID");
        if (methodCode == null || !methodCode.matches("[A-Z][A-Z0-9-]{1,30}/[A-Z][A-Z0-9_]{1,100}")
                || variantVersion == null || variantVersion.isBlank())
            throw new IllegalArgumentException("R2_METHOD_IDENTITY_INVALID");
        checkHash(inputFingerprint);
        checkHash(parametersFingerprint);
        facts = List.copyOf(Objects.requireNonNull(facts));
        if (facts.isEmpty() || facts.stream().anyMatch(Objects::isNull)
                || facts.stream().map(SourceFact::assertionId).distinct().count()!=facts.size())
            throw new IllegalArgumentException("R2_METHOD_POPULATION_INVALID");
    }
    static void checkHash(String hash) {
        if (hash==null || !hash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("R2_METHOD_HASH_INVALID");
    }
}
