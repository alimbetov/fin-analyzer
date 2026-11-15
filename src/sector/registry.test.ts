import { describe, expect, it } from "vitest";
import sectorSource from "../../backend/src/main/resources/fin/sector-business-model-packs-v2.tsv?raw";
import formSource from "../../backend/src/main/resources/fin/form-compatibility.tsv?raw";
import { PILOT_FORMS } from "../../packages/sector-pilot/src/index.mjs";
import { SECTOR_PACKS, modelPack, parseSectorRegistry, pilotCode, selectForms } from "./registry";

describe("IND-R0 executable coverage registry", () => {
  it("maps all 21 sections, 64 variants and 63 qualified-or-draft form references", () => {
    expect(SECTOR_PACKS.map(p => p.section).join("")).toBe("ABCDEFGHIJKLMNOPQRSTU");
    expect(new Set(SECTOR_PACKS.flatMap(p => p.models)).size).toBe(64);
    expect(new Set(SECTOR_PACKS.flatMap(p => p.forms)).size).toBe(63);
    expect(SECTOR_PACKS.every(p => p.catalogStatus === "DESIGN_ONLY")).toBe(true);
  });
  it("routes each model only to its section and limits executable forms to real pilots", () => {
    expect(selectForms("G_RETAIL").map(f => f.code)).toEqual(["SF-G01", "SF-G02", "SF-G03"]);
    expect(selectForms("G_RETAIL").every(f => f.executable)).toBe(true);
    expect(selectForms("K_BANKING").every(f => !f.executable)).toBe(true);
    expect(modelPack("NOT_REAL")).toBeNull();
    expect(selectForms("NOT_REAL")).toEqual([]);
    expect(SECTOR_PACKS.flatMap(s => s.forms).filter(code => pilotCode(code)).sort()).toEqual([...PILOT_FORMS].sort());
  });
  it("fails closed on malformed, duplicate or cross-sector mapping", () => {
    expect(() => parseSectorRegistry(sectorSource.replace("G_RETAIL", "A_CROP"), formSource)).toThrow();
    expect(() => parseSectorRegistry(sectorSource.replace("SF-G01;SF-G02;SF-G03", "SF-A01;SF-G02;SF-G03"), formSource)).toThrow();
    expect(() => parseSectorRegistry(sectorSource.replace("DESIGN_ONLY", "UAT_ACCEPTED"), formSource)).toThrow();
    expect(() => parseSectorRegistry(sectorSource.replace("G_RETAIL", "G_WHOLESALE"), formSource)).toThrow();
  });
});
