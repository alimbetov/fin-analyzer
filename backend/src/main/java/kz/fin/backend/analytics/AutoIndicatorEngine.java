package kz.fin.backend.analytics;

import kz.fin.backend.domain.EconomicKernel;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * FIN automatic analytic columns: strict typed, versioned, period-aware prototypes.
 * Inputs must already be selected from one server-verified approved FactSetRevision.
 * This class does NOT approve sources, authorize tenants or perform currency conversion.
 */
public final class AutoIndicatorEngine {
    private AutoIndicatorEngine() {}
    public static final String METHOD_VERSION = "AUTO_INDICATORS_V1";
    private static final int SCALE = 12;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public enum Aggregation { FLOW_SUM, MONTHLY_FLOW_MEAN, DAY_WEIGHTED_LEVEL_MEAN, STOCK_CLOSING }
    public enum MeasureKind { FLOW, STOCK, LEVEL }
    public enum Status { CALCULATED, ESTIMATED, INCOMPLETE, NOT_COMPUTABLE }

    public record SeriesKey(
            UUID organizationId, UUID economicEntityId, UUID businessScopeId,
            UUID factSetRevisionId, String metricCode, String metricVersion, MeasureKind measureKind,
            String unitCode, String currencyCode, EconomicKernel.ReportingBasis reportingBasis,
            AnalysisContext.DataSpace dataSpace, SortedMap<String,String> dimensions
    ) {
        public SeriesKey {
            Objects.requireNonNull(organizationId);
            Objects.requireNonNull(economicEntityId);
            Objects.requireNonNull(businessScopeId);
            Objects.requireNonNull(factSetRevisionId);
            Objects.requireNonNull(reportingBasis);
            Objects.requireNonNull(dataSpace);
            Objects.requireNonNull(measureKind);
            if (metricCode == null || !metricCode.matches("[A-Z][A-Z0-9_]{1,127}"))
                throw new IllegalArgumentException("INVALID_METRIC_CODE");
            if (metricVersion == null || metricVersion.isBlank())
                throw new IllegalArgumentException("MISSING_METRIC_VERSION");
            if (unitCode == null || !unitCode.matches("[A-Z][A-Z0-9_]{0,31}"))
                throw new IllegalArgumentException("INVALID_UNIT");
            if (currencyCode != null && !currencyCode.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("INVALID_CURRENCY");
            if ("MONEY".equals(unitCode) != (currencyCode != null))
                throw new IllegalArgumentException("MONEY_REQUIRES_CURRENCY_AND_NONMONEY_MUST_NOT_HAVE_CURRENCY");
            TreeMap<String,String> copy = new TreeMap<>(Objects.requireNonNull(dimensions));
            for (var entry : copy.entrySet()) {
                if (entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank())
                    throw new IllegalArgumentException("INVALID_DIMENSION");
            }
            dimensions = Collections.unmodifiableSortedMap(copy);
        }

        public boolean comparableContext(SeriesKey other) {
            return other != null
                    && organizationId.equals(other.organizationId)
                    && economicEntityId.equals(other.economicEntityId)
                    && businessScopeId.equals(other.businessScopeId)
                    && factSetRevisionId.equals(other.factSetRevisionId)
                    && reportingBasis == other.reportingBasis && dataSpace == other.dataSpace
                    && measureKind == other.measureKind
                    && dimensions.equals(other.dimensions);
        }

        public boolean comparableUnit(SeriesKey other) {
            return comparableContext(other) && unitCode.equals(other.unitCode)
                    && Objects.equals(currencyCode, other.currencyCode);
        }
    }

    public record Observation(SeriesKey series, EconomicKernel.TemporalReference time,
                              EconomicKernel.EconomicValue value) {
        public Observation {
            Objects.requireNonNull(series);
            Objects.requireNonNull(time);
            Objects.requireNonNull(value);
        }
    }

    public record Calculation(Status status, BigDecimal value, String reason, String methodVersion) {
        public Calculation {
            Objects.requireNonNull(status);
            Objects.requireNonNull(methodVersion);
            if (status == Status.CALCULATED || status == Status.ESTIMATED) {
                Objects.requireNonNull(value, "numeric result expected");
                if (reason != null && status == Status.CALCULATED)
                    throw new IllegalArgumentException("CALCULATED_WITH_REASON");
                value = normalized(value);
            } else {
                if (value != null || reason == null || reason.isBlank())
                    throw new IllegalArgumentException("INVALID_NON_NUMERIC_RESULT");
            }
        }
    }

    public record PeriodPoint(SeriesKey series, EconomicKernel.TemporalReference period,
                              Calculation calculation) {
        public PeriodPoint {
            Objects.requireNonNull(series);
            Objects.requireNonNull(period);
            Objects.requireNonNull(calculation);
        }
    }

    public record Comparison(Calculation absoluteChange, Calculation percentChange) {}

    public static Calculation ok(BigDecimal v) { return new Calculation(Status.CALCULATED,v,null,METHOD_VERSION); }
    public static Calculation estimated(BigDecimal v) {
        return new Calculation(Status.ESTIMATED,v,"ESTIMATED_INPUT",METHOD_VERSION);
    }
    public static Calculation blocked(Status status, String reason) {
        if (status == Status.CALCULATED || status == Status.ESTIMATED)
            throw new IllegalArgumentException("NUMERIC_STATUS_REQUIRES_VALUE");
        return new Calculation(status,null,reason,METHOD_VERSION);
    }

    private static BigDecimal normalized(BigDecimal value) {
        BigDecimal n = value.stripTrailingZeros();
        if (n.scale() > SCALE || n.precision() - n.scale() > 26)
            throw new IllegalArgumentException("DECIMAL_POLICY_VIOLATION");
        return n;
    }

    private static BigDecimal quotient(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator,SCALE,ROUNDING);
    }

    private static boolean isNumeric(Calculation r) {
        return r.status() == Status.CALCULATED || r.status() == Status.ESTIMATED;
    }

    private static Calculation from(EconomicKernel.EconomicValue input, SeriesKey key) {
        return switch (input.state()) {
            case PRESENT, EXPLICIT_ZERO -> ok(input.decimal());
            case MISSING -> blocked(Status.INCOMPLETE,"MISSING_INPUT");
            case NOT_APPLICABLE -> blocked(Status.NOT_COMPUTABLE,"NOT_APPLICABLE");
            case ESTIMATED -> key.dataSpace() == AnalysisContext.DataSpace.ACTUAL
                    ? blocked(Status.INCOMPLETE,"ESTIMATED_ACTUAL_NOT_ALLOWED")
                    : estimated(input.decimal());
        };
    }

    private static Calculation propagate(Calculation r) {
        return blocked(r.status(),r.reason());
    }

    private static void requireInterval(EconomicKernel.TemporalReference period) {
        if (period.kind() != EconomicKernel.TimeKind.INTERVAL)
            throw new IllegalArgumentException("INTERVAL_REQUIRED");
    }

    private static boolean calendarMonth(EconomicKernel.TemporalReference t) {
        return t.kind() == EconomicKernel.TimeKind.INTERVAL
                && t.startInclusive().getDayOfMonth() == 1
                && t.endExclusive().equals(t.startInclusive().plusMonths(1));
    }

    public static PeriodPoint aggregate(SeriesKey key, EconomicKernel.TemporalReference target,
                                        Collection<Observation> observations, Aggregation aggregation) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(target);
        Objects.requireNonNull(observations);
        Objects.requireNonNull(aggregation);
        requireInterval(target);
        if ((aggregation==Aggregation.FLOW_SUM || aggregation==Aggregation.MONTHLY_FLOW_MEAN)
                && key.measureKind()!=MeasureKind.FLOW
                || aggregation==Aggregation.DAY_WEIGHTED_LEVEL_MEAN && key.measureKind()!=MeasureKind.LEVEL
                || aggregation==Aggregation.STOCK_CLOSING && key.measureKind()!=MeasureKind.STOCK)
            throw new IllegalArgumentException("AGGREGATION_MEASURE_KIND_MISMATCH");
        List<Observation> ordered = new ArrayList<>(observations);
        for (Observation o : ordered) {
            if (!key.equals(o.series())) throw new IllegalArgumentException("MIXED_SERIES_CONTEXT");
        }
        ordered.sort(Comparator.comparing(o -> o.time().startInclusive()));

        if (aggregation == Aggregation.STOCK_CLOSING) {
            LocalDate closingDate = target.endExclusive().minusDays(1);
            List<Observation> closing = ordered.stream()
                    .filter(o -> o.time().kind() == EconomicKernel.TimeKind.AS_OF
                            && o.time().startInclusive().equals(closingDate)).toList();
            if (closing.size() > 1) throw new IllegalArgumentException("DUPLICATE_CLOSING_STOCK");
            return new PeriodPoint(key,target,closing.isEmpty()
                    ? blocked(Status.INCOMPLETE,"MISSING_CLOSING_STOCK")
                    : from(closing.getFirst().value(),key));
        }

        if (aggregation == Aggregation.MONTHLY_FLOW_MEAN
                && (target.startInclusive().getDayOfMonth()!=1
                    || target.endExclusive().getDayOfMonth()!=1))
            throw new IllegalArgumentException("MONTHLY_BUCKET_ALIGNMENT_REQUIRED");

        LocalDate cursor = target.startInclusive();
        BigDecimal total = BigDecimal.ZERO;
        long count = 0;
        boolean anyEstimated = false;
        for (Observation o : ordered) {
            if (o.time().kind() != EconomicKernel.TimeKind.INTERVAL)
                throw new IllegalArgumentException("STOCK_CANNOT_BE_FLOW_AGGREGATED");
            if (o.time().startInclusive().isBefore(target.startInclusive())
                    || o.time().endExclusive().isAfter(target.endExclusive()))
                throw new IllegalArgumentException("OUT_OF_WINDOW_OBSERVATION");
            if (o.time().startInclusive().isBefore(cursor))
                throw new IllegalArgumentException("OVERLAPPING_OBSERVATION");
            if (o.time().startInclusive().isAfter(cursor))
                return new PeriodPoint(key,target,blocked(Status.INCOMPLETE,"MISSING_PERIODS"));
            if (aggregation == Aggregation.MONTHLY_FLOW_MEAN && !calendarMonth(o.time()))
                throw new IllegalArgumentException("MONTHLY_BUCKET_REQUIRED");
            Calculation value = from(o.value(),key);
            if (!isNumeric(value)) return new PeriodPoint(key,target,propagate(value));
            anyEstimated |= value.status() == Status.ESTIMATED;
            long days = ChronoUnit.DAYS.between(o.time().startInclusive(),o.time().endExclusive());
            total = total.add(aggregation == Aggregation.DAY_WEIGHTED_LEVEL_MEAN
                    ? value.value().multiply(BigDecimal.valueOf(days)) : value.value());
            count += aggregation == Aggregation.DAY_WEIGHTED_LEVEL_MEAN ? days : 1;
            cursor = o.time().endExclusive();
        }

        if (!cursor.equals(target.endExclusive()))
            return new PeriodPoint(key,target,blocked(Status.INCOMPLETE,"MISSING_PERIODS"));

        BigDecimal computed = switch (aggregation) {
            case FLOW_SUM -> total;
            case MONTHLY_FLOW_MEAN, DAY_WEIGHTED_LEVEL_MEAN ->
                    quotient(total,BigDecimal.valueOf(count));
            case STOCK_CLOSING -> throw new IllegalStateException("handled above");
        };
        return new PeriodPoint(key,target,anyEstimated ? estimated(computed) : ok(computed));
    }

    public static Comparison compare(PeriodPoint previous, PeriodPoint current) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(current);
        if (!previous.series().equals(current.series()))
            throw new IllegalArgumentException("COMPARISON_CONTEXT_MISMATCH");
        requireInterval(previous.period());
        requireInterval(current.period());
        if (previous.period().endExclusive().isAfter(current.period().startInclusive()))
            throw new IllegalArgumentException("COMPARISON_PERIODS_OVERLAP_OR_REVERSE");
        Calculation a=previous.calculation(), b=current.calculation();
        if (!isNumeric(a)) {
            Calculation blocked=propagate(a);
            return new Comparison(blocked,blocked);
        }
        if (!isNumeric(b)) {
            Calculation blocked=propagate(b);
            return new Comparison(blocked,blocked);
        }
        BigDecimal delta = b.value().subtract(a.value());
        Calculation change = a.status() == Status.ESTIMATED || b.status() == Status.ESTIMATED
                ? estimated(delta) : ok(delta);
        Calculation percent;
        if (a.value().signum() <= 0) {
            percent=blocked(Status.NOT_COMPUTABLE,
                    a.value().signum()==0 ? "ZERO_BASELINE" : "NEGATIVE_BASELINE_PERCENT_UNDEFINED");
        } else {
            BigDecimal pct=quotient(delta.multiply(BigDecimal.valueOf(100)),a.value());
            percent=change.status()==Status.ESTIMATED ? estimated(pct) : ok(pct);
        }
        return new Comparison(change,percent);
    }

    public static Calculation percentOf(PeriodPoint numerator, PeriodPoint denominator) {
        Objects.requireNonNull(numerator);
        Objects.requireNonNull(denominator);
        if (!numerator.series().comparableUnit(denominator.series())
                || !numerator.period().equals(denominator.period()))
            throw new IllegalArgumentException("RATIO_CONTEXT_OR_UNIT_MISMATCH");
        Calculation a=numerator.calculation(), b=denominator.calculation();
        if (!isNumeric(a)) return propagate(a);
        if (!isNumeric(b)) return propagate(b);
        if (b.value().signum() <= 0)
            return blocked(Status.NOT_COMPUTABLE,
                    b.value().signum()==0 ? "ZERO_DENOMINATOR" : "NEGATIVE_DENOMINATOR");
        BigDecimal v=quotient(a.value().multiply(BigDecimal.valueOf(100)),b.value());
        return a.status()==Status.ESTIMATED || b.status()==Status.ESTIMATED ? estimated(v) : ok(v);
    }

    /**
     * Percentage shares of one metric grouped by a single dimension.
     * Requires the COMPLETE expected population, never normalizes a partial subset
     * to 100%, and never combines pre-aggregated TOTAL rows with details.
     */
    public static SortedMap<String,Calculation> shareByDimension(
            String dimension, Set<String> expectedMembers, Map<String,PeriodPoint> groups) {
        if (dimension==null || dimension.isBlank() || expectedMembers==null
                || expectedMembers.isEmpty() || groups==null)
            throw new IllegalArgumentException("SHARE_DEFINITION_REQUIRED");
        SortedSet<String> sorted=new TreeSet<>(expectedMembers);
        if(sorted.stream().anyMatch(s->s==null||s.isBlank()))
            throw new IllegalArgumentException("INVALID_SHARE_MEMBER");
        if(!sorted.containsAll(groups.keySet()))
            throw new IllegalArgumentException("UNEXPECTED_SHARE_MEMBER");
        PeriodPoint reference=null;
        BigDecimal sum=BigDecimal.ZERO;
        boolean estimate=false;
        Calculation problem=null;
        for (var entry : groups.entrySet()) {
            PeriodPoint point=Objects.requireNonNull(entry.getValue());
            if(!entry.getKey().equals(point.series().dimensions().get(dimension)))
                throw new IllegalArgumentException("GROUP_DIMENSION_MISMATCH");
            if(reference==null) reference=point;
            else {
                if(!point.period().equals(reference.period()))
                    throw new IllegalArgumentException("GROUP_PERIOD_MISMATCH");
                SeriesKey s=point.series(), r=reference.series();
                if(!s.organizationId().equals(r.organizationId())
                    || !s.economicEntityId().equals(r.economicEntityId())
                    || !s.businessScopeId().equals(r.businessScopeId())
                    || !s.factSetRevisionId().equals(r.factSetRevisionId())
                    || !s.metricCode().equals(r.metricCode())
                    || !s.metricVersion().equals(r.metricVersion())
                    || s.measureKind()!=r.measureKind()
                    || !s.unitCode().equals(r.unitCode())
                    || !Objects.equals(s.currencyCode(),r.currencyCode())
                    || s.reportingBasis()!=r.reportingBasis() || s.dataSpace()!=r.dataSpace())
                    throw new IllegalArgumentException("MIXED_SHARE_CONTEXT");
                SortedMap<String,String> left=new TreeMap<>(s.dimensions());
                SortedMap<String,String> right=new TreeMap<>(r.dimensions());
                left.remove(dimension); right.remove(dimension);
                if(!left.equals(right)) throw new IllegalArgumentException("MIXED_SHARE_DIMENSIONS");
            }
            Calculation calc=point.calculation();
            if(!isNumeric(calc)) problem=calc;
            else if(calc.value().signum()<0) problem=blocked(Status.NOT_COMPUTABLE,"NEGATIVE_SHARE_COMPONENT");
            else {
                sum=sum.add(calc.value());
                estimate |= calc.status()==Status.ESTIMATED;
            }
        }
        if(groups.size()!=expectedMembers.size())
            problem=blocked(Status.INCOMPLETE,"MISSING_SHARE_MEMBERS");
        if(problem==null && sum.signum()==0)
            problem=blocked(Status.NOT_COMPUTABLE,"ZERO_SHARE_TOTAL");
        SortedMap<String,Calculation> result=new TreeMap<>();
        for(String member:sorted) {
            if(problem!=null) result.put(member,propagate(problem));
            else {
                BigDecimal pct=quotient(groups.get(member).calculation().value().multiply(
                        BigDecimal.valueOf(100)),sum);
                result.put(member,estimate ? estimated(pct) : ok(pct));
            }
        }
        return Collections.unmodifiableSortedMap(result);
    }

    /**
     * Calendar-month moving average of FLOW_SUM or STOCK_CLOSING points.
     * Requires each window's full N monthly observations (not a partial average).
     */
    public static SortedMap<YearMonth,Calculation> movingAverage(
            SeriesKey key, Collection<Observation> observations,
            YearMonth from, YearMonth through, int windowMonths, Aggregation perMonth) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(observations);
        Objects.requireNonNull(from);
        Objects.requireNonNull(through);
        if(from.isAfter(through)||windowMonths<1||windowMonths>120)
            throw new IllegalArgumentException("INVALID_MOVING_WINDOW");
        if(perMonth != Aggregation.FLOW_SUM && perMonth != Aggregation.STOCK_CLOSING)
            throw new IllegalArgumentException("MOVING_AVERAGE_ONLY_ON_RAW_FLOW_OR_STOCK");
        if (perMonth==Aggregation.FLOW_SUM && key.measureKind()!=MeasureKind.FLOW
                || perMonth==Aggregation.STOCK_CLOSING && key.measureKind()!=MeasureKind.STOCK)
            throw new IllegalArgumentException("MOVING_MEASURE_KIND_MISMATCH");
        for(Observation o:observations) {
            if(!key.equals(o.series())) throw new IllegalArgumentException("MIXED_SERIES_CONTEXT");
        }
        SortedMap<YearMonth,Calculation> output=new TreeMap<>();
        for(YearMonth anchor=from; !anchor.isAfter(through); anchor=anchor.plusMonths(1)) {
            BigDecimal sum=BigDecimal.ZERO;
            boolean estimated=false;
            Calculation blocked=null;
            for(int lag=windowMonths-1;lag>=0;lag--) {
                YearMonth ym=anchor.minusMonths(lag);
                EconomicKernel.TemporalReference period=EconomicKernel.TemporalReference.interval(
                        ym.atDay(1),ym.plusMonths(1).atDay(1));
                List<Observation> month=new ArrayList<>();
                for(Observation o:observations) {
                    if(perMonth==Aggregation.FLOW_SUM && o.time().kind()==EconomicKernel.TimeKind.INTERVAL
                            && !o.time().endExclusive().isBefore(period.startInclusive())
                            && o.time().startInclusive().isBefore(period.endExclusive())) {
                        if(o.time().endExclusive().isAfter(period.startInclusive()))
                            month.add(o);
                    }
                    if(perMonth==Aggregation.STOCK_CLOSING && o.time().kind()==EconomicKernel.TimeKind.AS_OF
                            && o.time().startInclusive().equals(period.endExclusive().minusDays(1)))
                        month.add(o);
                }
                PeriodPoint point=aggregate(key,period,month,perMonth);
                Calculation c=point.calculation();
                if(!isNumeric(c)) {blocked=propagate(c); break;}
                sum=sum.add(c.value());
                estimated |= c.status()==Status.ESTIMATED;
            }
            Calculation result=blocked!=null ? blocked :
                    estimated ? estimated(quotient(sum,BigDecimal.valueOf(windowMonths))) :
                            ok(quotient(sum,BigDecimal.valueOf(windowMonths)));
            output.put(anchor,result);
        }
        return Collections.unmodifiableSortedMap(output);
    }
}
