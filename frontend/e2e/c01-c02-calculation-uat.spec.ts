import {expect,test,type Locator,type Page} from '@playwright/test';

/**
 * DC-R2.5: real React + PostgreSQL saved ROWSET => SHA-reverified UAT
 * calculation, then a second revision and historic replay. No mocked data.
 */
type FormCode='C01'|'C02';

async function openRowset(page:Page,code:FormCode):Promise<Locator>{
  await page.goto('/');
  await page.getByPlaceholder('Код или название...').fill(code);
  await page.locator('.form-list-item')
    .filter({has:page.locator('small').filter({hasText:new RegExp('^'+code+' ·')})}).click();
  await page.getByRole('button',{name:'Создать отчёт'}).click();
  const table=page.getByRole('table',{name:'Табличная форма'});
  await expect(table).toBeVisible();
  return table;
}

async function fieldIndex(table:Locator,code:string):Promise<number>{
  const titles=await table.locator('thead th').evaluateAll(
    nodes=>nodes.map(node=>node.getAttribute('title')));
  const index=titles.indexOf(code)-1; // The first column is the row number.
  expect(index,'Required field '+code+' must exist').toBeGreaterThanOrEqual(0);
  return index;
}

async function populate(table:Locator,rowIndex:number,values:Record<string,string>){
  const row=table.locator('tbody tr').nth(rowIndex);
  for(const [code,value] of Object.entries(values)){
    const index=await fieldIndex(table,code);
    await row.locator('input').nth(index).fill(value);
  }
}

async function appendRow(page:Page,table:Locator,values:Record<string,string>){
  await page.getByRole('button',{name:/^Добавить строку \(/}).click();
  const count=await table.locator('tbody tr').count();
  await populate(table,count-1,values);
}

test('FIN DC-R2.5 | C01 ROWSET -> gross/allowance/overdue -> historic comparison',async({page,request})=>{
  test.skip(process.env.VITE_FIN_INTAKE_UAT!=='true',
    'Only isolated opt-in FIN_INTAKE_UAT browser tests');
  const table=await openRowset(page,'C01');
  const panel=page.getByRole('region',{name:'UAT-расчёт C01'});
  const calculate=panel.getByRole('button',{name:'Рассчитать C01'});
  await expect(calculate).toBeDisabled();

  await appendRow(page,table,{counterparty:'CLIENT_1',gross:'100',
    allowance:'10',overdue:'20',dueAt:'2026-11-10'});
  await appendRow(page,table,{counterparty:'CLIENT_2',gross:'50',
    allowance:'5',overdue:'10',dueAt:'2026-11-05'});
  await page.locator('.editor-head').getByRole('button',{name:'Сохранить',exact:true}).click();
  await expect(calculate).toBeEnabled();

  const scope=await page.evaluate(()=>
    JSON.parse(localStorage.getItem('fin-local-scope')!) as {organizationId:string;projectId:string});
  const slot=await request.get('/api/v1/drafts/slot?'+new URLSearchParams({
    ...scope,formCode:'C01',periodType:'M',periodCode:'2026-11',
    currency:'KZT',coverage:'PERIOD_ONLY',reportingBasis:'MANAGEMENT',dataSpace:'SYNTHETIC'
  }));
  expect(slot.status(),await slot.text()).toBe(200);
  const original=await slot.json() as {
    revisionId:string;revisionNo:number;sha256:string;rows:unknown[]
  };
  expect(original.revisionNo).toBe(1);
  expect(original.rows).toHaveLength(2);

  await calculate.click();
  await expect(panel.getByText('135 KZT')).toBeVisible();
  await expect(panel.getByText('30 KZT')).toBeVisible();
  await expect(panel.getByText('0.2 (доля)')).toBeVisible();
  await panel.getByText('Как получено · исходная ревизия и контроль SHA').click();
  await expect(panel.getByText(original.revisionId)).toBeVisible();
  await expect(panel.getByText(original.sha256)).toBeVisible();

  await table.locator('tbody tr').first().locator('input')
    .nth(await fieldIndex(table,'gross')).fill('200');
  await expect(calculate).toBeDisabled();
  await expect(panel.getByText('135 KZT')).toHaveCount(0);
  await page.locator('.editor-head').getByRole('button',{name:'Сохранить',exact:true}).click();
  await calculate.click();
  await expect(panel.getByText('235 KZT')).toBeVisible();
  await panel.getByRole('button',{name:'Рассчитать выбранную редакцию'}).click();
  const comparison=panel.getByRole('table');
  await expect(comparison).toContainText('135 KZT');
  await expect(comparison).toContainText('235 KZT');
  await expect(comparison).toContainText('Чистая дебиторская задолженность');
});

test('FIN DC-R2.5 | C02 ROWSET -> outstanding and optional overdue',async({page})=>{
  test.skip(process.env.VITE_FIN_INTAKE_UAT!=='true',
    'Only isolated opt-in FIN_INTAKE_UAT browser tests');
  const table=await openRowset(page,'C02');
  const panel=page.getByRole('region',{name:'UAT-расчёт C02'});
  const calculate=panel.getByRole('button',{name:'Рассчитать C02'});

  await appendRow(page,table,{counterparty:'SUPPLIER_1',
    outstanding:'60',overdue:'10',dueAt:'2026-11-02'});
  await appendRow(page,table,{counterparty:'SUPPLIER_2',
    outstanding:'40',overdue:'10',dueAt:'2026-11-03'});
  await page.locator('.editor-head').getByRole('button',{name:'Сохранить',exact:true}).click();
  await expect(calculate).toBeEnabled();
  await calculate.click();
  await expect(panel.getByText('100 KZT')).toBeVisible();
  await expect(panel.getByText('20 KZT')).toBeVisible();
  await expect(panel.getByText('0.2 (доля)')).toBeVisible();
  await panel.getByText('Как получено · исходная ревизия и контроль SHA').click();
  await expect(panel.getByText('2026-11-30')).toBeVisible();
  await expect(panel.getByText('DECLARED_OVERDUE_NOT_RECONSTRUCTED_FROM_PAYMENT_EVENTS')).toBeVisible();
});
