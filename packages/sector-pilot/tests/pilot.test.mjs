import test from 'node:test';
import assert from 'node:assert/strict';
import { evaluateSectorForms, PILOT_FORMS } from '../src/index.mjs';

function row(formCode,period,values,dimensionId='item-1',businessId='company-1',currency='KZT'){
  return {formCode,period,values,dimensionId,businessId,currency,approved:true,
    sourceId:formCode+'-'+period+'-'+businessId+'-'+dimensionId,revisionId:'rev-'+period};
}
function pick(rows,formCode,windowCode){return rows.find(x=>x.formCode===formCode&&x.windowCode===windowCode);}
function val(rows,form,window,metric){return pick(rows,form,window).metrics[metric].value;}

test('trade: Q1 gross margin is 46%, not average of monthly percentages',()=>{
  const rows=[
    row('SF-G01','2026-01',{netRevenue:'100',cogs:'90',soldQty:'10'}),
    row('SF-G01','2026-02',{netRevenue:'900',cogs:'450',soldQty:'90'}),
    row('SF-G01','2026-03',{netRevenue:'0',cogs:'0',soldQty:'0'})
  ];
  const r=evaluateSectorForms(rows);
  assert.equal(val(r,'SF-G01','2026-01','SKU_GROSS_MARGIN_PCT'),'10.00');
  assert.equal(val(r,'SF-G01','2026-02','SKU_GROSS_MARGIN_PCT'),'50.00');
  assert.equal(pick(r,'SF-G01','2026-03').metrics.SKU_GROSS_MARGIN_PCT.reason,'ZERO_DENOMINATOR');
  assert.equal(val(r,'SF-G01','2026-Q1','SKU_GROSS_MARGIN_PCT'),'46.00');
  assert.equal(val(r,'SF-G01','2026-Q1','SKU_GROSS_PROFIT'),'460.00');
  assert.equal(pick(r,'SF-G01','2026-H1').status,'INCOMPLETE');
  assert.equal(pick(r,'SF-G01','2026').status,'INCOMPLETE');
});
test('trade: period stock turnover day weighting; landed cost quantity weighting',()=>{
  const stock=['2026-01','2026-02','2026-03'].map((p,i)=>row('SF-G02',p,{
    periodCogs:String((i+1)*100),avgStockValue:String((i+1)*50)}));
  const landed=[
    row('SF-G03','2026-01',{purchasePrice:'12',receivedQty:'10',freight:'20'}),
    row('SF-G03','2026-02',{purchasePrice:'10',receivedQty:'90',freight:'0'}),
    row('SF-G03','2026-03',{purchasePrice:'0',receivedQty:'0',freight:'0'})
  ];
  const r=evaluateSectorForms([...stock,...landed]);
  assert.equal(val(r,'SF-G02','2026-Q1','SKU_STOCK_TURN_PERIOD'),'6.00');
  assert.equal(val(r,'SF-G03','2026-01','LANDED_UNIT_COST'),'14.00');
  assert.equal(val(r,'SF-G03','2026-Q1','LANDED_UNIT_COST'),'10.40');
});
test('services: billable utilization and individual month hourly cost',()=>{
  const r=evaluateSectorForms([
    row('SF-M01','2026-01',{billableHours:'60',availableHours:'100'}),
    row('SF-M01','2026-02',{billableHours:'180',availableHours:'300'}),
    row('SF-M01','2026-03',{billableHours:'0',availableHours:'100'}),
    row('SF-M02','2026-01',{recognizedFee:'1000',directHours:'10',hourCost:'20',otherCost:'100'}),
    row('SF-M02','2026-02',{recognizedFee:'1500',directHours:'15',hourCost:'30',otherCost:'200'}),
    row('SF-M02','2026-03',{recognizedFee:'0',directHours:'0',hourCost:'30',otherCost:'0'}),
    row('SF-S01','2026-01',{netRevenue:'1000',directLaborCost:'200',materialsCost:'300',completedJobs:'10'})
  ]);
  assert.equal(val(r,'SF-M01','2026-Q1','BILLABLE_UTILIZATION_PCT'),'48.00');
  assert.equal(val(r,'SF-M02','2026-Q1','ENGAGEMENT_CONTRIBUTION'),'1550.00');
  assert.equal(val(r,'SF-S01','2026-01','SERVICE_UNIT_MARGIN'),'50.00');
  assert.equal(pick(r,'SF-S01','2026-Q1').status,'INCOMPLETE');
});
test('manufacturing: good yield, unit cost and equipment utilization',()=>{
  const r=evaluateSectorForms([
    row('SF-C01','2026-01',{unitsStarted:'100',goodUnits:'90',reworkedUnits:'5',wasteUnits:'5'}),
    row('SF-C01','2026-02',{unitsStarted:'900',goodUnits:'450',reworkedUnits:'30',wasteUnits:'420'}),
    row('SF-C01','2026-03',{unitsStarted:'0',goodUnits:'0',reworkedUnits:'0',wasteUnits:'0'}),
    row('SF-C02','2026-01',{materialUsed:'900',directLabor:'100',overhead:'100',goodUnits:'90'}),
    row('SF-C02','2026-02',{materialUsed:'4500',directLabor:'500',overhead:'0',goodUnits:'450'}),
    row('SF-C02','2026-03',{materialUsed:'0',directLabor:'0',overhead:'0',goodUnits:'0'}),
    row('SF-C03','2026-01',{runHours:'80',plannedHours:'100',downtimeHours:'20'}),
    row('SF-C03','2026-02',{runHours:'180',plannedHours:'300',downtimeHours:'120'}),
    row('SF-C03','2026-03',{runHours:'0',plannedHours:'0',downtimeHours:'0'})
  ]);
  assert.equal(val(r,'SF-C01','2026-Q1','GOOD_YIELD_PCT'),'54.00');
  assert.equal(val(r,'SF-C02','2026-Q1','UNIT_MFG_COST'),'11.30');
  assert.equal(val(r,'SF-C03','2026-Q1','EQUIPMENT_UTILIZATION_PCT'),'65.00');
});
test('missing is distinct from zero, quarter/half year remain incomplete',()=>{
  const r=evaluateSectorForms([row('SF-G01','2026-01',{netRevenue:'100',cogs:null,soldQty:'10'})]);
  assert.equal(pick(r,'SF-G01','2026-01').status,'INCOMPLETE');
  assert.equal(val(r,'SF-G01','2026-01','SKU_GROSS_PROFIT'),null);
  assert.deepEqual(pick(r,'SF-G01','2026-Q1').missingPeriods,['2026-02','2026-03']);
});
test('dedup, tenant scoping and monthly conflict',()=>{
  const x=row('SF-G01','2026-01',{netRevenue:'100',cogs:'20',soldQty:'1'});
  assert.throws(()=>evaluateSectorForms([x,x]),/duplicate sourceId/);
  assert.throws(()=>evaluateSectorForms([x,{...x,sourceId:'alt-source'}]),/duplicate monthly row/);
  const r=evaluateSectorForms([x,{...x,businessId:'another-business',sourceId:'other-source'}]);
  assert.equal(r.filter(x=>x.windowType==='MONTH').length,2);
});
test('reject invalid inputs, unapproved data and physical impossibilities',()=>{
  const x=row('SF-G01','2026-01',{netRevenue:'100',cogs:'20',soldQty:'1'});
  for(const bad of [
    {...x,approved:false},{...x,values:{...x.values,cogs:'-1'}},
    {...x,values:{...x.values,cogs:20}},
    {...x,values:{...x.values,unexpected:'2'}},
    {...x,currency:'KZT;DROP'},{...x,period:'2026-13'},
    {...x,formCode:'SF-K01'},
    {...x,values:{netRevenue:'100',cogs:'20'}}
  ])assert.throws(()=>evaluateSectorForms([bad]));
  assert.throws(()=>evaluateSectorForms([row('SF-M01','2026-01',{billableHours:'101',availableHours:'100'})]),/billable/);
  assert.throws(()=>evaluateSectorForms([row('SF-C03','2026-01',{runHours:'101',plannedHours:'100',downtimeHours:'0'})]),/run/);
});
test('exact decimal rounding, without floating-point drift',()=>{
  const r=evaluateSectorForms([row('SF-G03','2026-01',{purchasePrice:'0.1',receivedQty:'3',freight:'0.2'})]);
  assert.equal(val(r,'SF-G03','2026-01','LANDED_UNIT_COST'),'0.17');
});
test('only nine pilot forms; all other sector metrics NOT IMPLEMENTED',()=>{
  assert.deepEqual([...PILOT_FORMS].sort(),['SF-G01','SF-G02','SF-G03','SF-M01','SF-M02','SF-S01','SF-C01','SF-C02','SF-C03'].sort());
});


test('semiannual and annual aggregations use all monthly facts, no mean-of-ratios shortcut',()=>{
  const months=Array.from({length:12},(_,i)=>'2026-'+String(i+1).padStart(2,'0'));
  const trade=months.map(p=>row('SF-G01',p,{netRevenue:'100',cogs:'50',soldQty:'10'}));
  const services=months.map(p=>row('SF-M01',p,{billableHours:'3',availableHours:'10'}));
  const factory=months.map(p=>row('SF-C02',p,{materialUsed:'12',directLabor:'6',overhead:'2',goodUnits:'4'}));
  const results=evaluateSectorForms([...trade,...services,...factory]);
  assert.equal(val(results,'SF-G01','2026-H1','SKU_GROSS_MARGIN_PCT'),'50.00');
  assert.equal(val(results,'SF-G01','2026','SKU_GROSS_PROFIT'),'600.00');
  assert.equal(val(results,'SF-M01','2026','BILLABLE_UTILIZATION_PCT'),'30.00');
  assert.equal(val(results,'SF-C02','2026','UNIT_MFG_COST'),'5.00');
  for(const form of ['SF-G01','SF-M01','SF-C02'])
    assert.equal(pick(results,form,'2026').status,'CALCULATED');
});
test('complete quarter with zero denominator differs from incomplete half-year',()=>{
  const r=evaluateSectorForms(['2026-01','2026-02','2026-03'].map(p=>
    row('SF-M01',p,{billableHours:'0',availableHours:'0'})));
  assert.equal(pick(r,'SF-M01','2026-Q1').status,'CALCULATED');
  assert.equal(pick(r,'SF-M01','2026-Q1').metrics.BILLABLE_UTILIZATION_PCT.reason,'ZERO_DENOMINATOR');
  assert.equal(pick(r,'SF-M01','2026-H1').status,'INCOMPLETE');
  assert.equal(pick(r,'SF-M01','2026-H1').metrics.BILLABLE_UTILIZATION_PCT.reason,'MISSING_PERIODS');
});
