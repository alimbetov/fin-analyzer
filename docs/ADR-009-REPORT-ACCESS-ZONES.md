# ADR-009 — Зоны доступа к отчётности, временный, контрактный, одноразовый доступ и отзыв

**Дата:** 2026-11-15  
**Статус:** ACCEPTED как целевой продуктовый и технический контракт / NOT IMPLEMENTED  
**Продукт:** FIN Project Marketplace, включая FIN Analyzer, Report Center, «Мой бизнес», «Мой проект» и FIN Connect  
**Связанные документы:** [ADR-007](ADR-007-FIN-PROJECT-MARKETPLACE.md), [ADR-008](ADR-008-FIN-CONNECT-BACKEND-STACK.md), [публикация отчётов](FIN-FINANCIAL-REPORT-PUBLICATION.md), [консалтинг](FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md).

## 1. Цель и принципы

Компания управляет раскрытием **отдельных зон, отчетов, разделов, полей, периодов, файлов и версий** инвестору, банку, консультанту, партнеру или сотруднику. Доступ назначается адресно: recipient organization + named user(s), purpose, allowed actions, срок, условия сотрудничества и максимальное число использований. Выдача одной зоны не открывает остальные.

Значение VALIDATED означает проверку по правилам FIN, **не** юридически заверенный или аудированный документ. Публикация инвестиционного паспорта не публикует финансовые отчёты; выдача одной версии отчёта не предоставляет право на будущие версии. Keycloak login не даёт доступа к данным чужой компании.

**Ограничения:** запрет скачать не предотвращает скриншоты/фотографии/ручное копирование. Отзыв прекращает новую выдачу данных, но не удаляет уже полученные копии и байты. Интерфейс обязан предупреждать об этих ограничениях.

## 2. Зоны доступа к данным

| ID | Зона | Примеры | Default |
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

ZoneDefinition — **логическая политика**, не отдельная PostgreSQL БД, Keycloak realm или RustFS bucket. Если один документ смешивает классы, backend фильтрует каждый section/field по наиболее строгому требованию. Raw JSON/PDF/XLSX, hidden worksheets, PDF metadata, internal comments и изображения не должны обходить filtered projection.

## 3. Субъект, действия и granular scope

\`AccessGrant\` однозначно включает: ownerOrg, recipientOrg, namedPrincipal или проверенную recipient-group policy, purpose, classification, resourceId + immutable revisionId, form/section/metric/field allowlist, date period, allowedActions, time limits, engagementId и approval evidence. Возможные действия: VIEW_SUMMARY, VIEW_DETAIL, COMMENT, REQUEST_MATERIAL, DOWNLOAD, EXPORT, REVIEW, PROPOSE_CHANGE. GRANT/REVOKE/APPROVE_PUBLICATION — отдельные owner полномочия. DOWNLOAD **не** подразумевается из VIEW.

**Обязательная формула доступа:** authentication ∩ current membership ∩ owner/recipient organization ∩ legal-representative approval ∩ grant ∩ zone policy ∩ immutable resource revision ∩ specific action ∩ time window ∩ engagement state. Если любое условие не доказано — DENY. Смена активной организации не объединяет права.

Примеры: банк — только одобренные формы, периоды и документы; инвестор — teaser и выбранный финансовый раздел; консультант — только active accepted Engagement и отдельные item-level grants; партнёр — только свои проекты и операционные сведения, без банковских/HR данных.

## 4. Режимы доступа

| Mode | Семантика |
|---|---|
| FIXED_WINDOW | с validFrom включительно до expiresAt исключительно по серверному UTC |
| UNTIL_REVOKED | до явного отзыва с отдельным informed approval владельца |
| ENGAGEMENT_BOUND | только пока accepted Engagement находится в ACTIVE и не завершился |
| CONTRACT_WINDOW | до подтверждённой локально границы внешнего договорного срока; без автоматического продления |
| SINGLE_USE_VIEW | ровно одна авторизованная краткосрочная session/lease просмотра |
| SINGLE_USE_DOWNLOAD | ровно один **admission** к отправке файла; право расходуется до первых байтов |
| LIMITED_USES | N атомарно учтённых admissions к выбранной операции |
| MANUAL_APPROVAL | заявка ожидает разрешения владельца или второго approver |

Для совместной выдачи режимов effective expiry = минимальная из всех **обязательных** дат; если обязательная дата отсутствует — DENY, а не бесконечный доступ. Внешний контракт хранится как ExternalAgreementReference, его заключение и платежи вне FIN. Продление требует новой revision и approval, не происходит из-за автоматически изменённой даты договора.

Одноразовый режим **не равен невозможности сделать копию**. \`SINGLE_USE_VIEW\` атомарно выдаёт \`AccessLease\`, связанную с principalId + BFF session + reportRevision + TTL и доступную для внутренних viewer-chunk requests только этой сессии. Вторая параллельная сессия получает DENY. При \`SINGLE_USE_DOWNLOAD\` право расходуется непосредственно перед стримом; если сеть прервётся, новая выдача потребует нового grant. Для N uses применяется PostgreSQL atomic UPDATE/row lock, а не Redis счетчик. Повторные HTTP Range/download запросы не должны возрождать использованное право.

После REVOKE: новые запросы/lease DENY немедленно, in-flight streams закрываются на ближайшей контролируемой границе отправки; уже переданные данные возвратить нельзя. \`Cache-Control: no-store\`, запрет private CDN, прямых presigned S3 URLs и браузерного доступа к RustFS.

## 5. Мастер доступа и dashboards

**Wizard-ACCESS:**
1. Выбрать «Мой бизнес»/«Мой проект», отчёт/форму/документ, период и конкретную версию.
2. Выбрать получателя — verified organization, bank, investor, consultant with Engagement, partner — и назначенных сотрудников.
3. Указать цель: кредитный анализ, due diligence, консультация, совместный проект.
4. Выбрать зоны/поля, провести redaction, выбрать VIEW/COMMENT/DOWNLOAD/EXPORT.
5. Выбрать ограничение срока и использования: часы/дни, до конца Engagement, один просмотр, один download, N использований, до отзыва.
6. Для чувствительных данных — отдельное подтверждение/четыре глаза (конфигурируемое).
7. Показать **точный preview глазами получателя** включая metadata в HTML/PDF/XLSX, затем явное approve.
8. Создать grant/revision/audit/outbox atomically в FIN PostgreSQL; отправить KumoMTA уведомление с ссылкой.
9. Управление: увидеть просматривавших, оставшееся число использований, expiry; REVOKE, SUSPEND, re-grant only after approval.

**Владелец видит:** «Открытые доступы», «Ожидают моего решения», «Истекают», «Одноразовые», «История просмотров», «Отозванные», «Срочно закрыть все доступы». Каждая запись: recipientOrg/principal, zones, forms, period, revision, actions, expiry, purpose, uses, audit.

**Получатель видит:** «Мне предоставлено», «Запрошено», «Одноразовый доступ», «Истекает», «Отозвано». Предупреждение перед расходом one-use; возможность запросить re-grant, но без автоматического одобрения.

## 6. Сущности и backend

**FIN PostgreSQL**: ZoneDefinition, ResourceClassification, DisclosureProjection, AccessRequest/RequestItem, AccessGrant, AccessLease, ApprovalRecord, Engagement, AccessAuditEvent, ResourceRevision, NotificationIntent, OutboxEvent. Несколько организаций и версии прав учитываются в tenant-scoped запросах.

**Keycloak**: Authentication via Spring Boot OAuth2 BFF; JWT/session не заменяют FIN organization permissions. Keycloak DB отдельна от FIN DB. **Redis**: server-side Spring Session/rate limiting/cache, не authoritative permissions. **RustFS**: только private object bytes с upload quarantine и версиями; HTML/PDF/download через Spring Boot с перезапросом разрешения. **KumoMTA**: только server-side notifications, PostgreSQL transactional Outbox; успех SMTP не равен получению письма.

Серверный \`AuthorizationDecisionService\` применяется к search/list/detail/preview/export/download и stream chunks. Примерные будущие endpoints: \`/api/access-requests\`, \`/api/access-grants\`, \`/api/access-grants/{id}/revoke\`, \`/api/access-grants/{id}/leases\`, \`/api/reports/{id}/sections/{sectionId}\`, \`/api/documents/{id}/content\`. Не считать примеры реализованным API.

## 7. Lifecycle

\`\`\`text
AccessRequest DRAFT -> SUBMITTED -> UNDER_REVIEW -> APPROVED | PARTIALLY_APPROVED | DENIED
AccessGrant   PENDING -> SCHEDULED -> ACTIVE -> EXPIRED | REVOKED | CONSUMED
AccessLease   ISSUED -> ACTIVE -> CONSUMED | EXPIRED | REVOKED
Engagement    REQUESTED -> ACCEPTED -> ACTIVE -> SUSPENDED | COMPLETED | EXPIRED | REVOKED
\`\`\`

Grant/lease approvals carry actor, ownerOrg, recipientOrg, resourceId, revision, status, securityRevision and timestamps, with audit and optimistic concurrency. Замена опубликованного отчёта не открывает новую версию по старому grant.

## 8. Acceptance tests (PLANNED, NOT RUN)

| ID | PASS |
|---|---|
| ZONE-01 | Банк получает только разрешённые формы, периоды и field allowlist |
| ZONE-02 | Инвестор не видит Z30/Z80/Z95 при публичном teaser |
| ZONE-03 | Консультант без accepted Engagement не может читать private reports |
| ZONE-04 | Партнёр видит только свой project scope |
| ZONE-05 | Raw JSON, hidden Excel sheets, PDF metadata не обходят disclosure projection |
| ZONE-06 | Срок до начала, во время и после окна, разные часовые пояса |
| ZONE-07 | При завершении/отзыве Engagement новые обращения DENY |
| ZONE-08 | Внешний контракт не продлевает grant автоматически |
| ZONE-09 | Два одновременных SINGLE_USE_VIEW → ровно одна lease |
| ZONE-10 | SINGLE_USE_DOWNLOAD consumed до отправки; обрыв сети требует re-grant |
| ZONE-11 | LIMITED_USES=N в конкуренции не превышает N |
| ZONE-12 | VIEW_ONLY не даёт DOWNLOAD/EXPORT API; screenshots limitations раскрыты |
| ZONE-13 | Emergency revoke закрывает новые запросы при валидном JWT/session |
| ZONE-14 | Отзыв не обещает удаления ранее скачанных файлов |
| ZONE-15 | Старый grant не открывает следующую report revision |
| ZONE-16 | Две организации и switch organization не объединяют permissions |
| ZONE-17 | IDOR / guessed URL / cross-tenant search deny without existence leak |
| ZONE-18 | Browser не получает RustFS keys или direct S3 URLs |
| ZONE-19 | Redis outage/stale cache не возвращает revoked permissions |
| ZONE-20 | HTTP Range, malicious uploads, nested exports не обходят grant |
| ZONE-21 | Bank/investor grants независимы, notes остаются private |
| ZONE-22 | HIGHLY_RESTRICTED требует second approver, если включён policy |
| ZONE-23 | Неуполномоченный представитель компании не может GRANT |
| ZONE-24 | KumoMTA failure не отменяет grant/revoke, Outbox durable |
| ZONE-25 | Audit содержит actor/org/resource/action/revision/decision без payload |
| ZONE-26 | Project listing не публикует private financial report |
| ZONE-27 | Withdraw report закрывает новые requests, CDN no-store |
| ZONE-28 | Исправленная версия не раскрывается получателю автоматически |
| ZONE-29 | Z99 secrets cannot be shared by any user |
| ZONE-30 | Replay anonymous invite does not bypass login |
| ZONE-31 | Revoke during large stream stops future chunks where enforceable |
| ZONE-32 | HTML/PDF/XLSX одного snapshot имеют одинаковый разрешённый scope |
| ZONE-33 | Engagement expiry блокирует grants даже при упавшем scheduler |
| ZONE-34 | Старый Keycloak session не создаёт новую lease после revocation |
| ZONE-35 | Removal from recipientOrg немедленно блокирует дальнейшие открытия |
| ZONE-36 | Renewal/re-grant требует explicit approval и новой revision |

**Поставка:** ACCESS-R0 зоны и projections + RED tests; ACCESS-R1 BFF/Keycloak/PostgreSQL/Redis и FIXED_WINDOW/revoke; ACCESS-R2 RustFS grants/KumoMTA outbox; ACCESS-R3 Engagement/Contract/one-time/concurrency; ACCESS-R4 enterprise auditing. Приёмка требует реального environment, двух организаций, негативных Playwright/E2E и независимого security review. В текущем FIN-R1A ничего из этого не реализовано.
