import { test, expect } from "@playwright/test";
import ExcelJS from "exceljs";

test("FIN V5: localized React table, validation, exact preview and draft export",async ({page})=>{
  await page.goto("/");
  const studio=page.locator("#form-intake-v5");
  await expect(studio.getByRole("heading",{name:"Универсальный ввод финансовой формы"})).toBeVisible();
  await studio.getByLabel("Код проекта (локальный черновик)").fill("FIN-UAT");
  await studio.getByLabel("Чистая выручка").fill("00400.00");
  await studio.getByLabel("Себестоимость").fill("260");
  await studio.getByLabel("Операционные расходы").fill("90");
  await studio.getByRole("button",{name:"Проверить и показать Preview"}).click();
  const preview=studio.getByRole("region",{name:"Предпросмотр данных"});
  await expect(preview).toBeVisible();
  await expect(preview.getByText("400,00")).toBeVisible();
  await expect(preview.getByText("260,00")).toBeVisible();
  await expect(preview.getByText("90,00")).toBeVisible();
  await expect(preview.getByText("Проверено").first()).toBeVisible();
  await page.screenshot({path:"test-results/fin-v5-entry-preview-"+test.info().project.name+".png",fullPage:true});
  const [file]=await Promise.all([
    page.waitForEvent("download"),
    studio.getByRole("button",{name:"Скачать локальный черновик JSON"}).click()
  ]);
  expect(file.suggestedFilename()).toContain("LOCAL_UNVERIFIED_DRAFT");
  // Language switching does not erase raw data, no new financial approval is claimed.
  await studio.getByLabel("Язык шаблона").selectOption("kk");
  await expect(studio.getByLabel("Таза түсім")).toHaveValue("00400.00");
  await expect(studio.getByText(/Жергілікті интерфейс пилоты/)).toBeVisible();
});

test("FIN V5: downloaded locale-specific XLSX rows 1/2/3+ round-trip to same preview",async({page})=>{
  await page.goto("/");
  const studio=page.locator("#form-intake-v5");
  await studio.getByLabel("Код проекта (локальный черновик)").fill("FIN-UAT");
  await studio.getByLabel("Язык шаблона").selectOption("en");
  const [file]=await Promise.all([
    page.waitForEvent("download"),
    studio.getByRole("button",{name:"Download Excel"}).click()
  ]);
  const book=new ExcelJS.Workbook();
  await book.xlsx.readFile((await file.path())!);
  const sheet=book.getWorksheet("FIN_Data")!;
  expect(sheet.getCell("B1").value).toBe("en");
  expect(sheet.getCell("C1").value).toBe("2026.10");
  expect(sheet.getCell("B2").value).toBe("Indicator code");
  expect(sheet.getCell("C3").value).toBe("Net revenue");
  expect(sheet.getCell("D3").value).toBe(null);
  sheet.getCell("D3").value=400;
  sheet.getCell("D4").value=260;
  sheet.getCell("D5").value=90;
  const buffer=Buffer.from(await book.xlsx.writeBuffer());
  // Source template language can differ from the language used to review it.
  await studio.getByLabel("Template language").selectOption("ru");
  await studio.getByLabel("Загрузить Excel").setInputFiles({
    name:"FIN_v5_F02_en.xlsx",
    mimeType:"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    buffer
  });
  const preview=studio.getByRole("region",{name:"Предпросмотр данных"});
  await expect(preview).toBeVisible();
  await expect(preview.getByText("400,00")).toBeVisible();
  await expect(preview.getByText("260,00")).toBeVisible();
  await expect(preview.getByText("90,00")).toBeVisible();
  await page.screenshot({path:"test-results/fin-v5-xlsx-preview-"+test.info().project.name+".png",fullPage:true});
  sheet.getCell("A1").value="F01";
  const tampered=Buffer.from(await book.xlsx.writeBuffer());
  await studio.getByLabel("Загрузить Excel").setInputFiles({
    name:"tampered.xlsx",
    mimeType:"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    buffer:tampered
  });
  await expect(studio.getByRole("alert")).toContainText("XLSX_V5_VISIBLE_HEADER_CHANGED");
  await expect(preview).toBeVisible();
});


test("FIN V5: Excel parsed after a project switch cannot overwrite the new context",async({page})=>{
  await page.addInitScript(()=>{
    const original=File.prototype.arrayBuffer;
    File.prototype.arrayBuffer=function(){
      const source=this;
      return new Promise<ArrayBuffer>((resolve,reject)=>{
        window.addEventListener("fin-v5-resume",()=>{
          original.call(source).then(resolve,reject);
        },{once:true});
      });
    };
  });
  await page.goto("/");
  const studio=page.locator("#form-intake-v5");
  await studio.getByLabel("Код проекта (локальный черновик)").fill("FIN-UAT");
  await studio.getByLabel("Чистая выручка").fill("123");
  const [download]=await Promise.all([
    page.waitForEvent("download"),
    studio.getByRole("button",{name:"Скачать Excel"}).click()
  ]);
  const workbook=new ExcelJS.Workbook();
  await workbook.xlsx.readFile((await download.path())!);
  const sheet=workbook.getWorksheet("FIN_Data")!;
  sheet.getCell("D3").value=400;
  sheet.getCell("D4").value=260;
  sheet.getCell("D5").value=90;
  const buffer=Buffer.from(await workbook.xlsx.writeBuffer());
  await studio.getByLabel("Загрузить Excel").setInputFiles({
    name:"pending-import.xlsx",
    mimeType:"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    buffer
  });
  await studio.getByLabel("Код проекта (локальный черновик)").fill("FIN-OTHER");
  await page.evaluate(()=>window.dispatchEvent(new Event("fin-v5-resume")));
  await expect(studio.getByRole("alert")).toContainText("XLSX_V5_IMPORT_CONTEXT_CHANGED");
  await expect(studio.getByLabel("Чистая выручка")).toHaveValue("123");
  await expect(studio.getByRole("region",{name:"Предпросмотр данных"})).toHaveCount(0);
});
