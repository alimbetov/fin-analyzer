package kz.fin.formlib.calc.kernel;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class F02CalculationKernelTest {
  private final F02CalculationKernel kernel=new F02CalculationKernel();

  private static Map<String,BigDecimal> f02(String revenue,String cogs,String opex){
    return Map.of("F02.netRevenue",new BigDecimal(revenue),
        "F02.cogs",new BigDecimal(cogs),
        "F02.operatingExpenses",new BigDecimal(opex));
  }
  @Test void computesThreeExactFinancialNodes(){
    var r=kernel.evaluate(f02("12500000","7400000","2100000"));
    assertEquals("5100000.00",r.nodes().get("grossProfit").value());
    assertEquals("0.40800000",r.nodes().get("grossMargin").value());
    assertEquals("3000000.00",r.nodes().get("operatingResult").value());
    assertEquals("PRESENT",r.nodes().get("grossMargin").state());
  }
  @Test void zeroRevenueYieldsExplicitInvalidRatioNotFalseZero(){
    var r=kernel.evaluate(f02("0","1","2"));
    assertEquals("-1.00",r.nodes().get("grossProfit").value());
    assertEquals("-3.00",r.nodes().get("operatingResult").value());
    assertEquals("INVALID",r.nodes().get("grossMargin").state());
    assertEquals("DIVISION_BY_ZERO",r.nodes().get("grossMargin").errorCode());
    assertNull(r.nodes().get("grossMargin").value());
  }
  @Test void canonicalInputOrderDoesNotChangeHash(){
    var a=kernel.evaluate(f02("12500000.000","7400000","2100000"));
    var b=new LinkedHashMap<String,BigDecimal>();
    b.put("F02.operatingExpenses",new BigDecimal("2100000"));
    b.put("F02.netRevenue",new BigDecimal("12500000"));
    b.put("F02.cogs",new BigDecimal("7400000.0"));
    assertEquals(a.outputValuesSha256(),kernel.evaluate(b).outputValuesSha256());
    assertEquals(a.nodes(),kernel.evaluate(b).nodes());
  }
  @Test void resultDiffersWhenOperatingExpensesChange(){
    var x=kernel.evaluate(f02("100","20","30"));
    var y=kernel.evaluate(f02("100","20","31"));
    assertEquals(x.nodes().get("grossProfit"),y.nodes().get("grossProfit"));
    assertEquals(x.nodes().get("grossMargin"),y.nodes().get("grossMargin"));
    assertNotEquals(x.nodes().get("operatingResult"),y.nodes().get("operatingResult"));
    assertNotEquals(x.outputValuesSha256(),y.outputValuesSha256());
  }
  @Test void rejectMissingAndNegativeInputs(){
    assertThrows(IllegalArgumentException.class,()->kernel.evaluate(Map.of(
        "F02.netRevenue",BigDecimal.ONE)));
    assertThrows(IllegalArgumentException.class,()->kernel.evaluate(f02("100","-1","20")));
    assertThrows(IllegalArgumentException.class,()->kernel.evaluate(f02("1.1234567890123","1","1")));
  }
  @Test void noFloatingPointAtRoundingBoundary(){
    var r=kernel.evaluate(f02("1.005","0","0.005"));
    assertEquals("1.00",r.nodes().get("grossProfit").value());
    assertEquals("1.00000000",r.nodes().get("grossMargin").value());
    assertEquals("1.00",r.nodes().get("operatingResult").value());
  }
}
