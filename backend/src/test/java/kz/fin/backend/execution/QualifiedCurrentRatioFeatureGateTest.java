package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.persistence.TrustedAutoReportService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * FIN-04 is not release-qualified. Both scheduler and method-level switches
 * must be explicitly enabled; adding an adapter must not activate production.
 */
class QualifiedCurrentRatioFeatureGateTest {
    private QualifiedCurrentRatioAdapter build(boolean enabled) {
        return new QualifiedCurrentRatioAdapter(
                mock(JdbcTemplate.class),
                mock(TrustedAutoReportService.class),new ObjectMapper(),enabled);
    }

    @Test void unqualifiedMethodIsNotAutomaticallyEligibleByDefault() {
        assertEquals("FIN-04/CURRENT_RATIO",build(false).methodCode());
        assertFalse(build(false).autoEligible());
        assertEquals(java.util.List.of("SYS/VERIFY_PUBLISHED_FACT_SET"),
                build(false).dependsOn());
    }

    @Test void explicitQualificationSandboxSwitchIsRequired() {
        assertTrue(build(true).autoEligible());
    }
}
