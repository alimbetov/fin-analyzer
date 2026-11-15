# FIN Project Marketplace — единая консолидированная спецификация требований (SRS v1.0)

**Дата:** 2026-11-15 · **Статус:** CONSOLIDATED FOR REVIEW / NOT IMPLEMENTED.
**Ветка:** `docs/fin-marketplace-requirements-baseline` · **База:** `e946aa36c8f7391362293c02aa0212e758f64c44`.
**Назначение:** сохранить все ранее согласованные требования в одном управляемом baseline с явными ссылками на подробные спецификации; не смешивать статус ТЗ с фактической программной реализацией.

## 1. Область продукта и принятые решения

**FIN Project Marketplace** — единая международная информационная B2B-платформа. **FIN Analyzer** — расчётный и интерпретационный модуль. «Мой бизнес» — фактическая диагностика, «Мой проект» — стратегии и инвестиционные планы, **Project Discovery** — каталог проектов, **FIN Connect** — взаимодействие участников, **Report Center** — создание и публикация отчётов, **Access Center** — выдача/отзыв разрешений.

**ADR precedence**: [ADR-010](ADR-010-PRIMARY-PRODUCT-NAME.md) — название; [ADR-007](ADR-007-FIN-PROJECT-MARKETPLACE.md) — постоянный запрет приёма/перевода/хранения денег, биржевых торгов и исполнения финансовых сделок; [ADR-008](ADR-008-FIN-CONNECT-BACKEND-STACK.md) — стек и границы; [ADR-009](ADR-009-REPORT-ACCESS-ZONES.md) — зоны, временные права и отзыв. Этот документ соединяет требования; подробные профильные ТЗ действуют по своей области, если не противоречат принятым ADR. Любое противоречие должно быть вынесено в журнал решений, а не исправлено незаметно.

**Уровень готовности:** в `feat/fin-r0-r1a` есть ограниченный React/F01 Balance прототип (Draft PR #2); в `main` — продуктовая документация. Ни Spring Boot/Keycloak/Redis/RustFS/KumoMTA интеграции, ни 39 модулей, ни реальный Marketplace не реализованы. Создание этого baseline не изменяет кодовую ветку или `main`.

## 2. Основные требования (REQ-CORE)

- **CORE-01:** пользователь от S0 стартапа до S4 группы компаний получает соответствующий цели анализ без обязательного заполнения всех форм.
- **CORE-02:** один EconomicCase/CANONICAL financial core для всех способов ввода и аналитических направлений; входы имеют provenance, currency, quantity unit, period, actual/forecast, state и revision.
- **CORE-03:** неизвестное ≠ нулевое; продажи ≠ деньги; закупка запасов ≠ себестоимость реализации; вклад/кредит ≠ выручка; прогноз ≠ факт; консолидация требует исключения внутригрупповых оборотов.
- **CORE-04:** каждый показатель вычисляется детерминированным версионированным методом, имеет formula trace, assumptions, источник и статус NOT_COMPUTABLE при нехватке данных.
- **CORE-05:** «Мой бизнес» диагностирует продажи, прибыльность, ликвидность, долги, закупки, производство, персонал и внутренние проблемы; observation отделяется от hypothesis.
- **CORE-06:** «Мой проект» строит стратегические альтернативы, прогнозы, NPV/IRR/payback при валидных входах, сценарии рисков, инвестиционные и бизнес-планы для конкретной аудитории.
- **CORE-07:** отраслевой ОКЭД/ISIC/NACE/NAICS и международные методики со школами/теоретическими линзами — версионируемые registry definitions; не утверждать готовность любой отраслевой модели без golden fixture и review.
- **CORE-08:** investor teaser, published project, published financial report, invite-only report и документ — **разные** snapshot/resource/grant, их публикация не раскрывает друг друга автоматически.
- **CORE-09:** компания может приглашать консалтинговую организацию с собственной командой, принимать или отклонять её запросы документов и передавать только выбранные версии в пределах принятого Engagement.
- **CORE-10:** банк, фонд, частный инвестор и партнёр получают исключительно разрешённые проекции; FIN не даёт кредитного одобрения и не гарантирует инвестиционной доходности.
- **CORE-11:** Keycloak authenticates, FIN Spring Boot BFF authorizes; право на конкретную зону, документ, версию, поле и период проверяется по FIN PostgreSQL после любой revocation.
- **CORE-12:** размещение проекта, привлечение интереса, переписка и обмен данными не создают финансового договора или денежной операции внутри FIN.
- **CORE-13:** не утверждать, что данные официально аудированы, что налоговые требования актуальны или что банк одобрил финансирование, без соответствующего независимого основания.
- **CORE-14:** локальный FIN Core приносит пользу без обязательной регистрации; hosted-многопользовательские сценарии требуют защищённого backend и отдельной приёмки.

- **CORE-15:** FIN Business Health является адаптивным путём «Мой бизнес» для ИП/малого бизнеса с Express/Full диагностикой, отраслевым профилем, планом корректирующих действий и отдельными сценариями; не вводится 40-й модуль.
- **CORE-16:** единый MetricDefinition/Methodology Registry содержит стабильный metricCode, версию метода, входы, единицы, формулу, применимость, источники и перевод ru/kk/en для полей «название», «что считает», «как считает», «что показывает»; изменение локали не влияет на численный результат.
- **CORE-17:** диагностические выводы строго различают OBSERVATION, HYPOTHESIS и MISSING_EVIDENCE; неопределённые пороги и универсальный Business Health score запрещены без проверенной отраслевой методики; отсутствие данных не заменяется нулём.
- **CORE-18:** личные изъятия владельца отдельно от расходов P&L; версия сценария никогда не перезаписывает факт; все финансовые факты Business Health приватны до отдельного разрешения, gated access следует ADR-009.

**Детализация:** [FIN Business Health](FIN-BUSINESS-HEALTH.md) и [FIN Indicator Dictionary ru/kk/en](FIN-INDICATOR-DICTIONARY-RU-KK-EN.md). Статус — SPECIFIED, NOT IMPLEMENTED. Реестр KPI расширяет FND-08 и BUS-01/BUS-02; количество модулей и форм не меняется.

## 3. Модульная карта: 39 логических модулей

Модули — **bounded contexts**, не 39 микросервисов. Все business data используются по утверждённым domain contract и access rules.

| Module ID | Module | Actor | Result | Gate |
|---|---|---|---|---|
| FND-01 | Identity & Sessions | любой hosted пользователь | authenticated principal, server session | G3 |
| FND-02 | Organizations & Membership | компания, банк, фонд, консультант | проверенные membership и org context | G3 |
| FND-03 | Access Center | владелец, банк, инвестор, консультант | AccessGrant/Lease и журнал/отзыв | G3 |
| FND-04 | Canonical Economic Facts | все модули | approved fact revision, contextual snapshots | G1 |
| FND-05 | Form Registry | предприниматель, бухгалтер | FormDefinition/Instance/Revision | G1→G2 |
| FND-06 | Adaptive Wizard | новичок, CFO, консультант | WizardSession, next best question | G1 |
| FND-07 | Input Adapters & Journal | бухгалтер, владелец | staged imports, merge/reconciliation | G1→G2 |
| FND-08 | Methodology & Country Registry | экономист/аналитик | versioned methods + scope/coverage | G1→G4 |
| FND-09 | Document Vault & Evidence | владелец, банк, консультант | RustFS private revision + evidence record | G3 |
| FND-10 | Report Center & Disclosure | владелец, CFO, адресат | immutable report, private/invite/public output | G2→G3 |
| FND-11 | Notifications & Outbox | все роли | email status, durable outbox, inbox | G3 |
| FND-12 | Collaboration & Approvals | команда/консультант | work item and review history | G2→G3 |
| BUS-01 | My Business Dashboard | ИП/собственник/директор | business diagnostic overview | G1 |
| BUS-02 | Financial Analysis | бухгалтер, CFO, собственник | P&L/cash/ratio findings and limitations | G1→G2 |
| BUS-03 | Sales & Pricing | магазин/коммерческий директор | margin, profitability and trend | G1→G2 |
| BUS-04 | Marketing Analytics | маркетолог | CAC/CPA/ROAS and uncertainties | G2 |
| BUS-05 | Procurement & Supply | закупщик, логист | landed costs and stock indicators | G2 |
| BUS-06 | Production & Operations | производство, сервис, логистика | unit costs, bottleneck hypotheses | G2 |
| BUS-07 | HR Economics | HR/CFO | labor cost/productivity | G2→G4 |
| BUS-08 | Tax & Regulatory Planning | бухгалтер, консультант | tax planning assumptions and calendar | G4 |
| BUS-09 | Sustainability & Resource Economics | производственный менеджер | resource economics and savings scenarios | G4 |
| PRJ-01 | My Project Workspace | основатель/CFO/директор | EconomicProject and EconomicCase | G1 |
| PRJ-02 | Business Plan Builder | основатель/консультант | versioned plan and audience-specific pack | G1→G2 |
| PRJ-03 | Investment Scenario Lab | CFO/основатель | NPV, IRR, payback, scenarios | G1→G2 |
| PRJ-04 | Business Valuation | фонд, собственник, CFO | EV/Equity valuation and range | G2 |
| PRJ-05 | Strategic Planning | директор/консультант | strategy roadmap, milestones and KPI | G1→G2 |
| PRJ-06 | Risk & Stress Testing | аналитик/директор | risk register and sensitivity | G2 |
| PRJ-07 | Funding Navigator | основатель/CFO | financing plan, package checklist | G2 |
| PRJ-08 | Partnership & Franchising | компания/партнёр | joint economic case and options | G2→G3 |
| CON-01 | Project Discovery & Listing | гость/инвестор/партнёр | discoverable public project listing | G3 |
| CON-02 | Interest & Communication | инвестор/банк/компания | InterestRequest and conversation | G3 |
| CON-03 | Investor Workspace | частный инвестор/фонд | project comparisons and pipeline | G3 |
| CON-04 | Bank & Funding Workspace | банк/лизинг/фонд | review queue, completeness report | G3 |
| CON-05 | Consulting Company Workspace | консалтинговая компания | MaterialRequests, evidence review and reports | G3 |
| CON-06 | Data Room & Due Diligence | владелец/адресаты | private evidence exchange and audit | G3 |
| CON-07 | Project Execution Monitoring | владелец/допущенный инвестор | plan/fact and investor update | G4 |
| ADM-01 | Trust & Moderation | модератор/представитель | moderation decision and appeal | G3 |
| ADM-02 | Platform Administration | администратор/DevOps | controlled rollout, recovery | G3 |
| ADM-03 | Audit & Observability | безопасность/QA/оператор | audit trail, p95/p99 and health | G3→G4 |

Полный контракт по входам, UI, данным и негативным тестам: [MODULE-CATALOG.md](MODULE-CATALOG.md). Группы: FND=12, BUS=9, PRJ=8, CON=7, ADM=3.

## 4. Роли: 20 основных ролей и консалтинговая организация

| Role | Участник | Job to be done | Информация | Phase |
|---|---|---|---|---|
| R01 PROJECT_FOUNDER | предприниматель/автор идеи | от идеи к расчетной модели и бизнес-плану | private assumptions, plan | P0 |
| R02 BUSINESS_OWNER | владелец ИП/ТОО | диагностика, бюджет, проект развития, раскрытие инвестору | полные данные собственного бизнеса | P0 |
| R03 COMPANY_MANAGER | директор/операционный менеджер | KPI, меры улучшения, проекты, план-факт | делегированные компании данные | P0 / hosted P2 |
| R04 ACCOUNTANT | бухгалтер/финконтролёр | F/C формы, сверки, версии, качество данных | финансовые документы | P0 / hosted P2 |
| R05 FINANCIAL_ANALYST | CFO / финансовый аналитик | сценарии, инвестиционная модель, valuation | финансовая модель и методики | P0 |
| R06 MARKETING_MANAGER | маркетолог/директор продаж | маркетинговые каналы, CAC, воронки, ценовые решения | сегменты и коммерческие данные | P1 |
| R07 OPERATIONS_MANAGER | производство, логистика, закупки | себестоимость, мощности, KPI, ресурсы | производственные и контрактные данные | P1 |
| R08 EXTERNAL_ADVISOR | консультант/бухгалтерская фирма | структурировать модель клиента, объяснить заключения | только переданные клиентом поля | local export P1 / hosted P2 |
| R09 PRIVATE_INVESTOR | частный инвестор | найти, сравнить и проверить проекты | только shared snapshots | P2 |
| R10 INSTITUTIONAL_INVESTOR | фонд / PE / VC | pipeline, инвестиционный комитет, portfolio monitoring | DD пакеты по разрешению | P2 |
| R11 BANK_ANALYST | банк, кредитный аналитик | комплектность, модель, кредитный предварительный анализ | заявка и разрешённые материалы | P2 |
| R12 BANK_RISK_APPROVER | банковский риск-менеджер/комитет | независимое рассмотрение, заключение, decision record | закрытые материалы банка | P2 / separate bank policies |
| R13 FUNDING_PROVIDER | лизинговая, грантовая организация/фонд | проверка соответствия программе/объекту | данные адресованной заявки | P2 |
| R14 STRATEGIC_PARTNER | компания, франчайзи, поставщик ресурсов | поиск партнёра, оценка совместной экономики | только совместно разрешённые данные | P2 |
| R15 EXECUTION_SPONSOR | инвестор/спонсор мониторинга | наблюдать milestones, факт, отклонения | мониторинговый agreed scope | P2 |
| R16 ORG_ADMIN | уполномоченный администратор организации | участники, полномочия, проекты, доступ | метаданные организации | P2 |
| R17 COMPLIANCE_MODERATOR | модератор платформы, compliance | проверка объявлений, жалоб, мошенничества, меры | минимально необходимые данные, audited access | P2 |
| R18 PLATFORM_ADMIN | оператор FIN | конфигурация, incident response, эксплуатация | нет свободного доступа к финансовым данным | P2 |
| R19 METHODOLOGY_EDITOR | экономист/методолог отраслевых моделей | обновление методик, golden tests, country packs | публичные модели, тестовые фикстуры | internal P1 |
| R20 INDEPENDENT_REVIEWER | независимый S2/auditor/quality reviewer | оценка качества расчётов/методологии и доказательств | только выбранные для ревью материалы | P1 |

**ConsultingOrganization** — организация с собственными `CONSULT_ORG_ADMIN/ENGAGEMENT_LEAD/CONSULT_ANALYST/CONSULT_REVIEWER`, а не право R08 на все проекты. Один пользователь может состоять в нескольких организациях; активный tenant не расширяет права. Детальные положительные/отрицательные истории: [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md), [CONSULTING](FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md).

## 5. Каталог форм: 44 объектов, запрашиваемых по применимости

| Code | Name | Type | Content |
|---|---|---|---|
| F01 | Бухгалтерский баланс | STRUCTURED | Активы, обязательства, капитал; дата и basis |
| F02 | Отчёт о прибылях и убытках | STRUCTURED | Выручка, себестоимость, операционная и чистая прибыль; интервал |
| F03 | Отчёт о движении денежных средств | STRUCTURED | CFO/CFI/CFF, остатки денежных средств; метод и интервал |
| F04 | Отчёт об изменениях капитала | STRUCTURED | Входящий/исходящий капитал, взносы, дивиденды, корректировки |
| F05 | Примечания и пояснения к отчётности | COMPOSITE | Раскрытия, ссылки на статьи F01–F04 и подтверждающие документы |
| F06 | Оборотно-сальдовая ведомость (ОСВ) | STRUCTURED | Счёт, открытие, дебет/кредит обороты, закрытие, период, план счетов |
| F07 | Управленческая отчётность | STRUCTURED | Пользовательские статьи, mapping к canonical metrics, управленческие корректировки |
| F08 | Консолидированная отчётность | COMPOSITE | Контур группы, basis=CONSOLIDATED, компании и элиминации; F01–F04 повторно не хранить |
| C01 | Дебиторская задолженность | STRUCTURED | Контрагент, сумма, валюта, дата возникновения/погашения, просрочка/aging, резерв, связанная сторона |
| C02 | Кредиторская задолженность | STRUCTURED | Контрагент, сумма, срок, просрочка/aging, категория долга |
| C03 | Товарно-материальные запасы | STRUCTURED | Группа, количество/единица, стоимость, обесценение, неликвиды, дата |
| C04 | Основные средства | STRUCTURED | Объект/группа, первоначальная и балансовая стоимость, амортизация, обременение |
| C05 | Действующие кредиты и займы | STRUCTURED | Кредитор, договор, валюта, остаток, ставка, тип ставки, срок, график, просрочка, обеспечение |
| C06 | Обороты по банковским счетам | STRUCTURED | Счёт/банк, месяц, валюта, поступления, списания, внутренние переводы, исключения |
| C07 | Выручка по месяцам | STRUCTURED | Месяц, направление/клиент, сумма, возвраты, валюта |
| C08 | Себестоимость | STRUCTURED | Статья затрат, период, постоянная/переменная часть, сумма |
| C09 | Операционные расходы | STRUCTURED | Статья, период, центр затрат, сумма |
| C10 | Налоги и задолженность бюджету | STRUCTURED | Вид налога, период, начислено/уплачено/остаток/просрочка |
| C11 | Выданные и полученные авансы | STRUCTURED | Контрагент, направление, сумма, дата, основание, исполнение |
| C12 | Крупнейшие покупатели и поставщики | STRUCTURED | Контрагент, доля оборота, период, зависимость, условия |
| C13 | Забалансовые обязательства | STRUCTURED | Вид гарантии/поручительства/лизинга/условного обязательства, сумма, срок, вероятность, обеспечение |
| C14 | Связанные стороны и внутригрупповые операции | STRUCTURED | Стороны, тип связи, операции, остаток, элиминации/подтверждения |
| P01 | Прогнозный баланс | STRUCTURED | Активы, обязательства, капитал на прогнозные даты |
| P02 | Прогнозный ОПиУ | STRUCTURED | Выручка, себестоимость, расходы, прибыль по периодам |
| P03 | Прогноз движения денежных средств | STRUCTURED | Притоки/оттоки, открытие/закрытие денежных средств, финансирование |
| P04 | График обслуживания долга | STRUCTURED | Кредит, выборка, проценты, комиссия, тело долга, остаток, дата |
| P05 | CAPEX-план | STRUCTURED | Статья, срок платежа, сумма, финансирование, ввод в эксплуатацию |
| P06 | Потребность в оборотном капитале | STRUCTURED | DSO/DPO/DIO или детальный план дебиторки/кредиторки/запасов |
| P07 | Финансовая модель проекта | COMPOSITE | Сценарий, инвестиции, операционные потоки, NPV/IRR при заданных assumptions |
| P08 | Стресс-сценарии | STRUCTURED | Шоки выручки, расходов, курса, ставки; baseline/optimistic/stress |
| P09 | Прогноз DSCR | COMPOSITE | CFADS, debt service, формула, период, версия методики и исключения |
| P10 | Источники и использование кредита | STRUCTURED | Источник, сумма, валюта, срок, назначение, подтверждающий документ |
| T01 | ФНО 100.00, декларация по КПН | EXTERNAL/TAX: оригинал, период, статус, вручную подтверждённые сопоставляемые показатели |  |
| T02 | ФНО 200.00, ИПН и социальный налог | EXTERNAL/TAX: оригинал и выборочные агрегированные показатели |  |
| T03 | ФНО 300.00, НДС | EXTERNAL/TAX: оригинал, период, подтверждаемые показатели оборота/НДС |  |
| T04 | ФНО 910.00, упрощённая декларация | EXTERNAL/TAX: оригинал и подтверждённые показатели при применимом налоговом режиме |  |
| D01 | Анкета заёмщика и заявка | DOCUMENT | Версия анкеты конкретного банка, дата, статус |
| D02 | Собственники, бенефициары, структура группы | DOCUMENT | Комплектность, актуальность, доступ |
| D03 | Бизнес-план / ТЭО | DOCUMENT | Назначение кредита, дата, привязка к P07/P10 |
| D04 | Реестр и документы залогов | DOCUMENT | Тип объекта, собственник, оценка, дата оценки, обременения |
| D05 | Договоры с покупателями и поставщиками | DOCUMENT | Контрагент, сумма, срок, действительность |
| D06 | Банковские справки / сведения о долгах | DOCUMENT | Банк, дата выдачи, срок актуальности, C05/C06 |
| D07 | Корпоративное решение о кредитовании | DOCUMENT | Орган, дата, полномочия, применимость |
| D08 | Аудиторское заключение | DOCUMENT | Период, аудитор, тип мнения, применимость |

**8 F** финансовых форм, **14 C** расшифровок, **10 P** прогнозов, **4 T** внешних налоговых форм KZ, **8 D** документов кредитного досье. Не использовать их как единый обязательный checklist. Налоговые формы не являются официальным электронным filing-сервисом. Источник: [FORM-CATALOG.md](FORM-CATALOG.md).

## 6. Экономические направления: 12 целевых дополнений

| Legacy ID | Направление | Inputs | Analysis | Output |
|---|---|---|---|---|
| FUND | Funding & Capital Raising | потребность, назначение, сроки, собственный вклад, финансовая история, обеспечение, ограничения | потребность в капитале, варианты долгового/долевого финансирования, долговая нагрузка, сравнение условий при наличии подтверждённых данных | Financing Plan, список возможных механизмов, checklist документов |
| MARKET | FIN Project Marketplace | одобренная владельцем карточка, ОКЭД, регион, стадия, сумма, цели, публичные факты, контакт | поиск и фильтрация опубликованных проектов, запрос интереса и контроля доступа; не обещание доходности | Investment Teaser, Project Listing, Interest Request |
| VAL | Business Valuation | бизнес-отчётность, прогноз денежных потоков, долг, денежные средства, ставки, сопоставимые сделки (при проверяемом источнике) | DCF, диапазоны, чувствительность, стоимость equity vs enterprise, разводнение долей | Valuation Report с допущениями и ограничениями |
| SALES | Sales & Commercial Analytics | SKU/услуги, продажи, цены, скидки, возвраты, клиентские сегменты, каналы и договоры | маржа, динамика, продуктовая и контрактная прибыльность, ценовые и скидочные сценарии | Sales Dashboard / Pricing Analysis |
| SUP | Procurement & Supply Chain | поставщики, закупки, остатки, сроки поставки, логистика, потери, валюты | стоимость закупки с доставкой, оборачиваемость, lead time, зависимость от поставщика, доступные альтернативы | Supply Chain & Inventory Diagnostics |
| HR | HR & Workforce Economics | штат, ФОТ, отработанные часы, производительность, вакансии, подразделения | затраты на персонал, стоимость часа, выработка, сценарий расширения штата | Workforce Economics Report |
| TAX | Tax & Regulatory Planning | юрисдикция, дата, налоговый режим, ОКЭД, факты налоговой базы и плановые платежи | календарь обязательств, сценарное влияние налоговых допущений при утверждённой методике | Tax Calendar / Scenario Report, не налоговая декларация |
| STRAT | Strategic Planning | цели, стратегические альтернативы, инвестиции, ограничения, внутренние факторы, данные рынка | сравнение сценариев, финансовая оценка филиала/продукта/рынка, SWOT как структурированные гипотезы | Strategy Options & Roadmap |
| RISK | Risk Management | риск-события, допущения, волатильность, концентрации, последствия, меры контроля | стресс-тест, сценарная чувствительность, риск-реестр с указанием субъективности оценок | Risk Register & Stress Report |
| PARTNER | Partnerships & Franchising | модель партнёрства, взносы, royalties, распределение затрат/результатов, обязательства | unit economics франшизы/совместного проекта, сравнение условий при достаточных данных | Partnership Case / Franchise Economics |
| EXEC | Project Execution & Monitoring | baseline проекта, CAPEX/OPEX бюджет, факт, milestone, сроки, KPI, подтверждения | отклонения план/факт, освоение капитала, сроки, cash needs, анализ отклонений | Execution Dashboard / Investor Update |
| ESG | Sustainability & Resource Economics | энергия, вода, отходы, выпуск, тарифы, вложения в эффективность | стоимость ресурсов на единицу, экономический эффект энергосбережения, ресурсные сценарии | Resource Efficiency Report |

**Терминология:** legacy `MARKET` означает конкретный каталог **Project Discovery / CON-01**, не всю платформу FIN Project Marketplace. FIN/Investment/Marketing/Production/Business Plan — базовые аналитические области сверх этих 12. [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md).

## 7. Мастера: 11 end-to-end инструментов

| Wizard | Title | Actor | Happy path |
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

Укрупнённые UI-маршруты MB-WIZ, PR-WIZ, PUB-WIZ, REP-WIZ и CONS-REQ — лишь группировка шагов; 11 WZ являются общим каталогом. Дополнительно Access wizard = WZ-08. В каждом мастере: конкретная цель, профиль, вопрос/форма, unknown/zero/not applicable, previous/resume, review, version, error handling, approval when sharing, traceable output. Источник: [FIN-WORKSPACES-AND-JOURNEYS.md](FIN-WORKSPACES-AND-JOURNEYS.md).

## 8. Зоны финансовой отчётности: 12, scope и срок

| Zone | Meaning | Examples | Default |
|---|---|---|---|
| Z00_PUBLIC | Добровольно опубликованная проекция | public teaser, public report summary | только explicit PUBLIC |
| Z10_FIN_SUMMARY | Агрегированные показатели | выручка, прибыль, маржа | PRIVATE |
| Z20_FIN_STATEMENTS | Финансовая отчётность | F01–F08, баланс, P&L, CF | PRIVATE |
| Z30_FIN_DETAILS | Детализация и расшифровки | C01–C14, AR/AP, кредиты, налоги | RESTRICTED |
| Z40_SALES_MARKETING | Продажи/маркетинг | SKU, клиенты, каналы, договорная маржа | RESTRICTED |
| Z50_OPERATIONS | Производство и процессы | сырьё, BOM, себестоимость, мощность | RESTRICTED |
| Z60_STRATEGY | Стратегия и проекты | CAPEX, прогнозы, бизнес-планы | RESTRICTED |
| Z70_LEGAL | Юридические/контрактные | договоры, права, лицензии | HIGHLY_RESTRICTED |
| Z80_HR_BANK | Кадровые/банковские | персональные выплаты, выписки, счета | HIGHLY_RESTRICTED |
| Z90_CONSULT | Дополнительные запросы консультанта | материалы по принятому Engagement | PRIVATE/RESTRICTED |
| Z95_NOTES | Внутренние заметки | внутренние выводы банка, фонда, консалтинга | OWNER_ORG_ONLY |
| Z99_SYSTEM | Секреты системы | пароли, токены, ключи, конфигурация | NEVER_SHARE |

AccessGrant содержит: owner organization, recipient organization/named user, reason, resourceId, immutable revision, sections/metrics/fields/period, action, validFrom/expiresAt, engagement/contract scope, approval and audit. VIEW ≠ DOWNLOAD; старый grant не даёт новую версию. FIXED_WINDOW, ENGAGEMENT_BOUND, CONTRACT_WINDOW, UNTIL_REVOKED, SINGLE_USE_VIEW/DOWNLOAD, LIMITED_USES, MANUAL_APPROVAL — разные модели выдачи. Конкурентное расходование one-use обеспечивается **PostgreSQL atomics/lease**, не Redis. Emergency revoke блокирует будущие запросы, но не может удалить ранее скачанное или запретить снимок экрана. 36 ZONE тестов в [ADR-009](ADR-009-REPORT-ACCESS-ZONES.md).

## 9. Процессы (сквозная приемка)

| Flow | Участники | Польза | Обязательный негативный сценарий |
|---|---|---|---|
| UC-01 | S1 ИП → FIN Analyzer | экспресс-диагностика, дефицит данных, план улучшения | нельзя считать missing нулём |
| UC-02 | Компания → CFO → «Мой проект» | стратегические сценарии A/B/do-nothing | forecast не правит actual |
| UC-03 | Стартап → бизнес-план → инвестор | версия плана и безопасный teaser | закрытая модель не раскрыта |
| UC-04 | Компания → банк/фонд | адресный отчёт, дополнительные документы | нет чужих заявок/внутренних заметок |
| UC-05 | Консалтинг → компания | принятое сотрудничество, запрос дополнительных материалов, частичная выдача | без engagement/grant — DENY |
| UC-06 | Компания → партнёр | совместный проект, предложение ресурсов и расчёт альтернатив | контракты/деньги вне FIN |
| UC-07 | Инвестор → Project Discovery | сравнение и интерес к проекту | InterestRequest ≠ обязательство |
| UC-08 | Компания → Report Center | публикация отчёта отдельно от проекта | confidential facts скрыты |
| UC-09 | Владелец → Access Center | доступ инвестору на 48 ч/один просмотр, отзыв | stale JWT, Redis, parallel lease denied |
| UC-10 | Фонд → due diligence | внутренние заметки и разрешённые документы | фонд B не видит работу фонда A |
| UC-11 | Компания → мониторинг | план-факт после внешнего соглашения | FIN не исполняет сделку |
| UC-12 | Модератор → жалоба | проверка публичного проекта | нет полного доступа к частным финансам |

Нормативные сценарии: [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md), [Consultant](FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md), [Reports](FIN-FINANCIAL-REPORT-PUBLICATION.md).

## 10. Инфраструктура и источник истины

**G1/G2 (local Core):** React/Vite/TypeScript, controlled XLSX, локальный .fin.json save/reopen и reproducible analytical core; без обязательного аккаунта и без скрытой облачной отправки данных. **G3/G4 (hosted Connect):** React → HTTPS/BFF Spring Boot (Spring MVC, JPA) → FIN PostgreSQL как источник истины для organizations, memberships, projects, grants, audit, outbox, reports. **Keycloak:** OIDC и отдельная PostgreSQL DB; экран входа через контролируемый reverse proxy, access/refresh tokens серверные. **Redis:** серверные сессии/временный cache. **RustFS:** private object storage, quarantine и backend streaming без прямых браузерных S3 URL. **KumoMTA:** email только через PostgreSQL transactional Outbox и idempotent worker, доставка email не равна SMTP-accepted.

Для каждого private LIST/SEARCH/GET/EXPORT/DOWNLOAD backend повторно проверяет tenancy, organization membership, scope, version и status. One-time grants и revoke защищаются от гонок и stale sessions. Do not invent URLs/endpoints; OpenAPI и контрактные тесты перед кодом.

## 11. Релизы и Definition of Done

| Gate | Ценность | Proof | Status |
|---|---|---|---|
| G0 | F01 Balance baseline | independent S2, ручной MacBook UAT, XLSX hardening | OPEN |
| G1 | S0/S1/S2 → Smart Interview → diagnosis → minimal Business Plan | golden fixtures, Vitest/Playwright, save/reopen | PLANNED |
| G2 | method registry, scenarios, Business Plan, safe teaser/report export | one canonical scenario across all outputs; redaction tests | PLANNED |
| G3 | org/identity, access, investor/bank/consultant hosted flows | real Keycloak/PostgreSQL/Redis/RustFS/KumoMTA, 36 ZONE tests, 20 CONS, tenant E2E | PLANNED |
| G4 | мониторинг, новые страны и отрасли, продвинутая аналитика | versioned country/sector methods and user UAT | PLANNED |

`SPECIFIED`, `IMPLEMENTED`, `TESTED`, `UAT_PASS`, `S2_APPROVED`, `RELEASED` — разные статусы. Документный review не доказывает работающий код. Независимый S2 должен быть на конкретном SHA и проверяемых evidence.

## 12. Исходные нормативные документы и traceability

| Scope | Detailed source |
|---|---|
| Общая бизнес-концепция | [Главное ТЗ v3.6](FIN-ANALYZER-PROJECT-TZ.md) |
| 39 модулей и 11 мастеров | [MODULE-CATALOG](MODULE-CATALOG.md) |
| Роли и межролевые процессы | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| Отраслевая и инвестиционная аналитика | [ECONOMIC-DIRECTIONS](ECONOMIC-DIRECTIONS.md) |
| 44 формы | [FORM-CATALOG](FORM-CATALOG.md) |
| Бизнес и интерпретатор | [MY-BUSINESS](FIN-MY-BUSINESS-INTERPRETER.md) |
| Проект и стратегия | [MY-PROJECT](FIN-MY-PROJECT-STRATEGY.md) |
| Панели и маршруты | [WORKSPACES](FIN-WORKSPACES-AND-JOURNEYS.md) |
| Отчетность и публикация | [REPORT-PUBLICATION](FIN-FINANCIAL-REPORT-PUBLICATION.md) |
| Консалтинг и документы | [CONSULTING](FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md) |
| Бизнес-модель площадки | [ADR-007](ADR-007-FIN-PROJECT-MARKETPLACE.md) |
| Backend stack | [ADR-008](ADR-008-FIN-CONNECT-BACKEND-STACK.md) |
| Зоны/срочный доступ/отзыв | [ADR-009](ADR-009-REPORT-ACCESS-ZONES.md) |
| Бренд и структура | [ADR-010](ADR-010-PRIMARY-PRODUCT-NAME.md) |
| Ранние технические критерии F01 | [FIN-SPEC](FIN-SPEC.md), [ARCHITECTURE](ARCHITECTURE.md), [DELIVERY](DELIVERY-AND-ACCEPTANCE.md) |

**Открытые решения, конфликты и трассировка:** [FIN-REQUIREMENTS-TRACEABILITY.md](FIN-REQUIREMENTS-TRACEABILITY.md), [FIN-REQUIREMENTS-DECISIONS-GAPS.md](FIN-REQUIREMENTS-DECISIONS-GAPS.md). Оба документа создаются в той же ветке как часть baseline.

**Граница завершения:** консолидированы требования, но не созданы backend/API, migrations, UAT evidence, сертифицированные методики или интеграции. Исходные документы сохраняются, ничего не удаляется.
