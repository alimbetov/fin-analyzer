package kz.fin.backend.intake;

import kz.fin.backend.domain.EconomicKernel;

import java.util.Objects;

/**
 * FIN HTAP W1.2.A: fail-closed adapter from existing catalog source periods
 * to the FIRST explicitly supported calculation grain.
 *
 * This is a candidate-input classification only. It does NOT authorize
 * FactSet publication, method execution, SQL batches, or ACTUAL results.
 * Every source period/coverage combination must remain representable and
 * unsupported combinations must never become monthly FLOW implicitly.
 */
public final class HtapCalculationPeriodPolicy {
    public enum Status {
        ELIGIBLE_MONTHLY_INTERVAL_CANDIDATE,
        BLOCKED_UNSUPPORTED_PERIOD_POLICY,
        BLOCKED_TIME_KIND_MISMATCH
    }

    public record Decision(
            ReportingPeriodV5.Period source,
            EconomicKernel.TimeKind timeKind,
            Status status,
            String reasonCode) {
        public Decision {
            Objects.requireNonNull(source);
            Objects.requireNonNull(timeKind);
            Objects.requireNonNull(status);
            Objects.requireNonNull(reasonCode);
        }

        public boolean eligibleCandidate() {
            return status == Status.ELIGIBLE_MONTHLY_INTERVAL_CANDIDATE;
        }
    }

    private HtapCalculationPeriodPolicy() {}

    /**
     * Resolves the source period using the existing trusted ISO calendar,
     * preserving type, coverage and boundaries. Future financial policies
     * must be separately versioned and reviewed before enabling them.
     */
    public static Decision classify(ReportingPeriodV5.Type type, String periodCode,
                                    ReportingPeriodV5.Coverage coverage,
                                    EconomicKernel.TimeKind timeKind) {
        Objects.requireNonNull(timeKind, "timeKind");
        var resolved = ReportingPeriodV5.resolve(type, periodCode, coverage);

        // An AS_OF source point is a dated stock observation, not an
        // INTERVAL. Monthly/quarterly AS_OF *closing* observations can be
        // legitimate in future policies, but are not this FLOW pilot.
        if (type == ReportingPeriodV5.Type.AS_OF
                && timeKind != EconomicKernel.TimeKind.AS_OF) {
            return new Decision(resolved, timeKind, Status.BLOCKED_TIME_KIND_MISMATCH,
                    "FIN_HTAP_SOURCE_TIME_KIND_MISMATCH");
        }

        // Initial pilot only: complete calendar month / PERIOD_ONLY / FLOW.
        // ReportingPeriodV5 already resolves half-open boundaries and ISO dates.
        if (type == ReportingPeriodV5.Type.MONTH
                && coverage == ReportingPeriodV5.Coverage.PERIOD_ONLY
                && timeKind == EconomicKernel.TimeKind.INTERVAL) {
            return new Decision(resolved, timeKind,
                    Status.ELIGIBLE_MONTHLY_INTERVAL_CANDIDATE,
                    "FIN_HTAP_MONTHLY_FLOW_CANDIDATE_ONLY");
        }

        // WEEK, QUARTER, HALF_YEAR, YEAR, AS_OF, YTD and LTD remain intact
        // source data. This gate does NOT silently resample or convert them.
        return new Decision(resolved, timeKind,
                Status.BLOCKED_UNSUPPORTED_PERIOD_POLICY,
                "BLOCKED_UNSUPPORTED_PERIOD_POLICY");
    }
}
