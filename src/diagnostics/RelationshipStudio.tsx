import { useMemo, useState } from "react";
import {
  BUSINESS_PROBLEMS, GRAPH_REGISTRY_VERSION, RELATIONSHIP_REGISTRY,
  graphPlan, type GraphStudy, type TimeRole
} from "./registry";

const ROLE_LABEL: Record<TimeRole,string> = {
  CURRENT_FLOW: "за месяц", CURRENT_STOCK: "на конец месяца",
  PRIOR_STOCK: "на конец предыдущего месяца"
};
const RULE_LABEL: Record<string,string> = {
  BALANCE_IDENTITY: "Проверка бухгалтерского баланса",
  CASH_ROLLFORWARD: "Сверка остатка денег и потоков",
  CASH_LEDGER_CASHFLOW_BRIDGE: "Сопоставление движения денег и расшифровок",
  OPERATING_RESULT_TO_CFO: "Прибыльность против денежного потока",
  RECEIVABLES_CASH_STRESS: "Дебиторка и давление на ликвидность",
  GROSS_MARGIN: "Валовая прибыль и маржа"
};
const TYPE_LABEL: Record<string,string> = {
  ACCOUNTING_IDENTITY: "Бухгалтерское тождество",
  CROSS_REPORT_RECONCILIATION: "Межотчётная сверка",
  ECONOMIC_BRIDGE: "Экономическая связь",
  QUALITATIVE_PATTERN: "Диагностический сигнал"
};
const formName: Record<string,string> = {
  F01: "Бухгалтерский баланс", F02: "Прибыли и убытки",
  F03: "Движение денежных средств", "DF-CSH-01": "Расшифровка поступлений",
  "DF-CSH-02": "Расшифровка платежей"
};
export function RelationshipStudio() {
  const [mode,setMode] = useState<"problem"|"form">("problem");
  const [problemCode,setProblemCode] = useState("PROFITABILITY");
  const [formCode,setFormCode] = useState("F02");
  const [month,setMonth] = useState("2026-10");
  const [depth,setDepth] = useState(1);
  const plan = useMemo(()=>graphPlan(RELATIONSHIP_REGISTRY,
    mode === "problem" ? { problemCode } : { formCode },depth),
    [mode,problemCode,formCode,depth]);
  const currentMonth = /^\d{4}-(0[1-9]|1[0-2])$/.test(month) ? month : "не указан";
  const lastMonth = /^\d{4}-(0[1-9]|1[0-2])$/.test(month) ?
    (() => { const [year,part] = month.split("-").map(Number);
      return part===1 ? `${year!-1}-12` : `${year}-${String(part!-1).padStart(2,"0")}`; })()
    : "не указан";
  const counts = useMemo(()=>{
    const forms=RELATIONSHIP_REGISTRY.forms;
    return {
      total:forms.length,
      pilot:forms.filter(f=>f.disposition==="PILOT_NUMERIC_SOURCE").length,
      evidence:forms.filter(f=>f.disposition==="EVIDENCE_ONLY").length,
      candidates:forms.filter(f=>f.disposition==="NUMERIC_CANDIDATE_UNQUALIFIED").length
    };
  },[]);
  return <section className="panel graph-panel" id="graph" aria-label="Межформенный диагностический граф">
    <div className="panel-heading">
      <div><span className="eyebrow">05 / CROSS-FORM INTELLIGENCE</span>
        <h2>Граф финансовой диагностики</h2></div>
      <span className="pill">R4.6 · SYNTHETIC PILOT</span>
    </div>
    <p className="graph-intro">Выберите финансовую проблему или исходную форму. FIN определит
      экономически связанные методики, необходимые показатели и отчёты, которые их предоставляют.
      Здесь показывается <strong>план исследования, а не расчёт по реальной организации</strong>.</p>
    <div className="graph-stats" aria-label="Покрытие каталога">
      <div><strong>{counts.total}</strong><span>всего форм</span></div>
      <div><strong>{counts.pilot}</strong><span>источников пилота</span></div>
      <div><strong>{counts.candidates}</strong><span>требуют квалификации</span></div>
      <div><strong>{counts.evidence}</strong><span>только доказательства</span></div>
    </div>
    <div className="graph-controls">
      <label className="field">Начальная точка
        <select aria-label="Источник диагностики" value={mode}
          onChange={e=>setMode(e.target.value as "problem"|"form")}>
          <option value="problem">Бизнес-проблема</option>
          <option value="form">Исходная форма</option>
        </select></label>
      {mode==="problem" ? <label className="field">Диагностическая проблема
        <select aria-label="Диагностическая проблема" value={problemCode}
          onChange={e=>setProblemCode(e.target.value)}>
          {BUSINESS_PROBLEMS.map(p=><option value={p.code} key={p.code}>{p.label}</option>)}
        </select></label>
        : <label className="field">Форма каталога
          <select aria-label="Исходная форма графа" value={formCode}
            onChange={e=>setFormCode(e.target.value)}>
            {RELATIONSHIP_REGISTRY.forms.map(f=><option value={f.code} key={f.code}>
              {f.code} — {formName[f.code] ?? f.kind}
            </option>)}
          </select></label>}
      <label className="field">Отчётный месяц
        <input aria-label="Отчётный месяц графа" type="month" value={month}
          onChange={e=>setMonth(e.target.value)}/></label>
      <label className="field">Глубина связей
        <select aria-label="Глубина графа" value={depth}
          onChange={e=>setDepth(Number(e.target.value))}>
          <option value={1}>1 · прямые связи</option>
          <option value={2}>2 · связанные причины</option>
          <option value={3}>3 · весь квалифицированный пилот</option>
        </select></label>
    </div>
    <p className="graph-disclaimer" role="note">
      Период потоков: {currentMonth}. Остатки: конец {currentMonth}, начальные остатки: конец {lastMonth}.
      Версия реестра: {GRAPH_REGISTRY_VERSION}. Не загружены доверенные PostgreSQL FactSet,
      отсутствует Keycloak/ABAC — результаты и рекомендации здесь <strong>не подтверждены финансовыми данными</strong>.
    </p>
    {plan.status==="NO_REVIEWED_RELATIONSHIP" ?
      <div className="sector-unavailable" role="status">
        Для выбранной формы пока нет методически зарегистрированной исполняемой финансовой связи.
        Форма сохраняется в каталоге, но FIN не выдумывает KPI или взаимосвязи.
      </div> : <>
        <h3 className="graph-subtitle">Методически описанные связи · {plan.rules.length}</h3>
        <div className="graph-rule-list" aria-label="Смежные методики">
          {plan.rules.map(rule=><article className="graph-rule" key={rule.code}>
            <div className="graph-rule-top"><strong>{RULE_LABEL[rule.code] ?? rule.code}</strong>
              <span className="pill subtle">{TYPE_LABEL[rule.type]}</span></div>
            <small>{rule.code} · v{rule.version} · синтетическая проверка</small>
            <div className="graph-rule-metrics">
              {rule.inputs.map(binding=><span key={binding.metric+binding.role} className="graph-metric">
                {binding.metric} <em>{ROLE_LABEL[binding.role]}</em></span>)}
            </div>
          </article>)}
        </div>
        <h3 className="graph-subtitle">Какие отчёты и показатели нужны · {plan.studies.length}</h3>
        <div className="graph-study-list" aria-label="Необходимые отчёты">
          {plan.studies.map((study:GraphStudy)=><div className="graph-study" key={study.formCode}>
            <strong>{study.formCode} · {formName[study.formCode] ?? study.formCode}</strong>
            <span className="graph-small">Нужные данные отсутствуют в доверенном контуре</span>
            <ul>{study.needed.map(d=><li key={d.binding.metric+d.binding.role}>
              <b>{d.binding.metric}</b> · {ROLE_LABEL[d.binding.role]} · методы: {d.unlocks.join(", ")}
            </li>)}</ul>
          </div>)}
        </div>
      </>}
    <p className="graph-disclaimer">Формы с обозначением «только доказательства» не получают
      искусственных числовых KPI. Реальная межформенная диагностика начнёт работать
      после серверного утверждения форм, проверки полноты данных, авторизации и независимого финансового S2.</p>
  </section>;
}
