import {writeFileSync,mkdirSync} from 'node:fs';
import {expect,test} from '@playwright/test';
import type {FormDetail} from '../src/api';

/**
 * FIN-FORMS-QA-R2.D: one REAL Chromium pass across every versioned form.
 * No mocked HTTP, no financial approval; evidence upload remains a known
 * product gap. A JSON matrix is validated by the release workflow.
 */
test('FIN-FORMS-QA-R2.D | all 166 real React forms render and accept edits',async({page,request})=>{
  test.setTimeout(15*60*1000);
  const response=await request.get('/api/v1/forms');
  expect(response.status()).toBe(200);
  const catalog:{formCode:string}[]=await response.json();
  expect(catalog).toHaveLength(166);
  const codes=catalog.map(x=>x.formCode).sort();
  expect(new Set(codes).size).toBe(166);
  const output='test-results/fin-forms-r2-166-browser.json';
  mkdirSync('test-results',{recursive:true});
  type Result={formCode:string;mode:string;fieldCount:number;captureMode:string;
    rendered:boolean;edited:boolean;status:'PASS'|'FAIL';reason?:string};
  const results:Result[]=[];
  for(const code of codes){
    let result:Result={formCode:code,mode:'UNKNOWN',fieldCount:0,
      captureMode:'UNKNOWN',rendered:false,edited:false,status:'FAIL'};
    try{
      const detailed=await request.get('/api/v1/catalog/forms/'+encodeURIComponent(code)+'?locale=ru');
      expect(detailed.status(),code).toBe(200);
      const detail:FormDetail=await detailed.json();
      expect(detail.form.formCode).toBe(code);
      const fields=detail.fields.filter(f=>f.inputMode==='MANUAL');
      expect(fields.length).toBeGreaterThan(0);
      result={...result,mode:detail.form.contentMode,
        captureMode:detail.form.captureMode,fieldCount:fields.length};
      await page.goto('/');
      const search=page.getByPlaceholder('Код или название...');
      await expect(search).toBeVisible();
      await search.fill(code);
      const selected=page.locator('.form-list-item').filter({
        has:page.locator('small').filter({hasText:new RegExp('^'+code+' ·')})
      });
      await expect(selected).toHaveCount(1);
      await selected.click();
      const create=page.getByRole('button',{name:'Создать отчёт'});
      await expect(create).toBeEnabled();
      await create.click();
      await expect(page.locator('.editor-head .eyebrow')).toContainText(code+' · '+detail.form.version);
      if(detail.form.contentMode==='ROWSET'){
        await expect(page.getByRole('table',{name:'Табличная форма'})
          .locator('thead th[title]')).toHaveCount(fields.length);
        await page.getByRole('button',{name:/Добавить строку/}).click();
        const first=page.getByRole('table',{name:'Табличная форма'})
          .locator('tbody tr').first().locator('input').first();
        await first.fill('12');
        await expect(first).toHaveValue('12');
      }else{
        await expect(page.locator('.editor-body input[id^="field-"]'))
          .toHaveCount(fields.length);
        const first=page.locator('#field-'+fields[0].fieldCode);
        await first.fill('12');
        await expect(first).toHaveValue('12');
        if(detail.form.contentMode==='EVIDENCE'){
          const uploader=page.locator('#evidence-upload');
          await expect(uploader).toBeEnabled();
          await uploader.setInputFiles({name:'qa-evidence.pdf',mimeType:'application/pdf',
            buffer:Buffer.from('%PDF-1.4\\nFIN synthetic evidence')});
          await expect(page.getByText('К сохранению:')).toBeVisible();
        }
      }
      result.rendered=true;
      await expect(page.getByText('Есть несохранённые изменения.')).toBeVisible();
      await expect(page.getByRole('button',{name:'Сохранить',exact:true})).toBeEnabled();
      result.edited=true;
      result.status='PASS';
    }catch(error){
      result.reason=error instanceof Error?error.message:String(error);
    }finally{
      results.push(result);
      writeFileSync(output,JSON.stringify({contract:'FIN-FORMS-QA-R2-D-V1',results},null,2));
      console.log('[FIN-QA-R2] '+code+' '+result.mode+' '+result.status
        +(result.reason?' '+result.reason.split('\n')[0]:''));
    }
  }
  expect(results.filter(x=>x.status!=='PASS').map(x=>[x.formCode,x.reason])).toEqual([]);
});
