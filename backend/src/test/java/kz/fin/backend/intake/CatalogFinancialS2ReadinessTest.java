package kz.fin.backend.intake;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIN-BE-R1 independent-finance-review readiness: validates that the review
 * register really covers every catalog source but does NOT mark forms reviewed.
 *
 * A GREEN test here is proof of correct PENDING statuses and complete tracking,
 * not evidence of an independently qualified financial methodology.
 */
class CatalogFinancialS2ReadinessTest {
    private static final String RESOURCE =
            "/fin/intake/fin-be-r1-financial-s2-review-register-v1.tsv";
    private static final List<String> HEAD = List.of(
            "formCode", "catalog", "formKind", "captureMode", "fieldCount",
            "provisionalRule", "schemaSource", "requiredFinanceEvidence",
            "positiveGoldenFixture", "negativeGoldenFixture", "reviewStatus",
            "financialReviewer", "reviewEvidenceSha256",
            "approvedSemanticContractSha256");

    private static Map<String, Map<String, String>> readRegister() {
        var input = CatalogFinancialS2ReadinessTest.class.getResourceAsStream(RESOURCE);
        assertNotNull(input, "FIN_BE_R1_S2_REGISTER_MISSING");
        var entries = new LinkedHashMap<String, Map<String, String>>();
        try (var reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            assertEquals(String.join("\t", HEAD), reader.readLine(),
                    "FIN_BE_R1_S2_REGISTER_HEADER_DRIFT");
            String line;
            while ((line = reader.readLine()) != null) {
                var fields = line.split("\t", -1);
                assertEquals(HEAD.size(), fields.length, "FIN_BE_R1_S2_ROW_SHAPE");
                var row = new HashMap<String, String>();
                for (int i = 0; i < HEAD.size(); i++) {
                    row.put(HEAD.get(i), fields[i]);
                }
                assertNull(entries.putIfAbsent(fields[0], Map.copyOf(row)),
                        "FIN_BE_R1_S2_DUPLICATE_FORM");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("FIN_BE_R1_S2_REGISTER_READ_FAILED", e);
        }
        assertEquals(166, entries.size());
        return Map.copyOf(entries);
    }

    @Test
    void independentS2RegisterHas166PendingCasesAndNoFabricatedApprovals() {
        var entries = readRegister();
        assertEquals(CatalogCaptureRegistry.all().stream()
                .map(CatalogCaptureRegistry.Definition::code).collect(
                        java.util.stream.Collectors.toSet()), entries.keySet());
        assertEquals(122, entries.values().stream()
                .filter(v -> "TYPED_DRAFT".equals(v.get("captureMode"))).count());
        assertEquals(32, entries.values().stream()
                .filter(v -> "SCHEMA_CANDIDATE".equals(v.get("captureMode"))).count());
        assertEquals(12, entries.values().stream()
                .filter(v -> "EVIDENCE_ONLY".equals(v.get("captureMode"))).count());
        assertEquals(794, entries.values().stream()
                .filter(v -> !"EVIDENCE_ONLY".equals(v.get("captureMode")))
                .mapToInt(v -> Integer.parseInt(v.get("fieldCount"))).sum());

        // Intentionally a fail-closed release gate: an independent human S2
        // review must precede any status change, and the automated implementation
        // is NOT permitted to generate financial decisions or self-approval.
        for (var entry : entries.values()) {
            assertEquals("PENDING_INDEPENDENT_FINANCIAL_REVIEW",
                    entry.get("reviewStatus"));
            assertEquals("NOT_ASSIGNED", entry.get("financialReviewer"));
            assertEquals("NOT_PROVIDED", entry.get("reviewEvidenceSha256"));
            assertEquals("NOT_PROVIDED", entry.get("approvedSemanticContractSha256"));
        }
    }

    @TestFactory
    Stream<DynamicTest> all166RequireSpecificIndependentReviewEvidence() {
        var entries = readRegister();
        return CatalogCaptureRegistry.all().stream().map(form ->
                DynamicTest.dynamicTest("BE-R1-S2-REQUIRED-" + form.code(), () -> {
                    var row = entries.get(form.code());
                    assertNotNull(row);
                    assertEquals(form.catalog(), row.get("catalog"));
                    assertEquals(form.kind(), row.get("formKind"));
                    String mode = form.captureMode() ==
                            CatalogCaptureRegistry.Mode.SCHEMA_NOT_APPROVED
                            ? "SCHEMA_CANDIDATE" : form.captureMode().name();
                    assertEquals(mode, row.get("captureMode"));
                    int fields = form.captureMode() ==
                            CatalogCaptureRegistry.Mode.EVIDENCE_ONLY ? 0 :
                            CatalogXlsxRoundTripService.fields(form.code()).size();
                    assertEquals(Integer.toString(fields), row.get("fieldCount"));
                    if (form.captureMode() == CatalogCaptureRegistry.Mode.SCHEMA_NOT_APPROVED) {
                        assertEquals(SpecializedSchemaCandidateRegistry.require(
                                form.code()).invariant(), row.get("provisionalRule"));
                        assertTrue(row.get("requiredFinanceEvidence").contains("INVARIANT"));
                    } else if (form.captureMode() == CatalogCaptureRegistry.Mode.EVIDENCE_ONLY) {
                        assertTrue(row.get("requiredFinanceEvidence")
                                .contains("ISSUER_VALIDATION"));
                    } else {
                        assertTrue(row.get("requiredFinanceEvidence")
                                .contains("SOURCE_MAPPING"));
                    }
                    assertEquals("S2-" + form.code() + "-POSITIVE-REQUIRED",
                            row.get("positiveGoldenFixture"));
                    assertEquals("S2-" + form.code() + "-NEGATIVE-REQUIRED",
                            row.get("negativeGoldenFixture"));
                    assertEquals("PENDING_INDEPENDENT_FINANCIAL_REVIEW",
                            row.get("reviewStatus"));
                }));
    }
}
