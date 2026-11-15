package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AutoIndicatorEngine;
import kz.fin.backend.analytics.AutoIndicatorEngine.*;
import kz.fin.backend.domain.EconomicKernel;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

/**
 * Deterministic, non-causal financial diagnostic pilot. Rule triggers describe
 * observable patterns and reviewable hypotheses, never a credit rating, an
 * audit opinion, an automated investment decision or guaranteed future cash.
 *
 * Inputs must come from one trusted, published economic revision. The pure
 * calculation engine is not an authentication or source approval boundary.
 */
public final class BusinessDiagnosticEngine {
    private BusinessDiagnosticEngine() {}
    public static final String METHOD_VERSION = "BUSINESS_DIAGNOSTIC_V1";

    public enum FindingKind { OBSERVATION, HYPOTHESIS, MISSING_EVIDENCE }
    public enum RuleCode {
        REVENUE_YOY_DECLINE,
        GROSS_MARGIN_YOY_COMPRESSION,
        REVENUE_CASH_AR_DIVERGENCE
    }

    /** No arbitrary user-executable expressions; additions require code and tests. */
    public record RuleDefinition(RuleCode code, String version, String explanationKey,
                                 String playbookCode, List<String> requiredMetrics) {
        public RuleDefinition {
            Objects.requireNonNull(code);
            if(version==null || version.isBlank() || explanationKey==null || explanationKey.isBlank()
                    || playbookCode==null || playbookCode.isBlank())
                throw new IllegalArgumentException("INVALID_RULE_METADATA");
            requiredMetrics=List.copyOf(requiredMetrics);
            if(requiredMetrics.isEmpty() || new HashSet<>(requiredMetrics).size()!=requiredMetrics.size())
                throw new IllegalArgumentException("INVALID_RULE_DEPENDENCIES");
        }
    }

    public static final List<RuleDefinition> PILOT_RULES=List.of(
            new RuleDefinition(RuleCode.REVENUE_YOY_DECLINE,"1.0.0",
                    "diagnosis.revenue.yoy.decline","REVENUE_DRIVER_REVIEW",List.of("REV_NET")),
            new RuleDefinition(RuleCode.GROSS_MARGIN_YOY_COMPRESSION,"1.0.0",
                    "diagnosis.margin.yoy.compression","PRICING_COST_MIX_REVIEW",
                    List.of("REV_NET","COGS")),
            new RuleDefinition(RuleCode.REVENUE_CASH_AR_DIVERGENCE,"1.0.0",
                    "diagnosis.cash.ar.divergence","WORKING_CAPITAL_REVIEW",
                    List.of("REV_NET","CASH_END","AR_END")));

    public record MetricHistory(SeriesKey key,List<Observation> observations) {
        public MetricHistory {
            Objects.requireNonNull(key);
            observations=List.copyOf(observations);
            for(var o:observations){
                if(!key.equals(o.series())) throw new IllegalArgumentException("MIXED_SERIES_CONTEXT");
            }
        }
    }

    public record Trend(String metricCode, String unitCode, String currencyCode,
                        YearMonth currentMonth, Calculation current, Calculation previousMonth,
                        Calculation yearAgo, Calculation momAbsolute, Calculation momPercent,
                        Calculation yoyAbsolute, Calculation yoyPercent, Calculation ttm,
                        Calculation movingAverage3) {}

    public record Evidence(String metricCode, YearMonth month, BigDecimal value,
                           String unitCode, String currencyCode) {}

    public record Finding(RuleCode ruleCode, String ruleVersion, FindingKind kind,
                          String explanationKey, String playbookCode,
                          List<String> missingMetrics, List<Evidence> evidence) {
        public Finding {
            Objects.requireNonNull(ruleCode);
            Objects.requireNonNull(ruleVersion);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(explanationKey);
            Objects.requireNonNull(playbookCode);
            missingMetrics=List.copyOf(missingMetrics);
            evidence=List.copyOf(evidence);
            if(kind==FindingKind.MISSING_EVIDENCE && missingMetrics.isEmpty())
                throw new IllegalArgumentException("MISSING_EVIDENCE_MUST_NAME_INPUTS");
            if(kind!=FindingKind.MISSING_EVIDENCE && !missingMetrics.isEmpty())
                throw new IllegalArgumentException("OBSERVATION_CANNOT_CLAIM_MISSING_INPUTS");
        }
    }

    public record Assessment(YearMonth evaluatedMonth, String methodVersion,
                             Map<String,Trend> trends, List<Finding> findings) {
        public Assessment {
            trends=Collections.unmodifiableMap(new TreeMap<>(trends));
            findings=List.copyOf(findings);
        }
    }

    /**
     * Evaluates a fixed, versioned set of tested rule implementations.
     * No universal severity/health scores; directional signals need independent
     * expert review and seasonal/industry applicability before production use.
     */
    public static Assessment assess(YearMonth month,Collection<MetricHistory> histories,
                                    List<RuleDefinition> rules) {
        Objects.requireNonNull(month);
        Objects.requireNonNull(histories);
        Objects.requireNonNull(rules);
        if(rules.isEmpty()) throw new IllegalArgumentException("EMPTY_RULE_SET");

        Map<String,MetricHistory> byMetric=new TreeMap<>();
        SeriesKey anchor=null;
        for(MetricHistory h:histories) {
            if(anchor==null) anchor=h.key();
            else if(!sameScope(anchor,h.key()))
                throw new IllegalArgumentException("CROSS_SCOPE_DIAGNOSTIC");
            if(byMetric.putIfAbsent(h.key().metricCode(),h)!=null)
                throw new IllegalArgumentException("DUPLICATE_DIAGNOSTIC_METRIC");
        }

        Set<RuleCode> seen=new HashSet<>();
        for(RuleDefinition r:rules) {
            if(!seen.add(r.code())) throw new IllegalArgumentException("DUPLICATE_DIAGNOSTIC_RULE");
            if(!PILOT_RULES.contains(r))
                throw new IllegalArgumentException("UNRECOGNIZED_OR_MODIFIED_RULE_VERSION");
        }
        Map<String,Trend> trends=new TreeMap<>();
        for(var entry:byMetric.entrySet())
            trends.put(entry.getKey(),trend(month,entry.getValue()));

        List<Finding> findings=new ArrayList<>();
        for(RuleDefinition rule:rules) {
            validateComparableInputs(rule,byMetric);
            evaluate(rule,month,trends,findings);
        }
        return new Assessment(month,METHOD_VERSION,trends,findings);
    }

    public static Trend trend(YearMonth currentMonth,MetricHistory history){
        Objects.requireNonNull(currentMonth);
        Objects.requireNonNull(history);
        var key=history.key();
        Calculation current=monthValue(history,currentMonth);
        Calculation previous=monthValue(history,currentMonth.minusMonths(1));
        Calculation yearAgo=monthValue(history,currentMonth.minusYears(1));
        Comparison mom=compare(history,currentMonth.minusMonths(1),currentMonth);
        Comparison yoy=compare(history,currentMonth.minusYears(1),currentMonth);
        Calculation ttm=key.measureKind()==MeasureKind.FLOW
                ? rangeValue(history,currentMonth.minusMonths(11),currentMonth)
                : AutoIndicatorEngine.blocked(Status.NOT_COMPUTABLE,"TTM_REQUIRES_FLOW");
        Calculation moving=key.measureKind()==MeasureKind.FLOW || key.measureKind()==MeasureKind.STOCK
                ? movingAverage3(history,currentMonth)
                : AutoIndicatorEngine.blocked(Status.NOT_COMPUTABLE,"MOVING_MEAN_UNSUPPORTED_MEASURE");
        return new Trend(key.metricCode(),key.unitCode(),key.currencyCode(),currentMonth,
                current,previous,yearAgo,mom.absoluteChange(),mom.percentChange(),
                yoy.absoluteChange(),yoy.percentChange(),ttm,moving);
    }

    private static EconomicKernel.TemporalReference month(YearMonth m){
        return EconomicKernel.TemporalReference.interval(m.atDay(1),m.plusMonths(1).atDay(1));
    }
    private static List<Observation> intersecting(MetricHistory history,
                                                    EconomicKernel.TemporalReference interval){
        return history.observations().stream().filter(o->{
            var t=o.time();
            if(t.kind()==EconomicKernel.TimeKind.AS_OF)
                return !t.startInclusive().isBefore(interval.startInclusive())
                        && t.startInclusive().isBefore(interval.endExclusive());
            return t.startInclusive().isBefore(interval.endExclusive())
                    && t.endExclusive().isAfter(interval.startInclusive());
        }).toList();
    }
    private static Aggregation aggregation(MeasureKind k){
        return switch(k){
            case FLOW -> Aggregation.FLOW_SUM;
            case STOCK -> Aggregation.STOCK_CLOSING;
            case LEVEL -> Aggregation.DAY_WEIGHTED_LEVEL_MEAN;
        };
    }
    private static Calculation monthValue(MetricHistory h,YearMonth m){
        var interval=month(m);
        return AutoIndicatorEngine.aggregate(h.key(),interval,intersecting(h,interval),
                aggregation(h.key().measureKind())).calculation();
    }
    private static Calculation rangeValue(MetricHistory h,YearMonth start,YearMonth end){
        if(h.key().measureKind()!=MeasureKind.FLOW)
            throw new IllegalArgumentException("TTM_REQUIRES_FLOW");
        var interval=EconomicKernel.TemporalReference.interval(start.atDay(1),end.plusMonths(1).atDay(1));
        return AutoIndicatorEngine.aggregate(h.key(),interval,intersecting(h,interval),
                Aggregation.FLOW_SUM).calculation();
    }
    private static Calculation movingAverage3(MetricHistory h,YearMonth month){
        // Evaluating this window through the primitive preserves the strict
        // full-history requirement (no partial 1-of-3 averages).
        return AutoIndicatorEngine.movingAverage(h.key(),h.observations(),month,month,3,
                aggregation(h.key().measureKind())).get(month);
    }
    private static Comparison compare(MetricHistory h,YearMonth oldMonth,YearMonth newMonth){
        var previous=new PeriodPoint(h.key(),month(oldMonth),monthValue(h,oldMonth));
        var current=new PeriodPoint(h.key(),month(newMonth),monthValue(h,newMonth));
        return AutoIndicatorEngine.compare(previous,current);
    }
    private static boolean sameScope(SeriesKey a,SeriesKey b){
        return a.organizationId().equals(b.organizationId())
                && a.economicEntityId().equals(b.economicEntityId())
                && a.businessScopeId().equals(b.businessScopeId())
                && a.factSetRevisionId().equals(b.factSetRevisionId())
                && a.reportingBasis()==b.reportingBasis() && a.dataSpace()==b.dataSpace()
                && a.dimensions().equals(b.dimensions());
    }
    private static boolean valid(Calculation c){
        return c.status()==Status.CALCULATED;
    }
    private static boolean greater(Calculation a,Calculation b){
        return a.value().compareTo(b.value())>0;
    }
    private static boolean less(Calculation a,Calculation b){
        return a.value().compareTo(b.value())<0;
    }

    private static void validateComparableInputs(RuleDefinition rule,
                                                 Map<String,MetricHistory> histories) {
        for(String code:rule.requiredMetrics()) {
            MetricHistory candidate=histories.get(code);
            if(candidate==null) continue; // handled by explicit MISSING_EVIDENCE
            SeriesKey key=candidate.key();
            MeasureKind expected=code.equals("CASH_END") || code.equals("AR_END")
                    ? MeasureKind.STOCK : MeasureKind.FLOW;
            if(key.measureKind()!=expected || !key.unitCode().equals("MONEY")
                    || key.currencyCode()==null)
                throw new IllegalArgumentException("DIAGNOSTIC_METRIC_SEMANTICS_MISMATCH");
            MetricHistory revenue=histories.get("REV_NET");
            if(revenue!=null && !key.currencyCode().equals(revenue.key().currencyCode()))
                throw new IllegalArgumentException("DIAGNOSTIC_CURRENCY_MISMATCH");
        }
    }

    private static void evaluate(RuleDefinition rule,YearMonth month,
                                 Map<String,Trend> trends,List<Finding> findings){
        List<String> missing=new ArrayList<>();
        for(String code:rule.requiredMetrics()){
            Trend t=trends.get(code);
            if(t==null || !valid(t.current())) {
                missing.add(code+":CURRENT"); continue;
            }
            if(!valid(rule.code()==RuleCode.REVENUE_CASH_AR_DIVERGENCE
                    ? t.previousMonth() : t.yearAgo()))
                missing.add(code+(rule.code()==RuleCode.REVENUE_CASH_AR_DIVERGENCE
                        ? ":PREVIOUS_MONTH" : ":YEAR_AGO"));
        }
        if(!missing.isEmpty()){
            findings.add(new Finding(rule.code(),rule.version(),FindingKind.MISSING_EVIDENCE,
                    rule.explanationKey()+".missing",rule.playbookCode(),missing,List.of()));
            return;
        }

        Trend revenue=trends.get("REV_NET");
        switch(rule.code()){
            case REVENUE_YOY_DECLINE -> {
                if(less(revenue.current(),revenue.yearAgo()))
                    findings.add(new Finding(rule.code(),rule.version(),FindingKind.OBSERVATION,
                            rule.explanationKey(),rule.playbookCode(),List.of(),
                            List.of(evidence(revenue,month),evidence(revenue,month.minusYears(1)))));
            }
            case GROSS_MARGIN_YOY_COMPRESSION -> {
                Trend cogs=trends.get("COGS");
                // Margins computed on matched revenue and COGS for each
                // period, never arithmetic averages of monthly percentages.
                if(revenue.current().value().signum()<=0 || revenue.yearAgo().value().signum()<=0){
                    findings.add(new Finding(rule.code(),rule.version(),FindingKind.MISSING_EVIDENCE,
                            rule.explanationKey()+".positive_revenue_required",rule.playbookCode(),
                            List.of("REV_NET:POSITIVE_DENOMINATOR"),List.of()));
                    return;
                }
                BigDecimal currentGross=revenue.current().value().subtract(cogs.current().value());
                BigDecimal priorGross=revenue.yearAgo().value().subtract(cogs.yearAgo().value());
                // cross-multiply to avoid any rounding of the margin itself
                if(currentGross.multiply(revenue.yearAgo().value())
                        .compareTo(priorGross.multiply(revenue.current().value()))<0)
                    findings.add(new Finding(rule.code(),rule.version(),FindingKind.OBSERVATION,
                            rule.explanationKey(),rule.playbookCode(),List.of(),
                            List.of(evidence(revenue,month),evidence(cogs,month),
                                    evidence(revenue,month.minusYears(1)),
                                    evidence(cogs,month.minusYears(1)))));
            }
            case REVENUE_CASH_AR_DIVERGENCE -> {
                Trend cash=trends.get("CASH_END"),ar=trends.get("AR_END");
                if(greater(revenue.current(),revenue.previousMonth())
                        && less(cash.current(),cash.previousMonth())
                        && greater(ar.current(),ar.previousMonth())){
                    findings.add(new Finding(rule.code(),rule.version(),FindingKind.OBSERVATION,
                            rule.explanationKey(),rule.playbookCode(),List.of(),
                            List.of(evidence(revenue,month),evidence(revenue,month.minusMonths(1)),
                                    evidence(cash,month),evidence(cash,month.minusMonths(1)),
                                    evidence(ar,month),evidence(ar,month.minusMonths(1)))));
                    findings.add(new Finding(rule.code(),rule.version(),FindingKind.HYPOTHESIS,
                            rule.explanationKey()+".collection_hypothesis",rule.playbookCode(),List.of(),
                            List.of(evidence(ar,month),evidence(ar,month.minusMonths(1)))));
                }
            }
        }
    }

    private static Evidence evidence(Trend t,YearMonth m){
        Calculation c = m.equals(t.currentMonth()) ? t.current() :
                m.equals(t.currentMonth().minusMonths(1)) ? t.previousMonth() :
                m.equals(t.currentMonth().minusYears(1)) ? t.yearAgo() :
                    throwBadEvidenceMonth();
        if(!valid(c)) throw new IllegalArgumentException("NON_ACTUAL_EVIDENCE");
        return new Evidence(t.metricCode(),m,c.value(),t.unitCode(),t.currencyCode());
    }
    private static Calculation throwBadEvidenceMonth(){
        throw new IllegalArgumentException("UNSUPPORTED_EVIDENCE_MONTH");
    }
}
