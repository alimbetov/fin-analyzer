package kz.fin.formlib.calc.loan;

import kz.fin.formlib.calc.loan.LoanAmendmentCalculator.Event;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Independent amendment financial controls; no mocked calculator or DB. */
class LoanAmendmentCalculatorTest {
  private final LoanPaymentScheduleCalculator base=new LoanPaymentScheduleCalculator();
  private final LoanAmendmentCalculator amendment=new LoanAmendmentCalculator();
  private static BigDecimal n(String x){return new BigDecimal(x);}
  private static LocalDate d(String x){return LocalDate.parse(x);}
  private static LoanContractIntakeService.Terms terms(String method,String rate){
    return new LoanContractIntakeService.Terms("LOAN-001","AGR-001","BANK",
      d("2027-01-01"),d("2027-01-01"),d("2027-01-01"),d("2027-05-01"),
      n("1000.00"),"KZ","BORROWER",method,"MONTHLY",1,"SIMPLE",
      "ACT_365_FIXED","NONE",2,"HALF_EVEN","ADJUST_LAST_PAYMENT",
      "FIXED",n(rate));
  }
  private LoanAmendmentCalculator.Result run(String method,String rate,Event event){
    var t=terms(method,rate);
    return amendment.calculate(t,List.of(),base.calculate(t,List.of(),List.of()),event);
  }
  private static Event prepay(String amount,String policy){
    return new Event("PREPAYMENT",d("2027-02-01"),n(amount),policy,null,null,null);
  }
  @Test void prepaymentReduceTermRetainsRegularInstallmentAndEndsOneMonthEarly(){
    var result=run("EQUAL_PRINCIPAL","0",prepay("400.00","REDUCE_TERM"));
    assertEquals(d("2027-04-01"),result.actualMaturityDate());
    assertEquals(3,result.lines().size());
    assertEquals(0,result.lines().getFirst().scheduledPrincipal().compareTo(n("250")));
    assertEquals(0,result.lines().getFirst().extraPrincipal().compareTo(n("400")));
    assertEquals(0,result.lines().get(1).scheduledPrincipal().compareTo(n("250")));
    assertEquals(0,result.lines().getLast().scheduledPrincipal().compareTo(n("100")));
    assertEquals(0,result.totalScheduledPrincipal().compareTo(n("600")));
    assertEquals(0,result.totalExtraPrincipal().compareTo(n("400")));
    assertEquals(0,result.finalPrincipal().compareTo(BigDecimal.ZERO));
  }
  @Test void prepaymentReduceInstallmentPreservesMaturityAndLowersNextPayments(){
    var result=run("EQUAL_PRINCIPAL","0",prepay("400.00","REDUCE_INSTALLMENT"));
    assertEquals(d("2027-05-01"),result.actualMaturityDate());
    assertEquals(4,result.lines().size());
    assertEquals(0,result.lines().get(1).scheduledPrincipal().compareTo(n("116.67")));
    assertEquals(0,result.lines().get(2).scheduledPrincipal().compareTo(n("116.67")));
    assertEquals(0,result.lines().get(3).scheduledPrincipal().compareTo(n("116.66")));
    assertEquals(0,result.totalExtraPrincipal().compareTo(n("400")));
  }
  @Test void annuityReduceTermVsInstallmentAreDistinctAndReconciled(){
    var term=run("ANNUITY","12",prepay("400.00","REDUCE_TERM"));
    var payment=run("ANNUITY","12",prepay("400.00","REDUCE_INSTALLMENT"));
    assertTrue(term.actualMaturityDate().isBefore(payment.actualMaturityDate()));
    assertEquals(d("2027-05-01"),payment.actualMaturityDate());
    assertTrue(payment.lines().get(1).totalCashDue().compareTo(
      term.lines().get(1).totalCashDue())<0);
    assertEquals(0,term.finalPrincipal().compareTo(BigDecimal.ZERO));
    assertEquals(0,payment.finalPrincipal().compareTo(BigDecimal.ZERO));
  }
  @Test void principalOnlyGraceDefersPrincipalButPaysInterest(){
    var result=run("EQUAL_PRINCIPAL","12",new Event("GRACE",
      d("2027-02-01"),null,null,d("2027-03-01"),"PRINCIPAL_ONLY",null));
    assertEquals(4,result.lines().size());
    assertEquals(0,result.lines().get(0).scheduledPrincipal().compareTo(BigDecimal.ZERO));
    assertTrue(result.lines().get(0).cashInterestDue().signum()>0);
    assertEquals(0,result.lines().get(1).scheduledPrincipal().compareTo(BigDecimal.ZERO));
    assertEquals(0,result.lines().get(2).scheduledPrincipal().compareTo(n("500.00")));
    assertEquals(0,result.lines().get(3).scheduledPrincipal().compareTo(n("500.00")));
    assertEquals(0,result.totalCapitalizedInterest().compareTo(BigDecimal.ZERO));
  }
  @Test void fullGraceCapitalizesInterestWithoutTreatingItAsCash(){
    var result=run("EQUAL_PRINCIPAL","12",new Event("GRACE",
      d("2027-02-01"),null,null,d("2027-03-01"),"FULL_CAPITALIZE",null));
    assertEquals(0,result.lines().get(0).cashInterestDue().compareTo(BigDecimal.ZERO));
    assertTrue(result.lines().get(0).capitalizedInterest().signum()>0);
    assertEquals(0,result.lines().get(1).cashInterestDue().compareTo(BigDecimal.ZERO));
    assertTrue(result.totalCapitalizedInterest().signum()>0);
    assertEquals(0,result.totalScheduledPrincipal().compareTo(
      n("1000").add(result.totalCapitalizedInterest())));
    assertEquals(0,result.finalPrincipal().compareTo(BigDecimal.ZERO));
  }
  @Test void midPeriodRateStepSplitsCouponAtExactDateInsteadOfNextDueDate(){
    var result=run("EQUAL_PRINCIPAL","12",new Event("RATE_STEP",
      d("2027-01-15"),null,null,null,null,n("24")));
    assertEquals(0,result.lines().getFirst().cashInterestDue().compareTo(n("15.78")));
    assertEquals(0,result.lines().getFirst().scheduledPrincipal().compareTo(n("250")));
    assertEquals(0,result.lines().get(1).cashInterestDue().compareTo(n("13.81")));
    assertEquals(0,result.finalPrincipal().compareTo(BigDecimal.ZERO));
  }
  @Test void invalidOrUnsourcedFloatingIndexMustFailClosed(){
    assertThrows(ResponseStatusException.class,()->run("ANNUITY","12",
      new Event("FLOATING_INDEX",d("2027-01-15"),null,null,null,null,n("12"))));
    assertThrows(ResponseStatusException.class,()->run("EQUAL_PRINCIPAL","0",
      prepay("2000","REDUCE_TERM")));
    assertThrows(ResponseStatusException.class,()->run("EQUAL_PRINCIPAL","12",
      new Event("GRACE",d("2027-02-01"),null,null,d("2027-05-01"),
        "FULL_CAPITALIZE",null)));
  }
}
