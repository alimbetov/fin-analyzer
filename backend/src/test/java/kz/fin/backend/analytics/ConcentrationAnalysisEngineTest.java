package kz.fin.backend.analytics;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static kz.fin.backend.analytics.ConcentrationAnalysisEngine.*;

class ConcentrationAnalysisEngineTest {
    private static SkuAmount sku(String code,String amount) {
        return new SkuAmount(code,new BigDecimal(amount));
    }
    private static BigDecimal amount(String raw){return new BigDecimal(raw);}

    @Test void independentlyComputedParetoAndAbcGolden500_300_150_50(){
        var population=pareto(List.of(sku("D","50"),sku("B","300"),
                sku("A","500"),sku("C","150")),amount("1000"));
        assertEquals(PARETO_VERSION,population.methodVersion());
        assertEquals(List.of("A","B","C","D"),population.rows()
                .stream().map(ParetoRow::skuCode).toList());
        assertEquals(List.of(50,30,15,5),population.rows().stream()
                .map(r->r.sharePercent().intValueExact()).toList());
        assertEquals(List.of(50,80,95,100),population.rows().stream()
                .map(r->r.cumulativePercent().intValueExact()).toList());
        var abc=abc(population,DEFAULT_ABC_POLICY);
        assertEquals(ABC_VERSION,abc.policyVersion());
        assertEquals(List.of("A","A","B","C"),abc.rows().stream()
                .map(AbcRow::classification).toList());
    }

    @Test void stableTiesAndBoundaryCrossingPolicyAreExplicit(){
        var tied=pareto(List.of(sku("ZZ","50"),sku("AA","50")),amount("100"));
        assertEquals(List.of("AA","ZZ"),tied.rows().stream()
                .map(ParetoRow::skuCode).toList());
        var crossing=abc(pareto(List.of(sku("A","85"),sku("B","10"),
                sku("C","5")),amount("100")),DEFAULT_ABC_POLICY);
        assertEquals(List.of("A","B","C"),crossing.rows()
                .stream().map(AbcRow::classification).toList());
        assertEquals(0,amount("100").compareTo(
                crossing.rows().getLast().cumulativePercent()));
    }

    @Test void tinyDistanceBelowEightyPercentMustNotBeRoundedIntoBClass(){
        var pop=pareto(List.of(
            sku("A","79999999999999999"),
            sku("B","20000000000000001")),
            amount("100000000000000000"));
        assertEquals(0,pop.rows().getFirst().cumulativePercent()
                .compareTo(amount("80.000000000000")));
        // Display rounds to 80%, but exact cumulative before B is below 80%.
        var abc=abc(pop,DEFAULT_ABC_POLICY);
        assertEquals(List.of("A","A"),
                abc.rows().stream().map(AbcRow::classification).toList());
    }

    @Test void zeroOrUnreconciledOrDuplicatePopulationCannotCompute(){
        assertThrows(IllegalArgumentException.class,
                ()->pareto(List.of(sku("A","20"),sku("B","80")),amount("90")));
        assertThrows(IllegalArgumentException.class,
                ()->pareto(List.of(sku("A","20"),sku("A","80")),amount("100")));
        assertThrows(IllegalArgumentException.class,
                ()->pareto(List.of(sku("A","0")),amount("0")));
        assertThrows(IllegalArgumentException.class,
                ()->pareto(List.of(),amount("100")));
        assertThrows(IllegalArgumentException.class,()->sku("A","-1"));
        assertThrows(IllegalArgumentException.class,()->sku("bad SKU","1"));
    }

    @Test void ratiosNeverUseFloatingArithmeticAndReturnImmutableRows(){
        var p=pareto(List.of(sku("A","1"),sku("B","1"),sku("C","1")),
                amount("3"));
        assertEquals(amount("33.333333333333"),
                p.rows().getFirst().sharePercent());
        assertEquals(amount("100.000000000000"),
                p.rows().getLast().cumulativePercent());
        assertThrows(UnsupportedOperationException.class,
                ()->p.rows().clear());
        var abc=abc(p,DEFAULT_ABC_POLICY);
        assertEquals(3,abc.rows().size());
        assertThrows(IllegalArgumentException.class,
                ()->new AbcPolicy("INVALID",amount("95"),amount("80")));
    }
}
