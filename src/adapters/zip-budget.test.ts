import { describe, expect, it } from "vitest";
import { generateBalanceTemplate } from "./excel";
import { enforceZipBudget } from "./zip-budget";

async function template(): Promise<ArrayBuffer> {
  return generateBalanceTemplate("Demo").then(blob => blob.arrayBuffer());
}
function centralEntryOffset(buffer: ArrayBuffer): number {
  const view = new DataView(buffer);
  for (let i = buffer.byteLength - 22; i >= Math.max(0, buffer.byteLength - 65557); i--) {
    if (view.getUint32(i, true) === 0x06054b50) {
      return view.getUint32(i + 16, true);
    }
  }
  throw Error("ZIP end marker not found");
}

describe("XLSX preflight resource budget", () => {
  it("accepts an ordinary FIN XLSX", async () => {
    const input = await template();
    expect(() => enforceZipBudget(input)).not.toThrow();
  });
  it("rejects arbitrary bytes before calling ExcelJS", () => {
    expect(() => enforceZipBudget(new Uint8Array([1, 2, 3, 4]).buffer)).toThrow(/ZIP/);
  });
  it("rejects oversized compressed files", () => {
    expect(() => enforceZipBudget(new Uint8Array(2_000_001).buffer)).toThrow(/2 МБ/);
  });
  it("rejects ZIP entries declaring huge unpacked sizes", async () => {
    const input = await template();
    const view = new DataView(input);
    const entry = centralEntryOffset(input);
    view.setUint32(entry + 24, 40_000_000, true);
    expect(() => enforceZipBudget(input)).toThrow(/бюджет распаковки/);
  });
  it("rejects ZIP files with enormous claimed entry counts", async () => {
    const input = await template();
    const view = new DataView(input);
    for (let i = input.byteLength - 22; i >= 0; i--) {
      if (view.getUint32(i, true) === 0x06054b50) {
        view.setUint16(i + 8, 400, true);
        view.setUint16(i + 10, 400, true);
        break;
      }
    }
    expect(() => enforceZipBudget(input)).toThrow(/центральный каталог/);
  });
});
