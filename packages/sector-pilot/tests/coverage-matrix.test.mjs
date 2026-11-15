import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

test('every one of 166 registered tables and 30 extension sheets has an honest smoke disposition',()=>{
  const cli=fileURLToPath(new URL('../src/smoke-cli.mjs',import.meta.url));
  const payload=JSON.parse(execFileSync(process.execPath,[cli],{encoding:'utf8'}));
  assert.equal(payload.formCount,166);
  assert.equal(payload.extensionSheetCount,30);
  assert.equal(payload.schemaVersion,'FIN_FORM_SMOKE_MATRIX_V2');
  assert.equal(payload.sectorFormsCalculated,9);
  assert.equal(payload.sectorFormsNotImplemented,54);
  assert.equal(payload.javaScenarioMethodsDefined,16);
  assert.equal(payload.javaSourceFormsInGoldenTests,16);
  assert.equal(payload.javaExtensionSheetsInGoldenTests,10);
  assert.equal(payload.fullFiveQuestionMethodologyAccepted,0);
  const documents=payload.forms.filter(x=>x.formKind==='DOCUMENT');
  const external=payload.forms.filter(x=>x.formKind==='EXTERNAL_TAX');
  assert.equal(documents.length,8);
  assert.equal(external.length,4);
  assert.ok(documents.every(x=>x.evidenceRole==='EVIDENCE_ONLY_NO_NUMERIC_KPI'
    && x.javaScenarioMethods.length===0 && x.sectorSmokeStatus==='NOT_EXECUTABLE_IN_SECTOR_KERNEL'));
  assert.ok(external.every(x=>x.evidenceRole==='EXTERNAL_POLICY_EVIDENCE_NO_NUMERIC_KPI'
    && x.javaScenarioMethods.length===0 && x.sectorSmokeStatus==='NOT_EXECUTABLE_IN_SECTOR_KERNEL'));
  assert.equal(payload.forms.filter(x=>x.evidenceRole==='POTENTIAL_STRUCTURED_NUMERIC_INPUT').length,154);
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
  assert.equal(payload.extensionSheets.filter(x=>x.status==='GOLDEN_TEST_DEFINED_CI_MUST_VERIFY').length,10);
  assert.equal(payload.extensionSheets.filter(x=>x.status==='CONTRACT_TEST_DEFINED_ONLY').length,20);
  assert.equal(payload.forms.filter(x=>x.javaScenarioMethods.length>0).length,16);
  assert.ok(payload.forms.every(x=>x.javaScenarioMethods.length>0
    ? x.javaScenarioStatus==='GOLDEN_TEST_DEFINED_CI_MUST_VERIFY'
    : x.javaScenarioStatus==='NOT_DEFINED'));
});
