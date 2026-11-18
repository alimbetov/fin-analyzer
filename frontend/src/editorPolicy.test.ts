import test from 'node:test';
import assert from 'node:assert/strict';
import {canEditForm,matchesPreview,verifySavedDraft} from './editorPolicy.ts';
import type {Draft,FormDetail} from './api';

const form=(overrides:Partial<FormDetail['form']>={}):FormDetail=>({
 form:{formCode:'F02',version:'FIN-FORM-V2',title:'Statement',
  captureMode:'TYPED_DRAFT',contentMode:'SINGLE_RECORD',
  qualificationState:'PILOT',translationStatus:'VERIFIED',...overrides},
 fields:[{fieldCode:'netRevenue',fieldType:'MONEY',inputMode:'MANUAL',
  required:true,displayOrder:1,sectionCode:'MAIN',title:'Revenue',
  translationStatus:'VERIFIED',unitPolicy:'CURRENCY'}],
 sections:[]
});
const draft=(overrides:Partial<Draft>={}):Draft=>({
 instanceId:'i-1',organizationId:'org-1',revisionNo:2,formCode:'F02',
 formVersion:'FIN-FORM-V2',contentMode:'SINGLE_RECORD',values:{netRevenue:'100'},
 rows:[],sha256:'hash',periodType:'M',periodCode:'2026-11',
 currency:'KZT',workflowStatus:'DRAFT',economicEntityId:'e',
 businessScopeId:'b',projectId:'p',coverage:'PERIOD_ONLY',
 reportingBasis:'MANAGEMENT',dataSpace:'SYNTHETIC',...overrides
});
test('approved typed F02 is editable for new and matching current revision',()=>{
 assert.equal(canEditForm(form(),'F02',null).allowed,true);
 assert.equal(canEditForm(form(),'F02',draft()).allowed,true);
});
test('typed ROWSET catalog candidate enables only unqualified draft editing',()=>{
 const candidate=form({formCode:'DF-CSH-01',captureMode:'TYPED_DRAFT',contentMode:'ROWSET'});
 const result=canEditForm(candidate,'DF-CSH-01',null);
 assert.equal(result.allowed,true);
 assert.equal(result.reason,null);
});
test('non-typed or EVIDENCE forms remain read only',()=>{
 const unqualified=form({formCode:'F03',captureMode:'SCHEMA_NOT_APPROVED'});
 assert.equal(canEditForm(unqualified,'F03',null).reason,'CAPTURE_SCHEMA_NOT_APPROVED');
 assert.equal(canEditForm(form({formCode:'DOC-01',contentMode:'EVIDENCE'}),'DOC-01',null)
   .reason,'EVIDENCE_UPLOAD_NOT_IMPLEMENTED');
});
test('a stale loaded FormDetail never enables saves for newly selected code',()=>{
 assert.equal(canEditForm(form(),'F03',null).reason,'FORM_LOADING');
 assert.equal(canEditForm(form(),'F03',draft({formCode:'F03'})).allowed,false);
});
test('unqualified, non-single, empty, historical and published reports fail closed',()=>{
 assert.equal(canEditForm(form({captureMode:'UNQUALIFIED',formCode:'F03'}),'F03',null).allowed,false);
 assert.equal(canEditForm(form({contentMode:'ROWS'}),'F02',null).allowed,false);
 assert.equal(canEditForm({...form(),fields:[]},'F02',null).allowed,false);
 assert.equal(canEditForm(form(),'F02',draft({workflowStatus:'PUBLISHED'})).allowed,false);
 assert.equal(canEditForm(form(),'F02',draft({formVersion:'FIN-FORM-V1'})).allowed,false);
});
test('preview binds exact snapshot revision, tenant and file digest',()=>{
 const ticket={instanceId:'i-1',organizationId:'org-1',expectedRevision:2,uploadSha256:'abc'};
 assert.equal(matchesPreview(ticket,draft(),'abc'),true);
 assert.equal(matchesPreview(ticket,draft({revisionNo:3}),'abc'),false);
 assert.equal(matchesPreview(ticket,draft({organizationId:'other'}),'abc'),false);
 assert.equal(matchesPreview(ticket,draft(),'def'),false);
});

test('read-after-write qualification checks exact financial values and row identity',()=>{
 const receipt={instanceId:'i-1',revisionNo:3,contentSha256:'hash',result:'UNQUALIFIED_DRAFT'};
 const fields=form().fields;
 const rows=[{rowId:'row-1',rowPosition:10,values:{netRevenue:'120.00'}}];
 const actual=draft({revisionNo:3,values:{},rows,contentMode:'ROWSET'});
 assert.equal(verifySavedDraft(receipt,actual,'org-1','F02',2,{},rows,fields),true);
 assert.equal(verifySavedDraft(receipt,actual,'org-2','F02',2,{},rows,fields),false);
 assert.equal(verifySavedDraft(receipt,actual,'org-1','F02',1,{},rows,fields),false);
 assert.equal(verifySavedDraft(receipt,actual,'org-1','F02',2,{},[
   {...rows[0],rowPosition:20}],fields),false);
 assert.equal(verifySavedDraft(receipt,actual,'org-1','F02',2,{},[
   {...rows[0],values:{netRevenue:'999'}}],fields),false);
 assert.equal(verifySavedDraft(receipt,actual,'org-1','F02',2,{},[
   {...rows[0],rowId:'row-2'}],fields),false);
 assert.equal(verifySavedDraft(receipt,draft({revisionNo:3}),'org-1','F02',2,
   {netRevenue:'100'},[],fields),true);
 assert.equal(verifySavedDraft(receipt,draft({revisionNo:3}),'org-1','F02',2,
   {netRevenue:'101'},[],fields),false);
});
