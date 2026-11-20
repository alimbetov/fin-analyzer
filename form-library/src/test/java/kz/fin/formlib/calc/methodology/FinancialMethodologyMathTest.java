package kz.fin.formlib.calc.methodology;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FinancialMethodologyMathTest {
  private static BigDecimal d(String v) {return new BigDecimal(v);}
  @Test void pastDueIsDaysNotMoneyAndDateDifferenceHasCorrectDirection(){
    assertEquals(12,FinancialMethodologyMath.daysPastDue(
      LocalDate.of(2026,11,20),LocalDate.of(2026,11,8)));
    assertEquals(0,FinancialMethodologyMath.daysPastDue(
      LocalDate.of(2026,11,20),LocalDate.of(2026,12,2)));
    assertEquals(0,FinancialMethodologyMath.daysPastDue(
      LocalDate.of(2026,11,20),LocalDate.of(2026,11,20)));
  }
  @Test void eirFiveYearFeeExampleCorrectsSourceTenPointTwoPercent(){
    BigDecimal eir=FinancialMethodologyMath.annualEirSynthetic(d("980"),
      List.of(d("80"),d("80"),d("80"),d("80"),d("1080")));
    assertTrue(eir.subtract(d("0.085076328113")).abs().compareTo(d("0.000000000002"))<0);
    assertTrue(eir.subtract(d("0.102")).abs().compareTo(d("0.01"))>0);
    assertEquals(0,eir.multiply(d("980")).subtract(d("83.374801550")).abs()
       .setScale(2,java.math.RoundingMode.HALF_EVEN).compareTo(d("0")));
  }
  @Test void invalidEirCashflowsOrUnbracketedRateBlocks(){
    assertThrows(IllegalArgumentException.class,()->
      FinancialMethodologyMath.annualEirSynthetic(d("0"),List.of(d("10"))));
    assertThrows(IllegalStateException.class,()->
      FinancialMethodologyMath.annualEirSynthetic(d("1000"),List.of(d("20"))));
  }
  @Test void itemLevelNrvAndZeroFloor(){
    var a=FinancialMethodologyMath.itemNrv(d("120"),d("110"),d("5"),d("5"));
    assertEquals(0,a.nrv().compareTo(d("100")));
    assertEquals(0,a.recognizedCost().compareTo(d("100")));
    assertEquals(0,a.writeDown().compareTo(d("20")));
    var b=FinancialMethodologyMath.itemNrv(d("120"),d("3"),d("7"),d("5"));
    assertEquals(0,b.recognizedCost().compareTo(BigDecimal.ZERO));
    assertEquals(0,b.writeDown().compareTo(d("120")));
    assertThrows(IllegalArgumentException.class,()->
      FinancialMethodologyMath.itemNrv(null,d("3"),d("0"),d("0")));
  }
  @Test void flowAndDiscountRateMustMatch(){
    var ev=FinancialMethodologyMath.discountedAnnualValue(
      "FCFF","WACC",List.of(d("100")),d("0.1"),d("0"));
    assertEquals("ENTERPRISE_VALUE",ev.resultBasis());
    assertTrue(ev.discountedValue().subtract(d("90.909090909090909"))
      .abs().compareTo(d("0.000000000000001"))<0);
    var eq=FinancialMethodologyMath.discountedAnnualValue(
      "FCFE","COST_OF_EQUITY",List.of(d("90")),d("0.2"),d("0"));
    assertEquals("EQUITY_VALUE",eq.resultBasis());
    assertEquals(0,eq.discountedValue().compareTo(d("75")));
    assertThrows(IllegalStateException.class,()->
      FinancialMethodologyMath.discountedAnnualValue(
        "FCFF","COST_OF_EQUITY",List.of(d("100")),d("0.1"),d("0")));
    assertThrows(IllegalStateException.class,()->
      FinancialMethodologyMath.discountedAnnualValue(
        "FCFE","WACC",List.of(d("90")),d("0.1"),d("0")));
  }
  @Test void debtServiceIsCashPaymentNotEirInterestExpense(){
    assertEquals(0,FinancialMethodologyMath.debtServiceCoverage(
      d("120"),d("80"),d("20")).compareTo(d("1.2")));
    assertThrows(IllegalStateException.class,()->
      FinancialMethodologyMath.debtServiceCoverage(d("120"),d("0"),d("0")));
  }
}
