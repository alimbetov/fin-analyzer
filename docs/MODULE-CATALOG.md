# FIN Project Marketplace — единый каталог модулей и функциональные контракты

**Версия:** MODULE-CATALOG v1.0 · **Дата:** 2026-11-15 · **Статус:** SPECIFIED / NOT IMPLEMENTED

**Главное ТЗ:** [FIN Project Marketplace](FIN-ANALYZER-PROJECT-TZ.md). Архитектура: [ADR-008](ADR-008-FIN-CONNECT-BACKEND-STACK.md), доступ: [ADR-009](ADR-009-REPORT-ACCESS-ZONES.md), наименование: [ADR-010](ADR-010-PRIMARY-PRODUCT-NAME.md), ролевая карта: [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md).

**Отраслевые данные и экономические решения:** [59 DF-форм](FIN-BUSINESS-DATA-FORMS.md), [63 SF-формы](FIN-SECTOR-FORM-CATALOG.md), [сбор/агрегирование по периодам](FIN-SECTOR-METHODS-PERIODICITY.md). Эти реестры входят в существующие `FND-05 Form Registry`, `FND-08 Methodology` и `BUS-01…09 / PRJ-*`; **новые 122 микросервиса не создаются**.

## 1. Продуктовая модель

FIN Project Marketplace — единая международная B2B-платформа. **FIN Analyzer** — внутреннее аналитическое ядро, «Мой бизнес» — диагностика действующего бизнеса, «Мой проект» — стратегия/инвестиционная модель/Business Plan, Project Discovery — информационный каталог, FIN Connect — взаимодействие сторон, Report Center — отчёты и публикация, Access Center — адресные и временные разрешения.

**Стабильные ID ниже описывают логические bounded contexts, НЕ 39 микросервисов и НЕ 39 задач первого выпуска.** Одна компания может иметь несколько экономических проектов, а проект — несколько заинтересованных сторон с разными правами. Существующий код FIN-R1A реализует только ограниченный F01 Balance, остальные модули — требования.

**Запрещённые функции:** приём, хранение и перевод денег, торговля/клиринг, исполнение инвестиционных сделок и автоматическое банковское одобрение. Операции и договоры участников происходят вне FIN (ADR-007). Публикация инвестиционных возможностей требует правовой проверки в соответствующих юрисдикциях.

## 2. Все 39 модулей

### Платформенное ядро (12)

| Код | Название | Основной пользователь | Результат | Этап |
|---|---|---|---|---|
| FND-01 | **Identity & Sessions** | любой hosted пользователь | authenticated principal, server session | G3 |
| FND-02 | **Organizations & Membership** | компания, банк, фонд, консультант | проверенные membership и org context | G3 |
| FND-03 | **Access Center** | владелец, банк, инвестор, консультант | AccessGrant/Lease и журнал/отзыв | G3 |
| FND-04 | **Canonical Economic Facts** | все модули | approved fact revision, contextual snapshots | G1 |
| FND-05 | **Form Registry** | предприниматель, бухгалтер | FormDefinition/Instance/Revision | G1→G2 |
| FND-06 | **Adaptive Wizard** | новичок, CFO, консультант | WizardSession, next best question | G1 |
| FND-07 | **Input Adapters & Journal** | бухгалтер, владелец | staged imports, merge/reconciliation | G1→G2 |
| FND-08 | **Methodology & Country Registry** | экономист/аналитик | versioned methods + scope/coverage | G1→G4 |
| FND-09 | **Document Vault & Evidence** | владелец, банк, консультант | RustFS private revision + evidence record | G3 |
| FND-10 | **Report Center & Disclosure** | владелец, CFO, адресат | immutable report, private/invite/public output | G2→G3 |
| FND-11 | **Notifications & Outbox** | все роли | email status, durable outbox, inbox | G3 |
| FND-12 | **Collaboration & Approvals** | команда/консультант | work item and review history | G2→G3 |

### Мой бизнес и экономические направления (9)

| Код | Название | Основной пользователь | Результат | Этап |
|---|---|---|---|---|
| BUS-01 | **My Business Dashboard** | ИП/собственник/директор | business diagnostic overview | G1 |
| BUS-02 | **Financial Analysis** | бухгалтер, CFO, собственник | P&L/cash/ratio findings and limitations | G1→G2 |
| BUS-03 | **Sales & Pricing** | магазин/коммерческий директор | margin, profitability and trend | G1→G2 |
| BUS-04 | **Marketing Analytics** | маркетолог | CAC/CPA/ROAS and uncertainties | G2 |
| BUS-05 | **Procurement & Supply** | закупщик, логист | landed costs and stock indicators | G2 |
| BUS-06 | **Production & Operations** | производство, сервис, логистика | unit costs, bottleneck hypotheses | G2 |
| BUS-07 | **HR Economics** | HR/CFO | labor cost/productivity | G2→G4 |
| BUS-08 | **Tax & Regulatory Planning** | бухгалтер, консультант | tax planning assumptions and calendar | G4 |
| BUS-09 | **Sustainability & Resource Economics** | производственный менеджер | resource economics and savings scenarios | G4 |

### Мой проект и бизнес-планирование (8)

| Код | Название | Основной пользователь | Результат | Этап |
|---|---|---|---|---|
| PRJ-01 | **My Project Workspace** | основатель/CFO/директор | EconomicProject and EconomicCase | G1 |
| PRJ-02 | **Business Plan Builder** | основатель/консультант | versioned plan and audience-specific pack | G1→G2 |
| PRJ-03 | **Investment Scenario Lab** | CFO/основатель | NPV, IRR, payback, scenarios | G1→G2 |
| PRJ-04 | **Business Valuation** | фонд, собственник, CFO | EV/Equity valuation and range | G2 |
| PRJ-05 | **Strategic Planning** | директор/консультант | strategy roadmap, milestones and KPI | G1→G2 |
| PRJ-06 | **Risk & Stress Testing** | аналитик/директор | risk register and sensitivity | G2 |
| PRJ-07 | **Funding Navigator** | основатель/CFO | financing plan, package checklist | G2 |
| PRJ-08 | **Partnership & Franchising** | компания/партнёр | joint economic case and options | G2→G3 |

### FIN Connect и взаимодействие (7)

| Код | Название | Основной пользователь | Результат | Этап |
|---|---|---|---|---|
| CON-01 | **Project Discovery & Listing** | гость/инвестор/партнёр | discoverable public project listing | G3 |
| CON-02 | **Interest & Communication** | инвестор/банк/компания | InterestRequest and conversation | G3 |
| CON-03 | **Investor Workspace** | частный инвестор/фонд | project comparisons and pipeline | G3 |
| CON-04 | **Bank & Funding Workspace** | банк/лизинг/фонд | review queue, completeness report | G3 |
| CON-05 | **Consulting Company Workspace** | консалтинговая компания | MaterialRequests, evidence review and reports | G3 |
| CON-06 | **Data Room & Due Diligence** | владелец/адресаты | private evidence exchange and audit | G3 |
| CON-07 | **Project Execution Monitoring** | владелец/допущенный инвестор | plan/fact and investor update | G4 |

### Доверие и эксплуатация (3)

| Код | Название | Основной пользователь | Результат | Этап |
|---|---|---|---|---|
| ADM-01 | **Trust & Moderation** | модератор/представитель | moderation decision and appeal | G3 |
| ADM-02 | **Platform Administration** | администратор/DevOps | controlled rollout, recovery | G3 |
| ADM-03 | **Audit & Observability** | безопасность/QA/оператор | audit trail, p95/p99 and health | G3→G4 |

## 3. Контракт каждого модуля (акторы, ввод, выход, UI, приёмка)

### Платформенное ядро

**FND-01 — Identity & Sessions.** Актор: любой hosted пользователь. Ввод: OIDC, MFA, login/logout, Redis-сессии. Выход: authenticated principal, server session. Панель: «Вход и мои сессии». Этап: G3. **PASS:** после logout и отзыва old session не открывает private docs.

**FND-02 — Organizations & Membership.** Актор: компания, банк, фонд, консультант. Ввод: организация, представительство, сотрудники, multi-org. Выход: проверенные membership и org context. Панель: «Моя организация/Участники». Этап: G3. **PASS:** Org A не читает данные Org B и не наследует права.

**FND-03 — Access Center.** Актор: владелец, банк, инвестор, консультант. Ввод: Z00–Z99, поля, периоды, версии, expiry, действия. Выход: AccessGrant/Lease и журнал/отзыв. Панель: «Кому открыт доступ». Этап: G3. **PASS:** ZONE-01…ZONE-36; atomic one-use/revoke.

**FND-04 — Canonical Economic Facts.** Актор: все модули. Ввод: факт, тип, период, валюта, scope, source, quality. Выход: approved fact revision, contextual snapshots. Панель: «Данные и источники». Этап: G1. **PASS:** нет смешения cash/revenue, actual/forecast, missing/zero.

**FND-05 — Form Registry.** Актор: предприниматель, бухгалтер. Ввод: B/F/C/P/T/D формы, schema, applicability. Выход: FormDefinition/Instance/Revision. Панель: «Формы и проверки». Этап: G1→G2. **PASS:** типизированные поля и Excel/React parity.

**FND-06 — Adaptive Wizard.** Актор: новичок, CFO, консультант. Ввод: цель, отрасль, coverage, known/unknown facts. Выход: WizardSession, next best question. Панель: «Мастера и ввод». Этап: G1. **PASS:** ветвление, resume, no silent data loss.

**FND-07 — Input Adapters & Journal.** Актор: бухгалтер, владелец. Ввод: interview, grid, XLSX, CSV, bank/POS/1C, journal. Выход: staged imports, merge/reconciliation. Панель: «Импорт и журнал». Этап: G1→G2. **PASS:** malicious imports denied, no duplicates.

**FND-08 — Methodology & Country Registry.** Актор: экономист/аналитик. Ввод: формулы, OKED/ISIC/NACE/NAICS, jurisdictions, schools. Выход: versioned methods + scope/coverage. Панель: «Методики и отрасли». Этап: G1→G4. **PASS:** unsupported method never marked qualified.

**FND-09 — Document Vault & Evidence.** Актор: владелец, банк, консультант. Ввод: файлы, checksum, classification, metadata. Выход: RustFS private revision + evidence record. Панель: «Документы». Этап: G3. **PASS:** auth download, quarantine, no exposed S3 URLs.

**FND-10 — Report Center & Disclosure.** Актор: владелец, CFO, адресат. Ввод: facts, report type, revision, allowed projection. Выход: immutable report, private/invite/public output. Панель: «Отчётность/Публикации». Этап: G2→G3. **PASS:** public report is not project publication; export parity.

**FND-11 — Notifications & Outbox.** Актор: все роли. Ввод: приглашения, запросы, expiry, preferences. Выход: email status, durable outbox, inbox. Панель: «Уведомления». Этап: G3. **PASS:** KumoMTA retry/bounce, no notification loss.

**FND-12 — Collaboration & Approvals.** Актор: команда/консультант. Ввод: tasks, notes, comments, four-eyes approvals. Выход: work item and review history. Панель: «Задачи/Согласования». Этап: G2→G3. **PASS:** no unauthorized publish/edit from external consultant.

### Мой бизнес и экономические направления

**BUS-01 — My Business Dashboard.** Актор: ИП/собственник/директор. Ввод: business profile, actual KPIs, goals. Выход: business diagnostic overview. Панель: «Мой бизнес». Этап: G1. **PASS:** useful provisional result without accountant.

**BUS-02 — Financial Analysis.** Актор: бухгалтер, CFO, собственник. Ввод: F01–F04, cash, AR/AP, obligations. Выход: P&L/cash/ratio findings and limitations. Панель: «Финансы/Деньги». Этап: G1→G2. **PASS:** no fabricated EBITDA/DSCR or false totals.

**BUS-03 — Sales & Pricing.** Актор: магазин/коммерческий директор. Ввод: SKU, volumes, discounts, channels, returns. Выход: margin, profitability and trend. Панель: «Продажи». Этап: G1→G2. **PASS:** COGS and refunds defined; no double count.

**BUS-04 — Marketing Analytics.** Актор: маркетолог. Ввод: spend, leads, cohorts, attribution settings. Выход: CAC/CPA/ROAS and uncertainties. Панель: «Маркетинг». Этап: G2. **PASS:** attribution does not prove incremental revenue.

**BUS-05 — Procurement & Supply.** Актор: закупщик, логист. Ввод: suppliers, stock, delivered cost, lead time. Выход: landed costs and stock indicators. Панель: «Склад/Закупки». Этап: G2. **PASS:** purchase != COGS, consistent cost allocation.

**BUS-06 — Production & Operations.** Актор: производство, сервис, логистика. Ввод: BOM, time, output, scrap, capacity. Выход: unit costs, bottleneck hypotheses. Панель: «Операции/Производство». Этап: G2. **PASS:** WIP and shared cost checks; OEE only if applicable.

**BUS-07 — HR Economics.** Актор: HR/CFO. Ввод: headcount, payroll, work hours. Выход: labor cost/productivity. Панель: «Персонал». Этап: G2→G4. **PASS:** PII isolation and aggregate source provenance.

**BUS-08 — Tax & Regulatory Planning.** Актор: бухгалтер, консультант. Ввод: country, date, regime, verified rules. Выход: tax planning assumptions and calendar. Панель: «Налоговые сценарии». Этап: G4. **PASS:** no fictitious statutory compliance or declarations.

**BUS-09 — Sustainability & Resource Economics.** Актор: производственный менеджер. Ввод: energy, water, emissions/waste, costs. Выход: resource economics and savings scenarios. Панель: «Ресурсы». Этап: G4. **PASS:** consistent units and cited assumptions.

### Мой проект и бизнес-планирование

**PRJ-01 — My Project Workspace.** Актор: основатель/CFO/директор. Ввод: project type, linked historical snapshot, scenario. Выход: EconomicProject and EconomicCase. Панель: «Мой проект». Этап: G1. **PASS:** project forecast cannot alter historical facts.

**PRJ-02 — Business Plan Builder.** Актор: основатель/консультант. Ввод: company, market, CAPEX, operations, forecast, risks. Выход: versioned plan and audience-specific pack. Панель: «Бизнес-план». Этап: G1→G2. **PASS:** numbers linked to same snapshot, no invented facts.

**PRJ-03 — Investment Scenario Lab.** Актор: CFO/основатель. Ввод: project vs equity cashflows, CAPEX, discount rate. Выход: NPV, IRR, payback, scenarios. Панель: «Инвестиции/Сценарии». Этап: G1→G2. **PASS:** bad IRR invalid, flows/rates not mixed.

**PRJ-04 — Business Valuation.** Актор: фонд, собственник, CFO. Ввод: DCF, forecast, discount, comparable evidence. Выход: EV/Equity valuation and range. Панель: «Оценка бизнеса». Этап: G2. **PASS:** no invented market multiples.

**PRJ-05 — Strategic Planning.** Актор: директор/консультант. Ввод: SWOT/PEST evidence, priorities, alternatives. Выход: strategy roadmap, milestones and KPI. Панель: «Стратегия». Этап: G1→G2. **PASS:** unverified hypothesis distinct from observed fact.

**PRJ-06 — Risk & Stress Testing.** Актор: аналитик/директор. Ввод: risk drivers, time horizon, downside cases. Выход: risk register and sensitivity. Панель: «Риски». Этап: G2. **PASS:** transparent uncertainty, no fake rating.

**PRJ-07 — Funding Navigator.** Актор: основатель/CFO. Ввод: required funds, instrument, lender, source rules. Выход: financing plan, package checklist. Панель: «Финансирование». Этап: G2. **PASS:** stale grant/loan programs not claimed current.

**PRJ-08 — Partnership & Franchising.** Актор: компания/партнёр. Ввод: resources, roles, investments, royalty models. Выход: joint economic case and options. Панель: «Партнёрство». Этап: G2→G3. **PASS:** no automated agreement execution.

### FIN Connect и взаимодействие

**CON-01 — Project Discovery & Listing.** Актор: гость/инвестор/партнёр. Ввод: owner-approved teaser, region, activity, request kind. Выход: discoverable public project listing. Панель: «Каталог проектов». Этап: G3. **PASS:** listing cannot expose private full plan/report.

**CON-02 — Interest & Communication.** Актор: инвестор/банк/компания. Ввод: contact request, messages, anti-spam policy. Выход: InterestRequest and conversation. Панель: «Обращения». Этап: G3. **PASS:** interest != investment; privacy of messages.

**CON-03 — Investor Workspace.** Актор: частный инвестор/фонд. Ввод: watchlist, projects, DD and notes. Выход: project comparisons and pipeline. Панель: «Кабинет инвестора». Этап: G3. **PASS:** two funds' notes and projects isolated.

**CON-04 — Bank & Funding Workspace.** Актор: банк/лизинг/фонд. Ввод: addressed report package, requirements, analyst requests. Выход: review queue, completeness report. Панель: «Кабинет банка/фонда». Этап: G3. **PASS:** FIN never decides bank credit approval.

**CON-05 — Consulting Company Workspace.** Актор: консалтинговая компания. Ввод: accepted engagement, additional docs, analysts. Выход: MaterialRequests, evidence review and reports. Панель: «Кабинет консалтинга». Этап: G3. **PASS:** CONS-01…CONS-20 and tenant isolation.

**CON-06 — Data Room & Due Diligence.** Актор: владелец/адресаты. Ввод: restricted files, grant, version, contract expiry. Выход: private evidence exchange and audit. Панель: «Data Room». Этап: G3. **PASS:** all document reads enforce FND-03 and owner approval.

**CON-07 — Project Execution Monitoring.** Актор: владелец/допущенный инвестор. Ввод: frozen baseline, actual results, external finance evidence. Выход: plan/fact and investor update. Панель: «Мониторинг». Этап: G4. **PASS:** external event isn't internal money transaction.

### Доверие и эксплуатация

**ADM-01 — Trust & Moderation.** Актор: модератор/представитель. Ввод: complaint, scam indicators, verification status. Выход: moderation decision and appeal. Панель: «Trust Desk». Этап: G3. **PASS:** moderator does not access private finance by default.

**ADM-02 — Platform Administration.** Актор: администратор/DevOps. Ввод: feature flags, incidents, method releases, permissions. Выход: controlled rollout, recovery. Панель: «Admin». Этап: G3. **PASS:** admin is not global reader of financial documents.

**ADM-03 — Audit & Observability.** Актор: безопасность/QA/оператор. Ввод: domain/access events, latency, errors, deliveries. Выход: audit trail, p95/p99 and health. Панель: «Эксплуатация». Этап: G3→G4. **PASS:** no finance payload leakage in logs.

## 4. Формальная структура, данные и единый движок

\`\`\`text
Principal -- Keycloak Identity
  |-- OrganizationMembership -- Organization
  |                               +-- BusinessProfile
  |                               |    +-- BusinessActualSnapshot
  |                               |    +-- FinancialFact/SourceEvidence
  |                               |    +-- FinancialReportRevision
  |                               +-- EconomicProject (0..N)
  |                                    +-- EconomicCase / AssumptionSet
  |                                    +-- StrategyCase / RiskRegister
  |                                    +-- BusinessPlanCase / AnalysisSnapshot
  |                                    +-- ProjectListing / PublicTeaserSnapshot
  |                                    +-- ExecutionBaseline / MonitoringSnapshot
  +-- InterestRequest / Engagement / MaterialRequest
  +-- AccessRequest / AccessGrant / AccessLease
  +-- Message / NotificationIntent / AuditEvent
\`\`\`

**FinancialFact** обязан содержать code, decimal value, quantity unit/currency, period/as-of, entity/scope, stock-vs-flow, actual-vs-forecast, provenance/revision, method version and input state. \`PRESENT\`, \`EXPLICIT_ZERO\`, \`MISSING\`, \`NOT_APPLICABLE\`, \`ESTIMATED\` не смешиваются. Cash receipt ≠ accrued revenue; purchase ≠ COGS; loan inflow ≠ sales; owner contribution ≠ income; project forecast не изменяет действующую бухгалтерию; отдельная отчетность нескольких компаний не является консолидацией без корректировок.

**Граф зависимостей показателей:** Source Evidence → Canonical Fact → Validation → Formula & Methodology Registry → AnalysisSnapshot → Diagnostic Finding → NextBestQuestion / BusinessPlan / Report / Disclosure. \`OBSERVATION\` (вычисленный факт), \`HYPOTHESIS\` (возможное объяснение), \`NOT_COMPUTABLE\` и \`RECOMMENDATION_WITH_ASSUMPTIONS\` — различимые результаты. AI не исполняет финансовые формулы произвольно.

**Международность:** версионируемые классификаторы ОКЭД / ISIC / NACE / NAICS, стандарты учета, юрисдикция, валюта, язык/переводы и методические школы через MethodRegistry; выбор любого кода не означает готовую квалифицированную отраслевую модель. CountryPack и SectorPack получают версию, источники, effective period, fixtures и статус покрытия.

## 5. Реестр 11 мастеров

| Код | Мастер | Пользователь / цель | Ключевые шаги |
|---|---|---|---|
| WZ-01 | Профиль бизнеса | любой | стадия S0–S4, страна, отрасль, цель, период |
| WZ-02 | Business Diagnosis | предприниматель/директор | продажи/расходы/деньги → сверка → KPI → интерпретатор |
| WZ-03 | Project & Strategy | основатель | идея → стратегические альтернативы → план |
| WZ-04 | Business Plan | основатель/CFO | рынок → операции → CAPEX → P&L/CF → risks → план |
| WZ-05 | Investment Scenario | CFO/инвестор | project/equity cashflow → ставка → NPV/IRR/сценарии |
| WZ-06 | Report Publication | собственник/бухгалтер | отчет → validation → audience → redact → approve |
| WZ-07 | Project Publication | владелец проекта | teaser → allowlist → preview → approval → listing |
| WZ-08 | Access & Revoke | владелец/получатель | зона/период/версия → срок → one-time → grant/revoke |
| WZ-09 | Consulting Material Request | консалтинг/клиент | engagement → checklist → просьба → выдача/отказ |
| WZ-10 | Bank/Funding Package | бизнес/банк | заявка → scoped reports → комплектность → запрос |
| WZ-11 | Monitoring | владелец/спонсор | frozen baseline → факт → отклонения → обновление |

Для всех мастеров: «не знаю» ≠ ноль, «не применимо», объяснение поля, выбор табличного/опросного/XLSX ввода по модулю, сохранение, шаг назад, preview, обработка ошибок и история ревизий. Смена отрасли не удаляет факты молча. Состояния не смешиваются: публикация проекта не публикует отчёт, а опубликованный отчёт не открывает private документы.

## 6. Панели и межролевые сценарии

| Панель | Задача пользователя |
|---|---|
| Мой бизнес | KPI, продажи, cash, запасы, персонал, проблемы, задания |
| Мой проект | стратегия, план, инвестиционные альтернативы, риски, публикации |
| Команда | поручения, формы, review, согласование выдачи |
| Инвестор / фонд | каталог, watchlist, сравнение, запрос интереса, приватные заметки |
| Банк / лизинг / гранты | адресованные пакеты, запросы, checklist, внутренний review |
| Партнёр | ресурсы, совместные проекты, обсуждение вариантов |
| Консалтинговая компания | клиенты, Engagement, запросы документов сверх финансовых форм, заключения |
| Report Center | отдельные финансовые отчёты, ревизии, PUB/INVITE, отзыв |
| Access Center | Z00–Z99, recipient-specific grants, one-use, contract expiry, audit |
| Trust/Admin | верификация представительства, жалобы, методики, инциденты |

Основные E2E:
1. ИП → Interview → диагностика → объяснение проблем → следующий вопрос → отчет.
2. Действующий бизнес → факты → «Мой проект» → альтернативы роста → Business Plan.
3. Стартап → инвестмодель → investor-safe teaser → публикация → запрос контакта.
4. Банк → адресная финансовая отчетность → запрос расшифровок → ответ и отзыв.
5. Консалтинг → принятое сотрудничество → документы помимо F/C → частичная выдача → заключение.
6. Партнёр → проект → предложение оборудования/мощности/сбыта → joint scenario.
7. Инвестор → сравнение проектов → private notes → DD request → expiry.
8. Отчет опубликован → данные исправлены → новая версия; старый grant не охватывает новую.
9. Два получателя → разные зоны → независимые grant/revoke.
10. One-time download → два одновременных запроса → ровно один победитель.
11. Просроченный контракт/отозванный Engagement → отказ даже при действующем Keycloak session.
12. Мониторинг после внешнего соглашения → план-факт, без движения денег внутри FIN.
13. Незнакомая отрасль/страна → общий анализ по применимости без выдуманных отраслевых KPI.
14. Жалоба на проект → модерация и апелляция без доступа модератора к закрытой модели.

## 7. Серверные границы и интеграции

**React + TypeScript**, HTTPS same-origin → **Spring Boot BFF / MVC + JPA**. FIN PostgreSQL — system of record для Organization, Membership, Project, EconomicCase, Report, AccessGrant, Request, Outbox и Audit. Для Keycloak — отдельная PostgreSQL БД; **Keycloak** аутентифицирует, но не решает доступ к отдельным документам. **Redis** — сессии, rate limiting, временный кэш (не authoritative ACL); **RustFS** — приватные документы и их версии, скачивание только через Spring Boot; **KumoMTA** — emails с PostgreSQL transactional Outbox и обработкой bounce/deferral.

Backend предпочитает **модульный монолит**, а не независимые микросервисы на каждый код FND/BUS/PRJ/CON/ADM. API versioning/OpenAPI required; JPA entities не протекают в React DTO. Примерные ресурсы /api/organizations, /businesses, /projects, /economic-cases, /forms, /financial-facts, /reports, /access-grants, /documents, /engagements, /material-requests, /interest-requests; endpoints **не реализованы**.

Нужны optimistic revisions и PostgreSQL atomics для one-time grant, tenant-scoped queries для списков/поиска/download, выдержка p95/p99 с реальными backend dependencies, непрерывная проверка прав при выдаче приватных данных. Все события \`AccessGrantRevoked\`, \`ReportApproved\`, \`ProjectPublished\`, \`MaterialRequested\` связаны с actor, org, resource, revision и audit; retry уведомления не создаёт дубликат бизнес-действия.

## 8. Реальный порядок реализации

| Этап | Вертикальный результат, обязательные компоненты | Что не следует делать |
|---|---|---|
| G0 — закрыть F01 | независимый S2 + MacBook UAT текущего R1A, XLSX security | объявлять F01 готовым финансовым движком |
| G1 — экономическая ценность | FND-04/05/06/07(min)/08(min), BUS-01/02/03(min), PRJ-01/02/03(min), локальный save/reopen | ждать marketplace или Keycloak для полезного отчёта |
| G2 — улучшить качество решений | методики, сценарии, бизнес-план, локальный share preview, операционные/маркетинг/закупки, valuation/risk | реализовывать 44 формы сразу |
| G3 — защищённое взаимодействие | FND-01/02/03/09/10/11/12, CON-01…06, ADM-01/02/03(min), реальный PostgreSQL/Keycloak/Redis/RustFS/KumoMTA | принимать деньги или запускать торги |
| G4 — расширение и международность | CON-07, страновые и отраслевые пакеты, HR, налоговые сценарии, ресурсы, мониторинг | обещать поддержку всех экономических школ без методик |

**G1 acceptance:** новичок может пройти диагностику и создать ограниченный бизнес-план без бухгалтера; три фикстуры S0/S1/S2; формулы и источники воспроизводимы; нет ложных показателей; нет скрытой отправки данных. **G3 acceptance:** два клиента + консультант + инвестор/банк; post-revoke denial, private RustFS, outbox KumoMTA, 36 ZONE E2E и 20 CONS E2E; roles проверяются сервером.

## 9. Contract checklist для каждой реализации

Для moduleId до READY_FOR_IMPLEMENTATION обязателен owner/actor, JTBD, позитивный и негативный Use Case, inputs/outputs, data schema с версиями, validation/formula evidence, API/port contracts, permissions/tenant, audit/state transitions, UX error/empty/loading/unauthorized, persistence/import/export, rollback/revision, unit+integration+Playwright E2E, manual UAT, independent S2 для security/finance. Слова «SPECIFIED», «IMPLEMENTED», «TESTED», «UAT_PASS», «APPROVED» и «RELEASED» различаются.

**Вывод:** каталог закрывает полный функциональный периметр на уровне модулей; подробные user stories отдельных процессов сохраняются в специальных ТЗ. Это не подтверждение технической готовности или юридической применимости во всех странах.
