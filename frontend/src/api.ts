export type Locale = 'ru' | 'kk' | 'en' | 'zh-Hans';

// FIN-KPI-R0. All relationships are candidate-only reference metadata,
// NOT permission to calculate, write, or access project financial facts.
export type IndicatorModel = {
  code:string;pack:string;title:string;titleReviewStatus:string;
  qualificationStatus:string;inputFormCodes:string[];outputMetricCodes:string[]
};
export type GoalFormLink = {formCode:string;role:'CORE'|'SUPPORTING'|'EVIDENCE';priority:number};
export type IndicatorGoal = {
  code:string;title:string|null;translationStatus:string;audience:string;
  deliverable:string;version:string;qualificationStatus:string;
  models:IndicatorModel[];forms:GoalFormLink[]
};
export type IndicatorExplanation = {
  meaning:string|null;helpsDecision:string|null;limitations:string|null;
  reviewStatus:string;source:string
};
export type IndicatorDefinition = {
  code:string;metricKind:string;qualificationStatus:string;
  models:IndicatorModel[];inputFormCodes:string[];goalCodes:string[];
  explanation:IndicatorExplanation
};
export type IndicatorFormUsage = {
  formCode:string;goalCodes:string[];modelCodes:string[];qualificationStatus:string
};
export type MetricReferenceView = {
  metricCode:string;kind:string;formCode:string|null;fieldCode:string|null;
  unitPolicy:string;qualificationState:string;locale:string;
  title:string|null;description:string|null;translationStatus:string
};

export type Style = 'EXCEL' | 'CLASSIC' | 'DASHBOARD';
export type Category = {code:string;parentCode:string|null;type:string;sortOrder:number;title:string;translationStatus:string};
export type CategoryLink = {categoryCode:string;formCode:string;role:string;sortOrder:number};
export type CategoryTree = {categories:Category[];links:CategoryLink[]};
export type FormItem = {formCode:string;title:string;primaryGroup:string;functionalDomain:string;contentMode:string;qualificationState:string;translationStatus:string};
export type Navigation = {totalForms:number;allForms:FormItem[];recommended:FormItem[]};
export type Field = {fieldCode:string;fieldType:string;inputMode:string;required:boolean;displayOrder:number;sectionCode:string;title:string;translationStatus:string;unitPolicy:string};
export type FormDetail = {form:{formCode:string;version:string;title:string;captureMode:string;contentMode:string;qualificationState:string;translationStatus:string};fields:Field[];sections:{sectionCode:string;sectionOrder:number}[]};
export type Currency = {code:string;title:string|null;minorUnits:number;status:string};
export type PeriodType = {code:string;title:string};
export type PeriodRange = {type:string;code:string;startInclusive:string;endExclusive:string};
export type Row = {rowId:string;rowPosition:number;values:Record<string,string>};
export type EvidenceAttachment = {id:string;fileName:string;mediaType:string;sizeBytes:number;sha256:string};
export type Draft = {attachment?:EvidenceAttachment|null;instanceId:string;revisionId?:string;organizationId:string;revisionNo:number;formCode:string;formVersion:string;contentMode:string;values:Record<string,string>;rows:Row[];sha256:string;periodType:string;periodCode:string;currency:string;workflowStatus:string;economicEntityId:string;businessScopeId:string;projectId:string;coverage:string;reportingBasis:string;dataSpace:string};
export type Receipt = {instanceId:string;revisionNo:number;contentSha256:string;result:string};
export type F02UatNode = {
  state:'PRESENT'|'INVALID';unit:string;value:string|null;
  errorCode:string|null;valueSha256:string
};
export type F02UatPreview = {
  instanceId:string;sourceRevisionId:string;formVersionId:string;
  sourceContentSha256:string;sourceRowOrderSha256:string;
  pinnedSourceSha256:string;sourceSchemaSha256:string;
  candidateFactsetSha256:string;mappingDescriptorSha256:string;
  periodType:string;periodCode:string;currency:string;reportingBasis:string;
  dataSpace:string;outputs:Record<string,F02UatNode>;
  resultValuesSha256:string;previewSha256:string;
  executionState:string;financiallyApproved:boolean;productionAdmitted:boolean
};
export type Profile = {id:string};
export type PreviewTicket = {previewId:string;profileId:string;instanceId:string;organizationId:string;expectedRevision:number;uploadSha256:string;bindingSha256:string;expiresAt:string;inputCellCount:number;values:Record<string,string>};

export class ApiHttpError extends Error {
  readonly status:number;
  readonly detail:string;
  constructor(status:number,detail:string){
    super('HTTP '+status+': '+detail);
    this.status=status;
    this.detail=detail;
    this.name='ApiHttpError';
  }
}

export function isDefiniteRejection(error:unknown):boolean {
  if(!(error instanceof ApiHttpError))return false;
  // A second in-flight request can win the idempotency unique race. Its
  // 409 does not prove the original transaction failed. Preserve the exact
  // command key until the revision has been read back.
  if(error.status===409)
    return !['IDEMPOTENCY_WRITE_CONFLICT','CONCURRENT_OR_DUPLICATE_WRITE']
      .includes(error.detail);
  return [400,401,403,404,413,415,422].includes(error.status);
}

const tokenKey='fin-demo-correlation';
export const demoToken=()=>{
  let value=sessionStorage.getItem(tokenKey);
  if(!value){value=crypto.randomUUID();sessionStorage.setItem(tokenKey,value);}
  return value;
};

export async function request(path:string,options:RequestInit={}):Promise<Response>{
  const headers=new Headers(options.headers);
  headers.set('X-FIN-UAT-Token',demoToken()); // tracing label only; backend does NOT authenticate it
  const response=await fetch('/api/v1'+path,{...options,headers,cache:'no-store'});
  if(!response.ok){
    const raw=await response.text();
    let detail=raw;
    try{const data=JSON.parse(raw) as {code?:string;message?:string;detail?:string;error?:string};
      detail=data.code||data.message||data.detail||data.error||raw;
    }catch{/* keep raw */}
    throw new ApiHttpError(response.status,detail.slice(0,450));
  }
  return response;
}
export async function get<T>(path:string):Promise<T>{return (await request(path)).json() as Promise<T>;}
export async function post<T>(path:string,data:unknown,idem:boolean|string=false):Promise<T>{
  const key=typeof idem==='string'?idem:idem?crypto.randomUUID():null;
  return (await request(path,{method:'POST',headers:{
    'Content-Type':'application/json',...(key?{'Idempotency-Key':key}:{})
  },body:JSON.stringify(data)})).json() as Promise<T>;
}
export async function multipart<T>(path:string,body:FormData,idem:boolean|string=false):Promise<T>{
  const key=typeof idem==='string'?idem:idem?crypto.randomUUID():null;
  return (await request(path,{method:'POST',headers:key?{'Idempotency-Key':key}:undefined,body})).json() as Promise<T>;
}
export async function downloadEvidence(instanceId:string,revision:number,organizationId:string,projectId:string):Promise<Blob>{
  const params=new URLSearchParams({organizationId,projectId});
  const response=await request('/drafts/'+encodeURIComponent(instanceId)
    +'/revisions/'+revision+'/attachment?'+params);
  return response.blob();
}
export async function exportXlsx(instanceId:string,org:string,style:Style,locale:Locale):Promise<{file:Blob;name:string}>{
  const params=new URLSearchParams({organizationId:org,styleCode:style,locale});
  const response=await request('/drafts/'+encodeURIComponent(instanceId)+'/export.xlsx?'+params);
  const disposition=response.headers.get('Content-Disposition')||'';
  const match=/filename="([^"]+\.xlsx)"/i.exec(disposition);
  if(!match)throw new Error('Backend не вернул Content-Disposition filename');
  return {file:await response.blob(),name:match[1]};
}
