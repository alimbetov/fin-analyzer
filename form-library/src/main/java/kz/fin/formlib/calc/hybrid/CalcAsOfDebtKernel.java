package kz.fin.formlib.calc.hybrid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-C01/C02: deterministic UAT stock calculation. Does not certify an
 * accounting-policy choice; C02 overdue-subset rule is an explicit UAT assumption.
 * Each row is one source-row UUID, not a claimed durable invoice identifier.
 * Editorial policy V2: anomalous allowances, credit balances and overdue amounts
 * are review blockers in this positive-stock UAT profile, NOT blanket IFRS bans.
 * Partial payments and amendments must already be reflected in source balances;
 * this kernel does not reconstruct payment allocation, ECL or write-offs.
 */
public final class CalcAsOfDebtKernel {
  private CalcAsOfDebtKernel() {}
  public enum Form { C01, C02 }
  public record Cell(UUID sourceRevisionId, String rowKey, String field, BigDecimal value) {}
  public record Result(String primaryMetric, BigDecimal primaryAmount,
      BigDecimal overdueAmount, BigDecimal overdueShare, String overdueShareState,
      Map<String,BigDecimal> fieldTotals, Map<String,Long> fieldCounts,
      int sourceRowCount) {
    public Result {
      fieldTotals = Map.copyOf(fieldTotals);
      fieldCounts = Map.copyOf(fieldCounts);
    }
  }
  private static final int MAX_PRECISION = 38;
  private static final int MAX_SCALE = 12;

  public static Result calculate(Form form, List<Cell> cells) {
    if (form == null || cells == null || cells.isEmpty())
      throw new IllegalArgumentException("ASOF_FORM_OR_INPUTS_MISSING");
    String principal = form == Form.C01 ? "gross" : "outstanding";
    String deduction = form == Form.C01 ? "allowance" : null;
    Set<String> permitted = form == Form.C01
        ? Set.of("gross", "allowance", "overdue")
        : Set.of("outstanding", "overdue");
    Map<String,Map<String,BigDecimal>> byRow = new HashMap<>();
    Set<String> seen = new HashSet<>();
    UUID revision = null;
    for (Cell cell : cells) {
      if (cell == null || cell.sourceRevisionId() == null || cell.field() == null
          || !permitted.contains(cell.field()) || cell.rowKey() == null)
        throw new IllegalArgumentException("ASOF_SOURCE_CELL_INVALID");
      try { UUID.fromString(cell.rowKey()); }
      catch (IllegalArgumentException ex) {
        throw new IllegalArgumentException("ASOF_DURABLE_SOURCE_ROW_UUID_REQUIRED", ex);
      }
      if (revision == null) revision = cell.sourceRevisionId();
      if (!revision.equals(cell.sourceRevisionId()))
        throw new IllegalStateException("ASOF_MULTIPLE_SOURCE_REVISIONS_NEED_MERGE_POLICY");
      String key = cell.rowKey() + "|" + cell.field();
      if (!seen.add(key))
        throw new IllegalStateException("ASOF_DUPLICATE_ROW_FIELD");
      BigDecimal value = cell.value();
      if (value == null || value.scale() > MAX_SCALE || value.precision() > MAX_PRECISION)
        throw new IllegalArgumentException("ASOF_MONEY_VALUE_INVALID");
      if (value.signum() < 0)
        throw new IllegalStateException("ASOF_CREDIT_BALANCE_CLASSIFICATION_REVIEW_REQUIRED");
      byRow.computeIfAbsent(cell.rowKey(), ignored -> new HashMap<>())
          .put(cell.field(), value);
    }
    if (byRow.size() > 50_000)
      throw new IllegalArgumentException("ASOF_TOO_MANY_ROWS");
    Map<String,BigDecimal> totals = new HashMap<>();
    Map<String,Long> counts = new HashMap<>();
    boolean overdueComplete = true;
    for (var row : byRow.values()) {
      if (!row.containsKey(principal) ||
          (deduction != null && !row.containsKey(deduction)))
        throw new IllegalStateException("ASOF_REQUIRED_INPUT_MISSING");
      BigDecimal gross = row.get(principal);
      if (deduction != null && row.get(deduction).compareTo(gross) > 0)
        throw new IllegalStateException("ASOF_ALLOWANCE_EXCEEDS_GROSS_REVIEW_REQUIRED");
      if (row.containsKey("overdue") && row.get("overdue").compareTo(gross) > 0)
        throw new IllegalStateException("ASOF_OVERDUE_EXCEEDS_OPEN_BALANCE_REVIEW_REQUIRED");
      if (!row.containsKey("overdue")) overdueComplete = false;
      for (var entry : row.entrySet()) {
        totals.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
        counts.merge(entry.getKey(), 1L, Long::sum);
      }
    }
    BigDecimal principalTotal = requirePrecision(totals.get(principal));
    BigDecimal output = deduction == null ? principalTotal :
        requirePrecision(principalTotal.subtract(requirePrecision(totals.get(deduction))));
    BigDecimal overdue = overdueComplete ? requirePrecision(totals.get("overdue")) : null;
    BigDecimal share = null;
    String state;
    if (!overdueComplete) state = "MISSING_OPTIONAL_INPUT";
    else if (principalTotal.signum() == 0) state = "UNDEFINED_ZERO_DENOMINATOR";
    else {
      // UAT technical decimal policy; not asserted as an IFRS rounding rule.
      share = overdue.divide(principalTotal, MAX_SCALE, RoundingMode.HALF_UP);
      state = "PRESENT";
    }
    for (BigDecimal v : totals.values()) requirePrecision(v);
    return new Result(form == Form.C01 ? "C01.netReceivablesProposed" :
        "C02.totalOutstandingProposed", output, overdue, share, state,
        totals, counts, byRow.size());
  }
  private static BigDecimal requirePrecision(BigDecimal v) {
    if (v == null || v.precision() > MAX_PRECISION || v.scale() > MAX_SCALE)
      throw new IllegalStateException("ASOF_SUM_PRECISION_OVERFLOW");
    return v;
  }
}
