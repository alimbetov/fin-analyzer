export const YEARS = ["2024", "2025"] as const;
export type Year = (typeof YEARS)[number];

export const BALANCE_METRICS = [
  { code: "TOTAL_ASSETS", label: "Итого активы", group: "Активы" },
  { code: "CURRENT_ASSETS", label: "Оборотные активы", group: "Активы" },
  { code: "TOTAL_LIABILITIES", label: "Итого обязательства", group: "Обязательства и капитал" },
  { code: "CURRENT_LIABILITIES", label: "Краткосрочные обязательства", group: "Обязательства и капитал" },
  { code: "TOTAL_EQUITY", label: "Собственный капитал", group: "Обязательства и капитал" }
] as const;

export type MetricCode = (typeof BALANCE_METRICS)[number]["code"];
export type BalanceValues = Record<Year, Record<MetricCode, string>>;
export const FORM_CODE = "FIN_BALANCE";
export const FORM_VERSION = "1.0.0";
export const PROJECT_SCHEMA = "fin.project/1";
export const UNIT = "THOUSANDS_KZT";
export const MAX_IMPORT_BYTES = 2_000_000;

export function emptyBalance(): BalanceValues {
  const row = (): Record<MetricCode, string> =>
    Object.fromEntries(BALANCE_METRICS.map(item => [item.code, ""])) as Record<MetricCode, string>;
  return { "2024": row(), "2025": row() };
}

export function cloneBalance(values: BalanceValues): BalanceValues {
  return {
    "2024": { ...values["2024"] },
    "2025": { ...values["2025"] }
  };
}

export interface Finding {
  code: string;
  severity: "BLOCKER" | "WARNING";
  message: string;
  year?: Year;
  metric?: MetricCode;
  cell?: string;
}

export interface ApprovedRevision {
  id: string;
  approvedAt: string;
  company: string;
  formVersion: string;
  values: BalanceValues;
}

export interface FinProject {
  schemaVersion: typeof PROJECT_SCHEMA;
  company: string;
  draft: BalanceValues;
  revisions: ApprovedRevision[];
}
