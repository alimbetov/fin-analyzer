import Decimal from "decimal.js";
import { BALANCE_METRICS, YEARS, type BalanceValues, type Finding, type MetricCode, type Year } from "./financial";

const STRICT_DECIMAL = /^-?(?:0|[1-9][0-9]*)(?:[.,][0-9]+)?$/;

export function parseAmount(input: string): Decimal | null {
  const value = input.trim();
  if (!STRICT_DECIMAL.test(value)) return null;
  const normalized = value.replace(",", ".");
  try {
    const decimal = new Decimal(normalized);
    if (!decimal.isFinite() || decimal.precision() > 15 || decimal.abs().greaterThanOrEqualTo('1000000000000000')) return null;
    return decimal;
  } catch {
    return null;
  }
}

export function validateBalance(values: BalanceValues): Finding[] {
  const findings: Finding[] = [];
  for (const year of YEARS) {
    const parsed = {} as Partial<Record<MetricCode, Decimal>>;
    for (const item of BALANCE_METRICS) {
      const raw = values[year][item.code];
      const amount = parseAmount(raw);
      if (raw.trim() === "") {
        findings.push({ code: "MISSING", severity: "BLOCKER", year, metric: item.code,
          message: "Отсутствует обязательный показатель: " + item.label });
      } else if (!amount) {
        findings.push({ code: "INVALID_DECIMAL", severity: "BLOCKER", year, metric: item.code,
          message: "Неверное число или точность более 15 значащих цифр: " + item.label });
      } else {
        parsed[item.code] = amount;
        if (item.code !== "TOTAL_EQUITY" && amount.isNegative()) {
          findings.push({ code: "NEGATIVE", severity: "BLOCKER", year, metric: item.code,
            message: "Отрицательное значение недопустимо для: " + item.label });
        }
      }
    }
    const assets = parsed.TOTAL_ASSETS;
    const liabilities = parsed.TOTAL_LIABILITIES;
    const equity = parsed.TOTAL_EQUITY;
    if (assets && liabilities && equity) {
      const difference = assets.minus(liabilities.plus(equity));
      // All imported values are in whole or fractional thousands of KZT.
      if (!difference.isZero()) {
        findings.push({ code: "BALANCE_MISMATCH", severity: "BLOCKER", year,
          message: "Баланс " + year + " не сходится: активы − обязательства − капитал = " + difference.toString() + " тыс. ₸" });
      }
    }
    if (assets && parsed.CURRENT_ASSETS && parsed.CURRENT_ASSETS.greaterThan(assets)) {
      findings.push({ code: "CURRENT_EXCEEDS_TOTAL", severity: "BLOCKER", year,
        metric: "CURRENT_ASSETS", message: "Оборотные активы превышают все активы" });
    }
    if (liabilities && parsed.CURRENT_LIABILITIES && parsed.CURRENT_LIABILITIES.greaterThan(liabilities)) {
      findings.push({ code: "CURRENT_EXCEEDS_TOTAL", severity: "BLOCKER", year,
        metric: "CURRENT_LIABILITIES", message: "Краткосрочные обязательства превышают все обязательства" });
    }
    if (parsed.CURRENT_LIABILITIES?.isZero()) {
      findings.push({ code: "ZERO_DENOMINATOR", severity: "WARNING", year,
        metric: "CURRENT_LIABILITIES", message: "Current Ratio невозможно рассчитать при нулевых краткосрочных обязательствах" });
    }
    if (equity?.isNegative()) {
      findings.push({ code: "NEGATIVE_EQUITY", severity: "WARNING", year,
        metric: "TOTAL_EQUITY", message: "Отрицательный капитал требует дополнительного анализа" });
    }
  }
  return findings;
}

export function canApprove(findings: Finding[]): boolean {
  return findings.every(finding => finding.severity !== "BLOCKER");
}

export function normalizeBalance(values: BalanceValues): BalanceValues {
  const result = {} as BalanceValues;
  for (const year of YEARS) {
    result[year] = {} as Record<MetricCode, string>;
    for (const item of BALANCE_METRICS) {
      const raw = values[year][item.code];
      result[year][item.code] = parseAmount(raw)?.toString() ?? raw.trim();
    }
  }
  return result;
}

export function isKnownYear(year: string): year is Year {
  return YEARS.some(item => item === year);
}
