import ExcelJS from "exceljs";
import { enforceZipBudget } from "../adapters/zip-budget";
import { fieldsForForm } from "./state";
import type { PilotCode } from "../../packages/sector-pilot/src/index.mjs";

const VERSION = "sector-pilot/1";
export interface SectorTemplateContext {
  company: string;
  formCode: PilotCode;
  modelCode: string;
  dimension: string;
  period: string;
  currency: string;
}
function safeExcelText(value: string): string {
  return /^[=+\-@]/.test(value) ? "'" + value : value;
}
function literal(value: ExcelJS.CellValue): string | null {
  if (value === null || value === undefined) return "";
  if (typeof value === "string") return value.trim();
  if (typeof value === "number" && Number.isFinite(value) && value >= 0 &&
      value <= 1e12) {
    const text=String(value);
    if (/e/i.test(text) || text.replace(/[^0-9]/g, "").length>15) return null;
    return text;
  }
  return null;
}
function metadata(context: SectorTemplateContext): Array<[string, string]> {
  return [
    ["SCHEMA_VERSION", VERSION], ["FORM_CODE", context.formCode],
    ["COMPANY", context.company], ["MODEL_CODE", context.modelCode],
    ["DIMENSION", context.dimension], ["PERIOD", context.period], ["CURRENCY", context.currency]
  ];
}
export async function generateSectorTemplate(context: SectorTemplateContext): Promise<Blob> {
  const book = new ExcelJS.Workbook();
  const instructions = book.addWorksheet("Instructions");
  instructions.addRow(["FIN: отраслевой пилот, методика ещё не прошла независимую квалификацию."]);
  instructions.addRow(["Заполните только столбец B на листе Sector; отсутствующее значение оставьте пустым."]);
  instructions.addRow(["Макросы, формулы и изменения кодов полей запрещены."]);
  instructions.getColumn(1).width = 110;
  const sheet = book.addWorksheet("Sector");
  sheet.getColumn("A").width = 31;
  sheet.getColumn("B").width = 27;
  sheet.getColumn("C").width = 40;
  sheet.getCell("A1").value = "FIN SECTOR PILOT";
  sheet.getCell("A2").value = safeExcelText(context.company);
  sheet.getCell("A3").value = context.formCode;
  sheet.getCell("B3").value = context.period;
  sheet.getCell("A4").value = "Код поля";
  sheet.getCell("B4").value = "Значение";
  sheet.getCell("C4").value = "Единица: контекст выбранной формы";
  for(const [index,field] of fieldsForForm(context.formCode).entries()) {
    const row=index+5;
    sheet.getCell(row,1).value=field;
    const cell=sheet.getCell(row,2);
    cell.fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FFF0F7FF" } };
    cell.numFmt="0.######";
    cell.dataValidation={type:"decimal",operator:"between",formulae:[0,1e12]};
  }
  const meta=book.addWorksheet("_Metadata");
  metadata(context).forEach(([key,value]) => meta.addRow([key,value]));
  meta.state="veryHidden";
  const buffer=await book.xlsx.writeBuffer();
  return new Blob([buffer as BlobPart], {type:"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"});
}
export async function parseSectorTemplate(input: ArrayBuffer, context: SectorTemplateContext): Promise<Record<string, string>> {
  enforceZipBudget(input);
  const book=new ExcelJS.Workbook();
  try {
    await book.xlsx.load(new Uint8Array(input) as unknown as Parameters<typeof book.xlsx.load>[0]);
  } catch {
    throw new Error("SECTOR_XLSX_DAMAGED");
  }
  if(book.worksheets.length!==3 ||
    !book.getWorksheet("Sector") || !book.getWorksheet("_Metadata") ||
    !book.getWorksheet("Instructions")) throw new Error("SECTOR_XLSX_SHEET_MISMATCH");
  const meta=book.getWorksheet("_Metadata")!;
  for(const [index,[key,value]] of metadata(context).entries()) {
    if(literal(meta.getCell(index+1,1).value)!==key ||
       literal(meta.getCell(index+1,2).value)!==value) {
      throw new Error("SECTOR_XLSX_METADATA_MISMATCH: "+key);
    }
  }
  const sheet=book.getWorksheet("Sector")!;
  // Counted rows are not the maximum row index; A900 must fail even when
  // the number of populated rows stays within the form's expected length.
  sheet.eachRow(row => {
    if (row.number > fieldsForForm(context.formCode).length + 4)
      throw new Error("SECTOR_XLSX_EXTRA_ROW: " + row.number);
    row.eachCell({ includeEmpty: false }, (_cell, col) => {
      if (col > 3) throw new Error("SECTOR_XLSX_EXTRA_COLUMN: " + col);
    });
  });
  if(literal(sheet.getCell("A1").value)!=="FIN SECTOR PILOT" ||
     literal(sheet.getCell("A2").value)!==safeExcelText(context.company) ||
     literal(sheet.getCell("A3").value)!==context.formCode ||
     literal(sheet.getCell("B3").value)!==context.period ||
     literal(sheet.getCell("A4").value)!=="Код поля" ||
     literal(sheet.getCell("B4").value)!=="Значение" ||
     sheet.actualColumnCount>3 ||
     sheet.actualRowCount>fieldsForForm(context.formCode).length+4) {
    throw new Error("SECTOR_XLSX_STRUCTURE_CHANGED");
  }
  const values:Record<string,string>={};
  for(const [index,field] of fieldsForForm(context.formCode).entries()) {
    const row=index+5;
    if(literal(sheet.getCell(row,1).value)!==field) {
      throw new Error("SECTOR_XLSX_FIELD_CHANGED: "+field);
    }
    const cell=sheet.getCell(row,2);
    const value=literal(cell.value);
    if(value===null) throw new Error("SECTOR_XLSX_FORMULA_OR_INVALID: "+cell.address);
    values[field]=value;
  }
  return values;
}
