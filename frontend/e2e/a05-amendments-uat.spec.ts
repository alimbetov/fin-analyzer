import {expect,test,type Page,type Locator} from '@playwright/test';

/** Real Chromium + backend + PG16. No mocked financial result. */
async function workspace(page:Page,rate:string):Promise<{
  schedule:Locator;amendment:Locator;scope:{organizationId:string;projectId:string};
  originalScheduleId:string;sourceId:string;parentSha:string
}>{
  await page.goto('/');
  await page.getByPlaceholder('Код или название...').fill('C05');
  await page.locator('.form-list-item').filter({
    has:page.locator('small').filter({hasText:/^C05 ·/})
  }).click();
  await page.getByRole('button',{name:'Создать отчёт'}).click();
  const table=page.getByRole('table',{name:'Табличная форма'});
  await page.getByRole('button',{name:/^Добавить строку \(/}).click();
  const columns=await table.locator('thead th')
    .evaluateAll(nodes=>nodes.map(n=>n.getAttribute('title')));
  for(const [field,value] of Object.entries({
    creditor:'BANK-R12',agreementRef:'AGR-R12',currency:'KZT',
    outstanding:'1000',interestRate:rate,rateType:'FIXED',
    dueAt:'2027-05-01',collateralRef:'NONE'
  })){
    const index=columns.indexOf(field)-1;
    expect(index,field+' must be present in C05').toBeGreaterThanOrEqual(0);
    await table.locator('tbody tr').first().locator('input').nth(index).fill(value);
  }
  const contract=page.getByRole('region',{name:'Кредитный договор A05'});
  await contract.getByLabel('Ключ договора').fill('LOAN-R12');
  await contract.getByLabel('Номер договора').fill('AGR-R12');
  await contract.getByLabel('Кредитор').fill('BANK-R12');
  await contract.getByLabel('Сумма выдачи, KZT').fill('1000');
  await contract.getByLabel('Дата договора').fill('2026-11-01');
  await contract.getByLabel('Дата выдачи').fill('2027-01-01');
  await contract.getByLabel('Начало начисления').fill('2027-01-01');
  await contract.getByLabel('Дата окончания').fill('2027-05-01');
  await contract.getByLabel('Способ погашения').selectOption('EQUAL_PRINCIPAL');
  await contract.getByLabel('База начисления').selectOption('ACT_365_FIXED');
  await contract.getByLabel('Округление').selectOption('HALF_EVEN');
  await contract.getByLabel('День платежа').fill('1');
  await contract.getByLabel('Номинальная ставка, % годовых').fill(rate);
  await contract.getByRole('button',{name:'Сохранить договор и C05 атомарно'}).click();
  await expect(page.locator('.editor-head')).toContainText('Редакция 1');

  const schedule=page.getByRole('region',{name:'График платежей A05'});
  await schedule.getByRole('button',{name:'Рассчитать и сохранить график'}).click();
  await expect(schedule.getByText(/График сохранён · 4 платежей/)).toBeVisible();
  const amendment=page.getByRole('region',{name:'Изменение кредитного графика A05'});
  await expect(amendment).toBeVisible();
  const scope=await page.evaluate(()=>
    JSON.parse(localStorage.getItem('fin-local-scope')!) as {
      organizationId:string;projectId:string
    });
  const source=await page.request.get('/api/v1/drafts/slot?'+new URLSearchParams({
    ...scope,formCode:'C05',periodType:'M',periodCode:'2026-11',
    currency:'KZT',coverage:'PERIOD_ONLY',reportingBasis:'MANAGEMENT',
    dataSpace:'SYNTHETIC'
  }));
  expect(source.status(),await source.text()).toBe(200);
  const body=await source.json() as {revisionId:string};
  const history=await page.request.get('/api/v1/uat/a05/schedules?'+
    new URLSearchParams({...scope,exactSourceRevisionId:body.revisionId}));
  expect(history.status(),await history.text()).toBe(200);
  const items=await history.json() as {
    scheduleRevisionId:string;resultSha256:string
  }[];
  expect(items).toHaveLength(1);
  return {schedule,amendment,scope,originalScheduleId:items[0].scheduleRevisionId,
    parentSha:items[0].resultSha256,sourceId:body.revisionId};
}

test('A05-R1.2 | actual C05 -> immutable prepay REDUCE_TERM and REDUCE_INSTALLMENT',
  async({page})=>{
  test.skip(process.env.VITE_FIN_A05_UAT!=='true','Isolated A05 UAT only');
  const {amendment,scope,originalScheduleId,parentSha,sourceId}=await workspace(page,'0');
  await amendment.getByLabel('Досрочное погашение, KZT').fill('400.00');
  await amendment.getByRole('button',{name:'Рассчитать и сохранить изменение'}).click();
  await expect(amendment.getByText(/Сценарий сохранён/)).toBeVisible();
  await expect(amendment).toContainText('2027-04-01');
  const firstId=await amendment.getByLabel('Редакция изменения A05').inputValue();
  const lineTable=amendment.getByRole('table',{name:'Пересчитанный кредитный график'});
  await expect(lineTable.locator('tbody tr')).toHaveCount(3);
  await expect(lineTable.locator('tbody tr').first()).toContainText('400,00');

  await amendment.getByLabel('Стратегия досрочного погашения')
    .selectOption('REDUCE_INSTALLMENT');
  await amendment.getByRole('button',{name:'Рассчитать и сохранить изменение'}).click();
  await expect(amendment.getByText(/Сценарий сохранён/)).toBeVisible();
  const secondId=await amendment.getByLabel('Редакция изменения A05').inputValue();
  expect(firstId).not.toBe(secondId);
  await expect(lineTable.locator('tbody tr')).toHaveCount(4);
  await expect(amendment).toContainText('2027-05-01');
  await amendment.getByLabel('Редакция изменения A05').selectOption(firstId);
  await expect(lineTable.locator('tbody tr')).toHaveCount(3);
  await expect(amendment).toContainText('2027-04-01');
  const qs=new URLSearchParams({...scope,parentScheduleRevisionId:originalScheduleId});
  const list=await page.request.get('/api/v1/uat/a05/amendments?'+qs);
  expect(list.status(),await list.text()).toBe(200);
  const rows=await list.json() as {amendmentRevisionId:string;resultSha256:string}[];
  expect(rows).toHaveLength(2);
  const read=await page.request.get('/api/v1/uat/a05/amendments/'+firstId+
    '?'+new URLSearchParams(scope));
  expect(read.status(),await read.text()).toBe(200);
  const exact=await read.json() as {exactSourceRevisionId:string;
    parentResultSha256:string;result:{finalPrincipal:string;actualMaturityDate:string};
    financiallyApproved:boolean};
  expect(exact.parentResultSha256).toBe(parentSha);
  expect(exact.exactSourceRevisionId).toBe(sourceId);
  expect(exact.result.actualMaturityDate).toBe('2027-04-01');
  expect(exact.result.finalPrincipal).toBe('0.00');
  expect(exact.financiallyApproved).toBe(false);
});

test('A05-R1.2 | grace capitalization and mid-coupon rate step from same source',
  async({page})=>{
  test.skip(process.env.VITE_FIN_A05_UAT!=='true','Isolated A05 UAT only');
  const {amendment,scope,originalScheduleId}=await workspace(page,'12');
  await amendment.getByLabel('Тип изменения A05').selectOption('GRACE');
  await amendment.getByLabel('Окончание льготного периода')
    .selectOption('2027-03-01');
  await amendment.getByRole('button',{name:'Рассчитать и сохранить изменение'}).click();
  await expect(amendment.getByText(/Сценарий сохранён/)).toBeVisible();
  const lineTable=amendment.getByRole('table',{name:'Пересчитанный кредитный график'});
  await expect(lineTable.locator('tbody tr')).toHaveCount(4);
  const graceId=await amendment.getByLabel('Редакция изменения A05').inputValue();

  await amendment.getByLabel('Режим льготного периода').selectOption('FULL_CAPITALIZE');
  await amendment.getByRole('button',{name:'Рассчитать и сохранить изменение'}).click();
  await expect(amendment.getByText(/Сценарий сохранён/)).toBeVisible();
  const capitalizedId=await amendment.getByLabel('Редакция изменения A05').inputValue();
  expect(capitalizedId).not.toBe(graceId);
  await expect(lineTable.locator('tbody tr').first()).toContainText('0,00');
  const capital=await page.request.get('/api/v1/uat/a05/amendments/'+capitalizedId+
    '?'+new URLSearchParams(scope));
  expect(capital.status(),await capital.text()).toBe(200);
  const body=await capital.json() as {result:{
    totalCapitalizedInterest:string;totalScheduledPrincipal:string;
    lines:{cashInterestDue:string;capitalizedInterest:string}[]
  }};
  expect(body.result.lines[0].cashInterestDue).toBe('0.00');
  expect(Number(body.result.totalCapitalizedInterest)).toBeGreaterThan(0);

  await amendment.getByLabel('Тип изменения A05').selectOption('RATE_STEP');
  await amendment.getByLabel('Дата изменения ставки').fill('2027-01-15');
  await amendment.getByLabel('Новая ставка, % годовых').fill('24');
  await amendment.getByRole('button',{name:'Рассчитать и сохранить изменение'}).click();
  await expect(amendment.getByText(/Сценарий сохранён/)).toBeVisible();
  await expect(lineTable.locator('tbody tr').first()).toContainText('15,78');
  const qs=new URLSearchParams({...scope,parentScheduleRevisionId:originalScheduleId});
  const history=await page.request.get('/api/v1/uat/a05/amendments?'+qs);
  expect(history.status(),await history.text()).toBe(200);
  expect(await history.json()).toHaveLength(3);
});
