package kz.fin.formlib.calc.kernel;

import kz.fin.formlib.calc.fact.FactDigest;
import kz.fin.formlib.calc.plan.F02CalcPlanDescriptor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CALC-R0.2 pure F02 TEST_ONLY BigDecimal kernel. No database, Spring,
 * floating point, clocks or mutable static state.
 *
 * Input: 3 pinned positive cost/revenue magnitudes in KZT, monthly. Output:
 * money (scale=2), margin ratio (scale=8), HALF_EVEN. If revenue is 0,
 * grossMargin has explicit INVALID/DIVISION_BY_ZERO; other outputs remain
 * computable. The synthetic policy is not a financial S2 approval.
 */
public final class F02CalculationKernel {
  public static final String CONTRACT="FIN-CALC-R02-F02-KERNEL-V1";
  private static final Set<String> REQUIRED=Set.of(
      "F02.netRevenue","F02.cogs","F02.operatingExpenses");

  public record NodeValue(String state,String unit,String value,
      String errorCode,String valueSha256) {
    public NodeValue {
      if(!"PRESENT".equals(state)&&!"INVALID".equals(state))
        throw new IllegalArgumentException("CALC_R02_VALUE_STATE_INVALID");
      if("PRESENT".equals(state) && (value==null||errorCode!=null)
          ||"INVALID".equals(state) && (value!=null||errorCode==null))
        throw new IllegalArgumentException("CALC_R02_VALUE_PAYLOAD_INVALID");
      FactDigest.requireSha(valueSha256,"CALC_R02_NODE_SHA_INVALID");
    }
  }
  public record Result(Map<String,NodeValue> nodes,String outputValuesSha256) {
    public Result {
      if(nodes==null||nodes.size()!=3
          ||!nodes.keySet().equals(Set.of("grossProfit","grossMargin","operatingResult")))
        throw new IllegalArgumentException("CALC_R02_OUTPUT_SHAPE_INVALID");
      nodes=java.util.Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
      FactDigest.requireSha(outputValuesSha256,"CALC_R02_OUTPUT_SHA_INVALID");
    }
  }

  public Result evaluate(Map<String,BigDecimal> observations) {
    if(observations==null||!observations.keySet().equals(REQUIRED))
      throw new IllegalArgumentException("CALC_R02_EXACT_INPUTS_REQUIRED");
    BigDecimal revenue=money(observations.get("F02.netRevenue"));
    BigDecimal cogs=money(observations.get("F02.cogs"));
    BigDecimal opex=money(observations.get("F02.operatingExpenses"));

    BigDecimal profit=revenue.subtract(cogs).setScale(2,RoundingMode.HALF_EVEN);
    BigDecimal operating=profit.subtract(opex).setScale(2,RoundingMode.HALF_EVEN);
    return fromCalculated(profit,
        revenue.signum()==0?null:profit.divide(revenue,8,RoundingMode.HALF_EVEN),
        operating);
  }

  /**
   * Canonical F02 value/SHA encoder shared with the native SQL adapter.
   * Restricted to the kernel package. SQL calculations remain untrusted
   * until verified by an independent Java calculation at the worker gate.
   */
  static Result fromCalculated(BigDecimal profit,BigDecimal margin,
      BigDecimal operating){
    if(profit==null||operating==null)
      throw new IllegalArgumentException("CALC_R02_OUTPUT_VALUE_REQUIRED");
    var gp=present("grossProfit","KZT",
        profit.setScale(2,RoundingMode.UNNECESSARY),2);
    var marginNode=margin==null
        ?invalid("grossMargin","RATIO","DIVISION_BY_ZERO")
        :present("grossMargin","RATIO",
            margin.setScale(8,RoundingMode.UNNECESSARY),8);
    var op=present("operatingResult","KZT",
        operating.setScale(2,RoundingMode.UNNECESSARY),2);
    Map<String,NodeValue> nodes=new LinkedHashMap<>();
    // Canonical topo order (grossProfit, grossMargin, operatingResult).
    nodes.put("grossProfit",gp);
    nodes.put("grossMargin",marginNode);
    nodes.put("operatingResult",op);
    String output=FactDigest.sha("FIN-FACT-CALC-OUTPUT-VALUES-V1",
        CONTRACT,F02CalcPlanDescriptor.ARTIFACT_SHA,
        F02CalcPlanDescriptor.CONFIG_SHA,F02CalcPlanDescriptor.PARAMS_SHA,
        gp.valueSha256(),marginNode.valueSha256(),op.valueSha256());
    return new Result(nodes,output);
  }

  private static BigDecimal money(BigDecimal amount) {
    if(amount==null||amount.signum()<0||amount.scale()>12
        ||amount.precision()>38)
      throw new IllegalArgumentException("CALC_R02_INPUT_VALUE_INVALID");
    return amount.setScale(2,RoundingMode.HALF_EVEN);
  }
  private static NodeValue present(String code,String unit,
      BigDecimal value,int scale) {
    String str=value.setScale(scale,RoundingMode.HALF_EVEN).toPlainString();
    String sha=FactDigest.sha("FIN-FACT-CALC-NODE-V1",
        CONTRACT,code,unit,"PRESENT",str,"");
    return new NodeValue("PRESENT",unit,str,null,sha);
  }
  private static NodeValue invalid(String code,String unit,String error) {
    String sha=FactDigest.sha("FIN-FACT-CALC-NODE-V1",
        CONTRACT,code,unit,"INVALID","",error);
    return new NodeValue("INVALID",unit,null,error,sha);
  }
}
