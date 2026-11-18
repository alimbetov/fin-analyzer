import type {Draft,FormDetail} from './api';

export type EditorEligibility = {
  allowed:boolean;
  reason:string|null;
};

/** UI convenience policy. The Backend remains the write authority. */
export function canEditForm(form:FormDetail|null,code:string,draft:Draft|null):EditorEligibility {
  if(!form)return {allowed:false,reason:'FORM_NOT_LOADED'};
  if(form.form.formCode!==code)return {allowed:false,reason:'FORM_LOADING'};
  if(form.form.contentMode!=='SINGLE_RECORD')
    return {allowed:false,reason:'UNSUPPORTED_CONTENT_MODE'};
  // R16: the public catalog is advisory. Only the explicitly reviewed F02
  // SINGLE_RECORD pilot has a server-enforced writable financial contract.
  // TYPED_DRAFT alone does not qualify the other catalog candidates.
  if(code!=='F02')
    return {allowed:false,reason:'CAPTURE_SCHEMA_NOT_APPROVED'};
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
