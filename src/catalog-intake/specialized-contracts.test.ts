import {describe,it,expect} from "vitest";
import {SPECIALIZED_CANDIDATES,parseSpecializedCandidates,requireSpecializedCandidate,
        validateSpecializedCandidate,type SpecializedCandidate} from "./specialized-contracts";
import {getCaptureForm,normalizeTypedValue,type CatalogRow,type FieldType} from "./catalog-schema";
import source from "../../backend/src/main/resources/fin/specialized-schema-candidates-r6.tsv?raw";

function fixture(type:FieldType):string{
  switch(type){
    case "REF":return "REF-001";
    case "CODE":return "CATEGORY_A";
    case "DATE":return "2026-10-31";
    case "MONEY":return "100.00";
    case "DECIMAL":return "0.15";
    case "PERIOD":return "2026-10";
    case "ENUM":return "ACTIVE";
    case "INTEGER":return "5";
    case "TEXT":return "Описание подтверждающего источника";
    case "BOOLEAN":return "false";
  }
}
function candidateRow(form:SpecializedCandidate):CatalogRow{
  return {rowId:"ROW-001",cells:Object.fromEntries(form.fields.map(f=>[f.code,fixture(f.type)]))};
}
function given(cells:Record<string,string>):CatalogRow[]{
  return [{rowId:"ROW-001",cells}];
}
describe("R6.1 specialized schema proposals: 32 real code-contract validations",()=>{
  it("matches exactly the 32 C/F/P catalog summaries and has no accidental composite financial duplication",()=>{
    expect(SPECIALIZED_CANDIDATES).toHaveLength(32);
    const actual=new Set(SPECIALIZED_CANDIDATES.map(s=>s.code));
    const expected=new Set(["C01","C02","C03","C04","C05","C06","C07","C08","C09","C10","C11","C12","C13","C14",
      "F01","F02","F03","F04","F05","F06","F07","F08",
      "P01","P02","P03","P04","P05","P06","P07","P08","P09","P10"]);
    expect(actual).toEqual(expected);
    for(const form of SPECIALIZED_CANDIDATES){
      expect(getCaptureForm(form.code).captureMode).toBe("SCHEMA_NOT_APPROVED");
      expect(form.qualification).toBe("SCHEMA_CANDIDATE_NOT_APPROVED");
      expect(requireSpecializedCandidate(form.code)).toBe(form);
      if(form.invariant==="REFERENCE_ONLY")
        expect(form.fields.some(f=>f.type==="MONEY")).toBe(false);
    }
  });
  it.each(SPECIALIZED_CANDIDATES)(
    "$code — proposed typed rows are canonical, with NO automatic methodology approval or fact publication",
    form=>{
      const draft=validateSpecializedCandidate(form.code,[candidateRow(form)],"ru");
      expect(draft.acceptedAsUnqualifiedDraft,JSON.stringify(draft.findings)).toBe(true);
      expect(draft.methodologyApproved).toBe(false);
      expect(draft.publishable).toBe(false);
      for(const field of form.fields)
        expect(draft.rows[0]?.cells[field.code]).toBe(normalizeTypedValue(field.type,fixture(field.type),"ru"));
    });
  it.each(SPECIALIZED_CANDIDATES)(
    "$code — unknown source field, duplicate row and missing rows are rejected",
    form=>{
      const row=candidateRow(form);
      const bad=validateSpecializedCandidate(form.code,[
        {...row,cells:{...row.cells,inventedKpi:"900"}},row
      ],"ru");
      expect(bad.acceptedAsUnqualifiedDraft).toBe(false);
      expect(bad.findings.some(f=>f.code==="SPECIALIZED_UNKNOWN_FIELD")).toBe(true);
      expect(bad.findings.some(f=>f.code==="SPECIALIZED_INVALID_OR_DUPLICATE_ROW")).toBe(true);
      expect(validateSpecializedCandidate(form.code,[],"ru").acceptedAsUnqualifiedDraft).toBe(false);
    });
  it("F01 balance identity uses exact 12-decimal values, not JS floating point",()=>{
    const valid=validateSpecializedCandidate("F01",given({
      assets:"1000000000000000000.000000000001",
      liabilities:"500000000000000000.000000000001",
      equity:"500000000000000000",asOf:"2026-10-31"
    }),"ru");
    expect(valid.findings).toEqual([]);
    expect(valid.acceptedAsUnqualifiedDraft).toBe(true);
    const mismatch=validateSpecializedCandidate("F01",given({
      assets:"100.01",liabilities:"60.01",equity:"39.99",asOf:"2026-10-31"
    }),"ru");
    expect(mismatch.findings).toContainEqual(expect.objectContaining({
      code:"SPECIALIZED_PROVISIONAL_INVARIANT_MISMATCH",expected:"100",actual:"100.01",fieldCode:"assets"
    }));
  });
  it("F03 rolling cash, F04 equity, P03 projected cash and P04 loan principal have separate golden/negative fixtures",()=>{
    const fixtures=[
      ["F03",{openingCash:"100",cfo:"30",cfi:"-10",cff:"5",closingCash:"125"},"closingCash"],
      ["F04",{openingEquity:"100",contributions:"25",dividends:"10",otherChanges:"-5",closingEquity:"110"},"closingEquity"],
      ["P03",{openingCash:"100",inflows:"25",outflows:"10",closingCash:"115"},"closingCash"],
      ["P04",{openingPrincipal:"100",drawdown:"25",principalRepayment:"10",closingPrincipal:"115"},"closingPrincipal"],
      ["P01",{assets:"100",liabilities:"40",equity:"60"},"assets"]
    ] as const;
    for(const [code,cells,field] of fixtures){
      const good=validateSpecializedCandidate(code,given(cells),"en");
      expect(good.findings.filter(f=>f.code==="SPECIALIZED_PROVISIONAL_INVARIANT_MISMATCH")).toEqual([]);
      const wrong=validateSpecializedCandidate(code,given({...cells,[field]:"999"}),"en");
      expect(wrong.findings.some(f=>f.code==="SPECIALIZED_PROVISIONAL_INVARIANT_MISMATCH")).toBe(true);
      expect(wrong.publishable).toBe(false);
    }
  });
  it("does not assert a universal F06 trial-balance equation without account normal-side metadata",()=>{
    const form=requireSpecializedCandidate("F06");
    const candidate=validateSpecializedCandidate(form.code,[candidateRow(form)],"ru");
    expect(candidate.findings.map(f=>f.code)).toContain("SPECIALIZED_METHOD_REVIEW_REQUIRED");
    expect(candidate.methodologyApproved).toBe(false);
  });
  it("strictly protects manifest against unknown code, duplicate field, changed qualification and numeric composite",()=>{
    expect(()=>parseSpecializedCandidates(source.replace("formCode\t","code\t"))).toThrow("SPECIALIZED_HEADER_DRIFT");
    expect(()=>parseSpecializedCandidates(source.replace("C02\t","C01\t"))).toThrow("SPECIALIZED_UNQUALIFIED_OR_DUPLICATE");
    expect(()=>parseSpecializedCandidates(source.replace("SCHEMA_CANDIDATE_NOT_APPROVED","APPROVED")))
      .toThrow("SPECIALIZED_UNQUALIFIED_OR_DUPLICATE");
    expect(()=>parseSpecializedCandidates(source.replace("F05\tDISCLOSURE_LINKS\t","F05\tDISCLOSURE_LINKS\tcash:MONEY;")))
      .toThrow("SPECIALIZED_COMPOSITE_MUST_REFERENCE_SOURCES");
    expect(()=>requireSpecializedCandidate("D01")).toThrow("SPECIALIZED_UNKNOWN_FORM");
    expect(validateSpecializedCandidate("C01",[], "en").publishable).toBe(false);
  });
});
