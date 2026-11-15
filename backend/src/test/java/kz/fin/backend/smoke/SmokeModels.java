package kz.fin.backend.smoke;

import kz.fin.backend.domain.CalculationDag;
import java.math.*;
import java.util.*;
import static kz.fin.backend.domain.CalculationDag.*;

final class SmokeModels {
    private SmokeModels() {}
    private static Ref r(String code) { return new Ref(code); }
    private static Constant n(String value) { return new Constant(new BigDecimal(value), Dimension.scalar()); }
    private static Add add(Expr a, Expr b) { return new Add(a,b); }
    private static Subtract sub(Expr a, Expr b) { return new Subtract(a,b); }
    private static Multiply mul(Expr a, Expr b) { return new Multiply(a,b); }
    private static Divide div(Expr a, Expr b) { return new Divide(a,b); }
    private static Formula f(String code, Dimension unit, Expr expr) { return new Formula(code,"smoke-v1",unit,expr); }

    static CalculationDag build(String model) {
        Dimension money=Dimension.money(), qty=Dimension.units(), scalar=Dimension.scalar();
        Dimension price=money.divide(qty);
        Map<String,Dimension> in;
        List<Formula> forms;
        switch (model) {
            case "RETAIL" -> {
                in=Map.of("QTY",qty,"UNIT_PRICE",price,"UNIT_COST",price,
                          "RETURNS",money,"OPEX",money);
                forms=List.of(
                    f("GROSS_SALES",money,mul(r("QTY"),r("UNIT_PRICE"))),
                    f("NET_SALES",money,sub(r("GROSS_SALES"),r("RETURNS"))),
                    f("COGS",money,mul(r("QTY"),r("UNIT_COST"))),
                    f("GROSS_PROFIT",money,sub(r("NET_SALES"),r("COGS"))),
                    f("OPERATING_PROFIT",money,sub(r("GROSS_PROFIT"),r("OPEX"))),
                    f("OPERATING_MARGIN_PCT",scalar,mul(div(r("OPERATING_PROFIT"),r("NET_SALES")),n("100"))));
            }
            case "SERVICE" -> {
                in=Map.of("BILLABLE_HOURS",qty,"HOURLY_COST",price,"FEE",money,"OTHER_COST",money);
                forms=List.of(
                    f("DIRECT_LABOR_COST",money,mul(r("BILLABLE_HOURS"),r("HOURLY_COST"))),
                    f("CONTRIBUTION",money,sub(sub(r("FEE"),r("DIRECT_LABOR_COST")),r("OTHER_COST"))),
                    f("CONTRIBUTION_MARGIN_PCT",scalar,mul(div(r("CONTRIBUTION"),r("FEE")),n("100"))));
            }
            case "MANUFACTURING" -> {
                in=Map.of("STARTED",qty,"GOOD",qty,"MATERIAL",money,"LABOR",money,"OVERHEAD",money);
                forms=List.of(
                    f("GOOD_YIELD_PCT",scalar,mul(div(r("GOOD"),r("STARTED")),n("100"))),
                    f("TOTAL_COST",money,add(add(r("MATERIAL"),r("LABOR")),r("OVERHEAD"))),
                    f("UNIT_COST",price,div(r("TOTAL_COST"),r("GOOD"))));
            }
            case "CASH" -> {
                in=Map.of("OPENING_CASH",money,"CASH_IN",money,"CASH_OUT",money);
                forms=List.of(
                    f("CASH_CHANGE",money,sub(r("CASH_IN"),r("CASH_OUT"))),
                    f("CLOSING_CASH",money,add(r("OPENING_CASH"),r("CASH_CHANGE"))));
            }
            case "DEBT" -> {
                in=Map.of("CFADS",money,"PRINCIPAL",money,"INTEREST",money);
                forms=List.of(
                    f("DEBT_SERVICE",money,add(r("PRINCIPAL"),r("INTEREST"))),
                    f("DSCR",scalar,div(r("CFADS"),r("DEBT_SERVICE"))));
            }
            case "STOCK" -> {
                in=Map.of("PERIOD_COGS",money,"AVERAGE_STOCK_VALUE",money);
                forms=List.of(f("INVENTORY_TURNS",scalar,div(r("PERIOD_COGS"),r("AVERAGE_STOCK_VALUE"))));
            }
            case "SAAS" -> {
                in=Map.of("PRIOR_MRR",money,"NEW_MRR",money,"EXPANSION_MRR",money,
                          "CHURN_MRR",money,"CONTRACTION_MRR",money);
                forms=List.of(
                    f("MRR_END",money,sub(sub(add(add(r("PRIOR_MRR"),r("NEW_MRR")),
                         r("EXPANSION_MRR")),r("CHURN_MRR")),r("CONTRACTION_MRR"))),
                    f("MRR_GROWTH_PCT",scalar,mul(div(sub(r("MRR_END"),r("PRIOR_MRR")),r("PRIOR_MRR")),n("100"))));
            }
            case "FORECAST" -> {
                in=Map.of("BASE_REVENUE",money,"GROWTH_RATE",scalar);
                forms=List.of(
                    f("FORECAST_REVENUE",money,mul(r("BASE_REVENUE"),add(n("1"),r("GROWTH_RATE")))),
                    f("REVENUE_CHANGE",money,sub(r("FORECAST_REVENUE"),r("BASE_REVENUE"))));
            }
            default -> throw new IllegalArgumentException("unknown smoke model: "+model);
        }
        return new CalculationDag(in,forms,MathContext.DECIMAL128);
    }
}
