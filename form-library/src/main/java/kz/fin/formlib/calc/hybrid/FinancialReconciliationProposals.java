package kz.fin.formlib.calc.hybrid;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * R16.B/R7.B F01+F03 DRAFT arithmetic, NOT qualified financial calculations.
 * Actual current R6 physical fields drive these typed inputs. No
 * fin_intake/fin_fact/calc_result writes, approval, tolerance or S2.
 *
 * F01 hypothesis: assets - liabilities - equity.
 * F03 hypothesis: openingCash + signed cfo + signed cfi + signed cff -
 *                 closingCash.
 *
 * Neither identity validates currency, corporate consolidation, FX or
 * source period consistency; the approved method must do that separately.
 */
public final class FinancialReconciliationProposals {
  public static final String STATUS="CANDIDATE_UNQUALIFIED_NOT_S2";
  private FinancialReconciliationProposals(){}

  public record F01(LocalDate asOf,BigDecimal assets,
      BigDecimal liabilities,BigDecimal equity) {
    public F01 {
      Objects.requireNonNull(asOf,"CALC_F01_ASOF_REQUIRED");
      require(assets);require(liabilities);require(equity);
    }
  }
  public record F03(LocalDate periodStart,LocalDate periodEndExclusive,
      BigDecimal openingCash,BigDecimal cfo,BigDecimal cfi,
      BigDecimal cff,BigDecimal closingCash) {
    public F03 {
      if(periodStart==null||periodEndExclusive==null
          ||!periodStart.isBefore(periodEndExclusive))
        throw new IllegalArgumentException("CALC_F03_PERIOD_INVALID");
      require(openingCash);require(cfo);require(cfi);require(cff);
      require(closingCash);
    }
  }

  public static BigDecimal f01Residual(F01 value){
    Objects.requireNonNull(value,"CALC_F01_REQUIRED");
    return value.assets().subtract(value.liabilities()).subtract(value.equity());
  }

  public static BigDecimal f03Residual(F03 value){
    Objects.requireNonNull(value,"CALC_F03_REQUIRED");
    return value.openingCash().add(value.cfo()).add(value.cfi())
        .add(value.cff()).subtract(value.closingCash());
  }

  private static void require(BigDecimal value){
    if(value==null||value.scale()<0||value.scale()>12||value.precision()>38)
      throw new IllegalArgumentException("CALC_PROPOSAL_NUMERIC_INVALID");
  }
}
