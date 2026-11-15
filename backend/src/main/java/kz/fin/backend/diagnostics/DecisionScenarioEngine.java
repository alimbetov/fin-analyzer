package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AnalysisContext;
import java.math.*;
import java.time.YearMonth;
import java.util.*;

/**
 * R4.4 deterministic monthly cash alternatives, no predictive claims.
 * Costs and their dates are explicit. Source and scenario scopes are immutable.
 */
public final class DecisionScenarioEngine {
    private DecisionScenarioEngine() {}
    public static final String VERSION="FIN_DECISION_SCENARIO_R4_1";
    private static final MathContext MC=new MathContext(34,RoundingMode.HALF_EVEN);
    public enum Status { SIMULATED_ONLY }
    public record Context(UUID organizationId,UUID businessScopeId,UUID factSetRevisionId,
                          String currency,AnalysisContext.DataSpace dataSpace) {
        public Context {
            Objects.requireNonNull(organizationId);Objects.requireNonNull(businessScopeId);
            Objects.requireNonNull(factSetRevisionId);Objects.requireNonNull(dataSpace);
            if(currency==null||!currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("INVALID_SCENARIO_CURRENCY");
        }
    }
    public record BaselineMonth(YearMonth month,BigDecimal receipts,BigDecimal payments,
                                BigDecimal restrictedClosingCash) {
        public BaselineMonth {
            Objects.requireNonNull(month);
            nonnegative(receipts,"BASELINE_RECEIPTS");
            nonnegative(payments,"BASELINE_PAYMENTS");
            nonnegative(restrictedClosingCash,"RESTRICTED_CASH");
        }
    }
    public record ActionMonth(YearMonth month,BigDecimal additionalReceipts,
                              BigDecimal implementationCost,BigDecimal recurringCost) {
        public ActionMonth {
            Objects.requireNonNull(month);
            nonnegative(additionalReceipts,"INCREMENTAL_RECEIPTS");
            nonnegative(implementationCost,"IMPLEMENTATION_COST");
            nonnegative(recurringCost,"RECURRING_COST");
        }
    }
    public record MonthResult(YearMonth month,BigDecimal doNothingClosingCash,
                              BigDecimal actionClosingCash,BigDecimal incrementalNetCash,
                              BigDecimal doNothingUnrestricted,BigDecimal actionUnrestricted) {}
    public record Comparison(Context context,String actionCode,List<MonthResult> monthly,
                             BigDecimal doNothingClosingCash,BigDecimal actionClosingCash,
                             BigDecimal minimumDoNothingUnrestricted,BigDecimal minimumActionUnrestricted,
                             BigDecimal totalIncrementalCash,BigDecimal discountedIncrementalCash,
                             BigDecimal monthlyDiscountRate,Status status,String methodVersion) {
        public Comparison { monthly=List.copyOf(monthly); }
    }
    private static void nonnegative(BigDecimal value,String label){
        Objects.requireNonNull(value,label);
        if(value.signum()<0 || value.precision()>38 || value.scale()>12)
            throw new IllegalArgumentException("INVALID_"+label);
    }
    public static Comparison compare(Context context,String actionCode,BigDecimal openingCash,
                                     List<BaselineMonth> baseline,List<ActionMonth> action,
                                     BigDecimal monthlyDiscountRate){
        Objects.requireNonNull(context);
        if(context.dataSpace()!=AnalysisContext.DataSpace.SYNTHETIC)
            throw new IllegalArgumentException("SCENARIO_ACTUAL_METHOD_NOT_QUALIFIED");
        if(actionCode==null||!actionCode.matches("[A-Z][A-Z0-9_]{1,127}"))
            throw new IllegalArgumentException("INVALID_ACTION_CODE");
        Objects.requireNonNull(openingCash);
        Objects.requireNonNull(monthlyDiscountRate);
        if(monthlyDiscountRate.compareTo(BigDecimal.ONE.negate())<=0)
            throw new IllegalArgumentException("INVALID_DISCOUNT_RATE");
        baseline=List.copyOf(Objects.requireNonNull(baseline));
        action=List.copyOf(Objects.requireNonNull(action));
        if(baseline.isEmpty()||baseline.size()>60||baseline.size()!=action.size())
            throw new IllegalArgumentException("SCENARIO_HORIZON_MISMATCH");
        BigDecimal baseCash=openingCash,actCash=openingCash;
        BigDecimal minBase=null,minAction=null,incremental=BigDecimal.ZERO,discounted=BigDecimal.ZERO;
        List<MonthResult> rows=new ArrayList<>();
        YearMonth previous=null;
        for(int i=0;i<baseline.size();i++){
            BaselineMonth base=baseline.get(i);
            ActionMonth changed=action.get(i);
            if(!base.month().equals(changed.month()) ||
                previous!=null && !previous.plusMonths(1).equals(base.month()))
                throw new IllegalArgumentException("SCENARIO_NONCOMPARABLE_PERIOD");
            previous=base.month();
            BigDecimal baseNet=base.receipts().subtract(base.payments());
            BigDecimal actionNet=changed.additionalReceipts()
                    .subtract(changed.implementationCost()).subtract(changed.recurringCost());
            baseCash=baseCash.add(baseNet);
            actCash=actCash.add(baseNet).add(actionNet);
            BigDecimal baseFree=baseCash.subtract(base.restrictedClosingCash());
            BigDecimal actionFree=actCash.subtract(base.restrictedClosingCash());
            minBase=minBase==null?baseFree:minBase.min(baseFree);
            minAction=minAction==null?actionFree:minAction.min(actionFree);
            incremental=incremental.add(actionNet);
            BigDecimal factor=BigDecimal.ONE.add(monthlyDiscountRate).pow(i+1,MC);
            discounted=discounted.add(actionNet.divide(factor,MC));
            rows.add(new MonthResult(base.month(),baseCash,actCash,actionNet,baseFree,actionFree));
        }
        if(actCash.subtract(baseCash).compareTo(incremental)!=0)
            throw new IllegalStateException("SCENARIO_CASH_BRIDGE_NOT_RECONCILED");
        return new Comparison(context,actionCode,rows,baseCash,actCash,minBase,minAction,
                incremental,discounted.setScale(12,RoundingMode.HALF_EVEN),
                monthlyDiscountRate,Status.SIMULATED_ONLY,VERSION);
    }
}
