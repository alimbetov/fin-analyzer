# FIN Analyzer — «Мой бизнес»: продажи, диагностика и интерпретатор

**Версия:** MB-R0 v1.0 · 2026-11-15 · **Статус:** PLANNED / NOT IMPLEMENTED  
**Связано:** [Рабочие пространства](FIN-WORKSPACES-AND-JOURNEYS.md), [Финансовые отчёты](FIN-FINANCIAL-REPORT-PUBLICATION.md), [«Мой проект»](FIN-MY-PROJECT-STRATEGY.md).

## 1. Цель и панель

Отдельное рабочее пространство для анализа **действующего бизнеса** без необходимости создавать инвестиционный проект. Нужные формы выбираются по отрасли/ОКЭД и цели:
- Продажи: выручка (по начислению), заказы, возвраты, каналы, клиенты, цена, товары/SKU, концентрации.
- Расходы и себестоимость: фиксированные/переменные, COGS vs закупка, себестоимость производства, маржа, скидки.
- Денежные потоки: счета, реальные поступления/платежи, долги, сроки, cash gaps.
- Клиенты/маркетинг: расходы, лиды, CAC/ROAS/retention только при подходящих определениях.
- Снабжение/склад: закупки, остатки, списания, lead time, оборот.
- Персонал/производство: часы, зарплаты, мощность, выпускаемые единицы, потери.
- **Финансовые отчёты**: создать/review/согласовать/передать/добровольно опубликовать отчёт **отдельно от Marketplace проекта**.
- «Проблемы и действия»: наблюдаемые KPI, объяснения, вопросы, корректирующий план.

### Dashboard

Ключевые KPI и периоды → диагностические факты и предупреждения → "Что ещё нужно ввести" → назначенные действия и контроль → отчёты и версии. Нет фиктивного \`health score\`. Предоставление пользователем цифр не означает аудит/верификацию.

## 2. MB-WIZ

Экономический вопрос → профиль/отрасль/валюта/период → выбрать input mode по каждому блоку (Interview/таблица/XLSX/CSV/журнал) → preview/canonical fact merge → data validation → KPI → Rule Interpreter → next-best-question → Corrective Action → сохранить ReportSnapshot.

Каждый вопрос: единица, период, пояснение, «Не знаю», «Не применимо», **явный 0**, отличимая оценка (ESTIMATED), версионированный источник. Малому ИП не требовать полную профессиональную отчётность.

## 3. Интерпретатор экономических показателей

\`\`\`text
Canonical Facts + Context/Period/Source
 → Financial/Operational Metric Engine
 → Data Completeness / Reconciliation
 → DiagnosticRuleRegistry (versioned applicability)
 → Finding: Confirmed Observation | Hypothesis | Missing Evidence
 → Next Best Question / Action Proposal
 → Follow-up Indicator
\`\`\`

**Observed:** «Валовая маржа снизилась на 3 п.п. по двум сопоставимым периодам», если данные есть и методика известна.

**Hypothesis:** «Вероятной причиной могла стать закупочная цена или скидки» — это **не подтверждённая причина**; предложить данные о ценах/объёмах для различения. Нельзя выдавать рекламную кампанию как доказанную причину падения прибыли без надлежащего анализа.

Кейсы: MB-01 динамика продаж; MB-02 маржа; MB-03 profit vs cash; MB-04 AR/AP aging; MB-05 SKU contribution; MB-06 маркетинг с attribution caveat; MB-07 inventory turnover; MB-08 unit manufacturing cost; MB-09 workforce; MB-10 data coverage and missingness. Каждый — golden tests и negative scenarios.

## 4. Связь с проектами и публикацией отчётности

«Создать стратегический проект из проблемы бизнеса» → сохраняется версия \`BusinessActualSnapshot\` → новый \`EconomicProject\` ссылочно использует факты, но **forecast не пишет в actuals**. Для публичного отчёта нужен **отдельный report disclosure wizard**: [публикация отчётов](FIN-FINANCIAL-REPORT-PUBLICATION.md); нет автоматического раскрытия private facts.

Внешняя консалтинговая компания может попросить дополнительные документы сверх отчётности **только после явно подтверждённого engagement и grants**. См. [Консалтинг](FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md).

## 5. Acceptance

- Cash receipts ≠ revenue; inventory purchase ≠ COGS; loan principal ≠ income; owner funding ≠ sales.
- Unknown != zero, отсутствующая единица измерения/период → NOT_COMPUTABLE, не ложный риск.
- Formula trace, source, status, version и limitations доступны для любой интерпретации.
- Создание проекта не изменяет фактические отчёты и их ревизии.
- Публикация финансовых отчётов отдельна от Marketplace проекта и не требует всех 44 F/C/P форм.
