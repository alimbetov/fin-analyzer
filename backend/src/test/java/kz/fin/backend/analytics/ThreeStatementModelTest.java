package kz.fin.backend.analytics;

import kz.fin.backend.domain.EconomicKernel;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ThreeStatementModelTest {
    private static FinancialAmount k(String v) { return FinancialAmount.of(v,"KZT"); }

    private static AnalysisContext context() {
        return new AnalysisContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                EconomicKernel.TemporalReference.interval(LocalDate.of(2026,1,1),LocalDate.of(2027,1,1)),
                EconomicKernel.ReportingBasis.MANAGEMENT,AnalysisContext.DataSpace.SYNTHETIC,"KZT");
    }

    private static ThreeStatementModel.OpeningBalance opening() {
        return new ThreeStatementModel.OpeningBalance(k("100"),k("80"),k("50"),k("200"),
                k("60"),k("150"),k("220"));
    }

    private static ThreeStatementModel.PeriodDrivers drivers(String capex) {
        return new ThreeStatementModel.PeriodDrivers(
                k("300"),k("180"),k("60"),k("10"),k("5"),k("9"),k(capex),
                k("20"),k("10"),k("5"),k("30"),k("15"),k("0"),k("6"));
    }
    private static void eq(String expected, FinancialAmount actual) {
        assertEquals("KZT",actual.currency());
        assertEquals(0,new BigDecimal(expected).compareTo(actual.amount()));
    }

    @Test void reconcilesProfitCashAndBalanceWithoutHiddenPlug() {
        var result=ThreeStatementModel.calculate(context(),opening(),drivers("40"));
        eq("120",result.profitAndLoss().grossProfit());
        eq("50",result.profitAndLoss().operatingProfit());
        eq("36",result.profitAndLoss().netProfit());
        eq("21",result.cashFlow().operating());
        eq("-40",result.cashFlow().investing());
        eq("9",result.cashFlow().financing());
        eq("90",result.cashFlow().closingCash());
        eq("480",result.closingBalance().totalAssets());
        eq("480",result.closingBalance().liabilitiesAndEquity());
        eq("250",result.closingBalance().equity());
        eq("230",result.closingBalance().propertyPlantEquipment());
        assertTrue(result.warnings().isEmpty());
        assertEquals("SIMPLIFIED_3STATEMENT_V1",result.methodVersion());
    }

    @Test void liquidityShortfallIsExplainedButNeverSilentlyPlugged() {
        var result=ThreeStatementModel.calculate(context(),opening(),drivers("160"));
        eq("-30",result.cashFlow().closingCash());
        assertEquals(java.util.List.of("LIQUIDITY_SHORTFALL"),result.warnings());
        eq("480",result.closingBalance().totalAssets());
    }

    @Test void rejectsUnbalancedOpeningStatement() {
        var bad=new ThreeStatementModel.OpeningBalance(k("100"),k("80"),k("50"),k("200"),
                k("60"),k("150"),k("221"));
        assertThrows(IllegalArgumentException.class,
                () -> ThreeStatementModel.calculate(context(),bad,drivers("40")));
    }

    @Test void rejectsMixedCurrencyAndPointInTimeForFlows() {
        var d=drivers("40");
        var bad=new ThreeStatementModel.PeriodDrivers(FinancialAmount.of("300","USD"),
                d.costOfSales(),d.operatingExpenses(),d.depreciation(),d.interestExpense(),
                d.taxExpense(),d.capex(),d.receivablesChange(),d.inventoryChange(),
                d.payablesChange(),d.newDebt(),d.debtRepaid(),d.contributedCapital(),d.dividends());
        assertThrows(IllegalArgumentException.class,
                () -> ThreeStatementModel.calculate(context(),opening(),bad));
        var base=context();
        var asof=new AnalysisContext(base.organizationId(),base.economicEntityId(),base.businessScopeId(),
                base.sourceRevisionId(),EconomicKernel.TemporalReference.asOf(LocalDate.of(2026,12,31)),
                base.reportingBasis(),base.dataSpace(),"KZT");
        assertThrows(IllegalArgumentException.class,
                () -> ThreeStatementModel.calculate(asof,opening(),drivers("40")));
    }

    @Test void rejectsNegativeTaxExpenseAndClosingNegativeDebt() {
        var d=drivers("40");
        var tax=new ThreeStatementModel.PeriodDrivers(d.revenue(),d.costOfSales(),d.operatingExpenses(),
                d.depreciation(),d.interestExpense(),k("-1"),d.capex(),d.receivablesChange(),
                d.inventoryChange(),d.payablesChange(),d.newDebt(),d.debtRepaid(),
                d.contributedCapital(),d.dividends());
        assertThrows(IllegalArgumentException.class,
                () -> ThreeStatementModel.calculate(context(),opening(),tax));
        var overRepay=new ThreeStatementModel.PeriodDrivers(d.revenue(),d.costOfSales(),d.operatingExpenses(),
                d.depreciation(),d.interestExpense(),d.taxExpense(),d.capex(),d.receivablesChange(),
                d.inventoryChange(),d.payablesChange(),d.newDebt(),k("500"),
                d.contributedCapital(),d.dividends());
        assertThrows(IllegalArgumentException.class,
                () -> ThreeStatementModel.calculate(context(),opening(),overRepay));
    }

    @Test void rejectsUnrepresentableMoneyWithoutImplicitRounding() {
        assertThrows(IllegalArgumentException.class,()->FinancialAmount.of("1.0000000000001","KZT"));
        assertThrows(IllegalArgumentException.class,
                ()->FinancialAmount.of("99999999999999999999999999.000000000001","KZT")
                        .plus(FinancialAmount.of("1","KZT")));
        assertThrows(IllegalArgumentException.class,
                ()->k("1").plus(FinancialAmount.of("1","USD")));
    }
}
