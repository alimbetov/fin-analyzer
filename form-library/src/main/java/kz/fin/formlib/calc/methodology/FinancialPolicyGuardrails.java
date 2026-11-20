package kz.fin.formlib.calc.methodology;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure policy guards for UAT candidate plans; does NOT qualify any formula.
 * All thresholds require previously approved explicit input: no invented IFRS
 * defaults, automatic materiality or unknown FX reference can enter a CalcPlan.
 */
public final class FinancialPolicyGuardrails {
  private FinancialPolicyGuardrails(){}

  public enum ReportingBasis { IFRS, MANAGEMENT }
  public enum DscrPurpose { COVENANT, ANALYTICAL }

  public static ReportingBasis requireSingleReportingBasis(
      List<ReportingBasis> sourceBases) {
    if(sourceBases==null || sourceBases.isEmpty() ||
        sourceBases.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("REPORTING_BASIS_REQUIRED");
    var basis=sourceBases.getFirst();
    if(sourceBases.stream().anyMatch(v->v!=basis))
      throw new IllegalStateException("MIXED_REPORTING_BASES_IN_CALC_PLAN");
    return basis;
  }

  /**
   * Numeric tolerance is an explicit accounting-policy input, not a
   * framework constant. An override is legal only if it tightens the
   * allowed absolute difference. No implicit zero/0.1%/1000 defaults.
   */
  public static BigDecimal approvedTolerance(BigDecimal systemApprovedLimit,
      BigDecimal formOverrideLimit) {
    if(systemApprovedLimit==null)
      throw new IllegalStateException("RECONCILIATION_TOLERANCE_POLICY_UNAPPROVED");
    if(systemApprovedLimit.signum()<0 ||
        (formOverrideLimit!=null && formOverrideLimit.signum()<0))
      throw new IllegalArgumentException("RECONCILIATION_TOLERANCE_NEGATIVE");
    if(formOverrideLimit==null)return systemApprovedLimit;
    if(formOverrideLimit.compareTo(systemApprovedLimit)>0)
      throw new IllegalStateException("RECONCILIATION_TOLERANCE_WEAKENING_FORBIDDEN");
    return formOverrideLimit;
  }

  /**
   * Never present analytical DSCR as a loan-covenant result when contractual
   * definition is unavailable. Contractual review is separate from math.
   */
  public static String dscrResultClass(DscrPurpose purpose,
      boolean verifiedContractDefinitionAvailable) {
    if(purpose==null)
      throw new IllegalArgumentException("DSCR_PURPOSE_REQUIRED");
    if(purpose==DscrPurpose.COVENANT) {
      if(!verifiedContractDefinitionAvailable)
        throw new IllegalStateException("DSCR_COVENANT_DEFINITION_NOT_VERIFIED");
      return "CONTRACTUAL_COVENANT_DSCR_UAT_CANDIDATE";
    }
    return "ANALYTICAL_DSCR_NOT_COVENANT";
  }

  /**
   * Verify uniform selected cost formula for every inventory class with
   * the same nature and use. Cross-period policy changes still require
   * a separately reviewed revision and are not authorized here.
   */
  /** IAS 2 eligibility check: specific identification for non-interchangeable items. */
  public static void requireCostFormulaConsistentWithInterchangeability(
      String formula,boolean ordinarilyInterchangeable) {
    if(formula==null)
      throw new IllegalArgumentException("INVENTORY_COST_FORMULA_REQUIRED");
    if(ordinarilyInterchangeable) {
      if(!formula.equals("FIFO") && !formula.equals("WEIGHTED_AVERAGE"))
        throw new IllegalStateException("INVENTORY_INTERCHANGEABLE_FORMULA_INVALID");
    } else if(!formula.equals("SPECIFIC_IDENTIFICATION")) {
      throw new IllegalStateException("INVENTORY_SPECIFIC_IDENTIFICATION_REQUIRED");
    }
  }

  public static void requireUniformInventoryClassFormula(
      Map<String,String> formulaByInventoryClass,
      List<String> inventoryClassCodes) {
    if(formulaByInventoryClass==null || formulaByInventoryClass.isEmpty()
        || inventoryClassCodes==null || inventoryClassCodes.isEmpty())
      throw new IllegalArgumentException("INVENTORY_CLASS_POLICY_REQUIRED");
    for(var classCode:inventoryClassCodes) {
      if(classCode==null || classCode.isBlank() ||
          !formulaByInventoryClass.containsKey(classCode))
        throw new IllegalStateException("INVENTORY_CLASS_COST_POLICY_MISSING");
      var method=formulaByInventoryClass.get(classCode);
      if(method==null || !List.of("FIFO","WEIGHTED_AVERAGE","SPECIFIC_IDENTIFICATION")
          .contains(method))
        throw new IllegalStateException("INVENTORY_CLASS_COST_FORMULA_UNSUPPORTED");
    }
  }
}
