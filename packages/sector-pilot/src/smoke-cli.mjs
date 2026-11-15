import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { fiveQuestionSmoke } from './smoke.mjs';
import { PILOT_FORMS } from './index.mjs';
import { SMOKE_CASES, syntheticRow } from '../tests/smoke-fixtures.mjs';

const formPath=fileURLToPath(new URL('../../../backend/src/main/resources/fin/form-compatibility.tsv',import.meta.url));
const extPath=fileURLToPath(new URL('../../../backend/src/main/resources/fin/form-extension-sheets-v2.tsv',import.meta.url));
const javaPath=fileURLToPath(new URL('../../../backend/src/test/resources/simulation/scenario-five-question-coverage.tsv',import.meta.url));
const parse=(path)=>readFileSync(path,'utf8').trimEnd().split(/\r?\n/).map(x=>x.split('\t'));
const [formHeader,...formRows]=parse(formPath);
const [extensionHeader,...extensionRows]=parse(extPath);
const [javaHeader,...javaRows]=parse(javaPath);
if(formHeader[0]!=='formCode'||formHeader[7]!=='qualificationStatus' ||
    extensionHeader[0]!=='sheetCode' || formRows.length!==166 || extensionRows.length!==30)
  throw new Error('SMOKE_CATALOG_SCHEMA_DRIFT');
const fixtures=new Map(SMOKE_CASES.map(x=>[x.form,x]));
if(fixtures.size!==9 || new Set(formRows.map(x=>x[0])).size!==166 ||
    new Set(extensionRows.map(x=>x[0])).size!==30)
  throw new Error('SMOKE_CATALOG_DUPLICATES');
if(javaHeader.join('\\t')!=='method\\tscenarioCode\\tmetricCode\\tsourceForms\\textensionSheets\\tstatus' ||
  javaRows.length!==16 || new Set(javaRows.map(x=>x[0])).size!==14 ||
  javaRows.some(x=>x.length!==6 || x[5]!=='SYNTHETIC_TEST_DEFINED'))
  throw new Error('SMOKE_JAVA_MANIFEST_INVALID');
const knownForms=new Set(formRows.map(x=>x[0]));
const knownSheets=new Set(extensionRows.map(x=>x[0]));
const methodsByForm=new Map(), methodsBySheet=new Map();
for(const [method,,,forms,sheets] of javaRows) {
  for(const form of forms.split(';')) {
    if(!knownForms.has(form))throw new Error('SMOKE_UNKNOWN_JAVA_FORM:'+form);
    if(!methodsByForm.has(form))methodsByForm.set(form,[]);
    methodsByForm.get(form).push(method);
  }
  if(sheets!=='-')for(const sheet of sheets.split(';')) {
    if(!knownSheets.has(sheet))throw new Error('SMOKE_UNKNOWN_JAVA_SHEET:'+sheet);
    if(!methodsBySheet.has(sheet))methodsBySheet.set(sheet,[]);
    methodsBySheet.get(sheet).push(method);
  }
}
const sectorForms=formRows.map(x=>x[0]).filter(code=>/^SF-[A-U]0[1-3]$/.test(code));
if(sectorForms.length!==63 || sectorForms.filter(code=>!fixtures.has(code)).length!==54)
  throw new Error('SMOKE_SECTOR_COVERAGE_DRIFT');
const results=formRows.map(row=>{
  const [formCode,,formKind,,,,,qualificationStatus]=row;
  const fixture=fixtures.get(formCode);
  const evidenceRole=formKind==='DOCUMENT' ? 'EVIDENCE_ONLY_NO_NUMERIC_KPI'
    : formKind==='EXTERNAL_TAX' ? 'EXTERNAL_POLICY_EVIDENCE_NO_NUMERIC_KPI'
    : 'POTENTIAL_STRUCTURED_NUMERIC_INPUT';
  const javaScenarioMethods=methodsByForm.get(formCode)??[];
  if(!fixture) return {
    javaScenarioMethods, javaScenarioStatus:javaScenarioMethods.length
      ? 'GOLDEN_TEST_DEFINED_CI_MUST_VERIFY' : 'NOT_DEFINED',
    formCode, formKind, evidenceRole, catalogMethodStatus:qualificationStatus,
    sectorSmokeStatus:'NOT_EXECUTABLE_IN_SECTOR_KERNEL',
    questions:Object.fromEntries(['what','why','seriousness','action','effect']
      .map(name=>[name,'NOT_TESTED']))
  };
  const report=fiveQuestionSmoke(syntheticRow(fixture),fixture.overrides);
  if(report.questions.what.metrics[fixture.metric].value!==fixture.before ||
      report.questions.effect.scenario.impacts[fixture.metric].delta!==fixture.delta)
    throw new Error('SMOKE_GOLDEN_MISMATCH:'+formCode);
  return {
    formCode, formKind, evidenceRole, catalogMethodStatus:qualificationStatus,
    javaScenarioMethods, javaScenarioStatus:javaScenarioMethods.length
      ? 'GOLDEN_TEST_DEFINED_CI_MUST_VERIFY' : 'NOT_DEFINED',
    sectorSmokeStatus:'EXECUTED_SYNTHETIC_ONLY',metricCode:fixture.metric,
    value:fixture.before,scenarioDelta:fixture.delta,
    questions:Object.fromEntries(Object.entries(report.questions)
      .map(([name,result])=>[name,result.status]))
  };
});
if(PILOT_FORMS.some(code=>!results.some(x=>x.formCode===code && x.sectorSmokeStatus==='EXECUTED_SYNTHETIC_ONLY')))
  throw new Error('SMOKE_PILOT_INCOMPLETE');
const payload={
  schemaVersion:'FIN_FORM_SMOKE_MATRIX_V2',
  scope:'SYNTHETIC_ONLY_NOT_PRODUCTION',
  formCount:results.length,
  sectorFormsCalculated:SMOKE_CASES.length,
  sectorFormsNotImplemented:54,
  javaScenarioMethodsDefined:javaRows.length,
  javaSourceFormsInGoldenTests:methodsByForm.size,
  javaExtensionSheetsInGoldenTests:methodsBySheet.size,
  fullFiveQuestionMethodologyAccepted:0,
  extensionSheetCount:extensionRows.length,
  forms:results,
  extensionSheets:extensionRows.map(row=>({
    sheetCode:row[0],parentFormCode:row[2],
    javaScenarioMethods:methodsBySheet.get(row[0])??[],
    status:methodsBySheet.has(row[0]) ? 'GOLDEN_TEST_DEFINED_CI_MUST_VERIFY' : 'CONTRACT_TEST_DEFINED_ONLY'
  }))
};
process.stdout.write(JSON.stringify(payload,null,2)+'\n');
