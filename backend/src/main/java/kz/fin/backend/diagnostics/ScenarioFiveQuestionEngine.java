package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.diagnostics.ScenarioEconomicsEngine.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * Deterministic five-question contract over synthetic scenario arithmetic.
 *
 * NOT a causal attribution engine, a certified severity score, or a trusted
 * source authorization boundary. Input APPROVED flags are test assumptions.
 * Production MUST resolve authoritative facts server-side and qualify methods.
 */
public final class ScenarioFiveQuestionEngine {
    private ScenarioFiveQuestionEngine() {}
    public static final String VERSION = "FIVE_QUESTION_SYNTHETIC_V1";

    public enum GateState {
        CALCULATED_SYNTHETIC, HYPOTHESIS_ONLY, NOT_ASSESSED,
        REVIEW_REQUIRED, REVIEW_SUGGESTED, SIMULATED_ONLY, NOT_COMPUTABLE
    }
    public record Gate(GateState state, String reason) {
        public Gate {
            Objects.requireNonNull(state);
            if(reason == null || reason.isBlank())
                throw new IllegalArgumentException("FIVE_Q_REASON_REQUIRED");
        }
    }
    public record Evidence(String formCode, String sheetCode, String sourceRevision,
                           String itemKey, String rowId, String period) {}
    public record Result(String contractVersion, String methodVersion, String scenarioCode,
                         Method method, String metricCode, Gate what, Gate why,
                         Gate seriousness, Gate action, Gate effect,
                         Map<String,String> values, Map<String,String> alternatives,
                         Map<String,String> assumptions, List<Evidence> evidence, String reviewAction,
                         boolean humanApprovalRequired) {
        public Result {
            values=Map.copyOf(values);
            alternatives=Map.copyOf(alternatives);
            assumptions=Map.copyOf(assumptions);
            evidence=List.copyOf(evidence);
        }
    }

    private static final Map<Method,String> REVIEW_ACTIONS = Map.ofEntries(
        Map.entry(Method.OWNER_CASH_MOVEMENT, "RECONCILE_RECEIPTS_AND_PAYMENTS"),
        Map.entry(Method.BALANCE_RECONCILIATION, "RECONCILE_BALANCE_SOURCE_LINES"),
        Map.entry(Method.CASH_FLOW_RECONCILIATION, "RECONCILE_CASH_FLOW_BRIDGE"),
        Map.entry(Method.ANNUAL_REVENUE_TREND, "VERIFY_REVENUE_RECOGNITION_AND_YEARS"),
        Map.entry(Method.BUDGET_ACTUAL_VARIANCE, "REVIEW_BUDGET_ACCOUNT_MAPPING"),
        Map.entry(Method.WIP_ROLLFORWARD, "RECONCILE_WORK_IN_PROGRESS"),
        Map.entry(Method.UNIT_PRODUCTION_COST, "VERIFY_PRODUCTION_COST_ALLOCATION"),
        Map.entry(Method.STANDARD_ACTUAL_VARIANCE, "VERIFY_PRICE_AND_USAGE_DRIVERS"),
        Map.entry(Method.BOQ_RESOURCE_COST, "VERIFY_MEASUREMENTS_AND_RATE_BASIS"),
        Map.entry(Method.CONSTRUCTION_EVM, "REVIEW_EVM_ASSUMPTIONS"),
        Map.entry(Method.DATED_PROJECT_NPV, "REVIEW_DISCOUNT_RATE_AND_FCFF"),
        Map.entry(Method.TRADE_GROSS_MARGIN, "RECONCILE_SALES_RETURNS_AND_COGS"),
        Map.entry(Method.STOCK_TURNOVER, "VERIFY_AVERAGE_INVENTORY_POLICY"),
        Map.entry(Method.DSCR_PILOT, "VERIFY_LENDER_CFADS_DEFINITION"),
        Map.entry(Method.WEEKLY_CASH_FORECAST_ROLLFORWARD, "RECONCILE_13_WEEK_CASH_FORECAST"),
        Map.entry(Method.OVERHEAD_ABSORPTION_VARIANCE, "REVIEW_COST_POOL_ALLOCATION_AND_ABSORPTION")
    );

    /**
     * Baseline and counterfactual are evaluated separately and never persisted.
     * Counterfactual must preserve source identity, scope, period and field names;
     * only explicit values may change.
     */
    public static Result evaluate(Input baseline, Input hypothetical, Method method,
                                  String metricCode) {
        Objects.requireNonNull(baseline);
        Objects.requireNonNull(method);
        if(baseline.scope().dataSpace()!=AnalysisContext.DataSpace.SYNTHETIC)
            throw new IllegalArgumentException("FIVE_Q_SYNTHETIC_ONLY");
        if(!REVIEW_ACTIONS.containsKey(method))
            throw new IllegalArgumentException("FIVE_Q_UNQUALIFIED_METHOD");
        if(metricCode==null || !metricCode.matches("[A-Za-z][A-Za-z0-9_]{0,127}"))
            throw new IllegalArgumentException("FIVE_Q_INVALID_METRIC");

        ScenarioEconomicsEngine.Report original=ScenarioEconomicsEngine.evaluate(baseline);
        Finding current=single(original,method);
        List<Evidence> sources=current.sources().stream().map(source -> new Evidence(
                source.formCode(),source.sheetCode(),source.sourceRevision(),
                source.itemKey(),source.rowId(),source.period().toString())).toList();

        boolean numeric=current.computedValues().containsKey(metricCode);
        BigDecimal before=numeric?new BigDecimal(current.computedValues().get(metricCode)):null;
        Map<String,String> values=numeric?Map.of("baseline",before.toPlainString()):Map.of();

        Gate what=new Gate(numeric ? GateState.CALCULATED_SYNTHETIC : GateState.NOT_COMPUTABLE,
                numeric?"SYNTHETIC_CALCULATION_NOT_AUTHORITATIVE":"METRIC_UNAVAILABLE_OR_MISSING_EVIDENCE");
        Gate why=new Gate(numeric ? GateState.HYPOTHESIS_ONLY : GateState.NOT_COMPUTABLE,
                numeric ? "RULE_"+current.reason()+"_IS_NOT_CAUSAL_EVIDENCE"
                        : "WHY_BLOCKED_BY_MISSING_EVIDENCE");
        Gate seriousness=new Gate(GateState.NOT_ASSESSED,
                "NO_APPROVED_SECTOR_MATERIALITY_OR_RISK_CALIBRATION");
        Gate action=new Gate(!numeric ? GateState.NOT_COMPUTABLE :
                        current.verdict()==Verdict.OBSERVATION ? GateState.REVIEW_REQUIRED
                                : GateState.REVIEW_SUGGESTED,
                "CANDIDATE_REVIEW_ACTION_HUMAN_APPROVAL_REQUIRED");

        Map<String,String> alternative=Map.of();
        Map<String,String> assumptions=Map.of();
        Gate effect=new Gate(GateState.NOT_COMPUTABLE, "NO_VALIDATED_COUNTERFACTUAL");
        if(hypothetical!=null) {
            assumptions=requireComparable(baseline,hypothetical);
            Finding changed=single(ScenarioEconomicsEngine.evaluate(hypothetical),method);
            String updated=changed.computedValues().get(metricCode);
            if(numeric && updated!=null && changed.verdict()!=Verdict.MISSING_EVIDENCE) {
                BigDecimal after=new BigDecimal(updated);
                alternative=Map.of("after",after.toPlainString(),
                        "delta",after.subtract(before).toPlainString());
                effect=new Gate(GateState.SIMULATED_ONLY,
                        "SYNTHETIC_INPUT_SUBSTITUTION_NO_COST_OR_FORECAST");
            }
        }
        return new Result(VERSION,original.methodVersion(),baseline.scenarioCode(),method,
                metricCode,what,why,seriousness,action,effect,
                values,alternative,assumptions,sources,REVIEW_ACTIONS.get(method),true);
    }

    private static Finding single(ScenarioEconomicsEngine.Report report, Method method) {
        List<Finding> candidates=report.findings().stream()
                .filter(f->f.method()==method).toList();
        if(candidates.size()!=1)
            throw new IllegalArgumentException("FIVE_Q_REQUIRES_SINGLE_METHOD_SUBJECT");
        return candidates.getFirst();
    }
    private static Map<String,String> requireComparable(Input before,Input after) {
        if(!before.scope().equals(after.scope())
                ||!before.scenarioCode().equals(after.scenarioCode())
                ||before.rows().size()!=after.rows().size())
            throw new IllegalArgumentException("FIVE_Q_CROSS_SCOPE_OR_STRUCTURE");
        Map<RowIdentity,InputRow> left=byStableKey(before.rows());
        Map<RowIdentity,InputRow> right=byStableKey(after.rows());
        if(!left.keySet().equals(right.keySet()))
            throw new IllegalArgumentException("FIVE_Q_CHANGED_SOURCE_IDENTITY");
        Map<String,String> changes=new TreeMap<>();
        for(RowIdentity key:left.keySet()) {
            InputRow a=left.get(key), z=right.get(key);
            if(!a.values().keySet().equals(z.values().keySet()))
                throw new IllegalArgumentException("FIVE_Q_CHANGED_INPUT_SCHEMA");
            for(String field:a.values().keySet()){
                if(!a.values().get(field).equals(z.values().get(field)))
                    changes.put(a.rowId()+"."+field,
                            a.values().get(field).toPlainString()+" -> "
                            +z.values().get(field).toPlainString());
            }
        }
        if(changes.isEmpty())throw new IllegalArgumentException("FIVE_Q_NO_SCENARIO_CHANGE");
        return Map.copyOf(changes);
    }
    // Structural identity avoids delimiter collisions in arbitrary source IDs.
    private record RowIdentity(String formCode,String sheetCode,String itemKey,
                               String rowId,java.time.YearMonth period,
                               java.time.LocalDate eventDate,String currency,
                               String sourceRevision,EvidenceState state) {}
    private static Map<RowIdentity,InputRow> byStableKey(List<InputRow> rows) {
        Map<RowIdentity,InputRow> byKey=new LinkedHashMap<>();
        for(InputRow row:rows) {
            RowIdentity key=new RowIdentity(row.formCode(),row.sheetCode(),
                    row.itemKey(),row.rowId(),row.period(),row.eventDate(),
                    row.currency(),row.sourceRevision(),row.state());
            if(byKey.putIfAbsent(key,row)!=null)
                throw new IllegalArgumentException("FIVE_Q_DUPLICATE_SOURCE_ROW");
        }
        return byKey;
    }
}
