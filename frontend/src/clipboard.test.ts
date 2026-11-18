import test from 'node:test';
import assert from 'node:assert/strict';
import {parseExcelPaste,ClipboardShapeError} from './clipboard.ts';

const fields=[
  {fieldCode:'netRevenue',title:'Выручка',fieldType:'MONEY'},
  {fieldCode:'cogs',title:'Себестоимость',fieldType:'MONEY'},
  {fieldCode:'operatingExpenses',title:'Расходы',fieldType:'MONEY'}
];

test('Excel one-column values paste in order and preserve raw precision',()=>{
  assert.deepEqual(parseExcelPaste('125000,50\r\n70000\r\n56444',0,fields),
    ['125000,50','70000','56444']);
});
test('single Excel horizontal row pastes into vertical editor',()=>{
  assert.deepEqual(parseExcelPaste('125000.50\t70000\t56444',0,fields),
    ['125000.50','70000','56444']);
});
test('matches two-column label-value clipboard by exact field',()=>{
  assert.deepEqual(parseExcelPaste('Выручка\t100\nСебестоимость\t50',0,fields),['100','50']);
  assert.deepEqual(parseExcelPaste('cogs\t90',1,fields),['90']);
});
test('matches Excel V4 numbered report triple columns',()=>{
  assert.deepEqual(parseExcelPaste('1\tВыручка\t100\n2\tСебестоимость\t50',0,fields),['100','50']);
});
test('fails closed on reordered or mismatched financial labels',()=>{
  assert.throws(()=>parseExcelPaste('1\tСебестоимость\t100\n2\tВыручка\t50',0,fields),
    ClipboardShapeError);
  assert.throws(()=>parseExcelPaste('Доход\t100\nСебестоимость\t50',0,fields),
    ClipboardShapeError);
});
test('fails closed when rectangular range exceeds last field or has irregular shape',()=>{
  assert.throws(()=>parseExcelPaste('1\n2\n3',2,fields),ClipboardShapeError);
  assert.throws(()=>parseExcelPaste('1\tВыручка\t100\n2\t50',0,fields),
    ClipboardShapeError);
});
test('single-cell paste remains native and empty buffer is invalid',()=>{
  assert.throws(()=>parseExcelPaste('',0,fields),ClipboardShapeError);
  assert.throws(()=>parseExcelPaste('1',-1,fields),ClipboardShapeError);
});
