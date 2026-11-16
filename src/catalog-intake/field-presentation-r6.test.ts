import {describe,it,expect} from "vitest";
import {
 PRESENTATION_REVIEW_INVENTORY,PRESENTATION_HEADER,parsePresentationInventory,
 fieldPresentation,formHasAllHumanLabels
} from "./field-presentation-r6";
import {CAPTURE_CATALOG} from "./catalog-schema";
import {SPECIALIZED_CANDIDATES} from "./specialized-contracts";
import raw from "../../backend/src/main/resources/fin/form-field-presentation-review-r6.tsv?raw";

describe("FIN R6.3/R6.4 — exhaustively enumerated field method/i18n readiness",()=>{
  it("every DF/SF and all 32 specialized schema fields have one explicit 4-locale review slot",()=>{
    const known=[
      ...CAPTURE_CATALOG.filter(f=>f.captureMode==="TYPED_DRAFT")
        .flatMap(form=>form.fields.map(f=>[form.code,f.code,f.type].join(":"))),
      ...SPECIALIZED_CANDIDATES.flatMap(form=>form.fields.map(f=>[form.code,f.code,f.type].join(":")))
    ];
    const registered=PRESENTATION_REVIEW_INVENTORY.map(x=>[x.formCode,x.fieldCode,x.fieldType].join(":"));
    expect(new Set(known)).toEqual(new Set(registered));
    expect(registered).toHaveLength(794);
    expect(new Set(registered).size).toBe(794);
    expect(PRESENTATION_REVIEW_INVENTORY.filter(x=>x.reviewStatus==="PROVISIONAL_NOT_S2")).toHaveLength(9);
    expect(PRESENTATION_REVIEW_INVENTORY.filter(x=>x.reviewStatus==="PENDING_TRANSLATION")).toHaveLength(785);
    expect(PRESENTATION_REVIEW_INVENTORY.every(x=>x.requiredness==="PENDING_METHOD_REVIEW")).toBe(true);
  });
  it("four human labels are present for a pilot only; unreviewed forms fail translation completeness honestly",()=>{
    const pilot=CAPTURE_CATALOG.find(f=>f.code==="DF-AST-01")!;
    for(const locale of ["ru","kk","en","zh-Hans"] as const){
      expect(formHasAllHumanLabels(pilot.code,pilot.fields,locale)).toBe(true);
      for(const field of pilot.fields){
        const result=fieldPresentation(pilot.code,field,locale);
        expect(result.label).not.toBe(field.code);
        expect(result.translationReview).toBe("PROVISIONAL_TRANSLATION");
        expect(result.requiredness).toBe("UNSPECIFIED_PENDING_METHOD_REVIEW");
        expect(result.help.length).toBeGreaterThan(20);
      }
    }
    const other=CAPTURE_CATALOG.find(f=>f.code==="SF-C01")!;
    for(const locale of ["ru","kk","en","zh-Hans"] as const)
      expect(formHasAllHumanLabels(other.code,other.fields,locale)).toBe(false);
    expect(fieldPresentation("F02",{code:"netRevenue",type:"MONEY"},"zh-Hans").translationReview)
      .toBe("TRANSLATION_NOT_READY");
  });
  it("no accidental approved statuses, missing schema rows, dup keys or phantom translations",()=>{
    expect(PRESENTATION_HEADER).toContain("labelZhHans");
    expect(()=>parsePresentationInventory(raw.replace("formCode\t","code\t")))
      .toThrow("R6_FIELD_TRANSLATION_HEADER_DRIFT");
    expect(()=>parsePresentationInventory(raw.replace("PROVISIONAL_NOT_S2","APPROVED")))
      .toThrow("R6_FIELD_TRANSLATION_DUPLICATE_OR_UNAPPROVED");
    const lines=raw.trimEnd().split(/\r?\n/);
    expect(()=>parsePresentationInventory(lines.slice(0,-1).join("\n")))
      .toThrow("R6_FIELD_TRANSLATION_COVERAGE_DRIFT");
    expect(()=>parsePresentationInventory(raw.replace("PENDING_TRANSLATION","PROVISIONAL_NOT_S2")))
      .toThrow("R6_FIELD_TRANSLATION_DRAFT_WITHOUT_LABEL");
  });
});
