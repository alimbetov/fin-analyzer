package kz.fin.backend.intake;

import kz.fin.backend.domain.EconomicKernel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** W0/W1.2.A negative and golden time/identity geometry. */
class HtapFinancialSliceKeyCalendarTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    private HtapFinancialSliceKey key(String currency, String revisionHash,
                                      String mappingVersion,
                                      ReportingPeriodV5.Type type,
                                      ReportingPeriodV5.Coverage coverage,
                                      String period, EconomicKernel.TimeKind kind) {
        return new HtapFinancialSliceKey(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                revisionHash,
                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                "F01", "F01-V1", "SYNTHETIC", "SEPARATE",
                "REV_NET", "V1", kind, type, coverage, period,
                "ISO_CALENDAR", "1", "MONEY", currency, SHA_A,
                mappingVersion, SHA_B);
    }

    @Test void logicalKeyIncludesMappingAndSourceAndCurrencyWithoutPhysicalPartition() {
        var a=key("KZT", SHA_A, "M1", ReportingPeriodV5.Type.MONTH,
                ReportingPeriodV5.Coverage.PERIOD_ONLY, "2024.02",
                EconomicKernel.TimeKind.INTERVAL);
        assertEquals(LocalDate.of(2024,2,1),a.startInclusive());
        assertEquals(LocalDate.of(2024,3,1),a.endExclusive()); // leap February
        assertNotEquals(a,key("USD",SHA_A,"M1",ReportingPeriodV5.Type.MONTH,
                ReportingPeriodV5.Coverage.PERIOD_ONLY,"2024.02",
                EconomicKernel.TimeKind.INTERVAL));
        assertNotEquals(a,key("KZT",SHA_B,"M1",ReportingPeriodV5.Type.MONTH,
                ReportingPeriodV5.Coverage.PERIOD_ONLY,"2024.02",
                EconomicKernel.TimeKind.INTERVAL));
        assertNotEquals(a,key("KZT",SHA_A,"M2",ReportingPeriodV5.Type.MONTH,
                ReportingPeriodV5.Coverage.PERIOD_ONLY,"2024.02",
                EconomicKernel.TimeKind.INTERVAL));
    }

    @Test void noGapCoercionAndNoYtdLtdOrStockToFlow() {
        var jan=HtapIsoFinancialCalendar.requireMonthlyFlow(
                ReportingPeriodV5.Type.MONTH,"2026.01",
                ReportingPeriodV5.Coverage.PERIOD_ONLY,EconomicKernel.TimeKind.INTERVAL);
        var feb=HtapIsoFinancialCalendar.requireMonthlyFlow(
                ReportingPeriodV5.Type.MONTH,"2026.02",
                ReportingPeriodV5.Coverage.PERIOD_ONLY,EconomicKernel.TimeKind.INTERVAL);
        var mar=HtapIsoFinancialCalendar.requireMonthlyFlow(
                ReportingPeriodV5.Type.MONTH,"2026.03",
                ReportingPeriodV5.Coverage.PERIOD_ONLY,EconomicKernel.TimeKind.INTERVAL);
        assertEquals(1,HtapIsoFinancialCalendar.requireConsecutiveMonthlyFlow(jan,feb).monthDistance());
        assertThrows(IllegalArgumentException.class,
                ()->HtapIsoFinancialCalendar.requireConsecutiveMonthlyFlow(jan,mar));
        assertThrows(IllegalArgumentException.class,
                ()->HtapIsoFinancialCalendar.requireConsecutiveMonthlyFlow(jan,jan));
        for(var coverage:new ReportingPeriodV5.Coverage[]{
                ReportingPeriodV5.Coverage.YEAR_TO_DATE,
                ReportingPeriodV5.Coverage.LIFE_TO_DATE}) {
            assertThrows(IllegalArgumentException.class,
                    ()->HtapIsoFinancialCalendar.requireMonthlyFlow(
                            ReportingPeriodV5.Type.MONTH,"2026.02",coverage,
                            EconomicKernel.TimeKind.INTERVAL));
        }
        assertThrows(IllegalArgumentException.class,
                ()->HtapIsoFinancialCalendar.requireMonthlyFlow(
                        ReportingPeriodV5.Type.MONTH,"2026.02",
                        ReportingPeriodV5.Coverage.PERIOD_ONLY,EconomicKernel.TimeKind.AS_OF));
        assertThrows(IllegalArgumentException.class,
                ()->key("KZT",SHA_A,"M1",ReportingPeriodV5.Type.MONTH,
                        ReportingPeriodV5.Coverage.PERIOD_ONLY,"2026.02",
                        EconomicKernel.TimeKind.AS_OF));
    }

    @Test void asOfRemainsDatedPointAndIsoWeekIsNotMonthlyPilot() {
        var date=ReportingPeriodV5.resolve(
                ReportingPeriodV5.Type.AS_OF,"2026-12-31",ReportingPeriodV5.Coverage.AS_OF);
        assertEquals(LocalDate.of(2026,12,31),
                HtapIsoFinancialCalendar.requireAsOfDate(date));
        assertThrows(IllegalArgumentException.class,
                ()->HtapIsoFinancialCalendar.requireMonthlyFlow(
                        ReportingPeriodV5.Type.AS_OF,"2026-12-31",
                        ReportingPeriodV5.Coverage.AS_OF,EconomicKernel.TimeKind.AS_OF));
        assertThrows(IllegalArgumentException.class,
                ()->HtapIsoFinancialCalendar.requireMonthlyFlow(
                        ReportingPeriodV5.Type.WEEK,"2020-W53",
                        ReportingPeriodV5.Coverage.PERIOD_ONLY,EconomicKernel.TimeKind.INTERVAL));
    }

    @Test void rejectsInvalidHashAndCurrencyWithoutInferringDefaults() {
        assertThrows(IllegalArgumentException.class,
                ()->key("KZT","not-a-sha","M1",ReportingPeriodV5.Type.MONTH,
                        ReportingPeriodV5.Coverage.PERIOD_ONLY,"2026.01",
                        EconomicKernel.TimeKind.INTERVAL));
        assertThrows(IllegalArgumentException.class,
                ()->key("kzt",SHA_A,"M1",ReportingPeriodV5.Type.MONTH,
                        ReportingPeriodV5.Coverage.PERIOD_ONLY,"2026.01",
                        EconomicKernel.TimeKind.INTERVAL));
        assertEquals("FIN_HTAP_CURRENCY_NOT_ISO4217",
                assertThrows(IllegalArgumentException.class,
                        ()->key("ZZZ",SHA_A,"M1",ReportingPeriodV5.Type.MONTH,
                                ReportingPeriodV5.Coverage.PERIOD_ONLY,"2026.01",
                                EconomicKernel.TimeKind.INTERVAL)).getMessage());
        assertThrows(IllegalArgumentException.class,
                ()->key(null,SHA_A,"M1",ReportingPeriodV5.Type.MONTH,
                        ReportingPeriodV5.Coverage.PERIOD_ONLY,"2026.01",
                        EconomicKernel.TimeKind.INTERVAL));
    }
}
