package kz.fin.backend.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Pure PRO-03 contribution and CASH-01 working-capital-cycle mathematics.
 * The caller must supply independently approved and meaningfully classified
 * economic inputs. This class NEVER authorizes a fact, guesses variable
 * cost from COGS or purchases from expenses, or publishes a financial report.
 */
public final class OperatingFinanceMethodEngine {
    private OperatingFinanceMethodEngine() {}
    public static final String PRO03_VERSION="PRO03_CONTRIBUTION_V1";
    public static final String CASH01_VERSION="CASH01_CCC_V1";
    private static final int SCALE=12;
    private static final BigDecimal HUNDRED=new BigDecimal("100");

    public record EconomicInput(String metricCode,BigDecimal value,String currency,
                                LocalDate periodStart,LocalDate periodEnd,
                                UUID factSetRevisionId) {
        public EconomicInput {
            if(metricCode==null || !metricCode.matches("[A-Z][A-Z0-9_]{1,127}"))
                throw new IllegalArgumentException("METHOD_METRIC_CODE_INVALID");
            Objects.requireNonNull(value,"value");
            if(value.scale()>12 || value.precision()>38)
                throw new IllegalArgumentException("METHOD_VALUE_PRECISION_INVALID");
            if(currency==null || !currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("METHOD_CURRENCY_INVALID");
            Objects.requireNonNull(periodStart);Objects.requireNonNull(periodEnd);
            Objects.requireNonNull(factSetRevisionId);
            if(!periodStart.isBefore(periodEnd))
                throw new IllegalArgumentException("METHOD_PERIOD_INVALID");
        }
    }
    public record Contribution(BigDecimal contribution,BigDecimal marginPercent,
                               String methodVersion) {}
    public enum DayCountPolicy { FIXED_360, ACTUAL_CALENDAR_DAYS }
    public record CashCycle(BigDecimal dsoDays,BigDecimal dioDays,BigDecimal dpoDays,
                            BigDecimal cashConversionDays,DayCountPolicy dayCountPolicy,
                            String methodVersion) {}

    /**
     * PRO03: net revenue - separately classified VARIABLE_COST.
     * A generic COGS field is NOT a substitute for variable cost.
     */
    public static Contribution contributionMargin(EconomicInput netRevenue,
                                                  EconomicInput variableCost) {
        require("REV_NET",netRevenue);
        require("VARIABLE_COST",variableCost);
        comparable(netRevenue,variableCost);
        if(netRevenue.value().signum()<=0)
            throw new IllegalArgumentException("PRO03_NON_POSITIVE_REVENUE");
        if(variableCost.value().signum()<0)
            throw new IllegalArgumentException("PRO03_NEGATIVE_VARIABLE_COST");
        BigDecimal contribution=netRevenue.value().subtract(variableCost.value());
        return new Contribution(contribution,
                contribution.multiply(HUNDRED).divide(
                        netRevenue.value(),SCALE,RoundingMode.HALF_EVEN),
                PRO03_VERSION);
    }

    /**
     * CASH01: DSO + DIO - DPO. Requires already independently qualified
     * period-average AR, inventory, AP and real purchases, not fallback COGS.
     */
    public static CashCycle cashConversionCycle(
            EconomicInput arAverage,EconomicInput inventoryAverage,
            EconomicInput apAverage,EconomicInput netRevenue,
            EconomicInput cogs,EconomicInput purchases,DayCountPolicy policy) {
        require("AR_AVG",arAverage);
        require("INVENTORY_AVG",inventoryAverage);
        require("AP_AVG",apAverage);
        require("REV_NET",netRevenue);
        require("COGS",cogs);
        require("PURCHASES",purchases);
        Objects.requireNonNull(policy,"dayCountPolicy");
        List<EconomicInput> all=List.of(arAverage,inventoryAverage,apAverage,
                netRevenue,cogs,purchases);
        for(var input:all)comparable(arAverage,input);
        for(var input:List.of(arAverage,inventoryAverage,apAverage))
            if(input.value().signum()<0)
                throw new IllegalArgumentException("CASH01_NEGATIVE_AVERAGE_BALANCE");
        if(netRevenue.value().signum()<=0 || cogs.value().signum()<=0 ||
                purchases.value().signum()<=0)
            throw new IllegalArgumentException("CASH01_NON_POSITIVE_DENOMINATOR");

        // The 360-day method is a year convention, not permission to
        // multiply a single month or an arbitrary 4-year input by 360.
        if(!arAverage.periodStart().plusYears(1).equals(arAverage.periodEnd()))
            throw new IllegalArgumentException("CASH01_ANNUAL_PERIOD_REQUIRED");
        long days=policy==DayCountPolicy.FIXED_360 ? 360L :
                ChronoUnit.DAYS.between(arAverage.periodStart(),arAverage.periodEnd());
        BigDecimal dayBasis=BigDecimal.valueOf(days);
        BigDecimal dso=arAverage.value().multiply(dayBasis)
                .divide(netRevenue.value(),SCALE,RoundingMode.HALF_EVEN);
        BigDecimal dio=inventoryAverage.value().multiply(dayBasis)
                .divide(cogs.value(),SCALE,RoundingMode.HALF_EVEN);
        BigDecimal dpo=apAverage.value().multiply(dayBasis)
                .divide(purchases.value(),SCALE,RoundingMode.HALF_EVEN);
        return new CashCycle(dso,dio,dpo,dso.add(dio).subtract(dpo),
                policy,CASH01_VERSION);
    }

    private static void require(String code,EconomicInput input){
        Objects.requireNonNull(input,"input");
        if(!code.equals(input.metricCode()))
            throw new IllegalArgumentException("METHOD_REQUIRED_METRIC_"+code);
    }
    private static void comparable(EconomicInput a,EconomicInput b){
        if(!a.currency().equals(b.currency()) ||
                !a.periodStart().equals(b.periodStart()) ||
                !a.periodEnd().equals(b.periodEnd()) ||
                !a.factSetRevisionId().equals(b.factSetRevisionId()))
            throw new IllegalArgumentException("METHOD_FACT_CONTEXT_MISMATCH");
    }
}
