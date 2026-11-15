package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrossFormEvidencePlannerTest {
    private static final YearMonth MONTH=YearMonth.of(2026,10);
    private static Scope scope(){
        return new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                AnalysisContext.DataSpace.SYNTHETIC,"KZT","MANAGEMENT",MONTH);
    }
    private static Evidence observed(String metric,String value){
        return new Evidence(new MetricKey(metric,TimeRole.CURRENT_FLOW),MONTH,
                new BigDecimal(value),"KZT",List.of(
                new Source("F02",UUID.randomUUID(),"pnl",metric,UUID.randomUUID())));
    }
    @Test void aUserStartingWithF02GetsOnlyLinkedEvidenceFormsNotTheEntireCatalog(){
        var report=CrossFormDiagnosticEngine.evaluate(scope(),
                List.of(observed("REV_NET","100"),observed("COGS","60"),observed("OPEX","20")));
        var result=CrossFormEvidencePlanner.plan(report);
        assertEquals("ONE_HOP_VERIFIED_RELATIONSHIPS",result.status());
        assertEquals(List.of("F03","F01"),result.nextForms().stream().map(
                CrossFormEvidencePlanner.Needed::formCode).toList());
        assertTrue(result.nextForms().stream().allMatch(x->
                x.action()==CrossFormEvidencePlanner.Action.PROVIDE_FORM));
        assertTrue(result.nextForms().getFirst().relatedRules().contains("OPERATING_RESULT_TO_CFO"));
        assertFalse(result.nextForms().stream().anyMatch(x->x.formCode().equals("DF-CSH-01")));
    }
    @Test void alreadySuppliedFormWithMissingRelatedMetricRequestsCorrectionNotNewForm(){
        var report=CrossFormDiagnosticEngine.evaluate(scope(),List.of(observed("REV_NET","100")));
        var plan=CrossFormEvidencePlanner.plan(report);
        assertTrue(plan.nextForms().stream().anyMatch(x->x.formCode().equals("F02")
                && x.action()==CrossFormEvidencePlanner.Action.COMPLETE_OR_CORRECT_FORM));
    }
    @Test void noVerifiedAnchorDoesNotAskTheUserToUploadAllRegisteredForms(){
        var report=CrossFormDiagnosticEngine.evaluate(scope(),List.of());
        var plan=CrossFormEvidencePlanner.plan(report);
        assertTrue(plan.nextForms().isEmpty());
        assertEquals("NO_VERIFIED_ANCHOR",plan.status());
    }
}
