import {getCaptureForm} from "./catalog-schema";

/**
 * Local structural inspection for the 12 document/tax forms.
 * Not an upload service, antivirus result, government verification or source approval.
 */
export interface EvidenceDraft {
  readonly formCode:string;
  readonly filename:string;
  readonly contentType:string;
  readonly byteLength:number;
  readonly sha256:string;
  readonly status:"LOCAL_UNVERIFIED_EVIDENCE";
  readonly sourceScan:"NOT_PERFORMED";
  readonly originalStoredOnServer:false;
  readonly valuesApproved:false;
  readonly qualification:"EVIDENCE_PENDING_REVIEW";
}
const ACCEPTED=new Map([
  [".pdf","application/pdf"],
  [".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"],
  [".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document"],
  [".png","image/png"],
  [".jpg","image/jpeg"],
  [".jpeg","image/jpeg"],
  [".xml","application/xml"]
]);
export const MAX_EVIDENCE_BYTES=10_000_000;

function kindForFilename(name:string):string {
  if(!/^[\p{L}\p{N}_.() -]{1,160}$/u.test(name)||name.startsWith(".")||name.endsWith(".")||
     name.includes("..")||name.includes("/")||name.includes("\\"))
    throw new Error("EVIDENCE_FILENAME_REJECTED");
  const extension=/(\.[A-Za-z0-9]+)$/.exec(name)?.[1]?.toLowerCase();
  const kind=extension&&ACCEPTED.get(extension);
  if(!kind)throw new Error("EVIDENCE_TYPE_NOT_ALLOWED");
  return kind;
}
function looksLike(mime:string,b:Uint8Array):boolean {
  const starts=(sequence:number[])=>sequence.every((v,i)=>b[i]===v);
  if(mime==="application/pdf")
    return starts([37,80,68,70,45])&&b.length>9;
  if(mime==="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"||
     mime==="application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    return starts([80,75,3,4])&&b.length>12;
  if(mime==="image/png")return starts([137,80,78,71,13,10,26,10])&&b.length>12;
  if(mime==="image/jpeg")return starts([255,216,255])&&b.length>12;
  if(mime==="application/xml"){
    const text=new TextDecoder("utf-8",{fatal:true}).decode(b.slice(0,128));
    return text.startsWith("<?xml ") || /^<[\p{L}_]/u.test(text);
  }
  return false;
}
export async function inspectLocalEvidence(
  formCode:string,filename:string,bytes:Uint8Array
):Promise<EvidenceDraft> {
  const form=getCaptureForm(formCode);
  if(form.captureMode!=="EVIDENCE_ONLY")
    throw new Error("EVIDENCE_CODE_NOT_DOCUMENT");
  if(!(bytes instanceof Uint8Array)||bytes.byteLength===0||
     bytes.byteLength>MAX_EVIDENCE_BYTES)
    throw new Error("EVIDENCE_SIZE_REJECTED");
  const contentType=kindForFilename(filename);
  if(!looksLike(contentType,bytes))
    throw new Error("EVIDENCE_MAGIC_OR_ENCODING_MISMATCH");
  if(!globalThis.crypto?.subtle)
    throw new Error("EVIDENCE_SHA256_UNAVAILABLE");
  const digest=await globalThis.crypto.subtle.digest("SHA-256",bytes.slice().buffer);
  const sha256=Array.from(new Uint8Array(digest))
    .map(x=>x.toString(16).padStart(2,"0")).join("");
  return {formCode,filename,byteLength:bytes.byteLength,contentType,sha256,
    status:"LOCAL_UNVERIFIED_EVIDENCE",sourceScan:"NOT_PERFORMED",
    originalStoredOnServer:false,valuesApproved:false,
    qualification:"EVIDENCE_PENDING_REVIEW"};
}
