import {useEffect,useRef,useState} from 'react';
import type {Draft} from './api';
import {get,isDefiniteRejection,post} from './api';

const targets=[
  {code:'F02.grossProfit',name:'Валовая прибыль'},
  {code:'F02.grossMargin',name:'Валовая маржа'},
  {code:'F02.operatingResult',name:'Операционный результат'}
] as const;
type Graph={
  state:string;graphSha256:string|null;
  methodRevisions:{
    id:string;formulaVersion:string;reviewState:string;
    definitionSha256:string;bindingSha256:string;currentSchema:boolean
  }[];
  orderedNodes:{outputMetricCode:string;operation:string}[];
  requiredPhysicalInputs:{formCode:string;fieldCode:string;metricCode:string}[];
  blockers:string[];
};
type Receipt={
  planId:string;resultId:string;targetMetricCode:string;executionFamily:string;
  sourceRevisionId:string;formulaRevisionId:string|null;
  calculationRevisionId:string|null;planSha256:string;resultSha256:string;
  graphSha256:string;sourceSha256:string;methodBindingSha256:string;
  valueState:'PRESENT'|'INVALID';valueUnit:string;valueText:string|null;
  errorCode:string|null;state:string;commandResult:string;
  lineage:Record<string,unknown>;financiallyApproved:boolean;
  productionAdmitted:boolean
};
type Props={
  draft:Draft|null;dirty:boolean;busy:boolean;
  saveUncertain:boolean;commitUncertain:boolean
};
function sha(value:string):boolean{return /^[a-f0-9]{64}$/.test(value);}

export default function MetricGraphExecutionPanel({
  draft,dirty,busy,saveUncertain,commitUncertain
}:Props){
  const [target,setTarget]=useState<string>('F02.grossProfit');
  const [methodId,setMethodId]=useState('');
  const [graph,setGraph]=useState<Graph|null>(null);
  const [loadingGraph,setLoadingGraph]=useState(false);
  const [working,setWorking]=useState(false);
  const [result,setResult]=useState<Receipt|null>(null);
  const [error,setError]=useState('');
  const [message,setMessage]=useState('');
  const [planInput,setPlanInput]=useState('');
  const keyRef=useRef<string|null>(null);

  useEffect(()=>{
    let active=true;
    setGraph(null);setMethodId('');setResult(null);setError('');setMessage('');
    keyRef.current=null;
    setLoadingGraph(true);
    get<Graph>('/metric-graph/metrics/'+encodeURIComponent(target))
      .then(data=>{if(active)setGraph(data)})
      .catch(e=>{if(active)setError(e instanceof Error?e.message:String(e))})
      .finally(()=>{if(active)setLoadingGraph(false)});
    return()=>{active=false};
  },[target]);
  useEffect(()=>{
    setResult(null);setMessage('');keyRef.current=null;
  },[draft?.instanceId,draft?.revisionId,draft?.sha256,dirty]);

  const options=graph?.methodRevisions.filter(
    m=>m.currentSchema&&m.reviewState==='UAT_TEST_ONLY')??[];
  const disabled=busy||working||loadingGraph||dirty||
    saveUncertain||commitUncertain||!draft?.revisionId||
    draft.formCode!=='F02';
  const ready=!disabled&&graph?.state==='DAG_COMPILED_PREVIEW_ONLY'&&
    Boolean(methodId)&&options.some(m=>m.id===methodId);

  function verify(receipt:Receipt):Receipt{
    if(!draft||receipt.sourceRevisionId!==draft.revisionId
      ||receipt.sourceSha256!==draft.sha256||receipt.targetMetricCode!==target
      ||receipt.executionFamily!=='F02_TYPED_DAG'
      ||receipt.formulaRevisionId!==methodId
      ||receipt.state!=='UAT_UNQUALIFIED'
      ||receipt.financiallyApproved||receipt.productionAdmitted
      ||!sha(receipt.planSha256)||!sha(receipt.resultSha256)
      ||!sha(receipt.graphSha256)||!sha(receipt.methodBindingSha256)
      ||(receipt.valueState==='PRESENT'&&receipt.valueText===null)
      ||(receipt.valueState==='INVALID'&&receipt.errorCode===null))
      throw new Error('MG-R0.4: источник, версия методики, SHA или статус не совпадают.');
    return receipt;
  }
  async function execute(){
    if(!ready||!draft)return;
    setWorking(true);setError('');setMessage('');
    if(!keyRef.current)keyRef.current=crypto.randomUUID();
    const command={
      organizationId:draft.organizationId,
      projectId:draft.projectId,
      targetMetricCode:target,
      exactSourceRevisionId:draft.revisionId,
      exactMethodRevisionId:methodId,
      exactCalculationRevisionId:null,
      expectedPeriodType:draft.periodType,
      expectedPeriodCode:draft.periodCode,
      expectedCurrency:draft.currency,
      expectedReportingBasis:draft.reportingBasis,
      expectedDataSpace:draft.dataSpace
    };
    try{
      const first=verify(await post<Receipt>('/uat/metric-graph/execute',
        command,keyRef.current));
      const query=new URLSearchParams({
        organizationId:draft.organizationId,projectId:draft.projectId
      });
      const exact=verify(await get<Receipt>(
        '/uat/metric-graph/plans/'+encodeURIComponent(first.planId)+'?'+query));
      if(exact.planSha256!==first.planSha256||
        exact.resultSha256!==first.resultSha256)
        throw new Error('MG-R0.4: результат не воспроизводится из PostgreSQL.');
      setResult(exact);setPlanInput(exact.planId);keyRef.current=null;
      setMessage('Сохранён immutable UAT-план с точным SHA.');
    }catch(e){
      if(isDefiniteRejection(e))keyRef.current=null;
      setError(e instanceof Error?e.message:String(e));
    }finally{setWorking(false)}
  }
  async function reopen(){
    if(!draft||disabled||!methodId)return;
    const plan=planInput.trim();
    if(!/^[a-f0-9-]{36}$/i.test(plan)){
      setError('Укажите точный UUID плана.');return;
    }
    setWorking(true);setError('');setMessage('');
    try{
      const query=new URLSearchParams({
        organizationId:draft.organizationId,projectId:draft.projectId
      });
      const exact=verify(await get<Receipt>(
        '/uat/metric-graph/plans/'+encodeURIComponent(plan)+'?'+query));
      setResult(exact);
      setMessage('Неизменяемый план повторно проверен по source/method/result SHA.');
    }catch(e){
      setResult(null);setError(e instanceof Error?e.message:String(e));
    }finally{setWorking(false)}
  }
  return <section className="mg-r04-panel" aria-label="Исполнение MG-R0.4">
    <header>
      <h3>Metric Graph · исполняемый UAT-план</h3>
      <p className="period-caption">Показатель → точная методика → исходная F02 →
        типизированный DAG → существующий BigDecimal kernel → immutable результат.</p>
    </header>
    <div className="a05-grid">
      <label className="a05-field">Целевой показатель
        <select aria-label="Показатель MG-R0.4" value={target}
          disabled={working||busy} onChange={e=>setTarget(e.target.value)}>
          {targets.map(t=><option key={t.code} value={t.code}>
            {t.code} · {t.name}
          </option>)}
        </select>
      </label>
      <label className="a05-field">Точная версия методики
        <select aria-label="Методика MG-R0.4" value={methodId}
          disabled={working||loadingGraph||options.length===0}
          onChange={e=>{setMethodId(e.target.value);setResult(null);keyRef.current=null;}}>
          <option value="">Выберите проверяемую версию</option>
          {options.map(m=><option key={m.id} value={m.id}>
            {m.formulaVersion} · {m.id.slice(0,8)}
          </option>)}
        </select>
      </label>
    </div>
    {graph&&<>
      <p className="period-caption">DAG: {graph.state}
        {' · '}узлов {graph.orderedNodes.length}
        {' · '}источников {graph.requiredPhysicalInputs.length}
        {graph.graphSha256?' · SHA '+graph.graphSha256.slice(0,12):''}
      </p>
      <div className="mg-r04-deps">
        {graph.requiredPhysicalInputs.map(input=>
          <span key={input.metricCode}>{input.formCode}.{input.fieldCode}</span>)}
      </div>
      {options.length===0&&<p role="status">
        Исполняемая UAT-методика не найдена. Автоматический выбор другой формулы запрещён.
      </p>}
    </>}
    {!draft?.revisionId&&<p role="status">Сначала сохраните точную редакцию F02.</p>}
    {dirty&&<p role="status">Сохраните изменения F02 перед расчётом.</p>}
    <div className="a05-schedule-actions">
      <button type="button" className="primary-save" disabled={!ready}
        onClick={()=>void execute()}>
        {working?'Проверка…':'Создать immutable план и рассчитать'}
      </button>
      <label>UUID сохранённого UAT-плана
        <input aria-label="UUID плана MG-R0.4" value={planInput}
          onChange={e=>setPlanInput(e.target.value)}
          placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
          disabled={working||busy}/>
      </label>
      <button type="button" onClick={()=>void reopen()}
        disabled={disabled||!methodId}>
        Повторно открыть план
      </button>
    </div>
    {error&&<p role="alert">{error}</p>}
    {message&&<p role="status">{message}</p>}
    {result&&<div className="mg-r04-output">
      <strong>{result.targetMetricCode} · {result.valueState==='PRESENT'
        ?result.valueText+' '+result.valueUnit
        :'Не определено: '+result.errorCode}</strong>
      <small>Источник и расчёт проверены повторно. S2: NO. Production: NO.</small>
      <details className="a05-lineage">
        <summary>Explain · методика, DAG, исходная ревизия, SHA и результат</summary>
        <dl>
          <dt>Ревизия F02</dt><dd>{result.sourceRevisionId}</dd>
          <dt>Ревизия методики</dt><dd>{result.formulaRevisionId}</dd>
          <dt>SHA источника</dt><dd>{result.sourceSha256}</dd>
          <dt>SHA контракта методики</dt><dd>{result.methodBindingSha256}</dd>
          <dt>SHA графа</dt><dd>{result.graphSha256}</dd>
          <dt>SHA immutable плана</dt><dd>{result.planSha256}</dd>
          <dt>SHA результата</dt><dd>{result.resultSha256}</dd>
          <dt>UUID плана</dt><dd>{result.planId}</dd>
          <dt>UUID результата</dt><dd>{result.resultId}</dd>
        </dl>
        <p className="period-caption">Расчётный контекст: {draft?.periodCode} ·
          {draft?.currency} · {draft?.reportingBasis} · {draft?.dataSpace}.</p>
      </details>
    </div>}
    <small>Только синтетический UAT. Значения не публикуются в fin_fact,
      не открывают production CalcPlan и не утверждают финансовую методику.</small>
  </section>;
}
