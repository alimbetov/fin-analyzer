import sectorSource from "../../backend/src/main/resources/fin/sector-business-model-packs-v2.tsv?raw";
import formSource from "../../backend/src/main/resources/fin/form-compatibility.tsv?raw";
import { PILOT_FORMS, type PilotCode } from "../../packages/sector-pilot/src/index.mjs";

export interface SectorPack {
  section: string;
  models: readonly string[];
  forms: readonly string[];
  baseForms: readonly string[];
  extensions: readonly string[];
  operationalGrain: readonly string[];
  analysisFamilies: readonly string[];
  methodologyRisk: string;
  catalogStatus: "DESIGN_ONLY";
}
const SECTOR_HEADER = "section\tbusinessModelVariants\tsectorFormCodes\tbaseDataFormCodes\toperationalGrain\tanalysisFamilies\trequiredExtensionSheets\tmethodologyRisk\tqualificationStatus";
const FORM_HEADER = "formCode\tcatalog\tformKind\taggregateRoot\tgrain\tfieldContract\tfieldEvidence\tqualificationStatus";
const EXPECTED_SECTIONS = "ABCDEFGHIJKLMNOPQRSTU";
const TITLES: Record<string, string> = {
  A: "Сельское и рыбное хозяйство", B: "Добыча", C: "Производство",
  D: "Энергетика", E: "Вода и отходы", F: "Строительство", G: "Торговля",
  H: "Транспорт и склад", I: "Гостиницы и питание", J: "IT и связь",
  K: "Финансы и страхование", L: "Недвижимость", M: "Профессиональные услуги",
  N: "Административные услуги", O: "Государственные услуги", P: "Образование",
  Q: "Медицина и соцуслуги", R: "Культура и спорт", S: "Бытовые услуги",
  T: "Домохозяйства", U: "Экстерриториальные организации"
};
function rows(raw: string, header: string, columns: number): string[][] {
  const lines = raw.trimEnd().split(/\r?\n/);
  if (lines.shift() !== header) throw new Error("REGISTRY_HEADER_DRIFT");
  return lines.map(line => {
    const fields = line.split("\t");
    if (fields.length !== columns || fields.some(field => !field.trim())) {
      throw new Error("REGISTRY_INVALID_ROW");
    }
    return fields;
  });
}
function members(input: string): string[] {
  const parts = input.split(";");
  if (parts.some(part => !part.trim()) || new Set(parts).size !== parts.length) {
    throw new Error("REGISTRY_DUPLICATE_MEMBER");
  }
  return parts;
}
export function parseSectorRegistry(source: string, formsSource: string): readonly SectorPack[] {
  const formRows = rows(formsSource, FORM_HEADER, 8);
  const knownForms = new Set(formRows.map(row => row[0]));
  if (formRows.length !== 166 || knownForms.size !== 166) throw new Error("FORM_CATALOG_DRIFT");
  const models = new Set<string>(), sectors = new Set<string>(), sectorForms = new Set<string>();
  const entries = rows(source, SECTOR_HEADER, 9).map(fields => {
    const [section, modelList, formList, baseList, grain, family, extensionList, risk, status] = fields;
    if (!section || !/^[A-U]$/.test(section) || sectors.has(section) || status !== "DESIGN_ONLY") {
      throw new Error("SECTOR_CODE_OR_STATUS_INVALID");
    }
    sectors.add(section);
    const modelCodes = members(modelList!);
    const formCodes = members(formList!);
    const baseForms = members(baseList!);
    if (modelCodes.length < 2 || formCodes.length !== 3) throw new Error("SECTOR_COUNT_INVALID");
    for (const model of modelCodes) {
      if (!model.startsWith(section + "_") || models.has(model)) throw new Error("MODEL_INVALID");
      models.add(model);
    }
    for (const code of [...formCodes, ...baseForms]) {
      if (!knownForms.has(code)) throw new Error("UNKNOWN_FORM_" + code);
    }
    for (const code of formCodes) {
      if (!/^SF-[A-U]0[1-3]$/.test(code) || code[3] !== section || sectorForms.has(code)) {
        throw new Error("CROSS_SECTOR_FORM_" + code);
      }
      sectorForms.add(code);
    }
    return {
      section, models: Object.freeze(modelCodes), forms: Object.freeze(formCodes),
      baseForms: Object.freeze(baseForms),
      extensions: Object.freeze(members(extensionList!)),
      operationalGrain: Object.freeze(members(grain!)), analysisFamilies: Object.freeze(members(family!)),
      methodologyRisk: risk!, catalogStatus: status as "DESIGN_ONLY"
    };
  });
  if (entries.map(entry => entry.section).join("") !== EXPECTED_SECTIONS ||
      models.size !== 64 || sectorForms.size !== 63) {
    throw new Error("REGISTRY_COVERAGE_DRIFT");
  }
  if (PILOT_FORMS.some(code => !sectorForms.has(code))) throw new Error("UNKNOWN_PILOT");
  return Object.freeze(entries);
}

export const SECTOR_PACKS = parseSectorRegistry(sectorSource, formSource);
export const PILOT_SET: ReadonlySet<string> = new Set(PILOT_FORMS);
export function sectionTitle(section: string): string {
  return TITLES[section] ?? "Неизвестный сектор";
}
export function modelPack(modelCode: string): SectorPack | null {
  return SECTOR_PACKS.find(pack => pack.models.includes(modelCode)) ?? null;
}
export function selectForms(modelCode: string): Array<{ code: string; executable: boolean }> {
  const pack = modelPack(modelCode);
  return pack ? pack.forms.map(code => ({ code, executable: PILOT_SET.has(code) })) : [];
}
export function pilotCode(value: string): PilotCode | null {
  return PILOT_SET.has(value) ? value as PilotCode : null;
}
