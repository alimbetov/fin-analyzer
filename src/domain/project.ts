import {
  BALANCE_METRICS, FORM_VERSION, MAX_IMPORT_BYTES, PROJECT_SCHEMA, YEARS,
  type ApprovedRevision, type BalanceValues, type FinProject, type MetricCode
} from "./financial";
import { canApprove, validateBalance } from "./validation";

export function createProject(company: string, draft: BalanceValues, revisions: ApprovedRevision[]): FinProject {
  return { schemaVersion: PROJECT_SCHEMA, company, draft, revisions };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function isBalanceValues(input: unknown): input is BalanceValues {
  if (!isRecord(input)) return false;
  return YEARS.every(year => {
    const row = input[year];
    return isRecord(row) && BALANCE_METRICS.every(metric =>
      typeof row[metric.code] === "string" && (row[metric.code] as string).length <= 100
    ) && Object.keys(row).every(code => BALANCE_METRICS.some(metric => metric.code === code as MetricCode));
  }) && Object.keys(input).every(key => YEARS.some(year => year === key));
}

export function readProject(text: string): FinProject {
  if (new TextEncoder().encode(text).length > MAX_IMPORT_BYTES) {
    throw new Error("Файл проекта превышает лимит 2 МБ");
  }
  const value: unknown = JSON.parse(text);
  if (!isRecord(value) || value.schemaVersion !== PROJECT_SCHEMA
    || typeof value.company !== "string" || value.company.length > 200
    || !isBalanceValues(value.draft) || !Array.isArray(value.revisions)
    || value.revisions.length > 50) {
    throw new Error("Неверная структура или версия FIN-проекта");
  }
  const revisions: ApprovedRevision[] = [];
  for (const rev of value.revisions) {
    if (!isRecord(rev) || typeof rev.id !== "string" || typeof rev.approvedAt !== "string"
      || typeof rev.company !== "string" || rev.company !== value.company
      || rev.formVersion !== FORM_VERSION || !isBalanceValues(rev.values)
      || !canApprove(validateBalance(rev.values))) {
      throw new Error("В FIN-проекте найдена недействительная утверждённая ревизия");
    }
    revisions.push({
      id: rev.id, approvedAt: rev.approvedAt, company: rev.company,
      formVersion: FORM_VERSION, values: rev.values
    });
  }
  return createProject(value.company, value.draft, revisions);
}
