import test from 'node:test';
import assert from 'node:assert/strict';
import {historyValues} from './revisionView.ts';

const current=[{fieldCode:'netRevenue',title:'Выручка по новой версии'},{fieldCode:'cogs',title:'Себестоимость'}];

test('historical field list always originates from persisted snapshot',()=>{
  const old={netRevenue:'1200.00',retiredMetric:'75',cogs:'40.12'};
  assert.deepEqual(historyValues(old,current,false),[
    {fieldCode:'netRevenue',label:'netRevenue',value:'1200.00'},
    {fieldCode:'retiredMetric',label:'retiredMetric',value:'75'},
    {fieldCode:'cogs',label:'cogs',value:'40.12'}
  ]);
});

test('labels are applied only for an exact matching form version',()=>{
  assert.deepEqual(historyValues({netRevenue:'2300.47',futureCode:'0'},current,true),[
    {fieldCode:'netRevenue',label:'Выручка по новой версии',value:'2300.47'},
    {fieldCode:'futureCode',label:'futureCode',value:'0'}
  ]);
});

test('rowset historical values retain every stored column',()=>{
  const rows:Record<string,string>[]=[{sku:'A-20',quantity:'0'}, {sku:'B-42',legacyCost:'99.12'}];
  assert.deepEqual(rows.map(row=>historyValues(row,[],false)),[
    [{fieldCode:'sku',label:'sku',value:'A-20'},{fieldCode:'quantity',label:'quantity',value:'0'}],
    [{fieldCode:'sku',label:'sku',value:'B-42'},{fieldCode:'legacyCost',label:'legacyCost',value:'99.12'}]
  ]);
});
