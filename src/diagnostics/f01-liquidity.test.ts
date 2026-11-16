import {describe,expect,it} from "vitest";
import {
  F01_LIQUIDITY_FIELDS,F01_LIQUIDITY_VERSION,F01_PILOT_VERSION,
  validateF01LiquidityDraft,type F01SourceCell
} from "./f01-liquidity";

const period="2026-10";
const fixture=():F01SourceCell[] => ([
  ["assets","1000"],["liabilities","600"],["equity","400"],
  ["cash","100"],["receivables","80"],
  ["currentAssets","400"],["currentLiabilities","200"]
] as const).map(([sourceField,enteredAmount])=>({
  sourceRowId:"balance",sourceField,period,enteredAmount
}));

describe("F01 versioned liquidity draft (never independent financial approval)",()=>{
  it("preserves the legacy version while adding two new current-balance fields",()=>{
    expect(F01_PILOT_VERSION).toBe("FIN-PILOT-V1");
    expect(F01_LIQUIDITY_VERSION).toBe("FIN-LIQUIDITY-V1");
    expect(F01_LIQUIDITY_FIELDS).toContain("currentAssets");
    expect(F01_LIQUIDITY_FIELDS).toContain("currentLiabilities");
    expect(F01_LIQUIDITY_FIELDS.length).toBe(7);
  });
  it("accepts an exactly reconciled one-period F01 draft",()=>{
    expect(validateF01LiquidityDraft(fixture())).toEqual([]);
  });
  it("blocks missing values instead of assuming zero",()=>{
    const values=fixture().filter(c=>c.sourceField!=="currentLiabilities");
    expect(validateF01LiquidityDraft(values)).toContain(
      "F01_MISSING_REQUIRED:currentLiabilities");
  });
  it("blocks assets, obligations and date misclassification",()=>{
    const tooMuch=fixture().map(c=>c.sourceField==="currentAssets"
      ? {...c,enteredAmount:"1001"}:c);
    expect(validateF01LiquidityDraft(tooMuch)).toContain(
      "F01_CURRENT_ASSETS_EXCEED_TOTAL");
    const otherPeriod=fixture().map(c=>c.sourceField==="currentLiabilities"
      ? {...c,period:"2026-09"}:c);
    expect(validateF01LiquidityDraft(otherPeriod)).toContain("F01_MIXED_PERIODS");
    const unbalanced=fixture().map(c=>c.sourceField==="equity"
      ? {...c,enteredAmount:"399"}:c);
    expect(validateF01LiquidityDraft(unbalanced)).toContain("F01_BALANCE_IDENTITY_FAILED");
  });
  it("blocks duplicate, wrong coordinate, and negative current liabilities",()=>{
    expect(validateF01LiquidityDraft([...fixture(),fixture()[0]!]))
      .toContain("F01_DUPLICATE_FIELD");
    const forged=fixture().map(c=>c.sourceField==="currentAssets"
      ? {...c,sourceRowId:"invented"}:c);
    expect(validateF01LiquidityDraft(forged)).toContain("F01_UNRECOGNIZED_FIELD_OR_ROW");
    const negative=fixture().map(c=>c.sourceField==="currentLiabilities"
      ? {...c,enteredAmount:"-200"}:c);
    expect(validateF01LiquidityDraft(negative))
      .toContain("F01_NEGATIVE_AMOUNT:currentLiabilities");
  });
});
