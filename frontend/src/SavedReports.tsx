import {useEffect,useRef} from 'react';
import {ChevronRight,FilePlus2,FolderOpen,RefreshCw} from 'lucide-react';

export type SavedReport = {
  instanceId:string;
  periodType:string;
  periodCode:string;
  periodStart:string|null;
  currency:string;
  coverage:string;
  reportingBasis:string;
  dataSpace:string;
  revisionNo:number;
  workflowStatus:string;
};

const periodNames:Record<string,string> = {
  D:'День',W:'Неделя',DEC:'Декада',M:'Месяц',Q:'Квартал',H:'Полугодие',Y:'Год'
};

type Props = {
  title:string;
  formCode:string;
  reports:SavedReport[]|null;
  error:string;
  busy:boolean;
  canCreate:boolean;
  formReady:boolean;
  onRefresh:()=>void;
  onNew:()=>void;
  onOpen:(instanceId:string)=>void;
};

export default function SavedReports({
  title,formCode,reports,error,busy,canCreate,formReady,onRefresh,onNew,onOpen
}:Props){
  const panel=useRef<HTMLElement>(null);
  useEffect(()=>{
    panel.current?.focus({preventScroll:true});
    panel.current?.scrollIntoView({block:'nearest'});
  },[formCode]);

  const years=new Map<string,Map<string,SavedReport[]>>();
  const byDate=[...(reports??[])].sort((a,b)=>
    (b.periodStart??b.periodCode).localeCompare(a.periodStart??a.periodCode)||
    b.periodCode.localeCompare(a.periodCode)||a.periodType.localeCompare(b.periodType));
  for(const report of byDate){
    const year=report.periodCode.slice(0,4);
    const periods=years.get(year)??new Map<string,SavedReport[]>();
    const key=report.periodType+' '+report.periodCode;
    periods.set(key,[...(periods.get(key)??[]),report]);
    years.set(year,periods);
  }
  const sortedYears=[...years.entries()].sort(([a],[b])=>b.localeCompare(a));
  return <section ref={panel} tabIndex={-1} className="editor-panel saved-reports" aria-label="Сохранённые отчёты">
    <div className="panel-head">
      <div><p className="eyebrow">{formCode} · {title}</p><h2>Сохранённые отчёты</h2></div>
      <button type="button" className="tool" aria-label="Обновить сохранённые отчёты" onClick={onRefresh} disabled={busy}><RefreshCw size={18}/></button>
    </div>
    <div className="saved-reports-body">
      <div className="saved-reports-actions">
        <button type="button" onClick={onNew} disabled={busy||!formReady}><FilePlus2 size={17}/>{canCreate?'Создать отчёт':'Открыть шаблон'}</button>
      </div>
      {error?<p role="alert">{error}</p>:reports===null?<p role="status">Загрузка отчётов…</p>:
        reports.length===0?<p className="empty">В текущем проекте ещё нет сохранённых отчётов этой формы.</p>:
        sortedYears.map(([year,periods],yearIndex)=><details className="saved-year" key={year} open={yearIndex===0}>
          <summary><ChevronRight size={16}/><strong>{year}</strong><span>{periods.size} периодов</span></summary>
          {[...periods.entries()].map(([key,entries])=><details className="saved-period" key={key} open>
            <summary><ChevronRight size={16}/><strong>{entries[0].periodCode}</strong><span>{periodNames[entries[0].periodType]??entries[0].periodType}</span></summary>
            {entries.map(report=><button type="button" className="saved-report" key={report.instanceId} disabled={busy}
              onClick={()=>onOpen(report.instanceId)}>
              <FolderOpen size={18}/><span><strong>Открыть отчёт · {report.currency}</strong>
                <small>Редакция {report.revisionNo} · {report.workflowStatus} · {report.reportingBasis} · {report.coverage} · {report.dataSpace}</small></span>
              <ChevronRight size={16}/>
            </button>)}
          </details>)}
        </details>)}
    </div>
  </section>;
}
