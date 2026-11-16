import fieldCatalogText from "../../backend/src/main/resources/fin/intake-fields-v5.tsv?raw";

/**
 * FIN V5 pilot contract. This is an executable F02 form definition, not a declaration
 * that every entry in the 166-form discovery catalog is import-qualified.
 * The resource is shared with Java; no second JavaScript field list is maintained.
 */
export const FORM_SCHEMA_VERSION = "FIN-FORM-V2";
export const FORMAT_VERSION = "FIN-FORM-XLSX-V2";
export const MAX_PILOT_XLSX_BYTES = 2_000_000;
export const ACTIVE_LOCALES = ["ru", "kk", "en"] as const;
export type ActiveLocale = (typeof ACTIVE_LOCALES)[number];
export type FormLocale = ActiveLocale | "zh-Hans";
export const SUPPORTED_FORM_LOCALES: readonly FormLocale[] = ["ru", "kk", "en", "zh-Hans"];
export const LOCALE_NAMES: Record<FormLocale, string> = {
  ru: "Русский", kk: "Қазақша", en: "English", "zh-Hans": "简体中文 (планируется)"
};

export interface FormField {
  readonly rowId: string;
  readonly code: string;
  readonly ordinal: number;
  readonly required: boolean;
  readonly labels: Readonly<Record<ActiveLocale, string>>;
}
export interface FormSchema {
  readonly code: "F02";
  readonly version: typeof FORM_SCHEMA_VERSION;
  readonly titles: Readonly<Record<ActiveLocale, string>>;
  readonly fields: readonly FormField[];
}
export interface EntryContext {
  projectCode: string; // local draft reference only; NOT a server-owned project ID
  formCode: "F02";
  periodCode: string;
  locale: ActiveLocale;
  reportCurrency: "KZT";
  accountingCurrency: "KZT";
  amountScale: 1 | 1000 | 1000000;
}
export interface EntryRow {
  rowId: string;
  fieldCode: string;
  rawValue: string;
}
export interface EntryFinding {
  code: string;
  fieldCode?: string;
  sourceCell?: string;
}
export interface ValidatedEntry {
  context: EntryContext;
  rows: readonly EntryRow[];
  findings: readonly EntryFinding[];
  valid: boolean;
}

const header = "formCode\tformVersion\trowId\tfieldCode\tordinal\trequired\ttitleRu\ttitleKk\ttitleEn\tlabelRu\tlabelKk\tlabelEn";
function parseRegistry(raw: string): FormSchema {
  const lines = raw.trimEnd().split(/\r?\n/);
  if (lines.shift() !== header) throw new Error("V5_REGISTRY_HEADER_CHANGED");
  const fields: FormField[] = [];
  let titles: Record<ActiveLocale, string> | undefined;
  for (const line of lines) {
    const cells = line.split("\t");
    if (cells.length !== 12 || cells.some(value => !value.trim())) throw new Error("V5_REGISTRY_BAD_ROW");
    const [formCode, version, rowId, code, ordinal, required, ruTitle, kkTitle, enTitle, ru, kk, en] = cells;
    if (formCode !== "F02" || version !== FORM_SCHEMA_VERSION || rowId !== "pnl"
      || !/^[A-Za-z][A-Za-z0-9_]*$/.test(code!)
      || !/^[1-9][0-9]*$/.test(ordinal!)
      || !["true", "false"].includes(required!)) throw new Error("V5_REGISTRY_INVALID_FIELD");
    const titleRow = { ru: ruTitle!, kk: kkTitle!, en: enTitle! };
    if (titles && ACTIVE_LOCALES.some(lang => titles![lang] !== titleRow[lang]))
      throw new Error("V5_REGISTRY_TITLE_DRIFT");
    titles = titleRow;
    fields.push({ rowId: rowId!, code: code!, ordinal: Number(ordinal),
      required: required === "true", labels: { ru: ru!, kk: kk!, en: en! } });
  }
  fields.sort((a,b) => a.ordinal - b.ordinal);
  if (fields.length !== 3 || new Set(fields.map(x => x.code)).size !== 3 ||
    new Set(fields.map(x => x.ordinal)).size !== 3 ||
    fields.some((field,i) => field.ordinal !== i+1) || !titles)
    throw new Error("V5_REGISTRY_INCOMPLETE_F02");
  return { code: "F02", version: FORM_SCHEMA_VERSION, titles, fields };
}
export const PILOT_F02_SCHEMA: FormSchema = parseRegistry(fieldCatalogText);

export function requireActiveLocale(locale: FormLocale): asserts locale is ActiveLocale {
  if (!ACTIVE_LOCALES.includes(locale as ActiveLocale))
    throw new Error("TRANSLATION_NOT_READY: " + locale);
}
export function normalizeDecimal(value: string): string {
  const trimmed = value.trim();
  if (!/^-?[0-9]{1,26}(\.[0-9]{1,12})?$/.test(trimmed)) throw new Error("INVALID_DECIMAL");
  const negative = trimmed.startsWith("-");
  const unsigned = negative ? trimmed.slice(1) : trimmed;
  const [integer, decimal] = unsigned.split(".");
  const clean = (integer!.replace(/^0+(?=\d)/, "") || "0");
  const decimals = (decimal ?? "").replace(/0+$/, "");
  const sign = negative && (clean !== "0" || !!decimals) ? "-" : "";
  return sign + clean + (decimals ? "." + decimals : "");
}

/** A comma decimal separator is accepted only in RU/KK and never as a thousands separator. */
export function normalizeUserDecimal(value:string,locale:ActiveLocale):string {
  const trimmed=value.trim();
  if ((locale==="ru" || locale==="kk") && /^-?[0-9]{1,26},[0-9]{1,12}$/.test(trimmed))
    return normalizeDecimal(trimmed.replace(",","."));
  return normalizeDecimal(trimmed);
}

export function validateF02(context: EntryContext, rows: readonly EntryRow[], sourceKind: "MANUAL" | "XLSX" = "MANUAL"): ValidatedEntry {
  const findings: EntryFinding[] = [];
  if (!/^[\p{L}\p{N}_-]{1,50}$/u.test(context.projectCode))
    findings.push({ code: "PROJECT_CODE_REQUIRED" });
  if (context.formCode !== "F02" || ![1, 1000, 1000000].includes(context.amountScale)
      || context.reportCurrency !== "KZT" || context.accountingCurrency !== "KZT")
    findings.push({ code: "V5_PILOT_CONTEXT_NOT_SUPPORTED" });
  const parts = /^(\d{4})\.(0[1-9]|1[0-2])$/.exec(context.periodCode);
  const selectedYear = parts ? Number(parts[1]) : 0;
  if (!parts || selectedYear < 1900 || selectedYear > 9998)
    findings.push({ code: "INVALID_MONTH" });
  if (!ACTIVE_LOCALES.includes(context.locale))
    findings.push({code: "TRANSLATION_NOT_READY"});
  const byCode = new Map<string, EntryRow>();
  for (const row of rows) {
    const known = PILOT_F02_SCHEMA.fields.some(field => field.code === row.fieldCode && field.rowId === row.rowId);
    if (!known) { findings.push({ code: "UNKNOWN_SOURCE_COORDINATE", fieldCode: row.fieldCode }); continue; }
    if (byCode.has(row.fieldCode)) { findings.push({ code: "DUPLICATE_SOURCE_FIELD", fieldCode: row.fieldCode }); continue; }
    byCode.set(row.fieldCode, row);
  }
  const normalized: EntryRow[] = PILOT_F02_SCHEMA.fields.map(field => {
    const raw = byCode.get(field.code)?.rawValue ?? "";
    if (!raw.trim()) {
      if (field.required) findings.push({ code: "REQUIRED_FIELD_MISSING", fieldCode: field.code });
      return { rowId: field.rowId, fieldCode: field.code, rawValue: "" };
    }
    try {
      return { rowId: field.rowId, fieldCode: field.code, rawValue: normalizeUserDecimal(raw,context.locale) };
    } catch {
      findings.push({ code: "INVALID_DECIMAL", fieldCode: field.code });
      return { rowId: field.rowId, fieldCode: field.code, rawValue: raw };
    }
  });
  const localizedFindings = sourceKind === "XLSX"
    ? findings.map(finding => {
        const index = PILOT_F02_SCHEMA.fields.findIndex(f => f.code === finding.fieldCode);
        return index < 0 ? finding : {...finding, sourceCell: "FIN_Data!D" + (index + 3)};
      })
    : findings;
  return { context, rows: normalized, findings: localizedFindings, valid: findings.length === 0 };
}

/** Decimal-string formatter; does not convert the financial amount to a JS Number. */
export function formatExactDecimal(value: string, locale: FormLocale, fractionDigits = 2): string {
  const v = normalizeDecimal(value);
  const tag = ({ ru: "ru-RU", kk: "kk-KZ", en: "en-US", "zh-Hans": "zh-CN" } as const)[locale];
  const numberParts = new Intl.NumberFormat(tag).formatToParts(12345.6);
  const group = numberParts.find(part => part.type === "group")?.value ?? " ";
  const separator = numberParts.find(part => part.type === "decimal")?.value ?? ".";
  const negative = v.startsWith("-");
  const [intPart, fracPart = ""] = (negative ? v.slice(1) : v).split(".");
  const grouped = intPart!.replace(/\B(?=(\d{3})+(?!\d))/g, group);
  const digits = Math.max(fractionDigits, fracPart.length);
  return (negative ? "−" : "") + grouped + (digits ? separator + fracPart.padEnd(digits, "0") : "");
}
