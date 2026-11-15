package kz.fin.backend.analytics;

import kz.fin.backend.domain.EconomicKernel;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static kz.fin.backend.analytics.AutoIndicatorEngine.*;
import static kz.fin.backend.analytics.AutoReportProjection.*;

class AutoReportProjectionTest {
    private static final UUID ORG=UUID.randomUUID(), ENTITY=UUID.randomUUID(),
            SCOPE=UUID.randomUUID(), FACTS=UUID.randomUUID();
    private static final YearMonth JAN=YearMonth.of(2026,1),FEB=YearMonth.of(2026,2);
    private static SeriesKey metric(String code, String unit, String currency,
                                    MeasureKind kind, Map<String,String> dimensions) {
        return new SeriesKey(ORG,ENTITY,SCOPE,FACTS,code,"v1",kind,unit,currency,
                EconomicKernel.ReportingBasis.MANAGEMENT,AnalysisContext.DataSpace.ACTUAL,
                new TreeMap<>(dimensions));
    }
    private static EconomicKernel.TemporalReference period(YearMonth month) {
        return EconomicKernel.TemporalReference.interval(month.atDay(1),month.plusMonths(1).atDay(1));
    }
    private static EconomicKernel.TemporalReference twoMonths() {
        return EconomicKernel.TemporalReference.interval(JAN.atDay(1),FEB.plusMonths(1).atDay(1));
    }
    private static Observation flow(SeriesKey key,YearMonth month,String value) {
        return new Observation(key,period(month),EconomicKernel.EconomicValue.present(value));
    }
    private static Observation stock(SeriesKey key,YearMonth month,String value) {
        return new Observation(key,EconomicKernel.TemporalReference.asOf(month.atEndOfMonth()),
                EconomicKernel.EconomicValue.present(value));
    }
    private static void eq(String expected,Cell cell) {
        assertEquals(Status.CALCULATED,cell.calculation().status(),cell.calculation().reason());
        assertEquals(0,new BigDecimal(expected).compareTo(cell.calculation().value()));
    }

    @Test void projectsEightAutomaticColumnsInDefinitionOrder() {
        var rev=metric("REV_NET","MONEY","KZT",MeasureKind.FLOW,Map.of());
        var cogs=metric("COGS","MONEY","KZT",MeasureKind.FLOW,Map.of());
        var cash=metric("CASH_END","MONEY","KZT",MeasureKind.STOCK,Map.of());
        var east=metric("REV_NET","MONEY","KZT",MeasureKind.FLOW,Map.of("store","EAST"));
        var west=metric("REV_NET","MONEY","KZT",MeasureKind.FLOW,Map.of("store","WEST"));
        var input=Map.of(
                rev,List.of(flow(rev,JAN,"100"),flow(rev,FEB,"200")),
                cogs,List.of(flow(cogs,JAN,"60"),flow(cogs,FEB,"120")),
                cash,List.of(stock(cash,JAN,"150"),stock(cash,FEB,"220")),
                east,List.of(flow(east,JAN,"25"),flow(east,FEB,"50")),
                west,List.of(flow(west,JAN,"75"),flow(west,FEB,"150")));

        ReportDefinition def=new ReportDefinition("FIN_MONTHLY_TABLE","v1",List.of(
                new Column("SHARE_EAST",800,"kpi.share",
                        new ShareOfTotal(east,"store",Set.of("EAST","WEST"),period(FEB),Aggregation.FLOW_SUM)),
                new Column("REV_MONTH_MEAN",200,"kpi.avg",
                        new Aggregate(rev,twoMonths(),Aggregation.MONTHLY_FLOW_MEAN)),
                new Column("COGS_SHARE",700,"kpi.cogsShare",
                        new PercentOf(cogs,rev,period(FEB),Aggregation.FLOW_SUM)),
                new Column("CLOSING_CASH",600,"kpi.cash",
                        new Aggregate(cash,period(FEB),Aggregation.STOCK_CLOSING)),
                new Column("REV_2M_SUM",100,"kpi.sum",
                        new Aggregate(rev,twoMonths(),Aggregation.FLOW_SUM)),
                new Column("REV_MOM_PCT",400,"kpi.pct",
                        new Compare(rev,period(JAN),period(FEB),Aggregation.FLOW_SUM,ComparisonMode.PERCENT)),
                new Column("REV_MA_2",500,"kpi.ma",
                        new MovingAverage(rev,FEB,2,Aggregation.FLOW_SUM)),
                new Column("REV_MOM_ABS",300,"kpi.abs",
                        new Compare(rev,period(JAN),period(FEB),Aggregation.FLOW_SUM,ComparisonMode.ABSOLUTE))));
        assertEquals("REV_2M_SUM",def.columns().getFirst().columnCode());
        Projection view=project(def,input);
        assertEquals("FIN_MONTHLY_TABLE",view.reportCode());
        assertEquals("v1",view.reportVersion());
        assertEquals(List.of("REV_2M_SUM","REV_MONTH_MEAN","REV_MOM_ABS","REV_MOM_PCT",
                "REV_MA_2","CLOSING_CASH","COGS_SHARE","SHARE_EAST"),
                view.cells().stream().map(Cell::columnCode).toList());
        eq("300",view.cells().get(0));
        eq("150",view.cells().get(1));
        eq("100",view.cells().get(2));
        eq("100",view.cells().get(3));
        eq("150",view.cells().get(4));
        eq("220",view.cells().get(5));
        eq("60",view.cells().get(6));
        eq("25",view.cells().get(7));
        assertEquals("PCT",view.cells().get(7).unitCode());
        assertNull(view.cells().get(7).currencyCode());
        assertEquals("KZT",view.cells().get(0).currencyCode());
        assertThrows(UnsupportedOperationException.class,()->view.cells().clear());
    }

    @Test void projectsMissingRowsAsIncompleteNotZero() {
        var revenue=metric("REV_NET","MONEY","KZT",MeasureKind.FLOW,Map.of());
        var def=new ReportDefinition("FIN_REVENUE","v1",List.of(
                new Column("REV_Q1",100,"finance.revenue",
                        new Aggregate(revenue,twoMonths(),Aggregation.FLOW_SUM))));
        var view=project(def,Map.of(revenue,List.of(flow(revenue,JAN,"100"))));
        assertEquals(Status.INCOMPLETE,view.cells().getFirst().calculation().status());
        assertNull(view.cells().getFirst().calculation().value());
        assertEquals("MISSING_PERIODS",view.cells().getFirst().calculation().reason());
    }

    @Test void duplicateOrdersAndColumnCodesCannotBePublished() {
        var revenue=metric("REV_NET","MONEY","KZT",MeasureKind.FLOW,Map.of());
        var c1=new Column("REV",100,"finance.rev",new Aggregate(revenue,period(JAN),Aggregation.FLOW_SUM));
        var c2=new Column("COST",100,"finance.cost",new Aggregate(revenue,period(JAN),Aggregation.FLOW_SUM));
        var c3=new Column("REV",200,"finance.rev2",new Aggregate(revenue,period(JAN),Aggregation.FLOW_SUM));
        assertThrows(IllegalArgumentException.class,()->new ReportDefinition("REPORT","v1",List.of(c1,c2)));
        assertThrows(IllegalArgumentException.class,()->new ReportDefinition("REPORT","v1",List.of(c1,c3)));
        assertThrows(IllegalArgumentException.class,()->new ReportDefinition("REPORT","v1",List.of()));
    }

    @Test void doesNotCombineUnrelatedFactsetRevisionOrUnknownStore() {
        var total=metric("REV_NET","MONEY","KZT",MeasureKind.FLOW,Map.of());
        var east=metric("REV_NET","MONEY","KZT",MeasureKind.FLOW,Map.of("store","EAST"));
        var west=metric("REV_NET","MONEY","KZT",MeasureKind.FLOW,Map.of("store","WEST"));
        var inputs=Map.of(total,List.of(flow(total,JAN,"100")),
                east,List.of(flow(east,JAN,"25")));
        var def=new ReportDefinition("FIN_REGION","v1",List.of(
                new Column("EAST_PCT",100,"finance.share",
                        new ShareOfTotal(east,"store",Set.of("EAST","WEST"),
                                period(JAN),Aggregation.FLOW_SUM))));
        var missing=project(def,inputs).cells().getFirst().calculation();
        assertEquals(Status.INCOMPLETE,missing.status());
        assertNull(missing.value());
        assertThrows(IllegalArgumentException.class,()->new ShareOfTotal(west,"store",
                null,period(JAN),Aggregation.FLOW_SUM));
        var wrongRev=new SeriesKey(ORG,ENTITY,SCOPE,UUID.randomUUID(),
                "REV_NET","v1",MeasureKind.FLOW,"MONEY","KZT",
                EconomicKernel.ReportingBasis.MANAGEMENT,AnalysisContext.DataSpace.ACTUAL,
                new TreeMap<>(Map.of("store","WEST")));
        var badInput=new HashMap<SeriesKey,List<Observation>>(inputs);
        badInput.put(wrongRev,List.of(flow(wrongRev,JAN,"75")));
        assertEquals(Status.INCOMPLETE,project(def,badInput).cells().getFirst().calculation().status());
    }
    @Test void oneReportCannotMixOrganizationsOrFactSetRevisionsAcrossColumns() {
        var revenue=metric("REV_NET","MONEY","KZT",MeasureKind.FLOW,Map.of());
        var foreign=new SeriesKey(UUID.randomUUID(),ENTITY,SCOPE,FACTS,
                "REV_NET","v1",MeasureKind.FLOW,"MONEY","KZT",
                EconomicKernel.ReportingBasis.MANAGEMENT,AnalysisContext.DataSpace.ACTUAL,
                new TreeMap<>());
        var differentSnapshot=new SeriesKey(ORG,ENTITY,SCOPE,UUID.randomUUID(),
                "COGS","v1",MeasureKind.FLOW,"MONEY","KZT",
                EconomicKernel.ReportingBasis.MANAGEMENT,AnalysisContext.DataSpace.ACTUAL,
                new TreeMap<>());
        var first=new Column("REV",100,"kpi.revenue",
                new Aggregate(revenue,period(JAN),Aggregation.FLOW_SUM));
        for(var other:List.of(foreign,differentSnapshot)) {
            var next=new Column("OTHER",200,"kpi.other",
                    new Aggregate(other,period(JAN),Aggregation.FLOW_SUM));
            assertEquals("REPORT_CONTEXT_MISMATCH",assertThrows(IllegalArgumentException.class,
                    ()->new ReportDefinition("ONE_REPORT","v1",List.of(first,next))).getMessage());
        }
    }

}
