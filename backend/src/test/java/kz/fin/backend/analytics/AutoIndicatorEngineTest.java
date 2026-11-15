package kz.fin.backend.analytics;

import kz.fin.backend.domain.EconomicKernel;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static kz.fin.backend.analytics.AutoIndicatorEngine.*;

class AutoIndicatorEngineTest {
    private static final UUID ORG=UUID.randomUUID();
    private static final UUID ENTITY=UUID.randomUUID();
    private static final UUID SCOPE=UUID.randomUUID();
    private static final UUID FACTS=UUID.randomUUID();
    private static SeriesKey key(String metric, String unit, String currency, Map<String,String> dims) {
        return keyFor(ORG,FACTS,metric,unit,currency,dims,AnalysisContext.DataSpace.ACTUAL,
                metric.equals("STOCK_END") ? MeasureKind.STOCK :
                metric.equals("STAFF_FTE") ? MeasureKind.LEVEL : MeasureKind.FLOW);
    }
    private static SeriesKey keyFor(UUID org, UUID facts, String metric, String unit, String currency,
                                    Map<String,String> dims, AnalysisContext.DataSpace space, MeasureKind kind) {
        return new SeriesKey(org,ENTITY,SCOPE,facts,metric,"v1",kind,unit,currency,
                EconomicKernel.ReportingBasis.MANAGEMENT,space,new TreeMap<>(dims));
    }
    private static final YearMonth JAN=YearMonth.of(2026,1),FEB=YearMonth.of(2026,2),
            MAR=YearMonth.of(2026,3),APR=YearMonth.of(2026,4);

    private static EconomicKernel.TemporalReference interval(YearMonth ym) {
        return EconomicKernel.TemporalReference.interval(ym.atDay(1),ym.plusMonths(1).atDay(1));
    }
    private static EconomicKernel.TemporalReference range(YearMonth start, YearMonth endExclusive) {
        return EconomicKernel.TemporalReference.interval(start.atDay(1),endExclusive.atDay(1));
    }
    private static Observation flow(SeriesKey key, YearMonth ym, String n) {
        return new Observation(key,interval(ym),EconomicKernel.EconomicValue.present(n));
    }
    private static Observation stock(SeriesKey key, YearMonth ym, String n) {
        return new Observation(key,EconomicKernel.TemporalReference.asOf(ym.atEndOfMonth()),
                EconomicKernel.EconomicValue.present(n));
    }
    private static void eq(String expected, Calculation actual) {
        assertEquals(Status.CALCULATED,actual.status(),actual.reason());
        assertEquals(0,new BigDecimal(expected).compareTo(actual.value()),actual.toString());
    }

    @Test void sumQuarterAndMeanMonthlyRevenueWithoutDoubleCounting() {
        SeriesKey k=key("REV_NET","MONEY","KZT",Map.of("store","A"));
        List<Observation> data=List.of(flow(k,JAN,"100"),flow(k,FEB,"900"),flow(k,MAR,"0"));
        eq("1000",aggregate(k,range(JAN,APR),data,Aggregation.FLOW_SUM).calculation());
        eq("333.333333333333",aggregate(k,range(JAN,APR),data,Aggregation.MONTHLY_FLOW_MEAN).calculation());
        assertEquals(Status.INCOMPLETE,aggregate(k,range(JAN,APR),data.subList(0,2),Aggregation.FLOW_SUM)
                .calculation().status());
        assertEquals("MISSING_PERIODS",aggregate(k,range(JAN,APR),data.subList(0,2),Aggregation.FLOW_SUM)
                .calculation().reason());
    }

    @Test void timeWeightedLevelAverageDiffersFromNaiveSampleMean() {
        SeriesKey k=key("STAFF_FTE","FTE",null,Map.of());
        var first=new Observation(k,EconomicKernel.TemporalReference.interval(
                LocalDate.of(2026,4,1),LocalDate.of(2026,4,11)),EconomicKernel.EconomicValue.present("10"));
        var second=new Observation(k,EconomicKernel.TemporalReference.interval(
                LocalDate.of(2026,4,11),LocalDate.of(2026,5,1)),EconomicKernel.EconomicValue.present("20"));
        eq("16.666666666667",aggregate(k,interval(APR),List.of(first,second),
                Aggregation.DAY_WEIGHTED_LEVEL_MEAN).calculation());
    }

    @Test void stockUsesClosingValueNotSum() {
        SeriesKey k=key("STOCK_END","MONEY","KZT",Map.of("warehouse","1"));
        var data=List.of(stock(k,JAN,"10"),stock(k,FEB,"20"),stock(k,MAR,"30"));
        eq("30",aggregate(k,range(JAN,APR),data,Aggregation.STOCK_CLOSING).calculation());
        assertThrows(IllegalArgumentException.class,
                ()->aggregate(k,range(JAN,APR),data,Aggregation.FLOW_SUM));
        assertEquals("MISSING_CLOSING_STOCK",aggregate(k,range(JAN,APR),data.subList(0,2),
                Aggregation.STOCK_CLOSING).calculation().reason());
    }

    @Test void comparisonAbsoluteAndPercentageNeverDivideByZero() {
        SeriesKey k=key("REV_NET","MONEY","KZT",Map.of());
        var prev=aggregate(k,interval(JAN),List.of(flow(k,JAN,"100")),Aggregation.FLOW_SUM);
        var curr=aggregate(k,interval(FEB),List.of(flow(k,FEB,"120")),Aggregation.FLOW_SUM);
        var c=compare(prev,curr);
        eq("20",c.absoluteChange());
        eq("20",c.percentChange());
        var zero=aggregate(k,interval(JAN),List.of(flow(k,JAN,"0")),Aggregation.FLOW_SUM);
        var againstZero=compare(zero,curr);
        eq("120",againstZero.absoluteChange());
        assertEquals("ZERO_BASELINE",againstZero.percentChange().reason());
        var negative=aggregate(k,interval(JAN),List.of(flow(k,JAN,"-10")),Aggregation.FLOW_SUM);
        assertEquals("NEGATIVE_BASELINE_PERCENT_UNDEFINED",compare(negative,curr).percentChange().reason());
        assertThrows(IllegalArgumentException.class,()->compare(curr,prev));
        assertThrows(IllegalArgumentException.class,()->compare(curr,curr));
    }

    @Test void movingMeanRequiresCompleteHistoryAndIsChronological() {
        SeriesKey k=key("REV_NET","MONEY","KZT",Map.of());
        var data=List.of(flow(k,JAN,"100"),flow(k,FEB,"200"),flow(k,MAR,"300"),flow(k,APR,"400"));
        var r=movingAverage(k,data,MAR,APR,3,Aggregation.FLOW_SUM);
        assertEquals(List.of(MAR,APR),new ArrayList<>(r.keySet()));
        eq("200",r.get(MAR));
        eq("300",r.get(APR));
        var incomplete=movingAverage(k,List.of(flow(k,JAN,"100"),flow(k,MAR,"300"),flow(k,APR,"400")),
                MAR,APR,3,Aggregation.FLOW_SUM);
        assertEquals("MISSING_PERIODS",incomplete.get(MAR).reason());
        assertEquals("MISSING_PERIODS",incomplete.get(APR).reason());
        assertEquals("MISSING_PERIODS",movingAverage(k,data,JAN,JAN,3,Aggregation.FLOW_SUM)
                .get(JAN).reason());
    }

    @Test void movingMeanOnStockUsesEndOfMonthNotSumAcrossMonths() {
        SeriesKey k=key("STOCK_END","PCS",null,Map.of("store","A"));
        var result=movingAverage(k,List.of(stock(k,JAN,"50"),stock(k,FEB,"100"),stock(k,MAR,"150")),
                MAR,MAR,3,Aggregation.STOCK_CLOSING);
        eq("100",result.get(MAR));
    }

    @Test void percentagesAlwaysUseMatchingContextAndCompatibleUnits() {
        var revenue=key("REV_NET","MONEY","KZT",Map.of("store","A"));
        var cogs=key("COGS","MONEY","KZT",Map.of("store","A"));
        var rev=aggregate(revenue,interval(JAN),List.of(flow(revenue,JAN,"100")),Aggregation.FLOW_SUM);
        var cost=aggregate(cogs,interval(JAN),List.of(flow(cogs,JAN,"60")),Aggregation.FLOW_SUM);
        eq("60",percentOf(cost,rev));
        var usd=key("COGS","MONEY","USD",Map.of("store","A"));
        var wrong=aggregate(usd,interval(JAN),List.of(flow(usd,JAN,"60")),Aggregation.FLOW_SUM);
        assertThrows(IllegalArgumentException.class,()->percentOf(wrong,rev));
        var q=key("QTY","PCS",null,Map.of("store","A"));
        assertThrows(IllegalArgumentException.class,()->percentOf(
                aggregate(q,interval(JAN),List.of(flow(q,JAN,"60")),Aggregation.FLOW_SUM),rev));
    }

    @Test void shareByStoreIsCompleteAndNeverNormalizesMissingGroups() {
        var east=key("REV_NET","MONEY","KZT",Map.of("store","EAST","channel","RETAIL"));
        var west=key("REV_NET","MONEY","KZT",Map.of("store","WEST","channel","RETAIL"));
        var a=aggregate(east,interval(JAN),List.of(flow(east,JAN,"100")),Aggregation.FLOW_SUM);
        var b=aggregate(west,interval(JAN),List.of(flow(west,JAN,"300")),Aggregation.FLOW_SUM);
        var share=shareByDimension("store",Set.of("EAST","WEST"),Map.of("EAST",a,"WEST",b));
        eq("25",share.get("EAST"));eq("75",share.get("WEST"));
        assertThrows(UnsupportedOperationException.class,()->share.clear());
        var incomplete=shareByDimension("store",Set.of("EAST","WEST"),Map.of("EAST",a));
        assertEquals("MISSING_SHARE_MEMBERS",incomplete.get("EAST").reason());
        assertNull(incomplete.get("WEST").value());
        assertThrows(IllegalArgumentException.class,
                ()->shareByDimension("store",Set.of("EAST","WEST"),Map.of("EAST",b)));
    }

    @Test void shareRejectsMixedCompanyCurrencyVersionAndDimension() {
        var a=key("REV_NET","MONEY","KZT",Map.of("store","A","channel","RETAIL"));
        var diffTenant=keyFor(UUID.randomUUID(),FACTS,"REV_NET","MONEY","KZT",
                Map.of("store","B","channel","RETAIL"),AnalysisContext.DataSpace.ACTUAL,MeasureKind.FLOW);
        var diffChannel=key("REV_NET","MONEY","KZT",Map.of("store","B","channel","B2B"));
        var diffRevision=keyFor(ORG,UUID.randomUUID(),"REV_NET","MONEY","KZT",
                Map.of("store","B","channel","RETAIL"),AnalysisContext.DataSpace.ACTUAL,MeasureKind.FLOW);
        var x=aggregate(a,interval(JAN),List.of(flow(a,JAN,"100")),Aggregation.FLOW_SUM);
        for(var b:List.of(diffTenant,diffChannel,diffRevision)) {
            var y=aggregate(b,interval(JAN),List.of(flow(b,JAN,"300")),Aggregation.FLOW_SUM);
            assertThrows(IllegalArgumentException.class,
                    ()->shareByDimension("store",Set.of("A","B"),Map.of("A",x,"B",y)));
        }
        var zero=aggregate(a,interval(JAN),List.of(flow(a,JAN,"0")),Aggregation.FLOW_SUM);
        assertEquals("ZERO_SHARE_TOTAL",shareByDimension("store",Set.of("A"),
                Map.of("A",zero)).get("A").reason());
        var loss=aggregate(a,interval(JAN),List.of(flow(a,JAN,"-2")),Aggregation.FLOW_SUM);
        assertEquals("NEGATIVE_SHARE_COMPONENT",shareByDimension("store",Set.of("A"),
                Map.of("A",loss)).get("A").reason());
    }

    @Test void noCrossTenantCrossScenarioCrossPeriodOrOverlappingFlow() {
        var actual=key("REV_NET","MONEY","KZT",Map.of());
        var scenario=keyFor(ORG,FACTS,"REV_NET","MONEY","KZT",Map.of(),
                AnalysisContext.DataSpace.SCENARIO,MeasureKind.FLOW);
        var jan=flow(actual,JAN,"100");
        assertThrows(IllegalArgumentException.class,()->aggregate(actual,interval(JAN),
                List.of(new Observation(scenario,jan.time(),jan.value())),Aggregation.FLOW_SUM));
        assertThrows(IllegalArgumentException.class,()->aggregate(actual,interval(JAN),
                List.of(jan,jan),Aggregation.FLOW_SUM));
        assertThrows(IllegalArgumentException.class,()->aggregate(actual,interval(JAN),
                List.of(flow(actual,FEB,"20")),Aggregation.FLOW_SUM));
        assertThrows(IllegalArgumentException.class,()->aggregate(actual,interval(JAN),
                List.of(stock(actual,JAN,"100")),Aggregation.FLOW_SUM));
    }

    @Test void estimatesAreNotApprovedActualsAndForecastsAreExplicitlyEstimated() {
        var actual=key("REV_NET","MONEY","KZT",Map.of());
        var est=EconomicKernel.EconomicValue.estimated("100","TRIBUO_V1");
        var actualPoint=aggregate(actual,interval(JAN),List.of(new Observation(actual,interval(JAN),est)),
                Aggregation.FLOW_SUM);
        assertEquals("ESTIMATED_ACTUAL_NOT_ALLOWED",actualPoint.calculation().reason());
        var forecast=keyFor(ORG,FACTS,"REV_NET","MONEY","KZT",Map.of(),
                AnalysisContext.DataSpace.FORECAST,MeasureKind.FLOW);
        var r=aggregate(forecast,interval(JAN),List.of(new Observation(forecast,interval(JAN),est)),
                Aggregation.FLOW_SUM);
        assertEquals(Status.ESTIMATED,r.calculation().status());
        assertEquals(0,new BigDecimal("100").compareTo(r.calculation().value()));
    }

    @Test void missingAndNotApplicableRemainDistinctAndExplicitZeroIsNotMissing() {
        var k=key("REV_NET","MONEY","KZT",Map.of());
        var missing=aggregate(k,interval(JAN),
                List.of(new Observation(k,interval(JAN),EconomicKernel.EconomicValue.missing())),
                Aggregation.FLOW_SUM);
        var na=aggregate(k,interval(JAN),
                List.of(new Observation(k,interval(JAN),EconomicKernel.EconomicValue.notApplicable())),
                Aggregation.FLOW_SUM);
        assertEquals("MISSING_INPUT",missing.calculation().reason());
        assertEquals("NOT_APPLICABLE",na.calculation().reason());
        eq("0",aggregate(k,interval(JAN),
                List.of(new Observation(k,interval(JAN),EconomicKernel.EconomicValue.explicitZero())),
                Aggregation.FLOW_SUM).calculation());
    }

    @Test void preventAccidentalRoundedMoneyOrArithmeticOfDifferentUnits() {
        var k=key("BIG","MONEY","KZT",Map.of());
        var first=new Observation(k,EconomicKernel.TemporalReference.interval(
                LocalDate.of(2026,1,1),LocalDate.of(2026,1,16)),
                EconomicKernel.EconomicValue.present("99999999999999999999999999.000000000001"));
        var second=new Observation(k,EconomicKernel.TemporalReference.interval(
                LocalDate.of(2026,1,16),LocalDate.of(2026,2,1)),
                EconomicKernel.EconomicValue.present("1"));
        assertEquals("DECIMAL_POLICY_VIOLATION",assertThrows(IllegalArgumentException.class,
                ()->aggregate(k,interval(JAN),List.of(first,second),Aggregation.FLOW_SUM)).getMessage());
        assertThrows(IllegalArgumentException.class,()->new SeriesKey(
                ORG,ENTITY,SCOPE,FACTS,"REV_NET","v1",MeasureKind.FLOW,"MONEY",null,
                EconomicKernel.ReportingBasis.MANAGEMENT,AnalysisContext.DataSpace.ACTUAL,new TreeMap<>()));
    }
}
