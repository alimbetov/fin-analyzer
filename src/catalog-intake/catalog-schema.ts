import catalogText from "../../backend/src/main/resources/fin/form-compatibility.tsv?raw";
import { normalizeDecimal } from "../intake-v5/form-schema";
import { resolvePeriod, type PeriodType, type ValueCoverage } from "../intake-v5/reporting-period";

/**
 * FIN R6 — the 166-entry discovery catalog is NOT 166 approved financial methods.
 * The 122 entries with TYPED_FIELD_HINTS can support validated, untrusted drafts.
 * Other entries must fail closed until their source/evidence contract is approved.
 */
const HEADER="formCode\tcatalog\tformKind\taggregateRoot\tgrain\tfieldContract\tfieldEvidence\tqualificationStatus";
export const CATALOG_CAPTURE_VERSION="FIN-CATALOG-CAPTURE-1";
export const MAX_CATALOG_ROWS=200;
export const CAPTURE_LOCALES=["ru","kk","en"] as const;
export type CaptureLocale=typeof CAPTURE_LOCALES[number];
export type FieldType="REF"|"CODE"|"DATE"|"MONEY"|"DECIMAL"|"PERIOD"|"ENUM"|"INTEGER"|"TEXT"|"BOOLEAN";
export type CaptureMode="TYPED_DRAFT"|"EVIDENCE_ONLY"|"SCHEMA_NOT_APPROVED";
export interface CatalogField {readonly code:string;readonly type:FieldType;}
export interface CatalogForm {
  readonly code:string;readonly catalog:string;readonly kind:string;
  readonly aggregateRoot:string;readonly grain:string;
  readonly evidence:string;readonly qualification:string;
  readonly captureMode:CaptureMode;readonly fields:readonly CatalogField[];
}
export interface CatalogCaptureContext {
  readonly formCode:string;readonly projectCode:string;readonly locale:CaptureLocale;
  readonly periodType:PeriodType;readonly periodCode:string;
  readonly coverage:ValueCoverage;readonly currency:"KZT";readonly amountScale:1|1000|1000000;
}
export interface CatalogRow {readonly rowId:string;readonly cells:Readonly<Record<string,string>>;}
export interface CatalogFinding {readonly code:string;readonly rowId?:string;readonly fieldCode?:string;readonly sourceCell?:string;}
export interface CatalogPreview {
  readonly status:"UNQUALIFIED_DRAFT";readonly publishable:false;readonly context:CatalogCaptureContext;
  readonly rows:readonly CatalogRow[];readonly findings:readonly CatalogFinding[];readonly valid:boolean;
}

const TYPES=new Set<FieldType>(["REF","CODE","DATE","MONEY","DECIMAL","PERIOD","ENUM","INTEGER","TEXT","BOOLEAN"]);
const KINDS=new Set(["STRUCTURED","STRUCTURED_INPUT","COMPOSITE","SECTOR_INPUT","DOCUMENT","EXTERNAL_TAX"]);
function fail(code:string):never {throw new Error(code);}
export function parseCaptureCatalog(text:string):readonly CatalogForm[] {
  const lines=text.trimEnd().split(/\r?\n/);
  if(lines.shift()!==HEADER)fail("CATALOG_HEADER_DRIFT");
  const codes=new Set<string>();
  const forms:CatalogForm[]=lines.map(line=>{
    const c=line.split("\t");
    if(c.length!==8||c.some(x=>!x.trim()))fail("CATALOG_INVALID_ROW");
    const [code,catalog,kind,root,grain,contract,evidence,qualification]=c as [string,string,string,string,string,string,string,string];
    if(!/^[A-Z][A-Z0-9-]{0,127}$/.test(code)||codes.has(code)||!KINDS.has(kind)||
       !["FCP_TD","DF","SF"].includes(catalog)||
       !["CATALOG_SUMMARY","TYPED_FIELD_HINTS"].includes(evidence)||
       !["DESIGN_ONLY","PILOT_UNQUALIFIED"].includes(qualification))
      fail("CATALOG_UNAPPROVED_OR_DUPLICATE_FORM");
    codes.add(code);
    let fields:CatalogField[]=[];
    let captureMode:CaptureMode="SCHEMA_NOT_APPROVED";
    if(evidence==="TYPED_FIELD_HINTS"){
      if(!["STRUCTURED_INPUT","SECTOR_INPUT"].includes(kind))fail("CATALOG_TYPED_KIND_MISMATCH");
      const fieldCodes=new Set<string>();
      fields=contract.split(";").map(token=>{
        const parts=token.split(":");
        if(parts.length!==2||!/^[A-Za-z][A-Za-z0-9_]*$/.test(parts[0]!)||
           !TYPES.has(parts[1] as FieldType)||fieldCodes.has(parts[0]!))
          fail("CATALOG_INVALID_TYPED_FIELD:"+code);
        fieldCodes.add(parts[0]!);
        return {code:parts[0]!,type:parts[1] as FieldType};
      });
      if(fields.length<1||fields.length>20)fail("CATALOG_FIELD_BUDGET");
      captureMode="TYPED_DRAFT";
    }else if(["DOCUMENT","EXTERNAL_TAX"].includes(kind)){
      captureMode="EVIDENCE_ONLY";
    }
    return {code,catalog,kind,aggregateRoot:root,grain,
      evidence,qualification,captureMode,fields};
  });
  if(forms.length!==166||codes.size!==166)fail("CATALOG_COUNT_DRIFT");
  if(forms.filter(f=>f.captureMode==="TYPED_DRAFT").length!==122 ||
     forms.filter(f=>f.captureMode==="EVIDENCE_ONLY").length!==12 ||
     forms.filter(f=>f.captureMode==="SCHEMA_NOT_APPROVED").length!==32)
    fail("CATALOG_READINESS_DRIFT");
  return Object.freeze(forms);
}
export const CAPTURE_CATALOG=parseCaptureCatalog(catalogText);
const index=new Map(CAPTURE_CATALOG.map(form=>[form.code,form]));
export function getCaptureForm(code:string):CatalogForm {
  const result=index.get(code);
  if(!result)fail("CATALOG_UNKNOWN_FORM:"+code);
  return result!;
}
function year(y:number):boolean {return Number.isInteger(y)&&y>=1900&&y<=9998;}
function canonicalDate(raw:string):string {
  if(!/^\d{4}-\d{2}-\d{2}$/.test(raw))fail("INVALID_DATE");
  const [y,m,d]=raw.split("-").map(Number);
  if(!year(y!)||m!<1||m!>12||d!<1||d!>31)fail("INVALID_DATE");
  const date=new Date(Date.UTC(2000,m!-1,d));
  date.setUTCFullYear(y!);
  if(date.toISOString().slice(0,10)!==raw)fail("INVALID_DATE");
  return raw;
}
function canonicalPeriod(raw:string):string {
  const month=/^(\d{4})-(0[1-9]|1[0-2])$/.exec(raw);
  const value=month?month[1]+"."+month[2]:raw;
  let type:PeriodType;
  if(/^\d{4}\.(0[1-9]|1[0-2])$/.test(value))type="MONTH";
  else if(/^\d{4}-W\d{2}$/.test(value))type="WEEK";
  else if(/^\d{4}-Q[1-4]$/.test(value))type="QUARTER";
  else if(/^\d{4}-H[12]$/.test(value))type="HALF_YEAR";
  else if(/^\d{4}$/.test(value))type="YEAR";
  else fail("INVALID_PERIOD");
  try{resolvePeriod(type,value);}catch{fail("INVALID_PERIOD");}
  return value;
}
function canonicalInteger(raw:string):string {
  if(!/^-?[0-9]{1,26}$/.test(raw))fail("INVALID_INTEGER");
  const negative=raw[0]==="-";
  const stripped=(negative?raw.slice(1):raw).replace(/^0+(?=\d)/,"");
  return (negative&&stripped!=="0"?"-":"")+stripped;
}
export function normalizeTypedValue(type:FieldType,raw:string):string {
  if(typeof raw!=="string")fail("INVALID_VALUE_TYPE");
  const value=raw.trim();
  if(!value)return "";
  if(type==="MONEY"||type==="DECIMAL"){
    try {return normalizeDecimal(value);}catch{fail("INVALID_DECIMAL");}
  }
  if(type==="INTEGER")return canonicalInteger(value);
  if(type==="DATE")return canonicalDate(value);
  if(type==="PERIOD")return canonicalPeriod(value);
  if(type==="BOOLEAN"){
    if(value!=="true"&&value!=="false")fail("INVALID_BOOLEAN");
    return value;
  }
  if(type==="TEXT"){
    if(value.length>2000||/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value))
      fail("INVALID_TEXT");
    return value.normalize("NFC");
  }
  if(value.length>120||!/^[\p{L}\p{N}_./:-]+$/u.test(value)||
     /^[=+@-]/.test(value))fail("INVALID_REFERENCE");
  // Field values are not identities. No implicit transliteration or classification.
  return value.normalize("NFC");
}
export function validateCatalogCapture(context:CatalogCaptureContext,
                                      rows:readonly CatalogRow[]):CatalogPreview {
  const form=getCaptureForm(context.formCode);
  if(form.captureMode!=="TYPED_DRAFT")
    fail("CATALOG_SCHEMA_NOT_QUALIFIED:"+form.code);
  const findings:CatalogFinding[]=[];
  if(!CAPTURE_LOCALES.includes(context.locale))findings.push({code:"TRANSLATION_NOT_READY"});
  if(!/^[\p{L}\p{N}_-]{1,50}$/u.test(context.projectCode))
    findings.push({code:"PROJECT_CODE_REQUIRED"});
  if(context.currency!=="KZT"||![1,1000,1000000].includes(context.amountScale))
    findings.push({code:"MONEY_CONTEXT_UNSUPPORTED"});
  try{resolvePeriod(context.periodType,context.periodCode,context.coverage);}
  catch{findings.push({code:"INVALID_REPORTING_PERIOD"});}
  if(!Array.isArray(rows)||rows.length<1||rows.length>MAX_CATALOG_ROWS)
    findings.push({code:"CATALOG_ROW_COUNT_INVALID"});
  const seen=new Set<string>();
  const normalized:CatalogRow[]=[];
  for(const row of Array.isArray(rows)?rows.slice(0,MAX_CATALOG_ROWS):[]){
    if(!row||!/^[A-Za-z0-9_-]{1,60}$/.test(row.rowId)||seen.has(row.rowId)){
      findings.push({code:"CATALOG_DUPLICATE_OR_INVALID_ROW_ID",rowId:row?.rowId});
      continue;
    }
    seen.add(row.rowId);
    if(!row.cells||typeof row.cells!=="object"||Array.isArray(row.cells)){
      findings.push({code:"CATALOG_INVALID_CELLS",rowId:row.rowId});continue;
    }
    const allowed=new Map(form.fields.map(f=>[f.code,f.type]));
    for(const key of Object.keys(row.cells))
      if(!allowed.has(key)) findings.push({code:"CATALOG_UNKNOWN_FIELD",rowId:row.rowId,fieldCode:key});
    const values:Record<string,string>={};
    for(const field of form.fields){
      const input=row.cells[field.code]??"";
      try{values[field.code]=normalizeTypedValue(field.type,input);}
      catch(e){findings.push({code:e instanceof Error?e.message:"CATALOG_INVALID_VALUE",
        rowId:row.rowId,fieldCode:field.code});values[field.code]=input;}
    }
    if(form.fields.every(field=>!values[field.code]?.trim()))
      findings.push({code:"CATALOG_EMPTY_ROW",rowId:row.rowId});
    normalized.push({rowId:row.rowId,cells:values});
  }
  return {status:"UNQUALIFIED_DRAFT",publishable:false,context,rows:normalized,
    findings,valid:findings.length===0};
}
