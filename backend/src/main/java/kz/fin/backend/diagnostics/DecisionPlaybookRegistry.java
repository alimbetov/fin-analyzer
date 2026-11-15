package kz.fin.backend.diagnostics;

import java.util.*;

/**
 * Explainable review plans linked to rule findings, NOT autonomous actions.
 * Each task requires a human owner/approval workflow before execution.
 * Predicted financial impact is intentionally absent until a scenario model
 * with approved assumptions has been evaluated.
 */
public final class DecisionPlaybookRegistry {
    private DecisionPlaybookRegistry() {}

    public record Action(String actionCode,String translationKey,
                         String verificationMetricCode,boolean requiresHumanApproval) {
        public Action {
            if(actionCode==null||!actionCode.matches("[A-Z][A-Z0-9_]{1,127}")
                    ||translationKey==null||translationKey.isBlank()
                    ||verificationMetricCode==null||!verificationMetricCode.matches("[A-Z][A-Z0-9_]{1,127}"))
                throw new IllegalArgumentException("INVALID_ACTION_DEFINITION");
        }
    }
    public record Playbook(String code,String version,List<Action> actions) {
        public Playbook {
            if(code==null||!code.matches("[A-Z][A-Z0-9_]{1,127}")
                    ||version==null||version.isBlank())
                throw new IllegalArgumentException("INVALID_PLAYBOOK");
            actions=List.copyOf(actions);
            if(actions.isEmpty()||actions.stream().map(Action::actionCode).distinct().count()!=actions.size())
                throw new IllegalArgumentException("EMPTY_OR_DUPLICATE_PLAYBOOK_STEPS");
        }
    }

    public static final List<Playbook> PILOT_PLAYBOOKS=List.of(
            new Playbook("REVENUE_DRIVER_REVIEW","1.0.0",List.of(
                    new Action("VERIFY_PERIOD_COMPARABILITY",
                            "action.revenue.verify_periods","REV_NET",true),
                    new Action("RECONCILE_PRICE_VOLUME_MIX",
                            "action.revenue.price_volume_mix","REV_NET",true),
                    new Action("VALIDATE_CUSTOMER_AND_RETURNS",
                            "action.revenue.customer_returns","REV_NET",true)
            )),
            new Playbook("PRICING_COST_MIX_REVIEW","1.0.0",List.of(
                    new Action("RECONCILE_REVENUE_AND_COGS",
                            "action.margin.reconcile_cogs","GROSS_MARGIN",true),
                    new Action("ANALYZE_PRICE_AND_INPUT_COST",
                            "action.margin.price_input_cost","GROSS_MARGIN",true),
                    new Action("SIMULATE_MARGIN_OPTIONS",
                            "action.margin.simulate_options","GROSS_MARGIN",true)
            )),
            new Playbook("WORKING_CAPITAL_REVIEW","1.0.0",List.of(
                    new Action("RECONCILE_RECEIPTS_TO_REVENUE",
                            "action.workingcapital.cash_reconciliation","CASH_END",true),
                    new Action("REVIEW_RECEIVABLE_AGING",
                            "action.workingcapital.aging","AR_END",true),
                    new Action("PREPARE_13_WEEK_CASH_PLAN",
                            "action.workingcapital.cash_forecast","CASH_END",true)
            )));

    public static Playbook byCode(String code) {
        return PILOT_PLAYBOOKS.stream().filter(p->p.code().equals(code)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("UNKNOWN_DECISION_PLAYBOOK"));
    }
}
