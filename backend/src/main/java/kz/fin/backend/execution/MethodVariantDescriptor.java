package kz.fin.backend.execution;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * R2.A1: versioned, server-owned method metadata. Registration alone cannot
 * qualify a family or grant an execution permit.
 */
public record MethodVariantDescriptor(
        String methodCode,
        String variantVersion,
        String descriptorVersion,
        String implementationVersion,
        String resultSchemaVersion,
        Set<String> supportedDataSpaces,
        List<InputRequirement> inputs,
        List<Dependency> dependencies,
        int maxExecutionSeconds,
        boolean methodS2Qualified) {

    public enum Role { REQUIRED, OPTIONAL, EVIDENCE_ONLY }

    public record InputRequirement(String metricCode, String measureKind,
                                   String unitCode, Role role) {
        public InputRequirement {
            requireCode(metricCode, "R2_INPUT_METRIC");
            Objects.requireNonNull(role, "R2_INPUT_ROLE_REQUIRED");
            if (!Set.of("FLOW", "STOCK", "POPULATION", "PUBLICATION").contains(measureKind)
                    || !Set.of("MONEY", "COUNT", "MANIFEST", "NONE").contains(unitCode))
                throw new IllegalArgumentException("R2_INPUT_SEMANTICS_UNSUPPORTED");
        }
    }
    public record Dependency(String methodCode, Role kind) {
        public Dependency {
            requireCode(methodCode, "R2_DEPENDENCY_CODE");
            Objects.requireNonNull(kind, "R2_DEPENDENCY_KIND_REQUIRED");
        }
    }

    public MethodVariantDescriptor {
        requireCode(methodCode, "R2_METHOD_CODE");
        requireVersion(variantVersion, "R2_VARIANT_VERSION");
        requireVersion(descriptorVersion, "R2_DESCRIPTOR_VERSION");
        requireVersion(implementationVersion, "R2_IMPLEMENTATION_VERSION");
        requireVersion(resultSchemaVersion, "R2_RESULT_SCHEMA_VERSION");
        supportedDataSpaces = Set.copyOf(Objects.requireNonNull(supportedDataSpaces));
        if (supportedDataSpaces.isEmpty() ||
                !Set.of("SYNTHETIC", "ACTUAL").containsAll(supportedDataSpaces))
            throw new IllegalArgumentException("R2_DATA_SPACE_INVALID");
        inputs = List.copyOf(Objects.requireNonNull(inputs));
        dependencies = List.copyOf(Objects.requireNonNull(dependencies));
        if (inputs.stream().anyMatch(Objects::isNull) ||
                dependencies.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("R2_DESCRIPTOR_NULL_ELEMENT");
        if (inputs.stream().map(InputRequirement::metricCode).distinct().count() != inputs.size()
                || dependencies.stream().map(Dependency::methodCode).distinct().count()
                   != dependencies.size())
            throw new IllegalArgumentException("R2_DESCRIPTOR_DUPLICATE_REQUIREMENT");
        if (dependencies.stream().anyMatch(d -> methodCode.equals(d.methodCode())))
            throw new IllegalArgumentException("R2_DESCRIPTOR_SELF_DEPENDENCY");
        if (maxExecutionSeconds < 1 || maxExecutionSeconds > 3600)
            throw new IllegalArgumentException("R2_EXECUTION_BUDGET_INVALID");
        // The current 60-family catalogue is explicitly release HOLD.
        if (methodS2Qualified)
            throw new IllegalArgumentException("R2_METHOD_S2_PROMOTION_FORBIDDEN");
    }
    static void requireCode(String value, String error) {
        if (value == null || !value.matches("[A-Z][A-Z0-9-]{1,30}/[A-Z][A-Z0-9_]{1,100}")
                && !isInputSymbol(value))
            throw new IllegalArgumentException(error);
    }
    private static boolean isInputSymbol(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{1,80}");
    }
    static void requireVersion(String value, String error) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException(error);
    }
}
