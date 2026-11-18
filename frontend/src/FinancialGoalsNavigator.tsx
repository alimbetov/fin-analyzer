import {useEffect,useMemo,useState} from 'react';
import {
  Activity,ArrowRight,BookOpen,ChevronDown,ChevronRight,
  CircleAlert,FileSpreadsheet,GitBranch,Layers3,Search,ShieldAlert
} from 'lucide-react';
import type {FormItem,IndicatorDefinition,IndicatorGoal,Locale,MetricReferenceView} from './api';
import type {DecisionContext} from './decisionTree';
import {buildDecisionTree,formContext,goalMetricCodes} from './decisionTree';
import {get} from './api';
import FinancialAtlas from './FinancialAtlas';

type Props={
  locale:Locale;
  forms:FormItem[];
  onOpenForm:(code:string,context:DecisionContext)=>void;
  onOpenLibrary:()=>void;
};
const roleLabels:Record<string,string>={
  CORE:'Основной для задачи',
  SUPPORTING:'Вспомогательный для задачи',
  EVIDENCE:'Подтверждающий документ'
};
const statusLabel:Record<string,string>={
  DESIGN_ONLY:'Модель пока проектируется',
  CALC_PILOT:'Расчётный пилот — не утверждён',
  CANDIDATE_NOT_S2:'Связи ещё не прошли S2',
  CATALOG_UNQUALIFIED:'Показатель ещё не квалифицирован'
};

/** Question-first, accessible, source-grounded decision tree.
 *  It is not a financial calculator or a project-data-readiness monitor.
 */
export default function FinancialGoalsNavigator({
  locale,forms,onOpenForm,onOpenLibrary
}:Props){
  const [goals,setGoals]=useState<IndicatorGoal[]>([]);
  const [indicators,setIndicators]=useState<IndicatorDefinition[]>([]);
  const [metrics,setMetrics]=useState<MetricReferenceView[]>([]);
  const [selectedGoal,setSelectedGoal]=useState('');
  const [selectedMetric,setSelectedMetric]=useState('');
  const [collapsedModels,setCollapsedModels]=useState<string[]>([]);
  const [recommendationsOpen,setRecommendationsOpen]=useState(true);
  const [query,setQuery]=useState('');
  const [visualMode,setVisualMode]=useState<'flow'|'atlas'>('flow');
  const [busy,setBusy]=useState(true);
  const [failure,setFailure]=useState('');
  useEffect(()=>{
    let active=true;
    setBusy(true);setFailure('');
    Promise.all([
      get<IndicatorGoal[]>('/indicator-catalog/goals?locale='+encodeURIComponent(locale)),
      get<IndicatorDefinition[]>('/indicator-catalog/indicators?locale='+encodeURIComponent(locale)),
      get<MetricReferenceView[]>('/catalog/metrics?kind=ANALYTICAL_OUTPUT&locale='+
        encodeURIComponent(locale)).catch(()=>[] as MetricReferenceView[])
    ]).then(([newGoals,newIndicators,newMetrics])=>{
      if(!active)return;
      setGoals(newGoals);
      setIndicators(newIndicators);
      setMetrics(newMetrics);
      setSelectedGoal(old=>newGoals.some(g=>g.code===old)?old:newGoals[0]?.code??'');
      setBusy(false);
    }).catch(error=>{
      if(!active)return;
      setFailure(error instanceof Error?error.message:String(error));
      setBusy(false);
    });
    return()=>{active=false};
  },[locale]);

  const goal=goals.find(g=>g.code===selectedGoal)??goals[0];
  const metricCodes=goal?goalMetricCodes(goal):[];
  const activeMetric=metricCodes.includes(selectedMetric)?selectedMetric:metricCodes[0]??'';
  const metricByCode=useMemo(()=>new Map(indicators.map(m=>[m.code,m])),[indicators]);
  const referenceByCode=useMemo(()=>new Map(metrics.map(m=>[m.metricCode,m])),[metrics]);
  const formByCode=useMemo(()=>new Map(forms.map(f=>[f.formCode,f])),[forms]);
  const tree=goal&&activeMetric?buildDecisionTree(goal,activeMetric):null;
  const selectedDefinition=metricByCode.get(activeMetric);
  const selectedReference=referenceByCode.get(activeMetric);
  const help=selectedDefinition?.explanation;
  const visibleGoals=goals.filter(g=>{
    const needle=query.trim().toLocaleLowerCase(locale);
    return (g.title??g.code).toLocaleLowerCase(locale).includes(needle)||
      g.code.toLocaleLowerCase().includes(needle)||
      goalMetricCodes(g).some(code=>code.toLocaleLowerCase().includes(needle));
  });
  const displayForm=(code:string)=>{
    const ref=formByCode.get(code);
    return ref?.title||code;
  };
  function selectGoal(code:string){
    setSelectedGoal(code);
    setSelectedMetric('');
    setCollapsedModels([]);
    setRecommendationsOpen(true);
  }
  function selectMetric(code:string){
    setSelectedMetric(code);
    setCollapsedModels([]);
    setRecommendationsOpen(true);
  }
  function openForm(code:string,modelCode:string|null){
    if(!goal||!activeMetric)return;
    const context=formContext(goal,activeMetric,code,modelCode);
    if(context)onOpenForm(code,context);
  }

  return <section className="kpi-navigator" aria-label="FIN Flow — навигатор финансовых решений">
    <div className="kpi-header">
      <div>
        <p className="eyebrow">FIN FLOW · ФИНАНСОВЫЕ РЕШЕНИЯ</p>
        <h2>От вопроса — к нужным данным.</h2>
        <p>Выберите финансовую задачу. Раскройте показатель, его модели
          и формы-источники — без необходимости знать номера отчётов.</p>
      </div>
      <div className="kpi-header-buttons">
        <div className="kpi-mode-switch" role="group" aria-label="Режим карты финансовых связей">
          <button type="button" aria-pressed={visualMode==='flow'}
            className={visualMode==='flow'?'active':''}
            onClick={()=>setVisualMode('flow')}><GitBranch size={15}/> Flow</button>
          <button type="button" aria-pressed={visualMode==='atlas'}
            className={visualMode==='atlas'?'active':''}
            onClick={()=>setVisualMode('atlas')}><Layers3 size={15}/> Atlas 2.5D</button>
        </div>
        <button className="kpi-secondary-action" type="button" onClick={onOpenLibrary}>
          <BookOpen size={17}/> Профессиональная библиотека
        </button>
      </div>
    </div>
    {busy&&<div role="status" className="kpi-empty">Загружаем дерево финансовых решений...</div>}
    {failure&&<div role="alert" className="error"><CircleAlert size={18}/>
      <span>Дерево недоступно: {failure}. Проверьте актуальную версию Backend.</span></div>}
    {!busy&&!failure&&<div className="kpi-workspace">
      <nav className="kpi-goal-column" aria-label="Выберите финансовый вопрос">
        <label className="kpi-search"><Search size={17}/>
          <input value={query} onChange={e=>setQuery(e.target.value)}
            aria-label="Поиск финансовой задачи или кода KPI"
            placeholder="Например, кредит, инвестиции, NPV"/>
        </label>
        <p className="kpi-subheading">7 задач · выберите вопрос</p>
        <div className="kpi-goal-list">
          {visibleGoals.map((item,i)=><button type="button" key={item.code}
            className={'kpi-goal-option'+(goal?.code===item.code?' active':'')}
            aria-current={goal?.code===item.code?'step':undefined}
            onClick={()=>selectGoal(item.code)}>
            <span className="kpi-goal-number">{String(i+1).padStart(2,'0')}</span>
            <span className="kpi-goal-title">{item.title??item.code}</span>
            <span className="kpi-muted">{goalMetricCodes(item).length} показателей · {item.forms.length} форм</span>
            {item.translationStatus==='PENDING_TRANSLATION'&&
              <span className="kpi-warning-text">Заголовок пока не переведён</span>}
          </button>)}
          {!visibleGoals.length&&<p className="kpi-empty">По запросу задачи не найдены.</p>}
        </div>
      </nav>
      {goal&&<div className="kpi-detail">
        <div className="kpi-detail-top">
          <div className="kpi-overline">ШАГ 01 / ВАШ ВОПРОС · {goal.code}</div>
          <h3>{goal.title??goal.code}</h3>
          <p>Результат этой задачи в каталоге: <b>{goal.deliverable}</b>.
            Выберите показатель ниже, чтобы раскрыть точные связи модели.</p>
          <div className="kpi-path-steps" aria-label="Путь финансового решения">
            <span>1. Вопрос</span><ArrowRight size={13}/>
            <span>2. Показатель</span><ArrowRight size={13}/>
            <span>3. Модель и источники</span><ArrowRight size={13}/>
            <span>4. Редактор</span>
          </div>
        </div>
        {visualMode==='flow'&&<>
        <div className="kpi-branch-stage">
          <div className="kpi-branch-heading">
            <span className="kpi-branch-count">02</span>
            <div><h4><Activity size={18}/> Какие показатели помогут?</h4>
              <p>Выбор показателя раскрывает только те модели,
                которые связаны с ним в исходном каталоге.</p>
            </div>
          </div>
          <div className="kpi-indicator-options" role="group" aria-label="Показатели выбранной задачи">
            {metricCodes.map(code=>{
              const ref=referenceByCode.get(code);
              return <button type="button" key={code}
                className={'kpi-indicator-option'+(activeMetric===code?' active':'')}
                aria-pressed={activeMetric===code} onClick={()=>selectMetric(code)}>
                <span className="kpi-indicator-code">{code}</span>
                <span className="kpi-indicator-title">{ref?.title||code}</span>
                <ChevronRight size={16}/>
              </button>;
            })}
          </div>
          {activeMetric&&<div className="kpi-explanation">
            <div className="kpi-explanation-heading">
              <span className="kpi-chip">ПОКАЗАТЕЛЬ · {activeMetric}</span>
              <span className="kpi-draft-status">
                {help?.reviewStatus??'PENDING_EXPLANATION'}
              </span>
            </div>
            <dl className="kpi-explanation-grid">
              <div><dt>Что показывает</dt><dd>{help?.meaning||
                'Пояснение для выбранного языка ещё не подготовлено.'}</dd></div>
              <div><dt>Зачем нужен</dt><dd>{help?.helpsDecision||
                'Описание управленческого применения ожидает перевода и проверки.'}</dd></div>
              <div><dt>Ограничения интерпретации</dt><dd>{help?.limitations||
                'Условия применимости пока не переведены.'}</dd></div>
            </dl>
            <p className="kpi-explanation-note"><ShieldAlert size={15}/>
              Это редакционный материал, а не утверждённая формула. Единицы:
              {' '}{selectedReference?.unitPolicy??'PENDING_METRIC_REVIEW'};
              статус метрики: {selectedDefinition?.qualificationStatus??'НЕИЗВЕСТЕН'}.
            </p>
          </div>}
        </div>
        {tree&&<div className="kpi-branch-stage kpi-tree">
          <div className="kpi-branch-heading">
            <span className="kpi-branch-count">03</span>
            <div><h4><GitBranch size={18}/> От каких данных зависит расчёт?</h4>
              <p>Каждая ветвь ведёт к конкретной модели и её входным формам.
                Все связи пока являются кандидатными.</p></div>
          </div>
          <div className="kpi-tree-stem">
            <div className="kpi-tree-root">
              <Layers3 size={16}/><span>{activeMetric}</span>
              <span className="kpi-tree-root-detail">{tree.branches.length} моделей-источников</span>
            </div>
            <div className="kpi-tree-branches">
              {tree.branches.map(branch=>{
                const model=branch.model;
                const expanded=!collapsedModels.includes(model.code);
                return <section className="kpi-model-branch" key={model.code}>
                  <button className="kpi-model-toggle" type="button"
                    aria-expanded={expanded}
                    aria-controls={'fin-model-'+model.code}
                    onClick={()=>setCollapsedModels(prev=>expanded?
                      [...prev,model.code]:prev.filter(x=>x!==model.code))}>
                    {expanded?<ChevronDown size={18}/>:<ChevronRight size={18}/>}
                    <span><strong>{model.code}</strong> · {model.title}
                      <small>Из analytics-model-catalog.tsv · {model.inputFormCodes.length} входных форм</small>
                    </span>
                    <span className="kpi-qual-status">{statusLabel[model.qualificationStatus]??model.qualificationStatus}</span>
                  </button>
                  {expanded&&<div id={'fin-model-'+model.code} className="kpi-model-children">
                    <p className="kpi-edge-explanation">
                      Тип связи: <b>MODEL_INPUT_CANDIDATE</b> — форма указана
                      входом модели {model.code}. Обязательность её полей
                      ещё определяется версионным контрактом.
                    </p>
                    {branch.inputs.map(input=>{
                      const form=formByCode.get(input.formCode);
                      return <button type="button" className="kpi-input-node" key={input.formCode}
                        onClick={()=>openForm(input.formCode,model.code)}
                        aria-label={'Открыть форму '+input.formCode+' из модели '+model.code}>
                        <FileSpreadsheet size={17}/><span><b>{displayForm(input.formCode)}</b>
                          <small>{input.formCode} · {input.goalRole?
                            roleLabels[input.goalRole]:'Вход модели, без роли в списке задачи'}</small>
                          <small>Контракт формы: {form?.qualificationState??'ДАННЫЕ НЕ ЗАГРУЖЕНЫ'}</small>
                        </span><ArrowRight size={17}/>
                      </button>;
                    })}
                  </div>}
                </section>;
              })}
            </div>
          </div>
          {tree.recommendations.length>0&&<section className="kpi-recommended-section">
            <button type="button" className="kpi-recommendations-toggle"
              aria-expanded={recommendationsOpen} aria-controls="fin-goal-advice"
              onClick={()=>setRecommendationsOpen(open=>!open)}>
              {recommendationsOpen?<ChevronDown size={17}/>:<ChevronRight size={17}/>}
              Дополнительные формы задачи · {tree.recommendations.length}
            </button>
            {recommendationsOpen&&<div id="fin-goal-advice">
              <p className="kpi-edge-explanation">
                Тип связи: <b>GOAL_RECOMMENDATION_CANDIDATE</b>.
                Эти формы связаны с общей задачей в use-case-form-map-r10.tsv,
                но не объявлены прямыми входами выбранной модели.
              </p>
              <div className="kpi-recommendations-grid">{tree.recommendations.map(link=>
                <button key={link.formCode} className="kpi-input-node" type="button"
                  onClick={()=>openForm(link.formCode,null)}>
                  <FileSpreadsheet size={17}/>
                  <span><b>{displayForm(link.formCode)}</b>
                    <small>{link.formCode} · {roleLabels[link.role]}</small></span>
                  <ArrowRight size={17}/>
                </button>)}</div>
            </div>}
          </section>}
        </div>}
        </>}
        {visualMode==='atlas'&&tree&&<FinancialAtlas
          goal={goal} metricCodes={metricCodes} metricCode={activeMetric}
          tree={tree} forms={forms}
          metricReference={selectedReference} explanation={help}
          onSelectMetric={selectMetric} onOpenForm={openForm}/>}
        <div className="kpi-next-step">
          <div><strong>ШАГ 04 / ОТКРЫТЬ НУЖНЫЙ РЕДАКТОР</strong>
            <p>Выберите форму в ветви. FIN сохранит выбранные задачу,
              показатель и модель, чтобы можно было вернуться к дереву.</p>
            <small>Расчёт и оценка заполненности проекта отключены до IAM,
              S2-контрактов и подтверждённой версии методики.</small>
          </div>
          <button type="button" onClick={onOpenLibrary}>Все формы <ArrowRight size={15}/></button>
        </div>
      </div>}
    </div>}
  </section>;
}
