package kz.fin.backend.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static kz.fin.backend.domain.EconomicKernel.*;

class EconomicKernelTest {
    private static final UUID ORG = UUID.randomUUID(), ENTITY = UUID.randomUUID(), SCOPE = UUID.randomUUID();
    private static final TemporalReference JAN = TemporalReference.interval(LocalDate.of(2026,1,1),LocalDate.of(2026,2,1));
    private static final TemporalReference FEB = TemporalReference.interval(LocalDate.of(2026,2,1),LocalDate.of(2026,3,1));
    private static final TemporalReference MAR = TemporalReference.interval(LocalDate.of(2026,3,1),LocalDate.of(2026,4,1));
    private static final TemporalReference Q1 = TemporalReference.interval(LocalDate.of(2026,1,1),LocalDate.of(2026,4,1));

    private FactAssertion fact(UUID id, UUID org, UUID source, String metric, EconomicValue value) {
        FactKey key = new FactKey(org, ENTITY, SCOPE, metric, "1.0.0", JAN,
                ReportingBasis.MANAGEMENT, "KZT", "KZT", new TreeMap<>(Map.of("store","1")));
        return new FactAssertion(id, key, value, source, Instant.parse("2026-11-15T00:00:00Z"));
    }

    @Test void missingIsNotZeroAndEstimatesNeedMethod() {
        assertNull(EconomicValue.missing().decimal());
        assertEquals(0, EconomicValue.explicitZero().decimal().signum());
        assertNotEquals(EconomicValue.missing().state(), EconomicValue.explicitZero().state());
        assertThrows(IllegalArgumentException.class, () -> new EconomicValue(ValueState.EXPLICIT_ZERO, BigDecimal.ONE, null));
        assertThrows(IllegalArgumentException.class, () -> EconomicValue.estimated("12", ""));
        assertThrows(IllegalArgumentException.class, () -> new EconomicValue(ValueState.MISSING, BigDecimal.ZERO, null));
    }

    @Test void decimalContractAcceptsNegativesButRejectsOverflow() {
        assertEquals(0, EconomicValue.present("-1234.567").decimal().compareTo(new BigDecimal("-1234.567")));
        assertThrows(IllegalArgumentException.class, () -> EconomicValue.present("1.1234567890123"));
        assertThrows(IllegalArgumentException.class, () -> EconomicValue.present("100000000000000000000000000"));
    }

    @Test void periodsAreHalfOpenAndStocksArePoints() {
        assertEquals(LocalDate.of(2026,4,1), Q1.endExclusive());
        assertThrows(IllegalArgumentException.class,
                () -> TemporalReference.interval(LocalDate.of(2026,3,2), LocalDate.of(2026,3,1)));
        assertThrows(IllegalArgumentException.class,
                () -> new TemporalReference(TimeKind.AS_OF, LocalDate.of(2026,3,1),LocalDate.of(2026,3,3)));
    }

    @Test void approvalRejectsConflictingSameKeyAndCrossTenantFacts() {
        UUID rev = UUID.randomUUID(), source = UUID.randomUUID();
        FactAssertion a = fact(UUID.randomUUID(), ORG, source, "REV_NET", EconomicValue.present("10"));
        FactAssertion b = fact(UUID.randomUUID(), ORG, UUID.randomUUID(), "REV_NET", EconomicValue.present("12"));
        assertThrows(IllegalArgumentException.class, () -> FactSetRevision.approved(rev, ORG, null, List.of(a,b), Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> FactSetRevision.approved(rev, ORG, null,
                List.of(fact(UUID.randomUUID(), UUID.randomUUID(), source, "REV_NET", EconomicValue.present("1"))), Instant.now()));
    }

    @Test void approvedFactSetsAreImmutableAndRestatementUsesNewRevision() {
        FactAssertion oldFact = fact(UUID.randomUUID(), ORG, UUID.randomUUID(), "REV_NET", EconomicValue.present("10"));
        FactAssertion newFact = fact(UUID.randomUUID(), ORG, UUID.randomUUID(), "REV_NET", EconomicValue.present("11"));
        FactSetRevision oldSet = FactSetRevision.approved(UUID.randomUUID(), ORG, null, List.of(oldFact), Instant.now());
        FactSetRevision next = FactSetRevision.approved(UUID.randomUUID(), ORG, oldSet.id(), List.of(newFact), Instant.now());
        assertEquals(oldFact.id(), oldSet.selectedAssertions().get(oldFact.key()));
        assertEquals(newFact.id(), next.selectedAssertions().get(newFact.key()));
        assertThrows(UnsupportedOperationException.class, () -> oldSet.selectedAssertions().clear());
        assertEquals(oldSet.id(), next.parentRevisionId());
    }

    @Test void flowAggregationNeedsCompleteMonthsAndNeverAveragesRatios() {
        var observations = List.of(
                new PeriodAggregation.Observation(JAN, EconomicValue.present("100")),
                new PeriodAggregation.Observation(FEB, EconomicValue.present("900")),
                new PeriodAggregation.Observation(MAR, EconomicValue.explicitZero()));
        var actual = PeriodAggregation.flowSum(Q1, observations);
        assertEquals(PeriodAggregation.Status.CALCULATED, actual.status());
        assertEquals(0, actual.value().compareTo(new BigDecimal("1000")));
        var incomplete = PeriodAggregation.flowSum(Q1, observations.subList(0,2));
        assertEquals(PeriodAggregation.Status.INCOMPLETE, incomplete.status());
        assertNull(incomplete.value());
    }

    @Test void periodOverlapAndStockSummationAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PeriodAggregation.flowSum(Q1, List.of(
                new PeriodAggregation.Observation(JAN, EconomicValue.present("10")),
                new PeriodAggregation.Observation(JAN, EconomicValue.present("20")))));
        assertThrows(IllegalArgumentException.class, () -> PeriodAggregation.flowSum(Q1, List.of(
                new PeriodAggregation.Observation(TemporalReference.asOf(LocalDate.of(2026,3,31)), EconomicValue.present("10")))));
    }

    @Test void closingStockIsNotSumOfMonthlyBalances() {
        var observations = List.of(
                new PeriodAggregation.Observation(TemporalReference.asOf(LocalDate.of(2026,1,31)), EconomicValue.present("10")),
                new PeriodAggregation.Observation(TemporalReference.asOf(LocalDate.of(2026,2,28)), EconomicValue.present("20")),
                new PeriodAggregation.Observation(TemporalReference.asOf(LocalDate.of(2026,3,31)), EconomicValue.present("30")));
        var result = PeriodAggregation.closingStock(Q1, observations);
        assertEquals(0, result.value().compareTo(new BigDecimal("30")));
        assertEquals(PeriodAggregation.Status.INCOMPLETE,
                PeriodAggregation.closingStock(Q1, observations.subList(0,2)).status());
    }

    private CalculationDag dag() {
        var money = CalculationDag.Dimension.money();
        var scalar = CalculationDag.Dimension.scalar();
        return new CalculationDag(Map.of("CURRENT_ASSETS", money, "CURRENT_LIABILITIES", money,
                        "REVENUE",money,"COGS",money),
                List.of(
                        new CalculationDag.Formula("CURRENT_RATIO", "1.0.0", scalar,
                                new CalculationDag.Divide(new CalculationDag.Ref("CURRENT_ASSETS"),
                                        new CalculationDag.Ref("CURRENT_LIABILITIES"))),
                        new CalculationDag.Formula("GROSS_PROFIT", "1.0.0", money,
                                new CalculationDag.Subtract(new CalculationDag.Ref("REVENUE"), new CalculationDag.Ref("COGS")))),
                MathContext.DECIMAL128);
    }

    @Test void dagComputesAndGuardsZeroDenominator() {
        var r = dag().calculate("CURRENT_RATIO", Map.of(
                "CURRENT_ASSETS", EconomicValue.present("150"),
                "CURRENT_LIABILITIES", EconomicValue.present("100")));
        assertEquals(CalculationDag.Status.CALCULATED, r.status());
        assertEquals(0, r.value().compareTo(new BigDecimal("1.5")));
        var zero = dag().calculate("CURRENT_RATIO", Map.of(
                "CURRENT_ASSETS", EconomicValue.present("150"),
                "CURRENT_LIABILITIES", EconomicValue.explicitZero()));
        assertEquals("ZERO_DENOMINATOR", zero.reason());
        assertNull(zero.value());
    }

    @Test void missingInputCannotBecomeNumericResult() {
        var r=dag().calculate("GROSS_PROFIT", Map.of("REVENUE", EconomicValue.present("900")));
        assertEquals(CalculationDag.Status.NOT_COMPUTABLE, r.status());
        assertNull(r.value());
    }

    @Test void estimatedInputIsMarkedEstimatedInDerivedResult() {
        var r=dag().calculate("GROSS_PROFIT", Map.of(
                "REVENUE", EconomicValue.estimated("900", "USER_ESTIMATE_V1"),
                "COGS", EconomicValue.present("500")));
        assertEquals(CalculationDag.Status.ESTIMATED, r.status());
        assertEquals(0, r.value().compareTo(new BigDecimal("400")));
    }

    @Test void dagRejectsCyclesAndIncompatibleDimensions() {
        var scalar=CalculationDag.Dimension.scalar();
        assertThrows(IllegalArgumentException.class, () -> new CalculationDag(Map.of(),
                List.of(new CalculationDag.Formula("A","1",scalar,new CalculationDag.Ref("B")),
                        new CalculationDag.Formula("B","1",scalar,new CalculationDag.Ref("A"))),
                MathContext.DECIMAL128));
        assertThrows(IllegalArgumentException.class, () -> new CalculationDag(
                Map.of("MONEY",CalculationDag.Dimension.money(),"QTY",CalculationDag.Dimension.units()),
                List.of(new CalculationDag.Formula("BAD","1",CalculationDag.Dimension.money(),
                        new CalculationDag.Add(new CalculationDag.Ref("MONEY"),new CalculationDag.Ref("QTY")))),
                MathContext.DECIMAL128));
    }

    @Test void scenariosDoNotMutateApprovedFacts() {
        FactAssertion actual = fact(UUID.randomUUID(), ORG, UUID.randomUUID(), "REV_NET", EconomicValue.present("100"));
        FactSetRevision approved = FactSetRevision.approved(UUID.randomUUID(), ORG,null,List.of(actual),Instant.now());
        Scenario scenario = new Scenario(UUID.randomUUID(),approved.id(), UUID.randomUUID(), Map.of("PRICE_CHANGE",new BigDecimal("0.15")));
        assertEquals(0, actual.value().decimal().compareTo(new BigDecimal("100")));
        assertEquals(approved.id(), scenario.baseFactSetRevisionId());
        assertThrows(UnsupportedOperationException.class, () -> scenario.overrides().clear());
    }
}
