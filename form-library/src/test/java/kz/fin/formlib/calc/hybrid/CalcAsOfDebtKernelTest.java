package kz.fin.formlib.calc.hybrid;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CalcAsOfDebtKernelTest {
  private final UUID revision=UUID.randomUUID();
  private final String row1=UUID.randomUUID().toString(), row2=UUID.randomUUID().toString();
  private CalcAsOfDebtKernel.Cell c(String row,String field,String amount) {
    return new CalcAsOfDebtKernel.Cell(revision,row,field,
        amount==null?null:new BigDecimal(amount));
  }
  @Test void c01AggregatesGrossBeforeAllowanceAndOverdueIsNotAdded() {
    var got=CalcAsOfDebtKernel.calculate(CalcAsOfDebtKernel.Form.C01,List.of(
        c(row1,"gross","100.00"),c(row1,"allowance","10.00"),
        c(row1,"overdue","20.00"),c(row2,"gross","50.00"),
        c(row2,"allowance","5.00"),c(row2,"overdue","10.00")));
    assertEquals(0,got.primaryAmount().compareTo(new BigDecimal("135.00")));
    assertEquals(0,got.overdueAmount().compareTo(new BigDecimal("30.00")));
    assertEquals(0,got.overdueShare().compareTo(new BigDecimal("0.2")));
    assertEquals(2,got.sourceRowCount());
  }
  @Test void c02TotalsAndRatio() {
    var got=CalcAsOfDebtKernel.calculate(CalcAsOfDebtKernel.Form.C02,List.of(
        c(row1,"outstanding","60.00"),c(row1,"overdue","10.00"),
        c(row2,"outstanding","40.00"),c(row2,"overdue","10.00")));
    assertEquals(0,got.primaryAmount().compareTo(new BigDecimal("100.00")));
    assertEquals("PRESENT",got.overdueShareState());
    assertEquals(0,got.overdueShare().compareTo(new BigDecimal("0.2")));
  }
  @Test void missingOptionalOverdueDoesNotBlockPrimary() {
    var got=CalcAsOfDebtKernel.calculate(CalcAsOfDebtKernel.Form.C01,List.of(
        c(row1,"gross","100"),c(row1,"allowance","10")));
    assertEquals(0,got.primaryAmount().compareTo(new BigDecimal("90")));
    assertEquals("MISSING_OPTIONAL_INPUT",got.overdueShareState());
    assertNull(got.overdueShare());
  }
  @Test void zeroDenominatorIsUndefinedNotZero() {
    var got=CalcAsOfDebtKernel.calculate(CalcAsOfDebtKernel.Form.C02,List.of(
        c(row1,"outstanding","0"),c(row1,"overdue","0")));
    assertEquals("UNDEFINED_ZERO_DENOMINATOR",got.overdueShareState());
    assertNull(got.overdueShare());
  }
  @Test void missingRequiredAndDuplicatesFailClosed() {
    assertThrows(IllegalStateException.class,()->CalcAsOfDebtKernel.calculate(
        CalcAsOfDebtKernel.Form.C01,List.of(c(row1,"gross","10"))));
    assertThrows(IllegalStateException.class,()->CalcAsOfDebtKernel.calculate(
        CalcAsOfDebtKernel.Form.C02,List.of(
            c(row1,"outstanding","10"),c(row1,"outstanding","10"))));
  }
  @Test void negativeOverdueOutOfRangeAndUnapprovedAllowancePolicyFail() {
    assertThrows(IllegalStateException.class,()->CalcAsOfDebtKernel.calculate(
        CalcAsOfDebtKernel.Form.C02,List.of(c(row1,"outstanding","-10"))));
    assertThrows(IllegalStateException.class,()->CalcAsOfDebtKernel.calculate(
        CalcAsOfDebtKernel.Form.C01,List.of(
            c(row1,"gross","10"),c(row1,"allowance","0"),c(row1,"overdue","11"))));
    assertThrows(IllegalStateException.class,()->CalcAsOfDebtKernel.calculate(
        CalcAsOfDebtKernel.Form.C01,List.of(
            c(row1,"gross","10"),c(row1,"allowance","20"))));
  }
  @Test void multipleRevisionsAndNonUuidRowIdsFailClosed() {
    var other=new CalcAsOfDebtKernel.Cell(UUID.randomUUID(),row2,"outstanding",
        new BigDecimal("10"));
    assertThrows(IllegalStateException.class,()->CalcAsOfDebtKernel.calculate(
        CalcAsOfDebtKernel.Form.C02,List.of(c(row1,"outstanding","10"),other)));
    assertThrows(IllegalArgumentException.class,()->CalcAsOfDebtKernel.calculate(
        CalcAsOfDebtKernel.Form.C02,List.of(c("SINGLE","outstanding","10"))));
  }
  @Test void c01ExcessAllowanceIsReviewNotAutomaticWriteOffOrClipping() {
    var ex=assertThrows(IllegalStateException.class,
      ()->CalcAsOfDebtKernel.calculate(CalcAsOfDebtKernel.Form.C01,List.of(
        c(row1,"gross","100.00"),c(row1,"allowance","120.00"),
        c(row1,"overdue","20.00"))));
    assertEquals("ASOF_ALLOWANCE_EXCEEDS_GROSS_REVIEW_REQUIRED",ex.getMessage());
  }
  @Test void c02CreditBalanceRequiresSeparateClassificationAndNoSilentAbs() {
    var ex=assertThrows(IllegalStateException.class,
      ()->CalcAsOfDebtKernel.calculate(CalcAsOfDebtKernel.Form.C02,List.of(
        c(row1,"outstanding","-25.00"),c(row1,"overdue","0.00"))));
    assertEquals("ASOF_CREDIT_BALANCE_CLASSIFICATION_REVIEW_REQUIRED",ex.getMessage());
  }
  @Test void c02OverdueCannotBeAddedOrExceedOpenBalanceInPilot() {
    var ex=assertThrows(IllegalStateException.class,
      ()->CalcAsOfDebtKernel.calculate(CalcAsOfDebtKernel.Form.C02,List.of(
        c(row1,"outstanding","70.00"),c(row1,"overdue","90.00"))));
    assertEquals("ASOF_OVERDUE_EXCEEDS_OPEN_BALANCE_REVIEW_REQUIRED",ex.getMessage());
  }
  @Test void partialPaymentMustAlreadyBeReflectedInAsOfSourceBalance() {
    // The kernel accepts the posted open balance of 70, not original invoice
    // face value 100 and does not apply the 30 payment a second time.
    var result=CalcAsOfDebtKernel.calculate(CalcAsOfDebtKernel.Form.C01,List.of(
      c(row1,"gross","70.00"),c(row1,"allowance","7.00"),
      c(row1,"overdue","25.00")));
    assertEquals(0,result.primaryAmount().compareTo(new BigDecimal("63.00")));
    assertEquals(0,result.overdueAmount().compareTo(new BigDecimal("25.00")));
  }

}
