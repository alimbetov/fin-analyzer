import { test, expect } from "@playwright/test";

test("FIN-DIAG-SMOKE: negative SKU margin shows an observation but no invented causal certainty or severity",
  async ({page}) => {
    await page.goto("/");
    await page.getByLabel("Название компании").fill("ТОО Synthetic Diagnostic Smoke");
    await page.getByLabel("Сектор ОКЭД").selectOption("G");
    await page.getByLabel("Вариант деятельности").selectOption("G_RETAIL");
    await page.getByLabel("Отраслевая форма").selectOption("SF-G01");
    await page.getByLabel("Код объекта").fill("smoke-sku-1");
    await page.getByLabel("Месяц отраслевых данных").fill("2026-01");
    await page.getByLabel("Отраслевое поле netRevenue").fill("1000");
    await page.getByLabel("Отраслевое поле cogs").fill("1100");
    await page.getByLabel("Отраслевое поле soldQty").fill("50");
    await page.getByRole("button",{name:"Подтвердить отраслевые данные"}).click();
    const findings=page.getByLabel("Диагностические выводы 2026-01");
    await expect(findings).toContainText("NEGATIVE_SKU_GROSS_PROFIT");
    await expect(findings).toContainText("VERIFY_REVENUE_COGS_AND_RETURNS");
    await expect(findings).toContainText("причинность не доказана");
    await expect(findings).toContainText("Существенность: методика не утверждена");
    await expect(findings).toContainText("Эффект: требуется отдельный сценарий");
    await page.screenshot({path:"test-results/fin-diagnostic-smoke.png",fullPage:true});
  });
