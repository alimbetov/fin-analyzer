import { useRef, useState, type ChangeEvent, type KeyboardEvent } from "react";
import {
  PILOT_F02_SCHEMA, type ActiveLocale, type EntryContext, type EntryRow,
  type ValidatedEntry, type EntryFinding, formatExactDecimal, validateF02
} from "./form-schema";
import { resolvePeriod } from "./reporting-period";
import { generateF02ExcelV5, parseF02ExcelV5 } from "./excel-v5";
import {
  planLocalRevisionV5,commitLocalRevisionV5,localReportIdentity,
  type LocalDraftRevisionV5
} from "./local-revision-v5";

const UI: Record<ActiveLocale, {
  title:string;sub:string;project:string;language:string;period:string;scale:string;
  download:string;upload:string;check:string;save:string;apply:string;
  code:string;indicator:string;amount:string;currency:string;status:string;
  preview:string;missing:string;valid:string;invalid:string;local:string
}> = {
  ru: {title:"Универсальный ввод финансовой формы",sub:"Пилот F02 · одна схема для Excel и React",
    project:"Код проекта (локальный черновик)",language:"Язык шаблона",period:"Отчётный месяц",
    scale:"Масштаб сумм",download:"Скачать Excel",upload:"Загрузить Excel",
    check:"Проверить и показать Preview",save:"Скачать локальный черновик JSON",
    apply:"Перенести данные в редактор",
    code:"Код показателя",indicator:"Наименование",amount:"Значение",
    currency:"Валюта",status:"Проверка",preview:"Предпросмотр данных",
    missing:"Не заполнено",valid:"Проверено",invalid:"Требуется исправление",
    local:"Локальный UX-пилот. Не является серверной загрузкой, утверждением или публикацией фактов."},
  kk: {title:"Қаржылық нысанды бірыңғай енгізу",sub:"F02 пилоты · Excel және React үшін бір схема",
    project:"Жоба коды (жергілікті нобай)",language:"Үлгі тілі",period:"Есепті ай",
    scale:"Сома масштабы",download:"Excel жүктеп алу",upload:"Excel жүктеу",
    check:"Тексеру және алдын ала қарау",save:"Жергілікті JSON нобайын жүктеп алу",
    apply:"Деректерді редакторға көшіру",
    code:"Көрсеткіш коды",indicator:"Атауы",amount:"Мәні",
    currency:"Валюта",status:"Тексеру",preview:"Деректерді алдын ала қарау",
    missing:"Толтырылмаған",valid:"Тексерілді",invalid:"Түзету қажет",
    local:"Жергілікті интерфейс пилоты. Серверге жүктеу, бекіту немесе қаржылық деректерді жариялау емес."},
  en: {title:"Financial form data entry",sub:"F02 pilot · one schema for Excel and React",
    project:"Project code (local draft)",language:"Template language",period:"Reporting month",
    scale:"Amount scale",download:"Download Excel",upload:"Import Excel",
    check:"Validate and preview",save:"Download local draft JSON",
    apply:"Copy values into editor",
    code:"Indicator code",indicator:"Indicator",amount:"Amount",
    currency:"Currency",status:"Validation",preview:"Data preview",
    missing:"Not entered",valid:"Validated",invalid:"Corrections required",
    local:"Local UI pilot. This is not a server submission, independent approval or published financial data."}
};
const REV_LABELS:Record<ActiveLocale,{
  history:string;version:string;uploaded:string;saved:string;source:string;
  reason:string;reasonPlaceholder:string;compare:string;before:string;after:string;
  noChanges:string;changed:string;newRevision:string;sessionOnly:string;
}> = {
  ru: {
    history:"История версий (текущая сессия)",version:"Версия",
    uploaded:"Время загрузки",saved:"Время сохранения",source:"Источник",
    reason:"Причина исправления",reasonPlaceholder:"Укажите причину корректировки",
    compare:"Сравнение с предыдущей версией",before:"Было",after:"Стало",
    noChanges:"Данные без изменений",changed:"Обнаружены изменения",
    newRevision:"Новая версия",sessionOnly:"История только в этой вкладке, без серверного сохранения."
  },
  kk: {
    history:"Нұсқалар тарихы (ағымдағы сеанс)",version:"Нұсқа",
    uploaded:"Жүктеу уақыты",saved:"Сақтау уақыты",source:"Дереккөзі",
    reason:"Түзету себебі",reasonPlaceholder:"Есепті түзету себебін көрсетіңіз",
    compare:"Алдыңғы нұсқамен салыстыру",before:"Бұрын",after:"Қазір",
    noChanges:"Деректер өзгермеді",changed:"Өзгерістер анықталды",
    newRevision:"Жаңа нұсқа",sessionOnly:"Тарих тек осы қойындыда, серверде сақталмаған."
  },
  en: {
    history:"Version history (current session)",version:"Version",
    uploaded:"Uploaded at",saved:"Saved at",source:"Source",
    reason:"Correction reason",reasonPlaceholder:"Explain the correction",
    compare:"Comparison with previous revision",before:"Before",after:"After",
    noChanges:"No changes in values",changed:"Changes detected",
    newRevision:"New revision",sessionOnly:"History is only in this tab, not saved on the server."
  }
};
function localDateTime(iso:string,locale:ActiveLocale):string {
  const tag={ru:"ru-RU",kk:"kk-KZ",en:"en-US"}[locale];
  // dateStyle/timeStyle cannot be combined with timeZoneName (ECMA-402).
  return new Intl.DateTimeFormat(tag,{
    year:"numeric",month:"2-digit",day:"2-digit",hour:"2-digit",
    minute:"2-digit",second:"2-digit",timeZoneName:"short"
  }).format(new Date(iso));
}

const ISSUE_LABEL: Record<ActiveLocale, Record<string,string>> = {
  ru: {
    REQUIRED_FIELD_MISSING:"Обязательное значение не заполнено",
    INVALID_DECIMAL:"Некорректное число",
    INVALID_MONTH:"Некорректный отчётный месяц",
    PROJECT_CODE_REQUIRED:"Укажите код проекта",
    UNKNOWN_SOURCE_COORDINATE:"Неизвестный показатель или строка",
    DUPLICATE_SOURCE_FIELD:"Дублирование показателя",
    V5_PILOT_CONTEXT_NOT_SUPPORTED:"Не поддерживается контекст пилотной формы",
    TRANSLATION_NOT_READY:"Перевод недоступен"
  },
  kk: {
    REQUIRED_FIELD_MISSING:"Міндетті мән толтырылмаған",
    INVALID_DECIMAL:"Сан пішімі қате",
    INVALID_MONTH:"Есепті ай қате",
    PROJECT_CODE_REQUIRED:"Жоба кодын көрсетіңіз",
    UNKNOWN_SOURCE_COORDINATE:"Белгісіз көрсеткіш немесе жол",
    DUPLICATE_SOURCE_FIELD:"Көрсеткіш қайталанған",
    V5_PILOT_CONTEXT_NOT_SUPPORTED:"Пилоттық нысанның контекстіне қолдау жоқ",
    TRANSLATION_NOT_READY:"Аударма қолжетімсіз"
  },
  en: {
    REQUIRED_FIELD_MISSING:"Required value is missing",
    INVALID_DECIMAL:"Invalid decimal number",
    INVALID_MONTH:"Invalid reporting month",
    PROJECT_CODE_REQUIRED:"Enter the project code",
    UNKNOWN_SOURCE_COORDINATE:"Unknown indicator or row",
    DUPLICATE_SOURCE_FIELD:"Duplicate indicator",
    V5_PILOT_CONTEXT_NOT_SUPPORTED:"Unsupported pilot form context",
    TRANSLATION_NOT_READY:"Translation is not available"
  }
};
function labelFinding(finding:EntryFinding,locale:ActiveLocale):string {
  const message=ISSUE_LABEL[locale][finding.code]??finding.code;
  return message+(finding.sourceCell?" · "+finding.sourceCell:"");
}
function blankRows(): EntryRow[] {
  return PILOT_F02_SCHEMA.fields.map(f=>({rowId:f.rowId,fieldCode:f.code,rawValue:""}));
}
function download(blob:Blob,name:string):void{
  const url=URL.createObjectURL(blob);
  const a=document.createElement("a");
  a.href=url;a.download=name;document.body.append(a);a.click();a.remove();
  setTimeout(()=>URL.revokeObjectURL(url),1000);
}
function cleanName(value:string):string{
  return value.replace(/[^\p{L}\p{N}_-]/gu,"_").slice(0,50);
}

export function FormDataEntryTable({context,rows,onChange}:{
  context:EntryContext;rows:readonly EntryRow[];onChange:(next:EntryRow[])=>void
}) {
  const labels=UI[context.locale];
  function set(index:number,rawValue:string):void {
    onChange(rows.map((row,i)=>i===index?{...row,rawValue}:row));
  }
  function advance(event:KeyboardEvent<HTMLInputElement>,index:number):void {
    if(event.key==="Enter"){
      event.preventDefault();
      const next=document.getElementById("fin-v5-value-"+(index+1)) as HTMLInputElement|null;
      next?.focus();
    }
  }
  return <div className="fin-v5-scroll">
    <table className="fin-v5-table fin-entry-table" aria-label={labels.title}>
      <caption>{PILOT_F02_SCHEMA.titles[context.locale]} · {context.periodCode} · {context.reportCurrency}</caption>
      <thead><tr><th scope="col">№</th><th scope="col">{labels.code}</th>
        <th scope="col">{labels.indicator}</th><th scope="col">{labels.amount}</th>
        <th scope="col">{labels.currency}</th></tr></thead>
      <tbody>{PILOT_F02_SCHEMA.fields.map((field,index)=><tr key={field.code}>
        <td className="fin-v5-text">{index+1}</td>
        <td className="fin-v5-code fin-v5-text">{field.code}</td>
        <th scope="row" className="fin-v5-text">{field.labels[context.locale]}</th>
        <td className="fin-v5-number"><input id={"fin-v5-value-"+index}
          aria-label={field.labels[context.locale]} inputMode="decimal"
          placeholder="—" autoComplete="off"
          value={rows[index]?.rawValue??""}
          onChange={event=>set(index,event.target.value)}
          onKeyDown={event=>advance(event,index)}/></td>
        <td className="fin-v5-text">{context.reportCurrency}</td>
      </tr>)}</tbody>
    </table>
  </div>;
}

export function FormPreviewTable({entry}: {entry:ValidatedEntry}) {
  const {context,rows,findings}=entry;
  const labels=UI[context.locale];
  const problems=new Map(findings.filter(x=>x.fieldCode).map(f=>[f.fieldCode!,f]));
  return <section className="fin-v5-preview" aria-label={labels.preview}>
    <div className="fin-v5-preview-head">
      <h3>{labels.preview} · {PILOT_F02_SCHEMA.titles[context.locale]}</h3>
      <span role="status" className={entry.valid?"fin-v5-ok":"fin-v5-error"}>
        {entry.valid?labels.valid:labels.invalid}
      </span>
    </div>
    <p className="fin-v5-note">{context.projectCode} · {context.periodCode}
      {" · "}{context.reportCurrency}
      {" · "}{context.amountScale===1?"×1":context.amountScale===1000?"×1 000":"×1 000 000"}</p>
    <div className="fin-v5-scroll"><table className="fin-v5-table fin-preview-table">
      <caption>{PILOT_F02_SCHEMA.titles[context.locale]}</caption>
      <thead><tr><th scope="col">№</th><th scope="col">{labels.indicator}</th>
        <th scope="col">{labels.code}</th><th scope="col">{labels.amount}</th>
        <th scope="col">{labels.status}</th></tr></thead>
      <tbody>{rows.map((row,index)=>{
        const field=PILOT_F02_SCHEMA.fields.find(f=>f.code===row.fieldCode);
        const invalid=problems.get(row.fieldCode);
        return <tr key={row.fieldCode}>
          <td className="fin-v5-text">{index+1}</td>
          <th scope="row" className="fin-v5-text">{field?.labels[context.locale]??row.fieldCode}</th>
          <td className="fin-v5-code fin-v5-text">{row.fieldCode}</td>
          <td className="fin-v5-number">{row.rawValue && !invalid?
            formatExactDecimal(row.rawValue,context.locale):row.rawValue||"—"}</td>
          <td className="fin-v5-text">{invalid?labelFinding(invalid,context.locale):(row.rawValue?labels.valid:labels.missing)}</td>
        </tr>;
      })}</tbody>
    </table></div>
    {findings.length>0 && <ul aria-label="Validation findings" className="fin-v5-issues">
      {findings.map((finding,index)=><li key={index}>{finding.fieldCode??"FORM"}: {labelFinding(finding,context.locale)} <small>({finding.code})</small></li>)}
    </ul>}
  </section>;
}

export function FormIntakeStudioV5() {
  const [context,setContext]=useState<EntryContext>({projectCode:"",formCode:"F02",
    locale:"ru",periodCode:"2026.10",accountingCurrency:"KZT",reportCurrency:"KZT",amountScale:1});
  const [rows,setRows]=useState<EntryRow[]>(blankRows);
  const [preview,setPreview]=useState<ValidatedEntry|null>(null);
  const [error,setError]=useState("");
  const [busy,setBusy]=useState(false);
  const [localHistory,setLocalHistory]=useState<LocalDraftRevisionV5[]>([]);
  const [sourceKind,setSourceKind]=useState<"MANUAL"|"XLSX">("MANUAL");
  const [previewUploadedAt,setPreviewUploadedAt]=useState<string|null>(null);
  const [changeReason,setChangeReason]=useState("");
  const [localMessage,setLocalMessage]=useState("");
  // A workbook is parsed asynchronously. Its result must never be written into
  // a different project, period, scale or language selected while parsing.
  const inputGeneration=useRef(0);
  const labels=UI[context.locale];
  const revisionLabels=REV_LABELS[context.locale];
  const revisionPlan=preview?.valid?planLocalRevisionV5(localHistory,preview):null;
  const matchingHistory=localHistory.filter(r=>r.identity===localReportIdentity(context))
    .sort((a,b)=>b.revisionNumber-a.revisionNumber);
  const period=/^\d{4}\.(0[1-9]|1[0-2])$/.test(context.periodCode)
    ? resolvePeriod("MONTH",context.periodCode):null;
  function updateContext(partial:Partial<EntryContext>):void {
    inputGeneration.current++;
    setContext(current=>({...current,...partial}));
    setPreview(null);setPreviewUploadedAt(null);setError("");setLocalMessage("");
  }
  function updateRows(next:EntryRow[]):void {
    inputGeneration.current++;
    setRows(next);setPreview(null);setPreviewUploadedAt(null);setError("");setLocalMessage("");
  }
  async function downloadTemplate():Promise<void>{
    setBusy(true);setError("");
    try{
      const blob=await generateF02ExcelV5(context);
      const filename="FIN_"+cleanName(context.projectCode)+"_F02_"+context.periodCode+"_"+context.locale+".xlsx";
      download(blob,filename);
    }catch(e){setError(e instanceof Error?e.message:"XLSX_EXPORT_ERROR");}
    finally{setBusy(false);}
  }
  async function uploadFile(event:ChangeEvent<HTMLInputElement>):Promise<void>{
    const file=event.currentTarget.files?.[0];event.currentTarget.value="";
    if(!file)return;
    if(!file.name.toLowerCase().endsWith(".xlsx")){setError("XLSX_FILE_REQUIRED");return;}
    const requestGeneration=++inputGeneration.current;
    const requestedContext={...context};
    setBusy(true);setError("");
    try{
      const next=await parseF02ExcelV5(await file.arrayBuffer(),requestedContext);
      if(inputGeneration.current!==requestGeneration){
        setError("XLSX_V5_IMPORT_CONTEXT_CHANGED");
        return;
      }
      // Parsing only prepares a preview: never overwrite unsaved editor values
      // until the user explicitly chooses to apply the imported rows.
      setPreview(validateF02(requestedContext,next,"XLSX"));
      setPreviewUploadedAt(new Date().toISOString());
      setSourceKind("XLSX");setChangeReason("");setLocalMessage("");
    }catch(e){setError(e instanceof Error?e.message:"XLSX_IMPORT_ERROR");}
    finally{setBusy(false);}
  }
  function saveLocalDraft():void{
    if(!preview?.valid || !previewUploadedAt)return;
    try{
      const result=commitLocalRevisionV5(localHistory,preview,previewUploadedAt,
        sourceKind,changeReason,new Date().toISOString());
      setLocalHistory(result.history);
      setLocalMessage(result.result==="DUPLICATE"
        ?revisionLabels.noChanges+" · v"+result.revision.revisionNumber
        :revisionLabels.newRevision+" · v"+result.revision.revisionNumber);
      download(new Blob([JSON.stringify({
        schema:"fin.v5.local-draft/2",status:"LOCAL_UNVERIFIED_DRAFT",
        persistence:"BROWSER_TAB_ONLY",formVersion:PILOT_F02_SCHEMA.version,
        context:preview.context,revision:result.revision,rows:result.revision.rows
      },null,2)],{type:"application/json"}),"FIN_F02_LOCAL_UNVERIFIED_DRAFT.json");
    }catch(e){setError(e instanceof Error?e.message:"LOCAL_REVISION_SAVE_FAILED");}
  }
  return <section className="panel fin-v5-studio" id="form-intake-v5">
    <div className="panel-heading"><div>
      <span className="eyebrow">V5 / IMPORT UX PILOT</span><h2>{labels.title}</h2>
      <p className="fin-v5-note">{labels.sub}</p></div><span className="pill">LOCAL DRAFT · NOT APPROVED</span>
    </div>
    <div className="fin-v5-controls">
      <label>{labels.project}<input aria-label={labels.project}
        value={context.projectCode} maxLength={50} placeholder="FIN-UAT"
        onChange={e=>updateContext({projectCode:e.target.value})}/></label>
      <label>{labels.language}<select aria-label={labels.language}
        value={context.locale} onChange={e=>updateContext({locale:e.target.value as ActiveLocale})}>
        <option value="ru">Русский</option><option value="kk">Қазақша</option>
        <option value="en">English</option><option value="zh-Hans" disabled>简体中文 · planned</option>
      </select></label>
      <label>{labels.period}<input type="month" aria-label={labels.period}
        value={context.periodCode.replace(".","-")}
        onChange={e=>updateContext({periodCode:e.target.value.replace("-",".")})}/></label>
      <label>{labels.scale}<select aria-label={labels.scale}
        value={context.amountScale}
        onChange={e=>updateContext({amountScale:Number(e.target.value) as EntryContext["amountScale"]})}>
        <option value={1}>1 · {context.reportCurrency}</option>
        <option value={1000}>1 000 · {context.reportCurrency}</option>
        <option value={1000000}>1 000 000 · {context.reportCurrency}</option>
      </select></label>
    </div>
    <p className="fin-v5-note">{period?.startInclusive??"—"} – {period?.endExclusive??"—"}
      {" · accounting currency KZT · report currency KZT · "}{labels.local}</p>
    <FormDataEntryTable context={context} rows={rows} onChange={updateRows}/>
    <div className="action-row fin-v5-actions">
      <button type="button" className="button secondary" disabled={busy}
        onClick={()=>void downloadTemplate()}>{labels.download}</button>
      <label className="fin-v5-upload">{labels.upload}
        <input aria-label={labels.upload} type="file" accept=".xlsx"
          disabled={busy} onChange={e=>void uploadFile(e)}/></label>
      <button type="button" className="button primary" disabled={busy}
        onClick={()=>{
          setPreview(validateF02(context,rows));
          setPreviewUploadedAt(new Date().toISOString());
          setSourceKind("MANUAL");setChangeReason("");setLocalMessage("");setError("");
        }}>{labels.check}</button>
    </div>
    {error && <p role="alert" className="fin-v5-error">{error}</p>}
    {preview && <>
      <FormPreviewTable entry={preview}/>
      {previewUploadedAt && <p className="fin-v5-note" data-testid="fin-v5-preview-upload-time">
        {revisionLabels.uploaded}: {localDateTime(previewUploadedAt,context.locale)}
        {" · "}{revisionLabels.source}: {sourceKind}
        {" · "}{revisionLabels.version}: v{revisionPlan?.nextRevision??"—"}
        {" · "}{revisionPlan?.status==="DUPLICATE"?revisionLabels.noChanges:
          revisionPlan?.status==="CHANGED"?revisionLabels.changed:revisionLabels.newRevision}
      </p>}
      {revisionPlan?.previous && <div className="fin-v5-scroll">
        <table className="fin-v5-table fin-preview-table">
          <caption>{revisionLabels.compare}</caption>
          <thead><tr><th scope="col">{labels.indicator}</th>
            <th scope="col">{revisionLabels.before}</th>
            <th scope="col">{revisionLabels.after}</th>
            <th scope="col">{labels.status}</th></tr></thead>
          <tbody>{revisionPlan.changes.map(change=><tr key={change.fieldCode}>
            <th scope="row" className="fin-v5-text">
              {PILOT_F02_SCHEMA.fields.find(f=>f.code===change.fieldCode)?.labels[context.locale]??change.fieldCode}
            </th>
            <td className="fin-v5-number">{change.previous===null?"—":formatExactDecimal(change.previous,context.locale)}</td>
            <td className="fin-v5-number">{formatExactDecimal(change.current,context.locale)}</td>
            <td className="fin-v5-text">{change.changed?revisionLabels.changed:revisionLabels.noChanges}</td>
          </tr>)}</tbody>
        </table>
      </div>}
      {revisionPlan?.status==="CHANGED" && <label className="fin-v5-revision-reason">
        {revisionLabels.reason}
        <input value={changeReason} maxLength={1000}
          onChange={e=>setChangeReason(e.target.value)}
          placeholder={revisionLabels.reasonPlaceholder} />
      </label>}
      {revisionPlan?.status==="MONEY_CONTEXT_MISMATCH" &&
        <p role="alert" className="fin-v5-error">LOCAL_V5_MONEY_CONTEXT_MISMATCH</p>}
      <div className="action-row fin-v5-actions">
        <button type="button" className="button secondary"
          onClick={()=>updateRows(preview.rows.map(row=>({...row})))}>{labels.apply}</button>
        <button type="button" className="button secondary"
          disabled={!preview.valid || revisionPlan?.status==="MONEY_CONTEXT_MISMATCH" ||
            (revisionPlan?.status==="CHANGED" && !changeReason.trim())}
          onClick={saveLocalDraft}>{labels.save}</button>
      </div>
    </>}
    {localMessage && <p role="status" className="fin-v5-ok">{localMessage}</p>}
    {matchingHistory.length>0 && <div className="fin-v5-revisions">
      <h3>{revisionLabels.history} · F02 · {context.periodCode}</h3>
      <p className="fin-v5-note">{revisionLabels.sessionOnly}</p>
      <div className="fin-v5-scroll"><table className="fin-v5-table fin-preview-table">
        <caption>{revisionLabels.history}</caption>
        <thead><tr><th scope="col">{revisionLabels.version}</th>
          <th scope="col">{revisionLabels.uploaded}</th>
          <th scope="col">{revisionLabels.saved}</th>
          <th scope="col">{revisionLabels.source}</th>
          <th scope="col">{revisionLabels.reason}</th></tr></thead>
        <tbody>{matchingHistory.map(item=><tr key={item.revisionNumber}>
          <td className="fin-v5-text">v{item.revisionNumber}</td>
          <td className="fin-v5-text" title={item.uploadedAt}>{localDateTime(item.uploadedAt,context.locale)}</td>
          <td className="fin-v5-text" title={item.savedAt}>{localDateTime(item.savedAt,context.locale)}</td>
          <td className="fin-v5-text">{item.sourceKind}</td>
          <td className="fin-v5-text">{item.changeReason||"—"}</td>
        </tr>)}</tbody>
      </table></div>
    </div>}
  </section>;
}
