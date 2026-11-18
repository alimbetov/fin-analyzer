import {useEffect,useState} from 'react';
import {ChevronRight,Clock3,Download,X} from 'lucide-react';
import type {Draft,Field} from './api';
import {get,request} from './api';

type HistoricalDraft=Draft&{attachment?:{fileName:string;sizeBytes:number;sha256:string}|null};
type Props={instanceId:string;organizationId:string;projectId:string;latestRevision:number;fields:Field[];onClose:()=>void};

export default function RevisionDrawer({instanceId,organizationId,projectId,latestRevision,fields,onClose}:Props){
  const [selected,setSelected]=useState(latestRevision);
  const [revision,setRevision]=useState<HistoricalDraft|null>(null);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  const [downloading,setDownloading]=useState(false);
  useEffect(()=>{setSelected(latestRevision)},[instanceId,latestRevision]);
  useEffect(()=>{
    let active=true;
    setLoading(true);setError('');setRevision(null);
    get<HistoricalDraft>('/drafts/'+encodeURIComponent(instanceId)+'/revisions/'+selected+
      '?organizationId='+encodeURIComponent(organizationId))
      .then(data=>{if(active){setRevision(data);setLoading(false)}})
      .catch(e=>{if(active){setError(String(e));setLoading(false)}});
    return()=>{active=false};
  },[instanceId,organizationId,selected]);
  useEffect(()=>{
    const escape=(event:KeyboardEvent)=>{if(event.key==='Escape')onClose()};
    document.addEventListener('keydown',escape);
    return()=>document.removeEventListener('keydown',escape);
  },[onClose]);
  async function downloadAttachment(){
    if(!revision?.attachment)return;
    setDownloading(true);setError('');
    try{
      const url='/drafts/'+encodeURIComponent(instanceId)+'/revisions/'+selected+'/attachment?'+
        new URLSearchParams({organizationId,projectId});
      const response=await request(url);
      const blob=await response.blob();
      const objectUrl=URL.createObjectURL(blob);
      const anchor=document.createElement('a');anchor.href=objectUrl;
      anchor.download=revision.attachment.fileName;document.body.append(anchor);
      anchor.click();anchor.remove();setTimeout(()=>URL.revokeObjectURL(objectUrl),1000);
    }catch(e){setError(String(e))}
    finally{setDownloading(false)}
  }
  return <div className="history-backdrop" onMouseDown={event=>{if(event.target===event.currentTarget)onClose()}}>
    <aside className="history-drawer" role="dialog" aria-modal="true" aria-label="История редакций">
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
          <div className="history-meta"><strong>Редакция {revision.revisionNo}</strong><span>{revision.periodCode} · {revision.currency}</span></div>
          <div className="history-fields">
            {fields.map(field=><div key={field.fieldCode}><span>{field.title||field.fieldCode}</span>
              <strong>{revision.values[field.fieldCode]||'—'}</strong></div>)}
            {revision.rows.length>0&&<div className="history-rowset"><strong>Табличные строки: {revision.rows.length}</strong>
              {revision.rows.map((row,index)=><details key={row.rowId}><summary>Строка {index+1} <ChevronRight size={14}/></summary>
                {fields.map(field=><p key={field.fieldCode}>{field.title||field.fieldCode}: {row.values[field.fieldCode]||'—'}</p>)}
              </details>)}</div>}
          </div>
          {revision.attachment&&<button className="history-download" type="button" onClick={downloadAttachment} disabled={downloading}>
            <Download size={17}/>{downloading?'Скачивание…':'Скачать '+revision.attachment.fileName}
          </button>}
        </>}
      </section>
      <footer className="history-note">Просмотр не изменяет текущий черновик и не создаёт новую редакцию.</footer>
    </aside>
  </div>;
}
