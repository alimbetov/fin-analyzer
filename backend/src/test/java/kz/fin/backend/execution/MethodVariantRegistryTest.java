package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MethodVariantRegistryTest {
    private static MethodTaskAdapter stub(MethodVariantDescriptor d) {
        return new MethodTaskAdapter() {
            @Override public String methodCode() { return d.methodCode(); }
            @Override public List<String> dependsOn() {
                return d.dependencies().stream()
                        .map(MethodVariantDescriptor.Dependency::methodCode).toList();
            }
            @Override public String execute(TrustedRevision revision) {
                throw new AssertionError("R2_A1_MUST_NOT_EXECUTE");
            }
        };
    }
    private static List<MethodTaskAdapter> adapters(List<MethodVariantDescriptor> ds) {
        return ds.stream().map(MethodVariantRegistryTest::stub).toList();
    }
    private static Set<String> families() {
        return Set.of("FIN-01","FIN-02","FIN-04","CON-01","CON-02");
    }
    private static MethodVariantDescriptor copy(MethodVariantDescriptor d,
                 List<MethodVariantDescriptor.Dependency> deps) {
        return new MethodVariantDescriptor(d.methodCode(),d.variantVersion(),
                d.descriptorVersion(),d.implementationVersion(),
                d.resultSchemaVersion(),d.supportedDataSpaces(),
                d.inputs(),deps,d.maxExecutionSeconds(),false);
    }
    @Test void registersExactlyExistingSevenCandidatesAndKeepsQualificationOnHold() {
        var descriptions=MethodVariantRegistry.definitions();
        var registry=new MethodVariantRegistry(adapters(descriptions),new ObjectMapper());
        assertEquals(7,registry.all().size());
        assertEquals(64,registry.fingerprint().length());
        assertEquals("R2_A1_CANDIDATE_1",registry.require("FIN-01/REV_NET_MOM").variantVersion());
        assertTrue(registry.all().stream().noneMatch(MethodVariantDescriptor::methodS2Qualified));
        assertThrows(IllegalArgumentException.class,()->registry.require("FIN-03/TREND"));
        assertEquals(Set.of("SYNTHETIC"),
                registry.require("SYS/DIAGNOSTIC_PILOT").supportedDataSpaces());
    }
    @Test void canonicalFingerprintIsStableAcrossShuffledDescriptionsAndInputSetOrders() {
        var ds=new ArrayList<>(MethodVariantRegistry.definitions());
        String first=MethodVariantRegistry.hash(ds);
        java.util.Collections.reverse(ds);
        assertEquals(first,MethodVariantRegistry.hash(ds));
        var altered=new ArrayList<>(ds);
        var d=altered.getFirst();
        altered.set(0,new MethodVariantDescriptor(d.methodCode(),"R2_A1_CANDIDATE_2",
                d.descriptorVersion(),d.implementationVersion(),d.resultSchemaVersion(),
                d.supportedDataSpaces(),d.inputs(),d.dependencies(),d.maxExecutionSeconds(),false));
        assertNotEquals(first,MethodVariantRegistry.hash(altered));
    }
    @Test void rejectsDuplicateMethodAndDuplicateAdapterAndUnknownFamily() {
        var ds=MethodVariantRegistry.definitions();
        var duplicate=new ArrayList<>(ds);duplicate.add(ds.getFirst());
        assertThrows(IllegalStateException.class,()->MethodVariantRegistry.validate(
                duplicate,adapters(ds),families()));
        var duplicatedAdapters=new ArrayList<>(adapters(ds));
        duplicatedAdapters.add(stub(ds.getFirst()));
        assertThrows(IllegalStateException.class,()->MethodVariantRegistry.validate(
                ds,duplicatedAdapters,families()));
        assertThrows(IllegalStateException.class,()->MethodVariantRegistry.validate(
                ds,adapters(ds),Set.of("FIN-01")));
    }
    @Test void rejectsMissingBindingsAndUnknownDependencyAndCycles() {
        var ds=MethodVariantRegistry.definitions();
        assertThrows(IllegalStateException.class,()->MethodVariantRegistry.validate(
                ds,adapters(ds.subList(0,6)),families()));
        var first=ds.getFirst();
        var modified=new ArrayList<>(ds);
        modified.set(0,copy(first,List.of(new MethodVariantDescriptor.Dependency(
                "SYS/NONEXISTENT",MethodVariantDescriptor.Role.REQUIRED))));
        assertThrows(IllegalStateException.class,()->MethodVariantRegistry.validate(
                modified,adapters(modified),families()));
        var cycle=new ArrayList<>(ds);
        cycle.set(0,copy(first,List.of(new MethodVariantDescriptor.Dependency(
                "SYS/DIAGNOSTIC_PILOT",MethodVariantDescriptor.Role.REQUIRED))));
        assertThrows(IllegalStateException.class,()->MethodVariantRegistry.validate(
                cycle,adapters(cycle),families()));
    }
    @Test void invalidUnitsDuplicatesVersionsAndS2PromotionRejected() {
        var d=MethodVariantRegistry.definitions().getFirst();
        assertThrows(IllegalArgumentException.class,()->new MethodVariantDescriptor.InputRequirement(
                "REV_NET","WRONG","MONEY",MethodVariantDescriptor.Role.REQUIRED));
        assertThrows(IllegalArgumentException.class,()->new MethodVariantDescriptor(
                "REV_NET",d.variantVersion(),d.descriptorVersion(),
                d.implementationVersion(),d.resultSchemaVersion(),d.supportedDataSpaces(),
                d.inputs(),d.dependencies(),60,false));
        assertThrows(IllegalArgumentException.class,()->new MethodVariantDescriptor.Dependency(
                "REV_NET",MethodVariantDescriptor.Role.REQUIRED));
        assertThrows(IllegalArgumentException.class,()->new MethodVariantDescriptor(
                d.methodCode(), "",d.descriptorVersion(),d.implementationVersion(),
                d.resultSchemaVersion(),d.supportedDataSpaces(),d.inputs(),d.dependencies(),60,false));
        assertThrows(IllegalArgumentException.class,()->new MethodVariantDescriptor(
                d.methodCode(), d.variantVersion(),d.descriptorVersion(),d.implementationVersion(),
                d.resultSchemaVersion(),d.supportedDataSpaces(),d.inputs(),d.dependencies(),60,true));
        assertThrows(IllegalArgumentException.class,()->new MethodVariantDescriptor(
                d.methodCode(), d.variantVersion(),d.descriptorVersion(),d.implementationVersion(),
                d.resultSchemaVersion(),Set.of(),d.inputs(),d.dependencies(),60,false));
    }
}
