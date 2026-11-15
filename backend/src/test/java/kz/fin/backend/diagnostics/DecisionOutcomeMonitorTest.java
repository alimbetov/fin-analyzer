package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AnalysisContext;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DecisionOutcomeMonitorTest {
    private static BigDecimal n(String s){return new BigDecimal(s);}
    private DecisionScenarioEngine.Comparison plan() {
        var context=new DecisionScenarioEngine.Context(UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),"KZT",AnalysisContext.DataSpace.SYNTHETIC);
        var months=List.of(new DecisionScenarioEngine.BaselineMonth(YearMonth.of(2026,10),
                n("20"),n("10"),BigDecimal.ZERO));
        var action=List.of(new DecisionScenarioEngine.ActionMonth(YearMonth.of(2026,10),
                n("30"),n("40"),BigDecimal.ZERO));
        return DecisionScenarioEngine.compare(context,"COLLECTION",n("100"),
                months,action,BigDecimal.ZERO);
    }
    @Test void monitoringVarianceNeverClaimsAchievedBusinessImpact(){
        var review=DecisionOutcomeMonitor.compare(plan(),List.of(
                new DecisionOutcomeMonitor.ObservedCash(YearMonth.of(2026,10),n("103"),"post-r1")));
        assertEquals(DecisionOutcomeMonitor.Status.SIMULATED_ONLY,review.status());
        assertEquals(0,n("3").compareTo(review.finalVarianceToPlan()));
        assertEquals("post-r1",review.rows().getFirst().sourceRevision());
        assertTrue(review.reason().contains("NOT_CAUSAL"));
    }
    @Test void missingAndDuplicateObservationsCannotProduceFalseOutcome(){
        var incomplete=DecisionOutcomeMonitor.compare(plan(),List.of());
        assertEquals(DecisionOutcomeMonitor.Status.NOT_COMPUTABLE,incomplete.status());
        assertNull(incomplete.finalVarianceToPlan());
        var repeated=List.of(new DecisionOutcomeMonitor.ObservedCash(YearMonth.of(2026,10),n("103"),"a"),
                new DecisionOutcomeMonitor.ObservedCash(YearMonth.of(2026,10),n("103"),"b"));
        assertThrows(IllegalArgumentException.class,()->DecisionOutcomeMonitor.compare(plan(),repeated));
    }
}
