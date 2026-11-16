import {useRef,useState,type ChangeEvent} from "react";
import {
  CAPTURE_CATALOG,CAPTURE_LOCALES,MAX_CATALOG_ROWS,
  getCaptureForm,validateCatalogCapture,validateCatalogContext,type CatalogCaptureContext,
  type CatalogPreview,type CatalogRow,type CaptureLocale
} from "./catalog-schema";
import {generateTypedCatalogExcel,parseTypedCatalogExcel} from "./catalog-excel";
import {formatExactDecimal} from "../intake-v5/form-schema";
import {SPECIALIZED_CANDIDATES,validateSpecializedCandidate} from "./specialized-contracts";
import {fieldPresentation} from "./field-presentation-r6";
import {inspectLocalEvidence,MAX_EVIDENCE_BYTES,type EvidenceDraft} from "./evidence-contract-r6";
import type {PeriodType,ValueCoverage} from "../intake-v5/reporting-period";

const LOCALIZED={
  ru:{
    title:"Каталог финансовых форм — типизированный ввод",
    subtitle:"166 кодов · 122 типизированных и 32 специализированных черновика · 12 документных источников",
    form:"Форма",project:"Код проекта (локальный)",locale:"Язык шаблона",
    periodType:"Тип периода",period:"Период",coverage:"Характер накопления",
    currency:"Валюта",scale:"Масштаб",download:"Скачать Excel",
    import:"Загрузить Excel",check:"Проверить ввод",add:"Добавить строку",
    remove:"Удалить",apply:"Перенести значения в редактор",
    save:"Скачать JSON черновика",row:"Строка",status:"Проверка",
    before:"Предпросмотр",source:"Источник",error:"Ошибка",
    ready:"Структура проверена — финансовая методика НЕ утверждена",
    blocked:"Для этой формы пока нет утверждённой машиночитаемой схемы. Импорт заблокирован.",
    evidence:"Документ/внешняя отчётность: нужен отдельный безопасный приём файлов и подтверждений.",
    local:"Только локальный черновик. Это НЕ серверная загрузка по projectId, не утверждённый источник и не диагностический результат.",
    field:"Код поля",type:"Тип",cancel:"Закрыть Preview",empty:"Не введено"
  },
  kk:{
    title:"Қаржылық нысандар каталогы — типтелген енгізу",
    subtitle:"166 код · 122 типтелген және 32 арнайы нобай · 12 құжаттық дереккөз",
    form:"Нысан",project:"Жоба коды (жергілікті)",locale:"Үлгі тілі",
    periodType:"Кезең түрі",period:"Кезең",coverage:"Жинақтау сипаты",
    currency:"Валюта",scale:"Масштаб",download:"Excel жүктеп алу",
    import:"Excel жүктеу",check:"Мәндерді тексеру",add:"Жол қосу",
    remove:"Жою",apply:"Деректерді редакторға көшіру",
    save:"JSON нобайын жүктеп алу",row:"Жол",status:"Тексеру",
    before:"Алдын ала қарау",source:"Дереккөзі",error:"Қате",
    ready:"Құрылымы тексерілді — қаржылық әдістеме бекітілмеген",
    blocked:"Бұл нысанның бекітілген машинамен оқылатын схемасы жоқ. Импорт бұғатталған.",
    evidence:"Құжат/сыртқы есеп: файлдар мен дәлелдемелерді бөлек қабылдау қажет.",
    local:"Тек жергілікті нобай. Серверге жүктеу, бекітілген дереккөз немесе диагностика емес.",
    field:"Өріс коды",type:"Түрі",cancel:"Алдын ала қарауды жабу",empty:"Толтырылмаған"
  },
  en:{
    title:"Financial forms catalog — typed data entry",
    subtitle:"166 codes · 122 typed plus 32 specialized draft candidates · 12 document evidence sources",
    form:"Form",project:"Project code (local)",locale:"Template language",
    periodType:"Period type",period:"Period",coverage:"Value coverage",
    currency:"Currency",scale:"Scale",download:"Download Excel",
    import:"Import Excel",check:"Validate entries",add:"Add row",
    remove:"Remove",apply:"Copy values to editor",
    save:"Download draft JSON",row:"Row",status:"Validation",
    before:"Data preview",source:"Source",error:"Error",
    ready:"Structure validated — financial methodology is NOT approved",
    blocked:"No approved machine-readable field schema for this form. Import is blocked.",
    evidence:"Document/external tax: requires a separate secure evidence upload process.",
    local:"Local draft only. NOT a projectId server upload, approved source or financial diagnosis.",
    field:"Field code",type:"Type",cancel:"Close preview",empty:"Not entered"
  }
} as const;

const PERIOD_CODES:Readonly<Record<PeriodType,string>>={
  WEEK:"2026-W46",MONTH:"2026.11",QUARTER:"2026-Q4",
  HALF_YEAR:"2026-H2",YEAR:"2026",AS_OF:"2026-11-16"
};
function fileDownload(blob:Blob,name:string):void{
  const url=URL.createObjectURL(blob);
  const a=document.createElement("a");a.href=url;a.download=name;
  document.body.append(a);a.click();a.remove();
  setTimeout(()=>URL.revokeObjectURL(url),1000);
}
function freshRow():CatalogRow{return {rowId:"ROW-001",cells:{}};}
function formatDisplay(value:string,type:string,locale:CaptureLocale):string{
  if(!value.trim())return "—";
  if(["MONEY","DECIMAL","INTEGER"].includes(type)){
    try{return formatExactDecimal(value,locale);}catch{return value;}
  }
  return value;
}
/** This is an explicitly UNQUALIFIED local draft studio, never a trusted server flow. */
export function CatalogIntakeStudio(){
  const [context,setContext]=useState<CatalogCaptureContext>({
    formCode:"DF-AST-01",projectCode:"",locale:"ru",
    periodType:"MONTH",periodCode:"2026.11",coverage:"PERIOD_ONLY",
    currency:"KZT",amountScale:1
  });
  const [rows,setRows]=useState<CatalogRow[]>([freshRow()]);
  const [preview,setPreview]=useState<CatalogPreview|null>(null);
  const [previewSource,setPreviewSource]=useState<"MANUAL"|"XLSX">("MANUAL");
  const [message,setMessage]=useState("");
  const [error,setError]=useState("");
  const [busy,setBusy]=useState(false);
  const [evidenceDraft,setEvidenceDraft]=useState<EvidenceDraft|null>(null);
  const generation=useRef(0);
  const form=getCaptureForm(context.formCode);
  const candidate=SPECIALIZED_CANDIDATES.find(f=>f.code===form.code);
  const fields=candidate?.fields??form.fields;
  const labels=LOCALIZED[context.locale];
  const typed=form.captureMode==="TYPED_DRAFT"||!!candidate;
  function resetPreview(){generation.current++;setBusy(false);setPreview(null);setError("");setMessage("");}
  function changeContext(partial:Partial<CatalogCaptureContext>){
    resetPreview();
    setContext(old=>({...old,...partial}));
    setEvidenceDraft(null);
    if(partial.formCode!==undefined)setRows([freshRow()]);
  }
  function changeRow(index:number,next:CatalogRow){
    resetPreview();setRows(previous=>previous.map((r,i)=>i===index?next:r));
  }
  function addRow(){
    if(rows.length>=MAX_CATALOG_ROWS)return;
    const used=new Set(rows.map(r=>r.rowId));
    let next=1;
    while(used.has("ROW-"+String(next).padStart(3,"0")))next++;
    resetPreview();setRows(previous=>[...previous,{rowId:"ROW-"+String(next).padStart(3,"0"),cells:{}}]);
  }
  async function onTemplate(){
    setBusy(true);setError("");
    try{
      const blob=await generateTypedCatalogExcel(context);
      fileDownload(blob,"FIN_"+context.formCode+"_"+context.periodCode+"_LOCAL.xlsx");
    }catch(e){setError(e instanceof Error?e.message:"CATALOG_TEMPLATE_FAILED");}
    finally{setBusy(false);}
  }
  async function onImport(e:ChangeEvent<HTMLInputElement>){
    const file=e.target.files?.[0];e.target.value="";
    if(!file)return;
    if(!file.name.toLowerCase().endsWith(".xlsx")||file.size>2_000_000){
      setError("CATALOG_XLSX_SIZE_OR_EXTENSION_INVALID");return;
    }
    const request=++generation.current;
    const captured={...context};
    setBusy(true);setError("");setMessage("");
    try{
      const parsed=await parseTypedCatalogExcel(await file.arrayBuffer(),captured);
      if(generation.current!==request){setError("CATALOG_IMPORT_CONTEXT_CHANGED");return;}
      setPreview(parsed);setPreviewSource("XLSX");
    }catch(e){
      if(generation.current===request)setError(e instanceof Error?e.message:"CATALOG_XLSX_INVALID");
    }finally{if(generation.current===request)setBusy(false);}
  }
  async function inspectEvidence(e:ChangeEvent<HTMLInputElement>){
    const file=e.target.files?.[0];e.target.value="";
    if(!file)return;
    // Do not read an oversized document into memory before checking its size.
    if(file.size===0||file.size>MAX_EVIDENCE_BYTES){
      setError("EVIDENCE_SIZE_REJECTED");return;
    }
    const request=++generation.current;
    const expectedForm=context.formCode;
    setError("");setEvidenceDraft(null);setBusy(true);
    try{
      const evidence=await inspectLocalEvidence(expectedForm,file.name,
        new Uint8Array(await file.arrayBuffer()));
      if(generation.current===request)setEvidenceDraft(evidence);
    }catch(e){
      if(generation.current===request)
        setError(e instanceof Error?e.message:"EVIDENCE_LOCAL_INVALID");
    }finally{if(generation.current===request)setBusy(false);}
  }
  function validateManual(){
    setError("");setMessage("");
    try{
      const found=candidate?validateSpecializedCandidate(form.code,rows,context.locale):null;
      const coordinateFindings=validateCatalogContext(context);
      setPreview(found?{
        status:"UNQUALIFIED_DRAFT",publishable:false,context,
        rows:found.rows,findings:[...coordinateFindings,...found.findings],
        valid:coordinateFindings.length===0&&found.acceptedAsUnqualifiedDraft
      }:validateCatalogCapture(context,rows));
      setPreviewSource("MANUAL");
    }
    catch(e){setError(e instanceof Error?e.message:"CATALOG_PREVIEW_FAILED");}
  }
  function saveDraft(){
    if(!preview?.valid)return;
    fileDownload(new Blob([JSON.stringify({
      format:"fin.catalog.typed-draft/1",qualification:"UNQUALIFIED_DRAFT",
      persistence:"LOCAL_JSON_ONLY",status:"NOT_APPROVED",
      context:preview.context,formCode:preview.context.formCode,rows:preview.rows
    },null,2)],{type:"application/json"}),"FIN_"+context.formCode+"_UNQUALIFIED_DRAFT.json");
    setMessage(labels.local);
  }
  const findingsByRow=new Map<string,number>();
  for(const finding of preview?.findings??[]){
    const id=finding.rowId??"FORM";
    findingsByRow.set(id,(findingsByRow.get(id)??0)+1);
  }
  return <section className="panel fin-catalog-studio" id="catalog-intake-r6" aria-label={labels.title}>
    <div>
      <span className="eyebrow">FIN R6 / TYPED SOURCE DRAFTS</span>
      <h2>{labels.title}</h2>
      <p>{labels.subtitle}</p>
      <p className="fin-catalog-warning">{labels.local}</p>
    </div>
    <div className="fin-catalog-controls">
      <label>{labels.form}
        <select aria-label={labels.form} value={context.formCode}
          onChange={e=>changeContext({formCode:e.target.value})}>
          {CAPTURE_CATALOG.map(item=><option key={item.code} value={item.code}>
            {item.code} · {item.captureMode==="TYPED_DRAFT"?"TYPED":SPECIALIZED_CANDIDATES.some(s=>s.code===item.code)?"SPECIALIZED_CANDIDATE":item.captureMode}
          </option>)}
        </select>
      </label>
      <label>{labels.project}
        <input aria-label={labels.project} value={context.projectCode}
          onChange={e=>changeContext({projectCode:e.target.value})} maxLength={50}/>
      </label>
      <label>{labels.locale}
        <select aria-label={labels.locale} value={context.locale}
          onChange={e=>changeContext({locale:e.target.value as CaptureLocale})}>
          {CAPTURE_LOCALES.map(lang=><option key={lang} value={lang}>{lang.toUpperCase()}</option>)}
        </select>
      </label>
      <label>{labels.periodType}
        <select aria-label={labels.periodType} value={context.periodType} onChange={e=>{
          const value=e.target.value as PeriodType;
          changeContext({periodType:value,periodCode:PERIOD_CODES[value],
            coverage:value==="AS_OF"?"AS_OF":"PERIOD_ONLY"});
        }}>
          {Object.keys(PERIOD_CODES).map(type=><option key={type} value={type}>{type}</option>)}
        </select>
      </label>
      <label>{labels.period}
        <input aria-label={labels.period} value={context.periodCode}
          onChange={e=>changeContext({periodCode:e.target.value})}/>
      </label>
      <label>{labels.coverage}
        <select aria-label={labels.coverage} value={context.coverage}
          onChange={e=>changeContext({coverage:e.target.value as ValueCoverage})}>
          {(context.periodType==="AS_OF"?["AS_OF"]:["PERIOD_ONLY","YEAR_TO_DATE","LIFE_TO_DATE"]).map(x=>
            <option key={x} value={x}>{x}</option>)}
        </select>
      </label>
      <label>{labels.currency}
        <input aria-label={labels.currency} value={context.currency} readOnly/>
      </label>
      <label>{labels.scale}
        <select aria-label={labels.scale} value={context.amountScale}
          onChange={e=>changeContext({amountScale:Number(e.target.value) as 1|1000|1000000})}>
          {[1,1000,1000000].map(v=><option key={v} value={v}>{v}</option>)}
        </select>
      </label>
    </div>
    <p className="fin-catalog-context">
      {form.code} · {form.kind} · {form.aggregateRoot} · {form.grain} · {form.qualification}
    </p>
    {!typed?<div>
      <p className="fin-catalog-blocked" role="alert">
        {form.captureMode==="EVIDENCE_ONLY"?labels.evidence:labels.blocked}
      </p>
      {form.captureMode==="EVIDENCE_ONLY"&&<div className="fin-catalog-preview">
        <p>{labels.local}</p>
        <label className="fin-catalog-import">
          {context.locale==="ru"?"Проверить документ локально":context.locale==="kk"?"Құжатты жергілікті тексеру":"Inspect document locally"}
          <input type="file" aria-label="Evidence source file"
             accept=".pdf,.xlsx,.docx,.png,.jpg,.jpeg,.xml"
             disabled={busy} onChange={inspectEvidence}/>
        </label>
        {evidenceDraft&&<div className="fin-catalog-context" data-testid="fin-evidence-local">
          <p>{evidenceDraft.formCode} · {evidenceDraft.filename} · {evidenceDraft.byteLength} bytes</p>
          <p>SHA-256: <code>{evidenceDraft.sha256}</code></p>
          <p>{evidenceDraft.status} · {evidenceDraft.sourceScan} · NOT STORED ON SERVER</p>
        </div>}
      </div>}
    </div>:<>
      <div className="fin-catalog-table-wrap">
        <table className="fin-catalog-table">
          <caption>{form.code} · {labels.field} / {labels.type}</caption>
          <thead><tr>
            <th scope="col">{labels.row}</th>
            {fields.map(field=><th key={field.code} scope="col">
              <span>{fieldPresentation(form.code,field,context.locale).label}</span><small>{field.type} · {field.code}</small>
            </th>)}
            <th scope="col">{labels.status}</th>
          </tr></thead>
          <tbody>{rows.map((row,i)=><tr key={i}>
            <th scope="row">
              <input aria-label={labels.row+" "+(i+1)} value={row.rowId}
                onChange={e=>changeRow(i,{...row,rowId:e.target.value})}/>
            </th>
            {fields.map(field=><td key={field.code}
              className={["MONEY","DECIMAL","INTEGER"].includes(field.type)?"fin-catalog-number":"fin-catalog-text"}>
              <input aria-label={row.rowId+" "+field.code} value={row.cells[field.code]??""}
                onChange={e=>changeRow(i,{...row,cells:{...row.cells,[field.code]:e.target.value}})}
                placeholder={field.type}/>
            </td>)}
            <td><button type="button" className="button ghost"
              onClick={()=>{resetPreview();setRows(r=>r.filter((_,j)=>j!==i));}}
              aria-label={labels.remove+" "+row.rowId}>{labels.remove}</button></td>
          </tr>)}</tbody>
        </table>
      </div>
      <div className="action-row fin-catalog-actions">
        <button type="button" className="button secondary" onClick={addRow}
          disabled={busy||rows.length>=MAX_CATALOG_ROWS}>{labels.add}</button>
        <button type="button" className="button secondary" onClick={onTemplate} disabled={busy}>{labels.download}</button>
        <label className="fin-catalog-import">{labels.import}
          <input aria-label={labels.import} type="file" accept=".xlsx" onChange={onImport}
            disabled={busy}/>
        </label>
        <button type="button" className="button primary" disabled={busy} onClick={validateManual}>{labels.check}</button>
      </div>
    </>}
    {error&&<p className="fin-catalog-error" role="alert">{labels.error}: {error}</p>}
    {message&&<p className="fin-catalog-context" role="status">{message}</p>}
    {preview&&<section className="fin-catalog-preview" aria-label={labels.before}>
      <h3>{labels.before} · {preview.context.formCode} · {preview.status}</h3>
      <p className="fin-catalog-warning">{labels.ready}</p>
      <p>{labels.source}: {previewSource} · {preview.findings.length} {labels.error.toLowerCase()}</p>
      <div className="fin-catalog-table-wrap"><table className="fin-catalog-table">
        <thead><tr><th>{labels.row}</th>
          {(SPECIALIZED_CANDIDATES.find(s=>s.code===preview.context.formCode)?.fields??getCaptureForm(preview.context.formCode).fields).map(f=><th key={f.code}>{fieldPresentation(preview.context.formCode,f,context.locale).label}</th>)}
          <th>{labels.status}</th>
        </tr></thead>
        <tbody>{preview.rows.map((row,i)=><tr key={i}>
          <td className="fin-catalog-text">{row.rowId}</td>
          {(SPECIALIZED_CANDIDATES.find(s=>s.code===preview.context.formCode)?.fields??getCaptureForm(preview.context.formCode).fields).map(field=><td
            key={field.code}
            className={["MONEY","DECIMAL","INTEGER"].includes(field.type)?"fin-catalog-number":"fin-catalog-text"}>
            {formatDisplay(row.cells[field.code]??"",field.type,context.locale)}
          </td>)}
          <td>{findingsByRow.get(row.rowId)??0}</td>
        </tr>)}</tbody>
      </table></div>
      {preview.findings.length>0&&<ul className="fin-catalog-errors">
        {preview.findings.map((f,i)=><li key={i}>
          {f.code} · {f.rowId??"FORM"} · {f.fieldCode??""} · {f.sourceCell??""}
        </li>)}
      </ul>}
      <div className="action-row">
        {previewSource==="XLSX"&&<button type="button" className="button secondary"
          onClick={()=>{setRows(preview.rows.map(r=>({...r,cells:{...r.cells}})));
            setPreview(null);setPreviewSource("MANUAL");}}>{labels.apply}</button>}
        <button type="button" className="button secondary" onClick={()=>setPreview(null)}>{labels.cancel}</button>
        <button type="button" className="button primary" disabled={!preview.valid}
          onClick={saveDraft}>{labels.save}</button>
      </div>
    </section>}
  </section>;
}
