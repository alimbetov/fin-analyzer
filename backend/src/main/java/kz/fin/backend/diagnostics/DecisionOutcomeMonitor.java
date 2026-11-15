package kz.fin.backend.diagnostics;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

/**
 * R4.4 follow-up monitor: compares observed/synthetic closing cash against the
 * frozen action plan, without pretending to establish causal realization.
 * No actual-data authorization or execution side effects live in this class.
 */
public final class DecisionOutcomeMonitor {
    private DecisionOutcomeMonitor() {}
    public enum Status { SIMULATED_ONLY, NOT_COMPUTABLE }
    public record ObservedCash(YearMonth month,BigDecimal closingCash,String sourceRevision) {
        public ObservedCash {
            Objects.requireNonNull(month);Objects.requireNonNull(closingCash);
            if(sourceRevision==null||sourceRevision.isBlank())
                throw new IllegalArgumentException("OUTCOME_EVIDENCE_REVISION_REQUIRED");
        }
    }
    public record ReviewRow(YearMonth month,BigDecimal plannedClosingCash,
                            BigDecimal observedClosingCash,BigDecimal varianceToPlan,
                            String sourceRevision) {}
    public record Review(Status status,String reason,List<ReviewRow> rows,
                         BigDecimal finalVarianceToPlan,String scenarioVersion) {
        public Review{rows=List.copyOf(rows);}
    }
    public static Review compare(DecisionScenarioEngine.Comparison plan,List<ObservedCash> observations){
        Objects.requireNonNull(plan);
        observations=List.copyOf(Objects.requireNonNull(observations));
        if(plan.status()!=DecisionScenarioEngine.Status.SIMULATED_ONLY)
            throw new IllegalArgumentException("UNKNOWN_SCENARIO_PROOF");
        Map<YearMonth,ObservedCash> byMonth=new TreeMap<>();
        for(ObservedCash item:observations){
            if(byMonth.putIfAbsent(item.month(),item)!=null)
                throw new IllegalArgumentException("DUPLICATE_OUTCOME_OBSERVATION");
        }
        List<ReviewRow> rows=new ArrayList<>();
        if(observations.size()!=plan.monthly().size())
            return new Review(Status.NOT_COMPUTABLE,"INCOMPLETE_OBSERVED_HORIZON",List.of(),
                    null,plan.methodVersion());
        for(DecisionScenarioEngine.MonthResult month:plan.monthly()){
            ObservedCash observation=byMonth.get(month.month());
            if(observation==null)
                return new Review(Status.NOT_COMPUTABLE,"MISSING_OBSERVED_MONTH",List.of(),
                        null,plan.methodVersion());
            rows.add(new ReviewRow(month.month(),month.actionClosingCash(),
                    observation.closingCash(),observation.closingCash()
                    .subtract(month.actionClosingCash()),observation.sourceRevision()));
        }
        return new Review(Status.SIMULATED_ONLY,
                "PLAN_VS_OBSERVATION_NOT_CAUSAL_EFFECT_OR_VERIFIED_ACTUAL",
                rows,rows.getLast().varianceToPlan(),plan.methodVersion());
    }
}
