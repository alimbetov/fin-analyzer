package kz.fin.backend.intake;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FormSchemaV5Test {
    @Test void sharedRegistryHasStableFieldOrderAndLocaleBoundaries(){
        var f=FormSchemaV5.pilotF02();
        assertEquals("FIN-FORM-V2",f.version());
        assertEquals(List.of("netRevenue","cogs","operatingExpenses"),f.fieldCodes());
        assertEquals("Net revenue",f.fields().getFirst().labels().get("en"));
        assertEquals("Таза түсім",f.fields().getFirst().labels().get("kk"));
        assertThrows(IllegalArgumentException.class,()->FormSchemaV5.requireLocale("zh-Hans"));
    }
    @Test void exactDecimalsAndMissingDifferFromExplicitZero(){
        assertEquals("0",FormSchemaV5.canonicalDecimal("-0.000"));
        assertEquals("12345678901234567890.1234",
            FormSchemaV5.canonicalDecimal("12345678901234567890.123400"));
        var invalid=FormSchemaV5.normalizeF02(List.of(new FormSchemaV5.Input("pnl","netRevenue","0")));
        assertFalse(invalid.valid());
        assertEquals("0",invalid.cells().getFirst().decimalText());
        assertEquals(2,invalid.errors().stream().filter(x->x.startsWith("REQUIRED_FIELD_MISSING")).count());
    }
    @Test void normalizedRowsUseSchemaOrderRegardlessOfInputOrder(){
        var rows=List.of(new FormSchemaV5.Input("pnl","operatingExpenses","90.00"),
          new FormSchemaV5.Input("pnl","cogs","00260.00"),
          new FormSchemaV5.Input("pnl","netRevenue","400"));
        var result=FormSchemaV5.normalizeF02(rows);
        assertTrue(result.valid(),result.errors().toString());
        assertEquals(List.of("400","260","90"),result.cells().stream()
          .map(FormSchemaV5.Input::decimalText).toList());
        assertFalse(FormSchemaV5.normalizeF02(List.of(rows.getFirst(),rows.getFirst())).valid());
        assertThrows(IllegalArgumentException.class,()->FormSchemaV5.canonicalDecimal("1,000.00"));
    }
    @Test void commaDecimalAgreementIsExplicitlySourceLocaleBound(){
        assertEquals("1.25",FormSchemaV5.canonicalUserDecimal("1,25","ru"));
        assertEquals("1.25",FormSchemaV5.canonicalUserDecimal("1,25","kk"));
        assertThrows(IllegalArgumentException.class,()->FormSchemaV5.canonicalUserDecimal("1,25","en"));
        assertThrows(IllegalArgumentException.class,()->FormSchemaV5.canonicalUserDecimal("1 000,25","ru"));
        var rows=List.of(
          new FormSchemaV5.Input("pnl","netRevenue","400,50"),
          new FormSchemaV5.Input("pnl","cogs","260,25"),
          new FormSchemaV5.Input("pnl","operatingExpenses","90"));
        var result=FormSchemaV5.normalizeF02(rows,"ru");
        assertTrue(result.valid(),result.errors().toString());
        assertEquals(List.of("400.5","260.25","90"),result.cells().stream()
            .map(FormSchemaV5.Input::decimalText).toList());
        assertFalse(FormSchemaV5.normalizeF02(rows,"en").valid());
    }
    @Test void manualUploadMalformedBodiesProduceControlledErrors() {
        assertEquals("FIN_V5_CELLS_REQUIRED",
            assertThrows(IllegalArgumentException.class,
                ()->FormSchemaV5.normalizeF02(null,"ru")).getMessage());
        assertEquals("INVALID_PERIOD_INPUT",
            assertThrows(IllegalArgumentException.class,
                ()->ReportingPeriodV5.resolve(ReportingPeriodV5.Type.MONTH,null,
                    ReportingPeriodV5.Coverage.PERIOD_ONLY)).getMessage());
        assertEquals("INVALID_PERIOD_INPUT",
            assertThrows(IllegalArgumentException.class,
                ()->ReportingPeriodV5.resolve(null,"2026.10",
                    ReportingPeriodV5.Coverage.PERIOD_ONLY)).getMessage());
        assertEquals("TRANSLATION_NOT_READY",
            assertThrows(IllegalArgumentException.class,
                ()->FormSchemaV5.normalizeF02(List.of(),"zh-Hans")).getMessage());
    }
    @Test void manualUploadRejectsUnknownDuplicateOverflowAndRetainsExplicitZero() {
        var baseline=List.of(
            new FormSchemaV5.Input("pnl","netRevenue","0"),
            new FormSchemaV5.Input("pnl","cogs","260"),
            new FormSchemaV5.Input("pnl","operatingExpenses","90"));
        var valid=FormSchemaV5.normalizeF02(baseline);
        assertTrue(valid.valid());
        assertEquals("0",valid.cells().getFirst().decimalText());

        var duplicate=FormSchemaV5.normalizeF02(List.of(
            baseline.getFirst(),baseline.getFirst(),baseline.get(1),baseline.get(2)));
        assertFalse(duplicate.valid());
        assertTrue(duplicate.errors().stream().anyMatch(e->e.startsWith("DUPLICATE_SOURCE_FIELD:")));

        var foreign=FormSchemaV5.normalizeF02(List.of(
            new FormSchemaV5.Input("other","netRevenue","400"),
            baseline.get(1),baseline.get(2)));
        assertFalse(foreign.valid());
        assertTrue(foreign.errors().contains("UNKNOWN_SOURCE_COORDINATE"));

        var overflow=FormSchemaV5.normalizeF02(List.of(
            new FormSchemaV5.Input("pnl","netRevenue","9".repeat(27)),
            baseline.get(1),baseline.get(2)));
        assertFalse(overflow.valid());
        assertTrue(overflow.errors().contains("INVALID_DECIMAL:netRevenue"));

        var malformed=FormSchemaV5.normalizeF02(List.of(
            new FormSchemaV5.Input("pnl","netRevenue","1e6"),
            baseline.get(1),baseline.get(2)));
        assertFalse(malformed.valid());
        assertTrue(malformed.errors().contains("INVALID_DECIMAL:netRevenue"));
    }
    @Test void allTimeGranularitiesWithISOLeapYearAndYearRollover(){
        var week=ReportingPeriodV5.resolve(ReportingPeriodV5.Type.WEEK,"2026-W01",
            ReportingPeriodV5.Coverage.PERIOD_ONLY);
        assertEquals(LocalDate.of(2025,12,29),week.startInclusive());
        assertEquals(LocalDate.of(2026,1,5),week.endExclusive());
        assertEquals(LocalDate.of(2021,1,4),
          ReportingPeriodV5.resolve(ReportingPeriodV5.Type.WEEK,"2020-W53",
            ReportingPeriodV5.Coverage.PERIOD_ONLY).endExclusive());
        assertThrows(IllegalArgumentException.class,()->ReportingPeriodV5.resolve(
            ReportingPeriodV5.Type.WEEK,"2021-W53",ReportingPeriodV5.Coverage.PERIOD_ONLY));
        assertEquals(LocalDate.of(2026,11,1),ReportingPeriodV5.resolve(
            ReportingPeriodV5.Type.MONTH,"2026.10",ReportingPeriodV5.Coverage.PERIOD_ONLY).endExclusive());
        assertEquals(LocalDate.of(2026,10,1),ReportingPeriodV5.resolve(
            ReportingPeriodV5.Type.QUARTER,"2026-Q3",ReportingPeriodV5.Coverage.PERIOD_ONLY).endExclusive());
        assertEquals(LocalDate.of(2027,1,1),ReportingPeriodV5.resolve(
            ReportingPeriodV5.Type.HALF_YEAR,"2026-H2",ReportingPeriodV5.Coverage.PERIOD_ONLY).endExclusive());
        assertEquals(LocalDate.of(2027,1,1),ReportingPeriodV5.resolve(
            ReportingPeriodV5.Type.YEAR,"2026",ReportingPeriodV5.Coverage.PERIOD_ONLY).endExclusive());
        assertEquals(LocalDate.of(2024,2,29),ReportingPeriodV5.resolve(
            ReportingPeriodV5.Type.AS_OF,"2024-02-29",ReportingPeriodV5.Coverage.AS_OF).asOfDate());
        assertThrows(IllegalArgumentException.class,()->ReportingPeriodV5.resolve(
            ReportingPeriodV5.Type.AS_OF,"2025-02-29",ReportingPeriodV5.Coverage.AS_OF));
        assertThrows(IllegalArgumentException.class,()->ReportingPeriodV5.resolve(
            ReportingPeriodV5.Type.MONTH,"2026.10",ReportingPeriodV5.Coverage.AS_OF));
    }
}
