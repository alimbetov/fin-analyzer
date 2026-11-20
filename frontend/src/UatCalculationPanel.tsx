import {useEffect,useState} from 'react';
import type {CalcUatPreview,Draft} from './api';
import {get,post} from './api';

/**
 * DC-R2.5: one React consumer for the same pinned source / math kernel
 * preview for F02, C01 and C02. This performs NO client-side finance math.
 */
type FormCode='F02'|'C01'|'C02';
type Metric={code:string;title:string;formula:string};
const metrics:Record<FormCode,Metric[]>={
  F02:[
    {code:'grossProfit',title:'Валовая прибыль',formula:'netRevenue − cogs'},
    {code:'grossMargin',title:'Валовая рентабельность',formula:'grossProfit / netRevenue'},
    {code:'operatingResult',title:'Операционный результат',formula:'grossProfit − operatingExpenses'}
  ],
  C01:[
    {code:'C01.gross',title:'Валовая дебиторская задолженность',formula:'Σ gross'},
    {code:'C01.allowance',title:'Резерв, введённый пользователем',formula:'Σ allowance; ECL не рассчитывается'},
    {code:'C01.netReceivablesProposed',title:'Чистая дебиторская задолженность',formula:'gross − allowance'},
    {code:'C01.declaredOverdue',title:'Заявленная просрочка',formula:'Σ overdue; контроль dueAt, без распределения платежей'},
    {code:'C01.declaredOverdueShare',title:'Доля заявленной просрочки',formula:'overdue / gross'}
  ],
  C02:[
    {code:'C02.totalOutstandingProposed',title:'Кредиторская задолженность',formula:'Σ outstanding'},
    {code:'C02.declaredOverdue',title:'Заявленная просрочка',formula:'Σ overdue; контроль dueAt'},
    {code:'C02.declaredOverdueShare',title:'Доля заявленной просрочки',formula:'overdue / outstanding'}
  ]
};

type Props={
  formCode:FormCode;draft:Draft|null;
  dirty:boolean;busy:boolean;saveUncertain:boolean;commitUncertain:boolean
};
const ready=(draft:Draft|null,code:FormCode)=>
  Boolean(draft?.revisionId&&draft.formCode===code);

function outputLabel(item:CalcUatPreview['outputs'][string]):string{
  return item.state==='PRESENT'
    ?(item.value??'—')+' '+(item.unit==='RATIO'?'(доля)':item.unit)
    :item.state==='MISSING'
      ?'Нет исходных данных: '+(item.errorCode??'MISSING')
      :'Не определено: '+(item.errorCode??'INVALID');
}

export default function UatCalculationPanel({
  formCode,draft,dirty,busy,saveUncertain,commitUncertain
}:Props){
  const [current,setCurrent]=useState<CalcUatPreview|null>(null);
  const [historic,setHistoric]=useState<CalcUatPreview|null>(null);
  const [historicNo,setHistoricNo]=useState(1);
  const [working,setWorking]=useState(false);
  const [error,setError]=useState('');
  const [historyError,setHistoryError]=useState('');

  useEffect(()=>{
    setCurrent(null);setHistoric(null);setError('');setHistoryError('');
    setHistoricNo(Math.max(1,(draft?.revisionNo??1)-1));
  },[formCode,draft?.instanceId,draft?.revisionId,draft?.sha256,dirty]);

  const disabled=busy||working||dirty||saveUncertain||commitUncertain
    ||!ready(draft,formCode);
  const currentValid=current&&draft
    &&current.sourceRevisionId===draft.revisionId
    &&current.sourceContentSha256===draft.sha256
    &&current.instanceId===draft.instanceId
    &&current.formCode===formCode&&!dirty&&!saveUncertain&&!commitUncertain;

  async function run(snapshot:Draft):Promise<CalcUatPreview>{
    if(!snapshot.revisionId)throw new Error('Для расчёта необходим точный UUID ревизии.');
    const result=await post<CalcUatPreview>('/uat/calculations/preview',{
      formCode,organizationId:snapshot.organizationId,
      projectId:snapshot.projectId,exactRevisionId:snapshot.revisionId
    });
    if(result.sourceRevisionId!==snapshot.revisionId
        ||result.sourceContentSha256!==snapshot.sha256
        ||result.instanceId!==snapshot.instanceId
        ||result.formCode!==formCode
        ||result.reportingBasis!==snapshot.reportingBasis
        ||result.currency!==snapshot.currency
        ||result.executionState!=='FIN_INTAKE_UAT_PREVIEW_UNQUALIFIED'
        ||result.financiallyApproved||result.productionAdmitted
        ||!metrics[formCode].every(m=>Boolean(result.outputs[m.code]))){
      throw new Error('Ответ расчёта не совпадает с закреплённой ревизией, SHA или финансовым контекстом.');
    }
    return result;
  }

  async function calculate(){
    if(disabled||!draft)return;
    setWorking(true);setError('');setCurrent(null);setHistoric(null);
    try{setCurrent(await run(draft))}
    catch(e){setError(e instanceof Error?e.message:String(e))}
    finally{setWorking(false)}
  }

  async function compareHistorical(){
    if(disabled||!draft||!currentValid||historicNo>=draft.revisionNo)return;
    setWorking(true);setHistoryError('');setHistoric(null);
    try{
      const older=await get<Draft>('/drafts/'+encodeURIComponent(draft.instanceId)
        +'/revisions/'+historicNo+'?organizationId='+encodeURIComponent(draft.organizationId));
      if(older.revisionNo!==historicNo||older.instanceId!==draft.instanceId
          ||older.organizationId!==draft.organizationId||older.projectId!==draft.projectId
          ||older.formCode!==formCode||!older.revisionId)
        throw new Error('Историческая редакция не соответствует исходному финансовому контексту.');
      const past=await run(older);
      // Never compare different basis, unit/currency, period or methodology:
      // different source SHA is expected, methodology drift is not.
      if(past.calculationMethodSha256!==current.calculationMethodSha256
          ||past.currency!==current.currency
          ||past.periodCode!==current.periodCode
          ||past.reportingBasis!==current.reportingBasis
          ||past.dataSpace!==current.dataSpace)
        throw new Error('Редакции рассчитаны по разным методикам, периодам или базам. Сравнение запрещено.');
      setHistoric(past);
    }catch(e){setHistoryError(e instanceof Error?e.message:String(e))}
    finally{setWorking(false)}
  }

  return <section className="f02-uat-calculation" aria-label={'UAT-расчёт '+formCode}>
    <div className="f02-uat-calculation-head">
      <div>
        <h3>Расчёт {formCode} из сохранённой ревизии</h3>
        <small>Только локальный UAT · без финансового S2 и записи в fin_fact</small>
      </div>
      <button type="button" onClick={()=>void calculate()} disabled={disabled}>
        Рассчитать {formCode}
      </button>
    </div>
    {!draft?.revisionId&&<p>Сначала сохраните исходные значения {formCode}.</p>}
    {dirty&&<p>Есть несохранённые изменения. Для расчёта создайте новую ревизию.</p>}
    {error&&<p role="alert">{error}</p>}
    {currentValid&&<div className="f02-uat-results">
      {metrics[formCode].map(metric=>{
        const item=current.outputs[metric.code];
        return <div key={metric.code} className="f02-uat-result">
          <strong>{metric.title}</strong>
          <span>{outputLabel(item)}</span>
          <small>{metric.formula}</small>
        </div>;
      })}
      <details><summary>Как получено · исходная ревизия и контроль SHA</summary>
        <dl>
          <dt>Ревизия</dt><dd>{current.sourceRevisionId}</dd>
          <dt>Исходный SHA</dt><dd>{current.sourceContentSha256}</dd>
          <dt>Срез (AS_OF)</dt><dd>{current.asOfDate??'Периодический поток'}</dd>
          <dt>Период и учётная база</dt>
          <dd>{current.periodCode} · {current.reportingBasis} · {current.currency}</dd>
          <dt>Строк в исходной ревизии</dt><dd>{current.sourceRowCount}</dd>
          <dt>Методика</dt><dd>{current.methodCode} · {current.calculationMethodSha256}</dd>
          <dt>Исходные данные SHA</dt><dd>{current.pinnedSourceSha256}</dd>
          <dt>Результат SHA</dt><dd>{current.previewSha256}</dd>
        </dl>
        {current.diagnostics.map(code=>
          <p className="uat-calc-diagnostic" key={code}>{code}</p>)}
      </details>
    </div>}
    {currentValid&&draft.revisionNo>1&&
      <div className="uat-calc-history" aria-label="История и сравнение расчётов">
        <label>Сравнить с редакцией
          <select value={historicNo} onChange={e=>{setHistoricNo(Number(e.target.value));setHistoric(null)}}>
            {Array.from({length:draft.revisionNo-1},(_,i)=>draft.revisionNo-1-i).map(n=>
              <option key={n} value={n}>Редакция {n}</option>)}
          </select>
        </label>
        <button type="button" onClick={()=>void compareHistorical()} disabled={disabled}>
          Рассчитать выбранную редакцию
        </button>
        {historyError&&<p role="alert">{historyError}</p>}
        {historic&&<div className="uat-calc-compare">
          <p>Сравнение без изменения текущей редакции. Числовое изменение не пересчитывается в браузере.</p>
          <table><thead><tr><th>Показатель</th><th>Редакция {historicNo}</th><th>Текущая</th></tr></thead>
            <tbody>{metrics[formCode].map(metric=>
              <tr key={metric.code}>
                <th>{metric.title}</th>
                <td>{outputLabel(historic.outputs[metric.code])}</td>
                <td>{outputLabel(current.outputs[metric.code])}</td>
              </tr>)}</tbody>
          </table>
          <small>Исторический SHA: {historic.sourceContentSha256}</small>
        </div>}
      </div>}
  </section>;
}
