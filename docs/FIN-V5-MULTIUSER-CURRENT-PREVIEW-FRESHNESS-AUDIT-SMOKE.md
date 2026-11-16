# FIN V5 — Multiuser, CurrentFormView, PreviewFreshness: технический аудит и smoke-ready контракт

Дата: 2026-11-16. Ветка: feat/fin-r0-r1a. PR #2 Draft; main не изменять; production / S2 HOLD.

## 1. Вывод: единая организация — несколько пользователей с разными правами

FIN уже проверяет organizationId и FIN_READ / FIN_SUBMIT из доверенного подписанного JWT-claim fin_access. Несколько разных subject с одинаковым organizationId и FIN_READ могут видеть общие СОХРАНЁННЫЕ черновики и историю одной организации; FIN_SUBMIT позволяет отправлять Preview и корректировки. Полная регистрация персонала, проверка membership из БД, выдача ролей, per-project/per-field grants и реальный Keycloak onboarding пока НЕ РЕАЛИЗОВАНЫ. Нельзя считать наличие JWT тестов полноценной корпоративной регистрацией.

Важное ограничение: FIN_READ сейчас действует на весь organizationId. Разграничение по отдельному projectId, финансовым разделам и периодам — P0 для расширенного многопользовательского релиза, не обходить действующие ограничения.

## 2. Три разных объекта вместо одного автоматически изменяющегося Preview

1. **PreviewSnapshot** — ИММУТАБЕЛЬНЫЙ результат одной загрузки, сохраняет previewId, expectedRevision, digest, исходные canonical decimal cells. Не перезаписывается по чужому изменению.
2. **CurrentFormView** — read-only динамическое представление последней СОХРАНЁННОЙ DRAFT-ревизии формы, v0 если ещё ничего не сохранено. Номер ревизии, uploadedAt/By, savedAt/By, changeReason, normalizedFingerprint и exact cells. Новые незавершённые Preview в Current не попадают. Не является APPROVED source или FactSetRevision.
3. **PreviewFreshness** — сигнал об устаревании открытого preview: expectedRevision, currentRevision, stale и последний savedBy/At. Если сотрудник B подтвердил более свежую ревизию, Preview сотрудника A становится STALE, но его суммы НЕ меняются; при Confirm сервер вернёт HTTP 409.

## 3. Серверный результат этого аудита

- Новый CurrentFormV5QueryService: read-only поиск latestRevision, creator/editor, UTC timestamps, canonical F02 values. Действует только внутри organization + project + period.
- GET /api/v1/financial/organizations/{organizationId}/projects-v5/{projectId}/forms/F02/current?periodCode=2026.10 — FIN_READ, Cache-Control: no-store.
- GET /api/v1/financial/organizations/{organizationId}/projects-v5/{projectId}/forms/F02/previews/{previewId}/freshness — FIN_SUBMIT + только исходный автор preview, Cache-Control: no-store. Чужой preview не раскрывается.
- FormV5MultiUserRefreshIntegrationTest: PostgreSQL Testcontainers + MockMvc, разные JWT subjects одной организации, B видит после подтверждения A v1 и после подтверждения C v2, старый preview A становится STALE, попытка сохранить возвращает 409, read-only Bob не может писать, outsider denied.
- Проверка новых endpoints в FinancialApiDisabledIntegrationTest: при отключённой безопасности API DENY-ALL.
- Отдельный acceptance-gate CI требует новый MultiUser JUnit отчет; не объявлять PASS без завершения полного CI на одном SHA.

## 4. Требования React auto-refresh (ПОКА НЕ РЕАЛИЗОВАНЫ)

После подключения авторизованного React к server projectId:
- Только открытая в активной вкладке форма: GET /current при открытии, после Confirm и периодически каждые 10–15 с; запрос при возврате фокуса; скрытая вкладка не опрашивает сервер.
- Если revisionNumber не изменился — не перерисовывать таблицу и не сбрасывать focus.
- Если revisionNumber вырос — CurrentFormView показывает новую версию, кто сохранил и когда, но открытый редактор и immutable Preview НЕ меняют значения молча.
- При локальных несохранённых правках — предупреждение «Данные обновлены пользователем X в Y; версия vN», действия «Просмотреть изменения / Повторно проверить / Отмена». Backend Confirm всё равно проверяет CAS и не даст затереть чужую vN.
- Если открытый Preview устарел, отображать STALE и блокировать старое подтверждение до повторной проверки; HTTP 409 обрабатывается как конфликт, не как успешное сохранение.
- При 401/403/сети статус UNAVAILABLE/UNKNOWN; не показывать устаревший Current как заведомо актуальный и не очищать пользовательский черновик.
- Все даты хранятся в PostgreSQL UTC и показываются с понятным часовым поясом пользователя; отображаемое имя нельзя доверять из формы, стабильный userId/subject сохраняется для аудита.
- Целевая задержка актуализации открытого браузера — не дольше одного polling interval + network latency; целевой UX smoke <=15 секунд, не гарантия текущей реализации.
- SSE/WebSocket возможны позднее, но не заменяют повторную серверную проверку expectedRevision/CAS.

## 5. Smoke matrix

| ID | Тест | Ожидаемый результат | Статус реализации |
|---|---|---|---|
| MU-01 | A создаёт проект; B с FIN_READ открывает Current | v0, никаких незавершённых чужих данных | Авто-JUnit |
| MU-02 | A создаёт Preview без Confirm | B всё ещё видит v0 | Авто-JUnit |
| MU-03 | A подтверждает v1 | B видит v1, exact amounts, uploadedAt/By и savedAt/By | Авто-JUnit |
| MU-04 | C сохраняет v2 | B видит v2, C и новую дату; v1 в истории | Авто-JUnit |
| MU-05 | A держит старый Preview, C сохраняет v2 | freshness.stale=true, currentRevision=2, savedBy=C, immutable Preview | Авто-JUnit |
| MU-06 | A пытается Confirm старый Preview | 409 REVISION_CONFLICT; никаких потерь | Авто-JUnit |
| MU-07 | B имеет только FIN_READ | Чтение доступно, создание Preview запрещено | Авто-JUnit |
| MU-08 | C пытается получить частный Preview A | 404; чужой organization 403; без JWT 401 | Авто-JUnit |
| MU-09 | fin.security.enabled=false | GET Current/Freshness запрещены даже при injected JWT | Авто-JUnit |
| MU-10 | Загружают идентичные значения | Событие загрузки фиксируется; revision и savedAt без изменений | PostgreSQL revision test |
| MU-11 | Два реальных браузера A/B с одним org | У B Current обновляется без reload за <=15 c | BLOCKED: React auth+polling |
| MU-12 | B редактирует, A сохраняет vN | B видит stale-warning, черновик не теряется | BLOCKED: React auth+polling |
| MU-13 | Hidden tab -> focus | Poll paused -> immediate refresh, no cross-project race | BLOCKED: React auth+polling |
| MU-14 | Network failure, 401, 403 | Не объявлять stale/current неизвестное свежим; данные не терять | BLOCKED: React auth+polling |
| MU-15 | Реальный Keycloak аккаунт A/B/C, org role assignments | Сегментация доступа и отзыв не являются simulated JWT | BLOCKED: IAM E2E |

## 6. Достаточный локальный smoke на MacBook

Prerequisites: Java 21, Maven, Docker Engine (Docker Desktop или Colima), Node.js 22+, git branch feat/fin-r0-r1a.

Команды:

    git switch feat/fin-r0-r1a
    git rev-parse HEAD
    docker info
    java -version
    node -v
    mvn -B -ntp -f backend/pom.xml -Dtest=FormV5MultiUserRefreshIntegrationTest,FormDraftRevisionV5IntegrationTest,FinancialApiSecurityIntegrationTest,FinancialApiDisabledIntegrationTest test
    npm ci --no-audit --no-fund
    npm run typecheck
    npm test
    npm run build
    npx playwright install chromium webkit
    npm run test:e2e -- --project=chromium --project=webkit

MockMvc и Testcontainers тесты используют тестовые JWT claims; они НЕ доказывают живую интеграцию с Keycloak. Для MU-11..15 нужны две независимые настоящие сессии, действующий identity provider, Browser/API client и протокол полевых permissions. Не отключать default deny-all ради smoke.

## 7. Условия итогового статуса

BACKEND_MULTIUSER_READ_MODEL может получить PASS только на одном SHA с JUnit/Testcontainers и всеми job FIN CI GREEN. LIVE_REACT_AUTO_REFRESH остаётся NOT IMPLEMENTED до доказанного автоматического обновления в двух браузерах. Нельзя подменять Latest Draft термином «утверждённая версия» или изменять FactSetRevision вслед за черновиком.
