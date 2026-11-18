import {ArrowRight,Boxes,FileSpreadsheet,GitBranch,Layers3,ShieldAlert} from 'lucide-react';
import type {
  FormItem,IndicatorExplanation,IndicatorGoal,MetricReferenceView
} from './api';
import type {DecisionTree} from './decisionTree';

type Props={
  goal:IndicatorGoal;
  metricCodes:string[];
  metricCode:string;
  tree:DecisionTree;
  forms:FormItem[];
  metricReference?:MetricReferenceView;
  explanation?:IndicatorExplanation;
  onSelectMetric:(code:string)=>void;
  onOpenForm:(code:string,modelCode:string|null)=>void;
};

/**
 * Atlas is only a spatial presentation of the same source-grounded
 * DecisionTree used in FIN Flow. No duplicate graph or inferred edges.
 * Uses accessible native buttons (no mandatory canvas/WebGL).
 */
export default function FinancialAtlas({
  goal,metricCodes,metricCode,tree,forms,metricReference,explanation,
  onSelectMetric,onOpenForm
}:Props){
  const names=new Map(forms.map(f=>[f.formCode,f]));
  const displayName=(code:string)=>names.get(code)?.title||code;
  return <section className="fin-atlas" aria-label="FIN Atlas — пространственная карта зависимостей">
    <header className="atlas-header">
      <div>
        <span className="atlas-eyebrow"><Layers3 size={15}/> FIN ATLAS / 2.5D DEPENDENCY MAP</span>
        <h4>Карта финансовых связей</h4>
        <p>Один граф для Flow и Atlas. Нажмите показатель или форму,
          чтобы исследовать источники и открыть существующий редактор.</p>
      </div>
      <span className="atlas-readonly">ГРАФ-КАНДИДАТ · БЕЗ РАСЧЁТА</span>
    </header>
    <div className="atlas-scene" role="region" aria-label="Показатели, модели и формы" tabIndex={0}>
      <div className="atlas-columns">
        <div className="atlas-lane atlas-goal-lane">
          <div className="atlas-lane-label">01 / ВОПРОС</div>
          <div className="atlas-node atlas-goal-node">
            <span className="atlas-node-meta">БИЗНЕС-ЗАДАЧА</span>
            <strong>{goal.title??goal.code}</strong>
            <small>{goal.code}</small>
          </div>
          <p className="atlas-relationship">Задача связана с моделями
            из текущего каталога</p>
        </div>
        <div className="atlas-lane atlas-indicators-lane">
          <div className="atlas-lane-label">02 / ПОКАЗАТЕЛЬ</div>
          {metricCodes.map(code=><button type="button" key={code}
            className={'atlas-node atlas-metric-node'+(code===metricCode?' active':'')}
            aria-pressed={code===metricCode}
            onClick={()=>onSelectMetric(code)}>
            <span className="atlas-node-meta">OUTPUT METRIC</span>
            <strong>{code}</strong>
            {code===metricCode&&<small>Выбран · показать зависимости</small>}
            <ArrowRight size={15}/>
          </button>)}
          <p className="atlas-relationship">Выбор KPI меняет
            набор моделей и форм в этой карте</p>
        </div>
        <div className="atlas-lane atlas-models-lane">
          <div className="atlas-lane-label">03 / МОДЕЛЬ</div>
          {tree.branches.map(branch=><div className="atlas-node atlas-model-node"
            key={branch.model.code} data-model-code={branch.model.code}>
            <span className="atlas-node-meta"><GitBranch size={13}/> МЕТОДИКА-КАНДИДАТ</span>
            <strong>{branch.model.code}</strong>
            <small>{branch.model.title}</small>
            <span className="atlas-method-state">{branch.model.qualificationStatus}</span>
          </div>)}
          {!tree.branches.length&&<p className="atlas-empty">Для KPI нет модели в данном сценарии.</p>}
        </div>
        <div className="atlas-lane atlas-sources-lane">
          <div className="atlas-lane-label">04 / ВХОДНЫЕ ФОРМЫ</div>
          {tree.branches.map(branch=><section className="atlas-source-group" key={branch.model.code}>
            <span className="atlas-source-title">ИСТОЧНИКИ {branch.model.code}</span>
            {branch.inputs.map(item=><button className="atlas-node atlas-form-node"
              type="button" key={item.formCode}
              onClick={()=>onOpenForm(item.formCode,branch.model.code)}
              aria-label={'Atlas: открыть форму '+item.formCode+' из модели '+branch.model.code}>
              <FileSpreadsheet size={16}/>
              <span><strong>{displayName(item.formCode)}</strong>
                <small>{item.formCode} · {item.goalRole??'MODEL_INPUT'}</small></span>
              <ArrowRight size={15}/>
            </button>)}
          </section>)}
        </div>
      </div>
    </div>
    <div className="atlas-provenance">
      <div><strong><Boxes size={16}/> Почему эти связи?</strong>
        <p>Модели и входные формы взяты из <code>analytics-model-catalog.tsv</code>.
          Отношение <code>MODEL_INPUT_CANDIDATE</code> — предварительная
          зависимость, а не утверждённая обязательность поля.</p>
      </div>
      <div><strong><ShieldAlert size={16}/> Что означает выбранный KPI?</strong>
        <p>{explanation?.meaning??'Пояснение для выбранного языка ещё не подготовлено.'}</p>
        <p>Источник описания: {explanation?.reviewStatus??'PENDING_EXPLANATION'}.
          Единицы: {metricReference?.unitPolicy??'PENDING_METRIC_REVIEW'}.</p>
      </div>
    </div>
    {tree.recommendations.length>0&&<aside className="atlas-recommendations">
      <h5>Дополнительные формы общей задачи · {tree.recommendations.length}</h5>
      <p>Связи из <code>use-case-form-map-r10.tsv</code>.
        Они <b>не являются прямыми входами</b> выбранной модели.</p>
      <div className="atlas-recommendation-list">{tree.recommendations.map(f=>
        <button type="button" key={f.formCode}
          onClick={()=>onOpenForm(f.formCode,null)}
          aria-label={'Atlas: открыть дополнительную форму '+f.formCode}>
          <FileSpreadsheet size={16}/>
          <span>{displayName(f.formCode)} <small>{f.formCode} · {f.role}</small></span>
          <ArrowRight size={14}/>
        </button>)}</div>
    </aside>}
    <footer className="atlas-footer">Только справочные связи ·
      ни одной фиктивной цифры или обещания доступного расчёта.
      <span>Модель данных общая с FIN Flow.</span>
    </footer>
  </section>;
}
