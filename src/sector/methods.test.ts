import { describe, expect, it } from "vitest";
import { evaluateSectorForms, FORM_FIELDS, PILOT_FORMS, type PilotInput } from "../../packages/sector-pilot/src/index.mjs";
import { PILOT_METHODS } from "./methods";

describe("IND-R1 sector method passports",()=>{
  it("has a draft method description for every actually emitted pilot KPI, in RU/KK/EN",()=>{
    const rows:PilotInput[]=PILOT_FORMS.map((formCode,index)=>({
      formCode,businessId:"company-1",dimensionId:"dim-"+index,currency:"KZT",
      period:"2026-01",sourceId:"src-"+index,revisionId:"rev-"+index,approved:true,
      values:Object.fromEntries(FORM_FIELDS[formCode].map(code=>[code,"1"]))
    }));
    const actual=new Set(evaluateSectorForms(rows).filter(x=>x.windowType==="MONTH")
      .flatMap(x=>Object.keys(x.metrics)));
    expect(actual).toEqual(new Set(Object.keys(PILOT_METHODS)));
    for(const [code,item] of Object.entries(PILOT_METHODS)){
      expect(item.code).toBe(code);
      expect(item.qualification).toBe("PILOT_UNQUALIFIED");
      expect(item.formula.length).toBeGreaterThan(3);
      for(const language of ["ru","kk","en"] as const){
        const t=item.translations[language];
        for(const part of ["name","what","how","shows"] as const) {
          expect(t[part].trim(),code+"/"+language+"/"+part).not.toBe("");
        }
      }
    }
  });
});
