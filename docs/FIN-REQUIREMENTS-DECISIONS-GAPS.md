# FIN Project Marketplace — журнал решений, противоречий и открытых вопросов

**Baseline:** REQUIREMENTS-GAPS v1.0 · 2026-11-15  
**Ветка:** \`docs/fin-marketplace-requirements-baseline\`  
**Статус:** DECISIONS RECORDED / OPEN ISSUES NOT RESOLVED  
**Основной SRS:** [FIN-REQUIREMENTS-CONSOLIDATED.md](FIN-REQUIREMENTS-CONSOLIDATED.md).

## 1. Принятые решения (проектные, не реализованные)

| ID | Решение | Авторитетный источник |
|---|---|---|
| DEC-01 | Название всей платформы FIN Project Marketplace, FIN Analyzer — встроенный экономический модуль, Project Discovery — каталог | [ADR-010](ADR-010-PRIMARY-PRODUCT-NAME.md) |
| DEC-02 | Никакого хранения/перевода денег, биржевых торгов, исполнения инвестиционных сделок и кредитного одобрения в FIN | [ADR-007](ADR-007-FIN-PROJECT-MARKETPLACE.md) |
| DEC-03 | Публикация и обмен — информационные функции; соответствие правилам стран проверяется отдельно до hosted запуска | [ADR-007](ADR-007-FIN-PROJECT-MARKETPLACE.md) |
| DEC-04 | Local FIN Core для самостоятельного анализа не требует регистрации; hosted FIN Connect вводится отдельно | [ADR-008](ADR-008-FIN-CONNECT-BACKEND-STACK.md), [Module Catalog](MODULE-CATALOG.md) |
| DEC-05 | Hosted stack: React + Spring Boot BFF + FIN PostgreSQL; backend-only Keycloak (с собственной PostgreSQL DB), Redis, RustFS, KumoMTA | [ADR-008](ADR-008-FIN-CONNECT-BACKEND-STACK.md) |
| DEC-06 | Keycloak удостоверяет identity, FIN BFF проверяет org membership и resource scopes в PostgreSQL | [ADR-008](ADR-008-FIN-CONNECT-BACKEND-STACK.md), [ADR-009](ADR-009-REPORT-ACCESS-ZONES.md) |
| DEC-07 | Отчёт, публичный teaser, личные документы, заинтересованность инвестора и consulting Engagement — различные сущности | [ADR-007](ADR-007-FIN-PROJECT-MARKETPLACE.md), [Report Center](FIN-FINANCIAL-REPORT-PUBLICATION.md) |
| DEC-08 | Зоны Z00–Z99, адресат, действия, поля, период, immutable revision, срочный/контрактный/одноразовый доступ с отзывом | [ADR-009](ADR-009-REPORT-ACCESS-ZONES.md) |
| DEC-09 | Redis — не система учёта финансовых фактов, grants или Outbox; RustFS — не публичный файловый API | [ADR-008](ADR-008-FIN-CONNECT-BACKEND-STACK.md) |
| DEC-10 | Создание бизнес-плана и интерпретация должны базироваться на единой детерминированной финансовой модели | [Master TZ](FIN-ANALYZER-PROJECT-TZ.md) |
| DEC-11 | Подробные предметные сценарии модулей, форм и пользователей сохраняются отдельными требованиями, не удаляются в ходе консолидации | [Module Catalog](MODULE-CATALOG.md), [Traceability](FIN-REQUIREMENTS-TRACEABILITY.md) |

## 2. Уточнённые понятия и устранение расхождений

| ID | Что было неоднозначно | Нормативная трактовка | Остаточная работа |
|---|---|---|---|
| CONFLICT-01 | Старые заголовки «FIN Analyzer» как всей системы | Это название модуля; umbrella FIN Project Marketplace | технические пути/название GitHub-repo намеренно не переименовываются |
| CONFLICT-02 | Investment Marketplace и FIN Project Marketplace как название каталога | Каталог = **Project Discovery**; legacy direction \`MARKET\` → CON-01 | обновить устаревшее описание в ECONOMIC-DIRECTIONS (ветка требований) |
| CONFLICT-03 | «Четыре разных мастера» при пяти строках и 11 WZ в Module Catalog | 5 укрупнённых UX-маршрутов, 11 WZ-01…WZ-11 системных мастеров | поправить заголовок WORKSPACES и связать old/new |
| CONFLICT-04 | Browser-only MVP без backend vs enterprise Keycloak/PostgreSQL | Local G1/G2 и hosted G3/G4 — разные режимы развёртывания | не требовать identity/remote access для локальной диагностики |
| CONFLICT-05 | «Все ОКЭД и экономические школы» как мгновенно готовые формулы | все коды доступны для выбора, методы имеют конкретную поддержку и coverage status | выпускать sector/country/method packs с golden tests |
| CONFLICT-06 | Индивидуальный внешний консультант vs консалтинговая компания | R08 — персональная роль; ConsultingOrganization с несколькими участниками и Engagement | схемы membership, sharing и консалтингового revocation |
| CONFLICT-07 | IMPLEMENTED / TESTED / APPROVED | status string должна иметь доказательства CI/UAT/S2 на одном SHA | real acceptance evidence |
| CONFLICT-08 | «44 формы» означают обязательность ввода | формы подключаются адаптивно по бизнес-задаче/отрасли/данным | FormRequirementRegistry с applicable/not applicable |

## 3. Открытые технические и продуктовые задачи

| ID | Приоритет | Вопрос / риск | Нужный артефакт до реализации |
|---|---|---|---|
| GAP-01 | P0 | Рабочая feat-ветка и актуальное ТЗ main разошлись; нельзя потерять F01 код и поздние документы | отдельный integration diff, тесты, независимое review; docs branch не мержит код |
| GAP-02 | P0 | Общая схема EconomicFact (decimal, period, origin, stock/flow, entity, actual/forecast) | Canonical Financial Model ADR, schema и golden fixtures |
| GAP-03 | P0 | Реализация одного formula engine для browser-first и hosted | deterministic cross-runtime parity test и versioned methodology contract |
| GAP-04 | P0 | WizardSession, хранение draft, зависимые вопросы и resume | domain schema + E2E S0/S1/S2, validation and UX errors |
| GAP-05 | P0 | Способ подтверждения права действовать от имени организации | OrganizationVerificationPolicy, roles, evidence and audit |
| GAP-06 | P0 | Минимальные квалифицированные отрасли и страны для релиза | выбор 3 пилотов (services, retail, production) + documented method coverage |
| GAP-07 | P0 | One-time lease, races revoke vs download, old Keycloak session/Redis | PostgreSQL concurrency rules + ZONE-01…36 test implementation |
| GAP-08 | P1 | Единственная versioned схема public teaser vs published financial report | field allowlist + metadata/privacy parity tests |
| GAP-09 | P1 | Актуальность международных правил, налогов, банковских программ | country pack effectiveFrom/to, source and independent verification |
| GAP-10 | P1 | Параметры хранения персональных и финансовых данных по странам | data retention/residency/privacy/legal review |
| GAP-11 | P1 | Изоляция заметок и внутренних решений нескольких банков/фондов | multi-tenant ABAC model + cross-org negative E2E |
| GAP-12 | P1 | Надёжность RustFS при недозаписанной загрузке и orphan objects | quarantine/staging, checksum and garbage collector rules |
| GAP-13 | P1 | SMTP, webhooks, bounce и outbox для KumoMTA, где mock vs real | idempotent consumer, durable retry, real integration staging |
| GAP-14 | P1 | Фильтрация, обнаружение мошенничества, жалобы и реклама | abuse policy, moderator review/appeal, transparent listings |
| GAP-15 | P1 | Публикация экономических предложений в разных юрисдикциях | country-by-country counsel review, no payment/trading functionality |
| GAP-16 | P1 | Импорт XLSX/CSV/POS/банковских выгрузок и недоверенных данных | staged mapping and formula/zip/CSV injection tests |
| GAP-17 | P1 | Многоорганизационные проекты, связанные стороны и консолидация | entity/perimeter model, elimination correctness and access boundary |
| GAP-18 | P2 | Pricing и бизнес-модель SaaS для различных участников | user validation / pricing research, avoid unreviewed success fees |
| GAP-19 | P2 | Нагрузочные критерии и эксплуатационные SLO | workloads, p95/p99/CPU/RAM recovery in staging, avoid invented SLAs |
| GAP-20 | P2 | Единая UI-языковая и методическая локализация RU/KZ/EN и других стран | i18n key registry, country templates and compliance review |

### Дополнительные открытые методические решения по отраслям

| ID | Приоритет | Вопрос | Артефакт/доказательство |
|---|---|---|---|
| GAP-21 | P0 | 63 отраслевых SF-формы и рекомендованные частоты существуют, но нет полной методической квалификации каждого KPI | SectorPack review, versioned inputs, formula AST, 3 локали, golden tests |
| GAP-22 | P1 | Как формально отнести к отрасли деятельность ИП/ТОО/АО при множественных ОКЭД и смене редакций | классификатор+версия+effectiveDate+mapping, независимый review |
| GAP-23 | P1 | Какие данные собирать ежедневно, а какие только ежемесячно без чрезмерной нагрузки | CadencePolicy+UX pilots по G/M/C/A/F, high-frequency inputs optional |
| GAP-24 | P1 | Отделить юридические сроки сдачи отчетности от cadence экономической диагностики | CountryRegulatoryCalendar with verified effective rules |
| GAP-25 | P2 | Станет ли ML/Tribuo улучшением для отдельных отраслевых диагнозов | temporal holdout and tenant leakage checks versus deterministic/seasonal baselines |

**GAP-21 уточнён:** 9/63 SF-форм имеют исполняемые domain-реализации ([GMC pilot](FIN-SECTOR-PILOTS-GMC.md)), но independent Methodology Review, RU/KK/EN, React UI, реальные входы и server-side approvals не завершены. Это не снимает GAP-21.

## 4. Управление версиями и готовностью

**Статус каждого требования:** SPECIFIED → REVIEWED → READY_FOR_IMPLEMENTATION → IMPLEMENTED → TESTED → UAT_PASS → INDEPENDENT_APPROVE → RELEASED. Не считать записи в ТЗ выполненной функциональностью.

**G0:** F01 Draft PR #2 должен отдельно пройти независимое review и ручную проверку на MacBook, XLSX security tests.  
**G1:** S0/S1/S2 Wizard, Business Diagnosis, минимум Business Plan, local save/reopen.  
**G2:** investment cases, methodological coverage, safe report/teaser export.  
**G3:** hosted Spring Boot + PostgreSQL + Keycloak + Redis + RustFS + KumoMTA, организации, publication, access, investor/bank/consultant E2E.  
**G4:** cross-border expansion, отрасли, strategic monitoring.

**Запрет на смешение веток:** ветка \`docs/fin-marketplace-requirements-baseline\` хранит только консолидацию требований. \`main\` и \`feat/fin-r0-r1a\` не изменяются, merge в main без отдельной команды не проводится. При необходимости создаётся **Draft PR**, но это review документации, не релиз продукта.
