import {useEffect,useMemo,useState} from 'react';
import {Activity,ArrowRight,BookOpen,CheckCircle2,CircleAlert,FileSpreadsheet,Search} from 'lucide-react';
import type {FormItem,IndicatorDefinition,IndicatorGoal,Locale} from './api';
import {get} from './api';

const roleLabel:Record<string,string>={
  CORE:'Основные формы',
  SUPPORTING:'Дополнительные источники',
  EVIDENCE:'Подтверждающие документы'
};
const roleDescription:Record<string,string>={
  CORE:'Базовые данные для подготовки этого вида анализа',
  SUPPORTING:'Уточняют расчёт или финансовый контекст',
  EVIDENCE:'Документы-подтверждения; загрузка пока не реализована'
};

type Props={
  locale:Locale;
  forms:FormItem[];
  onOpenForm:(formCode:string)=>void;
  onOpenLibrary:()=>void;
};

/**
 * Financial question -> model outputs -> candidate form requirements.
 * The catalogue never claims calculations or project readiness.
 */
export default function FinancialGoalsNavigator({
  locale,forms,onOpenForm,onOpenLibrary
}:Props){
  const [goals,setGoals]=useState<IndicatorGoal[]>([]);
  const [indicators,setIndicators]=useState<IndicatorDefinition[]>([]);
  const [selectedCode,setSelectedCode]=useState('');
  const [query,setQuery]=useState('');
  const [busy,setBusy]=useState(true);
  const [failure,setFailure]=useState('');
  useEffect(()=>{
    let active=true;
    setBusy(true);setFailure('');
    Promise.all([
      get<IndicatorGoal[]>('/indicator-catalog/goals?locale='+encodeURIComponent(locale)),
      get<IndicatorDefinition[]>('/indicator-catalog/indicators')
    ]).then(([g,i])=>{
      if(!active)return;
      setGoals(g);
      setIndicators(i);
      setSelectedCode(old=>g.some(goal=>goal.code===old)?old:g[0]?.code??'');
      setBusy(false);
    }).catch(error=>{
      if(!active)return;
      setFailure(error instanceof Error?error.message:String(error));
      setBusy(false);
    });
    return()=>{active=false};
  },[locale]);

  const goal=goals.find(g=>g.code===selectedCode)??goals[0];
  const knownNames=useMemo(()=>new Map(forms.map(f=>[f.formCode,f])),[forms]);
  const indexed=useMemo(()=>new Map(indicators.map(x=>[x.code,x])),[indicators]);
  const visibleGoals=goals.filter(g=>
    (g.title??g.code).toLocaleLowerCase().includes(query.toLocaleLowerCase())||
    g.code.toLocaleLowerCase().includes(query.toLocaleLowerCase()));
  const metricCodes=goal?[...new Set(goal.models.flatMap(m=>m.outputMetricCodes))]:[];
  const notQualified=goal?.models.filter(m=>
    m.qualificationStatus!=='APPROVED'&&m.qualificationStatus!=='S2_APPROVED')??[];

  return <section className="kpi-navigator" aria-label="Навигатор финансовых решений">
    <div className="kpi-header">
      <div>
        <p className="eyebrow">FIN · РЕШЕНИЯ И ПОКАЗАТЕЛИ</p>
        <h2>Что вы хотите узнать о своём бизнесе?</h2>
        <p>Выберите цель — FIN покажет связанные показатели, исходные формы
          и ограничения расчёта. Не нужно знать номера отчётов заранее.</p>
      </div>
      <button className="kpi-secondary-action" type="button" onClick={onOpenLibrary}>
        <BookOpen size={17}/> Все 166 форм
      </button>
    </div>
    {busy&&<div className="kpi-empty" role="status">Загружаем каталог задач и показателей...</div>}
    {failure&&<div className="error" role="alert"><CircleAlert size={18}/>
      <span>Каталог показателей недоступен: {failure}. Проверьте запуск обновлённого Backend.</span></div>}
    {!busy&&!failure&&<div className="kpi-workspace">
      <div className="kpi-goal-column">
        <label className="kpi-search"><Search size={17}/>
          <input value={query} onChange={e=>setQuery(e.target.value)}
            aria-label="Поиск финансовой задачи" placeholder="Например, кредит или инвестор"/>
        </label>
        <p className="kpi-subheading">Финансовые задачи · {goals.length}</p>
        <div className="kpi-goal-list">
          {visibleGoals.map(g=><button key={g.code} type="button"
            aria-pressed={g.code===goal?.code}
            className={'kpi-goal-option'+(g.code===goal?.code?' active':'')}
            onClick={()=>setSelectedCode(g.code)}>
            <span className="kpi-goal-title">{g.title??g.code}</span>
            <span className="kpi-muted">{g.models.length} моделей · {g.forms.length} форм</span>
            {g.translationStatus==='PENDING_TRANSLATION'&&
              <span className="kpi-warning-text">Перевод ожидает проверки</span>}
          </button>)}
          {!visibleGoals.length&&<p className="kpi-empty">Задачи не найдены.</p>}
        </div>
      </div>
      {goal&&<div className="kpi-detail">
        <div className="kpi-detail-top">
          <span className="kpi-overline">Задача · {goal.code}</span>
          <h3>{goal.title??goal.code}</h3>
          <p>Цель анализа: {goal.deliverable}. Аудитория: {goal.audience}.</p>
          <p className="kpi-honesty"><CircleAlert size={16}/> Это кандидатная
            связь из каталога, а не подтверждённый финансовый расчёт.
            Данные конкретного проекта пока не проверены.</p>
        </div>
        <div className="kpi-count-strip">
          <div><strong>{metricCodes.length}</strong><span>результирующих показателей</span></div>
          <div><strong>{goal.models.length}</strong><span>аналитических моделей</span></div>
          <div><strong>{goal.forms.length}</strong><span>рекомендованных форм</span></div>
        </div>
        <div className="kpi-block">
          <h4><Activity size={18}/> Какие показатели помогут ответить?</h4>
          <div className="kpi-metric-list">
            {metricCodes.map(code=>{
              const indicator=indexed.get(code);
              const dependencies=indicator?.inputFormCodes??[];
              return <div className="kpi-metric" key={code}>
                <div className="kpi-metric-top"><b>{code}</b><span>КОНТРАКТ НЕ УТВЕРЖДЁН</span></div>
                <p>Показатель ожидаемого результата аналитической модели.
                  Исходных форм по каталогу: {dependencies.length}.</p>
                <small>Единицы, формула, интерпретация и условия применимости требуют проверки.</small>
              </div>;
            })}
          </div>
        </div>
        <div className="kpi-block">
          <h4><FileSpreadsheet size={18}/> Какие формы понадобятся?</h4>
          {(['CORE','SUPPORTING','EVIDENCE'] as const).map(role=>{
            const linked=goal.forms.filter(f=>f.role===role);
            return linked.length>0?<div className="kpi-role-section" key={role}>
              <h5>{roleLabel[role]} <span>{linked.length}</span></h5>
              <p>{roleDescription[role]}</p>
              <div className="kpi-form-grid">{linked.map(link=>{
                const form=knownNames.get(link.formCode);
                return <button key={link.formCode} className="kpi-form-link" type="button"
                  onClick={()=>onOpenForm(link.formCode)}
                  aria-label={'Открыть форму '+link.formCode}>
                  <span className="kpi-form-code">{link.formCode}</span>
                  <span className="kpi-form-name">{form?.title??link.formCode}</span>
                  <ArrowRight size={15}/>
                </button>;
              })}</div>
            </div>:null;
          })}
        </div>
        <div className="kpi-block kpi-models">
          <h4><CheckCircle2 size={18}/> Готовность методик</h4>
          <p>{notQualified.length} из {goal.models.length} моделей пока
            не имеют подтверждённого S2-допуска. Вычисление из этого
            экрана недоступно.</p>
          <div className="kpi-model-list">{goal.models.map(model=>
            <div className="kpi-model-item" key={model.code}>
              <span><b>{model.code}</b> · {model.title}</span>
              <span>{model.qualificationStatus}</span>
            </div>
          )}</div>
        </div>
      </div>}
    </div>}
  </section>;
}
