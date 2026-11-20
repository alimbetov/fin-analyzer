package kz.fin.formlib.calc.loan;

import kz.fin.formlib.calc.loan.LoanPaymentScheduleCalculator.CustomInstallment;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Independent dated-contract cash examples. No nominal/12 shortcut. */
class LoanPaymentScheduleCalculatorTest {
  private final LoanPaymentScheduleCalculator calculator=new LoanPaymentScheduleCalculator();
  private static BigDecimal n(String value){return new BigDecimal(value);}

  private static LoanContractIntakeService.Terms terms(
      String method,String value,String rate,String start,String maturity,
      int dueDay,String dayCount) {
    LocalDate s=LocalDate.parse(start);
    return new LoanContractIntakeService.Terms("LOAN-001","AGR-001","BANK-001",
      s,s,s,LocalDate.parse(maturity),n(value),"KZ","BORROWER",method,
      "MONTHLY",dueDay,"SIMPLE",dayCount,"NONE",2,"HALF_EVEN",
      "ADJUST_LAST_PAYMENT","FIXED",n(rate));
  }

  private static LoanContractIntakeService.Fee fee(String code,String type,
      String settlement,String basis,String value,String frequency,String event){
    return new LoanContractIntakeService.Fee(code,type,settlement,basis,n(value),
      frequency,event,"UNREVIEWED","UNREVIEWED");
  }

  @Test void zeroRateThreePeriodsDistributesFinalPennyExactly(){
    var t=terms("EQUAL_PRINCIPAL","1000.00","0","2027-01-01","2027-04-01",
      1,"ACT_365_FIXED");
    var result=calculator.calculate(t,List.of(),List.of());
    assertEquals(3,result.lines().size());
    assertEquals(List.of("333.33","333.33","333.34"),
      result.lines().stream().map(x->x.principalDue().toPlainString()).toList());
    assertEquals(0,result.totalContractualInterest().compareTo(BigDecimal.ZERO));
    assertEquals(0,result.finalBalance().compareTo(BigDecimal.ZERO));
    assertEquals(0,result.totalScheduledCash().compareTo(n("1000")));
  }

  @Test void annuityTwoDatedEqual31DayIntervalsHasIndependentGolden(){
    var t=terms("ANNUITY","1000.00","12","2026-12-01","2027-02-01",
      1,"ACT_360");
    var r=calculator.calculate(t,List.of(),List.of());
    assertEquals(2,r.lines().size());
    assertEquals("2027-01-01",r.lines().getFirst().dueAt().toString());
    assertEquals("2027-02-01",r.lines().getLast().dueAt().toString());
    // Financially independent manual recurrence: 12% * 31 / 360 each.
    assertEquals(0,r.lines().getFirst().contractualInterestDue().compareTo(n("10.33")));
    assertEquals(0,r.lines().getFirst().principalDue().compareTo(n("497.43")));
    assertEquals(0,r.lines().getLast().contractualInterestDue().compareTo(n("5.19")));
    assertEquals(0,r.lines().getLast().principalDue().compareTo(n("502.57")));
    assertEquals(0,r.totalScheduledCash().compareTo(n("1015.52")));
    assertEquals(0,r.finalBalance().compareTo(BigDecimal.ZERO));
  }

  @Test void actualDayCountFollows31Vs28Vs31DaysAndNotNominalMonth(){
    var t=terms("EQUAL_PRINCIPAL","900.00","12","2027-01-01","2027-04-01",
      1,"ACT_365_FIXED");
    var r=calculator.calculate(t,List.of(),List.of());
    assertEquals(List.of("2027-02-01","2027-03-01","2027-04-01"),
      r.lines().stream().map(l->l.dueAt().toString()).toList());
    assertEquals(0,r.lines().getFirst().contractualInterestDue()
      .compareTo(n("9.17"))); // 900 * .12 * 31 / 365
    assertEquals(0,r.lines().get(1).contractualInterestDue()
      .compareTo(n("5.52"))); // 600 * .12 * 28 / 365
    assertEquals(0,r.lines().getLast().contractualInterestDue()
      .compareTo(n("3.06"))); // 300 * .12 * 31 / 365
    assertEquals(0,r.totalPrincipal().compareTo(n("900")));
  }

  @Test void customScheduleUsesExplicitDatesAndAmountsNotGeneratedDefault(){
    var t=terms("CUSTOM","1000.00","12","2027-01-01","2027-04-01",
      1,"ACT_360");
    var custom=List.of(
      new CustomInstallment(LocalDate.parse("2027-01-31"),n("100.00")),
      new CustomInstallment(LocalDate.parse("2027-03-02"),n("0.00")),
      new CustomInstallment(LocalDate.parse("2027-04-01"),n("900.00")));
    var r=calculator.calculate(t,List.of(),custom);
    assertEquals(3,r.lines().size());
    assertEquals(0,r.lines().get(1).principalDue().compareTo(n("0")));
    assertEquals(0,r.lines().get(1).closingPrincipal().compareTo(n("900")));
    assertEquals(0,r.finalBalance().compareTo(BigDecimal.ZERO));
    assertEquals(0,r.totalPrincipal().compareTo(n("1000")));
    assertThrows(ResponseStatusException.class,()->
      calculator.calculate(t,List.of(),List.of(
        new CustomInstallment(LocalDate.parse("2027-04-01"),n("900")))));
  }

  @Test void financedWithheldUpfrontRecurringAndMaturityFeesNeverDoubleCount(){
    var t=terms("EQUAL_PRINCIPAL","1000.00","0",
      "2027-01-01","2027-03-01",1,"ACT_360");
    var fees=List.of(
      fee("FIN","FIXED","FINANCED","FIXED_AMOUNT","50","ONCE","DISBURSEMENT_DATE"),
      fee("HOLD","FIXED","WITHHELD","FIXED_AMOUNT","20","ONCE","DISBURSEMENT_DATE"),
      fee("UP","FIXED","UPFRONT_PAID","FIXED_AMOUNT","10","ONCE","DISBURSEMENT_DATE"),
      fee("RUN","RECURRING_FIXED","RECURRING","FIXED_AMOUNT","5","MONTHLY","SCHEDULE_DUE_DATE"),
      fee("END","FIXED","AT_MATURITY","FIXED_AMOUNT","30","ONCE","MATURITY_DATE"));
    var r=calculator.calculate(t,fees,List.of());
    assertEquals(0,r.funding().openingLiability().compareTo(n("1050")));
    assertEquals(0,r.funding().netCashProceeds().compareTo(n("970")));
    assertEquals(0,r.funding().financedFees().compareTo(n("50")));
    assertEquals(0,r.funding().withheldFees().compareTo(n("20")));
    assertEquals(0,r.funding().upfrontPaidFees().compareTo(n("10")));
    assertEquals(0,r.totalPrincipal().compareTo(n("1050")));
    assertEquals(0,r.totalScheduledFees().compareTo(n("40")));
    assertEquals(0,r.totalScheduledCash().compareTo(n("1090")));
    assertTrue(r.lines().getFirst().feeBreakdown().containsKey("RUN"));
    assertTrue(r.lines().getLast().feeBreakdown().containsKey("END"));
    assertFalse(r.lines().getFirst().feeBreakdown().containsKey("FIN"));
    assertFalse(r.lines().getLast().feeBreakdown().containsKey("HOLD"));
  }

  @Test void recurringOutstandingFeeUsesCurrentBalanceNotOriginalTwice(){
    var t=terms("EQUAL_PRINCIPAL","1000.00","0",
      "2027-01-01","2027-03-01",1,"ACT_360");
    var fee=fee("PCT","PERCENT_OF_OUTSTANDING","RECURRING",
      "OUTSTANDING","1","MONTHLY","SCHEDULE_DUE_DATE");
    var result=calculator.calculate(t,List.of(fee),List.of());
    assertEquals(0,result.lines().getFirst().feesDue().compareTo(n("10")));
    assertEquals(0,result.lines().getLast().feesDue().compareTo(n("5")));
    assertEquals(0,result.totalScheduledFees().compareTo(n("15")));
  }

  @Test void thirtyE360HasDifferentFractionFromActual365ForUnequalDates(){
    var start=LocalDate.parse("2027-01-31");
    var end=LocalDate.parse("2027-02-28");
    assertEquals(0,LoanPaymentScheduleCalculator.fraction(start,end,"THIRTY_E_360")
      .compareTo(n("28").divide(n("360"),java.math.MathContext.DECIMAL128)));
    assertNotEquals(0,LoanPaymentScheduleCalculator.fraction(start,end,"ACT_365_FIXED")
      .compareTo(LoanPaymentScheduleCalculator.fraction(start,end,"THIRTY_E_360")));
  }

  @Test void invalidFeeSettlementDateAndMissingCustomRejectInsteadOfGuess(){
    var t=terms("CUSTOM","1000.00","0",
      "2027-01-01","2027-03-01",1,"ACT_360");
    assertThrows(ResponseStatusException.class,
      ()->calculator.calculate(t,List.of(),List.of()));
    var fixed=terms("ANNUITY","1000.00","0",
      "2027-01-01","2027-03-01",1,"ACT_360");
    var bad=fee("BAD","FIXED","WITHHELD","FIXED_AMOUNT",
      "10","ONCE","MATURITY_DATE");
    assertThrows(ResponseStatusException.class,
      ()->calculator.calculate(fixed,List.of(bad),List.of()));
  }
}
