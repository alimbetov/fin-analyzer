import { useMemo, useRef, useState, type ChangeEvent } from "react";
import ReactECharts from "echarts-for-react";
import {
  BALANCE_METRICS, FORM_VERSION, YEARS, MAX_IMPORT_BYTES,
  emptyBalance, cloneBalance, type BalanceValues, type ApprovedRevision, type MetricCode
} from "./domain/financial";
import { canApprove, normalizeBalance, validateBalance } from "./domain/validation";
import { assetsGrowthPercent, currentRatios, metricSeries } from "./domain/calculations";
import { createProject, readProject } from "./domain/project";
import { generateBalanceTemplate, parseBalanceTemplate } from "./adapters/excel";
import { SectorStudio } from "./sector/SectorStudio";
import { RelationshipStudio } from "./diagnostics/RelationshipStudio";
import { TrustedFinanceWorkspace } from "./diagnostics/TrustedFinanceWorkspace";
import { FormIntakeStudioV5 } from "./intake-v5/FormIntakeStudioV5";
import { createSectorState, type SectorState } from "./sector/state";

function downloadFile(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function safeName(name: string): string {
  return name.trim().replace(/[^\p{L}\p{N}_-]+/gu, "_").slice(0, 50) || "company";
}

function formatNumber(input: string | null): string {
  if (input === null || input.trim() === "") return "—";
  const number = Number(input);
  return Number.isFinite(number) ? new Intl.NumberFormat("ru-RU", {
    maximumFractionDigits: 4
  }).format(number) : input;
}

function sampleValues(): BalanceValues {
  return {
    "2024": {
      TOTAL_ASSETS: "850000", CURRENT_ASSETS: "365000",
      TOTAL_LIABILITIES: "510000", CURRENT_LIABILITIES: "240000", TOTAL_EQUITY: "340000"
    },
    "2025": {
      TOTAL_ASSETS: "970000", CURRENT_ASSETS: "470000",
      TOTAL_LIABILITIES: "560000", CURRENT_LIABILITIES: "255000", TOTAL_EQUITY: "410000"
    }
  };
}

export default function App() {
  const [company, setCompany] = useState("");
  const [sector, setSector] = useState<SectorState>(() => createSectorState());
  const [draft, setDraft] = useState<BalanceValues>(emptyBalance);
  const [revisions, setRevisions] = useState<ApprovedRevision[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [year, setYear] = useState<(typeof YEARS)[number]>("2025");
  const uploadRef = useRef<HTMLInputElement>(null);
  const projectRef = useRef<HTMLInputElement>(null);
  const findings = useMemo(() => validateBalance(draft), [draft]);
  const blockerCount = findings.filter(f => f.severity === "BLOCKER").length;
  const latest = revisions.at(-1) ?? null;
  const ratios = useMemo(() => latest ? currentRatios(latest.values) : [], [latest]);
  const growth = useMemo(() => latest ? assetsGrowthPercent(latest.values) : null, [latest]);

  function updateValue(metric: MetricCode, value: string, period: (typeof YEARS)[number]) {
    setDraft(old => ({
      ...old,
      [period]: { ...old[period], [metric]: value }
    }));
    setMessage("");
  }

  async function onDownloadTemplate() {
    if (!company.trim()) {
      setError("Сначала укажите название компании.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const file = await generateBalanceTemplate(company.trim());
      downloadFile(file, "FIN_BALANCE_" + safeName(company) + "_2024_2025.xlsx");
      setMessage("Шаблон выгружен. Заполните голубые ячейки C6:D10 и загрузите файл обратно.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось сформировать шаблон XLSX");
    } finally {
      setBusy(false);
    }
  }

  async function onUpload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    if (!company.trim()) {
      setError("Перед загрузкой укажите компанию.");
      return;
    }
    if (!file.name.toLowerCase().endsWith(".xlsx") || file.size > MAX_IMPORT_BYTES) {
      setError("Допускается только шаблон FIN .xlsx размером до 2 МБ.");
      return;
    }
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const parsed = await parseBalanceTemplate(await file.arrayBuffer(), company.trim());
      setDraft(normalizeBalance(parsed.values));
      const count = parsed.findings.filter(f => f.severity === "BLOCKER").length;
      setMessage(count
        ? "Импортирован черновик. Обнаружены ошибки: " + count + ". Исправьте значения перед утверждением."
        : "Excel успешно импортирован. Проверьте данные и нажмите «Утвердить баланс».");
    } catch (cause) {
      // Fatal structural errors never replace the current draft or approved revisions.
      setError(cause instanceof Error ? cause.message : "Ошибка импорта Excel.");
    } finally {
      setBusy(false);
    }
  }

  function onApprove() {
    if (!company.trim()) {
      setError("Нужно указать компанию.");
      return;
    }
    if (!canApprove(findings)) {
      setError("Утверждение запрещено: обнаружено блокирующих ошибок — " + blockerCount + ".");
      return;
    }
    const values = normalizeBalance(draft);
    const newRevision: ApprovedRevision = {
      id: crypto.randomUUID(), approvedAt: new Date().toISOString(),
      company: company.trim(), formVersion: FORM_VERSION, values: cloneBalance(values)
    };
    setRevisions(old => [...old, newRevision].slice(-50));
    setMessage("Баланс подтверждён локально. Расчёты и диаграммы обновлены.");
    setError("");
  }

  function onSaveProject() {
    const project = createProject(company.trim(), draft, revisions, sector);
    downloadFile(new Blob([JSON.stringify(project, null, 2)], { type: "application/json" }),
      "FIN_" + safeName(company) + ".fin.json");
    setMessage("Проект сохранён в локальный JSON-файл.");
    setError("");
  }

  async function onOpenProject(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    setError("");
    try {
      if (file.size > MAX_IMPORT_BYTES) throw new Error("Файл проекта слишком большой");
      const project = readProject(await file.text());
      setCompany(project.company);
      setDraft(project.draft);
      setRevisions(project.revisions);
      setSector(project.sector ?? createSectorState());
      setMessage("Проект восстановлен: " + project.revisions.length + " утверждённых ревизий.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Файл проекта не удалось открыть.");
    }
  }

  function onNewProject() {
    if (revisions.length > 0 || company.trim() || sector.current.length > 0) {
      const ok = window.confirm("Несохранённые данные будут потеряны. Создать новый проект?");
      if (!ok) return;
    }
    setCompany("");
    setDraft(emptyBalance());
    setRevisions([]);
    setSector(createSectorState());
    setMessage("");
    setError("");
  }

  const assetSeries = latest ? metricSeries(latest.values, "TOTAL_ASSETS").map(Number) : [];
  const liabilitySeries = latest ? metricSeries(latest.values, "TOTAL_LIABILITIES").map(Number) : [];
  const ratioSeries = ratios.map(item => item.value === null ? null : Number(item.value));
  const chartTheme = {
    backgroundColor: "transparent",
    textStyle: { color: "#d1deee", fontFamily: "system-ui, sans-serif" },
    tooltip: { trigger: "axis", backgroundColor: "#10233b", borderColor: "#335273",
      textStyle: { color: "#ecf5ff" } },
    legend: { textStyle: { color: "#b5c8e3" }, bottom: 0 },
    grid: { top: 24, right: 28, bottom: 57, left: 70 },
    xAxis: { type: "category", data: [...YEARS], axisLabel: { color: "#a6c0da" },
      axisLine: { lineStyle: { color: "#335273" } } },
    yAxis: { type: "value", axisLabel: { color: "#a6c0da" }, splitLine: { lineStyle: { color: "#253b57" } } }
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><div className="brand-mark">F</div><div><strong>FIN Analyzer</strong><small>FINANCIAL STUDIO</small></div></div>
        <div className="sidebar-title">РАБОЧЕЕ ПРОСТРАНСТВО</div>
        <nav aria-label="Этапы работы">
          <a className="nav-active" href="#data">01 · Финансовые данные</a>
          <a href="#validation">02 · Проверка данных</a>
          <a href="#analysis">03 · Аналитика</a>
          <a href="#sector">04 · Отрасли</a>
          <a href="#graph">05 · Диагностический граф</a>
          <a href="#form-intake-v5">06 · Ввод форм V5 (пилот)</a>
          <a href="#trusted-finance">07 · Проверенные источники</a>
          <a href="#project">08 · Проект</a>
        </nav>
        <div className="side-note"><span className="status-dot"></span> Local-first · без Backend и БД</div>
        <p className="side-foot">FIN-R0 / R1A<br />Контролируемый Excel и первый аналитический отчёт</p>
      </aside>

      <main className="workspace">
        <header className="topbar">
          <span className="crumb">Рабочее пространство / <b>Финансовый анализ</b></span>
          <span className="release-tag">MVP · BALANCE 1.0</span>
        </header>
        <section className="hero">
          <div>
            <span className="eyebrow">FINANCIAL INTELLIGENCE</span>
            <h1>Финансовая отчётность<br /><span>под контролем</span></h1>
            <p>Скачайте шаблон, заполните бухгалтерский баланс за два года, проверьте значения и получите достоверные показатели и диаграммы.</p>
          </div>
          <div className="hero-art" aria-hidden="true">
            <div className="orb orb-one"></div><div className="orb orb-two"></div>
            <div className="hero-stat"><span>DATA QUALITY</span><strong>01 / 04</strong><small>Баланс — первый этап</small></div>
          </div>
        </section>

        <section className="panel" id="data">
          <div className="panel-heading"><div><span className="eyebrow">01 / INPUT</span><h2>Финансовые данные</h2></div><span className="pill">{revisions.length ? "Есть утверждённая версия" : "Новый проект"}</span></div>
          <div className="company-row">
            <label className="field">Компания
              <input aria-label="Название компании" value={company} onChange={e => setCompany(e.target.value)}
                maxLength={200} placeholder="Например, ТОО «Демо»" disabled={revisions.length > 0} />
            </label>
            <div className="field">Периоды <div className="read-only">2024 и 2025</div></div>
            <div className="field">Единицы <div className="read-only">тыс. ₸</div></div>
          </div>
          <div className="statement-card">
            <div className="statement-heading">
              <div className="statement-icon">▦</div>
              <div className="grow"><h3>Бухгалтерский баланс</h3><p>Активы · обязательства · собственный капитал</p></div>
              <span className="pill subtle">XLSX v{FORM_VERSION}</span>
            </div>
            <div className="action-row">
              <button className="button primary" type="button" onClick={onDownloadTemplate} disabled={busy}>↓ Скачать шаблон Excel</button>
              <button className="button secondary" type="button" onClick={() => uploadRef.current?.click()} disabled={busy}>↑ Загрузить заполненный XLSX</button>
              <input ref={uploadRef} className="visually-hidden" type="file" accept=".xlsx" aria-label="Выбрать заполненный XLSX" onChange={onUpload} />
              <button className="button ghost" type="button" onClick={() => { setDraft(sampleValues()); setMessage("Загружены демонстрационные значения — не официальная отчётность."); setError(""); }}>Демо-данные</button>
            </div>
          </div>
          <div className="editor-head"><h3>Проверка и редактирование показателей</h3><span>Можно исправить числа прямо в браузере</span></div>
          <div className="table-scroll"><table className="data-table">
            <thead><tr><th>Код / показатель</th>{YEARS.map(period => <th key={period}>{period} г. · тыс. ₸</th>)}</tr></thead>
            <tbody>
              {BALANCE_METRICS.map(metric => <tr key={metric.code}>
                <td><div className="metric-label">{metric.label}</div><div className="metric-code">{metric.code}</div></td>
                {YEARS.map(period => <td key={period}>
                  <input type="text" inputMode="decimal" aria-label={metric.label + " " + period}
                    value={draft[period][metric.code]} placeholder="—"
                    onChange={e => updateValue(metric.code, e.target.value, period)}
                    className={findings.some(f => f.severity === "BLOCKER" && f.year === period && f.metric === metric.code) ? "invalid" : ""}/>
                </td>)}
              </tr>)}
            </tbody>
          </table></div>
          <div className="editor-footer">
            <span>Формулы в ячейках XLSX запрещены. Пустые значения не считаются нулём.</span>
            <button type="button" className="button primary" onClick={onApprove} disabled={busy || blockerCount > 0 || !company.trim()}>✓ Утвердить баланс</button>
          </div>
        </section>

        <section className="panel" id="validation">
          <div className="panel-heading"><div><span className="eyebrow">02 / QUALITY</span><h2>Контроль данных</h2></div>
            <span className={blockerCount ? "pill danger" : "pill success"}>{blockerCount ? blockerCount + " блокирующих ошибок" : "Баланс проходит проверки"}</span>
          </div>
          <div className="validation-summary"><div className={blockerCount ? "quality-icon bad" : "quality-icon good"}>{blockerCount ? "!" : "✓"}</div>
            <div><strong>{blockerCount ? "Требуются исправления" : "Структурные и бухгалтерские проверки пройдены"}</strong>
              <p>Для каждого года: Активы = Обязательства + Капитал; контроль текущих активов и обязательств.</p></div></div>
          {findings.length > 0 && <div className="findings" aria-label="Результаты проверки">
            {findings.slice(0, 20).map((finding, index) => <div className="finding" key={finding.code + finding.year + finding.metric + index}>
              <span className={finding.severity === "BLOCKER" ? "issue-marker blocker" : "issue-marker warning"}>{finding.severity === "BLOCKER" ? "×" : "!"}</span>
              <span>{finding.message} {finding.year && "· " + finding.year}{finding.cell && " · " + finding.cell}</span>
            </div>)}
            {findings.length > 20 && <small>Показаны первые 20 замечаний из {findings.length}</small>}
          </div>}
        </section>

        <section className="panel" id="analysis">
          <div className="panel-heading"><div><span className="eyebrow">03 / ANALYTICS</span><h2>Финансовая аналитика</h2></div>
            <span className="pill">{latest ? "На основании утверждённой версии" : "Ожидает утверждения"}</span>
          </div>
          {!latest ? <div className="empty-analysis"><div className="empty-icon">▥</div>
            <h3>Анализ появится после утверждения баланса</h3><p>Загрузите значения по двум периодам и исправьте блокирующие ошибки. Графики никогда не рассчитываются по неутверждённому черновику.</p></div>
            : <>
              <div className="kpi-grid">
                <div className="kpi"><span>Итого активы · {year}</span><strong>{formatNumber(latest.values[year].TOTAL_ASSETS)}</strong><small>тыс. ₸</small></div>
                <div className="kpi"><span>Обязательства · {year}</span><strong>{formatNumber(latest.values[year].TOTAL_LIABILITIES)}</strong><small>тыс. ₸</small></div>
                <div className="kpi"><span>Current Ratio · {year}</span><strong>{formatNumber(ratios.find(r => r.year === year)?.value ?? null)}</strong><small>оборотные активы / краткосрочные обязательства</small></div>
                <div className="kpi"><span>Изменение активов г/г</span><strong>{growth === null ? "—" : formatNumber(growth) + "%"}</strong><small>2025 относительно 2024</small></div>
              </div>
              <div className="year-switch">Показатели:
                {YEARS.map(period => <button type="button" key={period} className={year === period ? "chosen" : ""} onClick={() => setYear(period)}>{period}</button>)}
              </div>
              <div className="charts">
                <div className="chart-card"><h3>Структура баланса</h3><p>Активы и обязательства · тыс. ₸</p>
                  <ReactECharts style={{ height: 280 }} opts={{ renderer: "svg" }} option={{...chartTheme,
                    color: ["#54b7ff", "#986bff"], series: [
                      { name: "Активы", type: "bar", barMaxWidth: 48, data: assetSeries, itemStyle: { borderRadius: [6,6,0,0] } },
                      { name: "Обязательства", type: "bar", barMaxWidth: 48, data: liabilitySeries, itemStyle: { borderRadius: [6,6,0,0] } }
                    ]}} />
                </div>
                <div className="chart-card"><h3>Текущая ликвидность</h3><p>Current Ratio по годам · раз</p>
                  <ReactECharts style={{ height: 280 }} opts={{ renderer: "svg" }} option={{...chartTheme,
                    color: ["#2dd4bf"],
                    series: [{ name: "Current Ratio", type: "line", smooth: true, symbolSize: 11,
                      areaStyle: { opacity: 0.1 }, lineStyle: { width: 4 }, data: ratioSeries }]
                  }} />
                </div>
              </div>
              <p className="provenance">Источники: утверждённая ревизия {latest.id.slice(0, 8)} · форма Balance {latest.formVersion} · {latest.approvedAt.slice(0, 10)}. Расчёт Current Ratio не является кредитным рейтингом.</p>
            </>}
        </section>

        <SectorStudio company={company} project={sector} onChange={setSector} />

        <RelationshipStudio />

        <FormIntakeStudioV5 />
        <TrustedFinanceWorkspace />

        <section className="panel project-panel" id="project">
          <div><span className="eyebrow">07 / LOCAL STORAGE</span><h2>Локальный проект</h2>
            <p>Финансовые данные не передаются на сервер. Скачайте FIN-проект, чтобы восстановить работу позднее.</p>
            <small>Утверждённых версий: {revisions.length}. Автосохранение отсутствует.</small>
          </div>
          <div className="project-actions">
            <button type="button" className="button primary" onClick={onSaveProject}>↓ Сохранить .fin.json</button>
            <button type="button" className="button secondary" onClick={() => projectRef.current?.click()}>↑ Открыть проект</button>
            <input ref={projectRef} className="visually-hidden" type="file" accept=".json,.fin.json" aria-label="Выбрать FIN-проект" onChange={onOpenProject}/>
            <button type="button" className="button ghost" onClick={onNewProject}>Новый проект</button>
          </div>
        </section>
        <footer>FIN Analyzer · Local-first financial intelligence · {FORM_VERSION}</footer>
      </main>
      {error && <div role="alert" className="toast error"><span>{error}</span><button aria-label="Закрыть" onClick={() => setError("")}>×</button></div>}
      {message && !error && <div role="status" className="toast"><span>{message}</span><button aria-label="Закрыть" onClick={() => setMessage("")}>×</button></div>}
    </div>
  );
}
