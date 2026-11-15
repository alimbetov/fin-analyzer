import { expect,test } from "@playwright/test";

test("FIN-R4.6: graph planner links multiple forms but does not invent business calculations", async ({page})=>{
  await page.goto("/");
  const panel=page.getByLabel("Межформенный диагностический граф");
  await expect(panel).toBeVisible();
  await expect(panel).toContainText("166");
  await expect(panel).toContainText("источников пилота");
  await expect(panel).toContainText("план исследования");
  await page.getByLabel("Диагностическая проблема").selectOption("PROFITABILITY");
  await expect(panel.getByLabel("Смежные методики").locator("article")).toHaveCount(3);
  await expect(panel.getByLabel("Необходимые отчёты")).toContainText("F02");
  await expect(panel.getByLabel("Необходимые отчёты")).toContainText("F01");
  await expect(panel.getByLabel("Необходимые отчёты")).toContainText("F03");
  await expect(panel).toContainText("не подтверждены финансовыми данными");
  await page.getByLabel("Глубина графа").selectOption("3");
  await expect(panel.getByLabel("Смежные методики").locator("article")).toHaveCount(5);
  await expect(panel.getByLabel("Необходимые отчёты")).toContainText("DF-CSH-01");
  await expect(panel.getByLabel("Необходимые отчёты")).toContainText("DF-CSH-02");

  await page.getByLabel("Источник диагностики").selectOption("form");
  await page.getByLabel("Исходная форма графа").selectOption("D01");
  await expect(panel).toContainText("нет методически зарегистрированной исполняемой финансовой связи");
  await expect(panel.getByLabel("Смежные методики")).toHaveCount(0);
  await page.getByLabel("Исходная форма графа").selectOption("F01");
  await expect(panel.getByLabel("Смежные методики").locator("article").first())
    .toContainText("Проверка бухгалтерского баланса");
  await page.screenshot({path:"test-results/fin-r4-6-cross-form-planner.png",fullPage:true});
});
