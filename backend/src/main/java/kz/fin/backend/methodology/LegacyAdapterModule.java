package kz.fin.backend.methodology;

import kz.fin.backend.execution.MethodTaskAdapter;
import kz.fin.backend.execution.MethodVariantDescriptor;
import java.util.Comparator;
import java.util.Objects;

/**
 * Metadata-only compatibility bridge for an existing execution adapter.
 * Intentionally NOT a Spring MethodTaskAdapter: it cannot be scheduled as
 * another task, and cannot bypass the existing permit boundary.
 */
public final class LegacyAdapterModule implements MethodModule {
    private final Definition definition;

    public LegacyAdapterModule(MethodTaskAdapter adapter, MethodVariantDescriptor descriptor) {
        Objects.requireNonNull(adapter);
        Objects.requireNonNull(descriptor);
        if (!descriptor.methodCode().equals(adapter.methodCode()))
            throw new IllegalArgumentException("R2_MODULE_ADAPTER_CODE_MISMATCH");
        var expected = descriptor.dependencies().stream()
                .map(MethodVariantDescriptor.Dependency::methodCode)
                .sorted().toList();
        var actual = adapter.dependsOn().stream().sorted().toList();
        if (!expected.equals(actual))
            throw new IllegalArgumentException("R2_MODULE_ADAPTER_DEPENDENCY_MISMATCH");

        this.definition = new Definition(descriptor.methodCode(),
                descriptor.variantVersion(), descriptor.descriptorVersion(),
                descriptor.implementationVersion(), descriptor.resultSchemaVersion(),
                CalculationEngine.JAVA,
                descriptor.dependencies().stream()
                    .map(MethodVariantDescriptor.Dependency::methodCode)
                    .sorted(Comparator.naturalOrder()).toList(),
                !"LEGACY_UNPINNED".equals(descriptor.implementationVersion())
                    && !"LEGACY_RESULT_UNVERIFIED".equals(descriptor.resultSchemaVersion()),
                descriptor.methodS2Qualified());
    }

    @Override
    public Definition definition() { return definition; }
}
