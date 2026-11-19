import {useEffect,useRef,useState} from 'react';
import type {KeyboardEvent as ReactKeyboardEvent} from 'react';
import {ChevronRight,Clock3,Download,X} from 'lucide-react';
import type {Draft,Field} from './api';
import {get,request} from './api';
import {historyValues} from './revisionView';

type HistoricalDraft=Draft&{attachment?:{fileName:string;sizeBytes:number;sha256:string}|null};
type Props={instanceId:string;organizationId:string;projectId:string;latestRevision:number;formVersion:string;fields:Field[];onClose:()=>void};

export default function RevisionDrawer({instanceId,organizationId,projectId,latestRevision,formVersion,fields,onClose}:Props){
  const [selected,setSelected]=useState(latestRevision);
  const [revision,setRevision]=useState<HistoricalDraft|null>(null);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  const [downloading,setDownloading]=useState(false);
  const [downloadError,setDownloadError]=useState('');
  const dialogRef=useRef<HTMLElement>(null);
  useEffect(()=>{setSelected(latestRevision)},[instanceId,latestRevision]);
  useEffect(()=>{
    let active=true;
    setLoading(true);setError('');setDownloadError('');setRevision(null);
    get<HistoricalDraft>('/drafts/'+encodeURIComponent(instanceId)+'/revisions/'+selected+
      '?organizationId='+encodeURIComponent(organizationId))
      .then(data=>{if(active){setRevision(data);setLoading(false)}})
      .catch(e=>{if(active){setError(String(e));setLoading(false)}});
    return()=>{active=false};
  },[instanceId,organizationId,selected]);
  useEffect(()=>{
    const previousFocus=document.activeElement instanceof HTMLElement?document.activeElement:null;
    dialogRef.current?.querySelector<HTMLButtonElement>('[aria-label="Закрыть историю"]')?.focus();
    return()=>previousFocus?.focus();
  },[]);
  function handleDialogKey(event:ReactKeyboardEvent<HTMLElement>){
    if(event.key==='Escape'){event.preventDefault();event.stopPropagation();onClose();return;}
    if(event.key!=='Tab')return;
    const buttons=Array.from(dialogRef.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')??[]);
    if(!buttons.length)return;
    const first=buttons[0],last=buttons[buttons.length-1];
    if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus()}
    else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus()}
  }
  async function downloadAttachment(){
    if(!revision?.attachment)return;
    setDownloading(true);setDownloadError('');
    try{
      const url='/drafts/'+encodeURIComponent(instanceId)+'/revisions/'+selected+'/attachment?'+
        new URLSearchParams({organizationId,projectId});
      const response=await request(url);
      const blob=await response.blob();
      const objectUrl=URL.createObjectURL(blob);
      const anchor=document.createElement('a');anchor.href=objectUrl;
      anchor.download=revision.attachment.fileName;document.body.append(anchor);
      anchor.click();anchor.remove();setTimeout(()=>URL.revokeObjectURL(objectUrl),1000);
    }catch(e){setDownloadError(String(e))}
    finally{setDownloading(false)}
  }
  return <div className="history-backdrop" onMouseDown={event=>{if(event.target===event.currentTarget)onClose()}}>
    <aside ref={dialogRef} className="history-drawer" role="dialog" aria-modal="true" aria-label="История редакций" onKeyDown={handleDialogKey}>
      <header className="history-heading">
        <div><span className="eyebrow">ИСТОРИЯ ОТЧЁТА</span><h2>Редакции</h2><p>Прошлые версии доступны только для просмотра</p></div>
        <button type="button" aria-label="Закрыть историю" onClick={onClose}><X size={20}/></button>
      </header>
      <nav className="history-versions" aria-label="Выбор редакции">
        {Array.from({length:latestRevision},(_,i)=>latestRevision-i).map(number=>
          <button key={number} className={selected===number?'active':''} type="button"
            aria-current={selected===number?'true':undefined} onClick={()=>setSelected(number)}>
            <Clock3 size={16}/> Редакция {number} {number===latestRevision?'· последняя':''}
          </button>)}
      </nav>
      <section className="history-content" aria-live="polite">
        {loading?<p role="status">Загрузка редакции…</p>:error?<p role="alert">{error}</p>:revision&&<>
          <div className="history-meta"><strong>Редакция {revision.revisionNo}</strong><span>{revision.periodCode} · {revision.currency} · контракт {revision.formVersion}</span></div>
          {revision.formVersion!==formVersion&&<p className="history-version-notice">Версия контракта отличается от текущей. Показаны исходные коды и значения без подстановки новых названий.</p>}
          <div className="history-fields">
            {revision.contentMode!=='ROWSET'&&historyValues(revision.values,fields,revision.formVersion===formVersion).map(field=><div key={field.fieldCode}><span>{field.label}</span>
              <strong>{field.value||'—'}</strong></div>)}
            {revision.rows.length>0&&<div className="history-rowset"><strong>Табличные строки: {revision.rows.length}</strong>
              {revision.rows.map((row,index)=><details key={row.rowId}><summary>Строка {index+1} <ChevronRight size={14}/></summary>
                {historyValues(row.values,fields,revision.formVersion===formVersion).map(field=><p key={field.fieldCode}>{field.label}: {field.value||'—'}</p>)}
              </details>)}</div>}
          </div>
          {downloadError&&<p role="alert" className="history-download-error">{downloadError}</p>}
          {revision.attachment&&<button className="history-download" type="button" onClick={downloadAttachment} disabled={downloading}>
            <Download size={17}/>{downloading?'Скачивание…':'Скачать '+revision.attachment.fileName}
          </button>}
        </>}
      </section>
      <footer className="history-note">Просмотр не изменяет текущий черновик и не создаёт новую редакцию.</footer>
    </aside>
  </div>;
}
