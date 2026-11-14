import { describe, expect, it } from "vitest";
import { emptyBalance } from "./financial";
import { canApprove, parseAmount, validateBalance } from "./validation";

function validBalance() {
  const values = emptyBalance();
  for (const year of ["2024", "2025"] as const) {
    values[year] = {
      TOTAL_ASSETS: "1000",
      CURRENT_ASSETS: "400",
      TOTAL_LIABILITIES: "600",
      CURRENT_LIABILITIES: "200",
      TOTAL_EQUITY: "400"
    };
  }
  return values;
}

describe("balance validation", () => {
  it("accepts balanced statements", () => {
    expect(canApprove(validateBalance(validBalance()))).toBe(true);
  });
  it("blocks a one-unit difference", () => {
    const values = validBalance();
    values["2025"].TOTAL_ASSETS = "1001";
    expect(validateBalance(values).some(f => f.code === "BALANCE_MISMATCH" && f.year === "2025")).toBe(true);
  });
  it("does not silently interpret missing as zero", () => {
    const values = validBalance();
    values["2024"].CURRENT_ASSETS = "";
    expect(validateBalance(values).some(f => f.code === "MISSING")).toBe(true);
  });
  it("refuses unsupported input and excessive precision", () => {
    expect(parseAmount("12abc")).toBeNull();
    expect(parseAmount("1234567890123456")).toBeNull();
    expect(parseAmount("100,25")?.toString()).toBe("100.25");
  });
  it("allows negative equity but marks it for review", () => {
    const values = validBalance();
    values["2025"].TOTAL_LIABILITIES = "1100";
    values["2025"].TOTAL_EQUITY = "-100";
    const findings = validateBalance(values);
    expect(canApprove(findings)).toBe(true);
    expect(findings.some(f => f.code === "NEGATIVE_EQUITY")).toBe(true);
  });
});
