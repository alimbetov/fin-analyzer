# FIN Analyzer — пользовательские пространства, мастера и публикация

**Версия:** WORKSPACE-FLOWS v1.0 · **Дата:** 2026-11-15 · **Статус:** PROPOSED / NOT IMPLEMENTED  
**Связанные документы:** [Master TZ](FIN-ANALYZER-PROJECT-TZ.md), [«Мой бизнес»](FIN-MY-BUSINESS-INTERPRETER.md), [«Мой проект»](FIN-MY-PROJECT-STRATEGY.md), [Публикация финансовых отчётов](FIN-FINANCIAL-REPORT-PUBLICATION.md), [Консалтинг](FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md).

## 1. Два главных пространства и Marketplace

- **«Мой бизнес»** — эксплуатационная/управленческая аналитика компании: продажи, себестоимость, cash, клиенты, маркетинг, ресурсы, персонал и диагностика внутренних проблем. Отдельная функция публикации финансовых отчётов.
- **«Мой проект»** — создание экономического проекта, стратегических целей, вариантов развития, бизнес-плана, инвестиционных моделей, пакетов для инвестора/банка и публикации выбранного публичного паспорта.
- **FIN Project Marketplace** — информационный каталог и сотрудничество участников: инвесторы, банки, компании, консалтинговые фирмы, партнёры; только раскрываемые владельцем сведения. **Нет денежных операций и исполнения сделок**.

Создатель может быть без действующей компании; действующий бизнес может иметь много проектов. \`BusinessActualSnapshot\` — исторические подтверждённые на доступном уровне факты; \`ProjectAssumptions\` — независимые прогнозы, которые не редактируют исторический источник.

## 2. Панели управления

| Код | Панель | Главный пользователь | Ключевые действия/ценность |
|---|---|---|---|
| WS00 | Публичный поиск | гость | фильтр по отрасли/стране/цели, публичные ProjectListing, public report |
| WS01 | Мой бизнес | ИП/собственник/руководитель | ввод продаж, KPI, диагностика проблем, действия, финансовые отчёты |
| WS02 | Мой проект | основатель/CFO | стратегия, сценарии, бизнес-план, пакеты для инвестора |
| WS03 | Команда компании | бухгалтер/маркетолог/производственник | назначенные блоки данных, согласования, ревизии |
| WS04 | Инвестор | частный инвестор | сравнение, Watchlist, interest, разрешённые документы |
| WS05 | Банк и фонд | аналитик и риск-менеджер | адресованные пакеты, запросы, внутренние заметки |
| WS06 | Деловой партнёр | партнёр/франчайзи/поставщик | предложения ресурсов, сотрудничество, запрос контакта |
| WS07 | Консалтинговая компания | фирма и её руководитель/аналитики | клиенты, engagements, дополнительные запросы, files, review |
| WS08 | Мониторинг | владелец и допущенный инвестор | plan/fact, KPI, даты, отчёты по исполнению |
| WS09 | Trust | модератор/администратор/методолог | безопасность, жалобы, версии методик, аудит |

Dashboard каждого рабочего пространства показывает **«Требует действия → Статус → Основные операции → История»**. Роль и org context определяются сервером, а не только меню React. У консалтинга обязательно есть оба края процесса: «Мои запросы» и «Входящие запросы консультантов» у компании.

## 3. Четыре разных мастера

| Wizard | Что делает | Когда заканчивается | Не смешивать с |
|---|---|---|---|
| **MB-WIZ** | профиль действующего бизнеса → B/F/C формы → review → KPI → interpretator → corrective actions | внутренний бизнес-диагноз | автоматической публикацией |
| **PR-WIZ** | бизнес-идея/рост → отрасль → стратегические альтернативы → CAPEX/market/operations → forecast → business plan | проект, стратегия, investor/bank pack | финансовыми операциями |
| **PUB-WIZ** | выбрать аудиторию → allowlist → точный preview → полномочия/approval → immutable PublicTeaserSnapshot | контролируемая публикация проекта | полным .fin.json |
| **REP-WIZ** | тип финансового отчёта → период/формы → проверки → snapshot → PRIVATE/INVITE/PUBLIC → approval | закрытый или раскрытый отчёт | публичной карточкой проекта |
| **CONS-REQ** | engagement → цель анализа → недостающие материалы → item-level request → consent/grant → review | запрос материалов и консалтинговое заключение | бесконтрольным доступом к клиенту |

В таблице **пять** независимых маршрутов: два бизнес-мастера и три формы обмена/публикации. Каждый поддерживает сохранить/продолжить, «не знаю», явный ноль, проверку source/period/currency, изменения без молчаливой потери данных.

## 4. Сквозные истории и негативные сценарии

| ID | Акторы | Поток | Обязательная негативная проверка |
|---|---|---|---|
| FLOW-01 | ИП | продажи → расходы → диагноз по правилам → план действий | missing/negative/zero отдельно |
| FLOW-02 | CFO и собственник | диагностика бизнеса → создать проект развития → 3 стратегических сценария | forecast не изменяет actual |
| FLOW-03 | Основатель | PR-WIZ → бизнес-план → публикационный preview | без достаточных данных нет выдуманного NPV |
| FLOW-04 | Компания и инвестор | publish teaser → search → interest → request docs | investor не видит private model |
| FLOW-05 | Компания и банк | адресованный financial package → вопросы → пересмотр | bank не видит чужие дела |
| FLOW-06 | Компания и партнёр | ресурсы и предложения → запрос контакта → обсуждение | договоры/деньги вне FIN |
| FLOW-07 | Бухгалтер/директор | REP-WIZ → проверка → approval → PUBLIC_SUMMARY | private fields не раскрываются |
| FLOW-08 | Банк/инвестор | INVITE_ONLY financial report → ScopedGrant → review → revoke | stale JWT/session после revoke deny |
| FLOW-09 | Консалтинговая фирма → компания | знакомство → engagement → запрос документов сверх отчётности → частичная выдача → review | без согласия не запросить/не читать private данные |
| FLOW-10 | Компания → консалтинговая фирма | компания приглашает → делегирует scope → запросы → заключение → закрытие | после закрытия нет новых downloads |
| FLOW-11 | Консультант и компания | консультант предлагает исправления бизнес-плана | consultant не публикует от клиента |
| FLOW-12 | Фонд | pipeline → аналитик → due diligence → private notes | два фонда изолированы |
| FLOW-13 | Owner и спонсор | reported external outcome → baseline → plan/fact | user-reported не VERIFIED_EXTERNAL |
| FLOW-14 | Модератор | жалоба → restrict → appeal | нет рутинного доступа к private financial data |
| FLOW-15 | Система | PG Outbox → KumoMTA retry/callback → UI inbox | email accepted не равно delivered |
| FLOW-16 | Security | multi-org user → change context → revoke grant → try old link | IDOR/cache stale fail closed |
| FLOW-17 | Компания | corrected published financial report → new revision | historical published snapshot immutable |

## 5. Публикация: отдельные объекты и статусы

**PUBLIC** и **INVITE_ONLY** относятся к отдельным ресурсам:
- \`ProjectListing/PublicTeaserSnapshot\` — публичное описание **проекта**;
- \`FinancialReportPublication/ReportDisclosureSnapshot\` — публичная или адресная версия **отчёта**;
- \`DocumentGrant\` — права конкретного получателя на конкретный **файл**;
- \`ConsultingEngagement/RequestItem\` — основание и **запрос материалов**.

Публикация ProjectListing **не публикует отчёты**. Публикация отчёта **не публикует проект**. Engagement консультанта **не выдаёт полный доступ ко всему бизнесу**. Все public versions immutable, с явным consent и security audit.

## 6. Инфраструктурный контракт

**React + TypeScript** → **Spring Boot BFF (Java 21, REST/JPA)** → **FIN PostgreSQL** (source of truth). Backend-only: **Keycloak OIDC + отдельная PostgreSQL DB**, **Redis sessions/cache**, **RustFS private object storage**, **KumoMTA mail via PostgreSQL Outbox**. User-facing Keycloak login через reverse proxy, React не хранит токены. Действия doc download/grant/approval и switch org требуют server authorization. Подробно: [ADR-008](ADR-008-FIN-CONNECT-BACKEND-STACK.md).

## 7. Release gates

- **Core**: расчётные факты и интерпретатор, “Мой бизнес”, “Мой проект”, ограниченные финансовые формы, локальный BusinessPlan/report export; без hosted accounts.
- **Hosted foundation**: Spring Boot + FIN PostgreSQL + Keycloak DB + Redis, verified org membership и tenant isolation (old JWT / revoke E2E).
- **Sharing**: RustFS с карантином и item-level grants, private reports and project documents; KumoMTA с durable Outbox, real callback.
- **Marketplace**: публичные project listings, controlled financial report publishing, discovery, interest, consultant requests, moderation, monitoring; только по подтверждённым security/finance/UAT gates.

**Текущее состояние:** document/specification only. В рабочем коде пока F01 balance prototype в \`feat/fin-r0-r1a\`, Draft PR #2; полнофункциональных кабинетов, server backend и Marketplace нет.
