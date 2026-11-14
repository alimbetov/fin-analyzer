import Decimal from "decimal.js";
import { YEARS, type BalanceValues, type Year } from "./financial";
import { parseAmount } from "./validation";

export interface RatioResult {
  year: Year;
  value: string | null;
  reason?: string;
}

export function currentRatios(values: BalanceValues): RatioResult[] {
  return YEARS.map(year => {
    const assets = parseAmount(values[year].CURRENT_ASSETS);
    const liabilities = parseAmount(values[year].CURRENT_LIABILITIES);
    if (!assets || !liabilities) {
      return { year, value: null, reason: "Недостаточно корректных данных" };
    }
    if (liabilities.isZero()) {
      return { year, value: null, reason: "Деление на ноль" };
    }
    return { year, value: assets.div(liabilities).toDecimalPlaces(4, Decimal.ROUND_HALF_UP).toString() };
  });
}

export function assetsGrowthPercent(values: BalanceValues): string | null {
  const previous = parseAmount(values["2024"].TOTAL_ASSETS);
  const current = parseAmount(values["2025"].TOTAL_ASSETS);
  if (!previous || !current || previous.isZero()) return null;
  return current.minus(previous).div(previous).mul(100).toDecimalPlaces(2).toString();
}

export function metricSeries(values: BalanceValues, code: keyof BalanceValues["2024"]): string[] {
  return YEARS.map(year => parseAmount(values[year][code])?.toString() ?? "");
}
