import { useEffect,useMemo,useRef,useState } from "react";
import type { User } from "oidc-client-ts";
import { configuredFinOidc,finUserManager,finApi,loadFinUser,
  tokenOrganizationHints,type FinOidcConfig } from "./fin-oidc";

const FIELDS:Record<string,readonly string[]>={
  F01:["assets","liabilities","equity","cash","receivables"],
  F02:["netRevenue","cogs","operatingExpenses"],
  F03:["operatingCash","investingCash","financingCash"],
  "DF-CSH-01":["receipt"],"DF-CSH-02":["payment"]
};
const LABELS:Record<string,string>={
  assets:"Активы",liabilities:"Обязательства",equity:"Капитал",cash:"Деньги",
  receivables:"Дебиторская задолженность",netRevenue:"Чистая выручка",
  cogs:"Себестоимость",operatingExpenses:"Операционные расходы",
  operatingCash:"Операционный денежный поток",investingCash:"Инвестиционный поток",
  financingCash:"Финансовый поток",receipt:"Поступление",payment:"Платёж"
};
type FormCode=keyof typeof FIELDS;
interface SourceCell {
  sourceRowId:string;sourceField:string;period:string;enteredAmount:string;
}
interface SubmissionInfo {
  id:string;formCode:string;reviewStatus:string;submittedBy:string;
  reviewBy:string|null;reviewReason:string|null;
  sourceKind:string;sourceDocumentHash:string;cells:SourceCell[];
}
interface Published {revisionId:string;snapshotHash:string;currentVersion:number}
interface Finding {
  ruleCode:string;relationType:string;verdict:string;reasonCode:string;
  qualification:string;computed:Record<string,string>;
  missing:Array<{metricCode:string;role:string}>;
  sources:Array<{formCode:string;sourceRevision:string;sourceField:string;rowId:string;factId:string}>;
}
interface ActualAssessment {
  id:string;factSetRevisionId:string;freshness:string;
  snapshotHash:string;snapshot:{
    qualification:string;month:string;currency:string;
    relationshipRegistryHash:string;
    findings:Finding[];
    nextEvidencePlan?:{nextForms:Array<{formCode:string;action:string}>};
  };
}
const UUID_PATTERN=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DECIMAL_PATTERN=/^-?[0-9]{1,26}(\.[0-9]{1,12})?$/;
function newRows(form:string,month:string):SourceCell[]{
  return (FIELDS[form]??[]).map((field,i)=>({
    sourceRowId:form==="F01"?"balance":form==="F02"?"pnl":form==="F03"?"cf":
      (form==="DF-CSH-01"?"receipt":"payment")+(i+1),
    sourceField:field,period:month,enteredAmount:""
  }));
}
function safeError(error:unknown):string{
  if(error instanceof Error){
    if(/^[A-Z0-9_]{3,100}$/.test(error.message))return error.message;
    return "Операция отклонена. Проверьте конфигурацию или данные.";
  }
  return "Не удалось выполнить операцию.";
}
function download(blob:Blob,name:string):void{
  const url=URL.createObjectURL(blob);
  const link=document.createElement("a");link.href=url;link.download=name;
  document.body.appendChild(link);link.click();link.remove();
  setTimeout(()=>URL.revokeObjectURL(url),1000);
}

export function TrustedFinanceWorkspace(){
  const setup=useMemo(()=>{
    try{return {config:configuredFinOidc(),error:""};}
    catch(e){return {config:null,error:safeError(e)};}
  },[]);
  const config=setup.config;
  const [user,setUser]=useState<User|null>(null);
  const [authError,setAuthError]=useState("");
  const [busy,setBusy]=useState(false);
  const [message,setMessage]=useState("");
  const [error,setError]=useState("");
  const [orgId,setOrgId]=useState("");
  const [entityId,setEntityId]=useState("");
  const [scopeId,setScopeId]=useState("");
  const [form,setForm]=useState<FormCode>("F02");
  const [month,setMonth]=useState("2026-10");
  const [currency,setCurrency]=useState("KZT");
  const [scale,setScale]=useState(1);
  const [cells,setCells]=useState<SourceCell[]>(()=>newRows("F02","2026-10"));
  const [selectedFile,setSelectedFile]=useState<File|null>(null);
  const [submissionId,setSubmissionId]=useState("");
  const [submission,setSubmission]=useState<SubmissionInfo|null>(null);
  const [reason,setReason]=useState("");
  const [sourceIds,setSourceIds]=useState("");
  const [published,setPublished]=useState<Published|null>(null);
  const [assessment,setAssessment]=useState<ActualAssessment|null>(null);
  const callbackInitialized=useRef(false);
  useEffect(()=>{
    if(!config || callbackInitialized.current)return;
    callbackInitialized.current=true;
    const client=finUserManager(config);
    void loadFinUser(client).then(u=>setUser(u))
      .catch(e=>setAuthError(safeError(e)));
  },[config]);
  const grants=useMemo(()=>user?.access_token
    ?tokenOrganizationHints(user.access_token):[],[user]);
  const accessible=grants.some(x=>x.organizationId===orgId);
  useEffect(()=>{
    if(grants.length && !grants.some(x=>x.organizationId===orgId))
      setOrgId(grants[0]!.organizationId);
  },[grants,orgId]);
  const allowed=!!config && !!user && !user.expired && accessible;
  function validateScope():void{
    if(!UUID_PATTERN.test(orgId)||!UUID_PATTERN.test(entityId)||!UUID_PATTERN.test(scopeId))
      throw new Error("FIN_ENTITY_SCOPE_UUID_REQUIRED");
    if(!/^\d{4}-(0[1-9]|1[0-2])$/.test(month))
      throw new Error("FIN_REPORT_MONTH_INVALID");
  }
  async function execute(action:()=>Promise<void>):Promise<void>{
    setError("");setMessage("");setBusy(true);
    try{await action();}
    catch(e){setError(safeError(e));}
    finally{setBusy(false);}
  }
  async function api<T>(resource:string,init:RequestInit={}):Promise<T>{
    if(!config||!user||!allowed)throw new Error("FIN_LOGIN_AND_ORGANIZATION_REQUIRED");
    return finApi<T>(config,user,orgId,resource,init);
  }
  function switchForm(next:FormCode){
    if(cells.some(c=>c.enteredAmount.trim()) &&
       !window.confirm("Несохранённый черновик будет очищен. Продолжить?"))return;
    setForm(next);setCells(newRows(next,month));
  }
  async function submitManual(){
    validateScope();
    const chosen=cells.filter(c=>c.enteredAmount.trim());
    if(chosen.some(c=>!DECIMAL_PATTERN.test(c.enteredAmount.trim())))
      throw new Error("FIN_DECIMAL_FORMAT_INVALID");
    if(chosen.length===0)throw new Error("FIN_FINANCIAL_CELLS_REQUIRED");
    const result=await api<{id:string;state:string}>("/submissions",{
      method:"POST",body:JSON.stringify({
        economicEntityId:entityId,businessScopeId:scopeId,formCode:form,
        reportingBasis:"MANAGEMENT",currencyCode:currency,amountScale:scale,
        cells:chosen
      })
    });
    setSubmissionId(result.id);setSubmission(null);setPublished(null);setAssessment(null);
    setMessage("Заявка "+result.id+" зарегистрирована. Требуется другой пользователь с FIN_REVIEW.");
  }
  async function downloadTemplate(){
    validateScope();
    const params=new URLSearchParams({
      economicEntityId:entityId,businessScopeId:scopeId,
      reportingBasis:"MANAGEMENT",currencyCode:currency,amountScale:String(scale)
    });
    const blob=await api<Blob>("/templates/"+form+"?"+params.toString());
    download(blob,"FIN-"+form+"-FIN-PILOT-V1.xlsx");
    setMessage("Шаблон загружен с сервера. После заполнения файл должен пройти серверную проверку.");
  }
  async function submitXlsx(){
    validateScope();
    if(!selectedFile || !selectedFile.name.toLowerCase().endsWith(".xlsx") ||
        selectedFile.size>2_000_000)throw new Error("FIN_XLSX_REQUIRED_MAX_2MB");
    const body=new FormData();
    body.append("file",selectedFile);
    body.append("economicEntityId",entityId);
    body.append("businessScopeId",scopeId);
    body.append("formCode",form);
    body.append("reportingBasis","MANAGEMENT");
    body.append("currencyCode",currency);
    body.append("amountScale",String(scale));
    const result=await api<{id:string}>("/submissions/xlsx",{method:"POST",body});
    setSubmissionId(result.id);setSubmission(null);setPublished(null);setAssessment(null);
    setMessage("Исходный XLSX сохранён с SHA-256. Заявка "+result.id+" ожидает независимой проверки.");
  }
  async function readSubmission(){
    if(!UUID_PATTERN.test(submissionId))throw new Error("FIN_SOURCE_REVISION_UUID_REQUIRED");
    setSubmission(await api<SubmissionInfo>("/submissions/"+submissionId));
  }
  async function reviewSource(approved:boolean){
    if(!UUID_PATTERN.test(submissionId))throw new Error("FIN_SOURCE_REVISION_UUID_REQUIRED");
    if(!reason.trim())throw new Error("FIN_REVIEW_REASON_REQUIRED");
    await api("/submissions/"+submissionId+"/review",{
      method:"POST",body:JSON.stringify({approved,reason:reason.trim()})
    });
    await readSubmission();
    if(approved && !sourceIds.split(/[\s,;]+/).includes(submissionId))
      setSourceIds(previous=>[previous.trim(),submissionId].filter(Boolean).join(", "));
    setMessage(approved?"Источник утверждён. Добавлен к кандидату на публикацию.":"Источник отклонён.");
  }
  async function publishFactSet(){
    validateScope();
    const ids=sourceIds.split(/[\s,;]+/).filter(Boolean);
    if(!ids.length || ids.some(id=>!UUID_PATTERN.test(id)))
      throw new Error("FIN_APPROVED_SOURCE_IDS_REQUIRED");
    const result=await api<Published>("/fact-sets",{
      method:"POST",body:JSON.stringify({
        economicEntityId:entityId,businessScopeId:scopeId,
        reportingBasis:"MANAGEMENT",approvedSourceIds:ids,
        expectedCurrentRevisionId:published?.revisionId??null
      })
    });
    setPublished(result);setAssessment(null);
    setMessage("Набор реальных фактов опубликован. Диагностика остаётся квалификационно ограниченной.");
  }
  async function diagnoseActual(){
    if(!published)throw new Error("FIN_FACT_SET_REQUIRED");
    const result=await api<ActualAssessment>("/fact-sets/"+
      published.revisionId+"/assessments/"+month,{method:"POST"});
    setAssessment(result);
    setMessage("Получен предварительный снимок с источниками. Не является утверждённым финансовым заключением.");
  }
  return <section className="panel trusted-workspace" id="trusted-finance"
      aria-label="Доверенные финансовые источники">
    <div className="panel-heading"><div>
      <span className="eyebrow">06 / SOURCE APPROVAL</span>
      <h2>Финансовые источники и независимая проверка</h2></div>
      <span className="pill">ACTUAL · PRODUCTION HOLD</span>
    </div>
    <p className="graph-disclaimer">Ни локально подтверждённые балансы, ни демонстрационные данные
      не становятся реальными утверждёнными фактами автоматически. Для этого нужны
      Keycloak, серверная проверка источника и второй пользователь-проверяющий.
      Диагностика реальных фактов остаётся предварительной до финансового S2.</p>
    {setup.error && <p role="alert" className="sector-error">{setup.error}</p>}
    {!config ? <p className="sector-unavailable" role="status">
      Подключение к защищённому API отключено. Нужны конфигурации
      VITE_FIN_OIDC_ISSUER, VITE_FIN_OIDC_CLIENT_ID и VITE_FIN_API_BASE,
      а также соответствующие настройки Keycloak и FIN backend. Здесь нет
      демонстрационных «успешных» публикаций.
    </p> : <>
      <div className="action-row">
        {user && !user.expired ? <>
          <span className="graph-small">OIDC: {user.profile.preferred_username ??
            user.profile.sub ?? "authenticated"}</span>
          <button type="button" className="button ghost"
            onClick={()=>void finUserManager(config).signoutRedirect()}>Выйти</button>
        </> : <button type="button" className="button primary"
          onClick={()=>void finUserManager(config).signinRedirect()}>Войти через Keycloak</button>}
      </div>
      {authError && <p role="alert" className="sector-error">{authError}</p>}
      {user && !user.expired && <>
        <div className="graph-controls">
          <label className="field">Организация из JWT
            <select aria-label="Организация FIN" value={orgId}
              onChange={e=>setOrgId(e.target.value)}>
              {grants.map(x=><option key={x.organizationId}
                value={x.organizationId}>{x.organizationId}</option>)}
            </select></label>
          <label className="field">Economic Entity ID
            <input aria-label="Economic Entity ID" value={entityId}
              placeholder="UUID" onChange={e=>setEntityId(e.target.value)}/></label>
          <label className="field">Business Scope ID
            <input aria-label="Business Scope ID" value={scopeId}
              placeholder="UUID" onChange={e=>setScopeId(e.target.value)}/></label>
          <label className="field">Отчётный месяц
            <input aria-label="Месяц доверенного источника" type="month" value={month}
              onChange={e=>{setMonth(e.target.value);setCells(newRows(form,e.target.value));}}/></label>
        </div>
        <div className="graph-controls">
          <label className="field">Форма
            <select aria-label="Форма доверенного источника" value={form}
              onChange={e=>switchForm(e.target.value as FormCode)}>
              {Object.keys(FIELDS).map(x=><option key={x}>{x}</option>)}
            </select></label>
          <label className="field">Валюта
            <select aria-label="Валюта доверенной формы" value={currency}
              onChange={e=>setCurrency(e.target.value)}>
              <option value="KZT">KZT</option><option value="USD">USD</option>
              <option value="EUR">EUR</option>
            </select></label>
          <label className="field">Масштаб
            <select aria-label="Масштаб суммы" value={scale}
              onChange={e=>setScale(Number(e.target.value))}>
              <option value={1}>Единицы валюты</option>
              <option value={1000}>Тысячи</option>
            </select></label>
        </div>
        <h3 className="graph-subtitle">1. Ввод и проверка данных</h3>
        <div className="table-scroll"><table className="data-table">
          <thead><tr><th>Строка источника</th><th>Показатель</th>
            <th>Период</th><th>Сумма</th></tr></thead>
          <tbody>{cells.map((cell,i)=><tr key={cell.sourceField+i}>
            <td><input aria-label={"Строка "+(i+1)} value={cell.sourceRowId}
              onChange={e=>setCells(old=>old.map((r,n)=>n===i?{...r,sourceRowId:e.target.value}:r))}/></td>
            <td>{LABELS[cell.sourceField]??cell.sourceField}<small> · {cell.sourceField}</small></td>
            <td><input aria-label={"Период "+cell.sourceField} type="month"
              value={cell.period} onChange={e=>setCells(old=>old.map((r,n)=>
                n===i?{...r,period:e.target.value}:r))}/></td>
            <td><input aria-label={"Сумма "+cell.sourceField} inputMode="decimal"
              placeholder="— (не ноль)" value={cell.enteredAmount}
              onChange={e=>setCells(old=>old.map((r,n)=>
                n===i?{...r,enteredAmount:e.target.value}:r))}/></td>
          </tr>)}</tbody></table></div>
        {form.startsWith("DF-CSH-") &&
          <button type="button" className="button ghost" onClick={()=>{
            const next=cells.length+1;
            setCells(old=>[...old,{sourceRowId:(form==="DF-CSH-01"?"receipt":"payment")+next,
              sourceField:form==="DF-CSH-01"?"receipt":"payment",period:month,enteredAmount:""}]);
          }}>Добавить строку расшифровки</button>}
        <div className="action-row">
          <button type="button" className="button primary" disabled={!allowed||busy}
            onClick={()=>void execute(submitManual)}>Передать ручной ввод на проверку</button>
          <button type="button" className="button secondary" disabled={!allowed||busy}
            onClick={()=>void execute(downloadTemplate)}>Скачать серверный XLSX</button>
        </div>
        <div className="action-row">
          <label className="field">XLSX из шаблона FIN
            <input type="file" aria-label="Исходный серверный XLSX" accept=".xlsx"
              onChange={e=>setSelectedFile(e.target.files?.[0]??null)}/></label>
          <button type="button" className="button secondary" disabled={!allowed||busy||!selectedFile}
            onClick={()=>void execute(submitXlsx)}>Загрузить XLSX на сервер</button>
        </div>
        <h3 className="graph-subtitle">2. Независимое рассмотрение (FIN_REVIEW)</h3>
        <div className="action-row">
          <label className="field">Source Revision ID
            <input aria-label="Source Revision ID" value={submissionId}
              onChange={e=>setSubmissionId(e.target.value)} placeholder="UUID заявки"/></label>
          <button type="button" className="button secondary" disabled={busy||!allowed}
            onClick={()=>void execute(readSubmission)}>Получить заявку</button>
        </div>
        {submission && <div className="graph-study" aria-label="Состояние проверки">
          <strong>{submission.formCode}: {submission.reviewStatus}</strong>
          <p className="graph-small">Автор: {submission.submittedBy}. Тип: {submission.sourceKind}.
            SHA-256 источника: {submission.sourceDocumentHash}</p>
          <p className="graph-small">Проверяющий: {submission.reviewBy??"не назначен"}.
            Причина: {submission.reviewReason??"ожидает проверки"}</p>
          <ul>{submission.cells.map((c,i)=><li key={i}>{c.period} · {c.sourceRowId} ·
            {c.sourceField}: {c.enteredAmount}</li>)}</ul>
        </div>}
        <div className="action-row">
          <label className="field">Основание решения
            <input aria-label="Основание финансовой проверки" value={reason}
              onChange={e=>setReason(e.target.value)} maxLength={1000}/></label>
          <button className="button primary" type="button" disabled={!allowed||busy}
            onClick={()=>void execute(()=>reviewSource(true))}>Утвердить источники</button>
          <button className="button secondary" type="button" disabled={!allowed||busy}
            onClick={()=>void execute(()=>reviewSource(false))}>Отклонить</button>
        </div>
        <h3 className="graph-subtitle">3. Публикация реальных фактов (FIN_PUBLISH)</h3>
        <label className="field">Одобренные Source Revision ID (через запятую)
          <textarea aria-label="Список утверждённых источников" value={sourceIds}
            onChange={e=>setSourceIds(e.target.value)} rows={2}/></label>
        <div className="action-row">
          <button className="button primary" type="button" disabled={!allowed||busy}
            onClick={()=>void execute(publishFactSet)}>Опубликовать ACTUAL FactSetRevision</button>
        </div>
        {published && <p className="graph-disclaimer" role="status">Опубликовано:
          {published.revisionId} · версия {published.currentVersion} · SHA {published.snapshotHash}
        </p>}
        <h3 className="graph-subtitle">4. Межформенные доказательства (FIN_ANALYZE)</h3>
        <div className="action-row">
          <button className="button secondary" type="button"
            disabled={!allowed||busy||!published}
            onClick={()=>void execute(diagnoseActual)}>Проверить межформенные связи</button>
          <span className="graph-small">Требуется FIN_ACTUAL_PREVIEW_ENABLED; финансовый S2 остаётся HOLD.</span>
        </div>
        {assessment && <div aria-label="Объяснимые финансовые выводы">
          <p className="graph-disclaimer">Статус: {assessment.snapshot.qualification};
            исходный FactSet: {assessment.factSetRevisionId}; свежесть: {assessment.freshness}.
            Доказательства закреплены хешем {assessment.snapshotHash}.</p>
          <div className="graph-rule-list">{assessment.snapshot.findings.map(f=>
            <article key={f.ruleCode} className="graph-rule">
              <div className="graph-rule-top"><strong>{f.ruleCode}</strong>
                <span className="pill subtle">{f.verdict}</span></div>
              <small>{f.reasonCode} · {f.qualification}</small>
              <ul>{Object.entries(f.computed).map(([key,value])=>
                <li key={key}>{key}: {value} {assessment.snapshot.currency}</li>)}
                {f.missing.map((m,i)=><li key={i}>Нет доказательства: {m.metricCode} · {m.role}</li>)}
              </ul>
              <details><summary>Происхождение расчёта</summary><ul>
                {f.sources.map((s,i)=><li key={i}>{s.formCode} / {s.sourceField} /
                  {s.rowId} · {s.sourceRevision} · {s.factId}</li>)}
              </ul></details>
            </article>)}</div>
        </div>}
      </>}
    </>}
    {error && <p role="alert" className="sector-error">{error}</p>}
    {message && <p role="status" className="sector-ok">{message}</p>}
  </section>;
}
