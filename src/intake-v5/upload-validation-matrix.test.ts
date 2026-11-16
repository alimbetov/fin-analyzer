import {describe,it,expect} from "vitest";
import {
  validateF02,normalizeDecimal,normalizeUserDecimal,formatExactDecimal,
  type EntryContext,type EntryRow
} from "./form-schema";

/**
 * Every supported manual upload value must enter the same canonical F02
 * validator as a parsed Excel cell. No finance result is accepted by parsing alone.
 */
const context:EntryContext={
  projectCode:"FIN_UAT",formCode:"F02",periodCode:"2026.10",locale:"ru",
  reportCurrency:"KZT",accountingCurrency:"KZT",amountScale:1
};
const validRows:EntryRow[]=[
  {rowId:"pnl",fieldCode:"netRevenue",rawValue:"400"},
  {rowId:"pnl",fieldCode:"cogs",rawValue:"260"},
  {rowId:"pnl",fieldCode:"operatingExpenses",rawValue:"90"}
];
function withValue(fieldCode:string,rawValue:string):EntryRow[] {
  return validRows.map(r=>r.fieldCode===fieldCode?{...r,rawValue}:r);
}

describe("FIN V5 internal manual/XLSX canonical upload validation matrix",()=>{
  it.each([
    ["zero explicitly","0","0"],["negative values","-12.5000","-12.5"],
    ["leading zeroes","0001.2500","1.25"],["exact 12 decimals","0.123456789012","0.123456789012"],
    ["negative zero","-0.000","0"],["upper supported size","9".repeat(26)+"."+"1".repeat(12),"9".repeat(26)+"."+"1".repeat(12)]
  ])("%s is exact: %s -> %s",(_name,input,canonical)=>{
    const output=validateF02(context,withValue("netRevenue",input));
    expect(output.valid).toBe(true);
    expect(output.rows[0]?.rawValue).toBe(canonical);
  });
  it.each([
    ["more than 12 decimals","0.1234567890123"],
    ["more than 26 integer digits","9".repeat(27)],
    ["scientific notation","1e12"],
    ["thousands separator","1 200.00"],
    ["ambiguous comma grouping","1,234.50"],
    ["plus sign","+12"],["currency sign","₸120"],
    ["NaN","NaN"],["Infinity","Infinity"],["HTML injection","<script>1</script>"]
  ])("%s must not be accepted as money",(_name,raw)=>{
    const output=validateF02(context,withValue("cogs",raw));
    expect(output.valid).toBe(false);
    expect(output.findings).toContainEqual(expect.objectContaining({
      code:"INVALID_DECIMAL",fieldCode:"cogs"
    }));
  });
  it("missing, blank, and explicit zero remain three distinct inputs",()=>{
    const missing=validateF02(context,validRows.filter(r=>r.fieldCode!=="netRevenue"),"XLSX");
    const blank=validateF02(context,withValue("netRevenue",""),"XLSX");
    const zero=validateF02(context,withValue("netRevenue","0"),"XLSX");
    expect(missing.valid).toBe(false);
    expect(blank.valid).toBe(false);
    expect(zero.valid).toBe(true);
    expect(missing.findings[0]?.sourceCell).toBe("FIN_Data!D3");
    expect(zero.rows[0]?.rawValue).toBe("0");
  });
  it("unknown row, unknown field and duplicate field block upload",()=>{
    const wrongRow=validateF02(context,[...validRows,
      {rowId:"other",fieldCode:"netRevenue",rawValue:"400"}]);
    expect(wrongRow.findings.some(f=>f.code==="UNKNOWN_SOURCE_COORDINATE")).toBe(true);
    const unknown=validateF02(context,[...validRows,
      {rowId:"pnl",fieldCode:"inventedRevenue",rawValue:"400"}]);
    expect(unknown.findings.some(f=>f.code==="UNKNOWN_SOURCE_COORDINATE")).toBe(true);
    const duplicate=validateF02(context,[...validRows,validRows[0]!]);
    expect(duplicate.findings.some(f=>f.code==="DUPLICATE_SOURCE_FIELD")).toBe(true);
  });
  it("no cross-project, invalid period, unsupported currency or scale",()=>{
    const contexts:EntryContext[]=[
      {...context,projectCode:""},
      {...context,periodCode:"2026.13"},
      {...context,periodCode:"1899.12"},
      {...context,amountScale:99 as 1},
      {...context,reportCurrency:"USD" as "KZT"},
      {...context,accountingCurrency:"USD" as "KZT"},
      {...context,locale:"zh-Hans" as "ru"}
    ];
    for(const candidate of contexts) {
      const output=validateF02(candidate,validRows);
      expect(output.valid,JSON.stringify(candidate)).toBe(false);
    }
  });
  it("input locale changes parsing but canonical text is language independent",()=>{
    expect(normalizeUserDecimal("1,25","ru")).toBe("1.25");
    expect(normalizeUserDecimal("1,25","kk")).toBe("1.25");
    expect(()=>normalizeUserDecimal("1,25","en")).toThrow("INVALID_DECIMAL");
    const ru=validateF02(context,withValue("netRevenue","00400,500"));
    const en=validateF02({...context,locale:"en"},withValue("netRevenue","400.50"));
    expect(ru.valid && en.valid).toBe(true);
    expect(ru.rows).toEqual(en.rows);
    expect(normalizeDecimal("400.500")).toBe("400.5");
  });
  it("display-only formatting cannot change an exact canonical value",()=>{
    const raw="12345678901234567890.123456789012";
    const rendered=formatExactDecimal(raw,"kk");
    expect(rendered.length).toBeGreaterThan(raw.length);
    expect(normalizeDecimal(raw)).toBe(raw);
  });
});
