package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural acceptance: a registered methodology is NOT assumed to have a
 * qualified Java executor. This test deliberately cannot promote any method.
 */
class MethodologyCatalogContractTest {
    private static final String RESOURCE="/fin/methodology/methodology-catalog-r1.json";

    @Test void allSixtyFamiliesAreUniquelyRegisteredWithFailClosedQualification() throws Exception {
        JsonNode root;
        try(InputStream input=getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(input,"MISSING_METHOD_CATALOG");
            root=new ObjectMapper().readTree(input);
        }
        assertEquals("METHOD-R1-PREQUALIFICATION-1",
                root.path("catalogVersion").asText());
        assertEquals("HOLD",root.path("releaseGate").asText());
        JsonNode methods=root.path("methods");
        assertTrue(methods.isArray());
        assertEquals(60,methods.size());

        var codes=new HashSet<String>();
        var counts=new java.util.HashMap<String,Integer>();
        var format=Pattern.compile("^(FIN|CON|PRO|CASH|BUD|INV|OPS|QLT|STR|ADV)-[0-9]{2}$");
        for(JsonNode method:methods) {
            String code=method.path("code").asText();
            assertTrue(format.matcher(code).matches(),code);
            assertTrue(codes.add(code),"DUPLICATE_METHOD_FAMILY: "+code);
            assertFalse(method.path("releaseQualified").asBoolean(true),
                    "UNVERIFIED_METHOD_PROMOTED: "+code);
            assertFalse(method.path("autoRunAllowed").asBoolean(true),
                    "UNQUALIFIED_AUTO_EXECUTION: "+code);
            assertFalse(method.path("title").asText().isBlank());
            assertFalse(method.path("implementationStatus").asText().isBlank());
            String group=method.path("group").asText();
            assertEquals(group,code.substring(0,code.indexOf('-')));
            counts.merge(group,1,Integer::sum);
        }
        assertEquals(java.util.Map.of(
                "FIN",8,"CON",6,"PRO",6,"CASH",6,"BUD",6,
                "INV",8,"OPS",6,"QLT",6,"STR",4,"ADV",4),counts);
        var fin01=methods.findValuesAsText("code");
        assertTrue(codes.contains("FIN-01"));
        assertTrue(codes.contains("ADV-04"));
    }

    @Test void noFullFamilyIsMarkedQualifiedFromSinglePilotVariant() throws Exception {
        try(InputStream input=getClass().getResourceAsStream(RESOURCE)) {
            var methods=new ObjectMapper().readTree(input).path("methods");
            JsonNode fin01=null;
            for(JsonNode method:methods)
                if("FIN-01".equals(method.path("code").asText())) fin01=method;
            assertNotNull(fin01);
            assertEquals("ONE_VARIANT_WIRED_UNQUALIFIED",
                    fin01.path("implementationStatus").asText());
            assertFalse(fin01.path("autoRunAllowed").asBoolean(true));
            assertFalse(fin01.path("releaseQualified").asBoolean(true));
        }
    }

    @Test void methodCatalogCoverageMatchesRealWiredBusinessVariants() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(input);
            JsonNode methods = new ObjectMapper().readTree(input).path("methods");
            var wired = new HashSet<String>();
            var counts = new java.util.HashMap<String,Integer>();
            for (JsonNode method : methods) {
                String status = method.path("implementationStatus").asText();
                counts.merge(status, 1, Integer::sum);
                if ("ONE_VARIANT_WIRED_UNQUALIFIED".equals(status)) {
                    wired.add(method.path("code").asText());
                    assertTrue(method.hasNonNull("candidateImplementation"));
                }
                assertFalse(method.path("autoRunAllowed").asBoolean());
                assertFalse(method.path("releaseQualified").asBoolean());
            }
            assertEquals(Set.of("FIN-01", "FIN-02", "FIN-04", "CON-01", "CON-02"),
                    wired);
            assertEquals(5, counts.getOrDefault("ONE_VARIANT_WIRED_UNQUALIFIED", 0).intValue());
            assertEquals(11, counts.getOrDefault("CODE_CANDIDATE_NOT_QUALIFIED", 0).intValue());
            assertEquals(2, counts.getOrDefault("PURE_ENGINE_GOLDENS_AUTHORED_NO_TRUSTED_ADAPTER", 0).intValue());
            assertEquals(42, counts.getOrDefault("NOT_YET_AUDITED", 0).intValue());
            assertEquals(60, counts.values().stream().mapToInt(Integer::intValue).sum());

            // These five financial families correspond to seven read-only
            // server adapters; two descriptors are SYS helpers.
            var descriptorFamilies = new HashSet<String>();
            for (var descriptor : MethodVariantRegistry.definitions()) {
                String family = descriptor.methodCode()
                        .substring(0, descriptor.methodCode().indexOf('/'));
                if (!"SYS".equals(family)) descriptorFamilies.add(family);
                assertFalse(descriptor.methodS2Qualified());
            }
            assertEquals(wired, descriptorFamilies);
        }
    }
}
