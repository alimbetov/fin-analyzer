import {expect,test} from '@playwright/test';

/**
 * Real HTTP + PostgreSQL proof: the first request commits, but the browser
 * never sees its response. Retrying must reuse the exact Idempotency-Key.
 */
test('lost save response replays one immutable revision instead of blocking the editor',async({page,request})=>{
  await page.goto('/');
  await page.getByPlaceholder('Код или название...').fill('F02');
  await page.locator('.form-list-item').filter({has:page.locator('small').filter({hasText:/^F02 ·/})}).click();
  await page.getByRole('button',{name:'Создать отчёт'}).click();
  await page.locator('#field-netRevenue').fill('15730.47');
  await page.locator('#field-cogs').fill('950.12');
  await page.locator('#field-operatingExpenses').fill('370.91');

  const scope=await page.evaluate(()=>JSON.parse(localStorage.getItem('fin-local-scope')!));
  const keys:string[]=[];
  let dropFirstResponse=true;
  await page.route('**/api/v1/drafts',async route=>{
    keys.push(route.request().headers()['idempotency-key']??'');
    if(dropFirstResponse){
      dropFirstResponse=false;
      const committed=await route.fetch(); // Real backend + PostgreSQL write
      expect(committed.status(),await committed.text()).toBe(200);
      await route.abort('failed'); // Client did not receive the commit receipt
      return;
    }
    await route.continue();
  });

  await page.locator('.editor-head').getByRole('button',{name:'Сохранить',exact:true}).click();
  await expect(page.locator('.editor-body .warning[role="alert"]'))
    .toContainText('Ответ на сохранение не подтверждён');
  await expect(page.locator('#field-netRevenue')).toBeDisabled();
  const retry=page.locator('.editor-head').getByRole('button',{name:'Повторить сохранение'});
  await expect(retry).toBeEnabled();
  await retry.click();

  await expect(page.locator('.editor-footer')).toContainText('Сохранено: ревизия 1');
  expect(keys).toHaveLength(2);
  expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/i);
  expect(keys[1]).toBe(keys[0]);
  await expect(page.locator('#field-netRevenue')).toBeEnabled();
  await expect(page.getByRole('button',{name:/История · 1/})).toBeVisible();

  const saved=await request.get('/api/v1/drafts/slot?'+new URLSearchParams({
    ...scope,formCode:'F02',periodType:'M',periodCode:'2026-11',
    currency:'KZT',coverage:'PERIOD_ONLY',reportingBasis:'MANAGEMENT',dataSpace:'SYNTHETIC'
  }));
  expect(saved.status(),await saved.text()).toBe(200);
  expect(await saved.json()).toMatchObject({
    revisionNo:1,values:{netRevenue:'15730.47',cogs:'950.12',operatingExpenses:'370.91'}
  });
});
