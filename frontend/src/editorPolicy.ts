import type {Draft,FormDetail,Receipt,Row,Field} from './api';

export type EditorEligibility = {
  allowed:boolean;
  reason:string|null;
};

/** UI convenience policy. The Backend remains the write authority. */
export function canEditForm(form:FormDetail|null,code:string,draft:Draft|null):EditorEligibility {
  if(!form)return {allowed:false,reason:'FORM_NOT_LOADED'};
  if(form.form.formCode!==code)return {allowed:false,reason:'FORM_LOADING'};
  if(!['SINGLE_RECORD','ROWSET','EVIDENCE'].includes(form.form.contentMode))
    return {allowed:false,reason:'UNSUPPORTED_CONTENT_MODE'};
  if(!form.fields.some(f=>f.inputMode==='MANUAL'))
    return {allowed:false,reason:'NO_MANUAL_FIELDS'};
  if(draft){
    if(draft.formCode!==code)return {allowed:false,reason:'DRAFT_FORM_MISMATCH'};
    if(draft.workflowStatus!=='DRAFT')return {allowed:false,reason:'REPORT_NOT_EDITABLE'};
    if(draft.formVersion!==form.form.version)
      return {allowed:false,reason:'PINNED_FORM_VERSION_MISMATCH'};
  }
  return {allowed:true,reason:null};
}

export function matchesPreview(
  ticket:{instanceId:string;organizationId:string;expectedRevision:number;uploadSha256:string},
  snapshot:Draft,
  uploadedFileSha:string
):boolean {
  return ticket.instanceId===snapshot.instanceId &&
    ticket.organizationId===snapshot.organizationId &&
    ticket.expectedRevision===snapshot.revisionNo &&
    ticket.uploadSha256===uploadedFileSha;
}

/** SHA-256 is evaluated on the uploaded raw bytes, not the displayed filename. */
export async function fileSha256(file:File):Promise<string> {
  const digest=await crypto.subtle.digest('SHA-256',await file.arrayBuffer());
  return [...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,'0')).join('');
}

/**
 * Read-after-write qualification. Check the immutable receipt AND the actual
 * server snapshot against the submitted form values / full ROWSET identity.
 * Comparisons apply the same decimal canonicalization as the backend for
 * values already syntactically normalized by the editor.
 */
function comparable(value:string,type:string):string {
  if(type==='MONEY'||type==='DECIMAL'){
    const m=/^(-?)(\d+)(?:\.(\d+))?$/.exec(value);
    if(!m)return value;
    const integer=m[2].replace(/^0+(?=\d)/,'');
    const fraction=(m[3]??'').replace(/0+$/,'');
    const zero=integer==='0'&&!fraction;
    return (zero?'':m[1])+integer+(fraction?'.'+fraction:'');
  }
  if(type==='INTEGER'&&/^-?\d+$/.test(value)){
    const m=/^(-?)(\d+)$/.exec(value)!;
    const integer=m[2].replace(/^0+(?=\d)/,'');
    return integer==='0'?'0':m[1]+integer;
  }
  return value.trim();
}
function sameFields(expected:Record<string,string>,actual:Record<string,string>,
  types:Map<string,string>):boolean {
  if(Object.keys(expected).length!==Object.keys(actual).length)return false;
  return Object.entries(expected).every(([key,value])=>
    Object.prototype.hasOwnProperty.call(actual,key)&&
    comparable(value,types.get(key)??'TEXT')===comparable(actual[key],types.get(key)??'TEXT'));
}
export function verifySavedDraft(
  receipt:Receipt,snapshot:Draft,expectedOrganization:string,
  expectedFormCode:string,previousRevision:number,
  expectedValues:Record<string,string>,expectedRows:Row[],fields:Field[]
):boolean {
  if(snapshot.instanceId!==receipt.instanceId||
     snapshot.organizationId!==expectedOrganization||
     snapshot.formCode!==expectedFormCode||
     snapshot.revisionNo!==receipt.revisionNo||
     snapshot.revisionNo!==previousRevision+1||
     snapshot.sha256!==receipt.contentSha256)return false;
  const types=new Map(fields.map(f=>[f.fieldCode,f.fieldType]));
  if(!sameFields(expectedValues,snapshot.values,types))return false;
  if(snapshot.rows.length!==expectedRows.length)return false;
  const byId=new Map(snapshot.rows.map(r=>[r.rowId,r]));
  if(byId.size!==snapshot.rows.length)return false;
  return expectedRows.every(r=>{
    const persisted=byId.get(r.rowId);
    return persisted!==undefined&&persisted.rowPosition===r.rowPosition&&
      sameFields(r.values,persisted.values,types);
  });
}
