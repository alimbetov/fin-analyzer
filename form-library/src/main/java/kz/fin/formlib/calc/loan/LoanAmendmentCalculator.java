package kz.fin.formlib.calc.loan;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import kz.fin.formlib.calc.loan.LoanPaymentScheduleCalculator.ScheduleLine;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A05-R1.2: deterministic amendment of an immutable A05-R1.1 contractual
 * cash projection. No mutation of C05 or historical schedule, no payment
 * allocation/factual early repayment, EIR or KZ GESV.
 *
 * Contractual scenarios supported: PREPAYMENT on an exact due date, rate STEP
 * change on an arbitrary date (split accrual), principal/full capitalizing
 * grace over a bounded set of due dates. Indexed floating rates are rejected
 * until a separately pinned index/fixing feed contract exists.
 */
@Component
public final class LoanAmendmentCalculator {
  public static final String ENGINE_ID="FIN-A05-CONTRACT-AMEND-R12-V1";
  private static final MathContext MC=MathContext.DECIMAL128;
  private static final BigDecimal ZERO=BigDecimal.ZERO;
  private static final BigDecimal ONE=BigDecimal.ONE;
  private static final BigDecimal HUNDRED=new BigDecimal("100");

  public record Event(String kind,LocalDate effectiveDate,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal prepaymentAmount,
      String prepaymentPolicy,LocalDate graceThroughDate,String graceMode,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal revisedAnnualRatePct) {}

  public record Line(int installmentNo,LocalDate accrualFrom,LocalDate dueAt,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal openingPrincipal,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal annualRatePct,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal contractualInterestRaw,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal cashInterestDue,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal capitalizedInterest,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal scheduledPrincipal,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal extraPrincipal,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal feesDue,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal closingPrincipal,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalCashDue,
      @JsonSerialize(contentUsing=ToStringSerializer.class)
      Map<String,BigDecimal> feeBreakdown) {
    public Line { feeBreakdown=Map.copyOf(feeBreakdown); }
  }
  public record Result(String method,LocalDate actualMaturityDate,
      List<Line> lines,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal originalOpeningPrincipal,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalScheduledPrincipal,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalExtraPrincipal,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalCapitalizedInterest,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalCashInterest,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalScheduledFees,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalCashDue,
      @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal finalPrincipal,
      String state) {
    public Result{lines=List.copyOf(lines);}
  }
  private static void check(boolean ok,String code){
    if(!ok)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  private static BigDecimal money(BigDecimal n,int scale,RoundingMode round){
    return LoanPaymentScheduleCalculator.money(n,scale,round);
  }
  private static BigDecimal factor(LocalDate start,LocalDate end,BigDecimal rate,
      String dayCount,Event event) {
    LocalDate split=event.effectiveDate();
    BigDecimal before=rate.divide(HUNDRED,MC);
    if(!"RATE_STEP".equals(event.kind()))
      return ONE.add(before.multiply(LoanPaymentScheduleCalculator.fraction(
        start,end,dayCount),MC),MC);
    BigDecimal after=event.revisedAnnualRatePct().divide(HUNDRED,MC);
    if(!split.isAfter(start))
      return ONE.add(after.multiply(LoanPaymentScheduleCalculator.fraction(
        start,end,dayCount),MC),MC);
    if(!split.isBefore(end))
      return ONE.add(before.multiply(LoanPaymentScheduleCalculator.fraction(
        start,end,dayCount),MC),MC);
    return ONE.add(before.multiply(LoanPaymentScheduleCalculator.fraction(
      start,split,dayCount),MC).add(
      after.multiply(LoanPaymentScheduleCalculator.fraction(split,end,dayCount),MC),
      MC),MC);
  }
  private static BigDecimal annuity(BigDecimal principal,
      List<LocalDate> dates,int fromIndex,LocalDate fromDate,
      LoanContractIntakeService.Terms t,Event event,int scale,RoundingMode round) {
    BigDecimal discount=ONE,total=ZERO;
    LocalDate previous=fromDate;
    for(int i=fromIndex;i<dates.size();i++){
      discount=discount.divide(factor(previous,dates.get(i),
        t.nominalAnnualRatePct(),t.dayCountConvention(),event),MC);
      total=total.add(discount,MC);
      previous=dates.get(i);
    }
    check(total.signum()>0,"A05_R12_ANNUITY_PV_INVALID");
    return money(principal.divide(total,MC),scale,round);
  }
  public static void validate(LoanContractIntakeService.Terms t,Event event,
      List<LocalDate> dates) {
    check(t!=null&&event!=null&&event.effectiveDate()!=null
        &&dates!=null&&!dates.isEmpty()
        &&event.effectiveDate().isAfter(t.accrualStartDate())
        &&!event.effectiveDate().isAfter(t.maturityDate()),
        "A05_R12_EFFECTIVE_DATE_INVALID");
    check(Set.of("ANNUITY","EQUAL_PRINCIPAL").contains(t.repaymentMethod()),
      "A05_R12_CUSTOM_REBASE_REQUIRES_EXPLICIT_POLICY");
    check("FIXED".equals(t.rateKind())&&"SIMPLE".equals(t.interestCompounding())
        &&"NONE".equals(t.businessDayAdjustment()),
      "A05_R12_BASE_POLICY_UNSUPPORTED");
    switch(event.kind()==null?"":event.kind()){
      case "PREPAYMENT" -> {
        check(dates.contains(event.effectiveDate())
          &&event.prepaymentAmount()!=null
          &&event.prepaymentAmount().signum()>0
          &&event.prepaymentAmount().scale()<=t.roundingScale()
          &&Set.of("REDUCE_TERM","REDUCE_INSTALLMENT")
            .contains(event.prepaymentPolicy())
          &&event.graceThroughDate()==null&&event.graceMode()==null
          &&event.revisedAnnualRatePct()==null,
          "A05_R12_PREPAYMENT_POLICY_INVALID");
      }
      case "GRACE" -> {
        check(dates.contains(event.effectiveDate())
          &&event.graceThroughDate()!=null
          &&!event.graceThroughDate().isBefore(event.effectiveDate())
          &&event.graceThroughDate().isBefore(t.maturityDate())
          &&dates.contains(event.graceThroughDate())
          &&Set.of("PRINCIPAL_ONLY","FULL_CAPITALIZE")
            .contains(event.graceMode())
          &&event.prepaymentAmount()==null&&event.prepaymentPolicy()==null
          &&event.revisedAnnualRatePct()==null,
          "A05_R12_GRACE_POLICY_INVALID");
      }
      case "RATE_STEP" -> {
        check(event.revisedAnnualRatePct()!=null
          &&event.revisedAnnualRatePct().signum()>=0
          &&event.revisedAnnualRatePct().compareTo(new BigDecimal("500"))<=0
          &&event.revisedAnnualRatePct().scale()<=10
          &&event.prepaymentAmount()==null&&event.prepaymentPolicy()==null
          &&event.graceThroughDate()==null&&event.graceMode()==null,
          "A05_R12_RATE_STEP_INVALID");
      }
      default -> throw new org.springframework.web.server.ResponseStatusException(
        HttpStatus.UNPROCESSABLE_ENTITY,"A05_R12_FLOATING_INDEX_OR_EVENT_NOT_SUPPORTED");
    }
  }

  /**
   * Past rows are copied from pinned parent until the first affected due
   * date, future rows recomputed. No retroactive overwrite. A reset splitting
   * an accrual interval recalculates the containing coupon's entire interest
   * with both dated rate segments.
   */
  public Result calculate(LoanContractIntakeService.Terms t,
      List<LoanContractIntakeService.Fee> fees,
      LoanPaymentScheduleCalculator.Result baseline,Event event){
    check(t!=null&&baseline!=null&&fees!=null
      &&baseline.lines()!=null&&!baseline.lines().isEmpty(),
      "A05_R12_BASE_SCHEDULE_REQUIRED");
    var dates=baseline.lines().stream().map(ScheduleLine::dueAt).toList();
    validate(t,event,dates);
    check("ANNUITY".equals(baseline.repaymentMethod())
      ||"EQUAL_PRINCIPAL".equals(baseline.repaymentMethod()),
      "A05_R12_BASE_METHOD_NOT_SUPPORTED");
    check(baseline.lines().getLast().closingPrincipal().signum()==0,
      "A05_R12_BASE_NOT_RECONCILED");
    int scale=t.roundingScale();
    RoundingMode round=LoanPaymentScheduleCalculator.rounding(t.roundingMode());
    BigDecimal opening=baseline.funding().openingLiability();
    BigDecimal balance=opening,regularPayment=ZERO;
    BigDecimal duePrincipal=ZERO,extraPrincipal=ZERO,capitalized=ZERO;
    BigDecimal cashInterest=ZERO,feesCash=ZERO,cashTotal=ZERO;
    List<Line> lines=new ArrayList<>();
    LocalDate previous=t.accrualStartDate();
    boolean recalcNeeded=false;
    boolean reduceTerm="PREPAYMENT".equals(event.kind())
      &&"REDUCE_TERM".equals(event.prepaymentPolicy());
    // Initial regular contractual ANNUITY amount stays fixed under
    // REDUCE_TERM until early maturity, or recalculates under other events.
    if("ANNUITY".equals(t.repaymentMethod()))
      regularPayment=baseline.lines().getFirst().principalDue()
        .add(baseline.lines().getFirst().contractualInterestDue());
    int changedAt=-1;
    for(int i=0;i<dates.size();i++){
      if(!dates.get(i).isBefore(event.effectiveDate())){changedAt=i;break;}
    }
    check(changedAt>=0,"A05_R12_EFFECTIVE_AFTER_MATURITY");
    for(int i=0;i<dates.size();i++){
      LocalDate due=dates.get(i);
      ScheduleLine parent=baseline.lines().get(i);
      boolean changed=i>=changedAt;
      boolean grace="GRACE".equals(event.kind())
        &&!due.isBefore(event.effectiveDate())
        &&!due.isAfter(event.graceThroughDate());
      boolean prepayNow="PREPAYMENT".equals(event.kind())
        &&due.equals(event.effectiveDate());
      BigDecimal rate="RATE_STEP".equals(event.kind())
        &&!due.isBefore(event.effectiveDate())
        ?event.revisedAnnualRatePct():t.nominalAnnualRatePct();
      BigDecimal interestRaw,interest,cap=ZERO,scheduled,extra=ZERO;
      Map<String,BigDecimal> breakdown=new LinkedHashMap<>();
      BigDecimal feeDue;
      if(!changed){
        check(parent.openingPrincipal().compareTo(balance)==0,
          "A05_R12_BASELINE_HISTORY_DRIFT");
        interestRaw=parent.contractualInterestRaw();
        interest=parent.contractualInterestDue();
        scheduled=parent.principalDue();
        feeDue=parent.feesDue();
        breakdown.putAll(parent.feeBreakdown());
      }else {
        BigDecimal factor=factor(previous,due,t.nominalAnnualRatePct(),
          t.dayCountConvention(),event);
        interestRaw=balance.multiply(factor.subtract(ONE),MC);
        interest=money(interestRaw,scale,round);
        if(grace){
          scheduled=ZERO;
          if("FULL_CAPITALIZE".equals(event.graceMode())){
            cap=interest;
            interest=ZERO;
          }
          recalcNeeded=true;
        }else {
          boolean recalcNow=recalcNeeded
            ||("RATE_STEP".equals(event.kind())&&i==changedAt);
          if("ANNUITY".equals(t.repaymentMethod())&&recalcNow&&!reduceTerm)
            regularPayment=annuity(balance,dates,i,previous,t,event,scale,round);
          if("ANNUITY".equals(t.repaymentMethod())){
            scheduled=regularPayment.subtract(interest);
          }else{
            scheduled=money(balance.divide(
              BigDecimal.valueOf(dates.size()-i),MC),scale,round);
          }
          recalcNeeded=false;
        }
        if(i==dates.size()-1)scheduled=balance.add(cap);
        if(scheduled.signum()>0&&scheduled.compareTo(balance.add(cap))>0)
          scheduled=balance.add(cap);
        check(scheduled.signum()>=0,
          "A05_R12_NEGATIVE_AMORTIZATION_NOT_SUPPORTED");
        for(var fee:fees){
          boolean charge="RECURRING".equals(fee.feeSettlement())
            ||(i==dates.size()-1&&"AT_MATURITY".equals(fee.feeSettlement()));
          if(charge){
            BigDecimal amount=LoanPaymentScheduleCalculator.feeAmount(
              fee,baseline.funding().drawdown(),balance,scale,round);
            if(amount.signum()!=0)breakdown.put(fee.feeCode(),amount);
          }
        }
        feeDue=breakdown.values().stream().reduce(ZERO,BigDecimal::add);
        if(prepayNow){
          BigDecimal remaining=balance.add(cap).subtract(scheduled);
          check(event.prepaymentAmount().compareTo(remaining)<=0,
            "A05_R12_PREPAYMENT_EXCEEDS_REMAINING_PRINCIPAL");
          extra=event.prepaymentAmount();
          recalcNeeded=!reduceTerm;
        }
      }
      BigDecimal close=balance.add(cap).subtract(scheduled).subtract(extra);
      check(close.signum()>=0,
        "A05_R12_NEGATIVE_CLOSING_PRINCIPAL");
      BigDecimal dueCash=scheduled.add(extra).add(interest).add(feeDue);
      lines.add(new Line(i+1,previous,due,balance,rate,
        interestRaw.setScale(12,RoundingMode.HALF_EVEN),
        interest,cap,scheduled,extra,feeDue,close,dueCash,breakdown));
      duePrincipal=duePrincipal.add(scheduled);
      extraPrincipal=extraPrincipal.add(extra);
      capitalized=capitalized.add(cap);
      cashInterest=cashInterest.add(interest);
      feesCash=feesCash.add(feeDue);
      cashTotal=cashTotal.add(dueCash);
      balance=close;
      previous=due;
      if(balance.signum()==0) {
        // Early closure is legal only for prepayment reducing term. Other
        // variants retain the contractual maturity unless paid on last due.
        check(i==dates.size()-1||"PREPAYMENT".equals(event.kind()),
          "A05_R12_UNEXPECTED_EARLY_MATURITY");
        break;
      }
    }
    check(balance.signum()==0,"A05_R12_UNREPAID_PRINCIPAL");
    check(opening.add(capitalized).compareTo(
      duePrincipal.add(extraPrincipal))==0,"A05_R12_PRINCIPAL_RECONCILIATION");
    check(cashTotal.compareTo(
      duePrincipal.add(extraPrincipal).add(cashInterest).add(feesCash))==0,
      "A05_R12_CASH_RECONCILIATION");
    return new Result(t.repaymentMethod(),lines.getLast().dueAt(),lines,
      opening,duePrincipal,extraPrincipal,capitalized,cashInterest,feesCash,
      cashTotal,balance,"UAT_SCENARIO_NOT_PAYMENT_FACT");
  }
}
