import ExcelJS from "exceljs";
import {
  BALANCE_METRICS, FORM_CODE, FORM_VERSION, MAX_IMPORT_BYTES, UNIT, YEARS,
  emptyBalance, type BalanceValues, type Finding
} from "../domain/financial";
import { validateBalance } from "../domain/validation";

const MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const SHEET = "Balance";
const MANIFEST = "_Metadata";

function safeExcelText(text: string): string {
  return /^[=+\-@]/.test(text) ? "'" + text : text;
}

function readLiteral(value: ExcelJS.CellValue): string | null {
  if (value === null || value === undefined) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return null;
}

export async function generateBalanceTemplate(company: string): Promise<Blob> {
  const workbook = new ExcelJS.Workbook();
  workbook.creator = "FIN Analyzer";
  workbook.created = new Date();
  const instructions = workbook.addWorksheet("Instructions");
  instructions.addRow(["FIN Analyzer — Бухгалтерский баланс"]);
  instructions.addRow(["Заполняйте только числовые значения в Balance!C6:D10."]);
  instructions.addRow(["Единица: ТЫСЯЧИ тенге. Отсутствующий показатель оставьте пустым."]);
  instructions.addRow(["Запрещены формулы, ссылки и произвольные изменения структуры."]);
  instructions.getColumn(1).width = 90;

  const sheet = workbook.addWorksheet(SHEET);
  sheet.columns = [
    { key: "code", width: 28 }, { key: "label", width: 43 },
    { key: "previous", width: 23 }, { key: "current", width: 23 }
  ];
  sheet.mergeCells("A1:D1");
  sheet.getCell("A1").value = "FIN ANALYZER — БУХГАЛТЕРСКИЙ БАЛАНС";
  sheet.getCell("A1").font = { bold: true, size: 16, color: { argb: "FFFFFFFF" } };
  sheet.getCell("A1").fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FF172C4C" } };
  sheet.mergeCells("A2:D2");
  sheet.getCell("A2").value = safeExcelText(company);
  sheet.getCell("A3").value = "Единица измерения:";
  sheet.getCell("B3").value = "тыс. ₸";
  sheet.getRow(5).values = ["Код показателя", "Статья баланса", YEARS[0], YEARS[1]];
  sheet.getRow(5).eachCell(cell => {
    cell.font = { bold: true, color: { argb: "FFFFFFFF" } };
    cell.fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FF254C7B" } };
  });
  BALANCE_METRICS.forEach((metric, i) => {
    const row = i + 6;
    sheet.getCell("A" + row).value = metric.code;
    sheet.getCell("B" + row).value = metric.label;
    for (const col of ["C", "D"]) {
      const cell = sheet.getCell(col + row);
      cell.numFmt = "#,##0.00;[Red](#,##0.00)";
      cell.fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FFF0F7FF" } };
      cell.dataValidation = { type: "decimal", operator: "between", formulae: [-1e15, 1e15] };
    }
  });
  sheet.views = [{ state: "frozen", ySplit: 5 }];

  const meta = workbook.addWorksheet(MANIFEST);
  const pairs: Array<[string, string]> = [
    ["FORM_CODE", FORM_CODE],
    ["FORM_VERSION", FORM_VERSION],
    ["COMPANY", company],
    ["UNIT", UNIT],
    ["YEAR_PREVIOUS", YEARS[0]],
    ["YEAR_CURRENT", YEARS[1]],
    ["SCHEMA_VERSION", "1"]
  ];
  // Metadata is written as a literal string; ExcelJS does not interpret string values as formulas.
  // Do not add a leading apostrophe: it would break company identity checks.
  pairs.forEach(([key, value]) => meta.addRow([key, value]));
  meta.state = "veryHidden";
  const buffer = await workbook.xlsx.writeBuffer();
  return new Blob([buffer as BlobPart], { type: MIME });
}

export interface ImportResult {
  values: BalanceValues;
  findings: Finding[];
}

export async function parseBalanceTemplate(input: ArrayBuffer, expectedCompany: string): Promise<ImportResult> {
  if (input.byteLength > MAX_IMPORT_BYTES) throw new Error("XLSX превышает лимит 2 МБ");
  const workbook = new ExcelJS.Workbook();
  try {
    await workbook.xlsx.load(new Uint8Array(input) as unknown as Parameters<typeof workbook.xlsx.load>[0]);
  } catch {
    throw new Error("Файл XLSX повреждён или не поддерживается");
  }
  if (workbook.worksheets.length !== 3 || !workbook.getWorksheet("Instructions")
    || !workbook.getWorksheet(SHEET) || !workbook.getWorksheet(MANIFEST)) {
    throw new Error("Несовместимая структура книги: ожидаются Instructions, Balance и _Metadata");
  }
  const meta = workbook.getWorksheet(MANIFEST)!;
  const expected: Array<[string, string]> = [
    ["FORM_CODE", FORM_CODE], ["FORM_VERSION", FORM_VERSION],
    ["COMPANY", expectedCompany], ["UNIT", UNIT],
    ["YEAR_PREVIOUS", YEARS[0]], ["YEAR_CURRENT", YEARS[1]],
    ["SCHEMA_VERSION", "1"]
  ];
  for (const [index, [key, expectedValue]] of expected.entries()) {
    if (readLiteral(meta.getCell("A" + (index + 1)).value) !== key
      || readLiteral(meta.getCell("B" + (index + 1)).value) !== expectedValue) {
      throw new Error("Неверные метаданные: " + key + ". Скачайте новый шаблон для текущей компании.");
    }
  }
  const sheet = workbook.getWorksheet(SHEET)!;
  const expectedHead = ["Код показателя", "Статья баланса", YEARS[0], YEARS[1]];
  for (let i = 0; i < expectedHead.length; i++) {
    if (readLiteral(sheet.getCell(5, i + 1).value) !== expectedHead[i]) {
      throw new Error("Заголовки Balance были изменены: " + sheet.getCell(5, i + 1).address);
    }
  }
  if (sheet.actualRowCount > 10) throw new Error("Неожиданные строки в Balance");
  const result = emptyBalance();
  const seen = new Set<string>();
  for (let i = 0; i < BALANCE_METRICS.length; i++) {
    const metric = BALANCE_METRICS[i]!;
    const row = i + 6;
    const code = readLiteral(sheet.getCell("A" + row).value);
    if (code && seen.has(code)) throw new Error("Дублирован код показателя: " + code);
    if (code) seen.add(code);
    if (code !== metric.code) throw new Error("Код показателя изменён: Balance!A" + row);
    if (readLiteral(sheet.getCell("B" + row).value) !== metric.label) {
      throw new Error("Название показателя изменено: Balance!B" + row);
    }
    YEARS.forEach((year, index) => {
      const cell = sheet.getCell(row, index + 3);
      const literal = readLiteral(cell.value);
      if (literal === null) {
        throw new Error("Вводите число, а не формулу или объект: Balance!" + cell.address);
      }
      result[year][metric.code] = literal;
    });
  }
  const findings = validateBalance(result).map(finding => {
    if (!finding.year || !finding.metric) return finding;
    const row = BALANCE_METRICS.findIndex(item => item.code === finding.metric) + 6;
    return { ...finding, cell: "Balance!" + (finding.year === YEARS[0] ? "C" : "D") + row };
  });
  return { values: result, findings };
}
