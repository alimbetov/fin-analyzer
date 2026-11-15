package kz.fin.backend.analytics;

import java.math.*;
import java.util.*;

/**
 * End-of-period, equally spaced annual cash flows: t=0..N. This is NOT XNPV.
 * FCFF must be discounted at WACC; FCFE at cost of equity. No hidden FX or tax.
 * Terminal value is optional and calculated only from explicitly supplied
 * normalized FCFF/FCFE; never inferred from the last projected cash flow.
 */
public final class InvestmentValuation {
    private InvestmentValuation() {}
    private static final MathContext MC = new MathContext(50, RoundingMode.HALF_EVEN);
    private static final int PV_SCALE = 12;

    public enum CashFlowBasis { FCFF, FCFE }
    public enum RateBasis { WACC, COST_OF_EQUITY }

    public record DiscountRate(BigDecimal annualEffectiveRate, RateBasis basis, String methodVersion) {
        public DiscountRate {
            Objects.requireNonNull(annualEffectiveRate);
            Objects.requireNonNull(basis);
            if (methodVersion == null || methodVersion.isBlank()) throw new IllegalArgumentException("RATE_METHOD_REQUIRED");
            if (annualEffectiveRate.compareTo(BigDecimal.ONE.negate()) <= 0)
                throw new IllegalArgumentException("INVALID_DISCOUNT_RATE");
        }
    }

    public record TerminalGrowth(BigDecimal annualGrowth, FinancialAmount normalizedCashFlow) {
        public TerminalGrowth {
            Objects.requireNonNull(annualGrowth);
            Objects.requireNonNull(normalizedCashFlow);
            if (annualGrowth.compareTo(BigDecimal.ONE.negate()) <= 0)
                throw new IllegalArgumentException("INVALID_TERMINAL_GROWTH");
            if (normalizedCashFlow.amount().signum() <= 0)
                throw new IllegalArgumentException("NONPOSITIVE_NORMALIZED_TERMINAL_CASHFLOW");
        }
    }

    public record Input(AnalysisContext context, CashFlowBasis cashFlowBasis, DiscountRate rate,
                        List<FinancialAmount> annualCashFlows, TerminalGrowth terminalGrowth) {
        public Input {
            Objects.requireNonNull(context);
            context.requireFlowPeriod();
            Objects.requireNonNull(cashFlowBasis);
            Objects.requireNonNull(rate);
            Objects.requireNonNull(annualCashFlows);
            if (annualCashFlows.size() < 2 || annualCashFlows.size() > 61) {
                throw new IllegalArgumentException("INVALID_VALUATION_HORIZON");
            }
            annualCashFlows = List.copyOf(annualCashFlows);
            for (FinancialAmount flow : annualCashFlows) context.requireCurrency(flow);
            if (cashFlowBasis == CashFlowBasis.FCFF && rate.basis() != RateBasis.WACC
                    || cashFlowBasis == CashFlowBasis.FCFE && rate.basis() != RateBasis.COST_OF_EQUITY) {
                throw new IllegalArgumentException("CASHFLOW_RATE_BASIS_MISMATCH");
            }
            if (terminalGrowth != null) {
                context.requireCurrency(terminalGrowth.normalizedCashFlow());
                if (rate.annualEffectiveRate().compareTo(terminalGrowth.annualGrowth()) <= 0)
                    throw new IllegalArgumentException("TERMINAL_GROWTH_MUST_BE_BELOW_DISCOUNT_RATE");
            }
        }
    }

    public record DiscountRow(int periodIndex, FinancialAmount cashFlow, FinancialAmount presentValue) {}

    public record Result(AnalysisContext context, CashFlowBasis cashFlowBasis,
                         List<DiscountRow> rows, FinancialAmount explicitPeriodNpv,
                         FinancialAmount terminalValueAtHorizon, FinancialAmount presentValueTerminal,
                         FinancialAmount npvWithTerminal, String methodVersion) {
        public Result { rows = List.copyOf(rows); }
    }

    public static Result calculate(Input input) {
        Objects.requireNonNull(input);
        BigDecimal factorBase = BigDecimal.ONE.add(input.rate().annualEffectiveRate());
        List<DiscountRow> rows = new ArrayList<>();
        FinancialAmount pvOfFlows = FinancialAmount.zero(input.context().currency());
        for (int i = 0; i < input.annualCashFlows().size(); i++) {
            FinancialAmount flow = input.annualCashFlows().get(i);
            BigDecimal presentValue = flow.amount().divide(factorBase.pow(i, MC), MC)
                    .setScale(PV_SCALE, RoundingMode.HALF_EVEN);
            FinancialAmount pv = new FinancialAmount(presentValue, input.context().currency());
            rows.add(new DiscountRow(i, flow, pv));
            pvOfFlows = pvOfFlows.plus(pv);
        }

        FinancialAmount terminalValue = FinancialAmount.zero(input.context().currency());
        FinancialAmount pvTerminal = FinancialAmount.zero(input.context().currency());
        if (input.terminalGrowth() != null) {
            TerminalGrowth tg = input.terminalGrowth();
            BigDecimal rate = input.rate().annualEffectiveRate();
            BigDecimal value = tg.normalizedCashFlow().amount()
                    .multiply(BigDecimal.ONE.add(tg.annualGrowth()), MC)
                    .divide(rate.subtract(tg.annualGrowth()), MC);
            terminalValue = new FinancialAmount(value.setScale(PV_SCALE, RoundingMode.HALF_EVEN),input.context().currency());
            int lastPeriod = input.annualCashFlows().size() - 1;
            BigDecimal discounted = terminalValue.amount().divide(factorBase.pow(lastPeriod, MC),MC)
                    .setScale(PV_SCALE,RoundingMode.HALF_EVEN);
            pvTerminal = new FinancialAmount(discounted,input.context().currency());
        }
        return new Result(input.context(), input.cashFlowBasis(), rows, pvOfFlows,
                terminalValue, pvTerminal, pvOfFlows.plus(pvTerminal), "PERIODIC_NPV_DCF_V1");
    }
}
