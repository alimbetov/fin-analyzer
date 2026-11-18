import {test,expect,type Page} from '@playwright/test';

/**
 * Real-browser functional proof: actual React + Spring Boot + PostgreSQL,
 * not a mocked screenshot or an HTTP-only test.
 * All values are synthetic, generated in a disposable CI database.
 */

async function selectBankFlow(page:Page){
  await page.goto('/');
  await expect(page.locator('.kpi-header h2')).toHaveText('От вопроса — к нужным данным.');
  await expect(page.locator('.kpi-goal-option')).toHaveCount(7);
  const bank=page.locator('.kpi-goal-option').filter({hasText:'Подготовка к кредитованию'});
  await bank.click();
  await expect(bank).toHaveAttribute('aria-current','step');
  const netProfit=page.locator('.kpi-indicator-option').filter({hasText:'NET_PROFIT'});
  await netProfit.click();
  await expect(netProfit).toHaveAttribute('aria-pressed','true');
  await expect(page.locator('.kpi-tree-root')).toContainText('NET_PROFIT');
  const model=page.locator('.kpi-model-toggle').filter({hasText:'CFO-01'});
  await expect(model).toHaveAttribute('aria-expanded','true');
  await expect(page.getByRole('button',{name:'Открыть форму F02 из модели CFO-01'})).toBeVisible();
}

async function openF02(page:Page){
  await selectBankFlow(page);
  await page.getByRole('button',{name:'Открыть форму F02 из модели CFO-01'}).click();
  await expect(page.locator('.decision-context')).toContainText('BANK_FINANCING');
  await expect(page.locator('.decision-context')).toContainText('NET_PROFIT');
  await expect(page.locator('.decision-context')).toContainText('CFO-01');
  await expect(page.locator('.decision-context')).toContainText('F02');
  await expect(page.locator('#field-netRevenue')).toBeVisible();
}

test('FIN UX-R1.1: question → metric → source F02 → PostgreSQL revision → reopen → exact branch',async({page},testInfo)=>{
  await openF02(page);
  const netRevenue=page.locator('#field-netRevenue');
  const cogs=page.locator('#field-cogs');
  const operating=page.locator('#field-operatingExpenses');
  await netRevenue.fill('125000.45');
  await cogs.fill('60000.20');
  await operating.fill('15000.00');
  await expect(page.getByRole('button',{name:'Сохранить ревизию'})).toBeEnabled();

  const saveResponse=page.waitForResponse(response=>
    response.url().includes('/api/v1/drafts') &&
    response.request().method()==='POST');
  await page.getByRole('button',{name:'Сохранить ревизию'}).click();
  const response=await saveResponse;
  expect(response.status()).toBe(200);
  const receipt=await response.json() as {
    instanceId:string;revisionNo:number;contentSha256:string;
  };
  expect(receipt.revisionNo).toBe(1);
  expect(receipt.contentSha256).toMatch(/^[0-9a-f]{64}$/i);
  await expect(page.locator('.editor-footer')).toContainText('Сохранено: ревизия 1');

  const scope=await page.evaluate(()=>{
    const raw=localStorage.getItem('fin-local-scope');
    return raw?JSON.parse(raw) as {organizationId:string}:null;
  });
  expect(scope?.organizationId).toMatch(/^[0-9a-f-]{36}$/i);
  const read=await page.request.get('/api/v1/drafts/'+encodeURIComponent(receipt.instanceId)+
    '?organizationId='+encodeURIComponent(scope!.organizationId));
  expect(read.ok()).toBeTruthy();
  const snapshot=await read.json() as {
    revisionNo:number;values:Record<string,string>;sha256:string;formCode:string;
  };
  expect(snapshot.formCode).toBe('F02');
  expect(snapshot.revisionNo).toBe(1);
  expect(snapshot.sha256).toBe(receipt.contentSha256);
  expect(snapshot.values.netRevenue).toBe('125000.45');
  expect(Number(snapshot.values.cogs)).toBe(60000.2);
  expect(Number(snapshot.values.operatingExpenses)).toBe(15000);

  await page.getByRole('button',{name:'Открыть по UUID (сверить ревизию)'}).click();
  await expect(page.locator('.editor-footer')).toContainText('Открыта ревизия 1');
  await expect(netRevenue).toHaveValue('125000.45');
  await expect(cogs).toHaveValue(snapshot.values.cogs);

  await page.getByRole('button',{name:'К дереву задачи'}).click();
  await expect(page.locator('.kpi-goal-option')
    .filter({hasText:'Подготовка к кредитованию'})).toHaveAttribute('aria-current','step');
  await expect(page.locator('.kpi-indicator-option')
    .filter({hasText:'NET_PROFIT'})).toHaveAttribute('aria-pressed','true');
  await expect(page.locator('.kpi-model-toggle').filter({hasText:'CFO-01'}))
    .toHaveAttribute('aria-expanded','true');
  await expect(page.getByRole('button',{name:'Открыть форму F02 из модели CFO-01'})).toBeVisible();
  await page.screenshot({path:testInfo.outputPath('decision-tree-after-save.png'),fullPage:true});
});

test('FIN UX-R1.1: unsaved edits cannot be discarded by switching tree branches without confirmation',async({page})=>{
  await openF02(page);
  await page.locator('#field-netRevenue').fill('99123');
  await expect(page.getByText('Есть несохранённые изменения.')).toBeVisible();
  await page.getByRole('button',{name:'К дереву задачи'}).click();

  let seen='';
  page.once('dialog',async dialog=>{
    seen=dialog.message();
    await dialog.dismiss();
  });
  await page.getByRole('button',{name:'Открыть форму F03 из модели CFO-01'}).click();
  expect(seen).toContain('Есть несохранённые значения');

  // Switching forms was denied. The financial input remains in the same
  // React editor state even after temporarily viewing the decision tree.
  await page.locator('.nav-line').filter({hasText:'Библиотека форм'}).first().click();
  await expect(page.locator('#field-netRevenue')).toHaveValue('99123');
  await expect(page.locator('.editor-head')).toContainText('F02');
  await expect(page.getByRole('button',{name:'Сохранить ревизию'})).toBeEnabled();
});

test('FIN UX-R1.1: keyboard-only selection, expand/collapse and form opening',async({page})=>{
  await page.goto('/');
  const bank=page.locator('.kpi-goal-option').filter({hasText:'Подготовка к кредитованию'});
  await bank.focus();
  await page.keyboard.press('Enter');
  await expect(bank).toHaveAttribute('aria-current','step');

  const netProfit=page.locator('.kpi-indicator-option').filter({hasText:'NET_PROFIT'});
  await netProfit.focus();
  await page.keyboard.press('Enter');
  await expect(netProfit).toHaveAttribute('aria-pressed','true');

  const model=page.locator('.kpi-model-toggle').filter({hasText:'CFO-01'});
  await model.focus();
  await page.keyboard.press('Space');
  await expect(model).toHaveAttribute('aria-expanded','false');
  await model.focus();
  await page.keyboard.press('Enter');
  await expect(model).toHaveAttribute('aria-expanded','true');

  const source=page.getByRole('button',{name:'Открыть форму F02 из модели CFO-01'});
  await source.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('#field-netRevenue')).toBeVisible();
  await expect(page.locator('.decision-context')).toContainText('CFO-01');
});

test('FIN UX-R1.1: mobile viewport, no horizontal overflow, accessible branch and reduced motion',async({page},testInfo)=>{
  await page.setViewportSize({width:390,height:844});
  await page.emulateMedia({reducedMotion:'reduce'});
  await selectBankFlow(page);
  await expect(page.locator('.kpi-explanation')).toContainText('редакционный материал');
  await expect(page.locator('.kpi-explanation')).toContainText('CATALOG_UNQUALIFIED');
  const bodyWidth=await page.evaluate(()=>document.documentElement.scrollWidth);
  expect(bodyWidth).toBeLessThanOrEqual(391);
  const animation=await page.locator('.kpi-model-children').first()
    .evaluate(el=>getComputedStyle(el).animationName);
  expect(animation).toBe('none');
  await page.screenshot({path:testInfo.outputPath('decision-tree-mobile-390.png'),fullPage:true});
});
