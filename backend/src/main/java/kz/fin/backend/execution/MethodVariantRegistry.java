package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * R2.A1 read-only registry for the seven EXISTING server adapters.
 * Deliberately not wired to SingleExecutorScheduler: no plan, task, or
 * execution authorization can result from reading a descriptor.
 */
@Component
public final class MethodVariantRegistry {
    private static final String RESOURCE = "/fin/methodology/methodology-catalog-r1.json";
    private static final String DESCRIPTOR_VERSION = "R2_A1_1";
    private static final String UNPINNED_IMPLEMENTATION = "LEGACY_UNPINNED";
    private final Map<String, MethodVariantDescriptor> entries;
    private final String fingerprint;

    public MethodVariantRegistry(List<MethodTaskAdapter> adapters, ObjectMapper mapper) {
        Objects.requireNonNull(adapters);
        Objects.requireNonNull(mapper);
        var catalogFamilies = loadFamilies(mapper);
        var definitions = definitions();
        validate(definitions, adapters, catalogFamilies);
        var map = new TreeMap<String,MethodVariantDescriptor>();
        for (var descriptor : definitions) map.put(descriptor.methodCode(), descriptor);
        entries = Map.copyOf(map);
        fingerprint = hash(definitions);
    }

    public MethodVariantDescriptor require(String methodCode) {
        var found = entries.get(methodCode);
        if (found == null) throw new IllegalArgumentException("R2_METHOD_VARIANT_UNKNOWN");
        return found;
    }
    public List<MethodVariantDescriptor> all() {
        return entries.values().stream()
                .sorted(Comparator.comparing(MethodVariantDescriptor::methodCode)).toList();
    }
    public String fingerprint() { return fingerprint; }

    static List<MethodVariantDescriptor> definitions() {
        var required = MethodVariantDescriptor.Role.REQUIRED;
        var sys = Set.of("SYNTHETIC", "ACTUAL");
        return List.of(
            descriptor("SYS/VERIFY_PUBLISHED_FACT_SET", sys, List.of(), List.of()),
            descriptor("SYS/DIAGNOSTIC_PILOT", Set.of("SYNTHETIC"),
                List.of(input("REV_NET", "FLOW", "MONEY")),
                List.of(dep("SYS/VERIFY_PUBLISHED_FACT_SET"))),
            descriptor("FIN-01/REV_NET_MOM", sys,
                List.of(input("REV_NET", "FLOW", "MONEY")),
                List.of(dep("SYS/VERIFY_PUBLISHED_FACT_SET"))),
            descriptor("FIN-02/REV_CATEGORY_SHARE", sys,
                List.of(input("REV_NET", "FLOW", "MONEY"),
                        input("REVENUE_CATEGORY_POPULATION", "POPULATION", "MANIFEST")),
                List.of(dep("SYS/VERIFY_PUBLISHED_FACT_SET"))),
            descriptor("FIN-04/CURRENT_RATIO", sys,
                List.of(input("CURRENT_ASSETS", "STOCK", "MONEY"),
                        input("CURRENT_LIABILITIES", "STOCK", "MONEY")),
                List.of(dep("SYS/VERIFY_PUBLISHED_FACT_SET"))),
            descriptor("CON-01/PARETO_SALES", sys,
                List.of(input("REVIEWED_SKU_POPULATION", "POPULATION", "MANIFEST")),
                List.of(dep("SYS/VERIFY_PUBLISHED_FACT_SET"))),
            descriptor("CON-02/ABC_SALES", sys,
                List.of(input("REVIEWED_SKU_POPULATION", "POPULATION", "MANIFEST")),
                List.of(dep("CON-01/PARETO_SALES")))
        );
    }
    private static MethodVariantDescriptor.InputRequirement input(String code,String measure,
                                                                  String unit) {
        return new MethodVariantDescriptor.InputRequirement(code,measure,unit,
                MethodVariantDescriptor.Role.REQUIRED);
    }
    private static MethodVariantDescriptor.Dependency dep(String code) {
        return new MethodVariantDescriptor.Dependency(code,MethodVariantDescriptor.Role.REQUIRED);
    }
    private static MethodVariantDescriptor descriptor(String code, Set<String> spaces,
                   List<MethodVariantDescriptor.InputRequirement> inputs,
                   List<MethodVariantDescriptor.Dependency> deps) {
        return new MethodVariantDescriptor(code, "R2_A1_CANDIDATE_1",
                DESCRIPTOR_VERSION, UNPINNED_IMPLEMENTATION,
                "LEGACY_RESULT_UNVERIFIED", spaces, inputs, deps, 60, false);
    }

    static void validate(List<MethodVariantDescriptor> definitions,
                         List<MethodTaskAdapter> adapters, Set<String> catalogFamilies) {
        if (definitions == null || adapters == null || catalogFamilies == null)
            throw new IllegalArgumentException("R2_REGISTRY_MISSING");
        var descriptors = new TreeMap<String, MethodVariantDescriptor>();
        for (var d : definitions) {
            if (d == null || descriptors.putIfAbsent(d.methodCode(), d) != null)
                throw new IllegalStateException("R2_REGISTRY_DUPLICATE_METHOD");
            String family = d.methodCode().substring(0,d.methodCode().indexOf('/'));
            if (!"SYS".equals(family) && !catalogFamilies.contains(family))
                throw new IllegalStateException("R2_REGISTRY_UNKNOWN_FAMILY");
        }
        var byAdapter = new HashMap<String, MethodTaskAdapter>();
        for (var adapter : adapters) {
            if (adapter == null || adapter.methodCode() == null ||
                    byAdapter.putIfAbsent(adapter.methodCode(), adapter) != null)
                throw new IllegalStateException("R2_REGISTRY_DUPLICATE_ADAPTER");
        }
        if (!descriptors.keySet().equals(byAdapter.keySet()))
            throw new IllegalStateException("R2_REGISTRY_ADAPTER_BINDING_MISMATCH");
        for (var descriptor : descriptors.values()) {
            var source = byAdapter.get(descriptor.methodCode());
            var expected = descriptor.dependencies().stream()
                    .map(MethodVariantDescriptor.Dependency::methodCode).sorted().toList();
            var actual = source.dependsOn().stream().sorted().toList();
            if (!actual.equals(expected))
                throw new IllegalStateException("R2_REGISTRY_DEPENDENCY_DRIFT");
            if (!descriptors.keySet().containsAll(expected))
                throw new IllegalStateException("R2_REGISTRY_UNKNOWN_DEPENDENCY");
        }
        var visited = new HashSet<String>();
        for (var code : descriptors.keySet())
            visit(code, descriptors, new HashSet<>(), visited);
    }
    private static void visit(String code, Map<String,MethodVariantDescriptor> map,
                              Set<String> visiting, Set<String> visited) {
        if (visited.contains(code)) return;
        if (!visiting.add(code)) throw new IllegalStateException("R2_REGISTRY_DEPENDENCY_CYCLE");
        for (var edge : map.get(code).dependencies()) {
            if (!map.containsKey(edge.methodCode()))
                throw new IllegalStateException("R2_REGISTRY_UNKNOWN_DEPENDENCY");
            visit(edge.methodCode(),map,visiting,visited);
        }
        visiting.remove(code);
        visited.add(code);
    }

    private static Set<String> loadFamilies(ObjectMapper mapper) {
        try (var stream=MethodVariantRegistry.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("R2_REGISTRY_CATALOG_NOT_FOUND");
            var root = mapper.readTree(stream);
            if (!"HOLD".equals(root.path("releaseGate").asText()) ||
                    root.path("methods").size()!=60)
                throw new IllegalStateException("R2_REGISTRY_CATALOG_RELEASE_DRIFT");
            var families = new HashSet<String>();
            for (var m : root.path("methods")) {
                if (m.path("releaseQualified").asBoolean(true)
                        || m.path("autoRunAllowed").asBoolean(true))
                    throw new IllegalStateException("R2_REGISTRY_UNQUALIFIED_PROMOTION");
                String code = m.path("code").asText();
                if (!families.add(code))
                    throw new IllegalStateException("R2_REGISTRY_DUPLICATE_FAMILY");
            }
            return Set.copyOf(families);
        } catch (IOException e) {
            throw new IllegalStateException("R2_REGISTRY_CATALOG_INVALID",e);
        }
    }

    /** Canonical length-prefixed UTF-8: unaffected by HashMap order or JVM locale. */
    static String hash(List<MethodVariantDescriptor> descriptors) {
        try {
            var output=new java.io.ByteArrayOutputStream();
            try (var data=new DataOutputStream(output)) {
                write(data, "FIN_R2_A1_REGISTRY_V1");
                for (var d : descriptors.stream()
                        .sorted(Comparator.comparing(MethodVariantDescriptor::methodCode)).toList()) {
                    write(data,d.methodCode()); write(data,d.variantVersion());
                    write(data,d.descriptorVersion()); write(data,d.implementationVersion());
                    write(data,d.resultSchemaVersion());
                    data.writeInt(d.maxExecutionSeconds());
                    data.writeBoolean(d.methodS2Qualified());
                    for (var space : d.supportedDataSpaces().stream().sorted().toList())
                        write(data,"SPACE:"+space);
                    for (var input : d.inputs().stream()
                            .sorted(Comparator.comparing(MethodVariantDescriptor.InputRequirement::metricCode)).toList()) {
                        write(data,"INPUT:"+input.metricCode());write(data,input.measureKind());
                        write(data,input.unitCode());write(data,input.role().name());
                    }
                    for (var dep : d.dependencies().stream()
                            .sorted(Comparator.comparing(MethodVariantDescriptor.Dependency::methodCode)).toList()) {
                        write(data,"DEP:"+dep.methodCode());write(data,dep.kind().name());
                    }
                    write(data,"END");
                }
            }
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(output.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("R2_REGISTRY_FINGERPRINT_FAILED",e);
        }
    }
    private static void write(DataOutputStream out, String value) throws IOException {
        var bytes=value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
