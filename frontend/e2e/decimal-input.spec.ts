import {expect,test} from '@playwright/test';

test('ROWSET accepts an unambiguous localized decimal at full precision',async({page})=>{
  await page.goto('/');
  await page.getByRole('button',{name:/Библиотека форм/}).click();
  await page.getByPlaceholder('Код или название...').fill('DF-AST-01');
  await page.locator('.form-list-item').filter({hasText:'DF-AST-01 ·'}).click();
  await page.getByRole('button',{name:'Создать отчёт'}).click();
  await page.getByRole('button',{name:/Добавить строку/}).click();
  const capacity = page.getByRole('table',{name:'Табличная форма'}).locator('tbody input').last();
  await capacity.fill('9\u202f007\u202f199\u202f254\u202f740\u202f993,123456789123');

  const [response] = await Promise.all([
    page.waitForResponse(r=>r.url().endsWith('/api/v1/drafts')&&r.request().method()==='POST'),
    page.getByRole('button',{name:'Сохранить',exact:true}).click()
  ]);
  expect(response.status(),await response.text()).toBe(200);
  await expect(page.locator('.editor-footer')).toContainText('Сохранено: ревизия 1');
  await expect(capacity).toHaveValue('9007199254740993.123456789123');
});
