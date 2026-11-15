# ADR-008 — FIN Connect Backend Stack: Spring Boot BFF + Keycloak + Redis + RustFS + KumoMTA + PostgreSQL

**Дата:** 2026-11-15  
**Решение:** ACCEPTED — целевая архитектура **планируемого** hosted FIN Connect / FIN Project Marketplace.  
**Реализация:** NOT IMPLEMENTED. Текущий React-only FIN-R1A этим ADR не изменяется.  
**Связь:** [ADR-007 — не-транзакционный FIN Project Marketplace](ADR-007-FIN-PROJECT-MARKETPLACE.md), [мастер-ТЗ](FIN-ANALYZER-PROJECT-TZ.md).

## 1. Принятое решение

**Технический стек:**
- **Frontend:** React + TypeScript (существующий Vite), browser UI.
- **Backend / BFF:** Java 21 LTS + Spring Boot + Spring MVC REST + Spring Security OAuth2 Client/OIDC + Spring Data JPA (совместимые поддерживаемые версии зафиксировать перед реализацией).
- **Identity Provider:** Keycloak (самостоятельно развёрнутый серверный сервис, интеграция только через backend и reverse proxy).
- **Permanent database:** PostgreSQL для организаций, пользователей приложения, membership, ACL, EconomicProject, BusinessPlan metadata, ProjectListing, InterestRequest, Document metadata, Outbox, audit и workflow state.
- **Session / ephemeral:** Redis — Spring Session, TTL-кэш, rate-limit/anti-abuse и только восстанавливаемые временные данные; Redis не является источником истины для финансовой модели или прав.
- **Private object storage:** RustFS через S3-compatible API только серверному адаптеру \`ObjectStoragePort\`.
- **Mail transfer:** KumoMTA для доставки email из backend \`NotificationPort\`; события доставки через аутентифицированные внутренние callbacks.
- **Background:** PostgreSQL transactional outbox + worker; Redis допускается как ускоритель/сигнал, но не как единственная долговечная очередь.
- **Edge:** reverse proxy / gateway с HTTPS, фиксированными маршрутами, безопасной обработкой forwarded headers и ограничениями размера запросов.
- **Infrastructure:** Docker Compose для локального E2E; Kubernetes только после подтверждённой потребности; секреты вне исходников, production network segregation.

**Важное уточнение:** Keycloak, Redis, RustFS, KumoMTA и PostgreSQL являются **отдельными сервисами**, не встроенными Java-библиотеками и не исполняемыми модулями в процессе Spring Boot. «Внутри backend» означает **закрытый инфраструктурный контур под управлением серверной стороны**.

## 2. Сетевая топология

\`\`\`text
 Public browser
       |
       | HTTPS only
       v
 [Edge reverse proxy]
       |---- /          -> React static SPA
       |---- /api/**    -> Spring Boot BFF / REST (session cookie)
       |---- /oauth2/** -> Spring Boot OAuth2 entrypoints/callbacks
       |---- /auth/**   -> Keycloak auth UI/protocol endpoints (controlled reverse proxy ONLY)
       |
 Private application network:
 [Spring Boot BFF] ----- OIDC/backchannel ------> [Keycloak] ------> [Keycloak database]
         |--------- JDBC/JPA -------------------> [PostgreSQL FIN database]
         |--------- Spring Session/cache ------> [Redis]
         |--------- S3 private -----------------> [RustFS private buckets]
         |--------- SMTP/internal API ----------> [KumoMTA] -> external email recipients
         |--------- authenticated event callback <---- [KumoMTA]
         |--------- outbox worker / audit ------> [PostgreSQL FIN database]
\`\`\`

**Browser MUST NOT** connect directly to Redis, RustFS S3 endpoint/console, KumoMTA SMTP/API, PostgreSQL, Keycloak Admin API or other private service ports. React MUST NOT contain API keys, SMTP credentials, RustFS access keys, Keycloak client secret, or handle/store OAuth access/refresh tokens in localStorage/sessionStorage.

**OIDC exception explained:** the browser *necessarily* receives Keycloak's user-facing login and authorization screens during interactive OIDC flows. To satisfy the selected network boundary, expose only Keycloak's required authentication endpoints **through the edge reverse proxy** (e.g. \`/auth/**\`), not a direct public internal service port. The BFF handles code exchange and tokens server-side. Keycloak Admin UI/API, master realm and management endpoints are restricted to trusted administration paths.

**Access Center contract:** [ADR-009](ADR-009-REPORT-ACCESS-ZONES.md) — zones Z00–Z99, PostgreSQL authoritative AccessGrant/AccessLease, expiry/contract/one-use/revoke. [ADR-010](ADR-010-PRIMARY-PRODUCT-NAME.md) — product identity; FIN Analyzer is module.

## 3. Identity, session and authorization

- Spring Boot is a **confidential OIDC client**, using Authorization Code flow (with supported protections, state, nonce, redirect-URI allowlist). No password grant / custom password proxy through React; password entry occurs on Keycloak-controlled login UI.
- Server stores OIDC access/refresh tokens (if retained) **only in protected server-side session or token store**, with encryption/security controls and TTL. React uses \`HttpOnly; Secure; SameSite\` session cookie; CSRF defenses for state-changing requests.
- Spring Session + Redis is the default distributed session store. Login/logout/session expiry removes server session; back-channel logout/session revocation requirements and token refresh policies must be specified/tested.
- Keycloak provides authentication and coarse-grained identity/realm roles. **FIN Backend is the ultimate authorization decision point** for each \`organizationId, projectId, documentId, action, sensitivity, shareGrant, expiry\`. Every object GET/POST/PUT/DELETE, list/search and download enforces this.
- FIN membership and representative/organization verification live in FIN PostgreSQL. A valid JWT and a Keycloak organization membership are **not** proof of legal authority to publish on a company's behalf.
- On organization membership revocation and role changes, no long-lived positive authorization cache may override PostgreSQL truth. Adopt per-request version/revision validation or robust invalidation and fail-closed on ambiguity; verify stale sessions and old tokens are rejected for protected resources.
- Same user may belong to multiple organizations. \`activeOrganization\` is explicit per request/context and must be membership-verified; \`organizationId\` passed by browser is untrusted.
- Default permissions deny; platform admins and moderators have no routine access to complete financial models/data room documents.

## 4. RustFS private document flow

1. Authorized user uploads file to \`POST /api/projects/{projectId}/documents\` via same-origin React→Spring Boot.
2. Backend validates size, content type by bytes, file extension, object name, owner organization, permissions, quotas, multipart/stream limits; captures checksum.
3. Backend streams to **private staging/quarantine** in RustFS; malware scanning/validation pipeline, explicit state \`UPLOADING → QUARANTINED → AVAILABLE/REJECTED\`.
4. PostgreSQL stores Document metadata, version, project/tenant ownership, security classification, status and RustFS object key; no file bytes in Redis.
5. Recipient requests \`GET /api/projects/{projectId}/documents/{documentId}/content\`. Backend checks session, organization, document grant and expiry **on every request** and streams response from RustFS. Prevent path traversal and IDOR.
6. Revoking access blocks future backend download requests but cannot delete previously downloaded copies. Audit view/download/grant/revoke.

**Strict decision:** do not expose RustFS hostname, browser S3 SDK, bucket credentials or client-facing pre-signed S3 URLs. This may increase backend bandwidth for large files; qualify streaming/backpressure, max file size, timeouts and memory budgets in load tests. Any future change requires separate ADR and user's approval.

## 5. KumoMTA & notification flow

- Business event (invitation to view a project, interest request, document granted, password notice where appropriate) commits a \`NotificationIntent\` + \`OutboxEvent\` in PostgreSQL **in the same transaction as related workflow state**.
- Background worker claims pending outbox with bounded retries, attempts/dedup, idempotency keys, exponential backoff and dead-letter/parking after exhausting policy; prevents silent event loss. Do not claim exactly-once delivery.
- \`NotificationService\` composes template + locale + recipient + minimum personal data needed, enforces user preferences and anti-spam/unsubscribe where relevant.
- Backend-to-KumoMTA through trusted SMTP or documented API; KumoMTA manages mail transfer to external recipient mail servers.
- KumoMTA delivery/bounce/deferral webhooks return to **authenticated internal backend callback endpoint** (signature/secret, retry protection and deduplication), with durable delivery status; never connect directly to React or send sensitive financial documents as unrestricted attachments.
- Email content: notify and link to authenticated, scoped, expiring FIN documents rather than sending full private business plans automatically.
- Test provider/mock can be used in local CI only; real KumoMTA use in deployed environment requires explicit non-mock configuration and observable delivery outcomes. A successful SMTP acceptance is **not** a recipient inbox confirmation.

## 6. Redis cache policy

Allowed: session state, short-lived rate limit counters, transient idempotency accelerators, non-sensitive reference lookups or reconcilable derived data. Durable idempotency records, project revisions, permissions, signed share grants, notification outbox, and audit events live in PostgreSQL.

Authorization-sensitive cache keys include identity, tenant and relevant revision; short TTL is **not a sufficient revocation guarantee**. Permission changes must invalidate or version-check cached grants. Loss of Redis must not default to granting access; sessions may require re-login. Never use Redis as authoritative persisted financial facts.

## 7. PostgreSQL persistence boundaries

- FIN main application PostgreSQL DB is the system of record for organizations, memberships, listings, shares, requests, messages (as relevant), audit, notification outbox and project metadata; financial source data scope may remain locally stored in FIN Core until explicit upload/consent.
- Keycloak should use an **independent PostgreSQL database** and credentials, separated from FIN application data; backup/upgrade lifecycle and least privilege differ. A PostgreSQL *instance/cluster* may be shared in development with separated DBs/users; production separation determined by risk.
- Use transactional boundaries; append-only audit trail or equivalent tamper-evidence controls; schema migrations and rollback/restore evidence; concurrency tests for shares, notifications and revocations.
- No global unscoped JPA queries for organization-owned data; authorization is checked in service/query layers, not only frontend menus.

## 8. Threat model / forbidden flows

- **T-01** Unauthenticated API download or enumerating resource IDs.
- **T-02** Old authenticated session/old JWT after removal from organization or revocation of a document grant.
- **T-03** Valid user from Org A trying to access Org B's project via URL, search, cache keys or attachments.
- **T-04** UI/JS tries to invoke RustFS or KumoMTA or Redis ports, S3 pre-signed URL leakage, exposed private buckets.
- **T-05** XSS, CSRF, session fixation, stolen cookie, open redirect through OIDC/proxy misconfiguration.
- **T-06** Trusting unverified organization membership or accepting spoofed organizationId.
- **T-07** Email notification duplicate/missing across crash between DB commit and SMTP; bounce ignored.
- **T-08** KumoMTA forged delivery webhook or unauthenticated status update.
- **T-09** Malicious document upload, zip bomb, mime spoofing, infected file distribution.
- **T-10** Direct access to Keycloak Admin API or default admin/realm endpoints from public internet.
- **T-11** Redis outage results in protected content leaking; cache staleness hides revocation.
- **T-12** PublicTeaser contains private fields/metadata or export leaks full \`.fin.json\`.
- **T-13** Roles from Keycloak mapped incorrectly so same user gains permissions in every tenant.
- **T-14** Foreign-origin cross-site request with session cookie modifies disclosure or sends messages.

### 8.1. Дополнительный контур Consulting Organization

`ConsultingOrganization` хранится в FIN PostgreSQL как субъект с собственной командой и `Engagement` с другой организацией. Консалтинговая фирма не получает права на private F/B/C формы, финансовые отчёты или дополнительные документы клиента без принятого engagement и конкретных grants. Все `RequestItem`, `DocumentGrant`, consent, approvals, revisions и audit — в PostgreSQL; RustFS только private object bytes; Redis никогда не является источником истины об access. Документы проходят backend auth на каждом скачивании. Уведомления Request/Reminder/Grant/Revocation через PostgreSQL Outbox и KumoMTA. [Security E2E CONS-01…CONS-20](FIN-CONSULTING-COMPANY-DOCUMENT-REQUESTS.md).

## 9. Nonfunctional and security gates (future implementation)

| Test | Mandatory PASS |
|---|---|
| BACK-01 | React network log has only same-origin FIN paths and browser Keycloak login through controlled public proxy; zero direct calls to private services |
| BACK-02 | No auth tokens or secrets stored/accessed in React/localStorage/sessionStorage; session cookie HttpOnly+Secure and CSRF protected |
| BACK-03 | Keycloak login, logout, MFA where required, session expiry and forced revocation verified using real OIDC integration |
| BACK-04 | Multiple organization memberships with explicit active tenant; cross-tenant project/list/download denied |
| BACK-05 | User removed from organization: existing session and previously valid token cannot access a protected document |
| BACK-06 | RustFS private file upload/review/download/deny/revoke, virus-scan quarantine, S3 credentials not leaked |
| BACK-07 | No publicly reachable Redis, RustFS API/console, KumoMTA admin/SMTP/API, PostgreSQL or Keycloak Admin API |
| BACK-08 | PostgreSQL commit and notification outbox atomic; restart/retry and dedup do not silently drop business events |
| BACK-09 | Real KumoMTA SMTP acceptance, delivery/bounce callback, signed event processing and retry; mocks are marked mock |
| BACK-10 | Redis failure fails closed for authorization; recovery does not resurrect stale sessions/grants |
| BACK-11 | Project publication/sharing respects allowlist and versioned approval; user cannot publish on behalf of unverified company |
| BACK-12 | Large file streaming and concurrent download stress meet published resource and p95/p99 limits, no heap exhaustion |
| BACK-13 | CSRF, session fixation, redirect spoofing, CORS, CSP, hostile proxy headers security negative tests PASS |
| BACK-14 | Confidential financial facts do not appear in SMTP logs, public tracing, Redis long-term storage or React bundle |
| BACK-15 | Production backup/restore of FIN DB, Keycloak DB, RustFS objects, disaster recovery and least-privilege credentials tested |

**Acceptance process:** run backend integration tests on real PostgreSQL, Redis, RustFS, Keycloak and KumoMTA (with production-like staging) + browser React E2E; report explicit commit SHA, tests and environment. Mock-only tests are insufficient for the hosted security release gate.

## 10. Boundaries / delivery

- Existing **FIN Core**: React-only partial F01 prototype and local JSON project. **It does not need Keycloak or these hosted services to run.**
- Planned **FIN Connect**: React hosted UI, Spring Boot backend/BFF, private PostgreSQL/Redis/RustFS/Keycloak/KumoMTA, controlled browser-facing Keycloak authorization screens behind proxy.
- **FIN Project Marketplace** remains informational and does not accept, transfer or hold money, organize trading or execute financial transactions (ADR-007).
- Do not create a separate microservice for each infrastructure integration at initial phase. Prefer **modular monolith**, clear ports/adapters: \`IdentityAdapter, OrganizationAuthorization, DocumentStoragePort, NotificationPort, OutboxProcessor, PublicTeaserPublisher\`.

## 11. Official documentation used for design constraints

- Keycloak hostname/reverse proxy: https://www.keycloak.org/server/hostname and https://www.keycloak.org/server/reverseproxy
- Spring Security OAuth2 Login: https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html
- Spring Session Redis: https://docs.spring.io/spring-session/reference/guides/boot-redis.html
- RustFS S3 API and compatibility: https://docs.rustfs.com/en and https://docs.rustfs.com/en/reference/s3-compatibility
- KumoMTA log/delivery webhooks: https://docs.kumomta.com/userguide/operation/webhooks/

**These technical contracts are requirements, not confirmation that the connectors/services were deployed in the repository.**
