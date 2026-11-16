package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** A registered adapter must remain OFF until explicitly enabled and accepted. */
class QualifiedSkuConcentrationFeatureGateTest {
    private static ReviewedSkuPopulationLoader emptyReader() {
        return new ReviewedSkuPopulationLoader(mock(org.springframework.jdbc.core.JdbcTemplate.class));
    }

    @Test void bothMethodsDefaultToFailClosedAutoEligibility() {
        var reader=emptyReader();
        var pareto=new QualifiedSkuParetoAdapter(reader,new ObjectMapper(),false);
        var abc=new QualifiedSkuAbcAdapter(reader,new ObjectMapper(),false);
        assertEquals("CON-01/PARETO_SALES",pareto.methodCode());
        assertEquals("CON-02/ABC_SALES",abc.methodCode());
        assertFalse(pareto.autoEligible());
        assertFalse(abc.autoEligible());
        assertEquals(List.of("SYS/VERIFY_PUBLISHED_FACT_SET"),pareto.dependsOn());
        assertEquals(List.of(pareto.methodCode()),abc.dependsOn());
    }

    @Test void sandboxOptInDoesNotChangeMethodFamilyQualification() {
        var reader=emptyReader();
        var pareto=new QualifiedSkuParetoAdapter(reader,new ObjectMapper(),true);
        var abc=new QualifiedSkuAbcAdapter(reader,new ObjectMapper(),true);
        assertTrue(pareto.autoEligible());
        assertTrue(abc.autoEligible());
        assertFalse(pareto.applicable(new MethodTaskAdapter.TrustedRevision(
                java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),
                "0".repeat(64),"SYNTHETIC")));
    }
}
