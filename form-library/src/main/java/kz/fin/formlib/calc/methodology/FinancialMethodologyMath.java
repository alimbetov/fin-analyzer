package kz.fin.formlib.calc.methodology;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * CALC-154: safe, generic mathematical checks extracted from source research.
 * UAT arithmetic ONLY: no IFRS judgment, no payment matching, no financial S2.
 *
 * All DCF/EIR periods below are EQUAL annual intervals from t=1.
 * Irregular dated cash flows and valuation changes require another contract.
 */
public final class FinancialMethodologyMath {
  private FinancialMethodologyMath() {}
  private static final MathContext MC=MathContext.DECIMAL128;

  /** Difference in days, NEVER a monetary overdue balance. */
  public static long daysPastDue(LocalDate asOf, LocalDate effectiveDueDate) {
    Objects.requireNonNull(asOf,"ASOF_DATE_REQUIRED");
    Objects.requireNonNull(effectiveDueDate,"EFFECTIVE_DUE_DATE_REQUIRED");
    return Math.max(0,ChronoUnit.DAYS.between(effectiveDueDate,asOf));
  }

  /** Item level lower of cost and estimated NRV; estimate must be separately qualified. */
  public record InventoryNrv(BigDecimal nrv, BigDecimal recognizedCost,
      BigDecimal writeDown) {}
  public static InventoryNrv itemNrv(BigDecimal cost,BigDecimal sellingPrice,
      BigDecimal completionCost,BigDecimal sellingCost) {
    if(cost==null||sellingPrice==null||completionCost==null||sellingCost==null)
      throw new IllegalArgumentException("NRV_ITEM_NONNEGATIVE_INPUT_REQUIRED");
    for(var v:List.of(cost,sellingPrice,completionCost,sellingCost))
      if(v.signum()<0)
        throw new IllegalArgumentException("NRV_ITEM_NONNEGATIVE_INPUT_REQUIRED");
    // Inventory assets do not become negative: any separate onerous-contract
    // liability needs a different accounting recognition contract.
    BigDecimal nrv=sellingPrice.subtract(completionCost).subtract(sellingCost)
      .max(BigDecimal.ZERO);
    BigDecimal recognized=cost.min(nrv);
    return new InventoryNrv(nrv,recognized,cost.subtract(recognized));
  }

  /**
   * Solve the example fixed-interval EIR equation by deterministic bisection.
   * Not an IFRS 9 effective-interest engine for irregular, modified or floating loans.
   */
  public static BigDecimal annualEirSynthetic(BigDecimal initialNetCarrying,
      List<BigDecimal> futureAnnualCashflows) {
    if(initialNetCarrying==null||initialNetCarrying.signum()<=0
        ||futureAnnualCashflows==null||futureAnnualCashflows.isEmpty()
        ||futureAnnualCashflows.size()>120
        ||futureAnnualCashflows.stream().anyMatch(x->x==null||x.signum()<0))
      throw new IllegalArgumentException("EIR_SYNTHETIC_INPUT_INVALID");
    BigDecimal low=BigDecimal.ZERO;
    BigDecimal high=BigDecimal.ONE;
    if(pv(futureAnnualCashflows,low).compareTo(initialNetCarrying)<0
        ||pv(futureAnnualCashflows,high).compareTo(initialNetCarrying)>0)
      throw new IllegalStateException("EIR_SYNTHETIC_RATE_NOT_BRACKETED_0_TO_1");
    for(int i=0;i<180;i++){
      BigDecimal middle=low.add(high).divide(BigDecimal.valueOf(2),MC);
      if(pv(futureAnnualCashflows,middle).compareTo(initialNetCarrying)>0)
        low=middle;
      else high=middle;
    }
    return low.add(high).divide(BigDecimal.valueOf(2),MC)
      .setScale(12,RoundingMode.HALF_EVEN);
  }

  private static BigDecimal pv(List<BigDecimal> cashflows,BigDecimal rate) {
    BigDecimal base=BigDecimal.ONE.add(rate);
    BigDecimal result=BigDecimal.ZERO;
    for(int i=0;i<cashflows.size();i++)
      result=result.add(cashflows.get(i).divide(base.pow(i+1,MC),MC),MC);
    return result;
  }

  /** Correct DCF flow/rate/result pairing. */
  public enum DcfBasis {
    FCFF_WACC("FCFF","WACC","ENTERPRISE_VALUE"),
    FCFE_KE("FCFE","COST_OF_EQUITY","EQUITY_VALUE");
    final String flow,rate,result;
    DcfBasis(String flow,String rate,String result) {
      this.flow=flow;this.rate=rate;this.result=result;
    }
  }
  public record DcfValue(BigDecimal discountedValue,String resultBasis) {}
  public static DcfValue discountedAnnualValue(
      String inputBasis,String rateBasis,
      List<BigDecimal> annualFlows,BigDecimal rate,BigDecimal terminalValue) {
    DcfBasis variant=null;
    for(var v:DcfBasis.values())
      if(v.flow.equals(inputBasis)&&v.rate.equals(rateBasis))variant=v;
    if(variant==null)
      throw new IllegalStateException("DCF_FLOW_RATE_INCOMPATIBLE");
    if(annualFlows==null||annualFlows.isEmpty()||annualFlows.size()>120
        ||rate==null||rate.compareTo(BigDecimal.ONE.negate())<=0
        ||terminalValue==null||annualFlows.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("DCF_INPUTS_INVALID");
    BigDecimal base=BigDecimal.ONE.add(rate);
    BigDecimal value=BigDecimal.ZERO;
    for(int i=0;i<annualFlows.size();i++)
      value=value.add(annualFlows.get(i).divide(base.pow(i+1,MC),MC),MC);
    value=value.add(terminalValue.divide(base.pow(annualFlows.size(),MC),MC),MC);
    return new DcfValue(value,variant.result);
  }

  /** Contractual cash debt service, NOT EIR accounting accrual. */
  public static BigDecimal debtServiceCoverage(BigDecimal cfads,
      BigDecimal contractualCashInterest,BigDecimal principalDue) {
    if(cfads==null||contractualCashInterest==null||principalDue==null
        ||contractualCashInterest.signum()<0||principalDue.signum()<0)
      throw new IllegalArgumentException("DSCR_CONTRACTUAL_CASH_INPUTS_REQUIRED");
    BigDecimal denominator=contractualCashInterest.add(principalDue);
    if(denominator.signum()==0)
      throw new IllegalStateException("DSCR_UNDEFINED_ZERO_DEBT_SERVICE");
    return cfads.divide(denominator,12,RoundingMode.HALF_EVEN);
  }
}
