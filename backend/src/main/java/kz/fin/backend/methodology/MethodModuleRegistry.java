package kz.fin.backend.methodology;

import kz.fin.backend.execution.MethodTaskAdapter;
import kz.fin.backend.execution.MethodVariantRegistry;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * R2.M1 read-only indexing of the exact seven existing adapters.
 * Does NOT create execution permits, a scheduler or a new outbox.
 * All bindings remain LEGACY_UNPINNED, with production execution HOLD.
 */
@Component
public final class MethodModuleRegistry {
    private final Map<String, MethodModule> modules;

    public MethodModuleRegistry(List<MethodTaskAdapter> adapters, MethodVariantRegistry variants) {
        this(bindLegacy(adapters, variants));
    }

    /** Independent validation seam for test modules and future SQL variants. */
    public MethodModuleRegistry(Iterable<MethodModule> definitions) {
        Objects.requireNonNull(definitions);
        var index = new TreeMap<String, MethodModule>();
        for (var module : definitions) {
            if (module == null || module.definition() == null)
                throw new IllegalStateException("R2_MODULE_NULL");
            String code = module.definition().methodCode();
            if (index.putIfAbsent(code, module) != null)
                throw new IllegalStateException("R2_MODULE_DUPLICATE_BINDING");
        }
        for (var module : index.values())
            for (var prerequisite : module.definition().dependencies())
                if (!index.containsKey(prerequisite))
                    throw new IllegalStateException("R2_MODULE_UNKNOWN_DEPENDENCY");
        var visited = new java.util.HashSet<String>();
        for (var code : index.keySet())
            visit(code, index, new java.util.HashSet<>(), visited);
        modules = Map.copyOf(index);
    }

    private static List<MethodModule> bindLegacy(List<MethodTaskAdapter> adapters,
                                                 MethodVariantRegistry variants) {
        Objects.requireNonNull(adapters);
        Objects.requireNonNull(variants);
        var index = new HashMap<String, MethodTaskAdapter>();
        for (var adapter : adapters) {
            if (adapter == null || adapter.methodCode() == null
                    || index.putIfAbsent(adapter.methodCode(), adapter) != null)
                throw new IllegalStateException("R2_MODULE_DUPLICATE_ADAPTER");
        }
        var descriptors = variants.all();
        if (index.size() != descriptors.size())
            throw new IllegalStateException("R2_MODULE_ADAPTER_BINDING_MISMATCH");
        for (var code : index.keySet()) variants.require(code);
        return descriptors.stream()
                .map(d -> (MethodModule) new LegacyAdapterModule(index.get(d.methodCode()), d))
                .toList();
    }

    private static void visit(String code, Map<String, MethodModule> index,
                              java.util.Set<String> visiting, java.util.Set<String> visited) {
        if (visited.contains(code)) return;
        if (!visiting.add(code))
            throw new IllegalStateException("R2_MODULE_DEPENDENCY_CYCLE");
        for (var next : index.get(code).definition().dependencies())
            visit(next, index, visiting, visited);
        visiting.remove(code);
        visited.add(code);
    }

    public List<MethodModule.Definition> allDefinitions() {
        return modules.values().stream().map(MethodModule::definition)
                .sorted(Comparator.comparing(MethodModule.Definition::methodCode)).toList();
    }

    public MethodModule.Definition require(String methodCode) {
        var module = modules.get(methodCode);
        if (module == null)
            throw new IllegalArgumentException("R2_MODULE_NOT_REGISTERED");
        return module.definition();
    }

    /** M1 is metadata only: no caller may upgrade legacy unpinned modules. */
    public void requireExecutableBinding(String methodCode) {
        var definition = require(methodCode);
        if (!definition.implementationPinned())
            throw new IllegalStateException("R2_METHOD_IMPLEMENTATION_NOT_PINNED");
        throw new IllegalStateException("R2_EXECUTION_PERMIT_NOT_AVAILABLE");
    }
}
