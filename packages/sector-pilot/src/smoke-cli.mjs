import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { fiveQuestionSmoke } from './smoke.mjs';
import { PILOT_FORMS } from './index.mjs';
import { SMOKE_CASES, syntheticRow } from '../tests/smoke-fixtures.mjs';

const formPath=fileURLToPath(new URL('../../../backend/src/main/resources/fin/form-compatibility.tsv',import.meta.url));
const extPath=fileURLToPath(new URL('../../../backend/src/main/resources/fin/form-extension-sheets-v2.tsv',import.meta.url));
const parse=(path)=>readFileSync(path,'utf8').trimEnd().split(/\r?\n/).map(x=>x.split('\t'));
const [formHeader,...formRows]=parse(formPath);
const [extensionHeader,...extensionRows]=parse(extPath);
if(formHeader[0]!=='formCode'||formHeader[7]!=='qualificationStatus' ||
    extensionHeader[0]!=='sheetCode' || formRows.length!==166 || extensionRows.length!==30)
  throw new Error('SMOKE_CATALOG_SCHEMA_DRIFT');
const fixtures=new Map(SMOKE_CASES.map(x=>[x.form,x]));
if(fixtures.size!==9 || new Set(formRows.map(x=>x[0])).size!==166 ||
    new Set(extensionRows.map(x=>x[0])).size!==30)
  throw new Error('SMOKE_CATALOG_DUPLICATES');
const results=formRows.map(row=>{
  const [formCode,,,,,,,qualificationStatus]=row;
  const fixture=fixtures.get(formCode);
  if(!fixture) return {
    formCode, catalogMethodStatus:qualificationStatus,
    sectorSmokeStatus:'NOT_EXECUTABLE_IN_SECTOR_KERNEL',
    questions:Object.fromEntries(['what','why','seriousness','action','effect']
      .map(name=>[name,'NOT_TESTED']))
  };
  const report=fiveQuestionSmoke(syntheticRow(fixture),fixture.overrides);
  if(report.questions.what.metrics[fixture.metric].value!==fixture.before ||
      report.questions.effect.scenario.impacts[fixture.metric].delta!==fixture.delta)
    throw new Error('SMOKE_GOLDEN_MISMATCH:'+formCode);
  return {
    formCode, catalogMethodStatus:qualificationStatus,
    sectorSmokeStatus:'EXECUTED_SYNTHETIC_ONLY',metricCode:fixture.metric,
    value:fixture.before,scenarioDelta:fixture.delta,
    questions:Object.fromEntries(Object.entries(report.questions)
      .map(([name,result])=>[name,result.status]))
  };
});
if(PILOT_FORMS.some(code=>!results.some(x=>x.formCode===code && x.sectorSmokeStatus==='EXECUTED_SYNTHETIC_ONLY')))
  throw new Error('SMOKE_PILOT_INCOMPLETE');
const payload={
  schemaVersion:'FIN_FORM_SMOKE_MATRIX_V1',
  scope:'SYNTHETIC_ONLY_NOT_PRODUCTION',
  formCount:results.length,
  sectorFormsCalculated:SMOKE_CASES.length,
  fullFiveQuestionMethodologyAccepted:0,
  extensionSheetCount:extensionRows.length,
  forms:results,
  extensionSheets:extensionRows.map(row=>({
    sheetCode:row[0],parentFormCode:row[2],
    status:'CONTRACT_ONLY_IN_THIS_SMOKE'
  }))
};
process.stdout.write(JSON.stringify(payload,null,2)+'\n');
