package kz.fin.backend.methodology;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MethodParameterPresetGeneratorTest {
    private static BigDecimal bd(String x) { return new BigDecimal(x); }

    @Test void nearGridIsDeterministicBoundedAndExplicitlyCentered() {
        var a=MethodParameterPresetGenerator.abcNear(bd("80"),bd("95"),bd("5"),10);
        var b=MethodParameterPresetGenerator.abcNear(bd("80.00"),bd("95.0"),bd("5.0"),10);
        assertEquals(a,b);
        assertEquals(10,a.size());
        assertEquals(bd("80"),a.getFirst().parameters().get("aLimitPercent"));
        assertEquals(bd("95"),a.getFirst().parameters().get("bLimitPercent"));
        var seen=new HashSet<String>();
        for(int i=0;i<a.size();i++) {
            var p=a.get(i);
            assertEquals(i+1,p.optionNo());
            var aa=(BigDecimal)p.parameters().get("aLimitPercent");
            var bb=(BigDecimal)p.parameters().get("bLimitPercent");
            assertTrue(aa.signum()>0 && aa.compareTo(bb)<0 && bb.compareTo(bd("100"))<0);
            assertEquals(0,aa.subtract(bd("80")).remainder(bd("5")).signum());
            assertEquals(0,bb.subtract(bd("95")).remainder(bd("5")).signum());
            assertEquals("CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED",
                    p.parameters().get("boundaryCrossingPolicy"));
            assertTrue(seen.add(aa.toPlainString()+"/"+bb.toPlainString()));
            assertThrows(UnsupportedOperationException.class,
                    () -> p.parameters().put("aLimitPercent",bd("0")));
        }
        assertEquals(3,MethodParameterPresetGenerator.abcNear(
                bd("80"),bd("95"),bd("5"),3).size());
    }

    @Test void noDefaultsOrUnsupportedStepOrUnlimitedCombinatorics() {
        assertThrows(NullPointerException.class, () ->
                MethodParameterPresetGenerator.abcNear(null,bd("95"),bd("5"),10));
        for(var bad:List.of("0","-5","10.01"))
            assertThrows(IllegalArgumentException.class,() ->
                    MethodParameterPresetGenerator.abcNear(
                            bd("80"),bd("95"),bd(bad),10));
        assertThrows(IllegalArgumentException.class,() ->
                MethodParameterPresetGenerator.abcNear(bd("95"),bd("80"),bd("5"),10));
        for(int n:List.of(0,11,100))
            assertThrows(IllegalArgumentException.class,() ->
                    MethodParameterPresetGenerator.abcNear(
                            bd("80"),bd("95"),bd("5"),n));
        assertNotEquals(MethodParameterPresetGenerator.abcNear(
                bd("80"),bd("95"),bd("5"),10),MethodParameterPresetGenerator.abcNear(
                bd("75"),bd("90"),bd("5"),10));
    }

    @Test void fixedParetoCannotInventTenDifferentAlgorithms() {
        var options=MethodParameterPresetGenerator.fixedPareto();
        assertEquals(1,options.size());
        assertEquals("NET_SALES",options.getFirst().parameters().get("populationMetric"));
        assertThrows(UnsupportedOperationException.class, () ->
                options.getFirst().parameters().put("returnPolicy","UNREVIEWED"));
    }
}
