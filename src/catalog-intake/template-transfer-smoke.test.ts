import {afterAll,describe,expect,it} from "vitest";
import ExcelJS from "exceljs";
import {mkdirSync,writeFileSync} from "node:fs";
import {
  CAPTURE_CATALOG,type CatalogCaptureContext
} from "./catalog-schema";
import {generateTypedCatalogExcel,parseTypedCatalogExcel} from "./catalog-excel";
import {SPECIALIZED_CANDIDATES} from "./specialized-contracts";
import {
  EDITABLE_CATALOG,verifyCatalogTemplateTransfer,STRUCTURAL_TRANSFER_VERSION
} from "./template-transfer-smoke";

const forms=EDITABLE_CATALOG.map(f=>f.code);
const proof:Record<string,{checkedFields:number;variants:string[]}>={};
const base:CatalogCaptureContext={
  formCode:"DF-AST-01",projectCode:"SMOKE_2026",locale:"ru",
  periodType:"MONTH",periodCode:"2026.10",coverage:"PERIOD_ONLY",
  currency:"KZT",amountScale:1
};
const variants:readonly Omit<CatalogCaptureContext,"formCode"|"projectCode"|"currency">[]=[
  {locale:"ru",periodType:"MONTH",periodCode:"2026.10",coverage:"PERIOD_ONLY",amountScale:1},
  {locale:"kk",periodType:"AS_OF",periodCode:"2026-10-31",coverage:"AS_OF",amountScale:1000},
  {locale:"en",periodType:"YEAR",periodCode:"2026",coverage:"YEAR_TO_DATE",amountScale:1000000}
];
async function save(book:ExcelJS.Workbook):Promise<ArrayBuffer>{
  const bytes=await book.xlsx.writeBuffer();
  return new Uint8Array(bytes as ArrayBuffer).slice().buffer as ArrayBuffer;
}
async function opened(context:CatalogCaptureContext):Promise<ExcelJS.Workbook>{
  const book=new ExcelJS.Workbook();
  const blob=await generateTypedCatalogExcel(context);
  await book.xlsx.load(new Uint8Array(await blob.arrayBuffer())
    as unknown as Parameters<typeof book.xlsx.load>[0]);
  return book;
}

describe("FIN R7 exhaustive XLSX structural transfer: all 166 classified, 154 editable",()=>{
  it("catalog coverage has exactly 122 typed, 32 provisional specialized and 12 evidence-only",()=>{
    expect(CAPTURE_CATALOG).toHaveLength(166);
    expect(EDITABLE_CATALOG).toHaveLength(154);
    expect(SPECIALIZED_CANDIDATES).toHaveLength(32);
    expect(new Set(forms).size).toBe(154);
    expect(CAPTURE_CATALOG.filter(x=>x.captureMode==="EVIDENCE_ONLY")).toHaveLength(12);
    // The 794 schema-field coordinates include unqualified candidates, not approved KPI.
    const allFields=EDITABLE_CATALOG.reduce((sum,x)=>{
      const specialization=SPECIALIZED_CANDIDATES.find(s=>s.code===x.code);
      return sum+(specialization?.fields??x.fields).length;
    },0);
    expect(allFields).toBe(794);
  });

  it.each(forms)("%s — every field survives XLSX → pre-save entity across 3 locales/periods/scales",async formCode=>{
    const variantCodes:string[]=[];
    let expectedFieldCount=0;
    for(const variant of variants) {
      const context={...base,...variant,formCode};
      const verified=await verifyCatalogTemplateTransfer(context);
      expect(verified.status).toBe("STRUCTURAL_PRE_SAVE_MATCH_UNQUALIFIED");
      expect(verified.version).toBe(STRUCTURAL_TRANSFER_VERSION);
      expect(verified.formCode).toBe(formCode);
      expect(verified.templateVersion).toBe("FIN-CATALOG-XLSX-2");
      expect(verified.context).toEqual(context);
      expect(verified.persisted).toBe(false);
      expect(verified.published).toBe(false);
      expect(verified.rowCount).toBe(1);
      expect(verified.checkedCoordinates).toHaveLength(verified.fieldCount);
      expect(new Set(verified.checkedCoordinates).size).toBe(verified.fieldCount);
      if(expectedFieldCount)expect(verified.fieldCount).toBe(expectedFieldCount);
      expectedFieldCount=verified.fieldCount;
      variantCodes.push(context.locale+"/"+context.periodType+"/"+context.periodCode+
        "/KZT/x"+String(context.amountScale));
    }
    proof[formCode]={checkedFields:expectedFieldCount,variants:variantCodes};
  },30000);

  it.each(forms)("%s — rejects wrong project, period, scale and forged currency before persistence",async formCode=>{
    const context={...base,formCode};
    const original=await generateTypedCatalogExcel(context);
    const bytes=await original.arrayBuffer();
    await expect(parseTypedCatalogExcel(bytes,{...context,projectCode:"FOREIGN_PROJECT"}))
      .rejects.toThrow(/CATALOG_XLSX_META_CHANGED|CATALOG_XLSX_PASSPORT_CHANGED/);
    await expect(parseTypedCatalogExcel(bytes,{...context,periodCode:"2026.11"}))
      .rejects.toThrow(/CATALOG_XLSX_META_CHANGED|CATALOG_XLSX_PASSPORT_CHANGED/);
    await expect(parseTypedCatalogExcel(bytes,{...context,amountScale:1000}))
      .rejects.toThrow(/CATALOG_XLSX_META_CHANGED|CATALOG_XLSX_PASSPORT_CHANGED/);
    // The browser catalog only supports KZT; a tampered currency must fail.
    const book=await opened(context);
    book.getWorksheet("_FIN_Metadata")!.getCell(11,2).value="USD";
    await expect(parseTypedCatalogExcel(await save(book),context))
      .rejects.toThrow(/CATALOG_XLSX_META_CHANGED/);
  },30000);

  it.each(CAPTURE_CATALOG.filter(x=>x.captureMode==="EVIDENCE_ONLY").map(x=>x.code))(
    "%s — document/tax evidence has no fabricated numeric XLSX template",
    async formCode=>{
      await expect(generateTypedCatalogExcel({...base,formCode}))
        .rejects.toThrow("CATALOG_SCHEMA_NOT_QUALIFIED");
    }
  );

  afterAll(()=>{
    const out={
      schemaVersion:"FIN_TEMPLATE_TRANSFER_SMOKE_V1",
      generatedAt:"TEST_RUN",releaseStatus:"UNQUALIFIED_STRUCTURAL_ONLY",
      totalCatalog:166,editableExpected:154,evidenceOnlyExpected:12,
      verifiedFormCount:Object.keys(proof).length,
      exercisedFieldCoordinates:Object.values(proof).reduce((n,r)=>n+r.checkedFields,0),
      periodLocaleScaleVariants:variants.length,
      coverage:Object.fromEntries(Object.entries(proof).sort(([a],[b])=>a.localeCompare(b)))
    };
    mkdirSync("test-results",{recursive:true});
    writeFileSync("test-results/fin-template-transfer-smoke.json",JSON.stringify(out,null,2)+"\n");
  });
});
