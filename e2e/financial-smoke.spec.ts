import { test, expect } from "@playwright/test";
import ExcelJS from "exceljs";

test("FIN-R1A: XLSX round-trip, negative validation, approved analysis and project reopen", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: /Финансовая отчётность/ })).toBeVisible();
  await page.getByLabel("Название компании").fill("ТОО Финанс Тест");

  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("button", { name: /Скачать шаблон Excel/ }).click()
  ]);
  const templatePath = await download.path();
  expect(templatePath).toBeTruthy();

  const workbook = new ExcelJS.Workbook();
  await workbook.xlsx.readFile(templatePath!);
  const balance = workbook.getWorksheet("Balance");
  expect(balance).toBeTruthy();
  for (const col of ["C", "D"]) {
    [1000, 400, 600, 200, 400].forEach((value, index) => {
      balance!.getCell(col + (index + 6)).value = value;
    });
  }

  // Start with a deliberately invalid balance. It must never become approved.
  balance!.getCell("D6").value = 1001;
  let buffer = Buffer.from(await workbook.xlsx.writeBuffer());
  await page.getByLabel("Выбрать заполненный XLSX").setInputFiles({
    name: "FIN_BALANCE.xlsx",
    mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    buffer
  });
  await expect(page.getByText(/Баланс 2025 не сходится/)).toBeVisible();
  await expect(page.getByRole("button", { name: /Утвердить баланс/ })).toBeDisabled();
  await expect(page.getByText(/Анализ появится после утверждения/)).toBeVisible();

  // Correct it and approve. The ratios and chart must render from approved values.
  balance!.getCell("D6").value = 1000;
  buffer = Buffer.from(await workbook.xlsx.writeBuffer());
  await page.getByLabel("Выбрать заполненный XLSX").setInputFiles({
    name: "FIN_BALANCE.xlsx",
    mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    buffer
  });
  await expect(page.getByText("Баланс проходит проверки")).toBeVisible();
  await page.getByRole("button", { name: /Утвердить баланс/ }).click();
  await expect(page.getByText("На основании утверждённой версии")).toBeVisible();
  await expect(page.getByText("Структура баланса")).toBeVisible();
  await expect(page.getByText("Текущая ликвидность")).toBeVisible();

  // Local project file must restore the approved state after refresh.
  const [saved] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("button", { name: /Сохранить .fin.json/ }).click()
  ]);
  const savedPath = await saved.path();
  expect(savedPath).toBeTruthy();
  await page.reload();
  await expect(page.getByText(/Анализ появится после утверждения/)).toBeVisible();
  await page.getByLabel("Выбрать FIN-проект").setInputFiles(savedPath!);
  await expect(page.getByText("Есть утверждённая версия")).toBeVisible();
  await expect(page.getByText("Структура баланса")).toBeVisible();
});
