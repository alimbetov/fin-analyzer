import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {writeFileSync} from 'node:fs';
import {expect,test,type Page} from '@playwright/test';
import type {CategoryTree,Draft,Field,FormDetail,Receipt,Row} from '../src/api';

test.use({trace:'off',screenshot:'off',video:'off'});

const running = process.env.FIN_QA_DATABASE === 'fin_qa';
const budget = Number(process.env.FIN_QA_STEP_TIMEOUT ?? 4000);
const readRetries = Number(process.env.FIN_QA_READ_RETRIES ?? 1);
const selection = process.env.FIN_QA_FORMS?.split(',').filter(Boolean);
const sha256 = /^[a-f0-9]{64}$/;
type Values = Record<string,string>;
type Contract = {
  formCode:string;formVersionId:string;schemaSha256:string;
  fields:(Field & {metricCode:string|null;contractSha256:string;semanticRole:string;bindingState:string})[];
};
const locales = ['ru','kk','en','zh-Hans'];

type Result = {
  formCode:string;fieldCount:number;definition?:FormDetail;contract?:Contract;categories?:string[];
  locales?:Record<string,string>;render?:boolean;fill?:boolean;save?:boolean;dbVerify?:boolean;reopen?:boolean;revision?:boolean;
  result:'PASS'|'FAIL';reason?:string;diagnostics:string[];
};

function sample(fields:Field[],form:string,row:number,revision:number):Values {
  return Object.fromEntries(fields.map((field,index)=>{
    const seed = createHash('sha256').update(`FIN-QA-R1:${form}:${field.fieldCode}:${row}:${revision}`).digest();
    const quantity = 1000+seed.readUInt16BE(0)+index;
    const suffix = `${row+1}_${index+1}_${revision}`;
    switch(field.fieldType){
      case 'MONEY': return [field.fieldCode,`${quantity}.47`];
      case 'DECIMAL': return [field.fieldCode,`${quantity}.123456789123`];
      case 'INTEGER': return [field.fieldCode,String(quantity)];
      case 'DATE': return [field.fieldCode,`2026-11-${String(1+(index+row+revision)%28).padStart(2,'0')}`];
      case 'BOOLEAN': return [field.fieldCode,String((index+row+revision)%2===0)];
      case 'PERIOD': return [field.fieldCode,`2026-${String(9+revision+row).padStart(2,'0')}`];
      // Candidate contracts currently store these as opaque strings. This
      // exercises round-trip fidelity and provides no dictionary validation.
      case 'REF': case 'CODE': case 'ENUM': case 'TEXT': return [field.fieldCode,`QA_${suffix}`];
      default: throw new Error('Unhandled field type '+field.fieldType);
    }
  }));
}

function dbRead(receipt:Receipt & {revisionId:string}){
  const sql = `SELECT json_build_object(
    'formCode', f.form_code, 'formVersion', v.version_label, 'revisionNo', r.revision_no,
    'instanceId', i.id, 'organizationId', i.organization_id, 'projectId', i.project_id,
    'sha256', r.canonical_content_sha256, 'values', coalesce(r.values_json::json,'{}'::json),
    'rows', coalesce((SELECT json_agg(json_build_object('rowId', x.row_id,
       'rowPosition', x.row_position, 'values', x.values_json::json) ORDER BY x.row_position)
       FROM fin_intake.lib_form_revision_row x WHERE x.revision_id=r.id),'[]'::json))
    FROM fin_intake.lib_form_revision r JOIN fin_intake.lib_form_instance i ON i.id=r.instance_id
    JOIN fin_lib.lib_form_definition f ON f.id=i.form_id
    JOIN fin_lib.lib_form_version v ON v.id=r.form_version_id
    WHERE r.id=:'revision_id'::uuid;`;
  return JSON.parse(execFileSync('psql',[
    '-X','-w','-At','-v','ON_ERROR_STOP=1','-v',`revision_id=${receipt.revisionId}`,'-d','fin_qa'
  ],{encoding:'utf8',input:sql,timeout:budget,env:{...process.env,PGOPTIONS:'-c default_transaction_read_only=on'}}));
}

async function openForm(page:Page,root:string,code:string){
  await page.goto('/');
  await page.getByRole('button',{name:/Категории/}).click({timeout:budget});
  await page.getByRole('button',{name:root,exact:true}).and(page.locator('.group-select')).click({timeout:budget});
  await page.getByPlaceholder('Код или название...').fill(code,{timeout:budget});
  await page.locator('.form-list-item').filter({has:page.locator('small').filter({
    hasText:new RegExp('^'+code+' ·')
  })}).click({timeout:budget});
}

async function save(page:Page){
  const [response] = await Promise.all([
    page.waitForResponse(r=>r.url().endsWith('/api/v1/drafts')&&r.request().method()==='POST',{timeout:budget}),
    page.getByRole('button',{name:'Сохранить',exact:true}).click({timeout:budget})
  ]);
  expect(response.status(),await response.text()).toBe(200);
  const receipt:Receipt & {revisionId:string} = await response.json();
  expect(receipt.contentSha256).toMatch(sha256);
  await expect(page.locator('.editor-footer')).toContainText('Сохранено: ревизия '+receipt.revisionNo,{timeout:budget});
  return {receipt,submitted:response.request().postDataJSON()};
}

test('FIN QA R1 | live catalog, browser and PostgreSQL qualification',async({browser,request},info)=>{
  test.skip(!running,'Requires FIN_QA_DATABASE=fin_qa and an isolated running stack');
  test.setTimeout(Number(process.env.FIN_QA_TIMEOUT ?? 600000));
  async function get(path:string){
    const response = await request.get('/api/v1'+path,{timeout:budget,maxRetries:readRetries});
    expect(response.status(),path).toBe(200);
    return response.json();
  }
  const catalog: {formCode:string;version:string}[] = await get('/forms');
  const tree:CategoryTree = await get('/catalog/category-tree?locale=ru');
  expect(catalog).toHaveLength(166);
  expect(new Set(catalog.map(f=>f.formCode)).size).toBe(catalog.length);
  const chosen = catalog.filter(f=>!selection||selection.includes(f.formCode));
  expect(chosen.length).toBe(selection?.length??catalog.length);
  const results:Result[] = [];
  for(const entry of chosen){
    const formCode = entry.formCode;
    const result:Result = {formCode,fieldCount:0,result:'FAIL',diagnostics:[]};
    results.push(result);
    const context = await browser.newContext({baseURL:'http://127.0.0.1:5173'});
    await context.tracing.start({screenshots:true,snapshots:true});
    const page = await context.newPage();
    page.on('pageerror',e=>result.diagnostics.push(e.message));
    page.on('console',message=>{if(message.type()==='error')result.diagnostics.push(message.text());});
    page.on('response',response=>{
      const path = new URL(response.url()).pathname;
      if(response.status()>=400||path==='/api/v1/drafts')
        result.diagnostics.push(`${response.request().method()} ${path} ${response.status()} correlationId=${response.request().headers()['x-fin-uat-token']??'-'}`);
    });
    try {
      const detail:FormDetail = await get('/catalog/forms/'+formCode+'?locale=ru');
      const contract:Contract = await get('/catalog/contracts/forms/'+formCode+'?locale=ru');
      result.definition = detail;
      result.contract = contract;
      result.fieldCount = detail.fields.length;
      expect(detail.form.formCode).toBe(formCode);
      expect(detail.form.version).toBe(entry.version);
      expect(contract.formCode).toBe(formCode);
      expect(contract.schemaSha256).toMatch(sha256);
      expect(detail.fields.length).toBeGreaterThan(0);
      expect(new Set(detail.fields.map(f=>f.fieldCode)).size).toBe(detail.fields.length);
      expect(contract.fields).toHaveLength(detail.fields.length);
      for(const field of detail.fields){
        const binding = contract.fields.find(f=>f.fieldCode===field.fieldCode);
        expect(binding).toMatchObject({fieldType:field.fieldType,inputMode:field.inputMode,required:field.required});
        expect(binding?.contractSha256).toMatch(sha256);
        if(binding?.semanticRole==='SOURCE_FIELD')expect(binding.metricCode).toBeTruthy();
      }
      const translations = await Promise.all(locales.map(async locale=>{
        const localized:FormDetail = locale==='ru'?detail:await get('/catalog/forms/'+formCode+'?locale='+locale);
        expect(localized.form).toMatchObject({formCode,version:entry.version});
        expect(localized.form.title.trim()).not.toBe('');
        expect(localized.fields.map(f=>f.fieldCode)).toEqual(detail.fields.map(f=>f.fieldCode));
        return [locale,localized.form.title];
      }));
      result.locales = Object.fromEntries(translations);
      const links = tree.links.filter(link=>link.formCode===formCode);
      result.categories = links.map(link=>link.categoryCode);
      const primary = tree.categories.find(c=>c.code===links.find(l=>l.role==='PRIMARY')?.categoryCode);
      const root = primary?.parentCode ? tree.categories.find(c=>c.code===primary.parentCode) : primary;
      expect(root).toBeDefined();
      const fields = detail.fields.filter(f=>f.inputMode==='MANUAL').sort((a,b)=>a.displayOrder-b.displayOrder);
      await openForm(page,root!.title,formCode);
      await page.getByRole('button',{name:'Создать отчёт'}).click({timeout:budget});
      await expect(page.locator('.editor-head .eyebrow')).toHaveText(formCode+' · '+entry.version,{timeout:budget});
      const rowset = detail.form.contentMode==='ROWSET';
      if(rowset)await expect(page.getByRole('table',{name:'Табличная форма'}).locator('thead th[title]')).toHaveCount(fields.length,{timeout:budget});
      else await expect(page.locator('.editor-body input[id^="field-"]')).toHaveCount(fields.length,{timeout:budget});
      result.render = true;
      const values = rowset?{}:sample(fields,formCode,0,1);
      const rowValues = rowset?[sample(fields,formCode,0,1),sample(fields,formCode,1,1)]:[];
      const cell = (row:number,column:number)=>rowset?
        page.getByRole('table',{name:'Табличная форма'}).locator('tbody tr').nth(row).locator('input').nth(column):
        page.locator(`[id="field-${fields[column].fieldCode}"]`);
      for(let row=0;row<(rowset?2:1);row++){
        if(rowset)await page.getByRole('button',{name:/Добавить строку/}).click({timeout:budget});
        for(const [i,field] of fields.entries())
          await cell(row,i).fill((rowset?rowValues[row]:values)[field.fieldCode],{timeout:budget});
      }
      result.fill = true;
      const first = await save(page);
      expect(first.receipt.revisionNo).toBe(1);
      result.save = true;
      const firstDb = dbRead(first.receipt);
      const expected = {
        formCode,formVersion:entry.version,revisionNo:1,values,instanceId:first.receipt.instanceId,
        organizationId:first.submitted.organizationId, projectId:first.submitted.projectId,
        sha256:first.receipt.contentSha256,
        rows:(first.submitted.rows as Row[]).map((row,i)=>({...row,values:rowValues[i]}))
      };
      expect(firstDb).toEqual(expected);
      result.dbVerify = true;
      const path = '/drafts/'+first.receipt.instanceId;
      const query = '?organizationId='+first.submitted.organizationId;
      expect(await get(path+query)).toMatchObject(expected);
      await openForm(page,root!.title,formCode);
      await page.getByRole('button',{name:/Открыть отчёт · KZT/}).click({timeout:budget});
      await expect(page.locator('.editor-footer')).toContainText('Открыта ревизия 1',{timeout:budget});
      for(let row=0;row<(rowset?2:1);row++)for(const [i,field] of fields.entries())
        await expect(cell(row,i)).toHaveValue((rowset?rowValues[row]:values)[field.fieldCode],{timeout:budget});
      result.reopen = true;
      const field = fields[0];
      const next = sample(fields,formCode,0,2)[field.fieldCode];
      await cell(0,0).fill(next,{timeout:budget});
      await expect(page.getByText('Есть несохранённые изменения.')).toBeVisible({timeout:budget});
      const second = await save(page);
      expect(second.receipt.revisionNo).toBe(2);
      const expectedNext = {...expected,revisionNo:2,sha256:second.receipt.contentSha256};
      if(rowset)expectedNext.rows = expected.rows.map((row,i)=>i===0?{...row,values:{...row.values,[field.fieldCode]:next}}:row);
      else expectedNext.values = {...values,[field.fieldCode]:next};
      expect(dbRead(second.receipt)).toEqual(expectedNext);
      expect(dbRead(first.receipt)).toEqual(firstDb);
      const history:Draft = await get(path+'/revisions/1'+query);
      expect(history).toMatchObject(expected);
      expect(await get(path+'/revisions/2'+query)).toMatchObject(expectedNext);
      expect(result.diagnostics.filter(d=>!d.startsWith('POST /api/v1/drafts 200'))).toEqual([]);
      result.revision = true;
      result.result = 'PASS';
    } catch(e){
      result.reason = e instanceof Error?e.message:String(e);
      await page.screenshot({path:info.outputPath(formCode+'.png'),timeout:budget}).catch(()=>{});
      await context.tracing.stop({path:info.outputPath(formCode+'-trace.zip')});
    } finally {
      if(result.result!=='FAIL')await context.tracing.stop();
      await context.close();
      writeFileSync(info.outputPath('qualification.json'),JSON.stringify({browser:browser.version(),results},null,2));
      console.log([formCode,result.fieldCount,result.render,result.fill,result.save,result.dbVerify,result.reopen,result.revision,result.result,result.reason?.split('\n')[0]].join(' | '));
    }
  }
  expect(results.filter(r=>r.result==='FAIL').map(r=>[r.formCode,r.reason])).toEqual([]);
});
