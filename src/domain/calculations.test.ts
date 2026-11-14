import { describe, expect, it } from "vitest";
import { emptyBalance } from "./financial";
import { assetsGrowthPercent, currentRatios } from "./calculations";

describe("financial indicators", () => {
  it("uses decimal arithmetic and period-specific values", () => {
    const values = emptyBalance();
    values["2024"].TOTAL_ASSETS = "300";
    values["2024"].CURRENT_ASSETS = "100";
    values["2024"].CURRENT_LIABILITIES = "30";
    values["2025"].TOTAL_ASSETS = "330";
    values["2025"].CURRENT_ASSETS = "150";
    values["2025"].CURRENT_LIABILITIES = "50";
    expect(currentRatios(values)).toEqual([
      { year: "2024", value: "3.3333" },
      { year: "2025", value: "3" }
    ]);
    expect(assetsGrowthPercent(values)).toBe("10");
  });
  it("never returns infinity on a zero denominator", () => {
    const values = emptyBalance();
    values["2025"].CURRENT_ASSETS = "15";
    values["2025"].CURRENT_LIABILITIES = "0";
    expect(currentRatios(values)[1]?.value).toBeNull();
  });
});
