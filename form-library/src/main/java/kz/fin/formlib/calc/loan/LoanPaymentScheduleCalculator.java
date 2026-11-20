package kz.fin.formlib.calc.loan;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A05-R1.1: dated contractual cash schedule, NOT EIR/GESV, actual payment
 * allocation, covenant DSCR or a new financial calculation platform.
 * Uses the pinned A05 typed contract as a domain-specific calculation node.
 *
 * ANNUITY is solved from actual dated simple-accrual intervals, NOT nominal/12.
 * The final residual is paid exactly; no negative amortization is permitted.
 */
@Component
public final class LoanPaymentScheduleCalculator {
  public static final String ENGINE_ID="FIN-A05-CONTRACT-CASH-DATED-R11-V1";
  private static final MathContext MC=MathContext.DECIMAL128;
  private static final BigDecimal HUNDRED=new BigDecimal("100");
  private static final BigDecimal ZERO=BigDecimal.ZERO;
  private static final BigDecimal ONE=BigDecimal.ONE;

  public record CustomInstallment(LocalDate dueDate,BigDecimal principalDue) {}

  public record Funding(BigDecimal drawdown,BigDecimal financedFees,
      BigDecimal withheldFees,BigDecimal upfrontPaidFees,
      BigDecimal netCashProceeds,BigDecimal openingLiability) {}

  public record ScheduleLine(int installmentNo,LocalDate accrualFrom,
      LocalDate dueAt,BigDecimal yearFractionRaw,BigDecimal openingPrincipal,
      BigDecimal contractualInterestRaw,BigDecimal contractualInterestDue,
      BigDecimal principalDue,BigDecimal feesDue,BigDecimal closingPrincipal,
      BigDecimal totalCashDue,Map<String,BigDecimal> feeBreakdown) {
    public ScheduleLine {feeBreakdown=Map.copyOf(feeBreakdown);}
  }

  public record Result(String repaymentMethod,String currency,
      String dayCountConvention,int roundingScale,String roundingMode,
      Funding funding,List<ScheduleLine> lines,BigDecimal totalPrincipal,
      BigDecimal totalContractualInterest,BigDecimal totalScheduledFees,
      BigDecimal totalScheduledCash,BigDecimal finalBalance,
      List<String> diagnostics) {
    public Result {
      lines=List.copyOf(lines);
      diagnostics=List.copyOf(diagnostics);
    }
  }

  private static ResponseStatusException reject(String reason) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,reason);
  }

  private static void check(boolean condition,String reason){
    if(!condition)throw reject(reason);
  }

  private static BigDecimal money(BigDecimal value,int scale,RoundingMode rounding){
    BigDecimal rounded=value.setScale(scale,rounding);
    check(rounded.precision()<=38,"A05_R11_MONEY_OVERFLOW");
    return rounded;
  }

  private static RoundingMode rounding(String mode) {
    return switch(mode){
      case "HALF_EVEN" -> RoundingMode.HALF_EVEN;
      case "HALF_UP" -> RoundingMode.HALF_UP;
      default -> throw reject("A05_R11_ROUNDING_POLICY_NOT_SUPPORTED");
    };
  }

  /** Each interval is (previous accrual boundary, current due date]. */
  static BigDecimal fraction(LocalDate start,LocalDate end,String rule){
    check(start!=null&&end!=null&&end.isAfter(start),"A05_R11_INVALID_ACCRUAL_INTERVAL");
    long days=switch(rule){
      case "ACT_365_FIXED","ACT_360" -> ChronoUnit.DAYS.between(start,end);
      case "THIRTY_E_360" -> (end.getYear()-start.getYear())*360L
        +(end.getMonthValue()-start.getMonthValue())*30L
        +Math.min(end.getDayOfMonth(),30)-Math.min(start.getDayOfMonth(),30);
      default -> throw reject("A05_R11_UNSUPPORTED_DAY_COUNT");
    };
    check(days>0,"A05_R11_NON_POSITIVE_DAY_COUNT");
    return BigDecimal.valueOf(days).divide(
      BigDecimal.valueOf("ACT_365_FIXED".equals(rule)?365L:360L),MC);
  }

  private static List<LocalDate> dueDates(LoanContractIntakeService.Terms t,
      List<CustomInstallment> custom){
    if("CUSTOM".equals(t.repaymentMethod())){
      check(custom!=null&&!custom.isEmpty()&&custom.size()<=600,
        "A05_R11_CUSTOM_INSTALLMENTS_REQUIRED");
      LocalDate prev=t.accrualStartDate();
      for(var entry:custom){
        check(entry!=null&&entry.dueDate()!=null
          &&entry.dueDate().isAfter(prev)
          &&!entry.dueDate().isAfter(t.maturityDate())
          &&entry.principalDue()!=null&&entry.principalDue().signum()>=0,
          "A05_R11_CUSTOM_DATE_OR_PRINCIPAL_INVALID");
        prev=entry.dueDate();
      }
      check(prev.equals(t.maturityDate()),"A05_R11_CUSTOM_MUST_END_AT_MATURITY");
      return custom.stream().map(CustomInstallment::dueDate).toList();
    }
    check(custom==null||custom.isEmpty(),"A05_R11_CUSTOM_NOT_ALLOWED_FOR_VARIANT");
    check("MONTHLY".equals(t.paymentFrequency())
      &&"NONE".equals(t.businessDayAdjustment()),"A05_R11_CALENDAR_POLICY_UNSUPPORTED");
    var dates=new ArrayList<LocalDate>();
    LocalDate next=t.accrualStartDate().withDayOfMonth(t.dueDay());
    if(!next.isAfter(t.accrualStartDate()))next=next.plusMonths(1);
    while(next.isBefore(t.maturityDate())){
      check(dates.size()<599,"A05_R11_TOO_MANY_INSTALLMENTS");
      dates.add(next);
      next=next.plusMonths(1);
    }
    dates.add(t.maturityDate());
    return List.copyOf(dates);
  }

  private static BigDecimal feeAmount(LoanContractIntakeService.Fee rule,
      BigDecimal drawdown,BigDecimal outstanding,int scale,RoundingMode rounding){
    BigDecimal basis=switch(rule.calculationBasis()){
      case "FIXED_AMOUNT" -> ONE;
      case "DISBURSEMENT","ORIGINAL_PRINCIPAL","LIMIT" -> drawdown;
      case "OUTSTANDING" -> outstanding;
      default -> throw reject("A05_R11_UNSUPPORTED_FEE_BASIS");
    };
    BigDecimal value="FIXED_AMOUNT".equals(rule.calculationBasis())
      ?rule.amountOrRate()
      :basis.multiply(rule.amountOrRate().divide(HUNDRED,MC),MC);
    return money(value,scale,rounding);
  }

  private static void feePolicy(LoanContractIntakeService.Fee fee){
    boolean upfront=Set.of("WITHHELD","UPFRONT_PAID","FINANCED")
      .contains(fee.feeSettlement());
    boolean recurring="RECURRING".equals(fee.feeSettlement());
    check((upfront&&"DISBURSEMENT_DATE".equals(fee.eventDateRule())
      &&"ONCE".equals(fee.frequency()))
      ||(recurring&&"SCHEDULE_DUE_DATE".equals(fee.eventDateRule())
        &&"MONTHLY".equals(fee.frequency()))
      ||("AT_MATURITY".equals(fee.feeSettlement())
        &&"MATURITY_DATE".equals(fee.eventDateRule())
        &&"ONCE".equals(fee.frequency())),
      "A05_R11_FEE_DATE_AND_SETTLEMENT_INCOMPATIBLE");
  }

  /**
   * Deterministic pure function for a pinned typed source revision. CUSTOM
   * principal amounts are an explicit immutable scenario input (not invented
   * from C05); service hashes/stores them with the schedule result.
   */
  public Result calculate(LoanContractIntakeService.Terms t,
      List<LoanContractIntakeService.Fee> fees,
      List<CustomInstallment> custom) {
    check(t!=null&&fees!=null&&custom!=null,"A05_R11_INPUT_REQUIRED");
    check("BORROWER".equals(t.perspective())
      &&"KZ".equals(t.jurisdiction())
      &&"FIXED".equals(t.rateKind())
      &&"SIMPLE".equals(t.interestCompounding())
      &&"MONTHLY".equals(t.paymentFrequency())
      &&"NONE".equals(t.businessDayAdjustment())
      &&"ADJUST_LAST_PAYMENT".equals(t.finalResidualRule()),
      "A05_R11_METHOD_UNSUPPORTED");
    check(t.principal()!=null&&t.principal().signum()>0
      &&t.nominalAnnualRatePct()!=null
      &&t.nominalAnnualRatePct().signum()>=0
      &&t.nominalAnnualRatePct().compareTo(new BigDecimal("500"))<=0,
      "A05_R11_PRINCIPAL_OR_RATE_INVALID");
    check(t.roundingScale()>=0&&t.roundingScale()<=2,
      "A05_R11_ROUNDING_SCALE_INVALID");
    var rounding=rounding(t.roundingMode());
    int scale=t.roundingScale();
    BigDecimal drawdown=money(t.principal(),scale,rounding);
    List<LocalDate> due=dueDates(t,custom);
    check(due.size()<=600,"A05_R11_TOO_MANY_INSTALLMENTS");

    BigDecimal withheld=money(ZERO,scale,rounding);
    BigDecimal upfrontPaid=money(ZERO,scale,rounding);
    BigDecimal financed=money(ZERO,scale,rounding);
    var seen=new java.util.HashSet<String>();
    for(var fee:fees) {
      check(fee!=null&&fee.feeCode()!=null&&seen.add(fee.feeCode()),
        "A05_R11_DUPLICATE_FEE_CODE");
      feePolicy(fee);
      if(Set.of("WITHHELD","UPFRONT_PAID","FINANCED").contains(fee.feeSettlement())){
        // PERCENT_OF_OUTSTANDING uses the original principal at drawdown,
        // not circularly including other financed fees.
        BigDecimal value=feeAmount(fee,drawdown,drawdown,scale,rounding);
        switch(fee.feeSettlement()){
          case "WITHHELD" -> withheld=withheld.add(value);
          case "UPFRONT_PAID" -> upfrontPaid=upfrontPaid.add(value);
          case "FINANCED" -> financed=financed.add(value);
          default -> throw new IllegalStateException();
        }
      }
    }
    BigDecimal opening=drawdown.add(financed);
    BigDecimal netProceeds=drawdown.subtract(withheld).subtract(upfrontPaid);
    check(netProceeds.signum()>=0,"A05_R11_NEGATIVE_NET_PROCEEDS");
    check(opening.precision()<=38,"A05_R11_FINANCED_PRINCIPAL_OVERFLOW");

    BigDecimal rate=t.nominalAnnualRatePct().divide(HUNDRED,MC);
    BigDecimal annuityPayment=ZERO;
    if("ANNUITY".equals(t.repaymentMethod())){
      BigDecimal discount=ONE,sum=ZERO;
      LocalDate previous=t.accrualStartDate();
      for(var date:due){
        var year=fraction(previous,date,t.dayCountConvention());
        discount=discount.divide(ONE.add(rate.multiply(year,MC)),MC);
        sum=sum.add(discount,MC);
        previous=date;
      }
      check(sum.signum()>0,"A05_R11_ANNUITY_PV_FACTOR_INVALID");
      annuityPayment=money(opening.divide(sum,MC),scale,rounding);
    }

    BigDecimal openingBalance=opening;
    BigDecimal totalPrincipal=ZERO,totalInterest=ZERO,totalFees=ZERO,totalCash=ZERO;
    LocalDate from=t.accrualStartDate();
    var lines=new ArrayList<ScheduleLine>();
    var diagnostics=new ArrayList<String>();
    BigDecimal equal=money(opening.divide(BigDecimal.valueOf(due.size()),MC),scale,rounding);
    for(int index=0;index<due.size();index++){
      LocalDate date=due.get(index);
      boolean last=index==due.size()-1;
      BigDecimal fraction=fraction(from,date,t.dayCountConvention());
      BigDecimal interestRaw=openingBalance.multiply(rate,MC).multiply(fraction,MC);
      BigDecimal interest=money(interestRaw,scale,rounding);
      BigDecimal principal;
      if("CUSTOM".equals(t.repaymentMethod())){
        principal=custom.get(index).principalDue();
        check(principal.scale()<=scale&&principal.precision()<=38,
          "A05_R11_CUSTOM_MONEY_SCALE_INVALID");
        principal=money(principal,scale,rounding);
        // No hidden final adjustment for CUSTOM. Explicit terms control it.
      }else if(last){
        principal=openingBalance;
      }else if("EQUAL_PRINCIPAL".equals(t.repaymentMethod())){
        principal=equal;
      }else{
        principal=annuityPayment.subtract(interest);
      }
      check(principal.signum()>=0&&principal.compareTo(openingBalance)<=0,
        "A05_R11_NEGATIVE_AMORTIZATION_OR_OVERPAYMENT");
      if(!last&&!"CUSTOM".equals(t.repaymentMethod()))
        check(principal.signum()>0,"A05_R11_NON_AMORTIZING_PAYMENT");
      BigDecimal closing=openingBalance.subtract(principal);
      var breakdown=new LinkedHashMap<String,BigDecimal>();
      for(var fee:fees){
        boolean charge="RECURRING".equals(fee.feeSettlement())
          ||(last&&"AT_MATURITY".equals(fee.feeSettlement()));
        if(charge){
          BigDecimal value=feeAmount(fee,drawdown,openingBalance,scale,rounding);
          if(value.signum()!=0)breakdown.put(fee.feeCode(),value);
        }
      }
      BigDecimal lineFees=breakdown.values().stream()
        .reduce(ZERO,BigDecimal::add);
      BigDecimal cash=principal.add(interest).add(lineFees);
      lines.add(new ScheduleLine(index+1,from,date,fraction,openingBalance,
        interestRaw,interest,principal,lineFees,closing,cash,breakdown));
      totalPrincipal=totalPrincipal.add(principal);
      totalInterest=totalInterest.add(interest);
      totalFees=totalFees.add(lineFees);
      totalCash=totalCash.add(cash);
      from=date;
      openingBalance=closing;
    }
    check(openingBalance.signum()==0&&totalPrincipal.compareTo(opening)==0,
      "A05_R11_PRINCIPAL_RECONCILIATION_FAILED");
    check(totalPrincipal.add(totalInterest).add(totalFees).compareTo(totalCash)==0,
      "A05_R11_CASH_RECONCILIATION_FAILED");
    if(withheld.signum()>0)diagnostics.add("WITHHELD_REDUCES_NET_DISBURSEMENT");
    if(financed.signum()>0)diagnostics.add("FINANCED_FEE_INCREASES_PRINCIPAL");
    if(upfrontPaid.signum()>0)diagnostics.add("UPFRONT_FEE_SEPARATE_FROM_SCHEDULE");
    return new Result(t.repaymentMethod(),"KZT",t.dayCountConvention(),
      scale,t.roundingMode(),
      new Funding(drawdown,financed,withheld,upfrontPaid,netProceeds,opening),
      lines,totalPrincipal,totalInterest,totalFees,totalCash,openingBalance,
      diagnostics);
  }
}
