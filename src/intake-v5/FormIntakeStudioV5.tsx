import { useState, type ChangeEvent, type KeyboardEvent } from "react";
import {
  PILOT_F02_SCHEMA, type ActiveLocale, type EntryContext, type EntryRow,
  type ValidatedEntry, formatExactDecimal, validateF02
} from "./form-schema";
import { resolvePeriod } from "./reporting-period";
import { generateF02ExcelV5, parseF02ExcelV5 } from "./excel-v5";

const UI: Record<ActiveLocale, {
  title:string;sub:string;project:string;language:string;period:string;scale:string;
  download:string;upload:string;check:string;save:string;
  code:string;indicator:string;amount:string;currency:string;status:string;
  preview:string;missing:string;valid:string;invalid:string;local:string
}> = {
  ru: {title:"Универсальный ввод финансовой формы",sub:"Пилот F02 · одна схема для Excel и React",
    project:"Код проекта (локальный черновик)",language:"Язык шаблона",period:"Отчётный месяц",
    scale:"Масштаб сумм",download:"Скачать Excel",upload:"Загрузить Excel",
    check:"Проверить и показать Preview",save:"Скачать локальный черновик JSON",
    code:"Код показателя",indicator:"Наименование",amount:"Значение",
    currency:"Валюта",status:"Проверка",preview:"Предпросмотр данных",
    missing:"Не заполнено",valid:"Проверено",invalid:"Требуется исправление",
    local:"Локальный UX-пилот. Не является серверной загрузкой, утверждением или публикацией фактов."},
  kk: {title:"Қаржылық нысанды бірыңғай енгізу",sub:"F02 пилоты · Excel және React үшін бір схема",
    project:"Жоба коды (жергілікті нобай)",language:"Үлгі тілі",period:"Есепті ай",
    scale:"Сома масштабы",download:"Excel жүктеп алу",upload:"Excel жүктеу",
    check:"Тексеру және алдын ала қарау",save:"Жергілікті JSON нобайын жүктеп алу",
    code:"Көрсеткіш коды",indicator:"Атауы",amount:"Мәні",
    currency:"Валюта",status:"Тексеру",preview:"Деректерді алдын ала қарау",
    missing:"Толтырылмаған",valid:"Тексерілді",invalid:"Түзету қажет",
    local:"Жергілікті интерфейс пилоты. Серверге жүктеу, бекіту немесе қаржылық деректерді жариялау емес."},
  en: {title:"Financial form data entry",sub:"F02 pilot · one schema for Excel and React",
    project:"Project code (local draft)",language:"Template language",period:"Reporting month",
    scale:"Amount scale",download:"Download Excel",upload:"Import Excel",
    check:"Validate and preview",save:"Download local draft JSON",
    code:"Indicator code",indicator:"Indicator",amount:"Amount",
    currency:"Currency",status:"Validation",preview:"Data preview",
    missing:"Not entered",valid:"Validated",invalid:"Corrections required",
    local:"Local UI pilot. This is not a server submission, independent approval or published financial data."}
};
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
  const problems=new Map(findings.filter(x=>x.fieldCode).map(f=>[f.fieldCode!,f.code]));
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
          <td className="fin-v5-text">{invalid??(row.rawValue?labels.valid:labels.missing)}</td>
        </tr>;
      })}</tbody>
    </table></div>
    {findings.length>0 && <ul aria-label="Validation findings" className="fin-v5-issues">
      {findings.map((finding,index)=><li key={index}>{finding.fieldCode??"FORM"}: {finding.code}</li>)}
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
  const labels=UI[context.locale];
  const period=/^\d{4}\.(0[1-9]|1[0-2])$/.test(context.periodCode)
    ? resolvePeriod("MONTH",context.periodCode):null;
  function updateContext(partial:Partial<EntryContext>):void {
    setContext(current=>({...current,...partial}));setPreview(null);setError("");
  }
  function updateRows(next:EntryRow[]):void {
    setRows(next);setPreview(null);setError("");
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
    setBusy(true);setError("");
    try{
      const next=await parseF02ExcelV5(await file.arrayBuffer(),context);
      setRows(next);setPreview(validateF02(context,next));
    }catch(e){setError(e instanceof Error?e.message:"XLSX_IMPORT_ERROR");}
    finally{setBusy(false);}
  }
  function saveLocalDraft():void{
    if(!preview?.valid)return;
    download(new Blob([JSON.stringify({
      schema:"fin.v5.local-draft/1",status:"LOCAL_UNVERIFIED_DRAFT",
      formVersion:PILOT_F02_SCHEMA.version,context:preview.context,rows:preview.rows
    },null,2)],{type:"application/json"}),"FIN_F02_LOCAL_UNVERIFIED_DRAFT.json");
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
        onClick={()=>{setPreview(validateF02(context,rows));setError("");}}>{labels.check}</button>
    </div>
    {error && <p role="alert" className="fin-v5-error">{error}</p>}
    {preview && <>
      <FormPreviewTable entry={preview}/>
      <div className="action-row fin-v5-actions">
        <button type="button" className="button secondary" disabled={!preview.valid}
          onClick={saveLocalDraft}>{labels.save}</button>
      </div>
    </>}
  </section>;
}
