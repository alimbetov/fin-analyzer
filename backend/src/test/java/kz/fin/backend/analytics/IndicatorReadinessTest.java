package kz.fin.backend.analytics;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static kz.fin.backend.analytics.IndicatorReadiness.*;

class IndicatorReadinessTest {
    private static Map<String,List<String>> graph() {
        return Map.of("PROJECT_NPV",List.of("PROJECT_FCF","DISCOUNT_RATE"),
                "PROJECT_FCF",List.of("OPERATING_PROFIT","CAPEX"),
                "OPERATING_PROFIT",List.of("REV_NET","COGS"));
    }
    private static SourceEvidence approved(String form) {
        return new SourceEvidence(form,"2026-Q1",InputState.APPROVED);
    }
    private static Map<String,SourceEvidence> ready() {
        return Map.of("REV_NET",approved("F02"),"COGS",approved("C08"),
                "CAPEX",approved("P05"),"DISCOUNT_RATE",approved("P07"));
    }

    @Test void fullDependencyGraphMakesNpvReadyAndCurrent() {
        var d=evaluate("PROJECT_NPV",graph(),ready(),"fingerprint-v1","fingerprint-v1");
        assertEquals(Readiness.READY,d.readiness());
        assertEquals(Freshness.CURRENT,d.freshness());
        assertTrue(d.issues().isEmpty());
    }

    @Test void missingCogsExplainsRootCauseAndOldResultIsStale() {
        Map<String,SourceEvidence> missing=new HashMap<>(ready());
        missing.remove("COGS");
        var d=evaluate("PROJECT_NPV",graph(),missing,"fp-v2","fp-v1");
        assertEquals(Readiness.WAITING_FOR_DATA,d.readiness());
        assertEquals(Freshness.STALE,d.freshness());
        assertEquals(1,d.issues().size());
        assertEquals("COGS",d.issues().getFirst().inputCode());
        assertEquals(InputState.MISSING,d.issues().getFirst().state());
    }

    @Test void pendingApprovalIsDifferentFromMissingOrInvalid() {
        Map<String,SourceEvidence> m=new HashMap<>(ready());
        m.put("DISCOUNT_RATE",new SourceEvidence("P07","2026-Q1",InputState.PENDING_APPROVAL));
        var d=evaluate("PROJECT_NPV",graph(),m,"fp-v2","fp-v1");
        assertEquals(Readiness.WAITING_FOR_APPROVAL,d.readiness());
        assertEquals("P07",d.issues().getFirst().sourceForm());
        m.put("CAPEX",new SourceEvidence("P05","2026-Q1",InputState.INVALID));
        var invalid=evaluate("PROJECT_NPV",graph(),m,"fp-v2","fp-v1");
        assertEquals(Readiness.INVALID_INPUT,invalid.readiness());
    }

    @Test void changingUnrelatedFactDoesNotStaleSameInputFingerprint() {
        var d=evaluate("PROJECT_NPV",graph(),ready(),"deps-v1","deps-v1");
        assertEquals(Freshness.CURRENT,d.freshness());
        assertEquals(Freshness.UNKNOWN,evaluate("PROJECT_NPV",graph(),ready(),"deps-v1",null).freshness());
    }

    @Test void rejectsCyclesAndDoesNotLoseDiagnosticsOnSharedDependencies() {
        assertThrows(IllegalArgumentException.class,()->evaluate("A",
                Map.of("A",List.of("B"),"B",List.of("A")),Map.of(),"new",null));
        var shared=Map.of("A",List.of("B","C"),"B",List.of("D"),"C",List.of("D"));
        var d=evaluate("A",shared,Map.of(),"new",null);
        assertEquals(1,d.issues().size());
        assertEquals("D",d.issues().getFirst().inputCode());
    }
}
