import {randomUUID} from 'node:crypto';
import {expect,test} from '@playwright/test';

test('form opens a scoped period tree and resumes and saves a report',async({page,request})=>{
  await page.goto('/');
  const list=page.getByRole('region',{name:'Сохранённые отчёты'});
  async function selectF02(){
    await page.getByPlaceholder('Код или название...').fill('F02');
    await page.getByRole('button',{name:/^Отчёт о прибылях и убытках F02/}).click();
    await expect(list.getByRole('heading',{name:'Сохранённые отчёты'})).toBeVisible();
  }
  await selectF02();
  await expect(list).toContainText('ещё нет сохранённых отчётов');
  const scope=await page.evaluate(()=>JSON.parse(localStorage.getItem('fin-local-scope')!));
  async function saveReport(periodCode:string,projectId=scope.projectId){
    const response=await request.post('/api/v1/drafts',{
      headers:{'Idempotency-Key':randomUUID()},
      data:{...scope,projectId,formCode:'F02',periodType:'M',periodCode,
        currency:'KZT',coverage:'PERIOD_ONLY',reportingBasis:'MANAGEMENT',dataSpace:'SYNTHETIC',
        expectedRevision:0,values:{netRevenue:'1900.05',cogs:'300.12',operatingExpenses:'170.91'},
        rows:[],createdBy:'qa',changeReason:'Synthetic archive test'}
    });
    expect(response.status(),await response.text()).toBe(200);
  }
  await saveReport('2025-12');
  await saveReport('2026-03');
  await saveReport('2026-11',randomUUID());

  await list.getByRole('button',{name:'Создать отчёт'}).click();
  await page.getByLabel('Учётный период').fill('2026-10');
  await page.locator('#field-netRevenue').fill('2300.47');
  await page.locator('#field-cogs').fill('400.12');
  await page.locator('#field-operatingExpenses').fill('160.91');
  const save=page.getByRole('button',{name:'Сохранить',exact:true});
  await save.click();
  await expect(page.locator('.editor-footer')).toContainText('Сохранено: ревизия 1');
  await page.reload();
  await selectF02();
  await expect(list.locator('.saved-year > summary strong')).toHaveText(['2026','2025']);
  await expect(list.locator('.saved-year[open] .saved-period > summary strong')).toHaveText(['2026-10','2026-03']);
  await expect(list).not.toContainText('2026-11');
  await list.getByRole('combobox',{name:'Фильтр по году'}).selectOption('2025');
  await expect(list.locator('.saved-year > summary strong')).toHaveText(['2025']);
  // Switching forms must never carry over a filter that hides the new form's reports.
  await page.getByPlaceholder('Код или название...').fill('D01');
  await page.locator('.form-list-item').filter({hasText:'D01 ·'}).click();
  await page.getByPlaceholder('Код или название...').fill('F02');
  await page.locator('.form-list-item').filter({hasText:'F02 ·'}).click();
  await expect(list.getByRole('combobox',{name:'Фильтр по году'})).toHaveValue('ALL');
  await expect(list.locator('.saved-year > summary strong')).toHaveText(['2026','2025']);
  await list.getByRole('textbox',{name:'Поиск по сохранённым отчётам'}).fill('2026-10');
  await expect(list.locator('.saved-year > summary strong')).toHaveText(['2026']);
  await expect(list.locator('.saved-period > summary strong')).toHaveText(['2026-10']);
  await list.getByRole('button',{name:'Сбросить'}).click();
  await expect(list.locator('.saved-year > summary strong')).toHaveText(['2026','2025']);
  const october=list.locator('.saved-period').filter({hasText:'2026-10'});
  await october.getByRole('button',{name:/Открыть отчёт/}).click();
  await expect(page.locator('.catalog-panel')).toHaveCount(0);
  await expect(page.getByRole('progressbar',{name:'Заполненные поля'})).toBeVisible();
  await expect(page.getByRole('navigation',{name:'Разделы формы'})).toBeVisible();
  await expect(page.locator('.form-section')).not.toHaveCount(0);
  await expect(page.locator('.form-section').first()).toHaveAttribute('open','');
  await page.locator('.form-section').first().locator('summary').click();
  await expect(page.locator('.form-section').first()).not.toHaveAttribute('open','');
  await page.getByRole('navigation',{name:'Разделы формы'}).getByRole('button').first().click();
  await expect(page.locator('.form-section').first()).toHaveAttribute('open','');
  await expect(page.locator('#field-netRevenue')).toHaveValue('2300.47');
  await page.locator('#field-netRevenue').fill('2500.49');
  await save.click();
  await expect(page.locator('.editor-footer')).toContainText('Сохранено: ревизия 2');
  const historyTrigger=page.getByRole('button',{name:/История · 2/});
  await historyTrigger.click();
  const history=page.getByRole('dialog',{name:'История редакций'});
  await expect(history).toBeVisible();
  await expect(history.getByRole('button',{name:'Закрыть историю'})).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(history.getByRole('button',{name:/Редакция 1/})).toBeFocused();
  await history.getByRole('button',{name:/Редакция 1/}).click();
  await expect(history).toContainText('2300.47');
  await page.keyboard.press('Escape');
  await expect(history).toHaveCount(0);
  await expect(historyTrigger).toBeFocused();
  await expect(page.locator('#field-netRevenue')).toHaveValue('2500.49');
  await page.getByRole('button',{name:'Вернуться к списку отчётов'}).click();
  await expect(october).toContainText('Редакция 2');
});
