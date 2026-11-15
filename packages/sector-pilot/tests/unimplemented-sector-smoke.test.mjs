import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { evaluateSectorForms, PILOT_FORMS } from '../src/index.mjs';

const catalog=fileURLToPath(new URL('../../../backend/src/main/resources/fin/form-compatibility.tsv',import.meta.url));
const rows=readFileSync(catalog,'utf8').trim().split(/\r?\n/).slice(1).map(line=>line.split('\t'));
const sector=rows.filter(item=>/^SF-[A-U]0[1-3]$/.test(item[0]));
const pilot=new Set(PILOT_FORMS);
const absent=sector.filter(item=>!pilot.has(item[0]));

test('sector catalog covers 21×3 forms: nine executable and 54 deliberately blocked',()=>{
  assert.equal(sector.length,63);
  assert.equal(pilot.size,9);
  assert.equal(absent.length,54);
  assert.ok(absent.every(item=>item[7]==='DESIGN_ONLY'));
  assert.ok(sector.filter(item=>pilot.has(item[0])).every(item=>item[7]==='PILOT_UNQUALIFIED'));
});
for(const form of absent){
  test(form[0]+' rejects a fake pilot computation instead of inventing KPI evidence',()=>{
    assert.throws(()=>evaluateSectorForms([{
      formCode:form[0],businessId:'synthetic-business',dimensionId:'synthetic-item',
      currency:'KZT',period:'2026-01',sourceId:'source-'+form[0],
      revisionId:'revision-'+form[0],approved:true,values:{}
    }]),/unsupported formCode/);
  });
}
