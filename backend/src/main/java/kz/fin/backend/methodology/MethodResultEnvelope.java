package kz.fin.backend.methodology;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Versioned, immutable computation receipt. Calculation != S2 qualification
 * and this DTO alone has no persistence or publication side effects.
 */
public record MethodResultEnvelope(
        MethodInput input, CalculationEngine engine,
        String implementationVersion, String resultSchemaVersion,
        String computationStatus, BigDecimal value,
        String unitCode, String reasonCode,
        List<MethodInput.SourceFact> provenance) {
    public MethodResultEnvelope {
        Objects.requireNonNull(input); Objects.requireNonNull(engine);
        if (implementationVersion == null || implementationVersion.isBlank()
                || resultSchemaVersion == null || resultSchemaVersion.isBlank()
                || !java.util.Set.of("CALCULATED","INCOMPLETE","NOT_COMPUTABLE").contains(computationStatus))
            throw new IllegalArgumentException("R2_METHOD_RESULT_SCHEMA_INVALID");
        if ("CALCULATED".equals(computationStatus)) {
            Objects.requireNonNull(value);
            if (reasonCode!=null || unitCode==null || unitCode.isBlank())
                throw new IllegalArgumentException("R2_METHOD_CALCULATED_INVALID");
        } else if (value!=null || reasonCode==null || reasonCode.isBlank())
            throw new IllegalArgumentException("R2_METHOD_BLOCKED_INVALID");
        provenance=List.copyOf(Objects.requireNonNull(provenance));
        if (provenance.isEmpty() || !input.facts().containsAll(provenance))
            throw new IllegalArgumentException("R2_METHOD_PROVENANCE_INVALID");
    }
    /** No result is automatically method-qualified. */
    public boolean executionQualified() { return false; }
}
