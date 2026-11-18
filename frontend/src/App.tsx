import {useEffect,useMemo,useRef,useState} from 'react';
import type {ClipboardEvent,KeyboardEvent,ReactNode} from 'react';
import {Activity,AlertTriangle,Check,ChevronDown,ChevronRight,Download,FilePlus2,FileSpreadsheet,FileUp,FolderTree,History,Eye,LayoutDashboard,LoaderCircle,PanelLeftClose,PanelLeftOpen,RefreshCw,Save,Search,Settings2} from 'lucide-react';
import type {CategoryTree,Currency,Draft,Field,FormDetail,Locale,Navigation,PeriodType,PreviewTicket,Profile,Receipt,Style} from './api';
import {exportXlsx,get,multipart,post} from './api';
import {parseExcelPaste} from './clipboard';
import './styles.css';

const periodExamples:Record<string,string>={D:'2026-11-18',W:'2026-W47',DEC:'2026-11-2',M:'2026-11',Q:'2026-Q4',H:'2026-H2',Y:'2026'};
const languages:Locale[]=['ru','kk','en','zh-Hans'];
type Scope={organizationId:string;economicEntityId:string;businessScopeId:string;projectId:string};
const newScope=():Scope=>({organizationId:crypto.randomUUID(),economicEntityId:crypto.randomUUID(),businessScopeId:crypto.randomUUID(),projectId:crypto.randomUUID()});
const storageKey='fin-local-scope';
function storedScope():Scope{
  try{
    const v=JSON.parse(localStorage.getItem(storageKey)||'null') as Scope|null;
    if(v&&Object.values(v).every(x=>typeof x==='string'&&/^[0-9a-f-]{36}$/i.test(x)))return v;
  }catch{/* use fresh */}
  return newScope();
}
const normalize=(s:string,type:string)=>{
  const v=s.trim().replace(/[\u00a0\u202f]/g,' ');
  if(!['MONEY','DECIMAL','INTEGER'].includes(type))return v;
  if(/^-?\d{1,3}( \d{3})+(?:[.,]\d{1,2})?$/.test(v))return v.replaceAll(' ','').replace(',','.');
  if(/^-?\d+(?:[.,]\d{1,2})?$/.test(v))return v.replace(',','.');
  return v; // invalid and ambiguous financial values are rejected by Backend
};
function Tool({label,onClick,disabled,children}:{label:string;onClick:()=>void;disabled?:boolean;children:ReactNode}){
  return <button className="tool" type="button" title={label} aria-label={label} onClick={onClick} disabled={disabled}>{children}</button>;
}

export default function App(){
  const [language,setLanguage]=useState<Locale>('ru');
  const [style,setStyle]=useState<Style>('EXCEL');
  const [navigation,setNavigation]=useState<Navigation|null>(null);
  const [tree,setTree]=useState<CategoryTree|null>(null);
  const [form,setForm]=useState<FormDetail|null>(null);
  const lastForm=useRef('F02');
  const [currencyOptions,setCurrencyOptions]=useState<Currency[]>([]);
  const [periodOptions,setPeriodOptions]=useState<PeriodType[]>([]);
  const [category,setCategory]=useState('ALL');
  const [formCode,setFormCode]=useState('F02');
  const [search,setSearch]=useState('');
  const [periodType,setPeriodType]=useState('M');
  const [periodCode,setPeriodCode]=useState('2026-11');
  const [currency,setCurrency]=useState('KZT');
  const [scope,setScope]=useState<Scope>(storedScope);
  const [instanceId,setInstanceId]=useState('');
  const [draft,setDraft]=useState<Draft|null>(null);
  const [values,setValues]=useState<Record<string,string>>({});
  const [profileId,setProfileId]=useState('');
  const [preview,setPreview]=useState<PreviewTicket|null>(null);
  const [file,setFile]=useState<File|null>(null);
  const [previous,setPrevious]=useState<Draft|null>(null);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [message,setMessage]=useState('Подключение к Backend...');
  const [sidebar,setSidebar]=useState(true);
  const [dashboard,setDashboard]=useState(true);
  const [expanded,setExpanded]=useState<Record<string,boolean>>({});
  const [showScope,setShowScope]=useState(false);
  const [range,setRange]=useState('');
  const inFlight=useRef(false);
  const saveKeyRef=useRef<string|null>(null);
  const [saveUncertain,setSaveUncertain]=useState(false);
  const [dirty,setDirty]=useState(false);
  const [commitUncertain,setCommitUncertain]=useState(false);

  useEffect(()=>{localStorage.setItem(storageKey,JSON.stringify(scope));},[scope]);
  useEffect(()=>{
    if(!dirty&&!saveUncertain&&!commitUncertain)return;
    const beforeUnload=(event:BeforeUnloadEvent)=>{
      event.preventDefault();
      event.returnValue='';
    };
    window.addEventListener('beforeunload',beforeUnload);
    return()=>window.removeEventListener('beforeunload',beforeUnload);
  },[dirty,saveUncertain,commitUncertain]);
  useEffect(()=>{
    let active=true;
    Promise.all([
      get<Navigation>('/catalog/navigation?locale='+language),
      get<CategoryTree>('/catalog/category-tree?locale='+language),
      get<Currency[]>('/catalog/currencies?locale='+language),
      get<PeriodType[]>('/catalog/period-types?locale='+language)
    ]).then(([nav,t,c,p])=>{
      if(active){setNavigation(nav);setTree(t);setCurrencyOptions(c.filter(x=>x.status==='ACTIVE'));
        setPeriodOptions(p);setMessage(nav.totalForms+' форм / '+t.categories.length+' категорий');}
    }).catch(e=>{if(active)setError(String(e));});
    return()=>{active=false};
  },[language]);
  useEffect(()=>{
    let active=true;
    get<FormDetail>('/catalog/forms/'+encodeURIComponent(formCode)+'?locale='+language)
      .then(v=>{if(active){setForm(v);if(lastForm.current!==formCode){lastForm.current=formCode;setDraft(null);setInstanceId('');setValues({});setProfileId('');setPreview(null);}}})
      .catch(e=>{if(active)setError(String(e))});
    return()=>{active=false};
  },[formCode,language]);
  useEffect(()=>{
    let active=true;
    get<{startInclusive:string;endExclusive:string}>('/catalog/periods/resolve?'+new URLSearchParams({type:periodType,code:periodCode}))
      .then(r=>{if(active)setRange(r.startInclusive+' — '+r.endExclusive+' (exclusive)')})
      .catch(()=>{if(active)setRange('Неверный период')});
    return()=>{active=false};
  },[periodType,periodCode]);

  const fields=useMemo(()=>(form?.fields??[]).filter(f=>f.inputMode==='MANUAL').sort((a,b)=>a.displayOrder-b.displayOrder),[form]);
  // The catalog is not an approval to write. Backend only accepts qualified
  // typed schemas; F02 is the explicit current pilot.
  const editable=form?.form.contentMode==='SINGLE_RECORD' &&
    (form.form.captureMode==='TYPED_DRAFT'||formCode==='F02') &&
    (!draft||draft.workflowStatus==='DRAFT') && fields.length>0;
  const roots=(tree?.categories??[]).filter(c=>c.type==='ROOT');
  const filtered=useMemo(()=>{
    const included=new Set<string>();
    if(category!=='ALL'){
      const categories=new Set([category]);
      for(const c of tree?.categories??[])if(c.parentCode===category)categories.add(c.code);
      for(const link of tree?.links??[])if(categories.has(link.categoryCode))included.add(link.formCode);
    }
    return (navigation?.allForms??[]).filter(f=>(category==='ALL'||included.has(f.formCode))&&
      (f.formCode+' '+f.title+' '+f.functionalDomain).toLowerCase().includes(search.toLowerCase()));
  },[navigation,tree,category,search]);
  const filled=fields.filter(f=>values[f.fieldCode]?.trim()).length;
  const selectedCurrency=currencyOptions.find(c=>c.code===currency);
  const isLocked=Boolean(draft);

  function setValue(code:string,value:string){
    setValues(old=>({...old,[code]:value}));
    setDirty(true);
    setPreview(null);
  }
  function pasteRange(e:ClipboardEvent<HTMLInputElement>,index:number){
    const plain=e.clipboardData.getData('text/plain');
    if(!plain.includes('\t')&&!plain.includes('\n'))return;
    e.preventDefault();
    try{
      const pasted=parseExcelPaste(plain,index,fields);
      setValues(old=>{
        const next={...old};
        pasted.forEach((raw,n)=>{
          const field=fields[index+n];
          next[field.fieldCode]=normalize(raw,field.fieldType);
        });
        return next;
      });
      setDirty(true);
      setPreview(null);
      setError('');
      setMessage('Проверено и вставлено '+pasted.length+' значений; для сохранения создайте ревизию');
    }catch(error){
      setError(error instanceof Error?error.message:String(error));
    }
  }
  function nextCell(e:KeyboardEvent<HTMLInputElement>,index:number){
    if(e.key==='Enter'||e.key==='ArrowDown'||e.key==='ArrowUp'){
      e.preventDefault();
      const dest=fields[index+(e.key==='ArrowUp'?-1:1)];
      if(dest)document.getElementById('field-'+dest.fieldCode)?.focus();
    }
  }
  async function execute(job:()=>Promise<void>){
    if(inFlight.current)return;
    inFlight.current=true;
    setBusy(true);setError('');
    try{await job()}catch(e){setError(e instanceof Error?e.message:String(e))}
    finally{inFlight.current=false;setBusy(false)}
  }
  function confirmDiscard():boolean {
    if(saveUncertain||commitUncertain)
      return window.confirm('Последняя запись могла выполниться на сервере. '+
        'Перед продолжением проверьте текущую ревизию. Всё равно выйти?');
    return !dirty||window.confirm('Есть несохранённые значения. Продолжить без сохранения?');
  }
  function switchForm(code:string){
    if(code===formCode||!confirmDiscard())return;
    setFormCode(code);setDashboard(false);setDirty(false);
    setCommitUncertain(false);setSaveUncertain(false);saveKeyRef.current=null;
  }
  const save=()=>execute(async()=>{
    if(!form||!editable)throw new Error('Запись разрешена только для DRAFT с утверждённым типизированным SINGLE_RECORD контрактом.');
    if(!draft&&!dirty&&Object.keys(values).length===0)throw new Error('Сначала заполните отчёт');
    if(draft&&!dirty)throw new Error('Нет изменений для сохранения');
    if(range==='Неверный период')throw new Error('Некорректный учётный период');
    for(const f of fields)if(f.required&&!values[f.fieldCode]?.trim())
      throw new Error('Обязательное поле: '+f.title+' ('+f.fieldCode+')');
    const cleaned=Object.fromEntries(fields.filter(f=>values[f.fieldCode]!==undefined)
      .map(f=>[f.fieldCode,normalize(values[f.fieldCode],f.fieldType)]));
    if(!saveKeyRef.current)saveKeyRef.current=crypto.randomUUID();
    // Preserve the exact idempotency key after uncertain network outcomes.
    setSaveUncertain(true);
    const receipt=await post<Receipt>('/drafts',{
      instanceId:draft?.instanceId??null,...scope,formCode,
      periodType:draft?.periodType??periodType,periodCode:draft?.periodCode??periodCode,
      currency:draft?.currency??currency,coverage:'PERIOD_ONLY',
      reportingBasis:'MANAGEMENT',dataSpace:'SYNTHETIC',
      expectedRevision:draft?.revisionNo??0,values:cleaned,rows:[],
      createdBy:'react-local-uat',changeReason:'React editor input'
    },saveKeyRef.current);
    const snapshot=await get<Draft>('/drafts/'+receipt.instanceId+'?organizationId='+scope.organizationId);
    if(snapshot.sha256!==receipt.contentSha256||snapshot.revisionNo!==receipt.revisionNo)
      throw new Error('Сохранение выполнено, но сверка ревизии не прошла. Повторите с тем же ключом.');
    setDraft(snapshot);setInstanceId(snapshot.instanceId);setValues(snapshot.values);setPreview(null);
    saveKeyRef.current=null;setSaveUncertain(false);
    setDirty(false);setCommitUncertain(false);
    setMessage('Сохранено: ревизия '+snapshot.revisionNo+' · SHA '+snapshot.sha256.slice(0,12));
  });
  const open=()=>execute(async()=>{
    if(!confirmDiscard())return;
    if(!instanceId)throw new Error('Укажите UUID экземпляра');
    const snapshot=await get<Draft>('/drafts/'+instanceId+'?organizationId='+scope.organizationId);
    if(snapshot.formCode!==formCode)throw new Error('Сначала выберите форму '+snapshot.formCode);
    setDraft(snapshot);setValues(snapshot.values);setPeriodType(snapshot.periodType);setPeriodCode(snapshot.periodCode);
    setCurrency(snapshot.currency);setPreview(null);setDirty(false);
    setCommitUncertain(false);setSaveUncertain(false);saveKeyRef.current=null;
    setMessage('Открыта ревизия '+snapshot.revisionNo);
  });
  const download=()=>execute(async()=>{
    if(!draft)throw new Error('Сначала сохраните отчёт');
    const {file:blob,name}=await exportXlsx(draft.instanceId,scope.organizationId,style,language);
    const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;
    document.body.append(a);a.click();a.remove();
    setTimeout(()=>URL.revokeObjectURL(url),1000);setMessage('Скачан '+name);
  });
  const doPreview=()=>execute(async()=>{
    if(!draft||!file)throw new Error('Откройте отчёт и выберите XLSX');
    if(dirty||saveUncertain)throw new Error('Есть несохранённая или неопределённая запись React; сначала сверьте ревизию');
    if(file.size>2_000_000)throw new Error('XLSX превышает лимит Backend 2 MB');
    let id=profileId;
    if(!id){
      const version=await get<{formVersionId:string}>('/uat-helper/drafts/'+draft.instanceId+
        '/version?organizationId='+scope.organizationId);
      const registered=await post<Profile>('/import-profiles',{formVersionId:version.formVersionId});
      id=registered.id;setProfileId(id);
    }
    const body=new FormData();body.append('file',file);body.append('instanceId',draft.instanceId);
    body.append('organizationId',scope.organizationId);body.append('actor','react-local-uat');
    body.append('reason','React Excel Preview');
    const ticket=await multipart<PreviewTicket>('/import-profiles/'+id+'/preview',body);
    const current=await get<Draft>('/drafts/'+draft.instanceId+'?organizationId='+scope.organizationId);
    if(current.revisionNo!==draft.revisionNo||current.sha256!==draft.sha256)
      throw new Error('Ревизия или SHA изменились во время Preview');
    if(ticket.expectedRevision!==draft.revisionNo)
      throw new Error('Preview относится к другой ревизии');
    setPrevious(current);setPreview(ticket);
    setMessage('Preview OK: '+Object.keys(ticket.values).length+' полей · без записи в fin_intake');
  });
  const doCommit=()=>execute(async()=>{
    if(!preview||!file||!draft||!previous)throw new Error('Требуется Preview');
    const body=new FormData();body.append('file',file);body.append('instanceId',draft.instanceId);
    body.append('organizationId',scope.organizationId);body.append('actor','react-local-uat');
    body.append('reason','React Excel Commit');
    // Commit is a mutating request. Once it returns, avoid blind retries if
    // read-back fails: the database may already contain the new revision.
    setCommitUncertain(true);
    setPreview(null);
    const receipt=await multipart<Receipt>('/import-profiles/previews/'+preview.previewId+'/commit',body,true);
    const current=await get<Draft>('/drafts/'+draft.instanceId+'?organizationId='+scope.organizationId);
    const mismatch=Object.entries(preview.values).filter(([code,v])=>current.values[code]!==v);
    if(mismatch.length||current.sha256!==receipt.contentSha256||
      current.revisionNo!==previous.revisionNo+1)
      throw new Error('Commit выполнен, но сверка не прошла. Обновите экземпляр; не повторяйте Commit вслепую.');
    setDraft(current);setValues(current.values);setFile(null);
    setDirty(false);setCommitUncertain(false);
    setMessage('Commit '+current.revisionNo+' · SHA '+current.sha256.slice(0,12)+' · проверено '+Object.keys(preview.values).length+' полей');
  });
  const reset=()=>{
    if(!confirmDiscard())return;
    setDraft(null);setInstanceId('');setValues({});setProfileId('');
    setPreview(null);setFile(null);setDirty(false);setCommitUncertain(false);
    setSaveUncertain(false);saveKeyRef.current=null;setMessage('Новый черновик');
  };

  function renderRow(field:Field,index:number){
    return <tr key={field.fieldCode}><td className="seq">{index+1}</td>
      <td className="metric"><span>{field.title||field.fieldCode}{field.required&&<strong className="required"> *</strong>}</span>
        <small>{field.fieldCode} · {field.fieldType}</small></td>
      <td><input id={'field-'+field.fieldCode} className={'cell-input '+(field.required?'required-cell':'')}
        value={values[field.fieldCode]??''} disabled={!editable||busy||saveUncertain||commitUncertain}
        onChange={e=>setValue(field.fieldCode,e.target.value)}
        onBlur={e=>{const fixed=normalize(e.target.value,field.fieldType);if(fixed!==e.target.value)setValue(field.fieldCode,fixed)}}
        onPaste={e=>pasteRange(e,index)} onKeyDown={e=>nextCell(e,index)}
        placeholder={field.fieldType==='MONEY'?'0.00':'—'} aria-label={field.title||field.fieldCode}/></td></tr>;
  }
  return <div className={'shell'+(!sidebar?' collapsed':'')}>
    <aside className="sidebar">
      <div className="logo"><div className="logo-mark"><Activity size={21}/></div><div><b>FIN</b><small>FORM STUDIO</small></div></div>
      <div className="side-overline">НАВИГАЦИЯ</div>
      <button className={'nav-line '+(dashboard?'selected':'')} onClick={()=>setDashboard(true)}><LayoutDashboard size={17}/> Обзор</button>
      <button className={'nav-line '+(!dashboard?'selected':'')} onClick={()=>setDashboard(false)}><FolderTree size={17}/> Каталог форм <span className="nav-count">{navigation?.totalForms??'—'}</span></button>
      <div className="side-overline space">КАТЕГОРИИ</div>
      <button className={'nav-line '+(category==='ALL'?'selected':'')} onClick={()=>{setCategory('ALL');setDashboard(false)}}>Все формы</button>
      {roots.map(root=><div key={root.code}>
        <div className={'nav-line group '+(category===root.code?'selected':'')}>
          <button className="group-select" onClick={()=>{setCategory(root.code);setDashboard(false)}}>{root.title}</button>
          <button className="expand" title="Подкатегории" onClick={()=>setExpanded(o=>({...o,[root.code]:!o[root.code]}))}>
            {expanded[root.code]?<ChevronDown size={15}/>:<ChevronRight size={15}/>}
          </button>
        </div>
        {expanded[root.code]&&(tree?.categories??[]).filter(c=>c.parentCode===root.code).map(c=>
          <button key={c.code} className={'nav-line sub '+(category===c.code?'selected':'')}
            onClick={()=>{setCategory(c.code);setDashboard(false)}}>{c.title}</button>)}
      </div>)}
      <div className="sidebar-footer"><span className="dot"/> Только localhost · без IAM</div>
    </aside>
    <div className="workspace">
      <header className="topbar">
        <div className="top-left"><Tool label={sidebar?'Скрыть меню':'Открыть меню'} onClick={()=>setSidebar(v=>!v)}>{sidebar?<PanelLeftClose size={19}/>:<PanelLeftOpen size={19}/>}</Tool>
        <span className="breadcrumb">Рабочая область <ChevronRight size={14}/> {dashboard?'Обзор':'Формы'} <ChevronRight size={14}/> <b>{formCode}</b></span></div>
        <div className="top-right"><span className="local-state"><span className="dot"/> LOCAL</span>
          <select value={language} onChange={e=>setLanguage(e.target.value as Locale)} aria-label="Язык">
            {languages.map(l=><option key={l} value={l}>{l==='ru'?'Русский':l==='kk'?'Қазақша':l==='en'?'English':'中文'}</option>)}
          </select><Tool label="Обновить каталог" onClick={()=>window.location.reload()}><RefreshCw size={18}/></Tool></div>
      </header>
      <main className="content">
        <div className="intro"><div><p className="eyebrow">FINANCIAL REPORTING WORKSPACE</p>
          <h1>{dashboard?'Финансовая рабочая область':'Каталог и редактор форм'}</h1>
          <p className="subtitle">Единый контракт данных · 166 форм · immutable revisions · Excel round-trip</p></div>
          <span className="mode-pill">LOCAL DEVELOPMENT</span></div>
        {error&&<div className="error" role="alert"><AlertTriangle size={18}/><span>{error}</span><button onClick={()=>setError('')} aria-label="Закрыть">×</button></div>}
        <div className="stats">
          <div className="stat"><span>Формы</span><strong>{navigation?.totalForms??'—'}</strong><small>В каталоге Backend</small></div>
          <div className="stat"><span>Категории</span><strong>{tree?.categories.length??'—'}</strong><small>Реальный fin_lib</small></div>
          <div className="stat"><span>Заполнено полей</span><strong>{filled}/{fields.length}</strong><small>Текущая форма</small></div>
          <div className="stat"><span>Ревизия</span><strong>{draft?.revisionNo??'—'}</strong><small>{draft?.sha256.slice(0,12)??'Новый черновик'}</small></div>
        </div>
        <div className="two-panel">
          <section className="catalog-panel">
            <div className="panel-head"><div><h2>Библиотека форм</h2><p>{filtered.length} результатов</p></div><FolderTree size={19}/></div>
            <label className="search-box"><Search size={17}/><input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Код или название..." /></label>
            <div className="form-list">
              {filtered.map(f=><button key={f.formCode} className={'form-list-item '+(f.formCode===formCode?'active':'')}
                onClick={()=>switchForm(f.formCode)}>
                <span className="form-icon"><FileSpreadsheet size={18}/></span>
                <span><b>{f.title||f.formCode}</b><small>{f.formCode} · {f.contentMode}</small></span><ChevronRight size={16}/></button>)}
              {!filtered.length&&<p className="empty">Формы не найдены</p>}
            </div>
          </section>
          <section className="editor-panel">
            <div className="panel-head editor-head"><div><p className="eyebrow">{formCode} · {form?.form.version||'—'}</p>
              <h2>{form?.form.title||'Загрузка формы'}</h2>
              <p>{draft?'Редакция '+draft.revisionNo+' · '+draft.workflowStatus:'Новая форма · заполнение с нуля'}</p></div>
              <div className="toolbar">
                <Tool label="Новый документ" disabled={busy} onClick={reset}><FilePlus2 size={18}/></Tool>
                <Tool label="Сохранить ревизию" disabled={busy||!editable||!dirty||commitUncertain} onClick={save}>{busy?<LoaderCircle className="spin" size={18}/>:<Save size={18}/>}</Tool>
                <Tool label="Открыть по UUID (сверить ревизию)" disabled={busy} onClick={open}><History size={18}/></Tool>
                <Tool label="Скачать XLSX" disabled={busy||!draft} onClick={download}><Download size={18}/></Tool>
                <Tool label="Загрузить XLSX" disabled={busy||!draft||dirty||commitUncertain||saveUncertain} onClick={()=>document.getElementById('xlsx-upload')?.click()}><FileUp size={18}/></Tool>
                <Tool label="Контекст организации" onClick={()=>setShowScope(v=>!v)}><Settings2 size={18}/></Tool>
              </div></div>
            <div className="editor-body">
              <div className="filters">
                <label>Тип периода<select value={periodType} disabled={isLocked} onChange={e=>{setPeriodType(e.target.value);setPeriodCode(periodExamples[e.target.value]||'')}}>
                  {periodOptions.map(p=><option key={p.code} value={p.code}>{p.title} ({p.code})</option>)}</select></label>
                <label>Учётный период<input value={periodCode} disabled={isLocked} onChange={e=>setPeriodCode(e.target.value)}/></label>
                <label>Валюта<select value={currency} disabled={isLocked} onChange={e=>setCurrency(e.target.value)}>
                  {currencyOptions.map(c=><option key={c.code} value={c.code}>{c.code} · {c.minorUnits}</option>)}</select></label>
                <label>Стиль<select value={style} onChange={e=>setStyle(e.target.value as Style)}>
                  <option value="EXCEL">EXCEL</option><option value="CLASSIC">CLASSIC</option><option value="DASHBOARD">DASHBOARD</option></select></label>
              </div>
              <p className="period-caption">Период: {range} · Единица: {selectedCurrency?.code||currency}</p>
              {showScope&&<div className="scope-panel">
                <div><b>Локальный UAT-контекст</b><p>Синтетические UUID; не является авторизацией</p></div>
                {Object.entries(scope).map(([key,value])=><label key={key}>{key}<input value={value} onChange={e=>setScope(old=>({...old,[key]:e.target.value}))} disabled={isLocked}/></label>)}
                <button onClick={()=>{setScope(newScope());reset()}}>Новая организация UAT</button>
                <label>Instance UUID<input value={instanceId} onChange={e=>setInstanceId(e.target.value)} /></label>
              </div>}
              {!editable&&form&&<div className="warning"><AlertTriangle size={17}/> Запись отключена: нет утверждённого типизированного SINGLE_RECORD контракта или отчёт не в DRAFT. Каталог сам по себе не разрешает ввод.</div>}
              {commitUncertain&&<div className="warning"><AlertTriangle size={17}/> Commit мог сохраниться. Откройте экземпляр заново и сверьте номер ревизии перед следующей операцией.</div>}
              {saveUncertain&&<div className="warning"><AlertTriangle size={17}/> Ответ на сохранение не подтверждён. Повторите сохранение с тем же Idempotency-Key; значения заблокированы до сверки.</div>}
              {dirty&&<div className="warning"><AlertTriangle size={17}/> Есть несохранённые изменения.</div>}
              <div className="sheet-head"><div><b>Показатели отчёта</b><p>Вставка из Excel: Ctrl+V · Enter / ↓ для следующего поля</p></div>
                <span className="total-badge">{fields.length} показателей</span></div>
              {style==='EXCEL'?<div className="sheet-scroll"><table className="sheet"><thead><tr><th>№</th><th>Показатель</th><th>Значение</th></tr></thead>
                <tbody>{fields.map(renderRow)}</tbody></table></div>:
                <div className={style==='DASHBOARD'?'cards-layout':'classic-layout'}>
                  {fields.map((f,i)=><div className="metric-card" key={f.fieldCode}>
                    <span className="card-seq">{i+1}</span><label htmlFor={'field-'+f.fieldCode}>{f.title||f.fieldCode}
                    {f.required&&<span className="required"> *</span>}<small>{f.fieldCode} · {f.fieldType}</small></label>
                    <input id={'field-'+f.fieldCode} value={values[f.fieldCode]??''} disabled={!editable||busy||saveUncertain||commitUncertain}
                      onChange={e=>setValue(f.fieldCode,e.target.value)} onPaste={e=>pasteRange(e,i)}
                      onKeyDown={e=>nextCell(e,i)}/></div>)}
                </div>}
              <div className="import-box">
                <div><b>Excel Round-trip</b><p>Выберите XLSX → Preview без записи → Commit новой ревизии</p></div>
                <input type="file" id="xlsx-upload" accept=".xlsx" onChange={e=>{setFile(e.target.files?.[0]??null);setPreview(null)}}/>
                <div className="import-tools">
                  <Tool label="Предпросмотр XLSX без записи" disabled={!file||!draft||busy} onClick={doPreview}><Eye size={18}/></Tool>
                  <Tool label="Подтвердить импорт новой ревизии" disabled={!preview||busy||commitUncertain} onClick={doCommit}><Check size={18}/></Tool>
                  <span>{file?.name??'Файл не выбран'}</span></div>
              </div>
            </div>
            <div className="editor-footer"><span className="dot"/> {message}<span className="footer-sha">{draft?'SHA256 '+draft.sha256.slice(0,16):'Unsaved'}</span></div>
          </section>
        </div>
        <footer className="disclaimer"><AlertTriangle size={15}/> Локальный стенд. Backend без Security: не публикуйте порт 8086 в сеть и не используйте реальные финансовые данные.</footer>
      </main>
    </div>
  </div>;
}
