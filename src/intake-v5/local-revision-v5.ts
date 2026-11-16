import type {EntryContext, EntryRow, ValidatedEntry} from "./form-schema";

/** Browser-session-only draft revisions. This is NOT projectId/PostgreSQL or trusted source history. */
export interface LocalDraftRevisionV5 {
  identity: string;
  revisionNumber: number;
  inputLocale: EntryContext["locale"];
  sourceKind: "MANUAL" | "XLSX";
  uploadedAt: string; // ISO UTC from client clock, unverified
  savedAt: string;    // ISO UTC from client clock, unverified
  changeReason: string;
  scale: EntryContext["amountScale"];
  rows: readonly EntryRow[];
}
export interface RowChangeV5 {
  fieldCode: string;
  previous: string | null;
  current: string;
  changed: boolean;
}
export interface LocalRevisionPlanV5 {
  previous: LocalDraftRevisionV5 | null;
  nextRevision: number;
  status: "NEW" | "CHANGED" | "DUPLICATE" | "MONEY_CONTEXT_MISMATCH";
  changes: readonly RowChangeV5[];
}

export function localReportIdentity(context:EntryContext):string {
  // Locale is intentionally excluded: the same report is viewable in 3 languages.
  // Scale is excluded so a change to it cannot silently create a second report.
  return JSON.stringify([context.projectCode,context.formCode,context.periodCode,
    context.accountingCurrency,context.reportCurrency]);
}
export function planLocalRevisionV5(
  history: readonly LocalDraftRevisionV5[], entry:ValidatedEntry
):LocalRevisionPlanV5 {
  if(!entry.valid) throw new Error("LOCAL_V5_INVALID_PREVIEW");
  const identity=localReportIdentity(entry.context);
  const existing=history.filter(rev=>rev.identity===identity);
  const previous=existing.reduce<LocalDraftRevisionV5|null>(
    (latest,item)=>!latest || item.revisionNumber>latest.revisionNumber?item:latest,null);
  if(previous && previous.scale!==entry.context.amountScale)
    return {previous,nextRevision:previous.revisionNumber,
      status:"MONEY_CONTEXT_MISMATCH",changes:[]};
  const priorByCode=new Map(previous?.rows.map(row=>[row.fieldCode,row.rawValue]));
  const changes=entry.rows.map(row=>{
    const old=priorByCode.get(row.fieldCode)??null;
    return {fieldCode:row.fieldCode,previous:old,current:row.rawValue,
      changed:old!==row.rawValue};
  });
  const duplicate=previous!==null && changes.every(c=>!c.changed);
  return {previous,
    nextRevision:previous?(duplicate?previous.revisionNumber:previous.revisionNumber+1):1,
    status:!previous?"NEW":duplicate?"DUPLICATE":"CHANGED",
    changes};
}
export function commitLocalRevisionV5(
  history:readonly LocalDraftRevisionV5[],
  entry:ValidatedEntry,
  uploadedAt:string,sourceKind:"MANUAL"|"XLSX",
  reason:string,savedAt:string
):{revision:LocalDraftRevisionV5;history:LocalDraftRevisionV5[];result:"CREATED"|"DUPLICATE"} {
  const plan=planLocalRevisionV5(history,entry);
  if(plan.status==="MONEY_CONTEXT_MISMATCH")
    throw new Error("LOCAL_V5_MONEY_CONTEXT_MISMATCH");
  if(plan.status==="DUPLICATE")
    return {revision:plan.previous!,history:[...history],result:"DUPLICATE"};
  if(plan.status==="CHANGED" && !reason.trim())
    throw new Error("LOCAL_V5_CHANGE_REASON_REQUIRED");
  if(!Number.isFinite(Date.parse(uploadedAt)) || !Number.isFinite(Date.parse(savedAt)))
    throw new Error("LOCAL_V5_TIMESTAMP_INVALID");
  const revision:LocalDraftRevisionV5={
    identity:localReportIdentity(entry.context),
    revisionNumber:plan.nextRevision,inputLocale:entry.context.locale,
    sourceKind,uploadedAt,savedAt,changeReason:reason.trim(),
    scale:entry.context.amountScale,
    rows:entry.rows.map(row=>({...row}))
  };
  return {revision,history:[...history,revision],result:"CREATED"};
}
