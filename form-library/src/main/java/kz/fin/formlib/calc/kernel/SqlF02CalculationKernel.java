package kz.fin.formlib.calc.kernel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Native PostgreSQL 16 arithmetic for exact F02 TEST_ONLY inputs.
 *
 * This adapter only requests scalar operations, never reads mutable forms,
 * touches source/FactSet state or writes calc_result. The caller must first
 * reverify the immutable pinned source FactSet and must enforce Java parity.
 */
@Component
public final class SqlF02CalculationKernel {
  private static final Set<String> SOURCES=Set.of(
      "F02.netRevenue","F02.cogs","F02.operatingExpenses");
  private final JdbcTemplate sql;

  public SqlF02CalculationKernel(JdbcTemplate sql) {
    this.sql=Objects.requireNonNull(sql);
  }

  public F02CalculationKernel.Result evaluate(Map<String,BigDecimal> observations) {
    if(observations==null || !observations.keySet().equals(SOURCES))
      throw new IllegalArgumentException("CALC_R02_EXACT_INPUTS_REQUIRED");
    BigDecimal revenue=require(observations.get("F02.netRevenue"));
    BigDecimal cogs=require(observations.get("F02.cogs"));
    BigDecimal opex=require(observations.get("F02.operatingExpenses"));
    var results=sql.query("""
        SELECT gross_profit,gross_margin,operating_result,margin_state
        FROM fin_calc.f02_native_calculate(?,?,?)
        """,(r,i)->{
          String state=r.getString("margin_state");
          BigDecimal profit=r.getBigDecimal("gross_profit");
          BigDecimal margin=r.getBigDecimal("gross_margin");
          BigDecimal operating=r.getBigDecimal("operating_result");
          if(!"PRESENT".equals(state)&&!"INVALID".equals(state)
             ||"PRESENT".equals(state)&&margin==null
             ||"INVALID".equals(state)&&margin!=null)
            throw new IllegalStateException("CALC_SQL_F02_STATE_INVALID");
          return F02CalculationKernel.fromCalculated(profit,margin,operating);
        },revenue,cogs,opex);
    if(results.size()!=1)
      throw new IllegalStateException("CALC_SQL_F02_SHAPE_INVALID");
    return results.getFirst();
  }

  private static BigDecimal require(BigDecimal number){
    if(number==null||number.signum()<0||number.scale()>12||number.precision()>38)
      throw new IllegalArgumentException("CALC_R02_INPUT_VALUE_INVALID");
    return number;
  }
}
