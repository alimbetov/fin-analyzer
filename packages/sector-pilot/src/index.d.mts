/** Public TypeScript declarations for the existing independent JS calculation kernel. */
export type PilotCode = "SF-G01" | "SF-G02" | "SF-G03" | "SF-M01" | "SF-M02" | "SF-S01" | "SF-C01" | "SF-C02" | "SF-C03";
export type PilotInput = {
  formCode: PilotCode;
  businessId: string;
  dimensionId: string;
  currency: string;
  period: string;
  sourceId: string;
  revisionId: string;
  approved: true;
  values: Record<string, string | null>;
};
export type PilotMetric = { status: "CALCULATED" | "NOT_COMPUTABLE"; value: string | null; unit?: string; reason?: string };
export type PilotOutput = {
  businessId: string;
  dimensionId: string;
  formCode: PilotCode;
  currency: string;
  windowType: "MONTH" | "QUARTER" | "HALF_YEAR" | "YEAR";
  windowCode: string;
  status: "CALCULATED" | "INCOMPLETE";
  missingPeriods: string[];
  missingFields: string[];
  sourceRevisions: string[];
  metrics: Record<string, PilotMetric>;
};
export const PILOT_FORMS: readonly PilotCode[];
export const FORM_FIELDS: Readonly<Record<PilotCode, readonly string[]>>;
export function evaluateSectorForms(input: PilotInput[]): PilotOutput[];
