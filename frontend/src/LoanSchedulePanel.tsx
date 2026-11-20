import {useEffect,useRef,useState} from 'react';
import {get,isDefiniteRejection,post} from './api';

type Money=string|number;
type Installment={dueDate:string;principalDue:string};
type ScheduleLine={
  installmentNo:number;accrualFrom:string;dueAt:string;
  yearFractionRaw:Money;openingPrincipal:Money;contractualInterestRaw:Money;
  contractualInterestDue:Money;principalDue:Money;feesDue:Money;
  closingPrincipal:Money;totalCashDue:Money;
  feeBreakdown:Record<string,Money>
};
type Funding={
  drawdown:Money;financedFees:Money;withheldFees:Money;upfrontPaidFees:Money;
  netCashProceeds:Money;openingLiability:Money
};
type Result={
  repaymentMethod:string;currency:string;dayCountConvention:string;
  roundingScale:number;roundingMode:string;
  funding:Funding;lines:ScheduleLine[];
  totalPrincipal:Money;totalContractualInterest:Money;
  totalScheduledFees:Money;totalScheduledCash:Money;finalBalance:Money;
  diagnostics:string[]
};
type ScheduleView={
  scheduleRevisionId:string;exactSourceRevisionId:string;
  contractRevisionId:string;appContractRevisionId:string;
  sourceContentSha256:string;termsSha256:string;
  engineDescriptorSha256:string;customInputSha256:string;
  variantSha256:string;resultSha256:string;state:string;
  result:Result;commandResult:string;
  financiallyApproved:boolean;productionAdmitted:boolean
};
type History={
  scheduleRevisionId:string;repaymentMethod:string;variantSha256:string;
  resultSha256:string;createdAt:string;state:string
};
type Props={
  organizationId:string;projectId:string;exactSourceRevisionId:string;
  sourceContentSha256:string;termsSha256:string;repaymentMethod:string;
  dirty:boolean;busy:boolean;saveUncertain:boolean;commitUncertain:boolean
};

function fmt(value:Money):string{
  const text=String(value);
  const [integer,fraction]=text.split('.');
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g,' ')+
    (fraction?','+fraction:'');
}
function customLines(text:string):Installment[]{
  if(!text.trim())throw new Error('CUSTOM требует дат и сумм каждой выплаты.');
  return text.trim().split(/\r?\n/).map((line,i)=>{
    const match=/^(\d{4}-\d{2}-\d{2})\s*;\s*(\d{1,26}(?:\.\d{1,2})?)$/
      .exec(line.trim());
    if(!match)throw new Error('CUSTOM, строка '+(i+1)+
      ': ожидается YYYY-MM-DD;сумма (без разделителей тысяч).');
    return {dueDate:match[1],principalDue:match[2]};
  });
}

export default function LoanSchedulePanel({
  organizationId,projectId,exactSourceRevisionId,
  sourceContentSha256,termsSha256,repaymentMethod,
  dirty,busy,saveUncertain,commitUncertain
}:Props){
  const [custom,setCustom]=useState('');
  const [result,setResult]=useState<ScheduleView|null>(null);
  const [history,setHistory]=useState<History[]>([]);
  const [working,setWorking]=useState(false);
  const [error,setError]=useState('');
  const [message,setMessage]=useState('');
  const idem=useRef<string|null>(null);
  const [selected,setSelected]=useState('');
  const blocked=busy||working||dirty||saveUncertain||commitUncertain;
  const query=new URLSearchParams({
    organizationId,projectId,exactSourceRevisionId
  }).toString();

  useEffect(()=>{
    let active=true;
    setResult(null);setSelected('');setHistory([]);setError('');setMessage('');
    idem.current=null;setCustom('');
    get<History[]>('/uat/a05/schedules?'+query).then(rows=>{
      if(active)setHistory(rows);
    }).catch(e=>{if(active)setError(String(e))});
    return()=>{active=false};
  },[exactSourceRevisionId,query]);

  function verified(next:ScheduleView):ScheduleView{
    if(next.exactSourceRevisionId!==exactSourceRevisionId
      ||next.sourceContentSha256!==sourceContentSha256
      ||next.termsSha256!==termsSha256
      ||next.state!=='UAT_UNQUALIFIED'
      ||next.financiallyApproved||next.productionAdmitted
      ||String(next.result.finalBalance)!=='0'
      ||!next.resultSha256?.match(/^[0-9a-f]{64}$/))
      throw new Error('A05: несоответствие сохранённого графика, источника или SHA.');
    return next;
  }

  async function calculate(){
    if(blocked)return;
    setWorking(true);setError('');setMessage('');
    if(!idem.current)idem.current=crypto.randomUUID();
    try{
      const command={
        organizationId,projectId,exactSourceRevisionId,
        customInstallments:repaymentMethod==='CUSTOM'?customLines(custom):[]
      };
      const saved=verified(await post<ScheduleView>(
        '/uat/a05/schedules',command,idem.current));
      const params=new URLSearchParams({organizationId,projectId});
      const exact=verified(await get<ScheduleView>(
        '/uat/a05/schedules/'+encodeURIComponent(saved.scheduleRevisionId)+'?'+params));
      if(exact.resultSha256!==saved.resultSha256||
        exact.variantSha256!==saved.variantSha256)
        throw new Error('A05: результат в PostgreSQL не воспроизводится.');
      setResult(exact);setSelected(exact.scheduleRevisionId);idem.current=null;
      setMessage('График сохранён · '+exact.result.lines.length+
        ' платежей · SHA '+exact.resultSha256.slice(0,12));
      setHistory(await get<History[]>('/uat/a05/schedules?'+query));
    }catch(e){
      if(isDefiniteRejection(e))idem.current=null;
      setError(e instanceof Error?e.message:String(e));
    }finally{setWorking(false)}
  }

  async function openExisting(id:string){
    setSelected(id);setError('');setMessage('');
    if(!id){setResult(null);return;}
    setWorking(true);
    try{
      const params=new URLSearchParams({organizationId,projectId});
      const restored=verified(await get<ScheduleView>(
        '/uat/a05/schedules/'+encodeURIComponent(id)+'?'+params));
      setResult(restored);
      setMessage('Открыта неизменяемая редакция '+id);
    }catch(e){
      setResult(null);setError(e instanceof Error?e.message:String(e));
    }finally{setWorking(false)}
  }

  return <section className="a05-schedule" aria-label="График платежей A05">
    <header>
      <h3>График кредитных платежей · A05-R1.1</h3>
      <p className="period-caption">Исходная C05: {exactSourceRevisionId} ·
        {repaymentMethod} · Договорный денежный график, не IFRS EIR и не ГЭСВ РК.</p>
    </header>
    {repaymentMethod==='CUSTOM'&&<label className="a05-custom">Произвольные даты и суммы основного долга
      <textarea aria-label="Платежи CUSTOM" rows={4}
        placeholder={'2027-01-31;100.00\n2027-03-02;0.00\n2027-04-01;900.00'}
        value={custom} onChange={e=>{setCustom(e.target.value);idem.current=null;}}
        disabled={blocked}/>
      <small>Каждая строка: YYYY-MM-DD;principal. Последняя дата должна совпадать
        с окончанием договора; сумма principal — с обязательством, включая финансируемые комиссии.
        Проценты рассчитывает backend по точным датам.</small>
    </label>}
    <div className="a05-schedule-actions">
      <button className="primary-save" type="button" disabled={blocked}
        onClick={()=>void calculate()}>
        {working?'Проверка и сохранение…':'Рассчитать и сохранить график'}
      </button>
      <label>История расчётных редакций
        <select aria-label="Версия графика A05" value={selected}
          onChange={e=>void openExisting(e.target.value)} disabled={blocked}>
          <option value="">Выберите сохранённую редакцию</option>
          {history.map(h=><option key={h.scheduleRevisionId} value={h.scheduleRevisionId}>
            {h.repaymentMethod} · {h.resultSha256.slice(0,12)} · {h.createdAt.slice(0,10)}
          </option>)}
        </select>
      </label>
    </div>
    {dirty&&<p role="status">
      Условия изменены, но не сохранены. Сначала сохраните новую редакцию C05 + A05.
    </p>}
    {error&&<p role="alert">{error}</p>}
    {message&&<p role="status">{message}</p>}
    {result&&<div className="a05-result">
      <div className="a05-totals">
        <span>Обязательство <b>{fmt(result.result.funding.openingLiability)} KZT</b></span>
        <span>Основной долг <b>{fmt(result.result.totalPrincipal)} KZT</b></span>
        <span>Проценты <b>{fmt(result.result.totalContractualInterest)} KZT</b></span>
        <span>Комиссии по графику <b>{fmt(result.result.totalScheduledFees)} KZT</b></span>
        <span>Всего по графику <b>{fmt(result.result.totalScheduledCash)} KZT</b></span>
        <span>Остаток <b>{fmt(result.result.finalBalance)} KZT</b></span>
      </div>
      <div className="a05-table-scroll">
        <table aria-label="Датированный график кредитных платежей">
          <thead><tr>
            <th>№</th><th>Начисление с</th><th>Срок платежа</th>
            <th>Входящий долг</th><th>Проценты</th><th>Основной долг</th>
            <th>Комиссии</th><th>Платёж</th><th>Остаток</th>
          </tr></thead>
          <tbody>{result.result.lines.map(line=><tr key={line.installmentNo}>
            <td>{line.installmentNo}</td><td>{line.accrualFrom}</td>
            <td>{line.dueAt}</td><td>{fmt(line.openingPrincipal)}</td>
            <td>{fmt(line.contractualInterestDue)}</td>
            <td>{fmt(line.principalDue)}</td><td title={
              Object.entries(line.feeBreakdown).map(([k,v])=>k+': '+fmt(v)).join('; ')
            }>{fmt(line.feesDue)}</td><td>{fmt(line.totalCashDue)}</td>
            <td>{fmt(line.closingPrincipal)}</td>
          </tr>)}</tbody>
        </table>
      </div>
      <details className="a05-lineage">
        <summary>Как рассчитано · источник, начисления, комиссии и SHA</summary>
        <dl>
          <dt>Редакция C05</dt><dd>{result.exactSourceRevisionId}</dd>
          <dt>SHA источника</dt><dd>{result.sourceContentSha256}</dd>
          <dt>SHA типизированных условий</dt><dd>{result.termsSha256}</dd>
          <dt>Контракт CALC-A05</dt><dd>{result.appContractRevisionId}</dd>
          <dt>Дескриптор алгоритма SHA</dt><dd>{result.engineDescriptorSha256}</dd>
          <dt>Сценарий SHA</dt><dd>{result.variantSha256}</dd>
          <dt>Результат SHA</dt><dd>{result.resultSha256}</dd>
          <dt>База / округление</dt><dd>{result.result.dayCountConvention} /
            {result.result.roundingMode}, scale {result.result.roundingScale}</dd>
          <dt>Выдано</dt><dd>{fmt(result.result.funding.drawdown)} KZT</dd>
          <dt>Финансируемые комиссии</dt>
          <dd>{fmt(result.result.funding.financedFees)} KZT</dd>
          <dt>Удержанные комиссии</dt>
          <dd>{fmt(result.result.funding.withheldFees)} KZT</dd>
          <dt>Уплаченные при выдаче</dt>
          <dd>{fmt(result.result.funding.upfrontPaidFees)} KZT</dd>
          <dt>Чистые средства на руки</dt>
          <dd>{fmt(result.result.funding.netCashProceeds)} KZT</dd>
          {result.result.diagnostics.map((d,i)=>
            <div key={i}><dt>Контроль</dt><dd>{d}</dd></div>)}
        </dl>
      </details>
      <small>Результат UAT_UNQUALIFIED. Плановые платежи не отражены как фактические
        денежные операции в F03. Финансовое S2 и production admission закрыты.</small>
    </div>}
  </section>;
}
