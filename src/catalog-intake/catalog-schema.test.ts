import {describe,it,expect} from "vitest";
import {
  CAPTURE_CATALOG,CATALOG_CAPTURE_VERSION,MAX_CATALOG_ROWS,
  getCaptureForm,parseCaptureCatalog,validateCatalogCapture,normalizeTypedValue,
  type CatalogField,type CatalogCaptureContext,type CatalogRow
} from "./catalog-schema";
import {PILOT_F02_SCHEMA} from "../intake-v5/form-schema";
import catalogSource from "../../backend/src/main/resources/fin/form-compatibility.tsv?raw";

const ctx:CatalogCaptureContext={
  formCode:"DF-AST-01",projectCode:"PROJECT_01",locale:"ru",
  periodType:"MONTH",periodCode:"2026.10",coverage:"PERIOD_ONLY",
  currency:"KZT",amountScale:1
};
function sample(field:CatalogField):string {
  switch(field.type){
    case "REF":return "REF-001";
    case "CODE":return "CATEGORY_A";
    case "DATE":return "2026-10-31";
    case "MONEY":return "00123.4500";
    case "DECIMAL":return "1.2500";
    case "PERIOD":return "2026-10";
    case "ENUM":return "ACTIVE";
    case "INTEGER":return "0003";
    case "TEXT":return "Договор №123 и подтверждение";
    case "BOOLEAN":return "false";
  }
}
function row(code:string):CatalogRow {
  const form=getCaptureForm(code);
  return {rowId:"ROW-001",cells:Object.fromEntries(form.fields.map(f=>[f.code,sample(f)]))};
}
describe("FIN catalog capture executable registry",()=>{
  it("registers exactly 166 UNIQUE codes and distinguishes typed/evidence/unknown schema",()=>{
    expect(CATALOG_CAPTURE_VERSION).toBe("FIN-CATALOG-CAPTURE-1");
    expect(CAPTURE_CATALOG).toHaveLength(166);
    expect(new Set(CAPTURE_CATALOG.map(f=>f.code)).size).toBe(166);
    expect(CAPTURE_CATALOG.filter(f=>f.captureMode==="TYPED_DRAFT")).toHaveLength(122);
    expect(CAPTURE_CATALOG.filter(f=>f.captureMode==="EVIDENCE_ONLY")).toHaveLength(12);
    expect(CAPTURE_CATALOG.filter(f=>f.captureMode==="SCHEMA_NOT_APPROVED")).toHaveLength(32);
    expect(PILOT_F02_SCHEMA.fields).toHaveLength(3);
    expect(getCaptureForm("F02").captureMode).toBe("SCHEMA_NOT_APPROVED");
    expect(getCaptureForm("D01").captureMode).toBe("EVIDENCE_ONLY");
    expect(getCaptureForm("T04").captureMode).toBe("EVIDENCE_ONLY");
  });
  it("rejects contract drift, duplicate codes and invented production-ready statuses",()=>{
    expect(()=>parseCaptureCatalog(catalogSource.replace("formCode\t","code\t"))).toThrow("CATALOG_HEADER_DRIFT");
    expect(()=>parseCaptureCatalog(catalogSource.replace("DF-AST-02\t","DF-AST-01\t"))).toThrow("CATALOG_UNAPPROVED_OR_DUPLICATE_FORM");
    expect(()=>parseCaptureCatalog(catalogSource.replace("DESIGN_ONLY","PRODUCTION_READY")))
      .toThrow("CATALOG_UNAPPROVED_OR_DUPLICATE_FORM");
    expect(()=>getCaptureForm("UNKNOWN-FORM")).toThrow("CATALOG_UNKNOWN_FORM");
  });
  it.each(CAPTURE_CATALOG.filter(f=>f.captureMode==="TYPED_DRAFT"))(
    "$code: a complete typed row round-trips through the canonical validator without claiming financial qualification",
    form=>{
      const input=row(form.code);
      const preview=validateCatalogCapture({...ctx,formCode:form.code},[input]);
      expect(preview.valid,JSON.stringify(preview.findings)).toBe(true);
      expect(preview.publishable).toBe(false);
      expect(preview.status).toBe("UNQUALIFIED_DRAFT");
      expect(preview.rows[0]?.rowId).toBe("ROW-001");
      expect(Object.keys(preview.rows[0]?.cells??{})).toEqual(form.fields.map(f=>f.code));
      for(const field of form.fields){
        expect(preview.rows[0]?.cells[field.code]).toBe(normalizeTypedValue(field.type,sample(field)));
      }
    }
  );
  it.each(CAPTURE_CATALOG.filter(f=>f.captureMode!=="TYPED_DRAFT"))(
    "$code: catalog-only or documentary form cannot silently masquerade as numeric intake",
    form=>{
      expect(()=>validateCatalogCapture({...ctx,formCode:form.code},[row("DF-AST-01")]))
        .toThrow("CATALOG_SCHEMA_NOT_QUALIFIED");
    }
  );
  it.each(CAPTURE_CATALOG.filter(f=>f.captureMode==="TYPED_DRAFT"))(
    "$code: unknown fields and duplicate source row ID always fail",
    form=>{
      const input=row(form.code);
      const validation=validateCatalogCapture({...ctx,formCode:form.code},[
        input,
        {rowId:"ROW-002",cells:{...input.cells,forgedIndicator:"1"}},
        {...input}
      ]);
      expect(validation.valid).toBe(false);
      expect(validation.findings.some(f=>f.code==="CATALOG_UNKNOWN_FIELD")).toBe(true);
      expect(validation.findings.some(f=>f.code==="CATALOG_DUPLICATE_OR_INVALID_ROW_ID")).toBe(true);
    }
  );
  it("rejects invalid date, decimal, decimal-places, integer, text, boolean and period",()=>{
    const invalid:[Parameters<typeof normalizeTypedValue>[0],string][]=[
      ["DATE","2025-02-29"],["DATE","1899-01-01"],["DECIMAL","1e12"],
      ["MONEY","1 000,25"],["MONEY","1.1234567890123"],["INTEGER","1.5"],
      ["TEXT","a".repeat(2001)],["REF","=cmd"],["CODE","CAP@BAD"],["BOOLEAN","yes"],
      ["PERIOD","2021-W53"],["PERIOD","2026.13"]
    ];
    for(const [type,value] of invalid)
      expect(()=>normalizeTypedValue(type,value),type+" "+value.slice(0,25)).toThrow();
    expect(normalizeTypedValue("DATE","2024-02-29")).toBe("2024-02-29");
    expect(normalizeTypedValue("PERIOD","2026-10")).toBe("2026.10");
    expect(normalizeTypedValue("INTEGER","-0000")).toBe("0");
    expect(normalizeTypedValue("BOOLEAN","false")).toBe("false");
    expect(normalizeTypedValue("MONEY","-0012.500")).toBe("-12.5");
  });
  it("missing != zero and unsupported contexts cannot be approved",()=>{
    const input=row("DF-AST-01");
    const blank=validateCatalogCapture(ctx,[{rowId:"ROW-001",cells:{cost:""}}]);
    expect(blank.valid).toBe(false);
    expect(blank.findings.some(f=>f.code==="CATALOG_EMPTY_ROW")).toBe(true);
    const zero=validateCatalogCapture(ctx,[{rowId:"ROW-001",cells:{cost:"0"}}]);
    expect(zero.valid).toBe(true);
    expect(zero.rows[0]?.cells.cost).toBe("0");
    const missing=validateCatalogCapture(ctx,[]);
    expect(missing.valid).toBe(false);
    expect(missing.findings).toEqual(expect.arrayContaining([expect.objectContaining({code:"CATALOG_ROW_COUNT_INVALID"})]));
    for(const values of [
      {...ctx,projectCode:""}, {...ctx,locale:"zh-Hans" as "ru"},
      {...ctx,periodCode:"2026.13"}, {...ctx,amountScale:9 as 1},
      {...ctx,currency:"USD" as "KZT"}
    ])expect(validateCatalogCapture(values,[input]).valid).toBe(false);
    expect(validateCatalogCapture(ctx,Array.from({length:MAX_CATALOG_ROWS+1},(_,i)=>({...input,rowId:"R"+i}))).valid).toBe(false);
  });
});
