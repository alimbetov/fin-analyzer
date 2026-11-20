import {expect,test,type Locator} from '@playwright/test';

/** Real Chromium + PostgreSQL16, no mocked data or finance results. */
test('A05-R1.0 | C05 row + rate/calendar + two fees -> atomic revision -> fee-only revision replay',
  async({page,request})=>{
  test.skip(process.env.VITE_FIN_A05_UAT!=='true',
    'A05 UI is disabled outside the isolated C05 loan UAT workflow.');

  await page.goto('/');
  await page.getByPlaceholder('Код или название...').fill('C05');
  await page.locator('.form-list-item')
    .filter({has:page.locator('small').filter({hasText:/^C05 ·/})}).click();
  await page.getByRole('button',{name:'Создать отчёт'}).click();

  const table=page.getByRole('table',{name:'Табличная форма'});
  const panel=page.getByRole('region',{name:'Кредитный договор A05'});
  await expect(table).toBeVisible();
  await expect(panel).toBeVisible();
  await expect(page.locator('.editor-head').getByRole('button',
    {name:'Сохранить',exact:true})).toBeDisabled();
  await page.getByRole('button',{name:/^Добавить строку \(/}).click();

  async function fillRow(fieldCode:string,value:string){
    const th=table.locator('thead th');
    const titles=await th.evaluateAll(nodes=>nodes.map(n=>n.getAttribute('title')));
    const index=titles.indexOf(fieldCode)-1;
    expect(index,fieldCode+' must be in C05 current schema').toBeGreaterThanOrEqual(0);
    await table.locator('tbody tr').first().locator('input').nth(index).fill(value);
  }

  for(const [field,value] of Object.entries({
    creditor:'BANK-01',agreementRef:'AGR-2026-01',currency:'KZT',
    outstanding:'100000',interestRate:'18',rateType:'FIXED',
    dueAt:'2027-11-01',collateralRef:'NONE'
  }))await fillRow(field,value);
  await panel.getByLabel('Ключ договора').fill('LOAN-001');
  await panel.getByLabel('Номер договора').fill('AGR-2026-01');
  await panel.getByLabel('Кредитор').fill('BANK-01');
  await panel.getByLabel('Сумма выдачи, KZT').fill('100000.00');
  await panel.getByLabel('Дата договора').fill('2026-10-20');
  await panel.getByLabel('Дата выдачи').fill('2026-11-01');
  await panel.getByLabel('Начало начисления').fill('2026-11-01');
  await panel.getByLabel('Дата окончания').fill('2027-11-01');
  await panel.getByLabel('Способ погашения').selectOption('ANNUITY');
  await panel.getByLabel('База начисления').selectOption('ACT_365_FIXED');
  await panel.getByLabel('Округление').selectOption('HALF_EVEN');
  await panel.getByLabel('Номинальная ставка, % годовых').fill('18');
  await panel.getByRole('button',{name:'Добавить комиссию'}).click();
  await panel.getByLabel('Комиссия 1 · код').fill('SETUP');
  await panel.getByLabel('Комиссия 1 · величина').fill('1000');
  await panel.getByRole('button',{name:'Добавить комиссию'}).click();
  await panel.getByLabel('Комиссия 2 · код').fill('SERVICE');
  await panel.getByLabel('Комиссия 2 · тип').selectOption('RECURRING_FIXED');
  await panel.getByLabel('Комиссия 2 · величина').fill('100');

  const save=panel.getByRole('button',{name:'Сохранить договор и C05 атомарно'});
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.locator('.editor-head')).toContainText('Редакция 1');
  const scope=await page.evaluate(()=>
    JSON.parse(localStorage.getItem('fin-local-scope')!) as {
      organizationId:string;projectId:string;economicEntityId:string;businessScopeId:string
    });

  async function slot():Promise<{
    instanceId:string;revisionId:string;revisionNo:number;
    sha256:string;rows:unknown[]
  }>{
    const response=await request.get('/api/v1/drafts/slot?'+new URLSearchParams({
      ...scope,formCode:'C05',periodType:'M',periodCode:'2026-11',
      currency:'KZT',coverage:'PERIOD_ONLY',reportingBasis:'MANAGEMENT',
      dataSpace:'SYNTHETIC'
    }));
    expect(response.status(),await response.text()).toBe(200);
    return response.json();
  }
  async function loan(revisionId:string):Promise<{
    receipt:{sourceContentSha256:string;termsSha256:string;revisionNo:number;
      financiallyApproved:boolean;productionAdmitted:boolean};
    fees:{feeCode:string;amountOrRate:number}[];sourceIntegrityState:string
  }>{
    const response=await request.get('/api/v1/uat/a05/c05/revisions/'+revisionId+'?'+
      new URLSearchParams({organizationId:scope.organizationId,projectId:scope.projectId}));
    expect(response.status(),await response.text()).toBe(200);
    return response.json();
  }

  const first=await slot();
  expect(first.revisionNo).toBe(1);
  expect(first.rows).toHaveLength(1);
  const typed1=await loan(first.revisionId);
  expect(typed1.receipt.sourceContentSha256).toBe(first.sha256);
  expect(typed1.sourceIntegrityState).toBe('SOURCE_SHA_REVERIFIED');
  expect(typed1.fees).toHaveLength(2);
  expect(typed1.receipt.financiallyApproved).toBe(false);
  expect(typed1.receipt.productionAdmitted).toBe(false);

  // Reloaded fees are sorted by stable feeCode, not their insertion index.
  // Verify identity first: editing by display row number would alter SERVICE.
  await expect(panel.getByLabel('Комиссия 2 · код')).toHaveValue('SETUP');
  await panel.getByLabel('Комиссия 2 · величина').fill('1100');
  await expect(save).toBeEnabled();
  await save.click();
  await expect(page.locator('.editor-head')).toContainText('Редакция 2');
  const second=await slot();
  expect(second.revisionNo).toBe(2);
  expect(second.revisionId).not.toBe(first.revisionId);
  expect(second.sha256).toBe(first.sha256); // same C05 form row, changed A05 typed fee
  const typed2=await loan(second.revisionId);
  expect(typed2.receipt.termsSha256).not.toBe(typed1.receipt.termsSha256);
  expect(Number(typed1.fees.find(f=>f.feeCode==='SETUP')!.amountOrRate)).toBe(1000);
  expect(Number(typed2.fees.find(f=>f.feeCode==='SETUP')!.amountOrRate)).toBe(1100);
  await expect(page.locator('.editor-head')).toContainText('Редакция 2');
});
