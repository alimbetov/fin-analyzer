package kz.fin.backend.execution;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SingleExecutorGraphTest {
    private static MethodTaskAdapter adapter(String code, List<String> prerequisites) {
        return new MethodTaskAdapter() {
            @Override public String methodCode() { return code; }
            @Override public List<String> dependsOn() { return prerequisites; }
            @Override public String execute(TrustedRevision revision) { return "{\"ok\":true}"; }
        };
    }
    @Test void rejectsUnknownDependencyRatherThanSchedulingBrokenPlan() {
        var method=adapter("FIN-01",List.of("UNKNOWN"));
        assertThrows(IllegalStateException.class, () -> new SingleExecutorScheduler(
                mock(JdbcTemplate.class),mock(PlatformTransactionManager.class),
                List.of(method),false));
    }
    @Test void rejectsDependencyCycle() {
        var a=adapter("FIN-01",List.of("CON-01"));
        var b=adapter("CON-01",List.of("FIN-01"));
        var thrown=assertThrows(IllegalStateException.class, () -> new SingleExecutorScheduler(
                mock(JdbcTemplate.class),mock(PlatformTransactionManager.class),
                List.of(a,b),false));
        assertTrue(thrown.getMessage().contains("DEPENDENCY_CYCLE"));
    }
    @Test void acceptsAcyclicDependencyGraph() {
        var a=adapter("SYS/VERIFY",List.of());
        var b=adapter("FIN-04",List.of("SYS/VERIFY"));
        assertDoesNotThrow(() -> new SingleExecutorScheduler(
                mock(JdbcTemplate.class),mock(PlatformTransactionManager.class),
                List.of(a,b),false));
    }
    @Test void rejectsAmbiguousMethodRegistration() {
        var a=adapter("FIN-01",List.of());
        var b=adapter("FIN-01",List.of());
        assertThrows(IllegalStateException.class, () -> new SingleExecutorScheduler(
                mock(JdbcTemplate.class),mock(PlatformTransactionManager.class),
                List.of(a,b),false));
    }
}
