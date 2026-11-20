import {expect,test,type Page,type Locator} from '@playwright/test';

/** Real React + Spring Boot + PostgreSQL16, no fake loan schedule fixtures. */
async function createLoan(page:Page,
  method:'ANNUITY'|'EQUAL_PRINCIPAL'|'CUSTOM',rate:string,
  start:string,maturity:string,withFinancingFees=false
):Promise<{panel:Locator;sourceId:string}>{
  await page.goto('/');
  await page.getByPlaceholder('Код или название...').fill('C05');
  await page.locator('.form-list-item').filter({
    has:page.locator('small').filter({hasText:/^C05 ·/})
  }).click();
  await page.getByRole('button',{name:'Создать отчёт'}).click();

  const table=page.getByRole('table',{name:'Табличная форма'});
  await page.getByRole('button',{name:/^Добавить строку \(/}).click();
  const columns=await table.locator('thead th').evaluateAll(
    list=>list.map(item=>item.getAttribute('title')));
  async function field(code:string,value:string){
    const col=columns.indexOf(code)-1;
    expect(col,code+' must exist in C05').toBeGreaterThanOrEqual(0);
    await table.locator('tbody tr').first().locator('input').nth(col).fill(value);
  }
  for(const [code,value] of Object.entries({
    creditor:'BANK-A05',agreementRef:'AGR-A05-R11',currency:'KZT',
    outstanding:'1000',interestRate:rate,rateType:'FIXED',
    dueAt:maturity,collateralRef:'NONE'
  }))await field(code,value);

  const panel=page.getByRole('region',{name:'Кредитный договор A05'});
  await panel.getByLabel('Ключ договора').fill('LOAN-R11');
  await panel.getByLabel('Номер договора').fill('AGR-A05-R11');
  await panel.getByLabel('Кредитор').fill('BANK-A05');
  await panel.getByLabel('Сумма выдачи, KZT').fill('1000');
  await panel.getByLabel('Дата договора').fill('2026-11-01');
  await panel.getByLabel('Дата выдачи').fill(start);
  await panel.getByLabel('Начало начисления').fill(start);
  await panel.getByLabel('Дата окончания').fill(maturity);
  await panel.getByLabel('Способ погашения').selectOption(method);
  await panel.getByLabel('База начисления').selectOption('ACT_360');
  await panel.getByLabel('Округление').selectOption('HALF_EVEN');
  await panel.getByLabel('День платежа').fill('1');
  await panel.getByLabel('Номинальная ставка, % годовых').fill(rate);
  if(withFinancingFees){
    const add=panel.getByRole('button',{name:'Добавить комиссию'});
    await add.click();
    await panel.getByLabel('Комиссия 1 · код').fill('FIN');
    await panel.getByLabel('Комиссия 1 · величина').fill('50');
    await panel.getByLabel('Комиссия 1 · списание').selectOption('FINANCED');
    await add.click();
    await panel.getByLabel('Комиссия 2 · код').fill('HOLD');
    await panel.getByLabel('Комиссия 2 · величина').fill('20');
    await panel.getByLabel('Комиссия 2 · списание').selectOption('WITHHELD');
    await add.click();
    await panel.getByLabel('Комиссия 3 · код').fill('RUN');
    await panel.getByLabel('Комиссия 3 · тип').selectOption('RECURRING_FIXED');
    await panel.getByLabel('Комиссия 3 · величина').fill('5');
    await add.click();
    await panel.getByLabel('Комиссия 4 · код').fill('END');
    await panel.getByLabel('Комиссия 4 · величина').fill('30');
    await panel.getByLabel('Комиссия 4 · списание').selectOption('AT_MATURITY');
  }
  await panel.getByRole('button',{name:'Сохранить договор и C05 атомарно'}).click();
  await expect(page.locator('.editor-head')).toContainText('Редакция 1');

  const scope=await page.evaluate(()=>
    JSON.parse(localStorage.getItem('fin-local-scope')!) as {
      organizationId:string;projectId:string;economicEntityId:string;businessScopeId:string
    });
  const response=await page.request.get('/api/v1/drafts/slot?'+new URLSearchParams({
    ...scope,formCode:'C05',periodType:'M',periodCode:'2026-11',
    currency:'KZT',coverage:'PERIOD_ONLY',reportingBasis:'MANAGEMENT',
    dataSpace:'SYNTHETIC'
  }));
  expect(response.status(),await response.text()).toBe(200);
  const source=await response.json() as {revisionId:string};
  const schedulePanel=page.getByRole('region',{name:'График платежей A05'});
  await expect(schedulePanel).toBeVisible();
  return {panel:schedulePanel,sourceId:source.revisionId};
}

test('A05-R1.1 | dated 2x31 day annuity golden and exact saved SHA history',
  async({page})=>{
  test.skip(process.env.VITE_FIN_A05_UAT!=='true','A05 isolated UAT only');
  const {panel,sourceId}=await createLoan(page,'ANNUITY','12',
    '2026-12-01','2027-02-01');
  await panel.getByRole('button',{name:'Рассчитать и сохранить график'}).click();
  await expect(panel.getByText(/График сохранён · 2 платежей/)).toBeVisible();
  const table=panel.getByRole('table',{name:'Датированный график кредитных платежей'});
  await expect(table.locator('tbody tr')).toHaveCount(2);
  await expect(table.locator('tbody tr').first()).toContainText('10,33');
  await expect(table.locator('tbody tr').first()).toContainText('497,43');
  await expect(table.locator('tbody tr').last()).toContainText('5,19');
  await expect(table.locator('tbody tr').last()).toContainText('502,57');
  await expect(panel).toContainText('1 015,52 KZT');
  await panel.getByText('Как рассчитано · источник, начисления, комиссии и SHA').click();
  await expect(panel).toContainText(sourceId);

  const scope=await page.evaluate(()=>
    JSON.parse(localStorage.getItem('fin-local-scope')!) as {
      organizationId:string;projectId:string
    });
  const list=await page.request.get('/api/v1/uat/a05/schedules?'+new URLSearchParams({
    ...scope,exactSourceRevisionId:sourceId
  }));
  expect(list.status(),await list.text()).toBe(200);
  const saved=await list.json() as {scheduleRevisionId:string;resultSha256:string}[];
  expect(saved).toHaveLength(1);
  expect(saved[0].resultSha256).toMatch(/^[0-9a-f]{64}$/);
  const read=await page.request.get('/api/v1/uat/a05/schedules/'+saved[0].scheduleRevisionId
    +'?'+new URLSearchParams(scope));
  expect(read.status(),await read.text()).toBe(200);
  const verified=await read.json() as {
    exactSourceRevisionId:string;resultSha256:string;
    result:{lines:unknown[];totalScheduledCash:number;finalBalance:number};
    financiallyApproved:boolean;productionAdmitted:boolean
  };
  expect(verified.exactSourceRevisionId).toBe(sourceId);
  expect(verified.resultSha256).toBe(saved[0].resultSha256);
  expect(verified.result.lines).toHaveLength(2);
  expect(verified.result.totalScheduledCash).toBe(1015.52);
  expect(verified.result.finalBalance).toBe(0);
  expect(verified.financiallyApproved).toBe(false);
  expect(verified.productionAdmitted).toBe(false);
});

test('A05-R1.1 | CUSTOM explicit dates, two immutable variants and archived replay',
  async({page})=>{
  test.skip(process.env.VITE_FIN_A05_UAT!=='true','A05 isolated UAT only');
  const {panel,sourceId}=await createLoan(page,'CUSTOM','0',
    '2027-01-01','2027-04-01');
  const custom=panel.getByLabel('Платежи CUSTOM');
  await custom.fill('2027-01-31;100.00\n2027-03-02;0.00\n2027-04-01;900.00');
  await panel.getByRole('button',{name:'Рассчитать и сохранить график'}).click();
  await expect(panel.getByText(/График сохранён · 3 платежей/)).toBeVisible();
  let table=panel.getByRole('table',{name:'Датированный график кредитных платежей'});
  await expect(table.locator('tbody tr')).toHaveCount(3);
  await expect(table.locator('tbody tr').first()).toContainText('100');
  const firstId=await panel.getByLabel('Версия графика A05').inputValue();

  await custom.fill('2027-01-31;200.00\n2027-03-02;0.00\n2027-04-01;800.00');
  await panel.getByRole('button',{name:'Рассчитать и сохранить график'}).click();
  await expect(panel.getByText(/График сохранён · 3 платежей/)).toBeVisible();
  table=panel.getByRole('table',{name:'Датированный график кредитных платежей'});
  await expect(table.locator('tbody tr').first()).toContainText('200');
  const secondId=await panel.getByLabel('Версия графика A05').inputValue();
  expect(secondId).not.toBe(firstId);
  await expect(panel.getByLabel('Версия графика A05').locator('option')).toHaveCount(3);

  await panel.getByLabel('Версия графика A05').selectOption(firstId);
  await expect(table.locator('tbody tr').first()).toContainText('100');
  const scope=await page.evaluate(()=>
    JSON.parse(localStorage.getItem('fin-local-scope')!) as {
      organizationId:string;projectId:string
    });
  const response=await page.request.get('/api/v1/uat/a05/schedules?'+new URLSearchParams({
    ...scope,exactSourceRevisionId:sourceId
  }));
  expect(response.status(),await response.text()).toBe(200);
  const histories=await response.json() as {scheduleRevisionId:string}[];
  expect(histories).toHaveLength(2);
  expect(histories.map(x=>x.scheduleRevisionId).sort()).toEqual([firstId,secondId].sort());
});


test('A05-R1.1 | EQUAL_PRINCIPAL financed, withheld and dated recurring fees',
  async({page})=>{
  test.skip(process.env.VITE_FIN_A05_UAT!=='true','A05 isolated UAT only');
  const {panel,sourceId}=await createLoan(page,'EQUAL_PRINCIPAL','0',
    '2027-01-01','2027-03-01',true);
  await panel.getByRole('button',{name:'Рассчитать и сохранить график'}).click();
  await expect(panel.getByText(/График сохранён · 2 платежей/)).toBeVisible();
  const table=panel.getByRole('table',{name:'Датированный график кредитных платежей'});
  const rows=table.locator('tbody tr');
  await expect(rows).toHaveCount(2);
  await expect(rows.first()).toContainText('2027-02-01');
  await expect(rows.first()).toContainText('525');
  await expect(rows.first()).toContainText('530');
  await expect(rows.last()).toContainText('2027-03-01');
  await expect(rows.last()).toContainText('560');
  await expect(panel).toContainText('1 050 KZT');
  await expect(panel).toContainText('1 090 KZT');
  await panel.getByText('Как рассчитано · источник, начисления, комиссии и SHA').click();
  await expect(panel).toContainText('980 KZT');
  await expect(panel).toContainText(sourceId);

  const scope=await page.evaluate(()=>
    JSON.parse(localStorage.getItem('fin-local-scope')!) as {
      organizationId:string;projectId:string
    });
  const history=await page.request.get('/api/v1/uat/a05/schedules?'+new URLSearchParams({
    ...scope,exactSourceRevisionId:sourceId
  }));
  expect(history.status(),await history.text()).toBe(200);
  const variants=await history.json() as {scheduleRevisionId:string}[];
  expect(variants).toHaveLength(1);
  const response=await page.request.get('/api/v1/uat/a05/schedules/'+
    variants[0].scheduleRevisionId+'?'+new URLSearchParams(scope));
  expect(response.status(),await response.text()).toBe(200);
  const saved=await response.json() as {
    result:{funding:{openingLiability:number;netCashProceeds:number};
      totalScheduledFees:number;totalScheduledCash:number;lines:{
        feeBreakdown:Record<string,number>;principalDue:number
      }[]};
  };
  expect(saved.result.funding.openingLiability).toBe(1050);
  expect(saved.result.funding.netCashProceeds).toBe(980);
  expect(saved.result.totalScheduledFees).toBe(40);
  expect(saved.result.totalScheduledCash).toBe(1090);
  expect(saved.result.lines[0].feeBreakdown).toEqual({RUN:5});
  expect(saved.result.lines[1].feeBreakdown).toEqual({END:30,RUN:5});
});
