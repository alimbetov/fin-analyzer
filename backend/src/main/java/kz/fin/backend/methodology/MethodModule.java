package kz.fin.backend.methodology;

import java.util.List;
import java.util.Objects;

/**
 * R2.M1: read-only, server-owned module identity and contract.
 * Module registration never issues a permit or executes a calculation.
 * A later M2/M3 module implementation may expose calculation through the
 * existing guarded scheduler, never through this metadata interface.
 */
public interface MethodModule {
    Definition definition();

    record Definition(
            String methodCode, String variantVersion,
            String descriptorVersion, String implementationVersion,
            String resultSchemaVersion, CalculationEngine engine,
            List<String> dependencies, boolean implementationPinned,
            boolean methodS2Qualified) {
        public Definition {
            checkCode(methodCode, "R2_MODULE_INVALID_CODE");
            checkVersion(variantVersion, "R2_MODULE_INVALID_VARIANT");
            checkVersion(descriptorVersion, "R2_MODULE_INVALID_DESCRIPTOR");
            checkVersion(implementationVersion, "R2_MODULE_INVALID_IMPLEMENTATION");
            checkVersion(resultSchemaVersion, "R2_MODULE_INVALID_RESULT_SCHEMA");
            Objects.requireNonNull(engine, "R2_MODULE_ENGINE_REQUIRED");
            dependencies = List.copyOf(Objects.requireNonNull(dependencies));
            if (dependencies.stream().anyMatch(Objects::isNull)
                    || dependencies.stream().distinct().count() != dependencies.size()
                    || dependencies.contains(methodCode))
                throw new IllegalArgumentException("R2_MODULE_INVALID_DEPENDENCIES");
            for (String code : dependencies)
                checkCode(code, "R2_MODULE_INVALID_DEPENDENCY");
            if (methodS2Qualified && !implementationPinned)
                throw new IllegalArgumentException("R2_MODULE_UNPINNED_QUALIFICATION");
        }
    }

    private static void checkCode(String code, String reason) {
        if (code == null || !code.matches("[A-Z][A-Z0-9-]{1,30}/[A-Z][A-Z0-9_]{1,100}"))
            throw new IllegalArgumentException(reason);
    }
    private static void checkVersion(String value, String reason) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException(reason);
    }
}
