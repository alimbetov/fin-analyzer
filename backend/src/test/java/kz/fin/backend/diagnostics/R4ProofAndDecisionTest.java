package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AnalysisContext;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class R4ProofAndDecisionTest {
    private static BigDecimal n(String x){return new BigDecimal(x);}
    private static DiagnosticProofEngine.Context proofContext(){
        return new DiagnosticProofEngine.Context(UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),AnalysisContext.DataSpace.SYNTHETIC,"KZT");
    }
    private static DiagnosticProofEngine.Sku sku(String code,String qty,String price,
                                                  String cost,String source) {
        return new DiagnosticProofEngine.Sku(code,n(qty),n(price),n(cost),source);
    }
    @Test void mixVolumePriceAndCostBridgeExactlyReconcileToObservedProfitChange() {
        var old=List.of(sku("A","10","10","6","old-A"),sku("B","10","20","14","old-B"));
        var now=List.of(sku("A","15","11","7","new-A"),sku("B","5","22","14","new-B"));
        var r=DiagnosticProofEngine.decompose(proofContext(),old,now);
        assertEquals(0,n("100").compareTo(r.grossProfitPrior()));
        assertEquals(0,n("100").compareTo(r.grossProfitCurrent()));
        assertEquals(0,n("0").compareTo(r.volumeEffect()));
        assertEquals(0,n("-10").compareTo(r.mixEffect()));
        assertEquals(0,n("25").compareTo(r.priceEffect()));
        assertEquals(0,n("-15").compareTo(r.costEffect()));
        assertEquals(0,n("0").compareTo(r.deltaGrossProfit()));
        assertEquals(0,BigDecimal.ZERO.compareTo(r.residual()));
        assertEquals(DiagnosticProofEngine.ExplanationStatus.ACCOUNTING_DECOMPOSITION_ONLY,
                r.explanationStatus());
        assertEquals(List.of("old-A","new-A"),r.evidence().get("A"));
    }
    @Test void revenueCanGrowWhileGrossProfitFallsWithoutClaimingRootCause() {
        var old=List.of(sku("A","100","90","54","r0"));
        var now=List.of(sku("A","100","100","65","r1"));
        var r=DiagnosticProofEngine.decompose(proofContext(),old,now);
        assertEquals(0,n("-100").compareTo(r.deltaGrossProfit()));
        assertEquals(0,n("1000").compareTo(r.priceEffect()));
        assertEquals(0,n("-1100").compareTo(r.costEffect()));
        var unqualified=DiagnosticProofEngine.assessMateriality(r,null);
        assertEquals(DiagnosticProofEngine.RiskState.NOT_ASSESSED,unqualified.state());
        assertEquals(0,n("100").compareTo(unqualified.measuredLoss()));
        var approved=new DiagnosticProofEngine.MaterialityPolicy(n("80"),"1.0.0","finance-review",
                LocalDate.of(2026,11,16));
        assertEquals(DiagnosticProofEngine.RiskState.MATERIAL_LOSS,
                DiagnosticProofEngine.assessMateriality(r,approved).state());
    }
    @Test void incompleteDriverPopulationAndDuplicateSkuRejectWithoutFalseBridge() {
        var prior=List.of(sku("A","10","10","6","r0"),sku("B","10","20","14","r0"));
        assertThrows(IllegalArgumentException.class,()->DiagnosticProofEngine.decompose(
                proofContext(),prior,List.of(sku("A","10","10","6","r1"))));
        assertThrows(IllegalArgumentException.class,()->DiagnosticProofEngine.decompose(
                proofContext(),List.of(sku("A","10","10","6","r0"),sku("A","20","10","6","r0")),
                List.of(sku("A","10","10","6","r1"))));
        assertThrows(IllegalArgumentException.class,()->DiagnosticProofEngine.decompose(
                proofContext(),List.of(sku("A","0","10","6","r0")),
                List.of(sku("A","20","10","6","r1"))));
    }
    @Test void unverifiedHypothesesNeverBecomeCausalTruth() {
        var h=new DiagnosticProofEngine.Hypothesis("SUPPLIER_PRICE",
                Set.of("COST_INVOICE","PRICE_LIST"),"Did unit input cost increase?");
        assertEquals(DiagnosticProofEngine.HypothesisState.NOT_TESTED,
                DiagnosticProofEngine.review(h,Set.of("COST_INVOICE"),true).state());
        assertEquals(DiagnosticProofEngine.HypothesisState.INCONCLUSIVE,
                DiagnosticProofEngine.review(h,Set.of("COST_INVOICE","PRICE_LIST"),null).state());
        var verified=DiagnosticProofEngine.review(h,Set.of("COST_INVOICE","PRICE_LIST"),true);
        assertEquals(DiagnosticProofEngine.HypothesisState.CONSISTENT_WITH_EVIDENCE,verified.state());
        assertEquals("CONSISTENCY_IS_NOT_PROOF_OF_CAUSATION",verified.limitation());
    }
    private static DecisionScenarioEngine.Context scenarioContext(){
        return new DecisionScenarioEngine.Context(UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),"KZT",AnalysisContext.DataSpace.SYNTHETIC);
    }
    @Test void actionCostCanMakeHeadlineBenefitEconomicallyNegative() {
        var base=List.of(new DecisionScenarioEngine.BaselineMonth(YearMonth.of(2026,10),
                n("70"),n("75"),n("20")));
        var action=List.of(new DecisionScenarioEngine.ActionMonth(YearMonth.of(2026,10),
                n("30"),n("40"),n("0")));
        var result=DecisionScenarioEngine.compare(scenarioContext(),"COLLECTION",n("100"),
                base,action,BigDecimal.ZERO);
        assertEquals(0,n("95").compareTo(result.doNothingClosingCash()));
        assertEquals(0,n("85").compareTo(result.actionClosingCash()));
        assertEquals(0,n("-10").compareTo(result.totalIncrementalCash()));
        assertEquals(0,n("-10").compareTo(result.discountedIncrementalCash()));
        assertEquals(0,n("65").compareTo(result.minimumActionUnrestricted()));
        assertEquals(DecisionScenarioEngine.Status.SIMULATED_ONLY,result.status());
    }
    @Test void distinctDatesAreMandatoryAndCashMustRollForwardWithoutHiddenPlug() {
        var base=List.of(
                new DecisionScenarioEngine.BaselineMonth(YearMonth.of(2026,10),n("10"),n("20"),n("0")),
                new DecisionScenarioEngine.BaselineMonth(YearMonth.of(2026,11),n("15"),n("10"),n("0")));
        var action=List.of(
                new DecisionScenarioEngine.ActionMonth(YearMonth.of(2026,10),n("5"),n("3"),n("0")),
                new DecisionScenarioEngine.ActionMonth(YearMonth.of(2026,11),n("0"),n("0"),n("1")));
        var r=DecisionScenarioEngine.compare(scenarioContext(),"PLAN",n("20"),base,action,n("0.01"));
        assertEquals(0,n("15").compareTo(r.doNothingClosingCash()));
        assertEquals(0,n("16").compareTo(r.actionClosingCash()));
        assertEquals(0,n("1").compareTo(r.totalIncrementalCash()));
        assertEquals(0,r.actionClosingCash().subtract(r.doNothingClosingCash())
                .compareTo(r.totalIncrementalCash()));
        var wrong=List.of(action.get(0),new DecisionScenarioEngine.ActionMonth(
                YearMonth.of(2026,12),n("0"),n("0"),n("1")));
        assertThrows(IllegalArgumentException.class,()->DecisionScenarioEngine.compare(
                scenarioContext(),"PLAN",n("20"),base,wrong,n("0.01")));
    }
    @Test void syntheticEngineRejectsActualDataAndNegativeCosts() {
        var actual=new DecisionScenarioEngine.Context(UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),"KZT",AnalysisContext.DataSpace.ACTUAL);
        var base=List.of(new DecisionScenarioEngine.BaselineMonth(YearMonth.of(2026,10),n("2"),n("1"),n("0")));
        var action=List.of(new DecisionScenarioEngine.ActionMonth(YearMonth.of(2026,10),n("0"),n("0"),n("0")));
        assertThrows(IllegalArgumentException.class,()->DecisionScenarioEngine.compare(
                actual,"OPTION_A",n("10"),base,action,n("0.01")));
        assertThrows(IllegalArgumentException.class,()->new DecisionScenarioEngine.ActionMonth(
                YearMonth.of(2026,10),n("0"),n("-10"),n("0")));
        assertThrows(IllegalArgumentException.class,()->DecisionScenarioEngine.compare(
                scenarioContext(),"OPTION_A",n("10"),base,action,n("-1")));
    }
}
