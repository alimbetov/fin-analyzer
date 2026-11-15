# FIN Analyzer — Консалтинговая компания и запросы дополнительных материалов

**Functional Specification:** CONSULT-R0 v1.0 · **Дата:** 2026-11-15  
**Статус:** REQUIREMENTS / NOT IMPLEMENTED  
**Связанные:** [Рабочие пространства](FIN-WORKSPACES-AND-JOURNEYS.md), [Публикация финансовых отчётов](FIN-FINANCIAL-REPORT-PUBLICATION.md), [ADR-007](ADR-007-FIN-PROJECT-MARKETPLACE.md), [ADR-008](ADR-008-FIN-CONNECT-BACKEND-STACK.md), [Общий реестр ролей R01–R20](ROLE-SCENARIOS-AND-PRODUCT-STRATEGY.md).

## 1. Роль и ценность

**CONSULTING_COMPANY — самостоятельная организация FIN Connect**, а не просто пользователь с ролью \`EXTERNAL_ADVISOR\` (R08). Консалтинговая фирма содержит нескольких уполномоченных сотрудников и собственный клиентский портфель. Она может **запрашивать у другой компании дополнительные материалы, помимо форм финансовой отчётности**, готовить экономический/операционный/маркетинговый анализ, инвестиционное заключение и рекомендации на основе предоставленной информации.

**Пользовательская ценность:**

| Участник | Потребность | Что делает FIN |
|---|---|---|
| Консалтинговая компания | собрать недостающие документы по проекту/заказу | структурированный due diligence checklist, запросы, шаблоны, назначения, контроль полноты и сроков |
| Руководитель консалтинга | распределить работу по аналитикам и контролировать сроки | engagements, assignments, SLA, очереди, причины задержек |
| Аналитик консалтинга | получить конкретные данные, не требуя весь бизнес-план | предметный RequestItem + scoped grant + evidence register |
| Проверяющий консалтинга | обеспечить независимую внутреннюю проверку | работа только с разрешённой версией источников, замечания и approvals |
| Компания-получатель запроса | решить, что предоставить, что скрыть, что отклонить | входящий реестр, объяснение цели, согласование по каждому документу, redaction, отзыв прав |
| CFO/бухгалтер компании | собрать корректные сведения, не нарушая конфиденциальность | назначения, версии, выгрузки и сохранение источников |
| Владелец компании | понимать, кому и зачем раскрыты сведения | аудит получателей, сроки, история согласий, закрытие сотрудничества |

Нельзя представлять любой материал как подтверждённый независимым аудитом только потому, что его запросил/прочитал консультант. Отчёт консультанта — отдельный продукт с собственным scope, методикой, источниками и статусом \`DRAFT/REVIEWED/SHARED\`.

## 2. Два допустимых способа взаимодействия

**CONSULT-FLOW-A — компания сама привлекает консультанта.**
1. Компания инициирует приглашение выбранной консалтинговой организации.
2. Уточняет предмет, проект/отчёт, полномочия, сроки и scope.
3. Консультант принимает приглашение, назначает руководителя и сотрудников.
4. Сотрудники отправляют дополнительный перечень материалов в рамках мандата; компания решает по каждому.
5. Консультант готовит заключение, передаёт выбранную версию компании, закрывает работу.

**CONSULT-FLOW-B — консультант обращается к другой компании.**
1. Консалтинговая организация может отправить **только запрос на установление сотрудничества** с указанием цели и своей организации. Никакого доступа к закрытым данным по факту отправки.
2. Представитель компании принимает либо отклоняет; отказ блокирует дальнейшие запросы материалов, без публичного рейтинга.
3. После принятия и подтверждения \`Engagement\` действует CONSULT-FLOW-A. Без явного engagement документы, сведения о сотрудниках и private financial facts запрашивать через Data Room нельзя.
4. Антиспам: лимиты попыток, block/report, запрет массового поиска личных контактных данных, журнал согласия и инициирования.

**Никакого автоматического доступа** на основании Keycloak login, публикации проекта, обращения консультанта или наличия форм отчётности в FIN. Наличие NDA/договора само по себе не заменяет серверные ACL. Договоры, оплата консультанта и юридически обязательные действия выполняются вне FIN.

## 3. Каталог дополнительных материалов (помимо F01–F08)

| Код | Категория | Примеры | Режим по умолчанию |
|---|---|---|---|
| DOC-LEGAL | Юридические и корпоративные | регистрационные документы, лицензии, разрешения, корпоративная структура, полномочия | PRIVATE / REQUESTED |
| DOC-CONTRACT | Договоры и обязательства | поставка, аренда, субподряд, лизинг, условия гарантий | PRIVATE / REQUESTED |
| DOC-MARKET | Коммерческие материалы | коммерческие предложения, прайс-листы, данные о конкурентах, исследование рынка | PRIVATE / REQUESTED |
| DOC-SALES | Продажи и клиенты | обезличенные продажи по SKU/сегментам, воронка, возвраты, скидки, контракты | PRIVATE / REQUESTED |
| DOC-OPERATIONS | Производство и процессы | спецификации, BOM, нормы сырья, технологические карты, мощности, загрузка | HIGHLY_SENSITIVE |
| DOC-ASSET | Оборудование и активы | инвентарные реестры, акты, фотографии, оценка, техническое состояние | PRIVATE / REQUESTED |
| DOC-LOGISTICS | Логистика и снабжение | маршруты, складские остатки, договоры перевозки, lead time | PRIVATE / REQUESTED |
| DOC-HR | Персонал | агрегированный ФОТ, штатное расписание, квалификация, часы | HIGHLY_SENSITIVE; PII минимизировать |
| DOC-TAX | Налоговые документы | декларации, сверки, налоговые платежи и применимость режимов | HIGHLY_SENSITIVE |
| DOC-BANK | Банковские и долговые | обороты, графики обслуживания кредита, письмо банка, ковенанты | HIGHLY_SENSITIVE; счета маскировать |
| DOC-INVEST | Инвестиции/проект | CAPEX, сметы, бизнес-план, feasibility, разрешения строительства, этапы | PRIVATE / REQUESTED |
| DOC-ESG | Ресурсы, безопасность и устойчивость | энергопотребление, вода, отходы, экологические и технические заключения | PRIVATE / REQUESTED |
| DOC-OTHER | Произвольный запрос | пользовательская форма/файл/пояснение с обязательной целью и типом | PRIVATE / REQUESTED |

**Запреты и предосторожности:** не требовать чужие персональные данные без соответствующего основания; PII, банковские реквизиты, коммерческая тайна и технологии — отдельные чувствительные поля. Передача некоторых материалов может быть запрещена законом/контрактом; FIN не должен вынуждать компанию предоставить то, что она не вправе раскрывать.

## 4. Кабинет консалтинговой компании (WS-CONSULT)

Навигация: **Клиенты / Предложения сотрудничества / Активные engagements / Запросы материалов / Полученные документы / На проверке / Замечания / Отчёты и заключения / Команда / Аудит**.

Рабочая главная страница:
- «Ожидают ответа компании», «Материалы получены», «Нужна проверка», «Просрочены», «Заблокированы из-за отсутствующих сведений»;
- таблица проектов: компания (только если сотрудничество подтверждено), задача, ответственный, дата запроса, dueDate, прогресс по согласованным **обязательным** пунктам, дата последнего доступа;
- кнопки «Создать перечень материалов», «Отправить запрос», «Запросить уточнение», «Сформировать анализ», «Подготовить заключение»;
- шаблоны запросов по типу анализа (финансовый, инвестиционный, маркетинговый, операционный, юридический обзор, подготовка бизнес-плана), но без автоматического требования всех документов;
- внутренние private notes и reviewers консалтинговой компании не видны клиенту, если не опубликованы адресно.

**Сотрудники консультанта:** \`CONSULT_ORG_ADMIN, ENGAGEMENT_LEAD, CONSULT_ANALYST, CONSULT_REVIEWER\`. Организация имеет одного или нескольких пользователей; scope разрешений по engagement+document+role, а не глобальный \`ROLE_CONSULTANT\`.

## 5. Кабинет компании-получателя запроса (WS-BUSINESS → Materials Inbox)

Навигация: **Запросы консультантов / Мои задачи / Подготовить материал / Согласовать выдачу / История доступа / Ограничения / Отчёты консультантов**.

Карточка запроса показывает:
- проверенное название консалтинговой организации (или уровень проверки), ответственных, purpose, engagementId, проект и документ, запрашиваемый период, dueDate, **почему нужен каждый пункт**;
- текущее состояние каждого RequestItem, разрешённую классификацию, возможность приложить существующую форму FIN или загрузить новый файл в RustFS;
- действия \`PREPARE / REDACT / GRANT / REQUEST_CLARIFICATION / NOT_AVAILABLE / DECLINE / REVOKE\`, комментарий, разрешённый срок, уведомление получателя;
- подтверждение «ровно эти файлы и эти версии увидит конкретная организация»; запрос отчётности сам по себе не даёт права читать иные финансовые или проектные документы.

**Four-eyes policy:** для чувствительных DOC-HR/DOC-BANK/DOC-LEGAL и S3/S4 может требоваться раздельная подготовка и подтверждение (конфигурация организации). Роль бухгалтер/финансовый директор может подготовить файл, но финальный \`GRANT\` требует права publish/share, не следует автоматически из возможности загрузки.

## 6. Request Wizard — CONS-REQ

| Step | Пользовательский вопрос | Результат |
|---|---|---|
| REQ-01 | «С какой компанией и по какому проекту сотрудничаем?» | accepted Engagement, verified org context и target scope |
| REQ-02 | «Какова цель анализа?» | financial DD / business plan / operations / market / strategy / custom |
| REQ-03 | «Какие сведения уже есть?» | применимые F/B/C/P формы и уже разрешённые evidence без повторного запроса |
| REQ-04 | «Каких материалов не хватает?» | динамический список RequestItems: файл/текст/таблица/пояснение, expected format |
| REQ-05 | «За какой период и насколько срочно?» | period, currency/unit, dueDate, priority, optional/required и purpose per item |
| REQ-06 | «Кто может видеть полученные материалы?» | назначенные аналитики и reviewers, срок доступа, sensitivity, default-deny |
| REQ-07 | «Проверим перед отправкой» | preview полного перечня, исключение запрещённых требований, подтверждение отправки |
| REQ-08 | «Что делать после ответа?» | completeness review, clarification/rework, evidence links, consultant report |

**Конструктор кастомных форм:** RequestItemType \`FILE / TEXT / NUMBER / DATE / TABLE / EXPLANATION / EXISTING_REPORT_REFERENCE\`, валидация по схеме, версии шаблона и требуемым единицам. Непредоставленный \`required\` не становится нулём и не блокирует право компании отказать; консультант видит \`UNAVAILABLE/DECLINED\`, а анализ — \`NOT_COMPUTABLE\` при нехватке операндов.

**Зоны и срочный доступ:** для каждого `ProvidedEvidenceRevision` — отдельная выдача `AccessGrant`, ограниченная конкретной зоной/периодом/версией и действиями. Engagement может ограничивать expiry, one-time VIEW/DOWNLOAD и revoke. Изменение или завершение внешнего контракта не расширяет доступ автоматически. [ADR-009](ADR-009-REPORT-ACCESS-ZONES.md).

## 7. Раздельные жизненные циклы

\`\`\`text
Engagement:
 REQUESTED -> ACCEPTED -> ACTIVE -> SUSPENDED | COMPLETED | REVOKED | EXPIRED
 REQUESTED -> DECLINED | WITHDRAWN

MaterialRequest:
 DRAFT -> SENT -> ACKNOWLEDGED -> IN_PROGRESS
 IN_PROGRESS -> PARTIALLY_FULFILLED | FULFILLED | NEEDS_CLARIFICATION | DECLINED
 PARTIALLY_FULFILLED -> IN_PROGRESS | FULFILLED | CLOSED
 FULFILLED -> REVIEWED -> CLOSED
 SENT/ACKNOWLEDGED/IN_PROGRESS -> CANCELLED | EXPIRED

RequestItem:
 REQUESTED -> PREPARING -> SUBMITTED -> REVIEW_PENDING
 REVIEW_PENDING -> ACCEPTED | NEEDS_REWORK | REJECTED
 REQUESTED/PREPARING -> NOT_AVAILABLE | DECLINED

DocumentGrant:
 REQUESTED -> APPROVED -> ACTIVE -> REVOKED | EXPIRED
 REQUESTED -> DENIED
\`\`\`

\`ENGAGEMENT_REVOKED\` прекращает новые обращения и доступы сразу; \`REPORT_SHARED\` не равен тому, что консультант стал официальным независимым аудитором. Изменение файла образует новую version, не переписывает ранее просмотренную.

Каждое изменение состояния: \`actor, actorOrganization, targetOrganization, engagementId, targetType, targetId, expectedVersion, reason, time, auditEventId\`. Оптимистическая конкуренция и idempotencyKey защищают от дублирования запросов/выдач при двойном клике.

## 8. Backend data model и интеграции

**PostgreSQL FIN** — источник истины: \`ConsultingOrganization\` как тип \`Organization\` (не отдельная БД на каждую фирму), \`Engagement\`, \`EngagementMember\`, \`MaterialRequest\`, \`RequestItem\`, \`RequestedEvidence\`, \`ProvidedEvidenceRevision\`, \`DocumentGrant\`, \`ReviewFinding\`, \`ConsultingReportSnapshot\`, \`AuditEvent\`, \`NotificationIntent\`, \`OutboxEvent\`. Multitenancy: issuerOrg vs targetOrg vs ownerOrg, consent, response versions, status/expiry.

**Keycloak** — login и identity, отдельная PostgreSQL БД, Spring Boot BFF OIDC; сам факт JWT/участия в организации не даёт разрешения на чужой документ. Два независимых \`OrganizationMembership\` проверяются по нужному контексту и engagement permissions.

**RustFS** — private buckets, quarantine, encryption/access controls, checksum, object key и версии. Все uploads/downloads через Spring Boot (no presigned browser URLs/credentials по ADR-008). Malware scanning, file size/type limits, path traversal и IDOR protection.

**Redis** — server sessions, rate limiting, short-lived caches, но **не** persistent grants, requests или audit. Invalidated membership/grant проверяется по PostgreSQL version/authoritative state на каждом защищённом доступе.

**KumoMTA** — уведомления через PostgreSQL Outbox worker: request sent, accepted/declined, material granted, needs clarification, due reminder, access revoked. Письма не содержат самих конфиденциальных файлов, а только ссылку на FIN с server-side access check. SMTP accepted ≠ inbox delivery; bounce/deferrals audit and retry.

**React** — кабинеты и формы; не получает OAuth токены, S3 credentials или приватные metadata без server-side scope.

## 9. Отчёт консультанта и работа движка интерпретатора

После получения согласованной версии доказательств консалтинговая компания может:
1. сопоставить report snapshots и отдельные документы с \`EvidenceRecord\`;
2. запустить утверждённые \`MethodologyVersion\` расчётов/интерпретатора;
3. вывести \`ObservedFinding\` и отдельно \`Hypothesis/UnverifiedClaim\`;
4. указать \`MissingData\` и \`RequestItem\` для следующего полезного уточнения;
5. сформировать \`ConsultingReportSnapshot\` с scope/источниками/ограничениями;
6. отправить владельцу только явно разрешённый финальный отчёт. Доступ к финальному файлу регулируется отдельно от доступа консультанта к исходникам.

**Не смешивать:** internal review консультанта, формально независимый аудит, банковское решение и подтверждённое юридическое заключение. Статус APPROVED в FIN не является внешней юридической сертификацией.

## 10. Acceptance / Security E2E

| ID | Проверка |
|---|---|
| CONS-01 | ConsultingOrg может создать профиль компании и назначить нескольких сотрудников; внутренние notes/assignment изолированы |
| CONS-02 | Без accepted Engagement консультант не может запрашивать private документы или видеть F/B/C финансовые факты другой компании |
| CONS-03 | Компания может отклонить unsolicited connection; повторный spam rate-limited и blocked |
| CONS-04 | Accepted Engagement ограничивает project + purpose + expiry и при необходимости группы документов |
| CONS-05 | Запрос с FILE/TABLE/TEXT/EXISTING_REPORT_REFERENCE корректно маршрутизируется, сохраняет период/формат/приоритет |
| CONS-06 | Компания может предоставить часть документов, отклонить другие и запросить пояснения; это не блокирует общий UX |
| CONS-07 | Консультант видит только выбранные версии файлов и лишь в пределах grant; чужой tenant/document ID даёт deny |
| CONS-08 | Membership/grant/engagement revoked во время активной Keycloak session → последующие download и API недоступны |
| CONS-09 | Изменение/повторная загрузка файла создаёт новую version; audit связывает старую и новую |
| CONS-10 | RustFS private object не отдаётся напрямую браузеру; вредоносный файл задерживается в quarantine |
| CONS-11 | Transaction + Outbox сохраняются атомарно; crash/retry KumoMTA не дублирует бизнес-запрос и не теряет журнал |
| CONS-12 | Два консультанта из разных организаций не видят запросы/заметки/материалы друг друга |
| CONS-13 | Missing/declined evidence не превращается в 0 или автоматически подтверждённый факт; диагностика показывает ограничения |
| CONS-14 | Консультант не может публиковать проект/финансовый отчёт клиента без отдельного approval и disclosure rights |
| CONS-15 | Public ProjectListing не даёт прав на полный отчёт, внутренние договоры или коммерческую тайну |
| CONS-16 | Компания видит кто и когда получил/скачал документ; revoke перекрывает новые обращения, но не уже скачанные копии |
| CONS-17 | В случае устаревшей сессии, потери Redis, ошибки RustFS и callback failures доступ fail-closed, задачи восстанавливаются |
| CONS-18 | Консультант может сформировать источник-обоснованное заключение, которое не маркируется независимым аудитом без доказательств |
| CONS-19 | Internal/company approval, консультантский review и подтверждённые источники не смешиваются в единый TRUSTED status |
| CONS-20 | Positive E2E: Client invites Consultant → Consultant requests five heterogeneous items → Client grants two, declines one, clarifies one, marks one unavailable → Analyst reviews and produces scoped report → Client revokes access |

**Release gate:** реальные PostgreSQL, Keycloak, Redis, RustFS и KumoMTA на staging (mock отдельно маркирован), negative E2E для двух independent organizations, security/S2 review. Отчёт об испытаниях указывает commit SHA, тесты, среды и остаточные дефекты. Сценарии пока только специфицированы.

## 11. Очерёдность

1. **CONS-R0** — каталог материалов и RequestItem schema, UI-макеты, связи с «Моим бизнесом», «Моим проектом» и published financial reports; без пользовательского сетевого доступа.
2. **CONS-R1** — Spring Boot, PostgreSQL/Keycloak/Redis, organization membership, accepted engagement и Request Wizard.
3. **CONS-R2** — RustFS storage, гранулярная выдача/отзыв, проверки файлов, KumoMTA outbox notifications.
4. **CONS-R3** — review/queries/consulting report, auditable access, E2E UAT.

**Соблюдать ADR-007:** FIN не получает/не переводит деньги и не исполняет финансовые сделки. Оплата консалтинга и договор с клиентом — вне FIN.
