package kz.fin.backend.intake;

import kz.fin.backend.domain.EconomicKernel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W1.2.A executable gate: source periods are not calculation periods.
 * These tests are intentionally independent of MethodExecution/ACTUAL permits.
 */
class HtapCalculationPeriodPolicyTest {
    private static final Map<ReportingPeriodV5.Type, String> CODES =
            Map.of(ReportingPeriodV5.Type.WEEK, "2026-W01",
                    ReportingPeriodV5.Type.MONTH, "2026.01",
                    ReportingPeriodV5.Type.QUARTER, "2026-Q1",
                    ReportingPeriodV5.Type.HALF_YEAR, "2026-H1",
                    ReportingPeriodV5.Type.YEAR, "2026",
                    ReportingPeriodV5.Type.AS_OF, "2026-01-31");

    @Test
    void onlyCompleteMonthlyIntervalPeriodOnlyIsAnEligibleCalculationCandidate() {
        var eligible = HtapCalculationPeriodPolicy.classify(
                ReportingPeriodV5.Type.MONTH, "2026.02",
                ReportingPeriodV5.Coverage.PERIOD_ONLY,
                EconomicKernel.TimeKind.INTERVAL);

        assertTrue(eligible.eligibleCandidate());
        assertEquals(HtapCalculationPeriodPolicy.Status.ELIGIBLE_MONTHLY_INTERVAL_CANDIDATE,
                eligible.status());
        assertEquals(LocalDate.of(2026, 2, 1), eligible.source().startInclusive());
        assertEquals(LocalDate.of(2026, 3, 1), eligible.source().endExclusive());
        assertEquals("ISO_CALENDAR", eligible.source().calendarId());
        assertEquals("1", eligible.source().calendarVersion());
        assertEquals("FIN_HTAP_MONTHLY_FLOW_CANDIDATE_ONLY", eligible.reasonCode());
    }

    @Test
    void allExistingPeriodTypesAndCoveragesAreLosslesslyClassifiedOrRejectedAsInvalid() {
        int candidates = 0;
        int unsupported = 0;
        int invalid = 0;

        for (var type : ReportingPeriodV5.Type.values()) {
            for (var coverage : ReportingPeriodV5.Coverage.values()) {
                boolean valid = (type == ReportingPeriodV5.Type.AS_OF)
                        == (coverage == ReportingPeriodV5.Coverage.AS_OF);
                if (!valid) {
                    assertThrows(IllegalArgumentException.class,
                            () -> HtapCalculationPeriodPolicy.classify(type, CODES.get(type),
                                    coverage, EconomicKernel.TimeKind.INTERVAL));
                    invalid++;
                    continue;
                }
                var timeKind = type == ReportingPeriodV5.Type.AS_OF
                        ? EconomicKernel.TimeKind.AS_OF
                        : EconomicKernel.TimeKind.INTERVAL;
                var result = HtapCalculationPeriodPolicy.classify(type, CODES.get(type),
                        coverage, timeKind);
                assertEquals(type, result.source().type());
                assertEquals(coverage, result.source().coverage());
                assertEquals(CODES.get(type), result.source().code());
                if (result.eligibleCandidate()) {
                    assertEquals(ReportingPeriodV5.Type.MONTH, type);
                    assertEquals(ReportingPeriodV5.Coverage.PERIOD_ONLY, coverage);
                    candidates++;
                } else {
                    assertEquals("BLOCKED_UNSUPPORTED_PERIOD_POLICY", result.reasonCode());
                    unsupported++;
                }
            }
        }
        assertEquals(1, candidates);
        assertEquals(15, unsupported);
        assertEquals(8, invalid);
    }

    @Test
    void cumulativeYtdAndLtdCannotMasqueradeAsIndependentMonthlyFlows() {
        for (var coverage : new ReportingPeriodV5.Coverage[]{
                ReportingPeriodV5.Coverage.YEAR_TO_DATE,
                ReportingPeriodV5.Coverage.LIFE_TO_DATE}) {
            var result = HtapCalculationPeriodPolicy.classify(
                    ReportingPeriodV5.Type.MONTH, "2026.03", coverage,
                    EconomicKernel.TimeKind.INTERVAL);
            assertFalse(result.eligibleCandidate());
            assertEquals(HtapCalculationPeriodPolicy.Status.BLOCKED_UNSUPPORTED_PERIOD_POLICY,
                    result.status());
        }
    }

    @Test
    void asOfSourceRequiresAsOfTimeKindButMonthlyStockRemainsDistinctFromFlow() {
        var asOf = HtapCalculationPeriodPolicy.classify(
                ReportingPeriodV5.Type.AS_OF, "2026-03-31",
                ReportingPeriodV5.Coverage.AS_OF, EconomicKernel.TimeKind.AS_OF);
        assertEquals(LocalDate.of(2026, 3, 31), asOf.source().asOfDate());
        assertEquals(HtapCalculationPeriodPolicy.Status.BLOCKED_UNSUPPORTED_PERIOD_POLICY,
                asOf.status());

        var wrongKind = HtapCalculationPeriodPolicy.classify(
                ReportingPeriodV5.Type.AS_OF, "2026-03-31",
                ReportingPeriodV5.Coverage.AS_OF, EconomicKernel.TimeKind.INTERVAL);
        assertEquals(HtapCalculationPeriodPolicy.Status.BLOCKED_TIME_KIND_MISMATCH,
                wrongKind.status());

        // A MONTH/AS_OF stock closing can exist as a source, but it cannot
        // be fed to the monthly FLOW candidate. A separately reviewed
        // stock-closing policy is needed before such calculation.
        var monthClosing = HtapCalculationPeriodPolicy.classify(
                ReportingPeriodV5.Type.MONTH, "2026.03",
                ReportingPeriodV5.Coverage.PERIOD_ONLY, EconomicKernel.TimeKind.AS_OF);
        assertEquals(HtapCalculationPeriodPolicy.Status.BLOCKED_UNSUPPORTED_PERIOD_POLICY,
                monthClosing.status());
        assertFalse(monthClosing.eligibleCandidate());
    }

    @Test
    void malformedMonthOrImpossibleIsoWeekCanNeverEnterCalculation() {
        assertThrows(IllegalArgumentException.class,
                () -> HtapCalculationPeriodPolicy.classify(
                        ReportingPeriodV5.Type.MONTH, "2026.13",
                        ReportingPeriodV5.Coverage.PERIOD_ONLY,
                        EconomicKernel.TimeKind.INTERVAL));
        assertThrows(IllegalArgumentException.class,
                () -> HtapCalculationPeriodPolicy.classify(
                        ReportingPeriodV5.Type.WEEK, "2026-W53",
                        ReportingPeriodV5.Coverage.PERIOD_ONLY,
                        EconomicKernel.TimeKind.INTERVAL));
    }
}
