import test from 'node:test';
import assert from 'node:assert/strict';
import {canEditForm,matchesPreview} from './editorPolicy.ts';
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
test('catalog TYPED_DRAFT hints for F03 do not authorize writes',()=>{
 const candidate=form({formCode:'F03',captureMode:'TYPED_DRAFT',contentMode:'SINGLE_RECORD'});
 const result=canEditForm(candidate,'F03',null);
 assert.equal(result.allowed,false);
 assert.equal(result.reason,'CAPTURE_SCHEMA_NOT_APPROVED');
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
