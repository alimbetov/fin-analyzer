import { test, expect } from "@playwright/test";
import ExcelJS from "exceljs";

test("IND-R1: React sector pilot runs on approved values, preserves prior revisions and reopens",async({page})=>{
  const external:string[]=[];
  page.on("request",r=>{if(!r.url().startsWith("http://127.0.0.1:4173/") &&
    !r.url().startsWith("blob:") && !r.url().startsWith("data:"))external.push(r.url());});
  await page.goto("/");
  await page.getByLabel("Название компании").fill("ТОО Отраслевая Проверка");
  await expect(page.getByRole("heading",{name:"Отраслевая диагностика"})).toBeVisible();
  await expect(page.getByLabel("Сектор ОКЭД").locator("option")).toHaveCount(21);
  await page.getByLabel("Сектор ОКЭД").selectOption("G");
  await page.getByLabel("Вариант деятельности").selectOption("G_RETAIL");
  await page.getByLabel("Отраслевая форма").selectOption("SF-G01");
  await page.getByLabel("Код объекта").fill("sku-1");
  await page.getByLabel("Месяц отраслевых данных").fill("2026-11");
  await page.getByLabel("Отраслевое поле netRevenue").fill("1000");
  await page.getByLabel("Отраслевое поле cogs").fill("540");
  await page.getByLabel("Отраслевое поле soldQty").fill("90");
  await expect(page.getByText("Для текущей формы и объекта подтверждённых данных пока нет.")).toBeVisible();
  await page.getByRole("button",{name:"Подтвердить отраслевые данные"}).click();
  await expect(page.getByText("46.00",{exact:true})).toBeVisible();
  await expect(page.getByText(/Неполный период/).first()).toBeVisible();
  await page.getByLabel("Отраслевое поле cogs").fill("500");
  await page.getByRole("button",{name:"Подтвердить отраслевые данные"}).click();
  await expect(page.getByText("50.00",{exact:true})).toBeVisible();
  await expect(page.getByText(/прошлых ревизий: 1/)).toBeVisible();
  const [saved]=await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("button",{name:/Сохранить .fin.json/}).click()
  ]);
  const savedPath=await saved.path();
  expect(savedPath).toBeTruthy();
  await page.reload();
  await page.getByLabel("Выбрать FIN-проект").setInputFiles(savedPath!);
  await expect(page.getByText("50.00",{exact:true})).toBeVisible();
  await expect(page.getByText(/прошлых ревизий: 1/)).toBeVisible();
  await page.screenshot({path:"test-results/sector-accepted.png",fullPage:true});
  await page.getByLabel("Сектор ОКЭД").selectOption("K");
  await expect(page.getByText(/исполняемый расчёт не реализован/)).toBeVisible();
  await expect(page.getByRole("button",{name:"Подтвердить отраслевые данные"})).toHaveCount(0);
  expect(external).toEqual([]);
});

test("IND-R1: controlled sector Excel import is a draft until explicit approval",async({page})=>{
  await page.goto("/");
  await page.getByLabel("Название компании").fill("ТОО Excel Sector");
  const [download]=await Promise.all([
    page.waitForEvent("download"),page.getByRole("button",{name:/Скачать Excel для SF-G01/}).click()
  ]);
  const book=new ExcelJS.Workbook();
  await book.xlsx.readFile((await download.path())!);
  const sector=book.getWorksheet("Sector")!;
  sector.getCell("B5").value=1000;
  sector.getCell("B6").value=540;
  sector.getCell("B7").value=90;
  const buffer=Buffer.from(await book.xlsx.writeBuffer());
  await page.getByLabel("Выбрать отраслевой XLSX").setInputFiles({
    name:"FIN_SF-G01.xlsx",
    mimeType:"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",buffer
  });
  await expect(page.getByLabel("Отраслевое поле netRevenue")).toHaveValue("1000");
  await expect(page.getByText("Для текущей формы и объекта подтверждённых данных пока нет.")).toBeVisible();
  await page.getByRole("button",{name:"Подтвердить отраслевые данные"}).click();
  await expect(page.getByText("46.00",{exact:true})).toBeVisible();
  sector.getCell("B5").value={formula:"900+100",result:1000};
  const bad=Buffer.from(await book.xlsx.writeBuffer());
  await page.getByLabel("Выбрать отраслевой XLSX").setInputFiles({
    name:"FIN_SF-G01.xlsx",
    mimeType:"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",buffer:bad
  });
  await expect(page.getByRole("alert")).toContainText("FORMULA_OR_INVALID");
  await expect(page.getByText("46.00",{exact:true})).toBeVisible();
});
