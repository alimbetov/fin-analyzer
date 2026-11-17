package kz.fin.backend.intake;

import kz.fin.backend.domain.EconomicKernel;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * W1.2.A strict ISO-calendar pilot: no implicit fiscal 4-4-5, YTD/LTD,
 * AS_OF -> INTERVAL or previous-available-row coercion.
 *
 * A resolved interval is source geometry, not a financial execution permit.
 */
public final class HtapIsoFinancialCalendar {
    private HtapIsoFinancialCalendar() {}

    public record Pair(ReportingPeriodV5.Period prior,
                       ReportingPeriodV5.Period current,
                       int monthDistance) {
        public Pair {
            Objects.requireNonNull(prior);
            Objects.requireNonNull(current);
            if (monthDistance != 1)
                throw new IllegalArgumentException("FIN_HTAP_MONTHLY_GAP_OR_DUPLICATE");
        }
    }

    public static ReportingPeriodV5.Period requireMonthlyFlow(
            ReportingPeriodV5.Type type, String code,
            ReportingPeriodV5.Coverage coverage, EconomicKernel.TimeKind timeKind) {
        var decision = HtapCalculationPeriodPolicy.classify(type, code, coverage, timeKind);
        if (!decision.eligibleCandidate())
            throw new IllegalArgumentException(decision.reasonCode());
        var period = decision.source();
        if (!"ISO_CALENDAR".equals(period.calendarId()) ||
                !"1".equals(period.calendarVersion()))
            throw new IllegalArgumentException("FIN_HTAP_ISO_CALENDAR_VERSION_UNSUPPORTED");
        return period;
    }

    public static Pair requireConsecutiveMonthlyFlow(
            ReportingPeriodV5.Period prior, ReportingPeriodV5.Period current) {
        Objects.requireNonNull(prior);
        Objects.requireNonNull(current);
        validateMonth(prior);
        validateMonth(current);
        if (!prior.calendarId().equals(current.calendarId()) ||
                !prior.calendarVersion().equals(current.calendarVersion()))
            throw new IllegalArgumentException("FIN_HTAP_CALENDAR_VERSION_CONFLICT");
        long gap = ChronoUnit.MONTHS.between(
                YearMonth.from(prior.startInclusive()), YearMonth.from(current.startInclusive()));
        if (gap != 1 || !prior.endExclusive().equals(current.startInclusive()))
            throw new IllegalArgumentException("FIN_HTAP_MONTHLY_GAP_OR_DUPLICATE");
        return new Pair(prior, current, 1);
    }

    public static void validateMonth(ReportingPeriodV5.Period p) {
        if (p.type() != ReportingPeriodV5.Type.MONTH
                || p.coverage() != ReportingPeriodV5.Coverage.PERIOD_ONLY
                || !"ISO_CALENDAR".equals(p.calendarId())
                || !"1".equals(p.calendarVersion())
                || p.startInclusive() == null || p.endExclusive() == null
                || p.startInclusive().getDayOfMonth() != 1
                || !p.endExclusive().equals(p.startInclusive().plusMonths(1)))
            throw new IllegalArgumentException("FIN_HTAP_MONTHLY_INTERVAL_NOT_QUALIFIED");
    }

    /** Prevents mixing a true point-in-time observation with an interval. */
    public static LocalDate requireAsOfDate(ReportingPeriodV5.Period period) {
        Objects.requireNonNull(period);
        if (period.type() != ReportingPeriodV5.Type.AS_OF
                || period.coverage() != ReportingPeriodV5.Coverage.AS_OF
                || period.asOfDate() == null)
            throw new IllegalArgumentException("FIN_HTAP_AS_OF_SOURCE_REQUIRED");
        return period.asOfDate();
    }
}
