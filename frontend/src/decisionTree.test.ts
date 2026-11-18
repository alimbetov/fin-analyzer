import test from 'node:test';
import assert from 'node:assert/strict';
import {buildDecisionTree,formContext,goalMetricCodes} from './decisionTree.ts';
import type {IndicatorGoal,IndicatorModel} from './api.ts';

const m=(code:string,inputs:string[],outputs:string[]):IndicatorModel=>({
  code,pack:'INVESTMENT',title:'Candidate',titleReviewStatus:'SOURCE_TITLE_EN_UNREVIEWED',
  qualificationStatus:'CALC_PILOT',inputFormCodes:inputs,outputMetricCodes:outputs
});
const goal:IndicatorGoal={
  code:'INVESTMENT_APPRAISAL',title:'Оценка инвестиционного проекта',
  translationStatus:'SOURCE_CANDIDATE_NOT_S2',audience:'INVESTOR',deliverable:'INVESTMENT_EVALUATION',
  version:'1',qualificationStatus:'CANDIDATE_NOT_S2',
  models:[
    m('INV-05',['P07','P03','P05','P03'],['PROJECT_NPV']),
    m('INV-04',['P07','C05'],['DISCOUNT_RATE']),
    m('INV-09',['P07','P08'],['INCREMENTAL_NPV'])
  ],
  forms:[
    {formCode:'P03',role:'CORE',priority:1},
    {formCode:'P05',role:'CORE',priority:2},
    {formCode:'P07',role:'CORE',priority:3},
    {formCode:'D03',role:'EVIDENCE',priority:5},
    {formCode:'P08',role:'SUPPORTING',priority:4}
  ]
};

test('tree separates exact model-input candidates from goal-level advice',()=>{
  const tree=buildDecisionTree(goal,'PROJECT_NPV');
  assert.equal(tree.branches.length,1);
  assert.equal(tree.branches[0].model.code,'INV-05');
  assert.deepEqual(tree.branches[0].inputs.map(f=>f.formCode),['P07','P03','P05']);
  assert.equal(tree.branches[0].inputs[0].goalRole,'CORE');
  assert.deepEqual(tree.recommendations.map(f=>f.formCode),['P08','D03']);
  assert.equal(tree.recommendations.at(-1)?.role,'EVIDENCE');
});

test('wrong metric cannot reveal or navigate arbitrary model edges',()=>{
  assert.deepEqual(goalMetricCodes(goal),['PROJECT_NPV','DISCOUNT_RATE','INCREMENTAL_NPV']);
  assert.equal(buildDecisionTree(goal,'UNRELATED').branches.length,0);
  assert.equal(formContext(goal,'UNRELATED','P07','INV-05'),null);
  assert.equal(formContext(goal,'PROJECT_NPV','C05','INV-05'),null);
  assert.equal(formContext(goal,'PROJECT_NPV','P07','INV-04'),null);
  assert.equal(formContext(goal,'PROJECT_NPV','D03','INV-05'),null);
});

test('form open carries exact goal, metric, model, role and source-link kind',()=>{
  assert.deepEqual(formContext(goal,'PROJECT_NPV','P07','INV-05'),{
    goalCode:'INVESTMENT_APPRAISAL',goalTitle:'Оценка инвестиционного проекта',
    metricCode:'PROJECT_NPV',modelCode:'INV-05',formCode:'P07',
    linkKind:'MODEL_INPUT_CANDIDATE',role:'CORE'
  });
  assert.deepEqual(formContext(goal,'PROJECT_NPV','D03',null),{
    goalCode:'INVESTMENT_APPRAISAL',goalTitle:'Оценка инвестиционного проекта',
    metricCode:'PROJECT_NPV',modelCode:null,formCode:'D03',
    linkKind:'GOAL_RECOMMENDATION_CANDIDATE',role:'EVIDENCE'
  });
});

test('shared model forms are not promoted into required or approved contracts',()=>{
  const tree=buildDecisionTree(goal,'PROJECT_NPV');
  assert.equal(tree.branches[0].model.qualificationStatus,'CALC_PILOT');
  assert.equal(tree.branches[0].inputs[0].linkKind,'MODEL_INPUT_CANDIDATE');
  assert.ok(!('isApproved' in tree.branches[0].inputs[0]));
});
