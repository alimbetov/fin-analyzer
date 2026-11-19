import test from 'node:test';
import assert from 'node:assert/strict';
import {formVisual,formVisuals,sectionTitle} from './formVisuals.ts';

test('icons are reused across domains, not generated per form',()=>{
  const supported=['ACCOUNTING_REGISTERS','EVIDENCE_AND_DOCUMENTS','ASSETS','CASH_AND_DEBT',
    'COSTS','BUSINESS_CONTEXT','CUSTOMERS','GOVERNANCE','HUMAN_RESOURCES','MARKETING',
    'OPERATIONS','PROJECTS','SALES','INVENTORY','FINANCIAL_REPORTING','BUDGET_AND_FORECAST',
    'SECTOR_A','SECTOR_U'];
  for(const domain of supported){
    const value=formVisual(domain);
    assert.equal(typeof value.icon,'object'); // Lucide forwardRef component
    assert.ok(['blue','teal','violet','amber'].includes(value.tone));
  }
  assert.ok(new Set(Object.values(formVisuals).map(v=>v.icon)).size<=15);
});

test('main section has explicit captions in four supported UI locales',()=>{
  assert.equal(sectionTitle('MAIN','ru'),'Основные показатели');
  assert.equal(sectionTitle('MAIN','kk'),'Негізгі көрсеткіштер');
  assert.equal(sectionTitle('MAIN','en'),'Main indicators');
  assert.equal(sectionTitle('MAIN','zh-Hans'),'主要指标');
  assert.equal(sectionTitle('UNREVIEWED_CUSTOM_SECTION','ru'),'UNREVIEWED_CUSTOM_SECTION');
});
