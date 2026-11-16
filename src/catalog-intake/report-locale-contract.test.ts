import {describe, expect, it} from "vitest";
import {CAPTURE_LOCALES} from "./catalog-schema";
import {REPORT_DISPLAY_LOCALES, fieldPresentation} from "./field-presentation-r6";

describe("FIN-BE-R1-X X0 — input locale is not report display locale",()=>{
  it("keeps existing local Excel inputs tri-language but supports four report labels",()=>{
    expect(CAPTURE_LOCALES).toEqual(["ru","kk","en"]);
    expect(REPORT_DISPLAY_LOCALES).toEqual(["ru","kk","en","zh-Hans"]);
    const field={code:"assetRef",type:"REF" as const};
    const display=fieldPresentation("DF-AST-01",field,"zh-Hans");
    expect(display.label).toBe("资产标识");
    expect(display.translationReview).toBe("PROVISIONAL_TRANSLATION");
    expect(fieldPresentation("F02",{code:"netRevenue",type:"MONEY"},"zh-Hans"))
      .toMatchObject({label:"netRevenue",translationReview:"TRANSLATION_NOT_READY"});
  });
});
