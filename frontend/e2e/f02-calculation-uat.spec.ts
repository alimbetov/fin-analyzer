import {expect,test} from '@playwright/test';

test('FIN DC-R2.3 | real F02 save → SHA-reverified calculate → edit → compare',async({page,request})=>{
  test.skip(process.env.VITE_FIN_INTAKE_UAT!=='true',
    'Disabled by default: run only in isolated FIN_INTAKE_UAT browser workflow');

  await page.goto('/');
  await page.getByPlaceholder('Код или название...').fill('F02');
  await page.locator('.form-list-item')
    .filter({has:page.locator('small').filter({hasText:/^F02 ·/})}).click();
  await page.getByRole('button',{name:'Создать отчёт'}).click();

  const panel=page.getByRole('region',{name:'UAT-расчёт F02'});
  const calculate=panel.getByRole('button',{name:'Рассчитать F02'});
  await expect(calculate).toBeDisabled();

  await page.locator('#field-netRevenue').fill('200');
  await page.locator('#field-cogs').fill('100');
  await page.locator('#field-operatingExpenses').fill('50');
  await page.locator('.editor-head').getByRole('button',{name:'Сохранить',exact:true}).click();

  await expect(calculate).toBeEnabled();
  const scope=await page.evaluate(()=>
    JSON.parse(localStorage.getItem('fin-local-scope')!) as {
      organizationId:string;projectId:string;economicEntityId:string;businessScopeId:string
    });
  const saved=await request.get('/api/v1/drafts/slot?'+new URLSearchParams({
    ...scope,formCode:'F02',periodType:'M',periodCode:'2026-11',
    currency:'KZT',coverage:'PERIOD_ONLY',reportingBasis:'MANAGEMENT',dataSpace:'SYNTHETIC'
  }));
  expect(saved.status(),await saved.text()).toBe(200);
  const first=await saved.json() as {
    revisionId:string;sha256:string;revisionNo:number;instanceId:string
  };
  expect(first.revisionId).toMatch(/^[a-f0-9-]{36}$/i);
  expect(first.revisionNo).toBe(1);

  await calculate.click();
  await expect(panel.getByText('100.00 KZT')).toBeVisible();
  await expect(panel.getByText('0.50000000 (доля)')).toBeVisible();
  await expect(panel.getByText('50.00 KZT')).toBeVisible();
  await panel.getByText('Как получено · исходная ревизия и контроль SHA').click();
  await expect(panel.getByText(first.sha256)).toBeVisible();
  await expect(panel.getByText(first.revisionId)).toBeVisible();

  await page.locator('#field-netRevenue').fill('300');
  await expect(calculate).toBeDisabled();
  await expect(panel.getByText('100.00 KZT')).toHaveCount(0);
  await page.locator('.editor-head').getByRole('button',{name:'Сохранить',exact:true}).click();
  await expect(calculate).toBeEnabled();
  await calculate.click();
  await expect(panel.getByText('200.00 KZT')).toBeVisible();
  await expect(panel.getByText('0.66666667 (доля)')).toBeVisible();
  await expect(panel.getByText('150.00 KZT')).toBeVisible();

  const old=await request.post('/api/v1/uat/calculations/f02/preview',{data:{
    organizationId:scope.organizationId,
    projectId:scope.projectId,
    exactRevisionId:first.revisionId
  }});
  expect(old.status(),await old.text()).toBe(200);
  const historic=await old.json() as {
    sourceContentSha256:string;outputs:Record<string,{value:string}>;
    financiallyApproved:boolean;productionAdmitted:boolean
  };
  expect(historic.sourceContentSha256).toBe(first.sha256);
  expect(historic.outputs.grossProfit.value).toBe('100.00');
  expect(historic.financiallyApproved).toBe(false);
  expect(historic.productionAdmitted).toBe(false);
});
