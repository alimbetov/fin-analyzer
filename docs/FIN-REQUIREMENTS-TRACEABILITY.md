# FIN Project Marketplace — матрица трассировки требований (RTM v1.0)

**Дата:** 2026-11-15 · **Ветка:** `docs/fin-marketplace-requirements-baseline` · **Статус:** DOC COVERAGE VERIFIED / EXECUTION NOT VERIFIED.
Основной источник: [SRS](FIN-REQUIREMENTS-CONSOLIDATED.md). Исходные IDs сохраняются; таблицы ниже фиксируют **покрытие документов**, не прохождение тестов.

## 1. Модули 39: область, актор, релиз и доказательство

| Module | Module name | Primary persona | Gate | Детальный документ | Test reference | Status |
|---|---|---|---|---|---|---|
| FND-01 | Identity & Sessions | любой hosted пользователь | G3 | [FIN-ANALYZER-PROJECT-TZ.md](FIN-ANALYZER-PROJECT-TZ.md) | Module contract / golden/negative E2E | SPECIFIED |
| FND-02 | Organizations & Membership | компания, банк, фонд, консультант | G3 | [FIN-ANALYZER-PROJECT-TZ.md](FIN-ANALYZER-PROJECT-TZ.md) | Module contract / golden/negative E2E | SPECIFIED |
| FND-03 | Access Center | владелец, банк, инвестор, консультант | G3 | [ADR-009-REPORT-ACCESS-ZONES.md](ADR-009-REPORT-ACCESS-ZONES.md) | ZONE-01…36 | SPECIFIED |
| FND-04 | Canonical Economic Facts | все модули | G1 | [FIN-ANALYZER-PROJECT-TZ.md](FIN-ANALYZER-PROJECT-TZ.md) | Module contract / golden/negative E2E | SPECIFIED |
| FND-05 | Form Registry | предприниматель, бухгалтер | G1→G2 | [FIN-ANALYZER-PROJECT-TZ.md](FIN-ANALYZER-PROJECT-TZ.md) | Module contract / golden/negative E2E | SPECIFIED |
| FND-06 | Adaptive Wizard | новичок, CFO, консультант | G1 | [FIN-ANALYZER-PROJECT-TZ.md](FIN-ANALYZER-PROJECT-TZ.md) | Module contract / golden/negative E2E | SPECIFIED |
| FND-07 | Input Adapters & Journal | бухгалтер, владелец | G1→G2 | [FIN-ANALYZER-PROJECT-TZ.md](FIN-ANALYZER-PROJECT-TZ.md) | Module contract / golden/negative E2E | SPECIFIED |
| FND-08 | Methodology & Country Registry | экономист/аналитик | G1→G4 | [FIN-ANALYZER-PROJECT-TZ.md](FIN-ANALYZER-PROJECT-TZ.md) | Module contract / golden/negative E2E | SPECIFIED |
| FND-09 | Document Vault & Evidence | владелец, банк, консультант | G3 | [ADR-008-FIN-CONNECT-BACKEND-STACK.md](ADR-008-FIN-CONNECT-BACKEND-STACK.md) | Module contract / golden/negative E2E | SPECIFIED |
| FND-10 | Report Center & Disclosure | владелец, CFO, адресат | G2→G3 | [FIN-FINANCIAL-REPORT-PUBLICATION.md](FIN-FINANCIAL-REPORT-PUBLICATION.md) | Module contract / golden/negative E2E | SPECIFIED |
| FND-11 | Notifications & Outbox | все роли | G3 | [FIN-ANALYZER-PROJECT-TZ.md](FIN-ANALYZER-PROJECT-TZ.md) | Module contract / golden/negative E2E | SPECIFIED |
| FND-12 | Collaboration & Approvals | команда/консультант | G2→G3 | [FIN-ANALYZER-PROJECT-TZ.md](FIN-ANALYZER-PROJECT-TZ.md) | Module contract / golden/negative E2E | SPECIFIED |
| BUS-01 | My Business Dashboard | ИП/собственник/директор | G1 | [FIN-MY-BUSINESS-INTERPRETER.md](FIN-MY-BUSINESS-INTERPRETER.md) | Module contract / golden/negative E2E | SPECIFIED |
| BUS-02 | Financial Analysis | бухгалтер, CFO, собственник | G1→G2 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| BUS-03 | Sales & Pricing | магазин/коммерческий директор | G1→G2 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| BUS-04 | Marketing Analytics | маркетолог | G2 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| BUS-05 | Procurement & Supply | закупщик, логист | G2 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| BUS-06 | Production & Operations | производство, сервис, логистика | G2 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| BUS-07 | HR Economics | HR/CFO | G2→G4 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| BUS-08 | Tax & Regulatory Planning | бухгалтер, консультант | G4 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| BUS-09 | Sustainability & Resource Economics | производственный менеджер | G4 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| PRJ-01 | My Project Workspace | основатель/CFO/директор | G1 | [FIN-MY-PROJECT-STRATEGY.md](FIN-MY-PROJECT-STRATEGY.md) | Module contract / golden/negative E2E | SPECIFIED |
| PRJ-02 | Business Plan Builder | основатель/консультант | G1→G2 | [FIN-MY-PROJECT-STRATEGY.md](FIN-MY-PROJECT-STRATEGY.md) | Module contract / golden/negative E2E | SPECIFIED |
| PRJ-03 | Investment Scenario Lab | CFO/основатель | G1→G2 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| PRJ-04 | Business Valuation | фонд, собственник, CFO | G2 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| PRJ-05 | Strategic Planning | директор/консультант | G1→G2 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| PRJ-06 | Risk & Stress Testing | аналитик/директор | G2 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| PRJ-07 | Funding Navigator | основатель/CFO | G2 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| PRJ-08 | Partnership & Franchising | компания/партнёр | G2→G3 | [ECONOMIC-DIRECTIONS.md](ECONOMIC-DIRECTIONS.md) | Module contract / golden/negative E2E | SPECIFIED |
| CON-01 | Project Discovery & Listing | гость/инвестор/партнёр | G3 | [FIN-WORKSPACES-AND-JOURNEYS.md](FIN-WORKSPACES-AND-JOURNEYS.md) | Module contract / golden/negative E2E | SPECIFIED |
| CON-02 | Interest & Communication | инвестор/банк/компания | G3 | [FIN-WORKSPACES-AND-JOURNEYS.md](FIN-WORKSPACES-AND-JOURNEYS.md) | Module contract / golden/negative E2E | SPECIFIED |
| CON-03 | Investor Workspace | частный инвестор/фонд | G3 | [FIN-WORKSPACES-AND-JOURNEYS.md](FIN-WORKSPACES-AND-JOURNEYS.md) | Module contract / golden/negative E2E | SPECIFIED |
| CON-04 | Bank & Funding Workspace | банк/лизинг/фонд | G3 | [FIN-WORKSPACES-AND-JOURNEYS.md](FIN-WORKSPACES-AND-JOURNEYS.md) | Module contract / golden/negative E2E | SPECIFIED |
| CON-05 | Consulting Company Workspace | консалтинговая компания | G3 | [FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md](FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md) | CONS-01…20 | SPECIFIED |
| CON-06 | Data Room & Due Diligence | владелец/адресаты | G3 | [FIN-WORKSPACES-AND-JOURNEYS.md](FIN-WORKSPACES-AND-JOURNEYS.md) | Module contract / golden/negative E2E | SPECIFIED |
| CON-07 | Project Execution Monitoring | владелец/допущенный инвестор | G4 | [FIN-WORKSPACES-AND-JOURNEYS.md](FIN-WORKSPACES-AND-JOURNEYS.md) | Module contract / golden/negative E2E | SPECIFIED |
| ADM-01 | Trust & Moderation | модератор/представитель | G3 | [ADR-008-FIN-CONNECT-BACKEND-STACK.md](ADR-008-FIN-CONNECT-BACKEND-STACK.md) | Module contract / golden/negative E2E | SPECIFIED |
| ADM-02 | Platform Administration | администратор/DevOps | G3 | [ADR-008-FIN-CONNECT-BACKEND-STACK.md](ADR-008-FIN-CONNECT-BACKEND-STACK.md) | Module contract / golden/negative E2E | SPECIFIED |
| ADM-03 | Audit & Observability | безопасность/QA/оператор | G3→G4 | [ADR-008-FIN-CONNECT-BACKEND-STACK.md](ADR-008-FIN-CONNECT-BACKEND-STACK.md) | Module contract / golden/negative E2E | SPECIFIED |

## 2. Каталог 44 форм и соответствие модулям

| Code | Form | Family | Consumer Module |
|---|---|---|---|
| F01 | Бухгалтерский баланс | F | FND-05/BUS-02/FND-10 |
| F02 | Отчёт о прибылях и убытках | F | FND-05/BUS-02/FND-10 |
| F03 | Отчёт о движении денежных средств | F | FND-05/BUS-02/FND-10 |
| F04 | Отчёт об изменениях капитала | F | FND-05/BUS-02/FND-10 |
| F05 | Примечания и пояснения к отчётности | F | FND-05/BUS-02/FND-10 |
| F06 | Оборотно-сальдовая ведомость (ОСВ) | F | FND-05/BUS-02/FND-10 |
| F07 | Управленческая отчётность | F | FND-05/BUS-02/FND-10 |
| F08 | Консолидированная отчётность | F | FND-05/BUS-02/FND-10 |
| C01 | Дебиторская задолженность | C | FND-05/BUS-02/CON-04 |
| C02 | Кредиторская задолженность | C | FND-05/BUS-02/CON-04 |
| C03 | Товарно-материальные запасы | C | FND-05/BUS-02/CON-04 |
| C04 | Основные средства | C | FND-05/BUS-02/CON-04 |
| C05 | Действующие кредиты и займы | C | FND-05/BUS-02/CON-04 |
| C06 | Обороты по банковским счетам | C | FND-05/BUS-02/CON-04 |
| C07 | Выручка по месяцам | C | FND-05/BUS-02/CON-04 |
| C08 | Себестоимость | C | FND-05/BUS-02/CON-04 |
| C09 | Операционные расходы | C | FND-05/BUS-02/CON-04 |
| C10 | Налоги и задолженность бюджету | C | FND-05/BUS-02/CON-04 |
| C11 | Выданные и полученные авансы | C | FND-05/BUS-02/CON-04 |
| C12 | Крупнейшие покупатели и поставщики | C | FND-05/BUS-02/CON-04 |
| C13 | Забалансовые обязательства | C | FND-05/BUS-02/CON-04 |
| C14 | Связанные стороны и внутригрупповые операции | C | FND-05/BUS-02/CON-04 |
| P01 | Прогнозный баланс | P | FND-05/PRJ-02/PRJ-03 |
| P02 | Прогнозный ОПиУ | P | FND-05/PRJ-02/PRJ-03 |
| P03 | Прогноз движения денежных средств | P | FND-05/PRJ-02/PRJ-03 |
| P04 | График обслуживания долга | P | FND-05/PRJ-02/PRJ-03 |
| P05 | CAPEX-план | P | FND-05/PRJ-02/PRJ-03 |
| P06 | Потребность в оборотном капитале | P | FND-05/PRJ-02/PRJ-03 |
| P07 | Финансовая модель проекта | P | FND-05/PRJ-02/PRJ-03 |
| P08 | Стресс-сценарии | P | FND-05/PRJ-02/PRJ-03 |
| P09 | Прогноз DSCR | P | FND-05/PRJ-02/PRJ-03 |
| P10 | Источники и использование кредита | P | FND-05/PRJ-02/PRJ-03 |
| T01 | ФНО 100.00, декларация по КПН | T | FND-05/BUS-08 |
| T02 | ФНО 200.00, ИПН и социальный налог | T | FND-05/BUS-08 |
| T03 | ФНО 300.00, НДС | T | FND-05/BUS-08 |
| T04 | ФНО 910.00, упрощённая декларация | T | FND-05/BUS-08 |
| D01 | Анкета заёмщика и заявка | D | FND-09/CON-04/CON-06 |
| D02 | Собственники, бенефициары, структура группы | D | FND-09/CON-04/CON-06 |
| D03 | Бизнес-план / ТЭО | D | FND-09/CON-04/CON-06 |
| D04 | Реестр и документы залогов | D | FND-09/CON-04/CON-06 |
| D05 | Договоры с покупателями и поставщиками | D | FND-09/CON-04/CON-06 |
| D06 | Банковские справки / сведения о долгах | D | FND-09/CON-04/CON-06 |
| D07 | Корпоративное решение о кредитовании | D | FND-09/CON-04/CON-06 |
| D08 | Аудиторское заключение | D | FND-09/CON-04/CON-06 |

Формы исходят из [FORM-CATALOG.md](FORM-CATALOG.md). F01 частично реализован в отдельной кодовой ветке, остальные не подтверждены как готовые. Универсальное требование: FormRegistry выбирает по применимости, не требует все 44 от каждого пользователя.

## 3. 11 мастеров и пользовательский сценарий

| Wizard | Name | Persona | Primary source | Status |
|---|---|---|---|---|
| WZ-01 | Профиль бизнеса | любой | [MODULE-CATALOG](MODULE-CATALOG.md) / [WORKSPACES](FIN-WORKSPACES-AND-JOURNEYS.md) | SPECIFIED |
| WZ-02 | Business Diagnosis | предприниматель/директор | [MODULE-CATALOG](MODULE-CATALOG.md) / [WORKSPACES](FIN-WORKSPACES-AND-JOURNEYS.md) | SPECIFIED |
| WZ-03 | Project & Strategy | основатель | [MODULE-CATALOG](MODULE-CATALOG.md) / [WORKSPACES](FIN-WORKSPACES-AND-JOURNEYS.md) | SPECIFIED |
| WZ-04 | Business Plan | основатель/CFO | [MODULE-CATALOG](MODULE-CATALOG.md) / [WORKSPACES](FIN-WORKSPACES-AND-JOURNEYS.md) | SPECIFIED |
| WZ-05 | Investment Scenario | CFO/инвестор | [MODULE-CATALOG](MODULE-CATALOG.md) / [WORKSPACES](FIN-WORKSPACES-AND-JOURNEYS.md) | SPECIFIED |
| WZ-06 | Report Publication | собственник/бухгалтер | [MODULE-CATALOG](MODULE-CATALOG.md) / [WORKSPACES](FIN-WORKSPACES-AND-JOURNEYS.md) | SPECIFIED |
| WZ-07 | Project Publication | владелец проекта | [MODULE-CATALOG](MODULE-CATALOG.md) / [WORKSPACES](FIN-WORKSPACES-AND-JOURNEYS.md) | SPECIFIED |
| WZ-08 | Access & Revoke | владелец/получатель | [MODULE-CATALOG](MODULE-CATALOG.md) / [WORKSPACES](FIN-WORKSPACES-AND-JOURNEYS.md) | SPECIFIED |
| WZ-09 | Consulting Material Request | консалтинг/клиент | [MODULE-CATALOG](MODULE-CATALOG.md) / [WORKSPACES](FIN-WORKSPACES-AND-JOURNEYS.md) | SPECIFIED |
| WZ-10 | Bank/Funding Package | бизнес/банк | [MODULE-CATALOG](MODULE-CATALOG.md) / [WORKSPACES](FIN-WORKSPACES-AND-JOURNEYS.md) | SPECIFIED |
| WZ-11 | Monitoring | владелец/спонсор | [MODULE-CATALOG](MODULE-CATALOG.md) / [WORKSPACES](FIN-WORKSPACES-AND-JOURNEYS.md) | SPECIFIED |

Mapping legacy: MB-WIZ=WZ-02; PR-WIZ=WZ-03/04; PUB-WIZ=WZ-07; REP-WIZ=WZ-06; CONS-REQ=WZ-09; Wizard-ACCESS=WZ-08. Ранний WIZ-001…006 — шаги первоначального интервью, не самостоятельные модули.

## 4. 20 ролей и граница организации

| Role | Persona | User story | Source |
|---|---|---|---|
| R01 PROJECT_FOUNDER | предприниматель/автор идеи | U-R01 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R02 BUSINESS_OWNER | владелец ИП/ТОО | U-R02 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R03 COMPANY_MANAGER | директор/операционный менеджер | U-R03 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R04 ACCOUNTANT | бухгалтер/финконтролёр | U-R04 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R05 FINANCIAL_ANALYST | CFO / финансовый аналитик | U-R05 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R06 MARKETING_MANAGER | маркетолог/директор продаж | U-R06 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R07 OPERATIONS_MANAGER | производство, логистика, закупки | U-R07 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R08 EXTERNAL_ADVISOR | консультант/бухгалтерская фирма | U-R08 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R09 PRIVATE_INVESTOR | частный инвестор | U-R09 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R10 INSTITUTIONAL_INVESTOR | фонд / PE / VC | U-R10 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R11 BANK_ANALYST | банк, кредитный аналитик | U-R11 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R12 BANK_RISK_APPROVER | банковский риск-менеджер/комитет | U-R12 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R13 FUNDING_PROVIDER | лизинговая, грантовая организация/фонд | U-R13 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R14 STRATEGIC_PARTNER | компания, франчайзи, поставщик ресурсов | U-R14 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R15 EXECUTION_SPONSOR | инвестор/спонсор мониторинга | U-R15 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R16 ORG_ADMIN | уполномоченный администратор организации | U-R16 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R17 COMPLIANCE_MODERATOR | модератор платформы, compliance | U-R17 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R18 PLATFORM_ADMIN | оператор FIN | U-R18 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R19 METHODOLOGY_EDITOR | экономист/методолог отраслевых моделей | U-R19 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |
| R20 INDEPENDENT_REVIEWER | независимый S2/auditor/quality reviewer | U-R20 | [ROLE-SCENARIOS](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) |

Внешний консультант R08 и ConsultingOrganization — разные сущности. CONSULTANT ORG содержит несколько сотрудников и `Engagement`; отдельные политики доступа: [CONSULTING](FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md).

## 5. 12 экономических направлений и module mapping

| Legacy direction | Name | Logical module |
|---|---|---|
| FUND | Funding & Capital Raising | PRJ-07 |
| MARKET | FIN Project Marketplace | CON-01/CON-02 |
| VAL | Business Valuation | PRJ-04 |
| SALES | Sales & Commercial Analytics | BUS-03 |
| SUP | Procurement & Supply Chain | BUS-05 |
| HR | HR & Workforce Economics | BUS-07 |
| TAX | Tax & Regulatory Planning | BUS-08 |
| STRAT | Strategic Planning | PRJ-05 |
| RISK | Risk Management | PRJ-06 |
| PARTNER | Partnerships & Franchising | PRJ-08 |
| EXEC | Project Execution & Monitoring | CON-07 |
| ESG | Sustainability & Resource Economics | BUS-09 |

`MARKET` — исторический alias Project Discovery. Методологическая квалификация зависит от источника, метода, отрасли, страны и даты.

## 6. Зоны 12

| Zone | Description | Example | Default |
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

## 7. Suites: сохранение идентификаторов и обязательные E2E

| Suite | Количество | Source | Текущий статус |
|---|---:|---|---|
| ZONE-01…36 | 36 | [ADR-009](ADR-009-REPORT-ACCESS-ZONES.md) | NOT RUN |
| CONS-01…20 | 20 | [Consulting](FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md) | NOT RUN |
| BACK-01…15 | 15 | [ADR-008](ADR-008-FIN-CONNECT-BACKEND-STACK.md) | NOT RUN |
| MKT-01…09 | 9 | [ADR-007](ADR-007-FIN-PROJECT-MARKETPLACE.md) | NOT RUN |
| REPORT-01…12 | 12 | [Report Center](FIN-FINANCIAL-REPORT-PUBLICATION.md) | NOT RUN |
| ROLE-01…20 | 20 | [Role scenarios](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md) | NOT RUN |

Обязательные cross-context проверки: S0/S1/S2 → diagnosis → business plan; profit≠cash; loan≠income; inventory purchase≠COGS; fact≠forecast; same revision→same numbers in HTML/PDF/XLSX; report publication ≠ project publication; lost Keycloak membership / stale Redis deny; concurrent one-use exactly 1; KumoMTA outbox retry; consulting partial response; international method with insufficient coverage.

### 7.1. FIN-BH и трёхъязычный Metric Registry — трассировка дополнений

| Требование | Нормативный источник | Доказательство реализации | Текущий статус |
|---|---|---|---|
| CORE-15 — диагностика ИП | [FIN Business Health](FIN-BUSINESS-HEALTH.md) | BH-T01…BH-T12; E2E Express → Full → actions | SPECIFIED / NOT RUN |
| CORE-16 — единые KPI + ru/kk/en | [Indicator Dictionary](FIN-INDICATOR-DICTIONARY-RU-KK-EN.md) | MET-01…MET-12, проверка 27 кодов × 3 локали | SPECIFIED / NOT RUN |
| CORE-17 — доказуемость выводов | [Business Health](FIN-BUSINESS-HEALTH.md) | BH-T04/BH-T08/BH-T09, no universal score | SPECIFIED / NOT RUN |
| CORE-18 — withdrawals/scenario/private | [Business Health](FIN-BUSINESS-HEALTH.md), [ADR-009](ADR-009-REPORT-ACCESS-ZONES.md) | BH-T05/BH-T07/BH-T10/BH-T11 и ZONE suites | SPECIFIED / NOT RUN |

**Оговорка:** запись в RTM доказывает только документное покрытие, **не** запуск автотестов, переводы в React, миграции или готовую диагностику.

## 8. Состояние и остаточная работа

- [x] Уникальные идентификаторы всех 39 модулей.
- [x] Уникальные коды всех 44 форм.
- [x] 11 WZ-IDs, 20 R-IDs, 12 Z-IDs, 12 economic directions.
- [x] Crosswalk старых и новых названий.
- [x] 36 ZONE и 20 CONS требований учтены.
- [ ] Schema/DTO, contract tests и migrations по каждому модулю.
- [ ] Real E2E / CI evidence, независимый S2, подтверждённая готовность функционала.
- [ ] Требования к R0 hosted/legal/MFA/tenant strategy верифицированы независимым review.

**Отметка [x] фиксирует лишь сохранность требований на бумаге.**
