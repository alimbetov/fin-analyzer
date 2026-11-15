# FIN Analyzer — архитектура v1.0

**Статус:** proposed, not implemented. Техническая база: [FIN-SPEC](FIN-SPEC.md).

## 1. Architecture Decision Records

**ADR-001 — Browser-first.** React + TypeScript + Vite, хостинг статических файлов. В MVP нет Backend, серверной БД, обязательного логина или обмена финансовыми данными по сети. Все рабочие данные в памяти с явной выгрузкой файла проекта.

**ADR-002 — Controlled XLSX Contract.** FIN формирует собственные XLSX-файлы на основе FormDefinition. Произвольный XLSX/PDF не является доверенным источником чисел.

**ADR-003 — Single Canonical Model.** Excel и React Editor являются двумя адаптерами для одной модели FinancialStatement; аналитика ничего не знает о расположении ячеек.

**ADR-004 — Deterministic Financial Core.** Decimal-арифметика, версионируемый DSL формул, граф зависимостей, недостающие значения и прозрачная трассировка до исходного документа/ячейки.

**ADR-005 — Presentation Independence.** ECharts и PDF/HTML Export потребляют одни и те же AnalysisSnapshot/ReportSnapshot. Quarto опционален и не является runtime-зависимостью MVP.

**ADR-006 — Revision Safety.** Импорт не изменяет APPROVED непосредственно; обязательно стадирование, проверка и явное подтверждение.

## 2. Рекомендуемый стек

| Слой | Решение | Назначение |
|---|---|---|
| Build | Vite + TypeScript strict | SPA |
| UI | React + MUI | адаптивный desktop-first интерфейс |
| State | Zustand | in-memory store с селекторами |
| Validation | Zod + pure domain rules | структурная и финансовая проверка |
| Arithmetic | decimal.js | точные расчёты после корректного импорта чисел |
| Spreadsheet | ExcelJS | генерация/чтение XLSX в браузере |
| Charts | Apache ECharts + React wrapper | диаграммы SVG/Canvas |
| Export | Print-friendly HTML + @react-pdf/renderer (если нужен программный PDF) | печатные отчёты |
| Unit tests | Vitest | доменные функции, Excel-адаптер |
| Component tests | React Testing Library | интерфейс и состояния |
| E2E | Playwright | сквозные пользовательские сценарии |
| Offline | Service Worker / PWA plugin | статические ресурсы после установки |

Точные версии зависимостей фиксируются в lockfile при старте реализации. Крупные библиотеки загружаются по требованию (lazy import) для контроля размера клиентского бандла.

## 3. Модульная структура

~~~text
src/
  app/                      # маршруты, инициализация, темы, shell
  features/
    companies/              # компания и reporting context
    statements/             # карточки Balance / Income / CashFlow / Equity
    credit-forms/           # PLANNED: каталог F/C/P, применимость и табличные формы
    credit-forecast/        # PLANNED: прогнозные сценарии, графики долга, DSCR
    tax-evidence/           # PLANNED: метаданные и подтверждение сведений из ФНО
    credit-dossier/         # PLANNED: документы D01-D08 и комплектность
    excel-exchange/         # Download Template / Upload Session
    financial-editor/       # React form из FormDefinition
    validation-workbench/   # ошибки и исправления
    strategy-studio/        # параметры стратегий и Rule Builder
    analysis-studio/        # KPI, charts, explainability, сравнения
    report-builder/         # компоновка отчётов / экспорт
    project-files/          # export/import .fin.json
  domain/
    financial-model/        # устойчивые типы, контексты, ценности
    registry/               # metric/form/strategy definitions
    formulas/               # typed AST, dependency graph
    validation/             # бухгалтерские инварианты
    analysis/               # расчёты и диагностика
    reporting/              # ReportSnapshot / ChartSpec
  adapters/
    exceljs/                # XLSX schema, encode/decode
    file-system/            # browser File API, downloads
    echarts/                # ChartSpec -> ECharts
    pdf/                    # ReportSnapshot -> printable artifacts
  config/
    metrics/                # JSON definitions версионируемые с кодом
    forms/
    strategies/
  test/
    fixtures/               # red/green XLSX and domain cases
    e2e/
docs/
~~~

Зависимости направлены внутрь: features/adapters → domain. Доменные функции не импортируют React, ExcelJS, ECharts или Window. Это позволит тестировать финансовую математику независимо от UI.

## 4. Типовая модель данных (концепт)

~~~typescript
// Только четыре базовых отчёта. Для расширенного каталога потребуется независимый FormCode.
type StatementType = "BALANCE" | "INCOME" | "CASH_FLOW" | "EQUITY";
type Basis = "SEPARATE" | "CONSOLIDATED";
type ReportStatus = "DRAFT" | "VALIDATED" | "APPROVED";
type ValueState = "PRESENT" | "MISSING" | "NOT_APPLICABLE";

interface ReportingContext {
  companyId: string;
  periodStart?: string;       // ISO date, для flow
  periodEnd: string;          // ISO date, для stock/flow
  frequency: "FY" | "H1" | "Q1" | "Q2" | "Q3" | "Q4";
  basis: Basis;
  accountingStandard: string;
  currency: string;           // ISO 4217
  scale: "UNITS" | "THOUSANDS" | "MILLIONS";
}

interface FinancialValue {
  metricCode: string;
  contextId: string;
  state: ValueState;
  rawDecimal?: string;        // десятичная строка при PRESENT
  source: {
    mode: "XLSX" | "REACT";
    formCode: StatementType;
    templateVersion: string;
    sheet?: string;
    address?: string;
  };
  revisionId: string;
}

interface FinancialReportRevision {
  id: string;
  type: StatementType;
  contextId: string;
  formVersion: string;
  status: ReportStatus;
  values: FinancialValue[];
  validationSummary: ValidationSummary;
}

interface FinProject {
  schemaVersion: string;
  company: CompanyMetadata;
  contexts: ReportingContext[];
  reports: FinancialReportRevision[];
  customStrategies: StrategyDefinition[];
  reportLayouts: ReportDefinition[];
}
~~~

Обозначения типов — проектная схема. При реализации она должна пройти ревью, включая ограничения ключей, нормализацию версий и формальную JSON Schema/Zod проверку.

**Планируемое расширение:** каталог [FORM-CATALOG](FORM-CATALOG.md) содержит 44 позиции. Он требует отдельного `FormCode` и `FormKind` (STRUCTURED/COMPOSITE/EXTERNAL-TAX/DOCUMENT), а также моделей строковых расшифровок, контрагентов, прогнозных сценариев, долговых графиков и реестра документов. Текущий `FinancialValue.source.formCode: StatementType` — только R1A-контракт, не универсальный тип. `StatementType` для четырёх основных отчётов сохраняется. Реализация расширения не должна менять уже утверждённые ревизии и расчётные snapshots.

Для поточных метрик период задаётся интервалом, для балансовых — датой. При расчётах LAG и средних запрещено смешивать несоответствующие интервалы/основы консолидации.

## 5. XLSX — контролируемый протокол

### 5.1. Структура книги

~~~text
FIN_BALANCE_v1.xlsx
  Instructions       — правила заполнения, единицы
  Balance            — утверждённые коды и значения по годам
  _Metadata          — templateId, version, formCode, schemaVersion,
                       ожидаемые столбцы, reportingContext,
                       идентификаторы строк
~~~

Метаданные служат проверке совместимости и согласованности, **не являются криптографическим доказательством** подлинности файла. Лист может быть изменён вручную. Импортёр всегда сверяет книгу с локально установленным определением формы и выполняет доменную проверку.

### 5.2. Пайплайн импорта

~~~text
File selection
    ↓
MIME/extension + size/budget check
    ↓
XLSX unzip limits + workbook structural checks
    ↓
Manifest/version/form/context comparison
    ↓
Metric mapping by stable codes, not by translated labels
    ↓
Typed decimal parsing and value provenance
    ↓
ValidationFindings (BLOCKER / WARNING / INFO)
    ↓
ImportPreview (diff to approved revision)
    ↓
Explicit user review / approval
    ↓
New immutable approved revision + analysis recalculation
~~~

Недопустимый файл не должен нарушать уже сохранённое состояние: все стадии до commit pure/transaction-like. Хэш входного файла — полезный идентификатор загрузки и дедупликации, но не доказательство внешней достоверности.

### 5.3. Точность и ограничения Excel

Excel хранит числовые ячейки в формате floating point с ограниченной точностью (около 15 значащих десятичных цифр). decimal.js предотвращает новые ошибки округления *после* чтения, но не восстанавливает уже потерянные Excel-цифры.

- Для допустимых финансовых сумм установить точные пределы, масштаб и ожидаемую точность.
- Для больших или высокоточных чисел использовать текстовые decimal-поля в шаблоне и строгий parsing, либо явно отклонять неоднозначную точность.
- Отсутствие, «—», ноль, отрицательная величина и округлённый итог различаются.
- Допуск сверки зависит от масштаба и округления и задаётся правилом, а не универсально равен 0.
- Внешние формулы, ссылки, динамические подключения, макросы и запуск исполняемого содержимого не допускаются.

## 6. Канонический каталог метрик

Набор стартовых кодов:

**Баланс:** TOTAL_ASSETS, CURRENT_ASSETS, NON_CURRENT_ASSETS, CASH_EQUIVALENTS, RECEIVABLES, INVENTORIES, TOTAL_LIABILITIES, CURRENT_LIABILITIES, NON_CURRENT_LIABILITIES, TOTAL_EQUITY.

**Income:** REVENUE, COST_OF_SALES, GROSS_PROFIT, OPERATING_PROFIT, FINANCE_COSTS, PROFIT_BEFORE_TAX, INCOME_TAX, NET_PROFIT.

**Cash Flow:** CASH_FLOW_OPERATING, CASH_FLOW_INVESTING, CASH_FLOW_FINANCING, CASH_BEGINNING, CASH_ENDING, CASH_CAPEX (если раскрыт).

**Equity:** EQUITY_OPENING, TOTAL_COMPREHENSIVE_INCOME, OWNER_CONTRIBUTIONS, DIVIDENDS, EQUITY_CLOSING (с учётом корректировок/состава капитала).

Показатели с отличающейся семантикой, например debt vs total liabilities или EBITDA vs profit, **не должны** автоматически взаимозаменяться.

Версии MetricDefinition неизменяемы. Любое изменение алгоритма/смысла повышает версию и даёт диагностическое предупреждение для старых анализов.

## 7. Calculation / Strategy Engine

### 7.1. Безопасный AST формул

~~~json
{
  "code": "CURRENT_RATIO",
  "formulaVersion": "1.0.0",
  "operator": "DIV",
  "args": [
    { "metric": "CURRENT_ASSETS" },
    { "metric": "CURRENT_LIABILITIES" }
  ],
  "guards": ["DENOMINATOR_POSITIVE", "SAME_CONTEXT"]
}
~~~

Движок возвращает одно из: CALCULATED, NOT_COMPUTABLE, INVALID_INPUT, NOT_APPLICABLE. Никаких NaN, Infinity, тихой подстановки нуля или оценки неполных данных как подтверждённых.

Использовать topological sort для зависимостей, контроль циклов, контроль размерности (денежная сумма / сумма = ratio). Кэшировать по fingerprint(revision IDs + indicator versions + strategy parameters).

### 7.2. Типизированные правила

~~~json
{
  "code": "LOW_LIQUIDITY_SIGNAL",
  "strategyVersion": "1.0.0",
  "appliesTo": ["NON_FINANCIAL"],
  "conditions": {
    "all": [
      { "metric": "CURRENT_RATIO", "op": "LT", "threshold": "1.0" }
    ]
  },
  "severity": "REVIEW",
  "missingDataPolicy": "NOT_EVALUATED"
}
~~~

Порог условный, его интерпретация обязательно выводится пользователю. DSL поддерживает только явные сравнения/AND/OR/NOT и утверждённые метрики. Не выполняется произвольный код пользователя.

### 7.3. Explainability

Каждый AnalysisFinding связан с ruleCode/version, actual value, threshold, вычисленной метрикой, revisionId, provenance input cells и качеством данных. При изменении любой ревизии зависимые заключения считаются устаревшими и пересчитываются.

## 8. ChartSpec и ReportSnapshot

~~~typescript
interface ChartSpec {
  chartId: string;
  type: "line" | "bar" | "waterfall" | "treemap" |
        "sankey" | "heatmap" | "pie" | "table";
  metricCodes: string[];
  contexts: string[];
  title: string;
  unit: string;
  provenanceRefs: string[];
  warnings: string[];
}

interface ReportSnapshot {
  generatedAt: string;
  companyId: string;
  approvedRevisionIds: string[];
  formulaVersions: Record<string, string>;
  strategyCode: string;
  strategyVersion: string;
  sections: Array<{
    sectionId: string;
    title: string;
    chartIds: string[];
    findingIds: string[];
  }>;
}
~~~

ChartSpec — декларативный слой; ECharts adapter рендерит его интерактивно, PDF adapter — векторно или в растровом виде. Некорректные схемы, недостающие значения и несопоставимые контексты не должны порождать графики, вводящие в заблуждение.

Для Sankey нужны настоящие компоненты потоков с корректной суммой. Нельзя искусственно интерпретировать финансовую отчётность как трассировку денежных транзакций.

## 9. Сохранение без сервера

Проект экспортируется в .fin.json, импортируется через браузерный File API. Пользователь отвечает за хранение и резервные копии файла. При загрузке:
- schema/version validation и размерные лимиты;
- поддерживаемая миграция либо безопасный отказ;
- переоценка вычисленных значений по текущей (или выбранной закреплённой) версии методики;
- уведомление о расхождении версии аналитики.

Служебные настройки UI можно хранить локально, но автоматически сохранять финансовые отчёты в браузер без явного выбора пользователя не требуется. Если PWA закеширован, это не означает, что данные пользователя автоматически сохранены.

## 10. Безопасность / угрозы

- XLSX zip bomb / огромные shared strings / необоснованные размеры workbook → лимиты и отказ.
- JSON проекта с глубокими или циклическими структурами → ограничение размера и схем; JSON циклов не содержит, но глубину проверять.
- Formula injection при XLSX экспорте → все пользовательские строки, начинающиеся с =, +, -, @, экранируются/записываются как текст; числовые значения сериализуются отдельно.
- XSS из пользовательских названий/подписей → только безопасный рендеринг React, без unsafe HTML.
- CDN/analytics/exfiltration → self-hosted assets, CSP, запрет сетевой передачи финансовых данных в штатном режиме.
- Supply chain → lockfile, audit зависимостей, pinned CI, проверка обновлений.
- DoS на больших данных → Web Worker для тяжёлого импорта/анализа и контролируемые лимиты памяти.
- Информация в локальном .fin.json не шифруется; пользователь получает предупреждение при сохранении конфиденциального проекта.

## 11. Критерии независимости модулей

- Один и тот же набор MetricValues из XLSX и React Editor имеет идентичный domain fingerprint.
- Один и тот же AnalysisSnapshot визуализируется одинаково по значениям в Dashboard и экспортном отчёте.
- Модуль domain/ без браузера исполняется в Node.js тестах.
- Ревизии не изменяются после подтверждения; изменение источника создаёт новый revisionId.
- При несовместимой версии формы/стратегии показывается понятная ошибка, а не silent fallback.

## 12. Открытые решения для R0

1. Точный каталог 30–50 P0 метрик и трактовка IFRS/локальных форм.
2. Допуски бухгалтерских тождеств для масштабов UNITS/THOUSANDS/MILLIONS.
3. Ограничение на размер книги, количество лет, строк и файлов для тестируемого браузерного сценария.
4. Политика сохранения первичных PDF (только ссылка/метаданные или опциональная локальная упаковка).
5. Финальный механизм PDF-рендеринга после прототипа ECharts → печатный отчёт.

Эти вопросы закрываются тестируемыми ADR/фикстурами, а не скрытыми предположениями в коде.
