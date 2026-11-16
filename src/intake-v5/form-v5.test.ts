import { describe, it, expect } from "vitest";
import ExcelJS from "exceljs";
import {
  PILOT_F02_SCHEMA, ACTIVE_LOCALES, requireActiveLocale, normalizeDecimal,
  formatExactDecimal, normalizeUserDecimal, validateF02, type EntryContext
} from "./form-schema";
import { resolvePeriod } from "./reporting-period";
import { generateF02ExcelV5, parseF02ExcelV5, headers } from "./excel-v5";

const ctx: EntryContext={projectCode:"FIN-UAT",formCode:"F02",locale:"ru",
  periodCode:"2026.10",reportCurrency:"KZT",accountingCurrency:"KZT",amountScale:1000};
async function wb(blob:Blob):Promise<ExcelJS.Workbook>{
  const book=new ExcelJS.Workbook();
  await book.xlsx.load(new Uint8Array(await blob.arrayBuffer()) as unknown as Parameters<typeof book.xlsx.load>[0]);
  return book;
}

describe("FIN V5 one-resource F02 field catalog",()=>{
  it("has stable machine codes and exactly three verified pilot display locales",()=>{
    expect(PILOT_F02_SCHEMA.fields.map(f=>f.code)).toEqual(["netRevenue","cogs","operatingExpenses"]);
    expect(ACTIVE_LOCALES).toEqual(["ru","kk","en"]);
    for(const field of PILOT_F02_SCHEMA.fields)
      for(const locale of ACTIVE_LOCALES) expect(field.labels[locale].length).toBeGreaterThan(3);
    expect(()=>requireActiveLocale("zh-Hans")).toThrow(/TRANSLATION_NOT_READY/);
  });
  it("does not turn missing into zero and keeps exact decimal strings",()=>{
    expect(normalizeDecimal("0001.2300")).toBe("1.23");
    expect(normalizeDecimal("-0.00")).toBe("0");
    expect(normalizeUserDecimal("1,25","ru")).toBe("1.25");
    expect(normalizeUserDecimal("1,25","kk")).toBe("1.25");
    expect(()=>normalizeUserDecimal("1,25","en")).toThrow("INVALID_DECIMAL");
    expect(()=>normalizeUserDecimal("1 000,25","ru")).toThrow("INVALID_DECIMAL");
    expect(formatExactDecimal("12345678901234567890.1234","ru")).toContain("1234");
    const missing=validateF02(ctx,[{rowId:"pnl",fieldCode:"netRevenue",rawValue:"0"}]);
    expect(missing.valid).toBe(false);
    expect(missing.rows[0]?.rawValue).toBe("0");
    expect(missing.findings.filter(f=>f.code==="REQUIRED_FIELD_MISSING")).toHaveLength(2);
  });
  it("normalizes same source values independent of locale and source-row ordering",()=>{
    const rows=[
      {rowId:"pnl",fieldCode:"cogs",rawValue:"00260.000"},
      {rowId:"pnl",fieldCode:"operatingExpenses",rawValue:"90"},
      {rowId:"pnl",fieldCode:"netRevenue",rawValue:"400.0"}
    ];
    const ru=validateF02(ctx,rows);
    const en=validateF02({...ctx,locale:"en"},[...rows].reverse());
    expect(ru.valid).toBe(true);
    expect(en.valid).toBe(true);
    expect(ru.rows).toEqual(en.rows);
    expect(ru.rows.map(r=>r.rawValue)).toEqual(["400","260","90"]);
  });
});
describe("FIN V5 reporting period resolver",()=>{
  it("resolves ISO year-boundary weeks and rejects impossible week numbers",()=>{
    expect(resolvePeriod("WEEK","2026-W01").startInclusive).toBe("2025-12-29");
    expect(resolvePeriod("WEEK","2020-W53").endExclusive).toBe("2021-01-04");
    expect(()=>resolvePeriod("WEEK","2021-W53")).toThrow("INVALID_ISO_WEEK");
  });
  it("resolves all monthly, quarter, half-year and annual boundaries",()=>{
    expect(resolvePeriod("MONTH","2026.10").endExclusive).toBe("2026-11-01");
    expect(resolvePeriod("QUARTER","2026-Q3").startInclusive).toBe("2026-07-01");
    expect(resolvePeriod("HALF_YEAR","2026-H2").endExclusive).toBe("2027-01-01");
    expect(resolvePeriod("YEAR","2026").endExclusive).toBe("2027-01-01");
    expect(resolvePeriod("AS_OF","2024-02-29").asOfDate).toBe("2024-02-29");
    expect(()=>resolvePeriod("AS_OF","2025-02-29")).toThrow("INVALID_AS_OF");
    expect(()=>resolvePeriod("MONTH","2026.13")).toThrow("INVALID_MONTH");
    expect(()=>resolvePeriod("MONTH","2026.10","AS_OF")).toThrow("PERIOD_COVERAGE_MISMATCH");
  });
});
describe("FIN V5 localized XLSX",()=>{
  it.each(ACTIVE_LOCALES)("issues one-locale %s workbook and parses user input from row 3",async locale=>{
    const context={...ctx,locale};
    const workbook=await wb(await generateF02ExcelV5(context));
    const sheet=workbook.getWorksheet("FIN_Data")!;
    expect(sheet.getCell("B1").value).toBe(locale);
    expect(sheet.getCell("B2").value).toBe(headers(locale)[1]);
    expect(sheet.getCell("B3").value).toBe("netRevenue");
    expect(sheet.getCell("C3").value).toBe(PILOT_F02_SCHEMA.fields[0]?.labels[locale]);
    expect(sheet.getCell("D3").value).toBe(null);
    sheet.getCell("D3").value=400;
    sheet.getCell("D4").value=260;
    sheet.getCell("D5").value=90;
    const edited=await workbook.xlsx.writeBuffer();
    const parsed=await parseF02ExcelV5(
      new Uint8Array(edited as ArrayBuffer).buffer as ArrayBuffer,context);
    const validated=validateF02(context,parsed);
    expect(validated.valid).toBe(true);
    expect(validated.rows.map(r=>r.rawValue)).toEqual(["400","260","90"]);
  });
  it("rejects changed locale/period/formula and unknown Chinese translation",async()=>{
    const book=await wb(await generateF02ExcelV5(ctx));
    book.getWorksheet("FIN_Data")!.getCell("C1").value="2026.11";
    let bytes=await book.xlsx.writeBuffer();
    await expect(parseF02ExcelV5(new Uint8Array(bytes as ArrayBuffer).buffer as ArrayBuffer,ctx))
      .rejects.toThrow("XLSX_V5_VISIBLE_HEADER_CHANGED");
    book.getWorksheet("FIN_Data")!.getCell("C1").value="2026.10";
    book.getWorksheet("FIN_Data")!.getCell("D3").value={formula:"1+1",result:2};
    bytes=await book.xlsx.writeBuffer();
    await expect(parseF02ExcelV5(new Uint8Array(bytes as ArrayBuffer).buffer as ArrayBuffer,ctx))
      .rejects.toThrow("XLSX_V5_FORMULAS_FORBIDDEN");
    expect(()=>headers("zh-Hans")).toThrow("TRANSLATION_NOT_READY");
  });
});
