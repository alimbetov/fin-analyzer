# FIN Analyzer — перспективность продукта, роли, сквозные сценарии и архитектурные границы

**Версия:** FIN-ROLE-SCENARIOS v1.1, 2026-11-15  
**Статус:** PRODUCT REQUIREMENTS / PROPOSED / NOT IMPLEMENTED  
**Главное ТЗ:** [FIN-ANALYZER-PROJECT-TZ.md](FIN-ANALYZER-PROJECT-TZ.md)  
**Экономические направления:** [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md)  
**Кредитные формы:** [FORM-CATALOG.md](FORM-CATALOG.md)  
**Техническая основа:** F01 Balance prototype в Draft PR #2, не общий выпуск всех описанных функций.

## 1. Стратегическая оценка перспективности

### 1.1. Главный тезис

FIN Analyzer должен превращать бизнес-вопрос в проверяемую экономическую модель, бизнес-план и объяснимый набор возможных решений; затем, на основании контролируемо раскрытых сведений, помогать пользователям вступить в контакт с инвесторами, банками, партнёрами и консультантами. Ценность возникает из доверия к данным и согласованности модели, а не из одной витрины проектов.

**Проверяемые продуктовые гипотезы (не подтверждённые метрики):**

| Гипотеза | Проверка | Что будет признаком полезности |
|---|---|---|
| H1: новичок способен получить полезный предварительный анализ без бухгалтера | наблюдаемый UAT на 5–10 представителях S0–S2 | завершение ввода, понимание результатов, отсутствие выдуманных фактов |
| H2: профессиональная модель и простой мастер могут использовать единые факты | золотые фикстуры B/F/C и cross-input E2E | идентичные показатели при сопоставимых входах |
| H3: пользователи заинтересованы в превращении диагностики в бизнес-план | пользовательские тесты и добровольный экспорт | достижение последовательного плана без повторного ввода |
| H4: инвесторы/банки предпочитают стандартизированный обзор проекта | интервью с адресатами + ревью обезличенных образцов | понятная структура, достаточность, traceability и конкретные запросы документов |
| H5: публикация проектов даст качественный двусторонний спрос | только после H1–H4: пилот Connect с проверенными сторонами | подтверждённый интерес, запросы документов, повторные обращения, без фабрикации инвестиционных обещаний |

Нет достаточных оснований утверждать, что marketplace, AI или доступ «ко всем инвесторам мира» создадут спрос автоматически. Оценка внедрения и коммерческого потенциала требует реального исследования целевых аудиторий.

### 1.2. Международные ориентиры и конкуренты

- LivePlan: guided business planning, forecasts, growth dashboards, sharing, pitch decks — https://www.liveplan.com/features
- DocSend: controlled document sharing, virtual data rooms and due diligence workflows — https://www.docsend.com/features/virtual-data-room/
- Вывод: FIN не должен копировать существующие продукты в первом релизе. Потенциальная специализация: **adaptive economic-question flow + methodologically transparent cross-domain model + industry/country applicability + reusable bank/investor packages**. Это гипотеза для проверки.

### 1.3. Приоритизация по ценности и риску

| Класс | Функции | Причина |
|---|---|---|
| P0 / Product Core | Smart Wizard, Canonical Economic Facts, Financial/Investment/Operations calculations, Business Plan, Scenario Lab, local save/export | полезны одному пользователю без network effects |
| P1 / Trust & Packaging | sector/country methodology coverage, evidence register, share-safe teaser, investor/bank data package, versioned valuation | усиливают качество и применимость результатов |
| P2 / Collaborative Finance | organization accounts, RBAC/ABAC, Connect discovery, invitations, Deal Room, bank/lender workspace, advisor collaboration, project monitoring | требуют backend, безопасности, нескольких сторон и реального спроса |
| P3 / Network & Governance | recommendation/matching, portfolio analytics, grant programs, cross-border onboarding, open ecosystem | высокая стоимость данных, интеграций и комплаенса |
| OUT OF SCOPE | платежи, хранение средств, биржевые торги, исполнение сделок, автоматическая выдача кредитов; регулируемые формы посредничества не входят в FIN | исключены из roadmap; до hosted публикации — юридическая оценка предлагаемых информационных действий в каждой стране |

Первоначальный продукт — **single-player utility** (пользователь получает пользу даже без инвесторов и банков). Только затем multi-party network; иначе marketplace столкнётся с отсутствием проверенных проектов и активных контрагентов.

## 2. Предметная модель и разграничение понятий

- **Organization** — компания, ИП, фонд, банк, консультант, публичная институция или группа; юридическая форма и регистрация зависят от страны.
- **Principal / User** — человек с удостоверяемой учётной записью в будущем hosted-контуре; локальный FIN не доказывает личность.
- **OrganizationMembership** — принадлежность к организации и полномочия. Компания может быть и project sponsor, и investor, и supplier.
- **EconomicProject** — цель и экономическая модель проекта (инвестиции, действующий бизнес, маркетинговая кампания, проект производства и т.д.).
- **EconomicCase** — конкретная аналитическая задача, контекст, контрфактические альтернативы и сценарии.
- **BusinessPlanCase** — структура бизнес-плана с одной версионированной экономической моделью и текстовыми/документальными доказательствами.
- **FinancingRequest** — потребность в средствах, суммы, назначения, типы финансовых инструментов, сроки, условия. Не равен договору.
- **FundingApplication** — адресованный банку/фонду/провайдеру запрос с выбранным раскрытием и формальным статусом рассмотрения; не означает подачу через интеграцию, пока нет реального канала.
- **ProjectListing / PublicTeaserSnapshot** — опубликованная разрешённая проекция проекта без исходного полного FIN проекта.
- **InterestRequest** — неофициальный сигнал заинтересованности, не обязывающий инвестировать.
- **DueDiligenceCase / DataRoom** — проверка и обмен разрешёнными документами.
- **AgreementReference** — ссылка на внешне заключённый документ; статус подтверждается отдельно.
- **FinancingEvent** — факт поступления финансирования по подтверждённому документу, не из события «инвестор согласен».
- **ExecutionBaseline / MonitoringPeriod** — согласованный план и фактическое исполнение.
- **EvidenceRecord** — источник, документ, период, версия, качество, происхождение метрики.
- **DisclosurePolicy / ShareGrant** — что может видеть конкретный субъект, на каком основании, до какой даты, можно ли скачивать.

**Обязательные инварианты:** projectId ≠ organizationId; пользователь не может сам объявить себя представителем чужой компании; статус «одобрено» в FIN отличается от банковского решения; заявка ≠ финансирование ≠ поступившие деньги; внутренняя финмодель ≠ публичная карточка; изменение бизнес-плана не переписывает выданный исторический snapshot.

## 3. Реестр ролей и границы полномочий

Роли **не взаимоисключающие**. Один человек может быть владельцем компании, автором проекта и частным инвестором. Доступ определяется контекстом организации + конкретного проекта/документа, а не одной глобальной строкой role.

| Код роли | Кто | Главная задача | Чувствительность данных | Этап |
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

**Ни одна из этих ролей (кроме технических возможностей самого владельца локального файла) не считается действующей авторизацией в существующем React-only пилоте.** Все hosted роли — требования к будущей IAM. Независимый reviewer не должен автоматически иметь права публикации или изменения исходных данных.

## 4. Сквозные пользовательские сценарии по каждой роли

Каждый сценарий описывает целевое поведение, а не реализованный сервис.

### U-R01 — Основатель нового бизнеса (S0)
- **Цель:** бизнес-план открытия небольшого производства, потребность в CAPEX и оборотном капитале.
- **Шаги:** выбрать отрасль/ОКЭД → Wizard с вопросами о продукте, объёмах, цене, расходах, оборудовании, сроках → сравнить baseline/downside/upside → получить прогноз cash, NPV (если данные позволяют), безубыточность → собрать Business Plan → экспортировать investor-safe teaser.
- **Результат:** BusinessPlanCase, AssumptionSet, ReportSnapshot и отдельный PublicTeaserSnapshot.
- **Негативный путь:** не хватает данных о цене оборудования → CAPEX gap, недоступный payback; исторические показатели не измышляются.

### U-R02 — ИП или собственник малого бизнеса (S1–S2)
- **Цель:** понять, почему при продажах не хватает денег.
- **Шаги:** указать бизнес/период → простые вопросы о продажах, оплатах, закупках, расходах, остатках, долгах → разделить начисления и деньги → Cash Diagnostic → выбрать next-best-question → сохранить проект.
- **Результат:** предварительная прибыльность, cash calendar (только при датах), warning по неопределённости.
- **Негативный путь:** нет данных о дебиторке → не утверждать точный кассовый прогноз и доступность ликвидности.

### U-R03 — Директор/управляющий компании
- **Цель:** открыть вторую точку или оптимизировать действующую.
- **Шаги:** просмотреть KPI → выбрать альтернативы A/B/do-nothing → изменить допущения → увидеть влияние на компанию и текущую ликвидность → утвердить сценарий как управленческий baseline (не юридическое решение).
- **Результат:** DecisionCase, comparison report, управленческий action plan.
- **Негативный путь:** у менеджера нет прав к payroll → показать только агрегаты или скрыть персональные сведения.

### U-R04 — Бухгалтер
- **Цель:** загрузить проверенную отчётность за периоды.
- **Шаги:** F01/F02/F03/F04 либо адаптер → контроль валюты, периода, stock/flow, арифметики → исправление ошибок → согласование корректировок → подготовка source-referenced snapshot.
- **Результат:** reproducible validated revisions, reconciliation findings.
- **Негативный путь:** Excel со сторонними формулами, неполным периодом или mismatched basis не меняет approved revision.

### U-R05 — Финансовый директор/аналитик
- **Цель:** сравнить кредит, долю инвестора и лизинг.
- **Шаги:** подключить прибыль и cash, графики платежей → спроектировать project vs equity cashflows → определить ставки/сценарии → анализ NPV, DSCR при корректной методике, dilution → документировать assumptions.
- **Результат:** Financing Alternatives Report и инвестиционное заключение с ограничениями.
- **Негативный путь:** не смешивать unlevered FCF с equity discount rate; IRR с несколькими корнями не представлять как однозначный.

### U-R06 — Маркетолог/руководитель продаж
- **Цель:** перераспределить бюджет каналов.
- **Шаги:** импортировать campaign spend, leads, новые покупки, возвраты, contribution margins → согласовать attribution window → сравнить CAC/ROAS → сценарный перенос бюджета.
- **Результат:** Channel Economics Report с когортами/допущениями.
- **Негативный путь:** attributed revenue не называется доказанной incremental revenue; без когорт/окна нет точного LTV.

### U-R07 — Производственный/операционный менеджер
- **Цель:** выяснить себестоимость продукции и эффект нового оборудования.
- **Шаги:** добавить единицу выпуска, затраты, выпуск, брак, время/мощность → определить allocation policy → расчёт unit cost/utilization → сценарий оборудования → передать CAPEX в Investment Advisor.
- **Результат:** Unit Economics + Capacity Report + Investment Case.
- **Негативный путь:** закупленный запас не равен потреблённому, общие затраты не распределяются дважды.

### U-R08 — Внешний консультант
- **Цель:** подготовить модель и бизнес-план для клиента.
- **Шаги:** получить явно выданный пакет/доступ → выявить missing facts → направить вопросы → предложить исправления → передать владельцу на подтверждение.
- **Результат:** review package, комментарии, согласованный список пробелов.
- **Негативный путь:** консультант не публикует проект, не меняет shareholder assumptions или банковское досье без отдельного согласия.

### U-R09 — Частный инвестор
- **Цель:** найти проекты для рассмотрения, не получая лишние личные данные.
- **Шаги:** указать отрасль/регион/инструмент/диапазон → изучить PublicTeaser → сравнить methodology, source coverage, risks → отправить InterestRequest → запросить доступ.
- **Результат:** список заинтересовавших проектов, переписка/запрос доступа в hosted Connect.
- **Негативный путь:** investor не видит private assumptions, payroll, банковские реквизиты; InterestRequest не создаёт обязательств.

### U-R10 — Инвестфонд / PE / VC
- **Цель:** вести pipeline и проводить due diligence нескольких проектов.
- **Шаги:** команда фонда открывает проекты с permissions → screening checklist → запрос документов → отраслевой/финансовый analysis → investment-committee memo → внешний term sheet → мониторинг подтверждённых вложений.
- **Результат:** pipeline, IC packet, decision log; private scoring относится к фонду, не к публичному рейтингу FIN.
- **Негативный путь:** reviewer фонда не видит конфиденциальные данные других фондов; решение фонда не означает поступление инвестиций.

### U-R11 — Кредитный аналитик банка
- **Цель:** проверить применимую заявку.
- **Шаги:** принять FundingApplication через авторизованный hosted канал либо импорт экспортного пакета → проверить согласие, профиль компании, формы/отчётность, документы → финансовый screening → запросить недостающие сведения → передать на решение.
- **Результат:** completeness checklist, analyst memo, evidence, missing data list.
- **Негативный путь:** FIN не выносит автоматическое «кредит одобрен»; профиль банка не объявляется официальной кредитной политикой без валидации.

### U-R12 — Риск-менеджер/кредитный комитет банка
- **Цель:** принять отдельное внутреннее решение с разделением обязанностей.
- **Шаги:** получить frozen application snapshot и memo аналитика → применить утверждённые кредитные политики банка → запросить уточнение → зафиксировать внешний decision reference.
- **Результат:** decision record с ответственным субъектом и датой, решение при наличии полномочий.
- **Негативный путь:** нельзя подменить банковский decision record перезапуском FIN анализа, Analyst не может сам себе выдать Approver.

### U-R13 — Лизинговая компания / грантовый фонд
- **Цель:** оценить соответствие проекта инструменту.
- **Шаги:** получить целевую заявку → сверить сумму, актив, график, актуальные program eligibility conditions → запросить документы → зафиксировать предварительную применимость.
- **Результат:** Eligibility Checklist / Offer Request.
- **Негативный путь:** устаревшая программа/неизвестная юрисдикция → NEEDS_CURRENT_VERIFICATION, не гарантированная субсидия/грант.

### U-R14 — Стратегический партнёр / франчайзи
- **Цель:** изучить совместное предприятие или франшизу.
- **Шаги:** найти проект по необходимым ресурсам/производству/рынку → предложить своё оборудование/сбыт/персонал → сравнить совместный cashflow и распределение выгод → сформировать Partnership Case.
- **Результат:** альтернативные экономические условия партнёрства.
- **Негативный путь:** общий вклад двух сторон не считается выручкой; проект не автоматически видим всем поставщикам.

### U-R15 — Спонсор мониторинга / инвестор после сделки
- **Цель:** контролировать исполнение проекта.
- **Шаги:** согласовать frozen baseline → получать подтверждённые periodic KPI → сравнить actual/forecast/covenants → запросить объяснение отклонений.
- **Результат:** Investor Update Snapshot, deviations, provenance.
- **Негативный путь:** фактический расход не заменяет исходный план; FIN не подтверждает юридический дефолт автоматически.

### U-R16 — Администратор организации
- **Цель:** управлять доступом сотрудников и консультантов.
- **Шаги:** создать организацию (после проверки представительства по требуемому режиму) → пригласить участников → предоставить роль+scope → отозвать доступ → посмотреть журнал.
- **Результат:** membership/ACL и audit log.
- **Негативный путь:** владелец локального FIN файла не считается автоматически юридическим администратором hosted-организации.

### U-R17 — Compliance / модератор
- **Цель:** проверять жалобы на недостоверные публичные инвестиционные объявления.
- **Шаги:** получить report → проверить только допустимые материалы → временно скрыть listing при наличии основания → уведомить стороны → оставить audit/evidence для апелляции.
- **Результат:** ModerationCase со статусом и основаниями.
- **Негативный путь:** модерация объявления не даёт сотруднику свободного доступа к закрытым бухгалтерским сведениям.

### U-R18 — Администратор платформы
- **Цель:** эксплуатация, конфигурация, восстановление инцидента.
- **Шаги:** service health/permissions/feature flags → управление версиями методик и доступностью сервисов → logging без финансовых payload → break-glass только с контролем и аудитом.
- **Результат:** operational audit, safe rollout and rollback.
- **Негативный путь:** staff admin не должен «по умолчанию» читать private reports и data rooms.

### U-R19 — Экономист / редактор методик
- **Цель:** добавлять международные, отраслевые, страновые методы и экономические школы.
- **Шаги:** создать MethodDefinition → указать источники, теоретический подход, юрисдикцию, ОКЭД/ISIC/NACE/NAICS при наличии verified crosswalk → написать golden fixtures → отправить в independent review → опубликовать новую версию.
- **Результат:** MethodRegistry entry, CoverageState и воспроизводимые расчёты.
- **Негативный путь:** метод с неподтверждёнными налоговыми ставками не может быть помечен JURISDICTION_QUALIFIED.

### U-R20 — Независимый reviewer / quality auditor
- **Цель:** удостовериться, что методика и сквозной сценарий корректны для заявленного scope.
- **Шаги:** ознакомиться с требованиями/fixtures → проверить numerical methods и security → провести независимый E2E → выдать approve или blocker.
- **Результат:** signed/referenced review evidence на конкретном commit/model version.
- **Негативный путь:** автор расчётного движка не может считать свой self-review независимым S2 APPROVE.

## 5. Cross-role workflows — жизненный цикл проекта

### FLOW-A: самопроверка без регистрации

R01/R02 → профиль S0–S2 → Smart Wizard → импорт/ручной ввод → валидация → частичная диагностика → бизнес-план → локальный .fin.json → безопасный экспорт. **Никакого hosted-участника нет.**

### FLOW-B: финансирование смешанной схемой

R01/R02 → EconomicCase → Investment Alternatives → FundingRequest: собственные средства + bank loan + equity → разные lender/investor-safe snapshots → R09/R10 проявляет интерес; R11 получает кредитный пакет → документы/вопросы → решения вне FIN Core → подтверждённый FinancingEvent → R15 мониторинг.

**Существенное ограничение:** банковские ставки/решения и инвесторские обязательства не подставляются фиктивно. Без backend — только файл/пакет для самостоятельного обмена.

### FLOW-C: проект с бизнес-партнёром

R02 + R14 → потенциальный ProjectListing → обмен раскрываемыми параметрами → joint EconomicCase с согласованными границами данных → сценарии распределения затрат и прибыли → внешний договор → R15 monitoring по agreed subset.

### FLOW-D: консультант помогает клиенту

R08 получает проект/делегированный доступ → исправляет структуру и вопросы → R04 подтверждает исходные бухгалтерские данные → R02 утверждает публикационную версию → R09/банк получает только разрешённый snapshot.

### FLOW-E: отказ в финансировании

FundingApplication получил REQUEST_MORE_INFO / DECLINED → исходный план остаётся неизменным → владелец исправляет данные в новой ревизии → новый расчёт и новая версия пакета → новая заявка/повторное рассмотрение. **Отказ не становится публичным рейтингом.**

### FLOW-F: международное расширение

Company A с country-specific IFRS/GAAP context, country B/филиал с local standard → standard mapping+FX rates+period checks → отдельные company snapshots → group consolidated result **только с элиминациями** → investor safe version. В противном случае NOT_COMPUTABLE/INCOMPLETE.

## 6. Матрица прав доступа: принцип deny-by-default

| Ресурс/операция | Project owner | Accountant / analyst | Consultant | Investor | Bank reviewer | Platform moderator |
|---|---|---|---|---|---|---|
| Raw EconomicFacts / full .fin.json | Own yes | Explicit scoped grant | Explicit scoped grant | No | No by default | No |
| Project editing / financial fact revision | Own yes | Granular permission + review | Proposed edits only by default | No | No | No |
| PublicTeaser approved snapshot | Publish/withdraw future visibility | Draft only by default | Draft only | Read after publication | Read if public | Moderate public copy |
| Invite-only DataRoom resource | Grant/revoke | Only by explicit permission | Only by explicit permission | Scoped invitation | Scoped invitation | No routine access |
| FundingApplication submission | Applicant with authorized role | Draft/prep | Draft/prep | No | Read own destination queue | No |
| Internal bank decision | No | No | No | No | Role-based, separate issuer control | No |
| Private investor notes/rank | No | No | No | Own notes | Bank's own scoped notes | No |
| Organization member management | Authorized org admin | No by default | No | Own organization only | Own organization only | No |
| Monitoring Investor Update | Publish scoped snapshot | Prepare | Prepare | Shared only | Shared only | No routine access |

**Hosted identity implementation (ADR-008):** React uses a Spring Boot BFF server-side OIDC session; Keycloak handles login, and Redis/RustFS/KumoMTA/PostgreSQL are backend-only dependencies, not browser integrations. Keycloak login may be displayed through controlled reverse-proxy auth endpoints, never its admin API. [Full security and E2E contract](ADR-008-FIN-CONNECT-BACKEND-STACK.md).

**Hosted identity**: organization-specific RBAC + resource ABAC (ownerOrg, projectId, documentId, sensitivity, recipientOrg, action, expiry, status), explicit ACL scopes, signed invitations, revocation, access audit and non-disclosure of sensitive fields. Avoid mixing requester and provider roles with one global role claim. Authentication/identity and KYC requirements must be validated for the particular legally allowed service.

**Official product boundary:** [FIN Project Marketplace ADR-007](ADR-007-FIN-PROJECT-MARKETPLACE.md) — information, project discovery, communication and controlled document access only; financial transactions are outside FIN.

**Core offline**: no implementation of cross-user authorization. Users can export data; once recipient downloads a file it cannot be remotely revoked. Do not promise enforceable no-forwarding or deletion of recipient copies.

## 7. What is required for a real international platform

1. **Jurisdiction/standards:** rule packs for IFRS, local GAAP, tax eligibility, accounting period and relevant regulatory controls with effectiveFrom/to. Cross-country reports only with verified conversion and disclosure. 
2. **Industry classification:** versioned OKED/ISIC/NACE/NAICS codes, reviewed correspondence relationships. “All OKED” begins with selectable catalog, not instant bespoke models for every code.
3. **Economic methodologies / schools:** MethodRegistry with numeric method + optional TheoryLens + validity/assumptions + country/industry applicability, scientific review state. One normative theory does not silently replace another's facts.
4. **Languages and currencies:** RU/KZ/EN first as product hypothesis; semantic codes immutable, translations optional; numeric/decimal/FX as explicit versions and dates.
5. **Data sources:** verified publication date/vintage, licensing, import mapping, market and funding program validity. No fabricated market size, competitors or benchmark rates.
6. **Regulatory boundary:** public listing / ordinary introduction / regulated crowdfunding / securities intermediation / lending / fund custody are separate regulated questions per jurisdiction. For AIFC, AFSA documents investment crowdfunding as an authorised market activity; see https://afsa.aifc.kz/market-activities/operating-an-investment-crowdfunding-platform/ . **No market activity launch without counsel and appropriate authorization where needed.**
7. **Security:** personal/financial data classification, tenancy isolation, threat model, encryption/secrets, audit, retention/deletion, incident response, DLP, content moderation, abuse prevention, verified provenance.
8. **Accessibility:** small business novice vs banker/investor experience; progressive disclosure, plain-language explanations, keyboard support and localizable terminology.

## 8. MVP boundaries and delivery gates

### G0 — Technical foundation (existing Draft PR #2)

F01 Balance Excel→React→Validate→Current Ratio→Graph→local save. Blockers: independent S2 review; human Desktop UAT; XLSX safety, accounting precision/provenance; do not merge main without approval.

### G1 — Wizard and core (single-player P0)

- EconomicCase + BusinessProfile, data-quality semantics, question decision engine, controlled calculation rules.
- UAT personas S0 startup, S1 individual service, S2 retail; actual-vs-forecast strict distinction.
- Minimal Business Plan Builder with revenue/cost/capex/cash scenarios and limitations.
- One consistent local report with save/reopen and noninvented data; elementary scenario comparison.

**Gate:** three users complete useful diagnosis; golden formulas and six critical accounting invariants pass; Playwright end-to-end and real MacBook UAT; security and no network exfiltration.

### G2 — Decision support and sharing (P1)

- Investment, operations, marketing baseline models on same facts.
- Versioned ReportSnapshot and share-safe PublicTeaserSnapshot with explicit preview and redaction.
- Export investor/bank packages; not an online marketplace.
- Methodology coverage report and evidence register; tested multi-domain consistency.

**Gate:** a financial number changed in assumptions updates all dependent reports, and public teaser contains no private fields. Independent reviewer verifies sample investor/bank packets.

### G3 — Hosted FIN Connect pilot (P2, separate architecture approval)

- Organization and identity/IAM, roles, onboarding/representation, project listings, restricted sharing, InterestRequest, partner/lender applicant queues, audit trails.
- Private data rooms after permission model and moderation.
- Initial pilot with consented organizations and investor/lender reviewers; no payments/trading.

**Gate:** cross-tenant security and refusal tests, authenticated UAT of founder/investor/bank interactions, documented legal scope; no false investment claims.

### G4 — Execution & portfolios (P2/P3)

- Monitored baseline, investor updates, deal pipeline, financing event confirmations, company/project portfolio reporting.
- Multi-country standards and additional industry packs with coverage reporting.
- International publication and investor-introduction features require per-jurisdiction legal review; financial execution, payment flows and exchange trading are outside FIN permanently (ADR-007).

**Gate:** verified cash/contract events and plan-fact reproducibility; no forged funding status; data room audit.

## 9. Acceptance matrix: role-oriented E2E

| Test | Actor / security or business invariant | PASS evidence |
|---|---|---|
| ROLE-01 | R01 completes draft plan without financial history | forecast tagged, no fake actuals, local save/reopen |
| ROLE-02 | R02 input by Interview and XLSX | canonical parity; cash vs accrual difference visible |
| ROLE-03 | R03 compares second shop vs existing shop | project and company flows not double-counted |
| ROLE-04 | R04 imports invalid XLSX | cannot alter approved revision |
| ROLE-05 | R05 loan/equity/leasing scenarios | no mixed cash-flow methodologies, traceable assumptions |
| ROLE-06 | R06 marketing analysis | invalid attribution not represented as causal incremental effect |
| ROLE-07 | R07 production cost | WIP/purchases and allocation policy correct |
| ROLE-08 | R08 consultant | cannot publish or approve without separate grant |
| ROLE-09 | R09 investor | cannot view private plan or raw banking/payroll fields |
| ROLE-10 | R10 fund and competitor fund | private notes, DD data and projects isolated |
| ROLE-11 | R11 bank analyst | only scoped application; missing data disclosure |
| ROLE-12 | R12 bank risk/approver | SoD on banking decision; no forged approval |
| ROLE-13 | R13 grants/leasing | stale program rules not claimed as current |
| ROLE-14 | R14 business partner | no accidental multi-org financial aggregation |
| ROLE-15 | R15 monitoring | immutable baseline, actual/forecast separated |
| ROLE-16 | R16 org admin | grants limited to own org and authorized resource |
| ROLE-17 | R17 moderator | cannot read private reports casually; all actions auditable |
| ROLE-18 | R18 platform admin | no default access to customer financial content |
| ROLE-19 | R19 methodology editor | method change requires golden tests, versioning and independent review |
| ROLE-20 | R20 S2 reviewer | independent evidence tied to immutable SHA/model version |
| SEC-01 | URL/token guessing | 403 or appropriate indistinguishable denial, no leak |
| SEC-02 | Revoke access | new requests denied; already downloaded copies cannot be revoked |
| SEC-03 | Safe publish | allowlist only, no hidden fields/metadata in PDF/HTML/JSON |
| SEC-04 | False financing event | no funding confirmed without external evidence |
| SEC-05 | Cross-tenant | no query/API/cache/attachment disclosures across entities |
| ECON-01 | Shared economics | investor teaser, bank package, business plan numbers equal same snapshot |
| ECON-02 | Changing assumptions | new scenario version; old approved snapshot immutable |
| METH-01 | Methodology & TheoryLens | changing the methodology/lens does not rewrite canonical facts; the comparison reports changed assumptions and evidence status |
| INTL-01 | Multi-country accounting | mismatched standards/currencies cannot be silently combined |
| LEGAL-01 | Regulated offer | all regulated marketplace features fail closed until authorized |

Each test must have executable fixture/data, test code, CI run URL/commit SHA and relevant manual UAT evidence. “Specified” and “Implemented” are not PASS.

## 10. Initial product KPIs (targets for validation, not observed facts)

- TTFR: median time to first useful result for S1/S2; initial hypothesis ≤10 min with required data accessible.
- Guided completion: percent of users finishing express diagnosis without consultant intervention.
- Comprehension: percent of UAT users who can correctly restate profit vs cash and report limitations.
- Model accuracy: discrepancy count vs independent golden fixtures, release blocker target = 0.
- Data quality: percent of results with explicit source/period/status and count of unresolved blockers.
- Plan readiness: percent of plans meeting selected audience's required sections and model checks; no invented fields.
- Share safety: count of unauthorized disclosure and stale link leak test failures (target 0).
- For Connect pilot only: qualified investor interest, DD completion, confirmed financing milestones — **not** promises or invented conversion rates.

## 11. Key product risks and gates

| Risk | Severity | Prevention |
|---|---|---|
| Build everything and fail to deliver useful MVP | Critical | P0 single-player wedge, stage gates, no simultaneous 20-module rollout |
| Non-validated economic claims dressed as recommendations | Critical | data status/provenance, mandatory gap disclosure, independent golden models |
| Marketplace with no verified supply or demand | High | validate project prep and investor/bank report demand before Connect |
| Financial information leakage | Critical | public teaser allowlist, deny-by-default, scoped sharing, tenant tests |
| Wrong regulatory characterization across markets | Critical | jurisdiction-specific legal assessment before regulated activities |
| Conflicting cross-domain calculations | High | one EconomicCase/Assumptions/FormulaRegistry/ReportSnapshot |
| Impossible universal OKED/ISIC coverage | High | explicit coverage levels and limited reviewed sector packs |
| Untrusted imports / stale data | High | resource-isolated import, versioned sources and current-data checks |
| Excessive role/IAM complexity in offline MVP | Medium | no fake hosted auth in core; separate Connect architecture stage |
| One large corporation vs novice UX | High | adaptive paths and progressive disclosure |
| Claiming independent review based on self-audit | High | external reviewer proof tied to SHA |

## 12. Explicit non-goals of first release

Not in initial MVP: operating a public hosted project marketplace, bank credit decisions, regulatory eligibility approvals, universal country tax rules, universal bank integrations, all industry models, external real-time research or unreviewed voice/OCR. **Not part of FIN at any planned stage:** processing payments, holding/transferring funds, exchange trading, custody, securities settlement and execution of investment/credit agreements.

## 13. Implementation traceability

Related: [Master TZ](FIN-ANALYZER-PROJECT-TZ.md), [Economic Directions](ECONOMIC-DIRECTIONS.md), [Technical Architecture](ARCHITECTURE.md), [Financial Form Catalog](FORM-CATALOG.md), [Project delivery gates](DELIVERY-AND-ACCEPTANCE.md).

**Current project status (verified at creation):** main contains documentation; F01 partial pilot in feat/fin-r0-r1a, Draft PR #2. No company accounts, bank/investor/marketplace modules, and no role-based hosted backend implemented. The above is a prospective functional specification, not a claim of deployed behavior.
