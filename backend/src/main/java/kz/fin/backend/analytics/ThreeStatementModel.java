package kz.fin.backend.analytics;

import java.math.BigDecimal;
import java.util.*;

/**
 * Minimal linked P&L, balance and cash-flow model with no unspecified plug.
 * Assumptions: one currency; no FX, leases, other assets, deferred tax or other
 * non-cash items; interest/tax expense paid during period; no exceptional items.
 * Outputs are provisional calculations, NOT approved financial statements.
 */
public final class ThreeStatementModel {
    private ThreeStatementModel() {}

    public record OpeningBalance(
            FinancialAmount cash, FinancialAmount receivables, FinancialAmount inventory,
            FinancialAmount propertyPlantEquipment, FinancialAmount payables,
            FinancialAmount debt, FinancialAmount equity
    ) {}

    public record PeriodDrivers(
            FinancialAmount revenue, FinancialAmount costOfSales, FinancialAmount operatingExpenses,
            FinancialAmount depreciation, FinancialAmount interestExpense, FinancialAmount taxExpense,
            FinancialAmount capex, FinancialAmount receivablesChange,
            FinancialAmount inventoryChange, FinancialAmount payablesChange,
            FinancialAmount newDebt, FinancialAmount debtRepaid,
            FinancialAmount contributedCapital, FinancialAmount dividends
    ) {}

    public record ProfitAndLoss(FinancialAmount grossProfit, FinancialAmount operatingProfit,
                                FinancialAmount netProfit) {}

    public record CashFlow(FinancialAmount operating, FinancialAmount investing,
                           FinancialAmount financing, FinancialAmount closingCash) {}

    public record ClosingBalance(FinancialAmount cash, FinancialAmount receivables,
                                 FinancialAmount inventory, FinancialAmount propertyPlantEquipment,
                                 FinancialAmount payables, FinancialAmount debt, FinancialAmount equity) {
        public FinancialAmount totalAssets() {
            return cash.plus(receivables).plus(inventory).plus(propertyPlantEquipment);
        }
        public FinancialAmount liabilitiesAndEquity() {
            return payables.plus(debt).plus(equity);
        }
    }

    public record Result(AnalysisContext context, ProfitAndLoss profitAndLoss,
                         CashFlow cashFlow, ClosingBalance closingBalance,
                         List<String> warnings, String methodVersion) {
        public Result { warnings = List.copyOf(warnings); }
    }

    public static Result calculate(AnalysisContext context, OpeningBalance start, PeriodDrivers d) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(start);
        Objects.requireNonNull(d);
        context.requireFlowPeriod();

        for (FinancialAmount a : List.of(start.cash(),start.receivables(),start.inventory(),
                start.propertyPlantEquipment(),start.payables(),start.debt(),start.equity(),
                d.revenue(),d.costOfSales(),d.operatingExpenses(),d.depreciation(),
                d.interestExpense(),d.taxExpense(),d.capex(),d.receivablesChange(),
                d.inventoryChange(),d.payablesChange(),d.newDebt(),d.debtRepaid(),
                d.contributedCapital(),d.dividends())) context.requireCurrency(a);

        start.cash().requireNonNegative("OPENING_CASH");
        start.receivables().requireNonNegative("OPENING_RECEIVABLES");
        start.inventory().requireNonNegative("OPENING_INVENTORY");
        start.propertyPlantEquipment().requireNonNegative("OPENING_PPE");
        start.payables().requireNonNegative("OPENING_PAYABLES");
        start.debt().requireNonNegative("OPENING_DEBT");

        for (var e : Map.ofEntries(
                Map.entry("REVENUE",d.revenue()),
                Map.entry("COGS",d.costOfSales()),
                Map.entry("OPEX",d.operatingExpenses()),
                Map.entry("DEPRECIATION",d.depreciation()),
                Map.entry("INTEREST",d.interestExpense()),
                Map.entry("TAX",d.taxExpense()),
                Map.entry("CAPEX",d.capex()),
                Map.entry("NEW_DEBT",d.newDebt()),
                Map.entry("REPAYMENT",d.debtRepaid()),
                Map.entry("EQUITY_CONTRIBUTION",d.contributedCapital()),
                Map.entry("DIVIDENDS",d.dividends())).entrySet()) {
            e.getValue().requireNonNegative(e.getKey());
        }

        FinancialAmount openingAssets = start.cash().plus(start.receivables())
                .plus(start.inventory()).plus(start.propertyPlantEquipment());
        FinancialAmount openingLiabilities = start.payables().plus(start.debt()).plus(start.equity());
        if (openingAssets.amount().compareTo(openingLiabilities.amount()) != 0)
            throw new IllegalArgumentException("OPENING_BALANCE_NOT_BALANCED");

        FinancialAmount gross = d.revenue().minus(d.costOfSales());
        FinancialAmount operatingProfit = gross.minus(d.operatingExpenses()).minus(d.depreciation());
        FinancialAmount netProfit = operatingProfit.minus(d.interestExpense()).minus(d.taxExpense());

        // Working-capital changes are closing minus opening. No assumption of
        // cash receipts = revenue, purchases = COGS or CAPEX = expense.
        FinancialAmount cfo = netProfit.plus(d.depreciation())
                .minus(d.receivablesChange()).minus(d.inventoryChange()).plus(d.payablesChange());
        FinancialAmount cfi = d.capex().times(BigDecimal.ONE.negate());
        FinancialAmount cff = d.newDebt().minus(d.debtRepaid())
                .plus(d.contributedCapital()).minus(d.dividends());
        FinancialAmount endCash = start.cash().plus(cfo).plus(cfi).plus(cff);

        ClosingBalance end = new ClosingBalance(
                endCash,
                start.receivables().plus(d.receivablesChange()),
                start.inventory().plus(d.inventoryChange()),
                start.propertyPlantEquipment().plus(d.capex()).minus(d.depreciation()),
                start.payables().plus(d.payablesChange()),
                start.debt().plus(d.newDebt()).minus(d.debtRepaid()),
                start.equity().plus(netProfit).plus(d.contributedCapital()).minus(d.dividends()));
        for (var e : Map.of(
                "RECEIVABLES",end.receivables(),
                "INVENTORY",end.inventory(),
                "PPE",end.propertyPlantEquipment(),
                "PAYABLES",end.payables(),
                "DEBT",end.debt()).entrySet()) e.getValue().requireNonNegative("CLOSING_" + e.getKey());

        if (end.totalAssets().amount().compareTo(end.liabilitiesAndEquity().amount()) != 0)
            throw new IllegalStateException("CLOSING_BALANCE_NOT_BALANCED");

        List<String> warnings = new ArrayList<>();
        if (endCash.amount().signum() < 0) warnings.add("LIQUIDITY_SHORTFALL");
        return new Result(context, new ProfitAndLoss(gross, operatingProfit, netProfit),
                new CashFlow(cfo,cfi,cff,endCash),end,warnings,"SIMPLIFIED_3STATEMENT_V1");
    }
}
