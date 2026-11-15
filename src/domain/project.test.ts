import { describe, expect, it } from "vitest";
import { emptyBalance, FORM_VERSION } from "./financial";
import { createProject, readProject } from "./project";

function validValues() {
  const values = emptyBalance();
  for (const year of ["2024", "2025"] as const) {
    values[year] = {
      TOTAL_ASSETS: "900", CURRENT_ASSETS: "400",
      TOTAL_LIABILITIES: "500", CURRENT_LIABILITIES: "200", TOTAL_EQUITY: "400"
    };
  }
  return values;
}

describe("local FIN project", () => {
  it("round-trips an approved revision", () => {
    const values = validValues();
    const project = createProject("Test", values, [{
      id: "r1", company: "Test", formVersion: FORM_VERSION,
      approvedAt: "2026-11-15T00:00:00.000Z", values
    }]);
    const restored = readProject(JSON.stringify(project));
    expect(restored.revisions[0]?.values["2025"].TOTAL_ASSETS).toBe("900");
  });
  it("rejects a tampered approved revision", () => {
    const values = validValues();
    const project = createProject("Test", values, [{
      id: "r1", company: "Test", formVersion: FORM_VERSION,
      approvedAt: "2026-11-15T00:00:00.000Z", values
    }]);
    project.revisions[0]!.values["2025"].TOTAL_EQUITY = "100";
    expect(() => readProject(JSON.stringify(project))).toThrow(/недействительная/);
  });
  it("preserves locally approved sector revisions when reopening the same FIN file", async () => {
    const { createSectorState, prepareSectorRow, approveSectorRow } = await import("../sector/state");
    const original = createSectorState("company-one");
    const approval = prepareSectorRow(original,"G_RETAIL","SF-G01","sku-1","KZT","2026-11",
      {netRevenue:"1000",cogs:"540",soldQty:"90"});
    const state = approveSectorRow(original,approval);
    const restored = readProject(JSON.stringify(createProject("Test",validValues(),[],state)));
    expect(restored.sector?.current[0]?.row.revisionId).toEqual(approval.row.revisionId);
    const corrupted=JSON.parse(JSON.stringify(restored));
    corrupted.sector.current[0].row.values.cogs="-1";
    expect(()=>readProject(JSON.stringify(corrupted))).toThrow();
  });
  it("rejects incompatible project schema", () => {
    expect(() => readProject('{"schemaVersion":"fin.project/999"}')).toThrow(/структура/);
  });
});
