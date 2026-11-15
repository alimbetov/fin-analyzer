package kz.fin.backend.analytics;

import kz.fin.backend.domain.EconomicKernel;
import java.time.YearMonth;
import java.util.*;
import static kz.fin.backend.analytics.AutoIndicatorEngine.*;

/**
 * Deterministic server-side projection of auto-calculated report columns.
 *
 * The caller MUST supply only authorized, server-resolved and approved facts.
 * This calculator never reads client-supplied formula source or executes SQL,
 * Java, scripts, reflection, or user expressions. No React/frontend dependency.
 */
public final class AutoReportProjection {
    private AutoReportProjection() {}

    public enum ComparisonMode { ABSOLUTE, PERCENT }

    public sealed interface Operation permits Aggregate, Compare, MovingAverage, PercentOf, ShareOfTotal {}

    public record Aggregate(SeriesKey key, EconomicKernel.TemporalReference period,
                            Aggregation method) implements Operation {}

    public record Compare(SeriesKey key, EconomicKernel.TemporalReference previous,
                          EconomicKernel.TemporalReference current, Aggregation method,
                          ComparisonMode mode) implements Operation {}

    public record MovingAverage(SeriesKey key, YearMonth endingMonth, int windowMonths,
                                Aggregation perMonth) implements Operation {}

    public record PercentOf(SeriesKey numerator, SeriesKey denominator,
                            EconomicKernel.TemporalReference period, Aggregation method)
            implements Operation {}

    public record ShareOfTotal(SeriesKey member, String dimension, Set<String> allMembers,
                               EconomicKernel.TemporalReference period, Aggregation method)
            implements Operation {
        public ShareOfTotal {
            if (allMembers == null || allMembers.isEmpty())
                throw new IllegalArgumentException("SHARE_POPULATION_REQUIRED");
            allMembers = Set.copyOf(allMembers);
        }
    }

    public record Column(String columnCode, int displayOrder, String translationKey,
                         Operation operation) {
        public Column {
            if (columnCode == null || !columnCode.matches("[A-Z][A-Z0-9_]{1,127}"))
                throw new IllegalArgumentException("INVALID_COLUMN_CODE");
            if (displayOrder < 1) throw new IllegalArgumentException("INVALID_DISPLAY_ORDER");
            if (translationKey == null || translationKey.isBlank())
                throw new IllegalArgumentException("TRANSLATION_KEY_REQUIRED");
            Objects.requireNonNull(operation);
        }
    }

    public record ReportDefinition(String reportCode, String version, List<Column> columns) {
        public ReportDefinition {
            if(reportCode == null || !reportCode.matches("[A-Z][A-Z0-9_]{1,127}")
                    || version == null || version.isBlank())
                throw new IllegalArgumentException("INVALID_REPORT_DEFINITION");
            List<Column> sorted=new ArrayList<>(Objects.requireNonNull(columns));
            if(sorted.isEmpty()) throw new IllegalArgumentException("REPORT_NEEDS_COLUMNS");
            sorted.sort(Comparator.comparingInt(Column::displayOrder).thenComparing(Column::columnCode));
            Set<Integer> orders=new HashSet<>();
            Set<String> names=new HashSet<>();
            SeriesKey anchor=null;
            for (Column c:sorted) {
                SeriesKey current=contextKey(c.operation());
                if(anchor==null) anchor=current;
                else if(!sameReportScope(anchor,current))
                    throw new IllegalArgumentException("REPORT_CONTEXT_MISMATCH");
                if(!orders.add(c.displayOrder()) || !names.add(c.columnCode()))
                    throw new IllegalArgumentException("DUPLICATE_COLUMN_CODE_OR_ORDER");
            }
            columns=List.copyOf(sorted);
        }
    }

    private static SeriesKey contextKey(Operation op) {
        if(op instanceof Aggregate a) return Objects.requireNonNull(a.key());
        if(op instanceof Compare a) return Objects.requireNonNull(a.key());
        if(op instanceof MovingAverage a) return Objects.requireNonNull(a.key());
        if(op instanceof PercentOf a) return Objects.requireNonNull(a.numerator());
        if(op instanceof ShareOfTotal a) return Objects.requireNonNull(a.member());
        throw new IllegalArgumentException("UNKNOWN_OPERATION");
    }

    private static boolean sameReportScope(SeriesKey left,SeriesKey right) {
        return left.organizationId().equals(right.organizationId())
                && left.economicEntityId().equals(right.economicEntityId())
                && left.businessScopeId().equals(right.businessScopeId())
                && left.factSetRevisionId().equals(right.factSetRevisionId())
                && left.reportingBasis()==right.reportingBasis()
                && left.dataSpace()==right.dataSpace();
    }

    public record Cell(String columnCode, int displayOrder, String translationKey,
                       String unitCode, String currencyCode, Calculation calculation) {}

    public record Projection(String reportCode, String reportVersion, List<Cell> cells) {
        public Projection { cells = List.copyOf(cells); }
    }

    public static Projection project(ReportDefinition definition,
                                     Map<SeriesKey,List<Observation>> authorizedInputs) {
        Objects.requireNonNull(definition);
        Objects.requireNonNull(authorizedInputs);
        Map<SeriesKey,List<Observation>> inputs=new HashMap<>();
        for(var e:authorizedInputs.entrySet())
            inputs.put(Objects.requireNonNull(e.getKey()),
                    List.copyOf(Objects.requireNonNull(e.getValue())));
        List<Cell> cells=new ArrayList<>();
        for(Column c:definition.columns()) {
            Calculation value;
            String unit;
            String currency;
            Operation op=c.operation();
            if(op instanceof Aggregate a) {
                value=calculate(inputs,a.key(),a.period(),a.method()).calculation();
                unit=a.key().unitCode();
                currency=a.key().currencyCode();
            } else if(op instanceof Compare a) {
                PeriodPoint prior=calculate(inputs,a.key(),a.previous(),a.method());
                PeriodPoint current=calculate(inputs,a.key(),a.current(),a.method());
                Comparison difference=AutoIndicatorEngine.compare(prior,current);
                value=a.mode()==ComparisonMode.ABSOLUTE ? difference.absoluteChange() : difference.percentChange();
                unit=a.mode()==ComparisonMode.ABSOLUTE?a.key().unitCode():"PCT";
                currency=a.mode()==ComparisonMode.ABSOLUTE?a.key().currencyCode():null;
            } else if(op instanceof MovingAverage a) {
                value=AutoIndicatorEngine.movingAverage(a.key(),source(inputs,a.key()),
                        a.endingMonth(),a.endingMonth(),a.windowMonths(),a.perMonth())
                        .get(a.endingMonth());
                unit=a.key().unitCode();
                currency=a.key().currencyCode();
            } else if(op instanceof PercentOf a) {
                value=AutoIndicatorEngine.percentOf(
                        calculate(inputs,a.numerator(),a.period(),a.method()),
                        calculate(inputs,a.denominator(),a.period(),a.method()));
                unit="PCT";currency=null;
            } else if(op instanceof ShareOfTotal a) {
                if(!a.allMembers().contains(a.member().dimensions().get(a.dimension())))
                    throw new IllegalArgumentException("SHARE_MEMBER_NOT_IN_POPULATION");
                Map<String,PeriodPoint> groups=new HashMap<>();
                for(String member:a.allMembers()) {
                    SortedMap<String,String> dims=new TreeMap<>(a.member().dimensions());
                    dims.put(a.dimension(),member);
                    SeriesKey k=new SeriesKey(a.member().organizationId(),a.member().economicEntityId(),
                            a.member().businessScopeId(),a.member().factSetRevisionId(),
                            a.member().metricCode(),a.member().metricVersion(),a.member().measureKind(),
                            a.member().unitCode(),a.member().currencyCode(),a.member().reportingBasis(),
                            a.member().dataSpace(),dims);
                    groups.put(member,calculate(inputs,k,a.period(),a.method()));
                }
                value=AutoIndicatorEngine.shareByDimension(a.dimension(),a.allMembers(),groups)
                        .get(a.member().dimensions().get(a.dimension()));
                unit="PCT";currency=null;
            } else throw new IllegalArgumentException("UNSUPPORTED_COLUMN_OPERATION");
            cells.add(new Cell(c.columnCode(),c.displayOrder(),c.translationKey(),
                    unit,currency,value));
        }
        return new Projection(definition.reportCode(),definition.version(),cells);
    }

    private static List<Observation> source(Map<SeriesKey,List<Observation>> inputs,SeriesKey key) {
        return inputs.getOrDefault(Objects.requireNonNull(key),List.of());
    }

    private static PeriodPoint calculate(Map<SeriesKey,List<Observation>> inputs,SeriesKey key,
                                         EconomicKernel.TemporalReference period,Aggregation method) {
        return AutoIndicatorEngine.aggregate(key,period,source(inputs,key),method);
    }
}
