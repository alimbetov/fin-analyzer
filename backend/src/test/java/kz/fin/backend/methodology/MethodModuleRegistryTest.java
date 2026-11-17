package kz.fin.backend.methodology;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.execution.MethodTaskAdapter;
import kz.fin.backend.execution.MethodVariantDescriptor;
import kz.fin.backend.execution.MethodVariantRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MethodModuleRegistryTest {
    private static MethodTaskAdapter stub(MethodVariantDescriptor d) {
        return new MethodTaskAdapter() {
            @Override public String methodCode() { return d.methodCode(); }
            @Override public List<String> dependsOn() {
                return d.dependencies().stream()
                        .map(MethodVariantDescriptor.Dependency::methodCode).toList();
            }
            @Override public String execute(TrustedRevision revision) {
                throw new AssertionError("M1_READ_ONLY_MUST_NOT_EXECUTE");
            }
        };
    }

    private static List<MethodTaskAdapter> adapters() {
        return MethodVariantRegistryTestFixtures.descriptors().stream()
                .map(MethodModuleRegistryTest::stub).toList();
    }

    @Test void bindsSevenLegacyAdaptersWithNoExecutionPermit() {
        var adapters = adapters();
        var variants = new MethodVariantRegistry(adapters, new ObjectMapper());
        var registry = new MethodModuleRegistry(adapters, variants);
        assertEquals(7, registry.allDefinitions().size());
        assertEquals("FIN-01/REV_NET_MOM",
                registry.require("FIN-01/REV_NET_MOM").methodCode());
        assertEquals(CalculationEngine.JAVA,
                registry.require("FIN-01/REV_NET_MOM").engine());
        assertEquals(List.of("CON-01/PARETO_SALES"),
                registry.require("CON-02/ABC_SALES").dependencies());
        assertTrue(registry.allDefinitions().stream()
                .noneMatch(MethodModule.Definition::implementationPinned));
        assertTrue(registry.allDefinitions().stream()
                .noneMatch(MethodModule.Definition::methodS2Qualified));
        assertEquals("R2_METHOD_IMPLEMENTATION_NOT_PINNED",
                assertThrows(IllegalStateException.class, () ->
                        registry.requireExecutableBinding("FIN-01/REV_NET_MOM")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> registry.require("FIN-99/UNKNOWN"));
    }

    @Test void failsOnMissingUnexpectedAndDuplicateAdapters() {
        var adapters = new ArrayList<>(adapters());
        var variants = new MethodVariantRegistry(adapters, new ObjectMapper());
        assertThrows(IllegalStateException.class, () ->
                new MethodModuleRegistry(adapters.subList(0, 6), variants));
        adapters.add(adapters.getFirst());
        assertThrows(IllegalStateException.class, () ->
                new MethodModuleRegistry(adapters, variants));
    }

    @Test void failsOnDuplicateModuleUnknownDependencyAndCycle() {
        var parent = module("SYS/VERIFY_PUBLISHED_FACT_SET", List.of());
        var child = module("FIN-01/REV_NET_MOM",
                List.of("SYS/VERIFY_PUBLISHED_FACT_SET"));
        assertEquals(2, new MethodModuleRegistry(List.of(parent, child))
                .allDefinitions().size());
        assertThrows(IllegalStateException.class, () ->
                new MethodModuleRegistry(List.of(parent, parent)));
        assertThrows(IllegalStateException.class, () ->
                new MethodModuleRegistry(List.of(child)));
        var cycleA = module("FIN-01/REV_NET_MOM", List.of("FIN-02/REV_CATEGORY_SHARE"));
        var cycleB = module("FIN-02/REV_CATEGORY_SHARE", List.of("FIN-01/REV_NET_MOM"));
        assertThrows(IllegalStateException.class, () ->
                new MethodModuleRegistry(List.of(cycleA, cycleB)));
    }

    @Test void metadataRejectsUnknownEngineSelfDependencyAndUnpinnedApproval() {
        assertThrows(NullPointerException.class, () -> new MethodModule.Definition(
                "FIN-01/REV_NET_MOM", "v1", "v1", "v1", "v1", null,
                List.of(), false, false));
        assertThrows(IllegalArgumentException.class, () -> new MethodModule.Definition(
                "FIN-01/REV_NET_MOM", "v1", "v1", "v1", "v1", CalculationEngine.SQL,
                List.of("FIN-01/REV_NET_MOM"), true, false));
        assertThrows(IllegalArgumentException.class, () -> new MethodModule.Definition(
                "FIN-01/REV_NET_MOM", "v1", "v1", "v1", "v1", CalculationEngine.JAVA,
                List.of(), false, true));
    }

    private static MethodModule module(String code, List<String> deps) {
        return () -> new MethodModule.Definition(code, "v1", "v1", "LEGACY_UNPINNED",
                "LEGACY_RESULT_UNVERIFIED", CalculationEngine.JAVA, deps, false, false);
    }

    private static final class MethodVariantRegistryTestFixtures {
        static List<MethodVariantDescriptor> descriptors() {
            // These are the exact seven baseline descriptors, reconstructed
            // from the immutable candidate registry contract.
            var methods = List.of(
                    "SYS/VERIFY_PUBLISHED_FACT_SET",
                    "SYS/DIAGNOSTIC_PILOT",
                    "FIN-01/REV_NET_MOM",
                    "FIN-02/REV_CATEGORY_SHARE",
                    "FIN-04/CURRENT_RATIO",
                    "CON-01/PARETO_SALES",
                    "CON-02/ABC_SALES");
            var output = new ArrayList<MethodVariantDescriptor>();
            for (var code : methods) {
                String prerequisite = code.equals("SYS/VERIFY_PUBLISHED_FACT_SET") ? null :
                        code.equals("CON-02/ABC_SALES") ? "CON-01/PARETO_SALES" :
                        "SYS/VERIFY_PUBLISHED_FACT_SET";
                var inputs = switch (code) {
                    case "SYS/DIAGNOSTIC_PILOT", "FIN-01/REV_NET_MOM" ->
                            List.of(input("REV_NET", "FLOW", "MONEY"));
                    case "FIN-02/REV_CATEGORY_SHARE" ->
                            List.of(input("REV_NET", "FLOW", "MONEY"),
                                    input("REVENUE_CATEGORY_POPULATION", "POPULATION", "MANIFEST"));
                    case "FIN-04/CURRENT_RATIO" ->
                            List.of(input("CURRENT_ASSETS", "STOCK", "MONEY"),
                                    input("CURRENT_LIABILITIES", "STOCK", "MONEY"));
                    case "CON-01/PARETO_SALES", "CON-02/ABC_SALES" ->
                            List.of(input("REVIEWED_SKU_POPULATION", "POPULATION", "MANIFEST"));
                    default -> List.<MethodVariantDescriptor.InputRequirement>of();
                };
                output.add(new MethodVariantDescriptor(code, "R2_A1_CANDIDATE_1", "R2_A1_1",
                        "LEGACY_UNPINNED", "LEGACY_RESULT_UNVERIFIED",
                        code.equals("SYS/DIAGNOSTIC_PILOT") ?
                                Set.of("SYNTHETIC") : Set.of("SYNTHETIC", "ACTUAL"),
                        inputs,
                        prerequisite == null ? List.of() : List.of(
                                new MethodVariantDescriptor.Dependency(prerequisite,
                                        MethodVariantDescriptor.Role.REQUIRED)),
                        60, false));
            }
            return List.copyOf(output);
        }

        static MethodVariantDescriptor.InputRequirement input(String code, String kind,
                                                               String unit) {
            return new MethodVariantDescriptor.InputRequirement(code, kind, unit,
                    MethodVariantDescriptor.Role.REQUIRED);
        }
    }
}
