package kz.fin.backend.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class PeriodAggregation {
    private PeriodAggregation() {}

    public record Observation(EconomicKernel.TemporalReference time, EconomicKernel.EconomicValue value) {}
    public enum Status { CALCULATED, INCOMPLETE }
    public record Aggregate(Status status, BigDecimal value, String reason) {}

    // FLOW aggregation requires a complete, non-overlapping, gap-free cover of the target window.
    public static Aggregate flowSum(EconomicKernel.TemporalReference target, Collection<Observation> observations) {
        if (target.kind() != EconomicKernel.TimeKind.INTERVAL) throw new IllegalArgumentException("flow target");
        List<Observation> ordered = new ArrayList<>(observations);
        ordered.sort(Comparator.comparing(x -> x.time().startInclusive()));
        LocalDate cursor = target.startInclusive();
        BigDecimal sum = BigDecimal.ZERO;
        for (Observation o : ordered) {
            if (o.time().kind() != EconomicKernel.TimeKind.INTERVAL) {
                throw new IllegalArgumentException("AS_OF cannot be summed");
            }
            if (o.time().startInclusive().isBefore(target.startInclusive())
                    || o.time().endExclusive().isAfter(target.endExclusive())) {
                throw new IllegalArgumentException("out-of-window observation");
            }
            if (o.time().startInclusive().isBefore(cursor)) {
                throw new IllegalArgumentException("overlapping observation");
            }
            if (o.time().startInclusive().isAfter(cursor)) {
                return new Aggregate(Status.INCOMPLETE, null, "MISSING_PERIODS");
            }
            cursor = o.time().endExclusive();
            if (!o.value().numeric() || o.value().state() == EconomicKernel.ValueState.ESTIMATED) {
                return new Aggregate(Status.INCOMPLETE, null, "MISSING_OR_ESTIMATED_VALUE");
            }
            sum = sum.add(o.value().decimal());
        }
        return cursor.equals(target.endExclusive())
                ? new Aggregate(Status.CALCULATED, sum, null)
                : new Aggregate(Status.INCOMPLETE, null, "MISSING_PERIODS");
    }

    // A stock for the requested window is an AS_OF value exactly on its closing date.
    public static Aggregate closingStock(EconomicKernel.TemporalReference target, Collection<Observation> observations) {
        if (target.kind() != EconomicKernel.TimeKind.INTERVAL) throw new IllegalArgumentException("window expected");
        LocalDate required = target.endExclusive().minusDays(1);
        List<Observation> matching = observations.stream()
                .filter(o -> o.time().kind() == EconomicKernel.TimeKind.AS_OF
                        && o.time().startInclusive().equals(required)).toList();
        if (matching.size() > 1) throw new IllegalArgumentException("ambiguous stock closing date");
        if (matching.isEmpty() || !matching.getFirst().value().numeric()) {
            return new Aggregate(Status.INCOMPLETE, null, "MISSING_CLOSING_STOCK");
        }
        if (matching.getFirst().value().state() == EconomicKernel.ValueState.ESTIMATED) {
            return new Aggregate(Status.INCOMPLETE, null, "ESTIMATED_CLOSING_STOCK");
        }
        return new Aggregate(Status.CALCULATED, matching.getFirst().value().decimal(), null);
    }
}
