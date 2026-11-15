package kz.fin.backend.diagnostics;

import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.MetricKey;
import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.TimeRole;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndicatorRelationshipRegistryTest {
    private static String resource(String name) throws Exception {
        try(InputStream stream=IndicatorRelationshipRegistryTest.class
                .getResourceAsStream("/fin/"+name)){
            assertNotNull(stream);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test void everyCatalogFormHasExplicitSafeDispositionAndOnlyFiveFormsHavePilotLinks(){
        var registry=IndicatorRelationshipRegistry.bundled();
        assertEquals(166,registry.forms().size());
        assertEquals(6,registry.relationships().size());
        assertEquals(Set.of("F01","F02","F03","DF-CSH-01","DF-CSH-02"),
                registry.referencedForms());
        assertEquals(5,registry.forms().values().stream().filter(f->
                f.disposition()==IndicatorRelationshipRegistry.FormDisposition.PILOT_NUMERIC_SOURCE).count());
        assertEquals(12,registry.forms().values().stream().filter(f->
                f.disposition()==IndicatorRelationshipRegistry.FormDisposition.EVIDENCE_ONLY).count());
        assertEquals(149,registry.forms().values().stream().filter(f->
                f.disposition()==IndicatorRelationshipRegistry.FormDisposition.NUMERIC_CANDIDATE_UNQUALIFIED).count());
        assertTrue(registry.forms().values().stream().allMatch(f->
                f.qualification().equals("DESIGN_ONLY") ||
                        f.qualification().equals("PILOT_UNQUALIFIED")));
        assertEquals(64,registry.definitionHash().length());
        assertEquals(registry.definitionHash(),IndicatorRelationshipRegistry.bundled().definitionHash());
    }
    @Test void executableRegistryExactlyMatchesFinancialEngineRules(){
        var registry=IndicatorRelationshipRegistry.bundled();
        assertEquals(CrossFormDiagnosticEngine.RULES,registry.executableSyntheticRules());
        assertEquals(registry.definitionHash(),CrossFormDiagnosticEngine.RELATIONSHIPS.definitionHash());
        assertTrue(registry.relationships().stream().allMatch(r->
                r.qualification()==IndicatorRelationshipRegistry.Qualification.SYNTHETIC_PILOT_UNQUALIFIED));
        assertEquals("F01",registry.originByMetric().get("CASH_END"));
        assertEquals("F03",registry.originByMetric().get("CFO"));
    }
    @Test void aTamperedRegistryNeverInventsAMethodOrLinksDocuments() throws Exception {
        String catalog=resource("form-compatibility.tsv");
        String original=resource("indicator-relationships-v1.tsv");
        assertThrows(IllegalArgumentException.class,
                ()->IndicatorRelationshipRegistry.parse(catalog,
                        original.replace("ASSETS:CURRENT_STOCK:F01","ASSETS:CURRENT_STOCK:D01")));
        assertThrows(IllegalArgumentException.class,
                ()->IndicatorRelationshipRegistry.parse(catalog,
                        original.replace("ASSETS:CURRENT_STOCK:F01","ASSETS:CURRENT_FLOW:F01")));
        assertThrows(IllegalArgumentException.class,
                ()->IndicatorRelationshipRegistry.parse(catalog,
                        original.replace("ASSETS:CURRENT_STOCK:F01","ASSETS:CURRENT_STOCK:SF-G01")));
        assertThrows(IllegalArgumentException.class,
                ()->IndicatorRelationshipRegistry.parse(catalog,
                        original.replace("SYNTHETIC_PILOT_UNQUALIFIED","PRODUCTION_QUALIFIED")));
        assertThrows(IllegalArgumentException.class,
                ()->IndicatorRelationshipRegistry.parse(catalog,
                        original+"GROSS_MARGIN\t1.0.0\tECONOMIC_BRIDGE\tGROSS_MARGIN\tSYNTHETIC_PILOT_UNQUALIFIED\tSAME_PUBLISHED_SCOPE\tMONTH_AND_PRIOR_CLOSE\tREV_NET:CURRENT_FLOW:F02;COGS:CURRENT_FLOW:F02\n"));
        assertThrows(IllegalArgumentException.class,
                ()->IndicatorRelationshipRegistry.parse(catalog.replace("F01\t","F99\t"),original));
    }
    @Test void anyCatalogFieldContractChangeInvalidatesThePinnedRegistryHash() throws Exception {
        String catalog=resource("form-compatibility.tsv");
        String policy=resource("indicator-relationships-v1.tsv");
        String edited=catalog.replace("C01\tFCP_TD\tSTRUCTURED\tReceivableLedger\tSOURCE_ROW\t",
                "C01\tFCP_TD\tSTRUCTURED\tReceivableLedger\tAS_OF\t");
        assertNotEquals(catalog,edited);
        var previous=IndicatorRelationshipRegistry.parse(catalog,policy);
        var next=IndicatorRelationshipRegistry.parse(edited,policy);
        assertNotEquals(previous.definitionHash(),next.definitionHash());
    }

    @Test void businessProblemsAreVersionedAndCannotInventGraphMetrics() throws Exception {
        var registry=IndicatorRelationshipRegistry.bundled();
        var problems=DiagnosticProblemCatalog.bundled();
        assertEquals(4,problems.routes().size());
        assertEquals(List.of(new MetricKey("REV_NET",TimeRole.CURRENT_FLOW),
                new MetricKey("COGS",TimeRole.CURRENT_FLOW)),
                problems.anchors("PROFITABILITY"));
        assertTrue(problems.anchors("UNKNOWN_PROBLEM").isEmpty());
        String routes=resource("diagnostic-problem-routes-v1.tsv");
        assertThrows(IllegalArgumentException.class,
                ()->DiagnosticProblemCatalog.parse(
                        routes.replace("CFO:CURRENT_FLOW","UNSUPPORTED:CURRENT_FLOW"),registry));
        assertThrows(IllegalArgumentException.class,
                ()->DiagnosticProblemCatalog.parse(
                        routes.replace("SYNTHETIC_PILOT_UNQUALIFIED","PRODUCTION_QUALIFIED"),registry));
        assertThrows(IllegalArgumentException.class,
                ()->DiagnosticProblemCatalog.parse(
                        routes.replace("CASH_END:CURRENT_STOCK;CFO:CURRENT_FLOW",
                                "CFO:CURRENT_FLOW;CFO:CURRENT_FLOW"),registry));
    }

    @Test void graphTerminatesAndDoesNotFabricateRelationshipsForUnqualifiedCatalogForms(){
        var c=new MetricKey("REV_NET",TimeRole.CURRENT_FLOW);
        var cost=new MetricKey("COGS",TimeRole.CURRENT_FLOW);
        var op=new MetricKey("OPEX",TimeRole.CURRENT_FLOW);
        var available=Set.of(c,cost,op);
        var near=DynamicDiagnosticPlanner.fromVerifiedFacts(available,1);
        assertEquals(DynamicDiagnosticPlanner.Status.RELATED_METHODS_FOUND,near.status());
        assertEquals(Set.of("GROSS_MARGIN","OPERATING_RESULT_TO_CFO","RECEIVABLES_CASH_STRESS"),
                new HashSet<>(near.relatedRuleCodes()));
        assertEquals(Set.of("F03","F01"),new HashSet<>(near.missingEvidence().stream()
                .map(DynamicDiagnosticPlanner.Demand::originForm).toList()));
        var far=DynamicDiagnosticPlanner.fromVerifiedFacts(available,3);
        assertEquals(5,far.relatedRuleCodes().size());
        assertFalse(far.relatedRuleCodes().contains("BALANCE_IDENTITY"),
                "balance identity is a separate metric component, not a fabricated correlation");
        assertTrue(far.missingEvidence().stream().anyMatch(x->x.originForm().equals("DF-CSH-01")));
        assertEquals(new HashSet<>(far.missingEvidence().stream().map(
                DynamicDiagnosticPlanner.Demand::metric).toList()).size(),far.missingEvidence().size());
        assertEquals(far,DynamicDiagnosticPlanner.fromVerifiedFacts(available,3));
        assertThrows(IllegalArgumentException.class,
                ()->DynamicDiagnosticPlanner.fromVerifiedFacts(available,4));
        assertEquals(DynamicDiagnosticPlanner.Status.NO_REVIEWED_RELATIONSHIP,
                DynamicDiagnosticPlanner.fromForm("SF-G01",Set.of(),2).status());
        assertEquals(DynamicDiagnosticPlanner.Status.NO_REVIEWED_RELATIONSHIP,
                DynamicDiagnosticPlanner.fromForm("D01",Set.of(),2).status());
        assertEquals(DynamicDiagnosticPlanner.Status.NO_REVIEWED_RELATIONSHIP,
                DynamicDiagnosticPlanner.fromProblem("FAKE_PROBLEM",Set.of(),2).status());
        assertEquals(DynamicDiagnosticPlanner.Status.RELATED_METHODS_FOUND,
                DynamicDiagnosticPlanner.fromProblem("CASH_SHORTFALL",Set.of(),2).status());
    }
}
