import ExcelJS from "exceljs";
import { enforceZipBudget } from "../adapters/zip-budget";
import {
  FORMAT_VERSION, FORM_SCHEMA_VERSION, MAX_PILOT_XLSX_BYTES, PILOT_F02_SCHEMA,
  type ActiveLocale, type EntryContext, type EntryRow, type FormLocale, requireActiveLocale,
  normalizeUserDecimal
} from "./form-schema";

const MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const HEADERS: Record<ActiveLocale, readonly string[]> = {
  ru: ["Код строки", "Код показателя", "Наименование показателя", "Значение",
    "Валюта", "Ед. изм.", "Период строки", "Примечание"],
  kk: ["Жол коды", "Көрсеткіш коды", "Көрсеткіш атауы", "Мәні",
    "Валюта", "Өлшем бірлігі", "Жол кезеңі", "Ескертпе"],
  en: ["Row ID", "Indicator code", "Indicator", "Amount",
    "Currency", "Unit", "Row period", "Notes"]
};
const DIRECTIONS: Record<ActiveLocale, readonly string[]> = {
  ru: ["Заполните значения только в голубых ячейках столбца D (начиная с 3-й строки).",
    "Не изменяйте коды полей, проект, период, валюту или структуру шаблона.",
    "Отсутствующее значение оставьте пустым. Ноль вводите явно. Формулы запрещены.",
    "Суммы указываются в валюте и масштабе, обозначенных в первой строке FIN_Data."],
  kk: ["Мәндерді тек D бағанының көк ұяшықтарына енгізіңіз (3-жолдан бастап).",
    "Өріс кодтарын, жобаны, кезеңді, валютаны немесе үлгі құрылымын өзгертпеңіз.",
    "Мән жоқ болса, бос қалдырыңыз. Нөлді анық енгізіңіз. Формулаларға тыйым салынады.",
    "Сомалар FIN_Data бірінші жолында көрсетілген валюта мен масштабта енгізіледі."],
  en: ["Enter values only in the blue cells of column D (starting at row 3).",
    "Do not modify field codes, project, period, currency or template structure.",
    "Leave missing values empty. Enter zero explicitly. Formulas are not allowed.",
    "Enter amounts in the currency and scale shown in FIN_Data row 1."]
};
const META_KEYS = ["FORMAT_VERSION", "FORM_CODE", "FORM_VERSION", "PROJECT_CODE",
  "LOCALE", "PERIOD_TYPE", "PERIOD_CODE", "REPORT_CURRENCY", "ACCOUNTING_CURRENCY",
  "AMOUNT_SCALE"] as const;
function passport(context: EntryContext): string[] {
  return [context.formCode, context.locale, context.periodCode,
    PILOT_F02_SCHEMA.titles[context.locale], context.reportCurrency,
    String(context.amountScale), context.projectCode, context.accountingCurrency];
}
function manifest(context: EntryContext): string[] {
  return [FORMAT_VERSION, context.formCode, FORM_SCHEMA_VERSION, context.projectCode,
    context.locale, "MONTH", context.periodCode, context.reportCurrency,
    context.accountingCurrency, String(context.amountScale)];
}
function literal(cell: ExcelJS.Cell): string {
  if (cell.value === null || cell.value === undefined) return "";
  if (typeof cell.value === "string") return cell.value;
  if (typeof cell.value === "number" && Number.isFinite(cell.value)) {
    const value = String(cell.value);
    if (value.replace(/[^0-9]/g, "").length > 15 || /e/i.test(value))
      throw new Error("XLSX_EXCEL_NUMERIC_PRECISION_UNSAFE");
    return value;
  }
  throw new Error("XLSX_FORMULA_OR_NON_LITERAL_FORBIDDEN");
}
function assertContext(context: EntryContext): void {
  if (context.formCode !== "F02" || !/^[\p{L}\p{N}_-]{1,50}$/u.test(context.projectCode)
    || !/^\d{4}\.(0[1-9]|1[0-2])$/.test(context.periodCode)
    || context.reportCurrency !== "KZT" || context.accountingCurrency !== "KZT"
    || ![1,1000,1000000].includes(context.amountScale))
    throw new Error("XLSX_V5_UNSUPPORTED_CONTEXT");
}

export function headers(locale: FormLocale): readonly string[] {
  requireActiveLocale(locale);
  return HEADERS[locale];
}
export async function generateF02ExcelV5(context: EntryContext): Promise<Blob> {
  assertContext(context);
  const book = new ExcelJS.Workbook();
  book.creator = "FIN Analyzer · V5 pilot";
  const sheet = book.addWorksheet("FIN_Data");
  const instructions = book.addWorksheet("Instructions");
  instructions.getColumn(1).width = 105;
  DIRECTIONS[context.locale].forEach(line => instructions.addRow([line]));
  const names = passport(context);
  for (let col=1; col<=8; col++) {
    const cell = sheet.getCell(1,col);
    cell.value = names[col-1]!;
    cell.fill = {type:"pattern",pattern:"solid",fgColor:{argb:"FFE2ECF8"}};
    cell.font = {bold:true,color:{argb:"FF163455"}};
    cell.alignment = {horizontal:"center",vertical:"middle",wrapText:true};
    const header = sheet.getCell(2,col);
    header.value = HEADERS[context.locale][col-1]!;
    header.font = {bold:true,color:{argb:"FFFFFFFF"}};
    header.fill = {type:"pattern",pattern:"solid",fgColor:{argb:"FF25476F"}};
    header.alignment = {horizontal:"center",vertical:"middle",wrapText:true};
  }
  sheet.getRow(1).height=38;
  sheet.getRow(2).height=34;
  [21,24,45,25,17,16,18,27].forEach((width,i)=>{sheet.getColumn(i+1).width=width;});
  for (const [index,field] of PILOT_F02_SCHEMA.fields.entries()) {
    const rowNumber = index+3;
    const row=sheet.getRow(rowNumber);
    row.height=36;
    const data: (string|null)[] = [field.rowId,field.code,field.labels[context.locale],
      null,context.reportCurrency,"",context.periodCode,""];
    data.forEach((value,i)=>{
      const cell=row.getCell(i+1);
      if (value!==null) cell.value=value;
      cell.alignment={horizontal:i===3?"right":"center",vertical:"middle",wrapText:i!==3};
      cell.font={color:{argb:"FF23394E"}};
      if(i===3){
        cell.fill={type:"pattern",pattern:"solid",fgColor:{argb:"FFDCEFFD"}};
        // Never visually round an entered 12-decimal value to two places.
        // Display precision must not conceal the exact amount the parser reads.
        cell.numFmt="#,##0.############;[Red](#,##0.############);0";
        cell.dataValidation={type:"decimal",operator:"between",formulae:[-1e14,1e14]};
      } else {
        cell.fill={type:"pattern",pattern:"solid",
          fgColor:{argb:index%2===0?"FFF4F8FC":"FFEAF1F8"}};
      }
    });
  }
  sheet.views=[{state:"frozen",xSplit:2,ySplit:2}];
  sheet.pageSetup={orientation:"landscape",fitToPage:true,fitToWidth:1,fitToHeight:0};
  const meta=book.addWorksheet("_FIN_Metadata");
  manifest(context).forEach((value,i)=>{
    meta.getCell(i+1,1).value=META_KEYS[i]!;
    meta.getCell(i+1,2).value=value;
  });
  meta.state="veryHidden";
  const buffer=await book.xlsx.writeBuffer();
  if (buffer.byteLength>MAX_PILOT_XLSX_BYTES) throw new Error("XLSX_V5_SIZE_LIMIT");
  return new Blob([buffer as BlobPart],{type:MIME});
}

export async function parseF02ExcelV5(source: ArrayBuffer, expected: EntryContext): Promise<EntryRow[]> {
  assertContext(expected);
  if(source.byteLength>MAX_PILOT_XLSX_BYTES) throw new Error("XLSX_V5_SIZE_LIMIT");
  enforceZipBudget(source);
  const book=new ExcelJS.Workbook();
  try {
    await book.xlsx.load(new Uint8Array(source) as unknown as Parameters<typeof book.xlsx.load>[0]);
  } catch { throw new Error("XLSX_V5_DAMAGED"); }
  if(book.worksheets.length!==3 || book.worksheets.map(s=>s.name).join("|")!=="FIN_Data|Instructions|_FIN_Metadata" ||
      !book.getWorksheet("FIN_Data") ||
      !book.getWorksheet("Instructions") || !book.getWorksheet("_FIN_Metadata") ||
      book.getWorksheet("_FIN_Metadata")!.state!=="veryHidden")
    throw new Error("XLSX_V5_SHEETS_CHANGED");
  // No formulas/errors even in sheets that are not part of the financial input.
  book.eachSheet(sheet=>sheet.eachRow(row=>row.eachCell({includeEmpty:false},cell=>{
    if(cell.type===ExcelJS.ValueType.Formula || cell.type===ExcelJS.ValueType.Error)
      throw new Error("XLSX_V5_FORMULAS_FORBIDDEN");
  })));
  const meta=book.getWorksheet("_FIN_Metadata")!;
  const suppliedLocale=literal(meta.getCell(5,2)) as FormLocale;
  requireActiveLocale(suppliedLocale);
  const instructions=book.getWorksheet("Instructions")!;
  instructions.eachRow(row=>{
    if(row.number>DIRECTIONS[suppliedLocale].length)
      throw new Error("XLSX_V5_INSTRUCTIONS_CHANGED");
    row.eachCell({includeEmpty:false},(_cell,col)=>{
      if(col>1)throw new Error("XLSX_V5_INSTRUCTIONS_CHANGED");
    });
  });
  meta.eachRow(row=>{
    if(row.number>META_KEYS.length)
      throw new Error("XLSX_V5_METADATA_CHANGED");
    row.eachCell({includeEmpty:false},(_cell,col)=>{
      if(col>2)throw new Error("XLSX_V5_METADATA_CHANGED");
    });
  });
  if(instructions.actualRowCount!==DIRECTIONS[suppliedLocale].length ||
     instructions.actualColumnCount>1)
    throw new Error("XLSX_V5_INSTRUCTIONS_CHANGED");
  for (const [index,expectedLine] of DIRECTIONS[suppliedLocale].entries())
    if(literal(instructions.getCell(index+1,1))!==expectedLine)
      throw new Error("XLSX_V5_INSTRUCTIONS_CHANGED: "+(index+1));
  if(meta.actualRowCount!==META_KEYS.length || meta.actualColumnCount>2)
    throw new Error("XLSX_V5_METADATA_CHANGED");
  const expectedManifest=manifest(expected);
  // Locale is a display-coordinate, not project/data identity. Accept a
  // published workbook in ru/kk/en regardless of the currently selected UI.
  expectedManifest[4]=suppliedLocale;
  for(const [index,key] of META_KEYS.entries()) {
    if(literal(meta.getCell(index+1,1))!==key ||
        literal(meta.getCell(index+1,2))!==expectedManifest[index])
      throw new Error("XLSX_V5_CONTEXT_MISMATCH: "+key);
  }
  const sheet=book.getWorksheet("FIN_Data")!;
  // actualRowCount counts populated rows, not the largest row index.
  // A relocated row at row 900 must not bypass the five-row budget.
  sheet.eachRow(row=>{
    if(row.number>PILOT_F02_SCHEMA.fields.length+2)
      throw new Error("XLSX_V5_EXTRA_ROWS_OR_COLUMNS");
    row.eachCell({includeEmpty:false},(_cell,columnNumber)=>{
      if(columnNumber>8)throw new Error("XLSX_V5_EXTRA_ROWS_OR_COLUMNS");
    });
  });
  if(sheet.actualColumnCount>8 || sheet.actualRowCount>PILOT_F02_SCHEMA.fields.length+2)
    throw new Error("XLSX_V5_EXTRA_ROWS_OR_COLUMNS");
  const issuedContext={...expected,locale:suppliedLocale};
  for(let i=1;i<=8;i++) {
    if(literal(sheet.getCell(1,i))!==passport(issuedContext)[i-1] ||
        literal(sheet.getCell(2,i))!==HEADERS[suppliedLocale][i-1])
      throw new Error("XLSX_V5_VISIBLE_HEADER_CHANGED: "+i);
  }
  const rows: EntryRow[]=[];
  for(const [index,field] of PILOT_F02_SCHEMA.fields.entries()) {
    const y=index+3;
    if(literal(sheet.getCell(y,1))!==field.rowId ||
       literal(sheet.getCell(y,2))!==field.code ||
       literal(sheet.getCell(y,3))!==field.labels[suppliedLocale] ||
       literal(sheet.getCell(y,5))!==expected.reportCurrency ||
       literal(sheet.getCell(y,6))!=="" ||
       literal(sheet.getCell(y,7))!==expected.periodCode ||
       literal(sheet.getCell(y,8))!=="")
      throw new Error("XLSX_V5_ROW_CONTEXT_CHANGED: "+y);
    const raw=literal(sheet.getCell(y,4));
    // Value semantics belong to the source template's locale, not to the
    // language subsequently chosen to inspect the preview.
    let rawValue=raw;
    if(raw.trim()) {
      try {rawValue=normalizeUserDecimal(raw,suppliedLocale);}
      catch { /* Keep invalid raw text for the shared validator/preview. */ }
    }
    rows.push({rowId:field.rowId,fieldCode:field.code,rawValue});
  }
  return rows;
}
