package kz.fin.backend.analytics;

import kz.fin.backend.domain.EconomicKernel;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InvestmentValuationTest {
    private static AnalysisContext context() {
        return new AnalysisContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                EconomicKernel.TemporalReference.interval(LocalDate.of(2026,1,1),LocalDate.of(2029,1,1)),
                EconomicKernel.ReportingBasis.MANAGEMENT,AnalysisContext.DataSpace.FORECAST,"KZT");
    }
    private static FinancialAmount k(String v) { return FinancialAmount.of(v,"KZT"); }
    private static InvestmentValuation.DiscountRate rate(String v) {
        return new InvestmentValuation.DiscountRate(new BigDecimal(v),
                InvestmentValuation.RateBasis.WACC,"WACC_SYNTHETIC_V1");
    }
    private static InvestmentValuation.Input input(String r,InvestmentValuation.TerminalGrowth tv) {
        return new InvestmentValuation.Input(context(),InvestmentValuation.CashFlowBasis.FCFF,
                rate(r),List.of(k("-1000"),k("600"),k("600")),tv);
    }
    private static void eq(String v,FinancialAmount actual) {
        assertEquals(0,new BigDecimal(v).compareTo(actual.amount()));
    }

    @Test void goldenPeriodicNpvKeepsTimeZeroAndDiscountsAnnualFlows() {
        var result=InvestmentValuation.calculate(input("0.10",null));
        eq("41.322314049586",result.explicitPeriodNpv());
        eq("41.322314049586",result.npvWithTerminal());
        eq("-1000",result.rows().get(0).presentValue());
        eq("545.454545454545",result.rows().get(1).presentValue());
        eq("495.867768595041",result.rows().get(2).presentValue());
        assertEquals(3,result.rows().size());
        assertEquals("PERIODIC_NPV_DCF_V1",result.methodVersion());
    }

    @Test void goldenDcfWithExplicitNormalizedTerminalCashFlow() {
        var terminal=new InvestmentValuation.TerminalGrowth(new BigDecimal("0.02"),k("600"));
        var result=InvestmentValuation.calculate(input("0.10",terminal));
        eq("7650",result.terminalValueAtHorizon());
        eq("6322.314049586777",result.presentValueTerminal());
        eq("6363.636363636363",result.npvWithTerminal());
        // Historical period-only NPV remains unchanged when terminal case is selected.
        eq("41.322314049586",result.explicitPeriodNpv());
    }

    @Test void zeroDiscountRateIsAllowedAndNoTerminalIsImplicit() {
        var r=InvestmentValuation.calculate(input("0",null));
        eq("200",r.npvWithTerminal());
        eq("0",r.terminalValueAtHorizon());
        eq("0",r.presentValueTerminal());
    }

    @Test void rejectsFcfeDiscountedAtWacc() {
        assertThrows(IllegalArgumentException.class,()->new InvestmentValuation.Input(
                context(),InvestmentValuation.CashFlowBasis.FCFE,
                rate("0.10"),List.of(k("-1000"),k("600")),null));
    }

    @Test void rejectsGrowthEqualToOrExceedingDiscountAndNegativeHundredPercent() {
        assertThrows(IllegalArgumentException.class,()->rate("-1"));
        assertThrows(IllegalArgumentException.class,
                ()->input("0.10",new InvestmentValuation.TerminalGrowth(new BigDecimal("0.10"),k("600"))));
        assertThrows(IllegalArgumentException.class,
                ()->input("0.10",new InvestmentValuation.TerminalGrowth(new BigDecimal("0.11"),k("600"))));
        assertThrows(IllegalArgumentException.class,
                ()->new InvestmentValuation.TerminalGrowth(new BigDecimal("0.02"),k("-100")));
    }

    @Test void rejectsMixedCurrenciesAndMissingHorizon() {
        assertThrows(IllegalArgumentException.class,()->new InvestmentValuation.Input(
                context(),InvestmentValuation.CashFlowBasis.FCFF,rate("0.10"),
                List.of(k("-1000"),FinancialAmount.of("600","USD")),null));
        assertThrows(IllegalArgumentException.class,()->new InvestmentValuation.Input(
                context(),InvestmentValuation.CashFlowBasis.FCFF,rate("0.10"),List.of(k("-1000")),null));
    }
}
