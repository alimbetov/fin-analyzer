package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.analytics.AutoIndicatorEngine;
import kz.fin.backend.analytics.AutoIndicatorEngine.*;
import kz.fin.backend.domain.EconomicKernel;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static kz.fin.backend.diagnostics.BusinessDiagnosticEngine.*;

class BusinessDiagnosticEngineTest {
    private static final UUID ORG=UUID.randomUUID(),ENTITY=UUID.randomUUID(),
            SCOPE=UUID.randomUUID(),REVISION=UUID.randomUUID();
    private static final YearMonth CURRENT=YearMonth.of(2026,10);
    private static final EconomicKernel.ReportingBasis BASIS=EconomicKernel.ReportingBasis.MANAGEMENT;

    private static SeriesKey key(String code, String currency, UUID revision){
        boolean stock=Set.of("CASH_END","AR_END").contains(code);
        return new SeriesKey(ORG,ENTITY,SCOPE,revision,code,"v1",
                stock?MeasureKind.STOCK:MeasureKind.FLOW,"MONEY",currency,
                BASIS,AnalysisContext.DataSpace.SYNTHETIC,new TreeMap<>());
    }
    private static MetricHistory history(String code,String currency,UUID revision,
                                         Map<YearMonth,String> overrides,Set<YearMonth> missing){
        SeriesKey key=key(code,currency,revision);
        List<Observation> observations=new ArrayList<>();
        for(int back=12;back>=0;back--){
            YearMonth month=CURRENT.minusMonths(back);
            if(missing.contains(month))continue;
            String number=overrides.getOrDefault(month,
                    code.equals("CASH_END")?"200":code.equals("AR_END")?"80":
                    code.equals("COGS")?"60":"100");
            var time=key.measureKind()==MeasureKind.STOCK
                    ? EconomicKernel.TemporalReference.asOf(month.atEndOfMonth())
                    : EconomicKernel.TemporalReference.interval(month.atDay(1),
                            month.plusMonths(1).atDay(1));
            observations.add(new Observation(key,time,EconomicKernel.EconomicValue.present(number)));
        }
        return new MetricHistory(key,observations);
    }
    private static MetricHistory hist(String code,Map<YearMonth,String> overrides){
        return history(code,"KZT",REVISION,overrides,Set.of());
    }
    private static List<RuleDefinition> rules(RuleCode code){
        return PILOT_RULES.stream().filter(r->r.code()==code).toList();
    }
    private static void eq(String expected,Calculation c){
        assertEquals(Status.CALCULATED,c.status(),c.reason());
        assertEquals(0,new BigDecimal(expected).compareTo(c.value()),c.toString());
    }

    @Test void completeHistoryComputesMomYoyTtmAndThreeMonthMean(){
        var revenue=hist("REV_NET",Map.of(CURRENT,"150",CURRENT.minusYears(1),"120"));
        Trend trend=trend(CURRENT,revenue);
        eq("150",trend.current());
        eq("100",trend.previousMonth());
        eq("120",trend.yearAgo());
        eq("50",trend.momAbsolute());
        eq("50",trend.momPercent());
        eq("30",trend.yoyAbsolute());
        eq("25",trend.yoyPercent());
        eq("1250",trend.ttm());
        eq("116.666666666667",trend.movingAverage3());
    }

    @Test void stockUsesClosingSnapshotNotSumAndTtmIsNotComputable(){
        Trend cash=trend(CURRENT,hist("CASH_END",Map.of(CURRENT,"150")));
        eq("150",cash.current());
        eq("200",cash.previousMonth());
        eq("-50",cash.momAbsolute());
        assertEquals(Status.NOT_COMPUTABLE,cash.ttm().status());
        assertEquals("TTM_REQUIRES_FLOW",cash.ttm().reason());
    }

    @Test void missingYearAgoKeepsMomButBlocksYoYAndTtm(){
        var revenue=history("REV_NET","KZT",REVISION,Map.of(),Set.of(CURRENT.minusYears(1)));
        Trend t=trend(CURRENT,revenue);
        eq("0",t.momAbsolute());
        assertEquals(Status.INCOMPLETE,t.yearAgo().status());
        assertEquals(Status.INCOMPLETE,t.yoyPercent().status());
        assertEquals(Status.CALCULATED,t.ttm().status()); // last 12 months complete
        var assessment=assess(CURRENT,List.of(revenue),rules(RuleCode.REVENUE_YOY_DECLINE));
        assertEquals(1,assessment.findings().size());
        assertEquals(FindingKind.MISSING_EVIDENCE,assessment.findings().getFirst().kind());
        assertEquals(List.of("REV_NET:YEAR_AGO"),assessment.findings().getFirst().missingMetrics());
    }

    @Test void zeroPreviousValueDoesNotBecomeFalsePercentage(){
        Trend t=trend(CURRENT,hist("REV_NET",Map.of(CURRENT.minusMonths(1),"0")));
        eq("100",t.momAbsolute());
        assertEquals(Status.NOT_COMPUTABLE,t.momPercent().status());
        assertEquals("ZERO_BASELINE",t.momPercent().reason());
    }

    @Test void yOyRevenueDeclineHasImmutableNumericEvidence(){
        var a=assess(CURRENT,List.of(hist("REV_NET",
                Map.of(CURRENT.minusYears(1),"120",CURRENT,"80"))),
                rules(RuleCode.REVENUE_YOY_DECLINE));
        assertEquals(1,a.findings().size());
        Finding f=a.findings().getFirst();
        assertEquals(FindingKind.OBSERVATION,f.kind());
        assertEquals(RuleCode.REVENUE_YOY_DECLINE,f.ruleCode());
        assertEquals("REVENUE_DRIVER_REVIEW",f.playbookCode());
        assertEquals(List.of(CURRENT,CURRENT.minusYears(1)),
                f.evidence().stream().map(Evidence::month).toList());
        assertThrows(UnsupportedOperationException.class,()->f.evidence().clear());
    }

    @Test void revenueGrowthDoesNotTriggerDeclineRule(){
        var a=assess(CURRENT,List.of(hist("REV_NET",Map.of(CURRENT,"130"))),
                rules(RuleCode.REVENUE_YOY_DECLINE));
        assertTrue(a.findings().isEmpty());
    }

    @Test void marginCompressionUsesMatchedCostsNotAverageOfPercentages(){
        var a=assess(CURRENT,List.of(
                    hist("REV_NET",Map.of(CURRENT.minusYears(1),"100",CURRENT,"100")),
                    hist("COGS",Map.of(CURRENT.minusYears(1),"50",CURRENT,"70"))),
                rules(RuleCode.GROSS_MARGIN_YOY_COMPRESSION));
        assertEquals(1,a.findings().size());
        assertEquals(FindingKind.OBSERVATION,a.findings().getFirst().kind());
        assertEquals(4,a.findings().getFirst().evidence().size());
    }

    @Test void zeroRevenueBlocksMarginConclusionWithReason(){
        var a=assess(CURRENT,List.of(
                    hist("REV_NET",Map.of(CURRENT,"0")),
                    hist("COGS",Map.of(CURRENT,"0"))),
                rules(RuleCode.GROSS_MARGIN_YOY_COMPRESSION));
        assertEquals(FindingKind.MISSING_EVIDENCE,a.findings().getFirst().kind());
        assertEquals(List.of("REV_NET:POSITIVE_DENOMINATOR"),
                a.findings().getFirst().missingMetrics());
    }

    @Test void cashReceivablesDivergenceCreatesObservationAndNoncausalHypothesis(){
        var a=assess(CURRENT,List.of(
                    hist("REV_NET",Map.of(CURRENT.minusMonths(1),"100",CURRENT,"150")),
                    hist("CASH_END",Map.of(CURRENT.minusMonths(1),"200",CURRENT,"160")),
                    hist("AR_END",Map.of(CURRENT.minusMonths(1),"80",CURRENT,"120"))),
                rules(RuleCode.REVENUE_CASH_AR_DIVERGENCE));
        assertEquals(List.of(FindingKind.OBSERVATION,FindingKind.HYPOTHESIS),
                a.findings().stream().map(Finding::kind).toList());
        assertEquals("WORKING_CAPITAL_REVIEW",a.findings().get(1).playbookCode());
        assertEquals(6,a.findings().getFirst().evidence().size());
        var playbook=DecisionPlaybookRegistry.byCode(a.findings().get(1).playbookCode());
        assertEquals(3,playbook.actions().size());
        assertTrue(playbook.actions().stream().allMatch(DecisionPlaybookRegistry.Action::requiresHumanApproval));
        assertEquals("AR_END",playbook.actions().get(1).verificationMetricCode());
    }

    @Test void missingReceivablesIsNotAssumedZeroOrSuccessfulCollection(){
        var ar=history("AR_END","KZT",REVISION,Map.of(),Set.of(CURRENT));
        var a=assess(CURRENT,List.of(
                hist("REV_NET",Map.of(CURRENT,"150")),
                hist("CASH_END",Map.of(CURRENT,"160")),
                ar),rules(RuleCode.REVENUE_CASH_AR_DIVERGENCE));
        assertEquals(1,a.findings().size());
        assertEquals(FindingKind.MISSING_EVIDENCE,a.findings().getFirst().kind());
        assertEquals(List.of("AR_END:CURRENT"),a.findings().getFirst().missingMetrics());
    }

    @Test void mixedCurrencyAndRevisionRejectRatherThanInventConclusion(){
        var rev=hist("REV_NET",Map.of());
        var costs=history("COGS","USD",REVISION,Map.of(),Set.of());
        assertEquals("DIAGNOSTIC_CURRENCY_MISMATCH",
                assertThrows(IllegalArgumentException.class,()->
                    assess(CURRENT,List.of(rev,costs),rules(RuleCode.GROSS_MARGIN_YOY_COMPRESSION)))
                    .getMessage());
        var foreign=history("COGS","KZT",UUID.randomUUID(),Map.of(),Set.of());
        assertEquals("CROSS_SCOPE_DIAGNOSTIC",
                assertThrows(IllegalArgumentException.class,()->
                    assess(CURRENT,List.of(rev,foreign),rules(RuleCode.GROSS_MARGIN_YOY_COMPRESSION)))
                    .getMessage());
    }

    @Test void unauthorizedOrChangedRuleCannotBeInjectedByCaller(){
        RuleDefinition spoofed=new RuleDefinition(RuleCode.REVENUE_YOY_DECLINE,"9.9",
                "diagnosis.revenue.yoy.decline","ARBITRARY_ACTION",List.of("REV_NET"));
        assertThrows(IllegalArgumentException.class,()->
                assess(CURRENT,List.of(hist("REV_NET",Map.of())),List.of(spoofed)));
        assertThrows(IllegalArgumentException.class,()->
                assess(CURRENT,List.of(hist("REV_NET",Map.of())),List.of(
                        PILOT_RULES.getFirst(),PILOT_RULES.getFirst())));
    }

    @Test void estimatedInputsDoNotBecomeConfirmedFindings(){
        var original=hist("REV_NET",Map.of());
        var key=original.key();
        List<Observation> inputs=new ArrayList<>(original.observations());
        inputs.removeIf(o->o.time().startInclusive().equals(CURRENT.atDay(1)));
        inputs.add(new Observation(key,EconomicKernel.TemporalReference.interval(
                CURRENT.atDay(1),CURRENT.plusMonths(1).atDay(1)),
                EconomicKernel.EconomicValue.estimated("60","TRIBUO_PILOT")));
        Trend trend=trend(CURRENT,new MetricHistory(key,inputs));
        assertEquals(Status.ESTIMATED,trend.current().status());
        var diagnosis=assess(CURRENT,List.of(new MetricHistory(key,inputs)),
                rules(RuleCode.REVENUE_YOY_DECLINE));
        assertEquals(FindingKind.MISSING_EVIDENCE,diagnosis.findings().getFirst().kind());
    }

    @Test void incompleteThreeMonthWindowCannotBecomeMovingAverage(){
        var incomplete=history("REV_NET","KZT",REVISION,Map.of(),
                Set.of(CURRENT.minusMonths(2)));
        Trend t=trend(CURRENT,incomplete);
        assertEquals(Status.INCOMPLETE,t.movingAverage3().status());
        assertEquals("MISSING_PERIODS",t.movingAverage3().reason());
    }
}
