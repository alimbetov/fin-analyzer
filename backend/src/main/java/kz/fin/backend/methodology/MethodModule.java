package kz.fin.backend.methodology;

import kz.fin.backend.execution.MethodTaskAdapter;
import kz.fin.backend.execution.MethodVariantDescriptor;
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
            MethodVariantDescriptor.requireMethodCode(methodCode, "R2_MODULE_INVALID_CODE");
            MethodVariantDescriptor.requireVersion(variantVersion, "R2_MODULE_INVALID_VARIANT");
            MethodVariantDescriptor.requireVersion(descriptorVersion, "R2_MODULE_INVALID_DESCRIPTOR");
            MethodVariantDescriptor.requireVersion(implementationVersion, "R2_MODULE_INVALID_IMPLEMENTATION");
            MethodVariantDescriptor.requireVersion(resultSchemaVersion, "R2_MODULE_INVALID_RESULT_SCHEMA");
            Objects.requireNonNull(engine, "R2_MODULE_ENGINE_REQUIRED");
            dependencies = List.copyOf(Objects.requireNonNull(dependencies));
            if (dependencies.stream().anyMatch(Objects::isNull)
                    || dependencies.stream().distinct().count() != dependencies.size()
                    || dependencies.contains(methodCode))
                throw new IllegalArgumentException("R2_MODULE_INVALID_DEPENDENCIES");
            for (String code : dependencies)
                MethodVariantDescriptor.requireMethodCode(code, "R2_MODULE_INVALID_DEPENDENCY");
            if (methodS2Qualified && !implementationPinned)
                throw new IllegalArgumentException("R2_MODULE_UNPINNED_QUALIFICATION");
        }
    }
}
