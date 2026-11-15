import test from 'node:test';
import assert from 'node:assert/strict';
import { FORM_FIELDS, PILOT_FORMS, evaluateSectorForms } from '../src/index.mjs';
import { diagnoseSectorOutput } from '../src/diagnostics.mjs';
import { fiveQuestionSmoke, simulateSectorWhatIf } from '../src/smoke.mjs';
import { SMOKE_CASES, syntheticRow } from './smoke-fixtures.mjs';

test('all nine sector forms have distinct golden smoke data and five explicit answer gates', () => {
  assert.deepEqual(SMOKE_CASES.map(f=>f.form).sort(),[...PILOT_FORMS].sort());
  for (const item of SMOKE_CASES) {
    const report = fiveQuestionSmoke(syntheticRow(item),item.overrides);
    assert.equal(report.evidenceSpace,'SYNTHETIC');
    assert.equal(report.methodology,'PILOT_UNQUALIFIED');
    assert.equal(report.questions.what.status,'CALCULATED',item.form);
    assert.equal(report.questions.what.metrics[item.metric].value,item.before,item.form);
    assert.equal(report.questions.why.status,'HYPOTHESIS_ONLY',item.form);
    assert.equal(report.questions.seriousness.status,'NOT_ASSESSED',item.form);
    assert.equal(report.questions.seriousness.reason,'NO_QUALIFIED_MATERIALITY_POLICY');
    assert.equal(report.questions.effect.status,'SIMULATED_ONLY',item.form);
    const effect=report.questions.effect.scenario.impacts[item.metric];
    assert.deepEqual([effect.before,effect.after,effect.delta],
      [item.before,item.after,item.delta],item.form);
    assert.equal(report.questions.effect.scenario.sourceRevision,'revision-'+item.form);
    assert.equal(report.questions.effect.scenario.mode,'SYNTHETIC_ONLY');
    const observations=report.findings.filter(f=>f.kind==='OBSERVATION');
    assert.deepEqual(observations.map(f=>f.ruleCode),item.observation?[item.observation]:[],item.form);
    assert.equal(report.questions.action.status,item.observation?'REVIEW_REQUIRED':'NO_QUALIFIED_TRIGGER');
    assert.equal(report.questions.action.humanApprovalRequired,true);
    for (const finding of report.findings) {
      assert.equal(finding.methodQualification,'PILOT_UNQUALIFIED');
      assert.deepEqual(finding.sourceRevisions,['revision-'+item.form]);
      assert.equal(finding.businessId,'synthetic-company');
    }
  }
});
test('missing one input blocks diagnostics and the scenario for every one of nine forms',()=>{
  for(const fixture of SMOKE_CASES) {
    const row=syntheticRow(fixture);
    const first=FORM_FIELDS[fixture.form][0];
    row.values={...row.values,[first]:null};
    const report=fiveQuestionSmoke(row,fixture.overrides);
    assert.equal(report.questions.what.status,'BLOCKED',fixture.form);
    assert.equal(report.questions.effect.status,'BLOCKED',fixture.form);
    assert.equal(report.findings.length,1,fixture.form);
    assert.equal(report.findings[0].kind,'MISSING_EVIDENCE',fixture.form);
    assert.ok(report.findings[0].missingFields.includes(first),fixture.form);
  }
});
test('zero denominator must be distinct from missing for eight ratio tables',()=>{
  const denominators={
    'SF-G01':'netRevenue','SF-G02':'avgStockValue','SF-G03':'receivedQty',
    'SF-M01':'availableHours','SF-S01':'completedJobs','SF-C01':'unitsStarted',
    'SF-C02':'goodUnits','SF-C03':'plannedHours'
  };
  for(const fixture of SMOKE_CASES.filter(x=>denominators[x.form])) {
    const row=syntheticRow(fixture);
    row.values={...row.values,[denominators[fixture.form]]:'0'};
    // Some operational constraints require both numerator and denominator to be zero.
    if(fixture.form==='SF-C01') {row.values.goodUnits='0';row.values.wasteUnits='0';row.values.reworkedUnits='0';}
    if(fixture.form==='SF-C03') {row.values.runHours='0';row.values.downtimeHours='0';}
    if(fixture.form==='SF-M01') row.values.billableHours='0';
    const result=evaluateSectorForms([row]).find(x=>x.windowType==='MONTH');
    assert.equal(result.status,'CALCULATED',fixture.form);
    const metricCode=fixture.form==='SF-G01'?'SKU_GROSS_MARGIN_PCT':fixture.metric;
    assert.equal(result.metrics[metricCode].status,'NOT_COMPUTABLE',fixture.form);
    const findings=diagnoseSectorOutput(result);
    assert.ok(findings.some(f=>f.kind==='NOT_COMPUTABLE'&&f.metricCode===metricCode),fixture.form);
    assert.ok(!findings.some(f=>f.kind==='OBSERVATION'&&f.metricCode===metricCode),fixture.form);
  }
});
test('synthetic scenarios reject unknown fields, invalid values and incomplete baseline',()=>{
  const fixture=SMOKE_CASES[0],row=syntheticRow(fixture);
  assert.throws(()=>simulateSectorWhatIf(row,{}),/INVALID_WHAT_IF_OVERRIDES/);
  assert.throws(()=>simulateSectorWhatIf(row,{hacker:'1'}),/INVALID_WHAT_IF_OVERRIDES/);
  assert.throws(()=>simulateSectorWhatIf(row,{cogs:'-99'}));
  assert.throws(()=>simulateSectorWhatIf({...row,values:{...row.values,cogs:null}},
    {cogs:'50'}),/WHAT_IF_REQUIRES_COMPLETE_BASELINE/);
});
test('diagnostic threshold is never inferred from a percentage without independent review',()=>{
  const fixture=SMOKE_CASES.find(x=>x.form==='SF-M01');
  const report=fiveQuestionSmoke(syntheticRow(fixture),fixture.overrides);
  assert.equal(report.questions.seriousness.status,'NOT_ASSESSED');
  assert.equal(report.questions.action.status,'NO_QUALIFIED_TRIGGER');
  assert.deepEqual(report.questions.why.candidateRules,[]);
});
