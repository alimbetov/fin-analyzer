# FIN Analyzer — самостоятельная публикация финансовых отчётов

**Functional Specification:** REPORT-R0 v1.0 · **Дата:** 2026-11-15 · **Статус:** PLANNED / NOT IMPLEMENTED  
**Связь:** [Рабочие пространства](FIN-WORKSPACES-AND-JOURNEYS.md), [Консалтинговые запросы](FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md), [ADR-007](ADR-007-FIN-PROJECT-MARKETPLACE.md).

## 1. Продуктовая ценность

Пользователь может создавать и утверждать отчёты по текущему бизнесу и **добровольно публиковать выбранные финансовые сведения**, не создавая инвестиционного проекта. Пользователи: владелец/управляющий, бухгалтер/CFO, банк/фонд, инвестор, деловой партнёр, консалтинговая компания.

Официальное представление отчётности государственному органу, аудит и юридическая сертификация не являются автоматическими результатами публикации внутри FIN. Источники, периоды и методики явно указываются; \`FIN_VALIDATED\` означает только проверки FIN, **не** внешний аудит.

## 2. Публикационные режимы

| Mode | Audience | Default and restrictions |
|---|---|---|
| PRIVATE | только уполномоченные сотрудники | default, без поисковой индексации |
| INTERNAL | назначенные представители компании | требуемый организационный approval |
| INVITE_ONLY | конкретные инвесторы, банки, консультанты, партнёры | document/section/version grant, expiry, revoke |
| PUBLIC_SUMMARY | все | allowlisted агрегаты с явными периодами и статусом данных |
| PUBLIC_REPORT | все | явный выбор полного состава конкретной публикуемой версии, preview, approval |
| WITHDRAWN | новые обращения закрыты | старые скачанные копии нельзя отозвать |

**Независимость:** публикация финансового отчёта не делает \`ProjectListing\` публичным; публикация проекта не открывает F01–F04, и наличие engagement консультанта не подразумевает права видеть полные отчёты.

## 3. Wizard-REP и панель отчётности

**Навигация «Мой бизнес → Финансовые отчёты»:** все отчёты, сформировать, данные, сверки, версия, согласования, публикации, адресные получатели, история исправлений.

Мастер: тип (управленческий/финансовый/сегментный/адресный) → entity/scope → отчётный период/учётный стандарт/валюта → формы и источники (Interview/React Grid/XLSX/CSV) → проверка и \`ReportSnapshot\` → доступные формулы/ограничения → report preview → owner approval → PRIVATE / INTERNAL / INVITE_ONLY / PUBLIC_SUMMARY / PUBLIC_REPORT → disclosure field allowlist → точный preview HTML/PDF/XLSX + metadata → публикация или адресный grant.

**Отчётный snapshot** immutable, содержит \`reportId, companyId, period, basis, currency, scale, methodVersion, factRevision, approval/status, contentHash, disclosurePolicyVersion\`.

## 4. Состояния и исправления

\`\`\`text
ReportRevision: DRAFT -> VALIDATION_PENDING -> VALIDATED | VALIDATED_WITH_WARNINGS | INVALID
                VALIDATED -> REVIEW_REQUESTED -> APPROVED | REJECTED -> (new revision)
Publication: NOT_PUBLISHED -> DISCLOSURE_DRAFT -> OWNER_APPROVED
             OWNER_APPROVED -> (MODERATION_PENDING) -> PUBLISHED
             PUBLISHED -> PAUSED | WITHDRAWN | REPLACED
\`\`\`

Correction/restatement после публикации создаёт новую ревизию и причину исправления. Прошлые опубликованные версии не перезаписываются; новые обращения могут быть закрыты и на старую версию. Приватная публичная сводка не содержит исходный .fin.json, зарплатные ведомости, банковские реквизиты или скрытые PDF метаданные.

## 5. Консалтинговые запросы сверх отчётности

Получатель \`ConsultingOrganization\` **не** получает доступ к приватным отчётам только за счёт статуса консалтинга. После принятого Engagement она может запросить один report snapshot и **другие материалы**: договоры, прайсы, планы продаж, технические/юридические документы. Отчёт и каждый файл имеют отдельный grant/expiry. [CONS-REQ](FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md).

## 6. Инфраструктура и тесты

React — UI, Spring Boot — publication, policy и per-resource ACL, PostgreSQL — immutable revisions/grants/audit/Outbox, Keycloak — authentication и отдельная DB, Redis — sessions, RustFS — private versions, KumoMTA — notifications из PG Outbox.

**REPORT-01** Private never found via anonymous API/search.  
**REPORT-02** Correct allowlist for PUBLIC_SUMMARY and PUBLIC_REPORT, no hidden metadata.  
**REPORT-03** Same snapshot produces equal numbers/periods/quality labels in HTML/PDF/XLSX.  
**REPORT-04** Publishing from wrong org/with stale session denied.  
**REPORT-05** Idempotent approval/publish, old published snapshot immutable.  
**REPORT-06** Withdrawal/revocation blocks future reads even when CDN/cache stale.  
**REPORT-07** Consultant without engagement/grant cannot view report; with grant cannot access unrelated data.  
**REPORT-08** Bank INVITE_ONLY report cannot be read by unrelated investor/consultant.  
**REPORT-09** Corrected report retains revision/provenance and withdrawal history.  
**REPORT-10** Old Keycloak session revoked membership denies new document access.  
**REPORT-11** RustFS private files, untrusted import and XSS/CSV injection negative tests PASS.  
**REPORT-12** Email accepted not delivered; retries never publish duplicate report.

**Release:** core report builder first, safe local preview next, hosted publishing after Spring Boot/Keycloak/PostgreSQL/RustFS security qualification. Current repository does not implement full Report Publication.
