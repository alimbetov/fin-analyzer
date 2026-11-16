import schemaText from "../../backend/src/main/resources/fin/specialized-schema-candidates-r6.tsv?raw";
import {getCaptureForm, normalizeTypedValue, type FieldType, type CatalogRow, type CaptureLocale} from "./catalog-schema";

/**
 * R6.1 candidates are derived from broad form descriptions, not independent
 * financial approval. They are NEVER published as approved financial facts.
 * Do not silently replace dedicated F01/F02/F03 reviewed source contracts.
 */
export type InvariantCode=
  "NONE"|"NONNEGATIVE_LEDGER"|"BALANCE_IDENTITY"|"CASH_ROLLFORWARD"|
  "EQUITY_ROLLFORWARD"|"TRIAL_BALANCE_ROW"|"FORECAST_CASH_ROLL"|
  "DEBT_PRINCIPAL_ROLL"|"REFERENCE_ONLY";
export interface CandidateField {readonly code:string;readonly type:FieldType;}
export interface SpecializedCandidate {
  readonly code:string;readonly layout:string;readonly fields:readonly CandidateField[];
  readonly invariant:InvariantCode;readonly qualification:"SCHEMA_CANDIDATE_NOT_APPROVED";
}
export interface SpecializedFinding {
  readonly code:string;readonly rowId?:string;readonly fieldCode?:string;
  readonly expected?:string;readonly actual?:string;
}
export interface SpecializedPreview {
  readonly code:string;readonly rows:readonly CatalogRow[];
  readonly findings:readonly SpecializedFinding[];
  readonly acceptedAsUnqualifiedDraft:boolean;
  readonly methodologyApproved:false;readonly publishable:false;
}
const TYPES=new Set(["REF","CODE","DATE","MONEY","DECIMAL","PERIOD","ENUM","INTEGER","TEXT","BOOLEAN"]);
const RULES=new Set<InvariantCode>([
  "NONE","NONNEGATIVE_LEDGER","BALANCE_IDENTITY","CASH_ROLLFORWARD",
  "EQUITY_ROLLFORWARD","TRIAL_BALANCE_ROW","FORECAST_CASH_ROLL",
  "DEBT_PRINCIPAL_ROLL","REFERENCE_ONLY"
]);
const HEADER="formCode\tlayout\tfields\tpreliminaryInvariant\tqualification";
function failure(code:string):never{throw new Error(code);}
export function parseSpecializedCandidates(text:string):readonly SpecializedCandidate[]{
  const lines=text.trimEnd().split(/\r?\n/);
  if(lines.shift()!==HEADER)failure("SPECIALIZED_HEADER_DRIFT");
  const seen=new Set<string>();
  const result=lines.map(line=>{
    const row=line.split("\t");
    if(row.length!==5)failure("SPECIALIZED_BAD_ROW");
    const [code,layout,fieldDefs,rule,status]=row as [string,string,string,string,string];
    if(!/^(F0[1-8]|C(0[1-9]|1[0-4])|P(0[1-9]|10))$/.test(code)||
       seen.has(code)||!/^[A-Z][A-Z0-9_]+$/.test(layout)||
       !RULES.has(rule as InvariantCode)||status!=="SCHEMA_CANDIDATE_NOT_APPROVED")
       failure("SPECIALIZED_UNQUALIFIED_OR_DUPLICATE");
    seen.add(code);
    const fields:CandidateField[]=[];
    const fieldCodes=new Set<string>();
    for(const field of fieldDefs.split(";")){
      const parts=field.split(":");
      if(parts.length!==2||!/^[A-Za-z][A-Za-z0-9_]*$/.test(parts[0]!)||
         !TYPES.has(parts[1]!)||fieldCodes.has(parts[0]!))
        failure("SPECIALIZED_INVALID_FIELDS");
      fieldCodes.add(parts[0]!);
      fields.push({code:parts[0]!,type:parts[1] as FieldType});
    }
    if(fields.length<2||fields.length>18)failure("SPECIALIZED_FIELD_COUNT");
    const catalog=getCaptureForm(code);
    if(catalog.captureMode!=="SCHEMA_NOT_APPROVED")
      failure("SPECIALIZED_MUST_NOT_OVERRIDE_APPROVED_SCHEMA");
    if(rule==="REFERENCE_ONLY"&&fields.some(f=>f.type==="MONEY"))
      failure("SPECIALIZED_COMPOSITE_MUST_REFERENCE_SOURCES");
    return {code,layout,fields,invariant:rule as InvariantCode,
            qualification:"SCHEMA_CANDIDATE_NOT_APPROVED" as const};
  });
  if(seen.size!==32||result.length!==32)failure("SPECIALIZED_COVERAGE_DRIFT");
  const missing=["C01","C14","F01","F08","P01","P10"].filter(s=>!seen.has(s));
  if(missing.length)failure("SPECIALIZED_COVERAGE_DRIFT");
  return result;
}
export const SPECIALIZED_CANDIDATES=parseSpecializedCandidates(schemaText);
const INDEX=new Map(SPECIALIZED_CANDIDATES.map(s=>[s.code,s]));
export function requireSpecializedCandidate(code:string):SpecializedCandidate {
  const value=INDEX.get(code);
  if(!value)failure("SPECIALIZED_UNKNOWN_FORM:"+code);
  return value!;
}
// Only integer arithmetic, 12 exact decimal places, no JS Number rounding.
function scaled(input:string):bigint {
  if(!/^-?\d{1,26}(\.\d{1,12})?$/.test(input))
    failure("SPECIALIZED_NOT_EXACT_DECIMAL");
  const neg=input.startsWith("-");
  const [integer,decimal=""]=(neg?input.slice(1):input).split(".");
  return (neg?-1n:1n)*BigInt(integer!+decimal.padEnd(12,"0"));
}
function money(n:bigint):string{
  const neg=n<0n?"-":"";
  const abs=n<0n?-n:n;
  const i=abs/1000000000000n;
  const d=(abs%1000000000000n).toString().padStart(12,"0").replace(/0+$/,"");
  return neg+i.toString()+(d?"."+d:"");
}
type Rule={result:string;terms:readonly string[];signs:readonly number[]};
const CHECKS:Partial<Record<InvariantCode,Rule>>={
  BALANCE_IDENTITY:{result:"assets",terms:["liabilities","equity"],signs:[1,1]},
  CASH_ROLLFORWARD:{result:"closingCash",terms:["openingCash","cfo","cfi","cff"],signs:[1,1,1,1]},
  EQUITY_ROLLFORWARD:{result:"closingEquity",terms:["openingEquity","contributions","dividends","otherChanges"],signs:[1,1,-1,1]},
  FORECAST_CASH_ROLL:{result:"closingCash",terms:["openingCash","inflows","outflows"],signs:[1,1,-1]},
  DEBT_PRINCIPAL_ROLL:{result:"closingPrincipal",terms:["openingPrincipal","drawdown","principalRepayment"],signs:[1,1,-1]}
};
export function validateSpecializedCandidate(
  code:string, rows:readonly CatalogRow[], locale:CaptureLocale
):SpecializedPreview {
  const schema=requireSpecializedCandidate(code);
  const findings:SpecializedFinding[]=[];
  if(!["ru","kk","en"].includes(locale))
    findings.push({code:"TRANSLATION_NOT_READY"});
  if(!Array.isArray(rows)||rows.length===0||rows.length>200)
    findings.push({code:"SPECIALIZED_ROW_COUNT_INVALID"});
  const normalized:CatalogRow[]=[];
  const ids=new Set<string>();
  for(const row of Array.isArray(rows)?rows.slice(0,200):[]){
    if(!row||typeof row.rowId!=="string"||!/^[A-Za-z0-9_-]{1,60}$/.test(row.rowId)||
        ids.has(row.rowId)){
      findings.push({code:"SPECIALIZED_INVALID_OR_DUPLICATE_ROW",rowId:row?.rowId});
      continue;
    }
    ids.add(row.rowId);
    if(!row.cells||Array.isArray(row.cells)||typeof row.cells!=="object"){
      findings.push({code:"SPECIALIZED_INVALID_CELLS",rowId:row.rowId});continue;
    }
    const fieldMap=new Map(schema.fields.map(f=>[f.code,f.type]));
    for(const inputCode of Object.keys(row.cells))
      if(!fieldMap.has(inputCode))
        findings.push({code:"SPECIALIZED_UNKNOWN_FIELD",rowId:row.rowId,fieldCode:inputCode});
    const values:Record<string,string>={};
    for(const field of schema.fields){
      try{values[field.code]=normalizeTypedValue(field.type,row.cells[field.code]??"",locale);}
      catch(e){
        findings.push({code:e instanceof Error?e.message:"SPECIALIZED_INVALID_VALUE",
          rowId:row.rowId,fieldCode:field.code});
        values[field.code]=row.cells[field.code]??"";
      }
    }
    if(Object.values(values).every(v=>!v))
      findings.push({code:"SPECIALIZED_EMPTY_ROW",rowId:row.rowId});
    const check=CHECKS[schema.invariant];
    if(check){
      const fields=[check.result,...check.terms];
      if(fields.every(f=>values[f]!==""&&values[f]!==undefined)){
        try{
          const expected=check.terms.reduce((acc,field,i)=>acc+
             BigInt(check.signs[i]!)*scaled(values[field]!),0n);
          const actual=scaled(values[check.result]!);
          if(expected!==actual)findings.push({
            code:"SPECIALIZED_PROVISIONAL_INVARIANT_MISMATCH",rowId:row.rowId,
            fieldCode:check.result,expected:money(expected),actual:money(actual)
          });
        }catch{
          // The per-field decimal validator has already emitted a precise error.
        }
      }else findings.push({code:"SPECIALIZED_CHECK_NOT_EVALUATED",rowId:row.rowId});
    }
    // Do not enforce a universal accounting equation for F06 trial balance:
    // debit/credit normal side and account polarity are absent from this draft.
    if(schema.invariant==="TRIAL_BALANCE_ROW")
      findings.push({code:"SPECIALIZED_METHOD_REVIEW_REQUIRED",rowId:row.rowId});
    normalized.push({rowId:row.rowId,cells:values});
  }
  const blocking=findings.filter(f=>f.code!=="SPECIALIZED_CHECK_NOT_EVALUATED"&&
                                  f.code!=="SPECIALIZED_METHOD_REVIEW_REQUIRED");
  return {code,rows:normalized,findings,
          acceptedAsUnqualifiedDraft:blocking.length===0,
          methodologyApproved:false,publishable:false};
}
