import { useMemo, useRef, useState, type ChangeEvent } from "react";
import { SECTOR_PACKS, pilotCode, sectionTitle, selectForms } from "./registry";
import {
  approveSectorRow, calculateSectorState, fieldsForForm, prepareSectorRow,
  type SectorState
} from "./state";
import { generateSectorTemplate, parseSectorTemplate, type SectorTemplateContext } from "./excel";
import { PILOT_METHODS } from "./methods";

const FIELD_LABEL: Record<string, string> = {
  netRevenue: "Чистая выручка", cogs: "Себестоимость продаж", soldQty: "Продано единиц",
  periodCogs: "Себестоимость за месяц", avgStockValue: "Средняя стоимость запаса",
  purchasePrice: "Цена закупки за единицу", receivedQty: "Принято единиц", freight: "Доставка",
  billableHours: "Оплачиваемые часы", availableHours: "Доступные часы",
  recognizedFee: "Признанный доход по контракту", directHours: "Рабочие часы",
  hourCost: "Стоимость часа", otherCost: "Прочие прямые затраты",
  directLaborCost: "Прямые затраты труда", materialsCost: "Материалы",
  completedJobs: "Завершённые заказы", unitsStarted: "Запущено в производство",
  goodUnits: "Годная продукция", reworkedUnits: "Повторно обработанные единицы",
  wasteUnits: "Брак/отходы", materialUsed: "Использованные материалы",
  directLabor: "Затраты труда", overhead: "Распределённые накладные",
  runHours: "Часы работы оборудования", plannedHours: "Плановые часы",
  downtimeHours: "Часы простоя"
};
const FORM_LABEL: Record<string, string> = {
  "SF-G01": "Продажи и маржа SKU", "SF-G02": "Оборачиваемость запаса",
  "SF-G03": "Стоимость закупки и доставки", "SF-M01": "Оплачиваемые часы",
  "SF-M02": "Результат по контракту", "SF-S01": "Рентабельность услуги",
  "SF-C01": "Выход годной продукции", "SF-C02": "Себестоимость единицы",
  "SF-C03": "Загрузка оборудования"
};
const WINDOW_LABEL: Record<string, string> = {
  MONTH: "Месяц", QUARTER: "Квартал", HALF_YEAR: "Полугодие", YEAR: "Год"
};
function fileDownload(file: Blob, name: string): void {
  const url = URL.createObjectURL(file);
  const link=document.createElement("a");
  link.href=url;
  link.download=name;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(()=>URL.revokeObjectURL(url),1000);
}
interface Props {
  company: string;
  project: SectorState;
  onChange: (next: SectorState) => void;
}
export function SectorStudio({ company, project, onChange }: Props) {
  const [section,setSection]=useState("G");
  const [model,setModel]=useState("G_RETAIL");
  const [form,setForm]=useState("SF-G01");
  const [dimension,setDimension]=useState("sku-1");
  const [period,setPeriod]=useState("2026-11");
  const [currency,setCurrency]=useState("KZT");
  const [values,setValues]=useState<Record<string,string>>({});
  const [message,setMessage]=useState("");
  const [error,setError]=useState("");
  const [busy,setBusy]=useState(false);
  const uploadRef=useRef<HTMLInputElement>(null);
  const pack=useMemo(()=>SECTOR_PACKS.find(item=>item.section===section)!,[section]);
  const availableForms=useMemo(()=>selectForms(model),[model]);
  const executable=pilotCode(form);
  const selectedFields=executable?fieldsForForm(executable):[];
  const outputs=useMemo(()=>calculateSectorState(project),[project]);
  const visible=outputs.filter(result=>result.formCode===form &&
    result.dimensionId===model+"."+dimension && result.currency===currency);
  const templateContext:SectorTemplateContext|null=executable?{
    company:company.trim(),formCode:executable,modelCode:model,dimension,period,currency
  }:null;
  const fingerprint=JSON.stringify(templateContext);
  const contextFingerprint=useRef(fingerprint);
  contextFingerprint.current=fingerprint;

  function canDiscardDraft(): boolean {
    return !Object.values(values).some(value => value.trim() !== "") ||
      window.confirm("Неподтверждённые отраслевые значения будут потеряны при смене формы или контекста. Продолжить?");
  }
  function clearDraft() {
    setValues({});
    setError("");
    setMessage("");
  }
  function chooseSection(code:string) {
    const next=SECTOR_PACKS.find(item=>item.section===code);
    if(!next || (next.section!==section && !canDiscardDraft()))return;
    setSection(next.section);
    setModel(next.models[0]!);
    setForm(next.forms[0]!);
    clearDraft();
  }
  function chooseModel(code:string) {
    if(!pack.models.includes(code) || (code!==model && !canDiscardDraft()))return;
    setModel(code);
    setForm(pack.forms[0]!);
    clearDraft();
  }
  function chooseForm(code:string) {
    if(!pack.forms.includes(code) || (code!==form && !canDiscardDraft()))return;
    setForm(code);
    clearDraft();
  }
  function confirm() {
    setError("");
    setMessage("");
    try {
      if(!company.trim())throw new Error("Укажите название компании перед подтверждением отраслевых данных.");
      const accepted=prepareSectorRow(project,model,form,dimension,currency,period,values);
      onChange(approveSectorRow(project,accepted));
      setMessage("Локально подтверждена ревизия "+accepted.row.revisionId.slice(0,12)+". Результаты пересчитаны.");
    } catch(cause) {
      setError(cause instanceof Error?cause.message:"Не удалось подтвердить данные.");
    }
  }
  async function download() {
    if(!templateContext || !company.trim()) {
      setError("Укажите компанию и исполняемую отраслевую форму.");
      return;
    }
    setBusy(true);setError("");
    try {
      const file=await generateSectorTemplate(templateContext);
      fileDownload(file,"FIN_"+form+"_"+period+".xlsx");
      setMessage("Шаблон сформирован: заполните значения на листе Sector и загрузите обратно.");
    } catch(cause) {
      setError(cause instanceof Error?cause.message:"Ошибка подготовки XLSX.");
    } finally {setBusy(false);}
  }
  async function upload(event:ChangeEvent<HTMLInputElement>) {
    const file=event.target.files?.[0];
    event.target.value="";
    if(!file)return;
    if(!templateContext || !company.trim() || !file.name.toLowerCase().endsWith(".xlsx") ||
       file.size>2_000_000) {
      setError("Нужен контролируемый XLSX FIN до 2 МБ для выбранной формы.");
      return;
    }
    setBusy(true);setError("");setMessage("");
    try {
      const expectedFingerprint=JSON.stringify(templateContext);
      const draft=await parseSectorTemplate(await file.arrayBuffer(),templateContext);
      if(contextFingerprint.current!==expectedFingerprint) throw new Error("SECTOR_XLSX_CONTEXT_CHANGED: загрузите файл повторно для нового выбора.");
      setValues(draft);
      setMessage("Excel импортирован в черновик. Проверьте поля и подтвердите отдельно.");
    } catch(cause) {
      setError(cause instanceof Error?cause.message:"Ошибка импорта XLSX.");
    } finally {setBusy(false);}
  }

  return <section className="panel sector-panel" id="sector">
    <div className="panel-heading">
      <div><span className="eyebrow">IND-R0 / IND-R1 · LOCAL PILOT</span><h2>Отраслевая диагностика</h2></div>
      <span className="pill">9 исполняемых форм / 63 в каталоге</span>
    </div>
    <p className="sector-note">Пилотные расчёты не прошли независимое методическое утверждение и не являются аудиторским заключением.
      Сейчас используется уровень секции ОКЭД A–U; сопоставление отдельных пятизначных кодов ещё не реализовано.</p>
    <div className="sector-inputs">
      <label className="field">Сектор ОКЭД (раздел)
        <select aria-label="Сектор ОКЭД" disabled={busy} value={section} onChange={e=>chooseSection(e.target.value)}>
          {SECTOR_PACKS.map(item=><option key={item.section} value={item.section}>
            {item.section} · {sectionTitle(item.section)}
          </option>)}
        </select>
      </label>
      <label className="field">Вариант деятельности
        <select aria-label="Вариант деятельности" disabled={busy} value={model} onChange={e=>chooseModel(e.target.value)}>
          {pack.models.map(code=><option key={code} value={code}>{code}</option>)}
        </select>
      </label>
      <label className="field">Отраслевая форма
        <select aria-label="Отраслевая форма" disabled={busy} value={form} onChange={e=>chooseForm(e.target.value)}>
          {availableForms.map(item=><option key={item.code} value={item.code}>
            {item.code} · {FORM_LABEL[item.code]??"Методика не реализована"} {item.executable?"(пилот)":"(только каталог)"}
          </option>)}
        </select>
      </label>
    </div>
    <p className="sector-catalog-note">Общие источники: {pack.baseForms.join(", ")}. Требуемые расширения: {pack.extensions.join(", ")}.
      Статус каталога: {pack.catalogStatus}. Правила отрасли: {pack.methodologyRisk}</p>
    {!executable ? <div className="sector-unavailable" role="status">
      Для выбранной формы есть спецификация, но исполняемый расчёт не реализован.
      FIN не будет отображать неподтверждённые KPI как рассчитанные.
    </div> : <>
      <div className="sector-inputs">
        <label className="field">Объект / SKU / договор
          <input aria-label="Код объекта" disabled={busy} value={dimension} maxLength={45}
            onChange={e=>{if(canDiscardDraft()){setDimension(e.target.value);clearDraft();}}} placeholder="sku-1" />
        </label>
        <label className="field">Месяц данных
          <input aria-label="Месяц отраслевых данных" type="month" disabled={busy} value={period}
            onChange={e=>{if(canDiscardDraft()){setPeriod(e.target.value);clearDraft();}}} />
        </label>
        <label className="field">Валюта
          <select aria-label="Валюта отраслевых данных" disabled={busy} value={currency}
            onChange={e=>{if(canDiscardDraft()){setCurrency(e.target.value);clearDraft();}}}>
            <option>KZT</option><option>USD</option><option>EUR</option>
          </select>
        </label>
      </div>
      <div className="action-row">
        <button type="button" className="button secondary" onClick={download} disabled={busy || !company.trim()}>
          Скачать Excel для {form}
        </button>
        <button type="button" className="button secondary" onClick={()=>uploadRef.current?.click()} disabled={busy || !company.trim()}>
          Загрузить отраслевой XLSX
        </button>
        <input ref={uploadRef} type="file" className="visually-hidden" accept=".xlsx"
          aria-label="Выбрать отраслевой XLSX" onChange={upload}/>
      </div>
      <div className="table-scroll">
        <table className="data-table sector-table"><thead><tr><th>Код / показатель</th><th>Значение за {period}</th></tr></thead>
          <tbody>{selectedFields.map(field=><tr key={field}>
            <td><div className="metric-label">{FIELD_LABEL[field]??field}</div><div className="metric-code">{field}</div></td>
            <td><input aria-label={"Отраслевое поле "+field} inputMode="decimal"
              placeholder="Пусто ≠ 0" value={values[field]??""}
              onChange={e=>{setValues(old=>({...old,[field]:e.target.value}));setError("");setMessage("");}} /></td>
          </tr>)}</tbody>
        </table>
      </div>
      <div className="editor-footer">
        <span>Значения в десятичных строках. Ноль указывается явно; пустое поле не будет подтверждено.
          Excel попадает только в черновик. Неподтверждённые поля не входят в сохранённый FIN-проект. Действующая расчётная версия фиксируется отдельно.</span>
        <button type="button" className="button primary" disabled={busy || !company.trim()} onClick={confirm}>
          Подтвердить отраслевые данные
        </button>
      </div>
    </>}
    {error && <p role="alert" className="sector-error">{error}</p>}
    {message && <p role="status" className="sector-ok">{message}</p>}
    <div className="editor-head"><h3>Результаты отраслевого пилота</h3><span>Только из локально подтверждённых ревизий</span></div>
    {visible.length===0 ? <p className="sector-note">Для текущей формы и объекта подтверждённых данных пока нет.</p> :
      <div className="sector-results">{visible.map(result=><article className="sector-result" key={result.windowType+result.windowCode}>
        <div className="sector-result-title"><strong>{WINDOW_LABEL[result.windowType]}: {result.windowCode}</strong>
          <span className="pill subtle">{result.status==="CALCULATED"?"Данные полные":"Неполный период"}</span></div>
        {Object.entries(result.metrics).map(([code,metric])=><div className="sector-metric" key={code}>
          <span>{PILOT_METHODS[code]?.translations.ru.name??code}</span><strong>{metric.value??"Не вычисляется"}</strong>
          <small>{metric.value===null?(metric.reason??"Нет данных"):(metric.unit??"")} · {PILOT_METHODS[code]?.formula??"Метод не зарегистрирован"} · {PILOT_METHODS[code]?.qualification??"UNQUALIFIED"}</small>
        </div>)}
        {result.missingFields.length>0 && <p>Не хватает полей: {result.missingFields.join(", ")}</p>}
        {result.missingPeriods.length>0 && <p>Не хватает месяцев: {result.missingPeriods.join(", ")}</p>}
        <small className="sector-source">Формулы — кандидаты pilot-1.0, не независимое методическое одобрение. Ревизии источников: {result.sourceRevisions.join(", ")||"нет"}</small>
      </article>)}</div>}
    <p className="sector-note">Локальных актуальных записей: {project.current.length}; прошлых ревизий: {project.prior.length}.
      При изменении утверждённой записи предыдущая ревизия сохраняется в FIN-проекте.</p>
  </section>;
}
