import {useEffect,useRef,useState} from 'react';
import {get,isDefiniteRejection,post} from './api';

type Money=string;
type ContractualLine={
  installmentNo:number;accrualFrom:string;dueAt:string;
  principalDue:Money;contractualInterestDue:Money;feesDue:Money;totalCashDue:Money
};
type AmendedLine={
  installmentNo:number;accrualFrom:string;dueAt:string;openingPrincipal:Money;
  annualRatePct:Money;contractualInterestRaw:Money;cashInterestDue:Money;
  capitalizedInterest:Money;scheduledPrincipal:Money;extraPrincipal:Money;
  feesDue:Money;closingPrincipal:Money;totalCashDue:Money;
  feeBreakdown:Record<string,Money>
};
type Amendment={
  amendmentRevisionId:string;parentScheduleRevisionId:string;
  exactSourceRevisionId:string;sourceSha256:string;termsSha256:string;
  parentResultSha256:string;engineDescriptorSha256:string;
  eventSha256:string;scenarioSha256:string;resultSha256:string;
  event:{kind:string;effectiveDate:string;prepaymentAmount:Money|null;
    prepaymentPolicy:string|null;graceThroughDate:string|null;
    graceMode:string|null;revisedAnnualRatePct:Money|null};
  result:{method:string;actualMaturityDate:string;lines:AmendedLine[];
    originalOpeningPrincipal:Money;totalScheduledPrincipal:Money;
    totalExtraPrincipal:Money;totalCapitalizedInterest:Money;
    totalCashInterest:Money;totalScheduledFees:Money;totalCashDue:Money;
    finalPrincipal:Money;state:string};
  commandResult:string;financiallyApproved:boolean;productionAdmitted:boolean
};
type Summary={
  amendmentRevisionId:string;eventKind:string;eventSha256:string;
  resultSha256:string;actualMaturity:string;state:string
};
type Props={
  organizationId:string;projectId:string;parentScheduleRevisionId:string;
  exactSourceRevisionId:string;parentResultSha256:string;
  baseLines:ContractualLine[];baseCash:Money;
  baseInterest:Money;baseMaturity:string;
  disabled:boolean
};
function money(x:Money):string{
  const [whole,rest]=String(x).split('.');
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g,' ')+(rest?','+rest:'');
}

export default function LoanAmendmentPanel({
  organizationId,projectId,parentScheduleRevisionId,exactSourceRevisionId,
  parentResultSha256,baseLines,baseCash,baseInterest,baseMaturity,disabled
}:Props){
  const [kind,setKind]=useState('PREPAYMENT');
  const [effectiveDate,setEffectiveDate]=useState(baseLines[0]?.dueAt??'');
  const [amount,setAmount]=useState('');
  const [policy,setPolicy]=useState('REDUCE_TERM');
  const [graceThrough,setGraceThrough]=useState(baseLines[0]?.dueAt??'');
  const [graceMode,setGraceMode]=useState('PRINCIPAL_ONLY');
  const [rate,setRate]=useState('');
  const [selected,setSelected]=useState('');
  const [history,setHistory]=useState<Summary[]>([]);
  const [view,setView]=useState<Amendment|null>(null);
  const [busy,setBusy]=useState(false);
  const [message,setMessage]=useState('');
  const [error,setError]=useState('');
  const keyRef=useRef<string|null>(null);
  const qs=new URLSearchParams({organizationId,projectId,
    parentScheduleRevisionId}).toString();
  useEffect(()=>{
    let active=true;
    setSelected('');setHistory([]);setView(null);setMessage('');setError('');
    keyRef.current=null;
    get<Summary[]>('/uat/a05/amendments?'+qs)
      .then(items=>{if(active)setHistory(items)})
      .catch(e=>{if(active)setError(String(e))});
    return()=>{active=false};
  },[parentScheduleRevisionId,qs]);

  function change(fn:()=>void){
    fn();keyRef.current=null;setMessage('');
  }
  function verified(x:Amendment):Amendment {
    if(x.parentScheduleRevisionId!==parentScheduleRevisionId
      ||x.exactSourceRevisionId!==exactSourceRevisionId
      ||x.parentResultSha256!==parentResultSha256
      ||x.result.state!=='UAT_SCENARIO_NOT_PAYMENT_FACT'
      ||x.financiallyApproved||x.productionAdmitted
      ||!/^0(?:\.0+)?$/.test(x.result.finalPrincipal)
      ||!x.resultSha256.match(/^[0-9a-f]{64}$/))
      throw new Error('A05 R1.2: источник, версия или SHA сценария не совпадают.');
    return x;
  }
  async function calculate(){
    if(disabled||busy)return;
    setBusy(true);setError('');setMessage('');
    if(!keyRef.current)keyRef.current=crypto.randomUUID();
    const event={
      kind,effectiveDate,
      prepaymentAmount:kind==='PREPAYMENT'?amount:null,
      prepaymentPolicy:kind==='PREPAYMENT'?policy:null,
      graceThroughDate:kind==='GRACE'?graceThrough:null,
      graceMode:kind==='GRACE'?graceMode:null,
      revisedAnnualRatePct:kind==='RATE_STEP'?rate:null
    };
    try{
      const first=verified(await post<Amendment>('/uat/a05/amendments',{
        organizationId,projectId,parentScheduleRevisionId,event
      },keyRef.current));
      const params=new URLSearchParams({organizationId,projectId});
      const check=verified(await get<Amendment>(
        '/uat/a05/amendments/'+encodeURIComponent(first.amendmentRevisionId)
        +'?'+params));
      if(check.resultSha256!==first.resultSha256
        ||check.scenarioSha256!==first.scenarioSha256)
        throw new Error('Сценарий не воспроизводится из PostgreSQL.');
      setView(check);setSelected(check.amendmentRevisionId);
      keyRef.current=null;
      setHistory(await get<Summary[]>('/uat/a05/amendments?'+qs));
      setMessage('Сценарий сохранён · SHA '+check.resultSha256.slice(0,12));
    }catch(e){
      if(isDefiniteRejection(e))keyRef.current=null;
      setError(e instanceof Error?e.message:String(e));
    }finally{setBusy(false)}
  }
  async function openExact(id:string){
    setSelected(id);setMessage('');setError('');
    if(!id){setView(null);return;}
    setBusy(true);
    try{
      const params=new URLSearchParams({organizationId,projectId});
      setView(verified(await get<Amendment>(
        '/uat/a05/amendments/'+encodeURIComponent(id)+'?'+params)));
    }catch(e){
      setView(null);setError(e instanceof Error?e.message:String(e));
    }finally{setBusy(false)}
  }
  const blocked=disabled||busy;
  return <section className="a05-amendment" aria-label="Изменение кредитного графика A05">
    <h4>A05-R1.2 · Изменения условий и сценарии</h4>
    <p className="period-caption">Каждое изменение — отдельная неизменяемая
      расчётная редакция поверх выбранного графика. Это не фактический платёж A01.</p>
    <div className="a05-grid">
      <label className="a05-field">Тип изменения
        <select aria-label="Тип изменения A05" value={kind}
          disabled={blocked} onChange={e=>change(()=>setKind(e.target.value))}>
          <option value="PREPAYMENT">Досрочное погашение</option>
          <option value="GRACE">Льготный период</option>
          <option value="RATE_STEP">Изменение фиксированной ставки</option>
        </select>
      </label>
      {kind==='RATE_STEP'
        ?<label className="a05-field">Дата изменения ставки
          <input aria-label="Дата изменения ставки" type="date"
            value={effectiveDate} disabled={blocked}
            onChange={e=>change(()=>setEffectiveDate(e.target.value))}/></label>
        :<label className="a05-field">Дата платежа / начала изменения
          <select aria-label="Дата изменения A05" value={effectiveDate}
            disabled={blocked}
            onChange={e=>change(()=>setEffectiveDate(e.target.value))}>
            {baseLines.map(line=><option key={line.dueAt} value={line.dueAt}>
              {line.dueAt}</option>)}
          </select></label>}
      {kind==='PREPAYMENT'&&<>
        <label className="a05-field">Дополнительное погашение основного долга, KZT
          <input aria-label="Досрочное погашение, KZT" value={amount}
            disabled={blocked} onChange={e=>change(()=>setAmount(e.target.value))}
            placeholder="400.00"/></label>
        <label className="a05-field">Пересчёт после погашения
          <select aria-label="Стратегия досрочного погашения" value={policy}
            disabled={blocked} onChange={e=>change(()=>setPolicy(e.target.value))}>
            <option value="REDUCE_TERM">Сократить срок</option>
            <option value="REDUCE_INSTALLMENT">Сократить платёж</option>
          </select></label>
      </>}
      {kind==='GRACE'&&<>
        <label className="a05-field">Последний платёж льготного периода
          <select aria-label="Окончание льготного периода" value={graceThrough}
            disabled={blocked} onChange={e=>change(()=>setGraceThrough(e.target.value))}>
            {baseLines.slice(0,-1).map(line=><option key={line.dueAt} value={line.dueAt}>
              {line.dueAt}</option>)}
          </select></label>
        <label className="a05-field">Режим льготного периода
          <select aria-label="Режим льготного периода" value={graceMode}
            disabled={blocked} onChange={e=>change(()=>setGraceMode(e.target.value))}>
            <option value="PRINCIPAL_ONLY">Отсрочка основного долга, проценты платятся</option>
            <option value="FULL_CAPITALIZE">Отсрочка с капитализацией процентов</option>
          </select></label>
      </>}
      {kind==='RATE_STEP'&&
        <label className="a05-field">Новая фиксированная ставка, % годовых
          <input aria-label="Новая ставка, % годовых" value={rate}
            disabled={blocked} onChange={e=>change(()=>setRate(e.target.value))}
            placeholder="24.000000"/></label>}
    </div>
    <div className="a05-schedule-actions">
      <button type="button" className="primary-save" disabled={blocked}
        onClick={()=>void calculate()}>
        {busy?'Проверка…':'Рассчитать и сохранить изменение'}
      </button>
      <label>История изменений
        <select aria-label="Редакция изменения A05" value={selected}
          disabled={blocked} onChange={e=>void openExact(e.target.value)}>
          <option value="">Выберите неизменяемый сценарий</option>
          {history.map(h=><option key={h.amendmentRevisionId}
              value={h.amendmentRevisionId}>
            {h.eventKind} · {h.actualMaturity} · {h.resultSha256.slice(0,12)}
          </option>)}
        </select></label>
    </div>
    {error&&<p role="alert">{error}</p>}
    {message&&<p role="status">{message}</p>}
    {view&&<div className="a05-result">
      <div className="a05-totals">
        <span>Исходное окончание <b>{baseMaturity}</b></span>
        <span>Новое окончание <b>{view.result.actualMaturityDate}</b></span>
        <span>Исходные платежи <b>{money(baseCash)} KZT</b></span>
        <span>Платежи по сценарию <b>{money(view.result.totalCashDue)} KZT</b></span>
        <span>Исходные проценты <b>{money(baseInterest)} KZT</b></span>
        <span>Проценты по сценарию <b>{money(view.result.totalCashInterest)} KZT</b></span>
        <span>Досрочно погашено <b>{money(view.result.totalExtraPrincipal)} KZT</b></span>
        <span>Капитализация <b>{money(view.result.totalCapitalizedInterest)} KZT</b></span>
      </div>
      <div className="a05-table-scroll">
        <table aria-label="Пересчитанный кредитный график">
          <thead><tr><th>№</th><th>Дата</th><th>Ставка, %</th>
            <th>Входящий</th><th>Проценты</th><th>Капитализация</th>
            <th>Регулярный основной долг</th><th>Досрочно</th>
            <th>Комиссии</th><th>Платёж</th><th>Остаток</th></tr></thead>
          <tbody>{view.result.lines.map(line=><tr key={line.installmentNo}>
            <td>{line.installmentNo}</td><td>{line.dueAt}</td>
            <td>{money(line.annualRatePct)}</td>
            <td>{money(line.openingPrincipal)}</td>
            <td>{money(line.cashInterestDue)}</td>
            <td>{money(line.capitalizedInterest)}</td>
            <td>{money(line.scheduledPrincipal)}</td>
            <td>{money(line.extraPrincipal)}</td>
            <td title={Object.entries(line.feeBreakdown).map(
              ([k,v])=>k+'='+v).join('; ')}>{money(line.feesDue)}</td>
            <td>{money(line.totalCashDue)}</td>
            <td>{money(line.closingPrincipal)}</td>
          </tr>)}</tbody>
        </table>
      </div>
      <details className="a05-lineage">
        <summary>Объяснение сценария: ревизии, SHA и расчётная политика</summary>
        <dl>
          <dt>Исходный график</dt><dd>{view.parentScheduleRevisionId}</dd>
          <dt>SHA исходного результата</dt><dd>{view.parentResultSha256}</dd>
          <dt>Редакция C05</dt><dd>{view.exactSourceRevisionId}</dd>
          <dt>SHA C05</dt><dd>{view.sourceSha256}</dd>
          <dt>SHA условий A05</dt><dd>{view.termsSha256}</dd>
          <dt>SHA алгоритма</dt><dd>{view.engineDescriptorSha256}</dd>
          <dt>SHA изменения</dt><dd>{view.eventSha256}</dd>
          <dt>SHA сценария</dt><dd>{view.scenarioSha256}</dd>
          <dt>SHA результата</dt><dd>{view.resultSha256}</dd>
          <dt>Статус</dt><dd>{view.result.state}</dd>
        </dl>
      </details>
      <small>Только модель договорных сценариев UAT. Индексная плавающая ставка,
        реальные платежи, IFRS EIR, ГЭСВ РК и covenant DSCR требуют отдельных
        квалифицированных источников и методик.</small>
    </div>}
  </section>;
}
