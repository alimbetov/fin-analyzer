import {describe,it,expect} from "vitest";
import ExcelJS from "exceljs";
import {
  CAPTURE_CATALOG,type CatalogCaptureContext,type CatalogField
} from "./catalog-schema";
import {generateTypedCatalogExcel,parseTypedCatalogExcel,TYPED_CATALOG_TEMPLATE_COUNT} from "./catalog-excel";
import {fieldPresentation,formHasAllHumanLabels} from "./field-presentation-r6";

const ctx:CatalogCaptureContext={
  formCode:"DF-AST-01",projectCode:"PILOT_1",locale:"ru",
  periodType:"MONTH",periodCode:"2026.10",coverage:"PERIOD_ONLY",
  currency:"KZT",amountScale:1
};
function example(field:CatalogField):string{
  switch(field.type){
    case "REF":return "R-001";
    case "CODE":return "CAT_A";
    case "DATE":return "2026-10-30";
    case "MONEY":return "123.456";
    case "DECIMAL":return "1.250";
    case "PERIOD":return "2026-10";
    case "ENUM":return "TYPE_A";
    case "INTEGER":return "23";
    case "TEXT":return "Русский финансовый комментарий";
    case "BOOLEAN":return "false";
  }
}
async function readBook(blob:Blob):Promise<ExcelJS.Workbook>{
  const book=new ExcelJS.Workbook();
  await book.xlsx.load(new Uint8Array(await blob.arrayBuffer()) as unknown as Parameters<typeof book.xlsx.load>[0]);
  return book;
}
async function bytes(book:ExcelJS.Workbook):Promise<ArrayBuffer>{
  const buffer=await book.xlsx.writeBuffer();
  return new Uint8Array(buffer as ArrayBuffer).slice().buffer as ArrayBuffer;
}
const typed=CAPTURE_CATALOG.filter(f=>f.captureMode==="TYPED_DRAFT");

describe("FIN catalog typed workbook integration, per form code",()=>{
  it("all 122 forms have independently addressable, safe machine-coded workbook contracts",()=>{
    expect(TYPED_CATALOG_TEMPLATE_COUNT).toBe(122);
    expect(new Set(typed.map(f=>f.code)).size).toBe(122);
  });
  it.each(typed)("$code — creates localized Excel, preserves type/field schema, and parses a typed row",async form=>{
    const context={...ctx,formCode:form.code};
    const book=await readBook(await generateTypedCatalogExcel(context));
    const data=book.getWorksheet("FIN_Catalog")!;
    expect(data.getCell("B1").value).toBe(form.code);
    expect(book.getWorksheet("_FIN_Metadata")?.state).toBe("veryHidden");
    for(const [i,field] of form.fields.entries()){
      expect(data.getCell(2,i+2).value).toBe(fieldPresentation(form.code,field,context.locale).label);
      expect(data.getCell(3,i+2).value).toBe(field.type);
      data.getCell(4,i+2).value=example(field);
    }
    const parsed=await parseTypedCatalogExcel(await bytes(book),context);
    expect(parsed.valid,JSON.stringify(parsed.findings)).toBe(true);
    expect(parsed.status).toBe("UNQUALIFIED_DRAFT");
    expect(parsed.publishable).toBe(false);
    expect(parsed.rows).toHaveLength(1);
    expect(parsed.rows[0]?.rowId).toBe("ROW-001");
    for(const field of form.fields)
      expect(parsed.rows[0]?.cells[field.code]).toBeDefined();
  },30000);
  it.each(["ru","kk","en"] as const)("template headers use exact %s source locale, without inventing translated field names",async locale=>{
    const book=await readBook(await generateTypedCatalogExcel({...ctx,locale}));
    const info=book.getWorksheet("Instructions")!;
    expect(info.getCell("A1").value).toBeTruthy();
    const fields=book.getWorksheet("FIN_Catalog")!;
    expect(fields.getCell("B2").value).toBe(fieldPresentation(ctx.formCode,{code:"assetRef",type:"REF"},locale).label);
    expect(fields.getCell("B1").value).toBe(ctx.formCode);
    expect(fields.getCell("G1").value).toBe(locale);
    fields.getCell("B4").value="R-001";
    fields.getCell("C4").value="CAT_A";
    const preview=await parseTypedCatalogExcel(await bytes(book),{...ctx,locale:"en"});
    expect(preview.context.locale).toBe(locale);
    expect(preview.valid).toBe(true);
  });
  it("rejects a forged field name, hidden metadata, far-away row and formulas",async()=>{
    const book=await readBook(await generateTypedCatalogExcel(ctx));
    const data=book.getWorksheet("FIN_Catalog")!;
    data.getCell("B2").value="inventedFinancialMeasure";
    await expect(parseTypedCatalogExcel(await bytes(book),ctx)).rejects.toThrow("CATALOG_XLSX_FIELD_SCHEMA_CHANGED");
    data.getCell("B2").value=fieldPresentation(ctx.formCode,{code:"assetRef",type:"REF"},"ru").label;
    book.getWorksheet("_FIN_Metadata")!.getCell("B2").value="F02";
    await expect(parseTypedCatalogExcel(await bytes(book),ctx)).rejects.toThrow("CATALOG_XLSX_META_CHANGED");
    book.getWorksheet("_FIN_Metadata")!.getCell("B2").value=ctx.formCode;
    data.getCell("A900").value="FORGED";
    await expect(parseTypedCatalogExcel(await bytes(book),ctx)).rejects.toThrow("CATALOG_XLSX_EXTRA_ROW");
    data.getCell("A900").value=null;
    data.getCell("B4").value={formula:"1+1",result:2};
    await expect(parseTypedCatalogExcel(await bytes(book),ctx)).rejects.toThrow("CATALOG_XLSX_FORMULAS_FORBIDDEN");
  });
  it("invalid amount stays untrusted, reported at exact Excel address",async()=>{
    const book=await readBook(await generateTypedCatalogExcel(ctx));
    const data=book.getWorksheet("FIN_Catalog")!;
    const idx=CAPTURE_CATALOG.find(f=>f.code===ctx.formCode)!.fields.findIndex(f=>f.type==="MONEY");
    const field=CAPTURE_CATALOG.find(f=>f.code===ctx.formCode)!.fields[idx]!;
    data.getCell(4,idx+2).value="0.1234567890123";
    const result=await parseTypedCatalogExcel(await bytes(book),ctx);
    expect(result.valid).toBe(false);
    expect(result.findings).toContainEqual(expect.objectContaining({
      code:"INVALID_DECIMAL",fieldCode:field.code,sourceCell:"FIN_Catalog!"+String.fromCharCode(66+idx)+"4"
    }));
  });
  it("visible report passport supplies organization status, form, period, currency, scale and exact template version",async()=>{
    const book=await readBook(await generateTypedCatalogExcel(ctx));
    const info=book.getWorksheet("Instructions")!;
    expect(info.getCell("A5").value).toBe("Паспорт отчёта");
    expect(info.getCell("B6").value).toContain("Не привязано к организации");
    expect(info.getCell("B7").value).toContain("DF-AST-01");
    expect(info.getCell("B8").value).toBe("PILOT_1");
    expect(info.getCell("B9").value).toContain("2026.10");
    expect(info.getCell("B10").value).toBe("KZT");
    expect(info.getCell("B11").value).toBe("1");
    expect(info.getCell("B12").value).toBe("FIN-CATALOG-XLSX-2");
    expect(info.getCell("B14").value).toBe("UNQUALIFIED_DRAFT");
    expect(book.getWorksheet("FIN_Catalog")!.getCell("A1").value).toBe("FIN-CATALOG-XLSX-2");
    expect(formHasAllHumanLabels(ctx.formCode,
      CAPTURE_CATALOG.find(f=>f.code===ctx.formCode)!.fields,"ru")).toBe(true);
    expect(formHasAllHumanLabels("SF-C01",
      CAPTURE_CATALOG.find(f=>f.code==="SF-C01")!.fields,"zh-Hans")).toBe(false);
  });
  it("rejects edited report passport without allowing a false organization or scale",async()=>{
    const book=await readBook(await generateTypedCatalogExcel(ctx));
    book.getWorksheet("Instructions")!.getCell("B11").value="1000000";
    await expect(parseTypedCatalogExcel(await bytes(book),ctx))
      .rejects.toThrow("CATALOG_XLSX_REPORT_PASSPORT_CHANGED");
  });
  it("can import existing FIN-CATALOG-CAPTURE-1 workbook without breaking legacy user exports",async()=>{
    const book=await readBook(await generateTypedCatalogExcel(ctx));
    const data=book.getWorksheet("FIN_Catalog")!;
    data.getCell("A1").value="FIN-CATALOG-CAPTURE-1";
    const form=CAPTURE_CATALOG.find(f=>f.code===ctx.formCode)!;
    for(const [i,field] of form.fields.entries())
      data.getCell(2,i+2).value=field.code;
    book.getWorksheet("_FIN_Metadata")!.getCell("B1").value="FIN-CATALOG-CAPTURE-1";
    const info=book.getWorksheet("Instructions")!;
    for(let row=5;row<=14;row++){
      info.getCell(row,1).value=null;
      info.getCell(row,2).value=null;
    }
    data.getCell("B4").value="R-001";
    data.getCell("C4").value="TYPE_A";
    const parsed=await parseTypedCatalogExcel(await bytes(book),ctx);
    expect(parsed.valid).toBe(true);
    expect(parsed.rows[0]?.cells.assetRef).toBe("R-001");
  });
  it("unsupported catalog-only F02 and evidence D01 do not emit a misleading typed workbook",async()=>{
    await expect(generateTypedCatalogExcel({...ctx,formCode:"F02"}))
      .rejects.toThrow("CATALOG_SCHEMA_NOT_QUALIFIED");
    await expect(generateTypedCatalogExcel({...ctx,formCode:"D01"}))
      .rejects.toThrow("CATALOG_SCHEMA_NOT_QUALIFIED");
  });
});
