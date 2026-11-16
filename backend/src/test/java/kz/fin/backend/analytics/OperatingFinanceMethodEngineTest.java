package kz.fin.backend.analytics;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static kz.fin.backend.analytics.OperatingFinanceMethodEngine.*;

class OperatingFinanceMethodEngineTest {
    private static final UUID REVISION=UUID.randomUUID();
    private static final LocalDate FROM=LocalDate.of(2026,1,1);
    private static final LocalDate UNTIL=LocalDate.of(2027,1,1);
    private static EconomicInput input(String metric,String amount) {
        return new EconomicInput(metric,new BigDecimal(amount),"KZT",
                FROM,UNTIL,REVISION);
    }
    private static void eq(String expected,BigDecimal actual) {
        assertEquals(0,new BigDecimal(expected).compareTo(actual));
    }

    @Test void contributionMarginRequiresActualVariableCostNotAllCostOfGoodsSold() {
        var result=contributionMargin(input("REV_NET","1000"),
                input("VARIABLE_COST","600"));
        assertEquals(PRO03_VERSION,result.methodVersion());
        eq("400",result.contribution());
        eq("40",result.marginPercent());
        var loss=contributionMargin(input("REV_NET","1000"),
                input("VARIABLE_COST","1200"));
        eq("-200",loss.contribution());
        eq("-20",loss.marginPercent());
        assertThrows(IllegalArgumentException.class,
                ()->contributionMargin(input("REV_NET","1000"),
                    input("COGS","600"))); // no silent COGS->variable substitution
        assertThrows(IllegalArgumentException.class,
                ()->contributionMargin(input("REV_NET","0"),
                    input("VARIABLE_COST","0")));
        assertThrows(IllegalArgumentException.class,
                ()->contributionMargin(input("REV_NET","1000"),
                    input("VARIABLE_COST","-20")));
    }

    @Test void cashCycleMatchesIndependent360DayGoldenWithPurchasesNotCogs() {
        var result=cashConversionCycle(
                input("AR_AVG","120"), input("INVENTORY_AVG","90"),
                input("AP_AVG","60"),input("REV_NET","720"),
                input("COGS","540"),input("PURCHASES","540"),
                DayCountPolicy.FIXED_360);
        assertEquals(CASH01_VERSION,result.methodVersion());
        assertEquals(DayCountPolicy.FIXED_360,result.dayCountPolicy());
        eq("60",result.dsoDays());
        eq("60",result.dioDays());
        eq("40",result.dpoDays());
        eq("80",result.cashConversionDays());
    }

    @Test void cashCycleRejectsMissingPurchasesZeroDenominatorsAndNegativeBalances() {
        assertThrows(IllegalArgumentException.class,()->cashConversionCycle(
            input("AR_AVG","120"),input("INVENTORY_AVG","90"),input("AP_AVG","60"),
            input("REV_NET","720"),input("COGS","540"),input("COGS","540"),
            DayCountPolicy.FIXED_360));
        assertThrows(IllegalArgumentException.class,()->cashConversionCycle(
            input("AR_AVG","120"),input("INVENTORY_AVG","90"),input("AP_AVG","60"),
            input("REV_NET","720"),input("COGS","540"),input("PURCHASES","0"),
            DayCountPolicy.FIXED_360));
        assertThrows(IllegalArgumentException.class,()->cashConversionCycle(
            input("AR_AVG","-1"),input("INVENTORY_AVG","90"),input("AP_AVG","60"),
            input("REV_NET","720"),input("COGS","540"),input("PURCHASES","540"),
            DayCountPolicy.FIXED_360));
    }

    @Test void mixedCurrencyPeriodOrFactRevisionCannotBeCalculated() {
        var usd=new EconomicInput("VARIABLE_COST",new BigDecimal("600"),"USD",
                FROM,UNTIL,REVISION);
        assertThrows(IllegalArgumentException.class,()->contributionMargin(
                input("REV_NET","1000"),usd));
        var wrongRevision=new EconomicInput("VARIABLE_COST",new BigDecimal("600"),
                "KZT",FROM,UNTIL,UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,()->contributionMargin(
                input("REV_NET","1000"),wrongRevision));
        var monthly=new EconomicInput("AR_AVG",new BigDecimal("120"),"KZT",
                FROM,LocalDate.of(2026,2,1),REVISION);
        assertThrows(IllegalArgumentException.class,()->cashConversionCycle(
            monthly,input("INVENTORY_AVG","90"),input("AP_AVG","60"),
            input("REV_NET","720"),input("COGS","540"),input("PURCHASES","540"),
            DayCountPolicy.FIXED_360));
        var allMonthlyPeriod=LocalDate.of(2026,2,1);
        var monthlyAr=new EconomicInput("AR_AVG",new BigDecimal("120"),"KZT",
                FROM,allMonthlyPeriod,REVISION);
        var monthlyInv=new EconomicInput("INVENTORY_AVG",new BigDecimal("90"),"KZT",
                FROM,allMonthlyPeriod,REVISION);
        var monthlyAp=new EconomicInput("AP_AVG",new BigDecimal("60"),"KZT",
                FROM,allMonthlyPeriod,REVISION);
        var monthlyRev=new EconomicInput("REV_NET",new BigDecimal("720"),"KZT",
                FROM,allMonthlyPeriod,REVISION);
        var monthlyCogs=new EconomicInput("COGS",new BigDecimal("540"),"KZT",
                FROM,allMonthlyPeriod,REVISION);
        var monthlyPurchases=new EconomicInput("PURCHASES",new BigDecimal("540"),"KZT",
                FROM,allMonthlyPeriod,REVISION);
        assertEquals("CASH01_ANNUAL_PERIOD_REQUIRED",
                assertThrows(IllegalArgumentException.class,()->cashConversionCycle(
                    monthlyAr,monthlyInv,monthlyAp,monthlyRev,monthlyCogs,
                    monthlyPurchases,DayCountPolicy.FIXED_360)).getMessage());
    }
}
