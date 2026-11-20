import {useEffect,useRef,useState} from 'react';
import type {Draft,Field,Row} from './api';
import {get,isDefiniteRejection,post} from './api';
import {verifySavedDraft} from './editorPolicy';

type Scope={
  organizationId:string;economicEntityId:string;businessScopeId:string;projectId:string
};
type Terms={
  loanBusinessKey:string;agreementRef:string;creditorRef:string;
  contractDate:string;disbursementDate:string;accrualStartDate:string;maturityDate:string;
  principal:string;jurisdiction:string;perspective:string;
  repaymentMethod:string;paymentFrequency:string;dueDay:number;
  interestCompounding:string;dayCountConvention:string;businessDayAdjustment:string;
  roundingScale:number;roundingMode:string;finalResidualRule:string;
  rateKind:string;nominalAnnualRatePct:string
};
type Fee={
  feeCode:string;feeRuleType:string;feeSettlement:string;
  calculationBasis:string;amountOrRate:string;frequency:string;
  eventDateRule:string;ifrsEirClassification:string;kzGesvClassification:string
};
type LoanReceipt={
  instanceId:string;sourceRevisionId:string;revisionNo:number;
  sourceContentSha256:string;contractRevisionId:string;termsSha256:string;
  result:string;financiallyApproved:boolean;productionAdmitted:boolean
};
type LoanRead={
  receipt:LoanReceipt;terms:Terms;fees:Fee[];
  sourceIntegrityState:string;loanIdentityId:string
};
type Props={
  draft:Draft|null;rowEntries:Row[];fields:Field[];scope:Scope;
  periodType:string;periodCode:string;currency:string;
  dirty:boolean;busy:boolean;saveUncertain:boolean;commitUncertain:boolean;
  onDirty:()=>void;onSaved:(snapshot:Draft,receipt:LoanReceipt)=>void
};
const initTerms:Terms={
  loanBusinessKey:'',agreementRef:'',creditorRef:'',contractDate:'',
  disbursementDate:'',accrualStartDate:'',maturityDate:'',principal:'',
  jurisdiction:'KZ',perspective:'BORROWER',repaymentMethod:'',
  paymentFrequency:'MONTHLY',dueDay:15,interestCompounding:'SIMPLE',
  dayCountConvention:'',businessDayAdjustment:'NONE',
  roundingScale:2,roundingMode:'',finalResidualRule:'ADJUST_LAST_PAYMENT',
  rateKind:'FIXED',nominalAnnualRatePct:''
};
const newFee=():Fee=>({
  feeCode:'',feeRuleType:'FIXED',feeSettlement:'UPFRONT_PAID',
  calculationBasis:'FIXED_AMOUNT',amountOrRate:'',frequency:'ONCE',
  eventDateRule:'DISBURSEMENT_DATE',ifrsEirClassification:'UNREVIEWED',
  kzGesvClassification:'UNREVIEWED'
});
const choices=(values:readonly string[])=>
  values.map(value=><option key={value} value={value}>{value}</option>);

export default function LoanContractStudio({
  draft,rowEntries,fields,scope,periodType,periodCode,currency,
  dirty,busy,saveUncertain,commitUncertain,onDirty,onSaved
}:Props){
  const [terms,setTerms]=useState<Terms>({...initTerms});
  const [fees,setFees]=useState<Fee[]>([]);
  const [working,setWorking]=useState(false);
  const [loading,setLoading]=useState(false);
  const [error,setError]=useState('');
  const [message,setMessage]=useState('');
  const [loaded,setLoaded]=useState<LoanRead|null>(null);
  const keyRef=useRef<string|null>(null);

  useEffect(()=>{
    let active=true;
    setLoaded(null);setError('');setMessage('');setTerms({...initTerms});setFees([]);
    keyRef.current=null;
    if(!draft?.revisionId)return;
    setLoading(true);
    const query=new URLSearchParams({
      organizationId:draft.organizationId,projectId:draft.projectId
    });
    get<LoanRead>('/uat/a05/c05/revisions/'+encodeURIComponent(draft.revisionId)+'?'+query)
      .then(result=>{
        if(!active)return;
        if(result.receipt.sourceRevisionId!==draft.revisionId||
          result.receipt.sourceContentSha256!==draft.sha256||
          result.sourceIntegrityState!=='SOURCE_SHA_REVERIFIED')
          throw new Error('Сохранённый A05 не соответствует исходной ревизии C05.');
        // Java BigDecimal is serialized as JSON number; controlled inputs
        // must normalize the wire representation to string on every reopen.
        setLoaded(result);
        setTerms({...result.terms,
          principal:String(result.terms.principal),
          nominalAnnualRatePct:String(result.terms.nominalAnnualRatePct)});
        setFees(result.fees.map(f=>({...f,amountOrRate:String(f.amountOrRate)})));
      })
      .catch(err=>{
        if(!active)return;
        if(String(err).includes('A05_TYPED_CONTRACT_NOT_FOUND')||
           String(err).includes('HTTP 404')) {
          setMessage('Существующая C05 без A05: в R1.0 импорт договора ещё не поддерживается.');
        } else setError(String(err));
      })
      .finally(()=>{if(active)setLoading(false)});
    return()=>{active=false};
  },[draft?.instanceId,draft?.revisionId,draft?.sha256,
    draft?.organizationId,draft?.projectId]);

  function change<K extends keyof Terms>(key:K,value:Terms[K]){
    setTerms(t=>({...t,[key]:value}));onDirty();setMessage('');
  }
  function changeFee(index:number,part:Partial<Fee>){
    setFees(old=>old.map((item,i)=>i===index?{...item,...part}:item));
    onDirty();setMessage('');
  }
  const disabled=busy||working||loading||saveUncertain||commitUncertain||
    currency!=='KZT'||rowEntries.length!==1||
    (Boolean(draft)&&!loaded);
  const row=rowEntries[0];
  const sourceMatch=Boolean(row&&terms.agreementRef===row.values.agreementRef
    &&terms.creditorRef===row.values.creditor
    &&row.values.currency===currency&&row.values.rateType==='FIXED'
    &&terms.nominalAnnualRatePct.trim()===row.values.interestRate?.trim());
  const ready=!disabled&&sourceMatch&&dirty&&
    terms.loanBusinessKey.trim()!==''&&terms.principal.trim()!==''&&
    terms.contractDate!==''&&terms.disbursementDate!==''&&
    terms.accrualStartDate!==''&&terms.maturityDate!==''&&
    terms.repaymentMethod!==''&&terms.dayCountConvention!==''&&
    terms.roundingMode!==''&&fees.every(f=>f.feeCode&&f.amountOrRate);

  async function save(){
    if(!ready||!row||!sourceMatch)return;
    setWorking(true);setError('');setMessage('');
    if(!keyRef.current)keyRef.current=crypto.randomUUID();
    const key=keyRef.current;
    try{
      const source={
        instanceId:draft?.instanceId??null,
        organizationId:scope.organizationId,
        economicEntityId:scope.economicEntityId,
        businessScopeId:scope.businessScopeId,projectId:scope.projectId,
        formCode:'C05',periodType,periodCode,currency,
        coverage:'PERIOD_ONLY',reportingBasis:'MANAGEMENT',dataSpace:'SYNTHETIC',
        expectedRevision:draft?.revisionNo??0,values:{},rows:rowEntries,
        createdBy:'a05-react-uat',changeReason:'A05-R1.0 typed loan and fee revision'
      };
      const receipt=await post<LoanReceipt>('/uat/a05/c05/revisions',{
        draft:source,terms:{...terms,principal:terms.principal,
          nominalAnnualRatePct:terms.nominalAnnualRatePct},fees
      },key);
      if(receipt.financiallyApproved||receipt.productionAdmitted||
        receipt.revisionNo!==source.expectedRevision+1)
        throw new Error('A05: несоответствие версии или UAT-статуса записи.');
      const exact=await get<Draft>('/drafts/'+encodeURIComponent(receipt.instanceId)+
        '?organizationId='+encodeURIComponent(scope.organizationId));
      if(!verifySavedDraft({
        instanceId:receipt.instanceId,revisionNo:receipt.revisionNo,
        contentSha256:receipt.sourceContentSha256,result:receipt.result
      },exact,scope.organizationId,'C05',source.expectedRevision,
      {},rowEntries,fields)||exact.revisionId!==receipt.sourceRevisionId)
        throw new Error('C05: проверка финального сохранения или SHA не прошла.');
      const query=new URLSearchParams({
        organizationId:scope.organizationId,projectId:scope.projectId
      });
      const typed=await get<LoanRead>('/uat/a05/c05/revisions/'+
        encodeURIComponent(receipt.sourceRevisionId)+'?'+query);
      if(typed.receipt.termsSha256!==receipt.termsSha256||
        typed.receipt.sourceContentSha256!==receipt.sourceContentSha256||
        typed.sourceIntegrityState!=='SOURCE_SHA_REVERIFIED'||
        typed.fees.length!==fees.length)
        throw new Error('A05: типизированный контракт после сохранения не совпадает.');
      setLoaded(typed);
      keyRef.current=null;
      onSaved(exact,receipt);
      setMessage('Сохранено: C05 редакция '+receipt.revisionNo+
        ' · A05 SHA '+receipt.termsSha256.slice(0,12)+
        ' · без финансовой квалификации');
    }catch(e){
      if(isDefiniteRejection(e))keyRef.current=null;
      setError(e instanceof Error?e.message:String(e));
    }finally{setWorking(false)}
  }
  const input=(id:keyof Terms,title:string,kind:'text'|'date'='text')=>
    <label className="a05-field" key={id}>{title}
      <input aria-label={title} type={kind} value={String(terms[id])}
        onChange={e=>change(id,e.target.value as never)} disabled={busy||working||loading}/>
    </label>;
  const select=(id:keyof Terms,title:string,opts:readonly string[])=>
    <label className="a05-field" key={id}>{title}
      <select aria-label={title} value={String(terms[id])}
        onChange={e=>change(id,e.target.value as never)}
        disabled={busy||working||loading}>
        <option value="">Выберите</option>{choices(opts)}
      </select>
    </label>;
  return <section className="a05-studio" aria-label="Кредитный договор A05">
    <header className="a05-title">
      <div><h3>CALC-A05 · Кредитный договор и комиссии</h3>
        <small>Одна ревизия C05 + типизированные условия · локальный UAT, без расчёта графика/EIR</small>
      </div>
    </header>
    {loading&&<p>Открывается точная версия кредитного договора…</p>}
    {draft&&!loaded&&!loading&&<p role="status">
      Для существующей C05 без A05 в R1.0 автоматическая конвертация недоступна.
    </p>}
    <fieldset className="a05-fields" disabled={busy||working||loading}>
      <legend>Договор</legend>
      <div className="a05-grid">
        {input('loanBusinessKey','Ключ договора')}
        {input('agreementRef','Номер договора')}
        {input('creditorRef','Кредитор')}
        {input('principal','Сумма выдачи, KZT')}
        {input('contractDate','Дата договора','date')}
        {input('disbursementDate','Дата выдачи','date')}
        {input('accrualStartDate','Начало начисления','date')}
        {input('maturityDate','Дата окончания','date')}
        {select('repaymentMethod','Способ погашения',['ANNUITY','EQUAL_PRINCIPAL','CUSTOM'])}
        {select('dayCountConvention','База начисления',['ACT_365_FIXED','ACT_360','THIRTY_E_360'])}
        {select('roundingMode','Округление',['HALF_EVEN','HALF_UP'])}
        <label className="a05-field">День платежа
          <input type="number" min={1} max={28} aria-label="День платежа"
            value={terms.dueDay} onChange={e=>change('dueDay',Number(e.target.value))}
            disabled={busy||working||loading}/>
        </label>
        {input('nominalAnnualRatePct','Номинальная ставка, % годовых')}
      </div>
      <p className="period-caption">Профиль R1.0: KZT, заёмщик, один транш,
        FIXED, MONTHLY, SIMPLE, без сдвига дат. Остальные методы и календарные политики — R1.2.
        Ставка в C05 должна быть в процентных пунктах (например, 18 означает 18% годовых).</p>
    </fieldset>
    <fieldset className="a05-fields" disabled={busy||working||loading}>
      <legend>Комиссии · {fees.length}</legend>
      {fees.map((fee,i)=><div className="a05-fee" key={i}>
        <label>Код комиссии<input aria-label={'Комиссия '+(i+1)+' · код'} value={fee.feeCode}
          onChange={e=>changeFee(i,{feeCode:e.target.value})}/></label>
        <label>Тип<select aria-label={'Комиссия '+(i+1)+' · тип'} value={fee.feeRuleType}
          onChange={e=>{
            const type=e.target.value;
            const basis=type.startsWith('PERCENT_OF_')
              ?({
                PERCENT_OF_DISBURSEMENT:'DISBURSEMENT',
                PERCENT_OF_ORIGINAL:'ORIGINAL_PRINCIPAL',
                PERCENT_OF_OUTSTANDING:'OUTSTANDING',
                PERCENT_OF_LIMIT:'LIMIT'
              } as Record<string,string>)[type]:'FIXED_AMOUNT';
            changeFee(i,{feeRuleType:type,calculationBasis:basis,
              feeSettlement:type==='RECURRING_FIXED'?'RECURRING':'UPFRONT_PAID',
              frequency:type==='RECURRING_FIXED'?'MONTHLY':'ONCE',
              eventDateRule:type==='RECURRING_FIXED'?'SCHEDULE_DUE_DATE':'DISBURSEMENT_DATE'});
          }}>{choices(['FIXED','RECURRING_FIXED','PERCENT_OF_DISBURSEMENT',
            'PERCENT_OF_ORIGINAL','PERCENT_OF_OUTSTANDING','PERCENT_OF_LIMIT'])}</select></label>
        <label>Расчётная сумма / %<input aria-label={'Комиссия '+(i+1)+' · величина'}
          value={fee.amountOrRate} onChange={e=>changeFee(i,{amountOrRate:e.target.value})}/></label>
        <label>Погашение комиссии<select aria-label={'Комиссия '+(i+1)+' · списание'}
          value={fee.feeSettlement} onChange={e=>{
            const settlement=e.target.value,recurring=settlement==='RECURRING';
            changeFee(i,{feeSettlement:settlement,frequency:recurring?'MONTHLY':'ONCE',
              eventDateRule:recurring?'SCHEDULE_DUE_DATE':settlement==='AT_MATURITY'?
                'MATURITY_DATE':'DISBURSEMENT_DATE'});
          }}>{choices(['UPFRONT_PAID','WITHHELD','FINANCED','RECURRING','AT_MATURITY'])}</select></label>
        <button type="button" onClick={()=>{setFees(old=>old.filter((_,j)=>j!==i));onDirty()}}>
          Удалить комиссию
        </button>
      </div>)}
      <button type="button" onClick={()=>{setFees(old=>[...old,newFee()]);onDirty()}}>
        Добавить комиссию
      </button>
      <p className="period-caption">IFRS 9 EIR и ГЭСВ РК: UNREVIEWED. Результат договорного
        графика пока не вычисляется. Комиссии сохраняются как исходные правила.</p>
    </fieldset>
    {!sourceMatch&&rowEntries.length===1&&
      <p role="status">Проверьте исходную строку C05: agreementRef, creditor, currency,
        rateType и interestRate должны соответствовать договору. Сначала заполните C05.</p>}
    {error&&<p role="alert">{error}</p>}
    {message&&<p role="status">{message}</p>}
    {loaded&&<p className="period-caption">
      Источник: {loaded.receipt.sourceRevisionId} · SHA {loaded.receipt.termsSha256.slice(0,16)}…
    </p>}
    <button type="button" className="primary-save" disabled={!ready} onClick={()=>void save()}>
      {working?'Сохранение…':'Сохранить договор и C05 атомарно'}
    </button>
  </section>;
}
