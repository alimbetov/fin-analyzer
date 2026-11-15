package kz.fin.backend.analytics;

import kz.fin.backend.domain.EconomicKernel;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceCostEstimateTest {
    private static AnalysisContext context() {
        return new AnalysisContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                EconomicKernel.TemporalReference.interval(LocalDate.of(2026,1,1),LocalDate.of(2027,1,1)),
                EconomicKernel.ReportingBasis.MANAGEMENT,AnalysisContext.DataSpace.PLAN,"KZT");
    }
    private static ResourceCostEstimate.ResourceRate rate(String resource,String unit,String price,String ccy,boolean approved) {
        return new ResourceCostEstimate.ResourceRate(resource,unit,FinancialAmount.of(price,ccy),"RATE_BOOK_R1",approved);
    }
    private static ResourceCostEstimate.ResourceUsage use(ResourceCostEstimate.ResourceRate rate,String units) {
        return new ResourceCostEstimate.ResourceUsage(rate,new BigDecimal(units));
    }
    private static ResourceCostEstimate.BoqLine foundation() {
        return new ResourceCostEstimate.BoqLine("FOUNDATION","WBS-A",20,"m3",new BigDecimal("10"),
                List.of(use(rate("CONCRETE","m3","100","KZT",true),"2"),
                        use(rate("LABOR","hour","50","KZT",true),"3")));
    }
    private static ResourceCostEstimate.BoqLine roof() {
        return new ResourceCostEstimate.BoqLine("ROOF","WBS-B",10,"m2",new BigDecimal("5"),
                List.of(use(rate("MATERIAL","kg","25","KZT",true),"4")));
    }
    private static void eq(String v,FinancialAmount a) {
        assertEquals("KZT",a.currency());
        assertEquals(0,new BigDecimal(v).compareTo(a.amount()));
    }

    @Test void goldenResourceBillAndDeterministicWbsDisplayOrder() {
        var r=ResourceCostEstimate.calculate(new ResourceCostEstimate.Input(context(),
                List.of(foundation(),roof()),new BigDecimal("0.10"),new BigDecimal("0.05")));
        assertEquals(List.of("ROOF","FOUNDATION"),r.lines().stream().map(ResourceCostEstimate.LineResult::lineCode).toList());
        eq("500",r.lines().get(0).directCost());
        eq("3500",r.lines().get(1).directCost());
        eq("4000",r.directCost());
        eq("400",r.overhead());
        eq("220",r.contingency());
        eq("4620",r.grandTotal());
        assertEquals("RESOURCE_BOQ_V1",r.methodVersion());
        assertThrows(UnsupportedOperationException.class,()->r.lines().clear());
    }

    @Test void noDoubleCountingSameBoqLineOrSameResource() {
        assertThrows(IllegalArgumentException.class,()->new ResourceCostEstimate.Input(context(),
                List.of(foundation(),foundation()),BigDecimal.ZERO,BigDecimal.ZERO));
        var same=rate("LABOR","hour","50","KZT",true);
        assertThrows(IllegalArgumentException.class,()->new ResourceCostEstimate.BoqLine(
                "B","WBS",1,"m3",BigDecimal.ONE,List.of(use(same,"1"),use(same,"2"))));
    }

    @Test void unknownOrUnapprovedUnitRatesCannotBeInferredOrUsed() {
        var bad=new ResourceCostEstimate.BoqLine("B","WBS",1,"m3",BigDecimal.ONE,
                List.of(use(rate("LABOR","hour","50","KZT",false),"2")));
        assertThrows(IllegalArgumentException.class,()->new ResourceCostEstimate.Input(context(),
                List.of(bad),BigDecimal.ZERO,BigDecimal.ZERO));
        var foreign=new ResourceCostEstimate.BoqLine("C","WBS",2,"m3",BigDecimal.ONE,
                List.of(use(rate("LABOR","hour","50","USD",true),"2")));
        assertThrows(IllegalArgumentException.class,()->new ResourceCostEstimate.Input(context(),
                List.of(foreign),BigDecimal.ZERO,BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,()->new ResourceCostEstimate.BoqLine(
                "EMPTY","WBS",1,"m3",BigDecimal.ONE,List.of()));
    }

    @Test void rejectsNegativeQuantitiesPricesAndMarkups() {
        assertThrows(IllegalArgumentException.class,()->new ResourceCostEstimate.BoqLine(
                "N","WBS",1,"m3",new BigDecimal("-1"),List.of(use(rate("LABOR","hour","50","KZT",true),"1"))));
        assertThrows(IllegalArgumentException.class,()->rate("LABOR","hour","-5","KZT",true));
        assertThrows(IllegalArgumentException.class,()->new ResourceCostEstimate.Input(
                context(),List.of(roof()),new BigDecimal("-0.01"),BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,()->new ResourceCostEstimate.Input(
                context(),List.of(roof()),BigDecimal.ZERO,new BigDecimal("1.50")));
    }

    @Test void noSilentTruncationOnFractionalResourceQuantities() {
        var line=new ResourceCostEstimate.BoqLine("F","WBS",1,"m3",new BigDecimal("1.25"),
                List.of(use(rate("M","kg","2.50","KZT",true),"0.50")));
        var r=ResourceCostEstimate.calculate(new ResourceCostEstimate.Input(context(),List.of(line),
                BigDecimal.ZERO,BigDecimal.ZERO));
        eq("1.5625",r.grandTotal());
    }
}
