import { describe, expect, it } from "vitest";
import ExcelJS from "exceljs";
import { generateSectorTemplate, parseSectorTemplate, type SectorTemplateContext } from "./excel";
import { FORM_FIELDS, PILOT_FORMS } from "../../packages/sector-pilot/src/index.mjs";

const context: SectorTemplateContext = {
  company:"ООО Демонстрация",formCode:"SF-G01",modelCode:"G_RETAIL",
  dimension:"sku-1",period:"2026-11",currency:"KZT"
};
async function workbook() {
  const file=await generateSectorTemplate(context);
  const book=new ExcelJS.Workbook();
  await book.xlsx.load(new Uint8Array(await file.arrayBuffer()) as unknown as Parameters<typeof book.xlsx.load>[0]);
  return book;
}
async function asBuffer(book:ExcelJS.Workbook):Promise<ArrayBuffer> {
  const bytes=await book.xlsx.writeBuffer();
  return Uint8Array.from(new Uint8Array(bytes as ArrayBuffer)).buffer as ArrayBuffer;
}
describe("IND-R1 controlled sector XLSX", () => {
  it("round-trips typed inputs without auto-approval", async () => {
    const book=await workbook();
    const sheet=book.getWorksheet("Sector")!;
    sheet.getCell("B5").value=1000;
    sheet.getCell("B6").value=540;
    sheet.getCell("B7").value=90;
    expect(await parseSectorTemplate(await asBuffer(book),context)).toEqual({
      netRevenue:"1000",cogs:"540",soldQty:"90"
    });
  });
  it("all nine pilot forms use the same field contract for generated XLSX and parsed draft", async()=>{
    for(const formCode of PILOT_FORMS){
      const ctx={...context,formCode};
      const file=await generateSectorTemplate(ctx);
      const book=new ExcelJS.Workbook();
      await book.xlsx.load(new Uint8Array(await file.arrayBuffer()) as unknown as Parameters<typeof book.xlsx.load>[0]);
      const expected:Record<string,string>={};
      for(const [index,field] of FORM_FIELDS[formCode].entries()){
        book.getWorksheet("Sector")!.getCell(index+5,2).value=index+1;
        expected[field]=String(index+1);
      }
      expect(await parseSectorTemplate(await asBuffer(book),ctx),formCode).toEqual(expected);
    }
  });
  it.each(PILOT_FORMS)("%s — all sector templates reject foreign currency and month",async formCode=>{
    const ctx={...context,formCode};
    const source=await generateSectorTemplate(ctx);
    const original=await source.arrayBuffer();
    await expect(parseSectorTemplate(original,{...ctx,currency:"USD"}))
      .rejects.toThrow(/METADATA_MISMATCH/);
    await expect(parseSectorTemplate(original,{...ctx,period:"2026-10"}))
      .rejects.toThrow(/METADATA_MISMATCH/);
    await expect(parseSectorTemplate(original,{...ctx,modelCode:"FORGED_MODEL"}))
      .rejects.toThrow(/METADATA_MISMATCH/);
  });

  it("rejects cross-company, cross-period, tampered codes and formulas",async()=>{
    const book=await workbook();
    await expect(parseSectorTemplate(await asBuffer(book),{...context,period:"2026-10"})).rejects.toThrow(/METADATA_MISMATCH/);
    book.getWorksheet("Sector")!.getCell("A5").value="other";
    await expect(parseSectorTemplate(await asBuffer(book),context)).rejects.toThrow(/FIELD_CHANGED/);
    book.getWorksheet("Sector")!.getCell("A5").value="netRevenue";
    book.getWorksheet("Sector")!.getCell("B5").value={formula:"1000+1",result:1001};
    await expect(parseSectorTemplate(await asBuffer(book),context)).rejects.toThrow(/FORMULA_OR_INVALID/);
  });
  it("rejects a sparse row hundreds of lines past the approved sector schema", async () => {
    const book=await workbook();
    book.getWorksheet("Sector")!.getCell("A900").value="injected";
    await expect(parseSectorTemplate(await asBuffer(book),context))
      .rejects.toThrow(/EXTRA_ROW/);
  });
  it("rejects an extra far-right column even when source counts stay small", async () => {
    const book=await workbook();
    book.getWorksheet("Sector")!.getCell("M5").value="injected";
    await expect(parseSectorTemplate(await asBuffer(book),context))
      .rejects.toThrow(/EXTRA_COLUMN/);
  });
  it("rejects foreign company and hidden schema changes",async()=>{
    const book=await workbook();
    await expect(parseSectorTemplate(await asBuffer(book),{...context,company:"Other Co"}))
      .rejects.toThrow(/METADATA_MISMATCH/);
    book.getWorksheet("_Metadata")!.getCell("B1").value="sector-pilot/99";
    await expect(parseSectorTemplate(await asBuffer(book),context))
      .rejects.toThrow(/METADATA_MISMATCH/);
  });
  it("rejects numeric cells whose Excel precision exceeds 15 significant digits",async()=>{
    const book=await workbook();
    book.getWorksheet("Sector")!.getCell("B5").value=0.1234567890123456;
    await expect(parseSectorTemplate(await asBuffer(book),context))
      .rejects.toThrow(/FORMULA_OR_INVALID/);
  });
});
