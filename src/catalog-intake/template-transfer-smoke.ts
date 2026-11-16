import ExcelJS from "exceljs";
import {
  CAPTURE_CATALOG, getCaptureForm, normalizeTypedValue, validateCatalogCapture,
  type CatalogCaptureContext, type CatalogField, type CatalogRow
} from "./catalog-schema";
import {SPECIALIZED_CANDIDATES,validateSpecializedCandidate} from "./specialized-contracts";
import {generateTypedCatalogExcel,parseTypedCatalogExcel} from "./catalog-excel";

/**
 * Test-only structural transfer service. Uses the real XLSX issuer and parser,
 * never POSTs, mutates a database, approves a source or computes financial KPI.
 * Independent typed example values are compared at every exact source coordinate
 * BEFORE any possible persistence call.
 */
export const STRUCTURAL_TRANSFER_VERSION = "FIN-TEMPLATE-STRUCTURAL-SMOKE-1";
export const EDITABLE_CATALOG = CAPTURE_CATALOG.filter(form=>
  form.captureMode==="TYPED_DRAFT" ||
  SPECIALIZED_CANDIDATES.some(candidate=>candidate.code===form.code)
);

export interface StructuralTransferReceipt {
  readonly version:typeof STRUCTURAL_TRANSFER_VERSION;
  readonly formCode:string;
  readonly templateVersion:"FIN-CATALOG-XLSX-2";
  readonly fieldCount:number;
  readonly rowCount:number;
  readonly checkedCoordinates:readonly string[];
  readonly context:CatalogCaptureContext;
  readonly status:"STRUCTURAL_PRE_SAVE_MATCH_UNQUALIFIED";
  readonly published:false;
  readonly persisted:false;
}

/** Source fixtures, not methodologically qualified money or a financial forecast. */
export function sampleStructuralCell(
  field:CatalogField, index:number, context:CatalogCaptureContext
):string {
  switch(field.type) {
    case "REF": return "REF_"+String(index+101);
    case "CODE": return field.code==="currency"?context.currency:"CODE_"+String(index+101);
    case "DATE": return "2026-10-"+String(index%27+1).padStart(2,"0");
    case "MONEY": return "1000."+String(index+101);
    case "DECIMAL": return "12."+String(index+101);
    case "PERIOD": return context.periodType==="AS_OF"?"2026.10":context.periodCode;
    case "ENUM": return "OPTION_"+String(index+101);
    case "INTEGER": return String(index+101);
    case "TEXT": return "Synthetic source text "+field.code+" "+String(index);
    case "BOOLEAN": return index%2===0?"true":"false";
  }
}

/** Preliminary identity controls only: do not infer accounting validation. */
const BALANCED_EXAMPLES:Readonly<Record<string,Readonly<Record<string,string>>>>={
  F01:{assets:"908",liabilities:"601",equity:"307"},
  P01:{assets:"908",liabilities:"601",equity:"307"},
  F03:{openingCash:"501",cfo:"211",cfi:"37",cff:"43",closingCash:"792"},
  F04:{openingEquity:"503",contributions:"211",dividends:"37",
       otherChanges:"43",closingEquity:"720"},
  P03:{openingCash:"503",inflows:"211",outflows:"37",closingCash:"677"},
  P04:{openingPrincipal:"503",drawdown:"211",principalRepayment:"37",
       closingPrincipal:"677"}
};

export function sourceFieldFixtures(context:CatalogCaptureContext):Readonly<Record<string,string>> {
  const form=getCaptureForm(context.formCode);
  const candidate=SPECIALIZED_CANDIDATES.find(x=>x.code===form.code);
  const fields=candidate?.fields??form.fields;
  if(form.captureMode!=="TYPED_DRAFT"&&!candidate)
    throw new Error("SMOKE_NOT_NUMERIC_EDITABLE:"+form.code);
  const overrides=BALANCED_EXAMPLES[form.code]??{};
  const result:Record<string,string>={};
  for(const [index,field] of fields.entries()) {
    const raw=overrides[field.code]??sampleStructuralCell(field,index,context);
    result[field.code]=normalizeTypedValue(field.type,raw,context.locale);
  }
  return result;
}

function identicalSourceFields(
  expected:Readonly<Record<string,string>>, received:Readonly<Record<string,string>>
):void {
  const required=Object.keys(expected).sort();
  const actual=Object.keys(received).sort();
  if(JSON.stringify(required)!==JSON.stringify(actual))
    throw new Error("SMOKE_FIELD_SET_CHANGED");
  for(const field of required)
    if(received[field]!==expected[field])
      throw new Error("SMOKE_FIELD_VALUE_MISMATCH:"+field);
}

/** Generate → fill all cells sequentially → serialize → reparse → validate DTO. */
export async function verifyCatalogTemplateTransfer(
  context:CatalogCaptureContext
):Promise<StructuralTransferReceipt> {
  const expected=sourceFieldFixtures(context);
  const book=new ExcelJS.Workbook();
  const issued=await generateTypedCatalogExcel(context);
  const issuedBytes=new Uint8Array(await issued.arrayBuffer());
  await book.xlsx.load(issuedBytes as unknown as Parameters<typeof book.xlsx.load>[0]);
  const sheet=book.getWorksheet("FIN_Catalog");
  if(!sheet)throw new Error("SMOKE_TEMPLATE_SHEET_MISSING");
  const fields=Object.keys(expected);
  // Fill every declared field, using intentionally distinct values and exact strings.
  for(const [index,fieldCode] of fields.entries())
    sheet.getCell(4,index+2).value=expected[fieldCode]!;
  const serialized=await book.xlsx.writeBuffer();
  const received=await parseTypedCatalogExcel(
    new Uint8Array(serialized as ArrayBuffer).slice().buffer as ArrayBuffer,context);
  if(!received.valid||received.publishable||received.status!=="UNQUALIFIED_DRAFT"||
     received.rows.length!==1||received.rows[0]?.rowId!=="ROW-001")
    throw new Error("SMOKE_PRE_SAVE_DTO_REJECTED:"+context.formCode+":"+
      received.findings.map(f=>f.code).join(","));
  if(JSON.stringify(received.context)!==JSON.stringify(context))
    throw new Error("SMOKE_CONTEXT_LOST:"+context.formCode);
  identicalSourceFields(expected,received.rows[0].cells);
  // Separate manual pre-save normalization must agree with the XLSX path.
  const candidate=SPECIALIZED_CANDIDATES.find(x=>x.code===context.formCode);
  const row:CatalogRow={rowId:"ROW-001",cells:expected};
  const manual=candidate
    ?validateSpecializedCandidate(context.formCode,[row],context.locale)
    :validateCatalogCapture(context,[row]);
  const manualValid="acceptedAsUnqualifiedDraft" in manual
    ?manual.acceptedAsUnqualifiedDraft:manual.valid;
  if(!manualValid)throw new Error("SMOKE_MANUAL_PARITY_REJECTED:"+context.formCode);
  identicalSourceFields(manual.rows[0]!.cells,received.rows[0].cells);
  return {
    version:STRUCTURAL_TRANSFER_VERSION,formCode:context.formCode,
    templateVersion:"FIN-CATALOG-XLSX-2",fieldCount:fields.length,rowCount:1,
    checkedCoordinates:fields.map((code,i)=>"FIN_Catalog!"+String.fromCharCode(66+i)+"4:"+code),
    context,status:"STRUCTURAL_PRE_SAVE_MATCH_UNQUALIFIED",
    published:false,persisted:false
  };
}
