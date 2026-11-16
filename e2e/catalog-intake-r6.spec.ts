import {expect,test} from "@playwright/test";
import ExcelJS from "exceljs";

test("FIN R6: typed DF form supports table input, read-only preview and local draft export",async({page})=>{
  await page.goto("/");
  const studio=page.locator("#catalog-intake-r6");
  await expect(studio).toBeVisible();
  await studio.getByLabel("Код проекта (локальный)").fill("FIN_DEMO");
  await studio.getByLabel("ROW-001 assetRef").fill("ASSET-001");
  await studio.getByLabel("ROW-001 type").fill("MACHINE");
  await studio.getByLabel("ROW-001 acquiredAt").fill("2026-10-31");
  await studio.getByLabel("ROW-001 cost").fill("1000.2500");
  await studio.getByLabel("ROW-001 netBookValue").fill("750.10");
  await studio.getByLabel("ROW-001 capacity").fill("42");
  await studio.getByRole("button",{name:"Проверить ввод"}).click();
  await expect(studio.getByRole("heading",{name:/Алдын ала қарау|Предпросмотр/})).toBeVisible();
  await expect(studio.locator(".fin-catalog-preview")).toContainText("UNQUALIFIED_DRAFT");
  await expect(studio.locator(".fin-catalog-preview")).toContainText("1 000,25");
  const [download]=await Promise.all([
    page.waitForEvent("download"),
    studio.getByRole("button",{name:"Скачать JSON черновика"}).click()
  ]);
  expect(download.suggestedFilename()).toContain("UNQUALIFIED_DRAFT.json");
  // No approval/published financial status after client-only draft export.
  await expect(studio.locator(".fin-catalog-warning").first()).toContainText("НЕ серверная загрузка");
});

test("FIN R6: generated typed Excel imports into preview without losing editor draft",async({page})=>{
  await page.goto("/");
  const studio=page.locator("#catalog-intake-r6");
  await studio.getByLabel("Код проекта (локальный)").fill("FIN_DEMO");
  await studio.getByLabel("ROW-001 assetRef").fill("DRAFT-LOCAL");
  const [download]=await Promise.all([
    page.waitForEvent("download"),
    studio.getByRole("button",{name:"Скачать Excel"}).click()
  ]);
  const book=new ExcelJS.Workbook();
  await book.xlsx.readFile((await download.path())!);
  const data=book.getWorksheet("FIN_Catalog")!;
  data.getCell("B4").value="ASSET-001";
  data.getCell("C4").value="MACHINE";
  data.getCell("D4").value="2026-10-31";
  data.getCell("E4").value="1000.25";
  data.getCell("F4").value="750.10";
  data.getCell("G4").value="42";
  const buffer=Buffer.from(await book.xlsx.writeBuffer());
  await studio.getByLabel("Загрузить Excel").setInputFiles({
    name:"FIN_DF-AST-01.xlsx",
    mimeType:"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    buffer
  });
  const preview=studio.locator(".fin-catalog-preview");
  await expect(preview).toContainText("UNQUALIFIED_DRAFT");
  await expect(preview).toContainText("ASSET-001");
  await expect(studio.getByLabel("ROW-001 assetRef")).toHaveValue("DRAFT-LOCAL");
  await studio.getByRole("button",{name:"Перенести значения в редактор"}).click();
  await expect(studio.getByLabel("ROW-001 assetRef")).toHaveValue("ASSET-001");
  await expect(preview).toHaveCount(0);
});

test("FIN R6: 166 choices are classified, 44 unspecialized cannot be uploaded as invented finance",async({page})=>{
  await page.goto("/");
  const studio=page.locator("#catalog-intake-r6");
  const selector=studio.getByLabel("Форма");
  await expect(selector.locator("option")).toHaveCount(166);
  await selector.selectOption("D01");
  await expect(studio.getByRole("alert")).toContainText("Документ/внешняя отчётность");
  await expect(studio.getByRole("button",{name:"Скачать Excel"})).toHaveCount(0);
  await selector.selectOption("F04");
  await expect(studio.getByRole("alert")).toContainText("нет утверждённой");
  await selector.selectOption("SF-C01");
  await expect(studio.getByLabel("ROW-001 plant")).toBeVisible();
  await expect(studio.getByLabel("ROW-001 unitsStarted")).toBeVisible();
  await studio.getByLabel("ROW-001 plant").fill("PLANT-001");
  await studio.getByLabel("ROW-001 unitsStarted").fill("1.25");
  await studio.getByRole("button",{name:"Проверить ввод"}).click();
  await expect(studio.locator(".fin-catalog-preview")).toContainText("UNQUALIFIED_DRAFT");
});
