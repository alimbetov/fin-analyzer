import {useEffect,useMemo,useRef,useState} from 'react';
import type {ClipboardEvent,KeyboardEvent,ReactNode} from 'react';
import {Activity,AlertTriangle,Check,ChevronDown,ChevronRight,Download,FilePlus2,FileSpreadsheet,FileUp,FolderTree,Eye,GitBranch,LayoutDashboard,LoaderCircle,PanelLeftClose,PanelLeftOpen,RefreshCw,Save,Search,Settings2} from 'lucide-react';
import type {CategoryTree,Currency,Draft,Field,FormDetail,Locale,Navigation,PeriodType,PreviewTicket,Profile,Receipt,Row,Style} from './api';
import {exportXlsx,get,isDefiniteRejection,multipart,post} from './api';
import {parseExcelPaste,parseRowsetExcelPaste} from './clipboard';
import {canEditForm,fileSha256,matchesPreview,verifySavedDraft} from './editorPolicy';
import SavedReports from './SavedReports';
import type {SavedReport} from './SavedReports';
import FinancialGoalsNavigator from './FinancialGoalsNavigator';
import type {DecisionContext} from './decisionTree';
import './styles.css';

const periodExamples:Record<string,string>={D:'2026-11-18',W:'2026-W47',DEC:'2026-11-2',M:'2026-11',Q:'2026-Q4',H:'2026-H2',Y:'2026'};
const languages:Locale[]=['ru','kk','en','zh-Hans'];
const editorBlockReason:Record<string,string>={
  FORM_NOT_LOADED:'Загрузка контракта формы ещё не завершена.',
  FORM_LOADING:'Ожидаем контракт выбранной формы.',
  UNSUPPORTED_CONTENT_MODE:'Для этой формы ещё нет поддерживаемого редактора.',
  NO_MANUAL_FIELDS:'В контракте формы отсутствуют поля ручного ввода.',
  DRAFT_FORM_MISMATCH:'Открытый отчёт относится к другой форме. Выберите форму, соответствующую отчёту.',
  REPORT_NOT_EDITABLE:'Отчёт больше не в статусе DRAFT. Существующие ревизии доступны только для чтения.',
  PINNED_FORM_VERSION_MISMATCH:'Отчёт закреплён за другой версией формы. Автоматическое перепривязывание запрещено.'
};
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
  if(type==='DECIMAL'){
    const number=/^(-?(?:\d{1,3}(?: \d{3})+|\d+))(?:([.,])(\d{1,12}))?$/.exec(v);
    if(!number)return v;
    const [,whole,separator,fraction]=number;
    // A lone comma followed by three digits may mean grouping.
    if(separator===','&&fraction.length===3&&/^-?\d{1,3}$/.test(whole))return v;
    return whole.replaceAll(' ','')+(fraction?'.'+fraction:'');
  }
  if(/^-?\d{1,3}( \d{3})+(?:[.,]\d{1,2})?$/.test(v))return v.replaceAll(' ','').replace(',','.');
  if(/^-?\d+(?:[.,]\d{1,2})?$/.test(v))return v.replace(',','.');
  return v; // invalid and ambiguous financial values are rejected by Backend
};
function Tool({label,onClick,disabled,children}:{label:string;onClick:()=>void;disabled?:boolean;children:ReactNode}){
  return <button className="tool" type="button" title={label} aria-label={label} onClick={onClick} disabled={disabled}>{children}</button>;
}

export default function App(){
  const [language,setLanguage]=useState<Locale>('ru');
  const [style,setStyle]=useState<Style>('CLASSIC');
  const [navigation,setNavigation]=useState<Navigation|null>(null);
  const [tree,setTree]=useState<CategoryTree|null>(null);
  const [form,setForm]=useState<FormDetail|null>(null);
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
  const [rowEntries,setRowEntries]=useState<Row[]>([]);
  const [profileId,setProfileId]=useState('');
  const [preview,setPreview]=useState<PreviewTicket|null>(null);
  const [file,setFile]=useState<File|null>(null);
  const [previous,setPrevious]=useState<Draft|null>(null);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [message,setMessage]=useState('Подключение к Backend...');
  const [sidebar,setSidebar]=useState(()=>window.innerWidth>920);
  const [navigator,setNavigator]=useState(false);
  const [decisionContext,setDecisionContext]=useState<DecisionContext|null>(null);
  const [dashboard,setDashboard]=useState(false);
  const [expanded,setExpanded]=useState<Record<string,boolean>>({});
  const [showScope,setShowScope]=useState(false);
  const [showReports,setShowReports]=useState(true);
  const [showCategories,setShowCategories]=useState(false);
  const [reports,setReports]=useState<SavedReport[]|null>(null);
  const [reportsError,setReportsError]=useState('');
  const [reportsRefresh,setReportsRefresh]=useState(0);
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
      .then(v=>{if(active)setForm(v)})
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

  useEffect(()=>{
    if(!showReports||navigator)return;
    let active=true;
    setReports(null);setReportsError('');
    const query=new URLSearchParams({...scope,formCode});
    get<SavedReport[]>('/drafts/catalog?'+query).then(items=>{
      if(active)setReports(items);
    }).catch(error=>{
      if(active)setReportsError(String(error));
    });
    return()=>{active=false;};
  },[showReports,navigator,scope,formCode,reportsRefresh]);

  const fields=useMemo(()=>(form?.fields??[]).filter(f=>f.inputMode==='MANUAL').sort((a,b)=>a.displayOrder-b.displayOrder),[form]);
  // The catalog is not an approval to write. Backend only accepts qualified
  // typed schemas; F02 is the explicit current pilot.
  const eligibility=canEditForm(form,formCode,draft);
  const editable=eligibility.allowed;
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
  const rowset=form?.form.contentMode==='ROWSET';
  const evidence=form?.form.contentMode==='EVIDENCE';
  const filled=rowset?rowEntries.reduce((n,r)=>n+fields.filter(f=>r.values[f.fieldCode]?.trim()).length,0):fields.filter(f=>values[f.fieldCode]?.trim()).length;
  const fieldCapacity=fields.length*(rowset?rowEntries.length:1);
  const selectedCurrency=currencyOptions.find(c=>c.code===currency);
  const isLocked=Boolean(draft)||dirty||saveUncertain||commitUncertain;

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
  function switchForm(code:string):boolean{
    if(inFlight.current||!confirmDiscard())return false;
    if(code!==formCode){
      setForm(null);
      setFormCode(code);
    }
    setDraft(null);setInstanceId('');setValues({});setRowEntries([]);
    setProfileId('');setPrevious(null);setPreview(null);setFile(null);
    setNavigator(false);setDashboard(false);setDirty(false);
    setCommitUncertain(false);setSaveUncertain(false);saveKeyRef.current=null;
    setDecisionContext(null);
    setReports(null);setReportsError('');setReportsRefresh(value=>value+1);
    setShowReports(true);
    return true;
  }
  function openFromFlow(code:string,context:DecisionContext){
    // Keep the original decision context if the editor refuses a navigation
    // because it contains unsaved values or an uncertain write.
    if(switchForm(code))setDecisionContext(context);
  }
  const save=()=>execute(async()=>{
    if(!form||!editable)throw new Error('Для этой формы запись черновика недоступна.');
    if(!draft&&!dirty&&Object.keys(values).length===0&&rowEntries.length===0)throw new Error('Сначала заполните отчёт');
    if(draft&&!dirty)throw new Error('Нет изменений для сохранения');
    if(range==='Неверный период')throw new Error('Некорректный учётный период');
    if(rowset&&(rowEntries.length===0||rowEntries.length>200))
      throw new Error('Табличная форма требует от 1 до 200 строк');
    for(const [i,record] of (rowset?rowEntries:[{values}]).entries())
      for(const f of fields)if(f.required&&!record.values[f.fieldCode]?.trim())
        throw new Error('Обязательное поле '+f.fieldCode+(rowset?' (строка '+(i+1)+')':''));
    const cleaned=rowset?{}:Object.fromEntries(fields.filter(f=>values[f.fieldCode]!==undefined)
      .map(f=>[f.fieldCode,normalize(values[f.fieldCode],f.fieldType)]));
    const preparedRows=rowset?rowEntries.map(r=>({...r,values:Object.fromEntries(
      fields.filter(f=>r.values[f.fieldCode]!==undefined)
        .map(f=>[f.fieldCode,normalize(r.values[f.fieldCode],f.fieldType)]))})):[];
    const previousRevision=draft?.revisionNo??0;
    if(!saveKeyRef.current)saveKeyRef.current=crypto.randomUUID();
    // Preserve the exact idempotency key after uncertain network outcomes.
    setSaveUncertain(true);
    let receipt:Receipt;
    try{
      receipt=await post<Receipt>('/drafts',{
        instanceId:draft?.instanceId??null,
        organizationId:scope.organizationId,
        economicEntityId:draft?.economicEntityId??scope.economicEntityId,
        businessScopeId:draft?.businessScopeId??scope.businessScopeId,
        projectId:draft?.projectId??scope.projectId,
        formCode,
        periodType:draft?.periodType??periodType,periodCode:draft?.periodCode??periodCode,
        currency:draft?.currency??currency,coverage:draft?.coverage??'PERIOD_ONLY',
        reportingBasis:draft?.reportingBasis??'MANAGEMENT',
        dataSpace:draft?.dataSpace??'SYNTHETIC',
        expectedRevision:draft?.revisionNo??0,values:cleaned,rows:preparedRows,
        createdBy:'react-local-uat',changeReason:'React editor input'
      },saveKeyRef.current);
    }catch(error){
      if(isDefiniteRejection(error)){
        setSaveUncertain(false);
        saveKeyRef.current=null;
      }
      if(!draft&&error instanceof Error&&error.message.includes('REPORT_SLOT_ALREADY_EXISTS')){
        // Re-open the exact slot, but do NOT silently overwrite the user's
        // unsaved financial input. A second Save will use a fresh revision key.
        const query=new URLSearchParams({
          organizationId:scope.organizationId,
          economicEntityId:scope.economicEntityId,
          businessScopeId:scope.businessScopeId,
          projectId:scope.projectId,
          formCode,periodType,periodCode,coverage:'PERIOD_ONLY',
          currency,reportingBasis:'MANAGEMENT',dataSpace:'SYNTHETIC'
        });
        const existing=await get<Draft>('/drafts/slot?'+query);
        setDraft(existing);
        setInstanceId(existing.instanceId);
        setDirty(true);
        throw new Error('Уже существует отчёт за этот период. Открыта ревизия '+
          existing.revisionNo+'. Введённые значения сохранены в редакторе, но ещё не записаны в БД. '+
          'Проверьте их и нажмите «Сохранить ревизию» ещё раз.');
      }
      throw error;
    }
    const snapshot=await get<Draft>('/drafts/'+receipt.instanceId+'?organizationId='+scope.organizationId);
    if(!verifySavedDraft(receipt,snapshot,scope.organizationId,formCode,
      previousRevision,cleaned,preparedRows,fields))
      throw new Error('Запись могла завершиться, но полная сверка ревизии, полей и строк не прошла. '+
        'Не меняйте данные вслепую: откройте сохранённый экземпляр и проверьте результат.');
    setDraft(snapshot);setInstanceId(snapshot.instanceId);setValues(snapshot.values);setRowEntries(snapshot.rows);setPreview(null);
    saveKeyRef.current=null;setSaveUncertain(false);
    setDirty(false);setCommitUncertain(false);
    setMessage('Сохранено: ревизия '+snapshot.revisionNo+' · SHA '+snapshot.sha256.slice(0,12));
  });
  const openDraft=(id:string)=>execute(async()=>{
    if(!confirmDiscard())return;
    if(!id)throw new Error('Укажите UUID экземпляра');
    const query=new URLSearchParams({organizationId:scope.organizationId});
    const snapshot=await get<Draft>('/drafts/'+encodeURIComponent(id)+'?'+query);
    if(snapshot.formCode!==formCode)throw new Error('Сначала выберите форму '+snapshot.formCode);
    setDraft(snapshot);setValues(snapshot.values);setRowEntries(snapshot.rows);setPeriodType(snapshot.periodType);setPeriodCode(snapshot.periodCode);
    setCurrency(snapshot.currency);setScope({
      organizationId:snapshot.organizationId,economicEntityId:snapshot.economicEntityId,
      businessScopeId:snapshot.businessScopeId,projectId:snapshot.projectId
    });setPreview(null);setDirty(false);
    setCommitUncertain(false);setSaveUncertain(false);saveKeyRef.current=null;
    setInstanceId(snapshot.instanceId);setShowReports(false);
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
    if(dirty||saveUncertain||commitUncertain)throw new Error('Есть несохранённая или неопределённая запись React; сначала сверьте ревизию');
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
    const uploadSha=await fileSha256(file);
    const ticket=await multipart<PreviewTicket>('/import-profiles/'+id+'/preview',body);
    const current=await get<Draft>('/drafts/'+draft.instanceId+'?organizationId='+scope.organizationId);
    if(current.revisionNo!==draft.revisionNo||current.sha256!==draft.sha256)
      throw new Error('Ревизия или SHA изменились во время Preview');
    if(ticket.profileId!==id||!matchesPreview(ticket,current,uploadSha))
      throw new Error('Preview не соответствует файлу, организации или ревизии');
    setPrevious(current);setPreview(ticket);
    setMessage('Preview OK: '+Object.keys(ticket.values).length+' полей · без записи в fin_intake');
  });
  const doCommit=()=>execute(async()=>{
    if(!preview||!file||!draft||!previous)throw new Error('Требуется Preview');
    const uploadSha=await fileSha256(file);
    if(!matchesPreview(preview,previous,uploadSha))
      throw new Error('Файл, организация или ревизия изменились после Preview');
    if(Date.parse(preview.expiresAt)<=Date.now())
      throw new Error('Preview истёк. Выполните проверку файла заново.');
    const changes=Object.keys(preview.values).filter(k=>preview.values[k]!==previous.values[k]);
    if(!window.confirm('Сохранить новую immutable-ревизию? Изменений: '+changes.length+
      '. Проверьте таблицу сравнения перед подтверждением.'))return;
    const body=new FormData();body.append('file',file);body.append('instanceId',draft.instanceId);
    body.append('organizationId',scope.organizationId);body.append('actor','react-local-uat');
    body.append('reason','React Excel Commit');
    // Commit is a mutating request. Once it returns, avoid blind retries if
    // read-back fails: the database may already contain the new revision.
    setCommitUncertain(true);
    setPreview(null);
    let receipt:Receipt;
    try{
      receipt=await multipart<Receipt>('/import-profiles/previews/'+preview.previewId+'/commit',body,true);
    }catch(error){
      // HTTP 409 can mean a consumed ticket after a successful but unobserved
      // commit. Never unlock the editor until the actual revision is read.
      if(isDefiniteRejection(error) &&
         !(error instanceof Error && error.message.startsWith('HTTP 409:')))
        setCommitUncertain(false);
      throw error;
    }
    const current=await get<Draft>('/drafts/'+draft.instanceId+'?organizationId='+scope.organizationId);
    const mismatch=Object.entries(preview.values).filter(([code,v])=>current.values[code]!==v);
    if(mismatch.length||current.sha256!==receipt.contentSha256||
      current.revisionNo!==previous.revisionNo+1)
      throw new Error('Commit выполнен, но сверка не прошла. Обновите экземпляр; не повторяйте Commit вслепую.');
    setDraft(current);setValues(current.values);setRowEntries(current.rows);setFile(null);
    setDirty(false);setCommitUncertain(false);
    setMessage('Commit '+current.revisionNo+' · SHA '+current.sha256.slice(0,12)+' · проверено '+Object.keys(preview.values).length+' полей');
  });
  const resetEditor=()=>{
    setDraft(null);setInstanceId('');setValues({});setRowEntries([]);setProfileId('');
    setPreview(null);setFile(null);setPrevious(null);setDirty(false);setCommitUncertain(false);
    setSaveUncertain(false);saveKeyRef.current=null;setMessage('Новый черновик');
  };
  const reset=()=>{if(confirmDiscard())resetEditor();};

  function appendGridRow(){
    if(rowEntries.length>=200)return;
    const rowPosition=rowEntries.length?Math.max(...rowEntries.map(r=>r.rowPosition))+10:10;
    setRowEntries(old=>[...old,{rowId:crypto.randomUUID(),rowPosition,values:{}}]);
    setDirty(true);setPreview(null);
  }
  function editGridCell(rowId:string,code:string,value:string){
    setRowEntries(old=>old.map(r=>r.rowId===rowId?
      {...r,values:{...r.values,[code]:value}}:r));
    setDirty(true);setPreview(null);
  }
  function removeGridRow(rowId:string){
    setRowEntries(old=>old.filter(r=>r.rowId!==rowId));
    setDirty(true);setPreview(null);
  }
  function pasteGrid(e:ClipboardEvent<HTMLInputElement>,rowIndex:number,columnIndex:number){
    const plain=e.clipboardData.getData('text/plain');
    if(!plain.includes('\t')&&!plain.includes('\n'))return;
    e.preventDefault();
    try{
      const matrix=parseRowsetExcelPaste(plain,rowIndex,columnIndex,fields.length);
      const updated=rowEntries.map(r=>({...r,values:{...r.values}}));
      while(updated.length<rowIndex+matrix.length){
        const previous=updated.at(-1)?.rowPosition??0;
        updated.push({rowId:crypto.randomUUID(),rowPosition:previous+10,values:{}});
      }
      for(let i=0;i<matrix.length;i++)
        for(let j=0;j<matrix[i].length;j++){
          const field=fields[columnIndex+j];
          updated[rowIndex+i].values[field.fieldCode]=normalize(matrix[i][j],field.fieldType);
        }
      setRowEntries(updated);setDirty(true);setPreview(null);setError('');
      setMessage('Вставлен диапазон '+matrix.length+' × '+matrix[0].length+
        ' в табличную форму. Для записи в PostgreSQL нажмите «Сохранить ревизию».');
    }catch(error){setError(error instanceof Error?error.message:String(error));}
  }

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
      <button className={'nav-line '+(navigator?'selected':'')}
        onClick={()=>{setNavigator(true);setDashboard(false)}}>
        <Activity size={17}/> Финансовые решения</button>
      <button className={'nav-line '+(!navigator&&dashboard?'selected':'')}
        onClick={()=>{setNavigator(false);setDashboard(true)}}>
        <LayoutDashboard size={17}/> Профессиональный обзор</button>
      <button className={'nav-line '+(!navigator&&!dashboard?'selected':'')}
        onClick={()=>{setNavigator(false);setDashboard(false)}}>
        <FolderTree size={17}/> Библиотека форм <span className="nav-count">{navigation?.totalForms??'—'}</span>
      </button>
      <button className="nav-line sidebar-categories-toggle" type="button" aria-expanded={showCategories} onClick={()=>setShowCategories(v=>!v)}><FolderTree size={17}/> Категории {showCategories?<ChevronDown size={15}/>:<ChevronRight size={15}/>}</button>
      {showCategories&&<div className="sidebar-categories"><button className={'nav-line '+(category==='ALL'?'selected':'')} onClick={()=>{setCategory('ALL');setNavigator(false);setDashboard(false)}}>Все формы</button>
      {roots.map(root=><div key={root.code}>
        <div className={'nav-line group '+(category===root.code?'selected':'')}>
          <button className="group-select" onClick={()=>{setCategory(root.code);setNavigator(false);setDashboard(false)}}>{root.title}</button>
          <button className="expand" title="Подкатегории" onClick={()=>setExpanded(o=>({...o,[root.code]:!o[root.code]}))}>
            {expanded[root.code]?<ChevronDown size={15}/>:<ChevronRight size={15}/>}
          </button>
        </div>
        {expanded[root.code]&&(tree?.categories??[]).filter(c=>c.parentCode===root.code).map(c=>
          <button key={c.code} className={'nav-line sub '+(category===c.code?'selected':'')}
            onClick={()=>{setCategory(c.code);setNavigator(false);setDashboard(false)}}>{c.title}</button>)}
      </div>)}
      </div>}
      <div className="sidebar-footer"><span className="dot"/> Только localhost · без IAM</div>
    </aside>
    <div className="workspace">
      <header className="topbar">
        <div className="top-left"><Tool label={sidebar?'Скрыть меню':'Открыть меню'} onClick={()=>setSidebar(v=>!v)}>{sidebar?<PanelLeftClose size={19}/>:<PanelLeftOpen size={19}/>}</Tool>
        <span className="breadcrumb">Рабочая область <ChevronRight size={14}/> {navigator?'Финансовые решения':dashboard?'Профессиональный обзор':'Библиотека форм'} {!navigator&&<><ChevronRight size={14}/> <b>{formCode}</b></>}</span></div>
        <div className="top-right"><span className="local-state"><span className="dot"/> LOCAL</span>
          <select value={language} onChange={e=>setLanguage(e.target.value as Locale)} aria-label="Язык">
            {languages.map(l=><option key={l} value={l}>{l==='ru'?'Русский':l==='kk'?'Қазақша':l==='en'?'English':'中文'}</option>)}
          </select><Tool label="Обновить каталог" onClick={()=>window.location.reload()}><RefreshCw size={18}/></Tool></div>
      </header>
      <main className="content">
        <div className="intro"><div><p className="eyebrow">FINANCIAL REPORTING WORKSPACE</p>
          <h1>{navigator?'От вопроса — к нужным данным':dashboard?'Обзор отчётности':'Мои отчёты'}</h1>
          <p className="subtitle">{navigator?'Выберите задачу, чтобы найти нужную форму':'Выберите отчёт, укажите период и заполните данные. Все изменения сохраняются в редакциях.'}</p></div>
          <span className="mode-pill">LOCAL DEVELOPMENT</span></div>
        {error&&<div className="error" role="alert"><AlertTriangle size={18}/><span>{error}</span><button onClick={()=>setError('')} aria-label="Закрыть">×</button></div>}
        <div hidden={!navigator}>
          <FinancialGoalsNavigator locale={language}
            forms={navigation?.allForms??[]} onOpenForm={openFromFlow}
            onOpenLibrary={()=>{setNavigator(false);setDashboard(false)}}/>
        </div>
        {!navigator&&<>
        {decisionContext?.formCode===formCode&&<div className="decision-context">
          <div>
            <span>ОТ ВОПРОСА — К НУЖНЫМ ДАННЫМ</span>
            <strong>{decisionContext.goalTitle??decisionContext.goalCode}</strong>
            <small>Код задачи: {decisionContext.goalCode}</small>
            <small>{decisionContext.metricCode} → {decisionContext.modelCode??'рекомендация задачи'} → {formCode}</small>
            <small>Связь: {decisionContext.linkKind}. Черновые метаданные — не допуск к расчёту.</small>
          </div>
          <button type="button" onClick={()=>{setNavigator(true);setDashboard(false)}}>
            <GitBranch size={16}/> К дереву задачи
          </button>
        </div>}
        {dashboard&&<div className="stats">
          <div className="stat"><span>Формы</span><strong>{navigation?.totalForms??'—'}</strong><small>В каталоге Backend</small></div>
          <div className="stat"><span>Категории</span><strong>{tree?.categories.length??'—'}</strong><small>Реальный fin_lib</small></div>
          <div className="stat"><span>Заполнено полей</span><strong>{filled}/{fieldCapacity}</strong><small>Текущая форма</small></div>
          <div className="stat"><span>Ревизия</span><strong>{draft?.revisionNo??'—'}</strong><small>{draft?.sha256.slice(0,12)??'Новый черновик'}</small></div>
        </div>}
        {dashboard&&<section className="dashboard-overview">
          <div className="overview-title"><b>Начать с рекомендованной формы</b>
            <span>Рекомендация — не разрешение на ввод. Квалификация указана у каждой формы.</span></div>
          <div className="overview-grid">{(navigation?.recommended??[]).slice(0,4).map(f=>
            <button key={f.formCode} onClick={()=>switchForm(f.formCode)} className="overview-card">
              <FileSpreadsheet size={20}/>
              <span><b>{f.title||f.formCode}</b><small>{f.formCode} · {f.qualificationState}</small></span>
              <ChevronRight size={16}/>
            </button>)}</div>
        </section>}
        <div className="two-panel">
          <section className="catalog-panel">
            <div className="panel-head"><div><h2>Выбрать форму</h2><p>Доступно: {filtered.length}</p></div><FolderTree size={19}/></div>
            <label className="search-box"><Search size={17}/><input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Код или название..." /></label>
            <div className="form-list">
              {filtered.map(f=><button key={f.formCode} className={'form-list-item '+(f.formCode===formCode?'active':'')}
                onClick={()=>switchForm(f.formCode)}>
                <span className="form-icon"><FileSpreadsheet size={18}/></span>
                <span><b>{f.title||f.formCode}</b><small>{f.formCode} · {f.contentMode==='EVIDENCE'?'Документ':f.contentMode==='ROWSET'?'Таблица':'Форма'}</small></span><ChevronRight size={16}/></button>)}
              {!filtered.length&&<p className="empty">Формы не найдены</p>}
            </div>
          </section>
          {showReports?<SavedReports title={form?.form.title??formCode} formCode={formCode}
            reports={reports} error={reportsError} busy={busy} canCreate={editable}
            formReady={form?.form.formCode===formCode}
            onRefresh={()=>setReportsRefresh(value=>value+1)}
            onNew={()=>{resetEditor();setShowReports(false);}}
            onOpen={openDraft}/>:
          <section className="editor-panel">
            <div className="panel-head editor-head"><div><p className="eyebrow">{formCode} · {form?.form.version||'—'}</p>
              <h2>{form?.form.title||'Загрузка формы'}</h2>
              <p>{draft?'Редакция '+draft.revisionNo+' · '+draft.workflowStatus:'Новая форма · заполнение с нуля'}</p>
              </div>
              <div className="toolbar">
                <Tool label="Сохранённые отчёты" disabled={busy} onClick={()=>{
                  if(confirmDiscard()){
                    resetEditor();setShowReports(true);
                  }
                }}><FolderTree size={18}/></Tool>
                <Tool label="Новый документ" disabled={busy} onClick={reset}><FilePlus2 size={18}/></Tool>
                <button className="primary-save" type="button" disabled={busy||!editable||!dirty||commitUncertain||saveUncertain} onClick={save}>{busy?<LoaderCircle className="spin" size={17}/>:<Save size={17}/>} Сохранить</button>

                {!evidence&&<Tool label="Скачать XLSX" disabled={busy||!draft} onClick={download}><Download size={18}/></Tool>}
                {!evidence&&<Tool label="Загрузить XLSX" disabled={busy||!draft||dirty||commitUncertain||saveUncertain} onClick={()=>document.getElementById('xlsx-upload')?.click()}><FileUp size={18}/></Tool>}

              </div></div>
            <div className="editor-body">
              <div className="filters">
                <label>Тип периода<select value={periodType} disabled={isLocked} onChange={e=>{setPeriodType(e.target.value);setPeriodCode(periodExamples[e.target.value]||'')}}>
                  {periodOptions.map(p=><option key={p.code} value={p.code}>{p.title} ({p.code})</option>)}</select></label>
                <label>Учётный период<input value={periodCode} disabled={isLocked} onChange={e=>setPeriodCode(e.target.value)}/></label>
                <label>Валюта<select value={currency} disabled={isLocked} onChange={e=>setCurrency(e.target.value)}>
                  {currencyOptions.map(c=><option key={c.code} value={c.code}>{c.code} · {c.minorUnits}</option>)}</select></label>
              </div>
              <details className="editor-options"><summary><Settings2 size={16}/> Дополнительные настройки и инструменты</summary>
                <div className="editor-options-body"><label>Вид редактора<select value={style} onChange={e=>setStyle(e.target.value as Style)}><option value="EXCEL">Таблица</option><option value="CLASSIC">Обычный</option><option value="DASHBOARD">Карточки</option></select></label>
                  <button type="button" onClick={()=>setShowScope(v=>!v)}>{showScope?'Скрыть контекст UAT':'Контекст организации (UAT)'}</button>
                  <button type="button" disabled={busy||!instanceId} onClick={()=>openDraft(instanceId)}>Проверить запись по UUID</button></div>
              </details>
              <p className="period-caption">Период: {range} · Единица: {selectedCurrency?.code||currency}</p>
              {showScope&&<div className="scope-panel">
                <div><b>Локальный UAT-контекст</b><p>Синтетические UUID; не является авторизацией</p></div>
                {Object.entries(scope).map(([key,value])=><label key={key}>{key}<input value={value} onChange={e=>setScope(old=>({...old,[key]:e.target.value}))} disabled={isLocked}/></label>)}
                <button onClick={()=>{
                  if(!confirmDiscard())return;
                  setScope(newScope());setDraft(null);setInstanceId('');
                  setValues({});setRowEntries([]);setProfileId('');setPreview(null);setPrevious(null);
                  setFile(null);setDirty(false);setSaveUncertain(false);
                  setCommitUncertain(false);saveKeyRef.current=null;
                  setMessage('Новая локальная организация UAT');
                }}>Новая организация UAT</button>
                <label>Instance UUID<input value={instanceId} onChange={e=>setInstanceId(e.target.value)} /></label>
              </div>}
              {!editable&&form&&<div className="warning" role="status"><AlertTriangle size={17}/> Запись отключена: {editorBlockReason[eligibility.reason??'']??'Контракт формы не позволяет изменение.'} Каталог не даёт разрешения на запись. Код: {eligibility.reason??'UNKNOWN'}.</div>}
              {commitUncertain&&<div className="warning"><AlertTriangle size={17}/> Commit мог сохраниться. Откройте экземпляр заново и сверьте номер ревизии перед следующей операцией.</div>}
              {saveUncertain&&<div className="warning"><AlertTriangle size={17}/> Ответ на сохранение не подтверждён. Повторите сохранение с тем же Idempotency-Key; значения заблокированы до сверки.</div>}
              {dirty&&<div className="warning"><AlertTriangle size={17}/> Есть несохранённые изменения.</div>}
              {evidence&&<div className="warning" role="status"><AlertTriangle size={17}/> Реквизиты документа доступны для сохранения. Прикрепление PDF и изображений через этот экран пока не подключено.</div>}
              <div className="sheet-head"><div><b>Показатели отчёта</b><p>Вставка из Excel: Ctrl+V · Enter / ↓ для следующего поля</p></div>
                <span className="total-badge">{fields.length} полей{rowset?' на строку · '+rowEntries.length+' строк':''}</span></div>
              {rowset?<div className="sheet-scroll">
                <p className="period-caption">ROWSET · предварительный типизированный черновик. Вставка диапазона Excel: Ctrl+V.</p>
                <table className="sheet" aria-label="Табличная форма">
                  <thead><tr><th>№</th>{fields.map(f=><th key={f.fieldCode} title={f.fieldCode}>{f.title||f.fieldCode}{f.required?' *':''}</th>)}<th>Действие</th></tr></thead>
                  <tbody>{rowEntries.map((row,i)=><tr key={row.rowId}><td className="seq">{i+1}</td>
                    {fields.map((f,j)=><td key={f.fieldCode}><input
                      aria-label={'Строка '+(i+1)+' · '+(f.title||f.fieldCode)}
                      value={row.values[f.fieldCode]??''} disabled={!editable||busy||saveUncertain||commitUncertain}
                      onChange={e=>editGridCell(row.rowId,f.fieldCode,e.target.value)}
                      onPaste={e=>pasteGrid(e,i,j)}
                      onBlur={e=>{const v=normalize(e.target.value,f.fieldType);
                        if(v!==e.target.value)editGridCell(row.rowId,f.fieldCode,v)}}/></td>)}
                    <td><button type="button" disabled={!editable||busy||saveUncertain||commitUncertain}
                      onClick={()=>removeGridRow(row.rowId)}>Удалить</button></td>
                  </tr>)}</tbody>
                </table>
                <button type="button" disabled={!editable||busy||saveUncertain||commitUncertain||rowEntries.length>=200}
                  onClick={appendGridRow}>Добавить строку ({rowEntries.length}/200)</button>
              </div>:
              style==='EXCEL'?<div className="sheet-scroll"><table className="sheet"><thead><tr><th>№</th><th>Показатель</th><th>Значение</th></tr></thead>
                <tbody>{fields.map(renderRow)}</tbody></table></div>:
                <div className={style==='DASHBOARD'?'cards-layout':'classic-layout'}>
                  {fields.map((f,i)=><div className="metric-card" key={f.fieldCode}>
                    <span className="card-seq">{i+1}</span><label htmlFor={'field-'+f.fieldCode}>{f.title||f.fieldCode}
                    {f.required&&<span className="required"> *</span>}<small>{f.fieldCode} · {f.fieldType}</small></label>
                    <input id={'field-'+f.fieldCode} value={values[f.fieldCode]??''} disabled={!editable||busy||saveUncertain||commitUncertain}
                      onChange={e=>setValue(f.fieldCode,e.target.value)} onPaste={e=>pasteRange(e,i)}
                      onKeyDown={e=>nextCell(e,i)}/></div>)}
                </div>}
              {!evidence&&<details className="import-details"><summary><FileUp size={16}/> Импорт данных из Excel (дополнительно)</summary><div className="import-box">
                <div><b>Excel Round-trip</b><p>Выберите XLSX → Preview без записи → Commit новой ревизии</p></div>
                <input type="file" id="xlsx-upload" accept=".xlsx" onChange={e=>{setFile(e.target.files?.[0]??null);setPreview(null)}}/>
                <div className="import-tools">
                  <Tool label="Предпросмотр XLSX без записи" disabled={!file||!draft||busy||saveUncertain||commitUncertain||dirty} onClick={doPreview}><Eye size={18}/></Tool>
                  <Tool label="Подтвердить импорт новой ревизии" disabled={!preview||busy||commitUncertain||saveUncertain} onClick={doCommit}><Check size={18}/></Tool>
                  <span>{file?.name??'Файл не выбран'}</span></div>
                {preview&&previous&&<div className="preview-diff" aria-label="Сравнение Excel и текущей ревизии">
                  <div className="diff-title"><b>Preview · ревизия {previous.revisionNo} → {previous.revisionNo+1}</b>
                    <span>{Object.keys(preview.values).filter(k=>preview.values[k]!==previous.values[k]).length} изменений</span></div>
                  <div className="diff-scroll"><table><thead><tr><th>Поле</th><th>До</th><th>После</th></tr></thead><tbody>
                    {Object.entries(preview.values).filter(([k,v])=>previous.values[k]!==v).map(([k,v])=>
                      <tr key={k}><td>{fields.find(f=>f.fieldCode===k)?.title||k}</td>
                        <td>{previous.values[k]??'—'}</td><td>{v}</td></tr>)}
                  </tbody></table></div>
                  <p>Preview не создаёт ревизию. Commit станет доступен только для этого файла и текущего snapshot.</p>
                </div>}
              </div></details>}
            </div>
            <div className="editor-footer"><span className="dot"/> {message}<span className="footer-sha">{draft?'SHA256 '+draft.sha256.slice(0,16):'Unsaved'}</span></div>
          </section>}
        </div>
        </>}
        <footer className="disclaimer"><AlertTriangle size={15}/> Локальный стенд. Backend без Security: не публикуйте порт 8086 в сеть и не используйте реальные финансовые данные.</footer>
      </main>
    </div>
  </div>;
}
