import { describe, expect, it } from "vitest";
import ExcelJS from "exceljs";
import { generateBalanceTemplate, parseBalanceTemplate } from "./excel";

async function workbookForTest() {
  const blob = await generateBalanceTemplate("ТОО Тест");
  const workbook = new ExcelJS.Workbook();
  await workbook.xlsx.load(new Uint8Array(await blob.arrayBuffer()) as unknown as Parameters<typeof workbook.xlsx.load>[0]);
  const sheet = workbook.getWorksheet("Balance")!;
  for (const col of ["C", "D"]) {
    [1000, 400, 600, 200, 400].forEach((value, index) => {
      sheet.getCell(col + (6 + index)).value = value;
    });
  }
  return workbook;
}
async function readWorkbook(workbook: ExcelJS.Workbook) {
  const buffer = await workbook.xlsx.writeBuffer();
  const bytes = new Uint8Array(buffer as ArrayBuffer);
  return parseBalanceTemplate(bytes.slice().buffer, "ТОО Тест");
}

describe("controlled XLSX contract", () => {
  it("roundtrips the generated template", async () => {
    const result = await readWorkbook(await workbookForTest());
    expect(result.values["2025"].TOTAL_ASSETS).toBe("1000");
    expect(result.findings.filter(f => f.severity === "BLOCKER")).toEqual([]);
  });
  it("reports an accounting mismatch at the exact year", async () => {
    const book = await workbookForTest();
    book.getWorksheet("Balance")!.getCell("D6").value = 1001;
    const result = await readWorkbook(book);
    expect(result.findings.some(f => f.code === "BALANCE_MISMATCH" && f.year === "2025")).toBe(true);
  });
  it("blocks formulas in input cells", async () => {
    const book = await workbookForTest();
    book.getWorksheet("Balance")!.getCell("D6").value = { formula: "SUM(D7:D8)", result: 1000 };
    await expect(readWorkbook(book)).rejects.toThrow(/формулу/);
  });
  it("blocks tampered schema versions", async () => {
    const book = await workbookForTest();
    book.getWorksheet("_Metadata")!.getCell("B2").value = "77.0.0";
    await expect(readWorkbook(book)).rejects.toThrow(/FORM_VERSION/);
  });
  it("blocks duplicated metric codes", async () => {
    const book = await workbookForTest();
    book.getWorksheet("Balance")!.getCell("A7").value = "TOTAL_ASSETS";
    await expect(readWorkbook(book)).rejects.toThrow(/Дублирован/);
  });
  it("blocks a different company context", async () => {
    const book = await workbookForTest();
    const bytes = new Uint8Array(await book.xlsx.writeBuffer() as ArrayBuffer);
    await expect(parseBalanceTemplate(bytes.slice().buffer, "Другая компания")).rejects.toThrow(/COMPANY/);
  });
});
