import Decimal from "decimal.js";

/** Browser input contract. Not a trusted financial approval or backend qualifier. */
export const F01_PILOT_VERSION = "FIN-PILOT-V1" as const;
export const F01_LIQUIDITY_VERSION = "FIN-LIQUIDITY-V1" as const;
export type F01FormVersion =
  | typeof F01_PILOT_VERSION | typeof F01_LIQUIDITY_VERSION;

export const F01_PILOT_FIELDS = [
  "assets", "liabilities", "equity", "cash", "receivables"
] as const;
export const F01_LIQUIDITY_FIELDS = [
  ...F01_PILOT_FIELDS, "currentAssets", "currentLiabilities"
] as const;
const REQUIRED = ["assets","liabilities","equity","currentAssets","currentLiabilities"] as const;
const ALLOWED = new Set<string>(F01_LIQUIDITY_FIELDS);
const DECIMAL = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]{1,12})?$/;

export interface F01SourceCell {
  readonly sourceRowId: string;
  readonly sourceField: string;
  readonly period: string;
  readonly enteredAmount: string;
}

/** Same fail-closed invariants as ReviewedFinancialIntakeService, for early UX feedback.
 * Only the server plus independent FIN_REVIEW can authorize ACTUAL facts.
 */
export function validateF01LiquidityDraft(cells: readonly F01SourceCell[]): string[] {
  const issues: string[] = [];
  const observed = new Map<string, Decimal>();
  const periods = new Set<string>();
  for (const cell of cells) {
    if (cell.sourceRowId !== "balance" || !ALLOWED.has(cell.sourceField)) {
      issues.push("F01_UNRECOGNIZED_FIELD_OR_ROW");
      continue;
    }
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(cell.period))
      issues.push("F01_PERIOD_INVALID");
    periods.add(cell.period);
    if (observed.has(cell.sourceField)) {
      issues.push("F01_DUPLICATE_FIELD");
      continue;
    }
    const value = cell.enteredAmount.trim();
    if (!DECIMAL.test(value) || value.replace(/[^0-9]/g, "").length > 26) {
      issues.push("F01_INVALID_DECIMAL:" + cell.sourceField);
      continue;
    }
    const parsed = new Decimal(value);
    if (!parsed.isFinite() || parsed.precision() > 38) {
      issues.push("F01_INVALID_PRECISION:" + cell.sourceField);
      continue;
    }
    observed.set(cell.sourceField, parsed);
    if (cell.sourceField !== "equity" && parsed.isNegative())
      issues.push("F01_NEGATIVE_AMOUNT:" + cell.sourceField);
  }
  if (periods.size !== 1) issues.push("F01_MIXED_PERIODS");
  for (const field of REQUIRED)
    if (!observed.has(field)) issues.push("F01_MISSING_REQUIRED:" + field);

  const assets = observed.get("assets");
  const liabilities = observed.get("liabilities");
  const equity = observed.get("equity");
  const currentAssets = observed.get("currentAssets");
  const currentLiabilities = observed.get("currentLiabilities");
  if (assets && liabilities && equity &&
      !assets.equals(liabilities.plus(equity)))
    issues.push("F01_BALANCE_IDENTITY_FAILED");
  if (assets && currentAssets && currentAssets.gt(assets))
    issues.push("F01_CURRENT_ASSETS_EXCEED_TOTAL");
  if (liabilities && currentLiabilities && currentLiabilities.gt(liabilities))
    issues.push("F01_CURRENT_LIABILITIES_EXCEED_TOTAL");
  return [...new Set(issues)];
}
