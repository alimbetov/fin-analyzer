import { test,expect } from "@playwright/test";

test("FIN P0: default browser has no working financial session or counterfeit ACTUAL diagnosis",async({page})=>{
  await page.goto("/");
  const workspace=page.getByLabel("Доверенные финансовые источники");
  await expect(workspace).toBeVisible();
  await expect(workspace).toContainText("ACTUAL · PRODUCTION HOLD");
  await expect(workspace).toContainText("Подключение к защищённому API отключено");
  await expect(workspace.getByRole("button",{name:"Утвердить источники"})).toHaveCount(0);
  await expect(workspace.getByRole("button",{name:"Опубликовать ACTUAL FactSetRevision"})).toHaveCount(0);
  await expect(workspace.getByLabel("Объяснимые финансовые выводы")).toHaveCount(0);
  await expect(page.getByLabel("Межформенный диагностический граф")).toContainText(
    "план исследования, а не расчёт по реальной организации");
  await page.screenshot({path:"test-results/fin-p0-default-deny.png",fullPage:true});
});
