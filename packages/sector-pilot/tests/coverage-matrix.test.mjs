import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

test('every one of 166 registered tables and 30 extension sheets has an honest smoke disposition',()=>{
  const cli=fileURLToPath(new URL('../src/smoke-cli.mjs',import.meta.url));
  const payload=JSON.parse(execFileSync(process.execPath,[cli],{encoding:'utf8'}));
  assert.equal(payload.formCount,166);
  assert.equal(payload.extensionSheetCount,30);
  assert.equal(payload.sectorFormsCalculated,9);
  assert.equal(payload.fullFiveQuestionMethodologyAccepted,0);
  assert.equal(payload.forms.filter(x=>x.sectorSmokeStatus==='EXECUTED_SYNTHETIC_ONLY').length,9);
  assert.equal(payload.forms.filter(x=>x.sectorSmokeStatus==='NOT_EXECUTABLE_IN_SECTOR_KERNEL').length,157);
  assert.ok(payload.forms.filter(x=>x.sectorSmokeStatus==='NOT_EXECUTABLE_IN_SECTOR_KERNEL')
    .every(x=>Object.values(x.questions).every(state=>state==='NOT_TESTED')));
  assert.ok(payload.forms.filter(x=>x.sectorSmokeStatus==='EXECUTED_SYNTHETIC_ONLY')
    .every(x=>x.questions.what==='CALCULATED'&&x.questions.why==='HYPOTHESIS_ONLY'
      &&x.questions.seriousness==='NOT_ASSESSED'
      &&x.questions.effect==='SIMULATED_ONLY'));
  assert.equal(new Set(payload.forms.map(x=>x.formCode)).size,166);
  assert.equal(new Set(payload.extensionSheets.map(x=>x.sheetCode)).size,30);
});
