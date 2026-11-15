import { describe, expect, it } from "vitest";
import { approveSectorRow, calculateSectorState, createSectorState, prepareSectorRow, readSectorState } from "./state";

const trade = { netRevenue: "1000", cogs: "540", soldQty: "90" };
describe("IND-R1 data acceptance and durability", () => {
  it("rejects missing and invalid values before approval", () => {
    const state = createSectorState("company-one");
    expect(() => prepareSectorRow(state,"G_RETAIL","SF-G01","sku-1","KZT","2026-01",
      {...trade,cogs:""})).toThrow("SECTOR_REQUIRED_FIELDS_MISSING");
    expect(() => prepareSectorRow(state,"G_RETAIL","SF-G01","sku-1","KZT","2026-01",
      {...trade,cogs:"-5"})).toThrow();
    expect(() => prepareSectorRow(state,"K_BANKING","SF-G01","sku-1","KZT","2026-01",
      trade)).toThrow("SECTOR_INVALID_SELECTION");
  });
  it("calculates from locally confirmed revision only and preserves previous revision", () => {
    const blank = createSectorState("company-one");
    expect(calculateSectorState(blank)).toEqual([]);
    const first = prepareSectorRow(blank,"G_RETAIL","SF-G01","sku-1","KZT","2026-01",trade);
    const approved = approveSectorRow(blank,first);
    const month = calculateSectorState(approved).find(x => x.windowType === "MONTH");
    expect(month?.metrics.SKU_GROSS_MARGIN_PCT?.value).toBe("46.00");
    const second = prepareSectorRow(approved,"G_RETAIL","SF-G01","sku-1","KZT","2026-01",
      {...trade,cogs:"500"});
    const updated = approveSectorRow(approved,second);
    expect(updated.prior.map(x=>x.row.revisionId)).toEqual([first.row.revisionId]);
    expect(calculateSectorState(updated).find(x=>x.windowType==="MONTH")?.metrics.SKU_GROSS_MARGIN_PCT?.value).toBe("50.00");
    expect(calculateSectorState(readSectorState(JSON.parse(JSON.stringify(updated)))).length).toBeGreaterThan(0);
  });
  it("rejects tampered form scope and duplicated revision", () => {
    const base = createSectorState("company-one");
    const item = prepareSectorRow(base,"G_RETAIL","SF-G01","sku-1","KZT","2026-01",trade);
    const accepted = approveSectorRow(base,item);
    const invalid = JSON.parse(JSON.stringify(accepted));
    invalid.current[0].modelCode = "M_CONSULTING";
    expect(() => readSectorState(invalid)).toThrow();
    const duplicate = {...accepted, prior: [item]};
    expect(() => readSectorState(duplicate)).toThrow("SECTOR_DUPLICATE_SOURCE_OR_REVISION");
  });
  it("keeps distinct business models and their dimensions separate", () => {
    let state = createSectorState("company-one");
    state = approveSectorRow(state,prepareSectorRow(state,"G_RETAIL","SF-G01","sku-1","KZT","2026-01",trade));
    state = approveSectorRow(state,prepareSectorRow(state,"G_WHOLESALE","SF-G01","sku-1","KZT","2026-01",trade));
    expect(calculateSectorState(state).filter(x => x.windowType==="MONTH").length).toBe(2);
  });
});
