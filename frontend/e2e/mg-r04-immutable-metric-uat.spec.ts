import {expect,test} from '@playwright/test';

/** Real F02 save -> metric target -> DAG selection -> existing kernel ->
 * immutable PostgreSQL plan/result; zero mocks or copied numeric fixtures. */
test('FIN MG-R0.4 | exact F02 metric, method SHA, replay and source revision',
  async({page,request})=>{
  test.skip(process.env.VITE_FIN_MG_R04_UAT!=='true',
    'MG-R0.4 is disabled except for isolated synthetic UAT');

  await page.goto('/');
  await page.getByPlaceholder('Код или название...').fill('F02');
  await page.locator('.form-list-item')
    .filter({has:page.locator('small').filter({hasText:/^F02 ·/})}).click();
  await page.getByRole('button',{name:'Создать отчёт'}).click();
  const mg=page.getByRole('region',{name:'Исполнение MG-R0.4'});
  await expect(mg).toBeVisible();
  const execute=mg.getByRole('button',{name:'Создать immutable план и рассчитать'});
  await expect(execute).toBeDisabled();

  await page.locator('#field-netRevenue').fill('200');
  await page.locator('#field-cogs').fill('100');
  await page.locator('#field-operatingExpenses').fill('50');
  await page.locator('.editor-head').getByRole('button',
    {name:'Сохранить',exact:true}).click();
  const scope=await page.evaluate(()=>
    JSON.parse(localStorage.getItem('fin-local-scope')!) as {
      organizationId:string;projectId:string;economicEntityId:string;businessScopeId:string
    });
  const firstResponse=await request.get('/api/v1/drafts/slot?'+new URLSearchParams({
    ...scope,formCode:'F02',periodType:'M',periodCode:'2026-11',
    currency:'KZT',coverage:'PERIOD_ONLY',reportingBasis:'MANAGEMENT',
    dataSpace:'SYNTHETIC'
  }));
  expect(firstResponse.status(),await firstResponse.text()).toBe(200);
  const first=await firstResponse.json() as {
    revisionId:string;sha256:string;revisionNo:number;instanceId:string
  };
  expect(first.revisionNo).toBe(1);

  const methods=mg.getByLabel('Методика MG-R0.4');
  await expect(methods.locator('option')).toHaveCount(2);
  await expect(execute).toBeDisabled();
  await methods.selectOption({index:1});
  await expect(execute).toBeEnabled();
  await execute.click();
  await expect(mg.getByText('Сохранён immutable UAT-план с точным SHA.'))
    .toBeVisible();
  await expect(mg.locator('.mg-r04-output strong')).toContainText(
    'F02.grossProfit · 100.00 KZT');

  const plan1=await mg.getByLabel('UUID плана MG-R0.4').inputValue();
  expect(plan1).toMatch(/^[0-9a-f-]{36}$/i);
  await mg.getByText('Explain · методика, DAG, исходная ревизия, SHA и результат')
    .click();
  await expect(mg).toContainText(first.revisionId);
  await expect(mg).toContainText(first.sha256);
  const read=await request.get('/api/v1/uat/metric-graph/plans/'+plan1+'?'+
    new URLSearchParams({organizationId:scope.organizationId,projectId:scope.projectId}));
  expect(read.status(),await read.text()).toBe(200);
  const old=await read.json() as {
    planSha256:string;resultSha256:string;sourceSha256:string;
    valueText:string;valueState:string;financiallyApproved:boolean;
    productionAdmitted:boolean
  };
  expect(old.sourceSha256).toBe(first.sha256);
  expect(old.valueText).toBe('100.00');
  expect(old.valueState).toBe('PRESENT');
  expect(old.planSha256).toMatch(/^[0-9a-f]{64}$/);
  expect(old.resultSha256).toMatch(/^[0-9a-f]{64}$/);
  expect(old.financiallyApproved).toBe(false);
  expect(old.productionAdmitted).toBe(false);

  await mg.getByRole('button',{name:'Повторно открыть план'}).click();
  await expect(mg.getByText(
    'Неизменяемый план повторно проверен по source/method/result SHA.')).toBeVisible();

  await page.locator('#field-netRevenue').fill('300');
  await expect(execute).toBeDisabled();
  await expect(mg.locator('.mg-r04-output')).toHaveCount(0);
  await page.locator('.editor-head').getByRole('button',
    {name:'Сохранить',exact:true}).click();
  await expect(execute).toBeEnabled();
  await execute.click();
  await expect(mg.locator('.mg-r04-output strong')).toContainText(
    'F02.grossProfit · 200.00 KZT');
  const plan2=await mg.getByLabel('UUID плана MG-R0.4').inputValue();
  expect(plan2).not.toBe(plan1);
  const immutablePast=await request.get(
    '/api/v1/uat/metric-graph/plans/'+plan1+'?'+
    new URLSearchParams({organizationId:scope.organizationId,projectId:scope.projectId}));
  expect(immutablePast.status(),await immutablePast.text()).toBe(200);
  const prior=await immutablePast.json() as {
    planSha256:string;resultSha256:string;valueText:string
  };
  expect(prior.planSha256).toBe(old.planSha256);
  expect(prior.resultSha256).toBe(old.resultSha256);
  expect(prior.valueText).toBe('100.00');
});
