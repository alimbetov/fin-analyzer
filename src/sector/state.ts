import { evaluateSectorForms, FORM_FIELDS, type PilotCode, type PilotInput, type PilotOutput } from "../../packages/sector-pilot/src/index.mjs";
import { modelPack, pilotCode } from "./registry";

export interface SectorApproval {
  modelCode: string;
  approvedAt: string;
  row: PilotInput;
}
export interface SectorState {
  schemaVersion: "sector-pilot/1";
  businessId: string;
  current: SectorApproval[];
  prior: SectorApproval[];
}
const KEY_PATTERN = /^[A-Za-z0-9_.:-]{1,80}$/;
export function createSectorState(businessId: string = "local-" + crypto.randomUUID()): SectorState {
  return { schemaVersion: "sector-pilot/1", businessId, current: [], prior: [] };
}
function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}
export function approvalKey(approval: SectorApproval): string {
  const { row } = approval;
  return JSON.stringify([approval.modelCode,row.formCode,row.dimensionId,row.currency,row.period]);
}
function validateApproval(value: unknown, expectedBusinessId: string): SectorApproval {
  if (!isRecord(value) || typeof value.modelCode !== "string" ||
      typeof value.approvedAt !== "string" || !/^\d{4}-\d\d-\d\dT/.test(value.approvedAt) ||
      !isRecord(value.row)) {
    throw new Error("SECTOR_INVALID_APPROVAL");
  }
  const row = value.row as Record<string, unknown>;
  const code = row.formCode;
  const modelCode = value.modelCode;
  const pack = modelPack(modelCode);
  if (typeof code !== "string" || !pilotCode(code) || !pack?.forms.includes(code) ||
      row.businessId !== expectedBusinessId || typeof row.dimensionId !== "string" ||
      !row.dimensionId.startsWith(modelCode + ".") ||
      row.dimensionId.length > 80 || !KEY_PATTERN.test(row.dimensionId) ||
      !isRecord(row.values) || Object.keys(row).length !== 9) {
    throw new Error("SECTOR_INVALID_SCOPE");
  }
  const safeRow = row as unknown as PilotInput;
  const results = evaluateSectorForms([safeRow]);
  const month = results.find(item => item.windowType === "MONTH");
  if (!month || month.status !== "CALCULATED") {
    throw new Error("SECTOR_INCOMPLETE_APPROVAL");
  }
  return { modelCode, approvedAt: value.approvedAt, row: {
    formCode: safeRow.formCode, businessId: safeRow.businessId,
    dimensionId: safeRow.dimensionId, currency: safeRow.currency, period: safeRow.period,
    sourceId: safeRow.sourceId, revisionId: safeRow.revisionId, approved: true,
    values: { ...safeRow.values }
  } };
}
export function readSectorState(value: unknown): SectorState {
  if (!isRecord(value) || value.schemaVersion !== "sector-pilot/1" ||
      typeof value.businessId !== "string" || !KEY_PATTERN.test(value.businessId) ||
      !Array.isArray(value.current) || !Array.isArray(value.prior) ||
      value.current.length > 300 || value.prior.length > 600 ||
      Object.keys(value).some(key => !["schemaVersion","businessId","current","prior"].includes(key))) {
    throw new Error("SECTOR_INVALID_PROJECT_STATE");
  }
  const current = value.current.map(item => validateApproval(item, value.businessId as string));
  const prior = value.prior.map(item => validateApproval(item, value.businessId as string));
  if (new Set(current.map(approvalKey)).size !== current.length) {
    throw new Error("SECTOR_DUPLICATE_CURRENT_REVISION");
  }
  const sourceIds = [...current, ...prior].map(x => x.row.sourceId);
  const revisionIds = [...current, ...prior].map(x => x.row.revisionId);
  if (new Set(sourceIds).size !== sourceIds.length || new Set(revisionIds).size !== revisionIds.length) {
    throw new Error("SECTOR_DUPLICATE_SOURCE_OR_REVISION");
  }
  evaluateSectorForms(current.map(item => item.row));
  return { schemaVersion: "sector-pilot/1", businessId: value.businessId, current, prior };
}
export function prepareSectorRow(
  state: SectorState, modelCode: string, formCode: string,
  dimension: string, currency: string, period: string, values: Record<string, string>
): SectorApproval {
  const pack = modelPack(modelCode);
  const code = pilotCode(formCode);
  if (!pack || !code || !pack.forms.includes(code) ||
      !/^[A-Za-z0-9_.:-]{1,45}$/.test(dimension)) {
    throw new Error("SECTOR_INVALID_SELECTION");
  }
  const validFields = FORM_FIELDS[code];
  if (Object.keys(values).length !== validFields.length ||
      !validFields.every(field => Object.hasOwn(values, field))) {
    throw new Error("SECTOR_INVALID_FIELDS");
  }
  const row: PilotInput = {
    formCode: code, businessId: state.businessId,
    dimensionId: modelCode + "." + dimension, currency, period,
    sourceId: "source-" + crypto.randomUUID(), revisionId: "rev-" + crypto.randomUUID(),
    approved: true, values: Object.fromEntries(validFields.map(field => [
      field, values[field]?.trim() ? values[field]!.trim() : null
    ]))
  };
  const month = evaluateSectorForms([row]).find(x => x.windowType === "MONTH");
  if (!month || month.status !== "CALCULATED") {
    throw new Error("SECTOR_REQUIRED_FIELDS_MISSING");
  }
  return { modelCode, approvedAt: new Date().toISOString(), row };
}
export function approveSectorRow(state: SectorState, approval: SectorApproval): SectorState {
  validateApproval(approval, state.businessId);
  const existingKey = approvalKey(approval);
  const old = state.current.find(item => approvalKey(item) === existingKey);
  const result: SectorState = {
    ...state,
    current: [...state.current.filter(item => approvalKey(item) !== existingKey), approval],
    prior: old ? [...state.prior, old] : [...state.prior]
  };
  return readSectorState(result);
}
export function calculateSectorState(state: SectorState): PilotOutput[] {
  return evaluateSectorForms(state.current.map(item => item.row));
}
export function fieldsForForm(form: PilotCode): readonly string[] {
  return FORM_FIELDS[form];
}
