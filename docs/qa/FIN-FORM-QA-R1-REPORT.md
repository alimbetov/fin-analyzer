# FIN-FORM-QA-R1 — функциональная приёмка

**Вердикт PARTIAL.** Дата прогона 18 ноября 2026 года. Проверено отображение 166 из 166 форм в Chromium. 123 прошли заполнение, сохранение, прямую сверку PostgreSQL, повторное открытие и проверку истории. 43 заблокированы на записи. Форм с FAIL — 0.

| Показатель | Результат |
|---|---|
| Репозиторий | [alimbetov/fin-analyzer](https://github.com/alimbetov/fin-analyzer) |
| Ветка | `feat/fin-form-library-standalone` |
| Исходный SHA | `b8eb89304f557b5ecb5dc9e4b26ff8b217adbb00` |
| Проверенный SHA с исправлением и тестами | `b0e2ac001dd63ecadbb2435f3da291c78f912209` |
| Платформа | macOS, Java 21.0.10, Spring Boot 3.5.7, React 19, Node 22.22.2 и npm 10.9.7 |
| Browser E2E | Playwright 1.56.1, Chromium 141.0.7390.37 |
| PostgreSQL | 16.15 в одноразовом Docker-контейнере с tmpfs, клиент psql 18.4 |
| Ожидалось / найдено / физических полей | 166 / 166 / 877 |
| Подтверждены UI Save / DB / Reload + Reopen / Revision | 123 / 123 / 123 / 123 |
| PASS / FAIL / BLOCKED | 123 / 0 / 43 |
| Дефекты и пробелы реализации | 5 зафиксировано, 2 исправлено, 3 открыто. Отдельно зафиксирован блок квалификации 31 формы. |

## Фактический контур

TSV-источники → `CatalogBootstrap`, `CatalogCategoryBootstrap`, `FieldMetricContractBootstrap` → каталог `fin_lib` → `ReferenceCatalogController`, `CatalogCategoryReadController`, `FieldMetricContractAuditController` → renderer `frontend/src/App.tsx` → `FormLibraryController` → `FormDraftService` → `FieldMetricContractGuard` и JPA-репозитории экземпляра/редакции/строк → `fin_intake` → GET последней или исторической редакции. Календарные периоды и валюты предоставляет `ReferenceCatalogController`. API `ReportGridController` обслуживает отдельный контур. Браузерный аудит покрывает формы React, которые сохраняются через drafts.

Liquibase применил 19 изменений, все 19 с контрольной суммой, Hibernate прошёл `ddl-auto=validate`. Backend стартовал с профилем default и подтвердил готовность ответом `GET /api/v1/forms`. Vite проксировал `/api` на Spring Boot. SQL подтвердил 40 таблиц, 48 FK и 81 индекс в `fin_lib`, 9 таблиц, 16 FK и 25 индексов в `fin_intake`. Режим Flyway в этой ветке отсутствует.

## Регрессия

| Контур | Результат |
|---|---|
| Java 21, `mvn verify` | 181 тест учтён, 171 выполнен успешно и 10 пропущены по условию окружения, 0 ошибок |
| PostgreSQL, Testcontainers | 5 тестов `ReactLocalPostgresIntegrationTest` и 4 теста `R16BPostgresqlContractTest` прошли. Покрыты атомарность инициализации каталога, конкурентные создание и обновление, неизменяемость и версионирование |
| PostgreSQL, прямой SQL | 166 форм / 877 полей / 877 контрактов / 840 метрик. Сверены обе сохранённые редакции, строки, значения, организация, проект и версия каждой прошедшей формы |
| React | Typecheck, 22 unit-теста, production build, строгая TypeScript-проверка новых Playwright specs прошли. Lint отсутствует в scripts и конфигурации |
| Playwright | 9 сценариев прошли, включая исходные 6 сценариев UX, исправление DECIMAL, API negatives и пакет каталога |
| Draft API | Проверены отказ при недостающем обязательном поле, неверной сумме, форме и UUID, replay с тем же idempotency key, stale update, конкурентные записи и неизменяемая история, неправильный organization/project scope |
| IAM и словари | BLOCKED. В localhost-архитектуре нет IAM, а REF/ENUM сохраняются как строки без реализованного контракта допустимых значений |

Расширенная UX-регрессия охватывает клавиатуру, возврат к ветви дерева, несохранённые изменения и мобильную навигацию. Пакет проверяет browser console во время позитивных сценариев и сохраняет screenshot/trace при ошибке формы. Открытие исторических редакций проверено через REST и SQL. В UI есть открытие последней редакции по UUID, выбор исторической версии отсутствует. Переводы проверены на доступность, редакционная квалификация остаётся открытой.

## Воспроизведение

Из корня репозитория при свободных loopback-портах 55486, 8086 и 5173. PostgreSQL запускается без постоянного тома и только для тестового стенда. Остальные команды выполняются с Java 21, Maven, Node 22 и клиентом `psql` в PATH.

```sh
docker run --rm -d --name fin-form-qa-r1-standalone \
  -p 127.0.0.1:55486:5432 \
  -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_USER=fin_qa -e POSTGRES_DB=fin_qa \
  --tmpfs /var/lib/postgresql/data postgres:16
docker exec fin-form-qa-r1-standalone pg_isready -U fin_qa -d fin_qa
FIN_FORM_DB_URL=jdbc:postgresql://127.0.0.1:55486/fin_qa \
  FIN_FORM_DB_USER=fin_qa FIN_FORM_DB_PASSWORD=unused \
  mvn -B -f form-library/pom.xml spring-boot:run
```

После старта выполните проверки из отдельного терминала в корне.

```sh
curl -fsS http://127.0.0.1:8086/api/v1/forms -o /tmp/fin-qa-forms.json
mvn -B -f form-library/pom.xml verify
mvn -B -f form-library/pom.xml \
  -Dtest=ReactLocalPostgresIntegrationTest -Dfin.postgres.uat.enabled=true test
mvn -B -f form-library/pom.xml \
  -Dtest=R16BPostgresqlContractTest -Dfin.postgres.uat.enabled=true test
export PGHOST=127.0.0.1 PGPORT=55486 PGUSER=fin_qa FIN_QA_DATABASE=fin_qa
PGOPTIONS='-c default_transaction_read_only=on' psql -X -w -d fin_qa \
  -v ON_ERROR_STOP=1 -f docs/audits/FIN-R1-postgresql-live-contract-audit.sql
cd frontend
npm ci --no-audit --no-fund
npm run check
npx playwright install chromium
npm run test:e2e -- --retries=0 --output=/tmp/fin-r1-verified-browser
FIN_QA_FORMS=F02,DF-AST-01,C01 npm run test:e2e -- --retries=0 form-qualification
```

В финальном прогоне Vite был заранее запущен командой `npm run dev` из `frontend/` в отдельном терминале. При свободном порте Playwright может поднять Vite сам.

`FIN_QA_FORMS` принимает один код или коды через запятую. По умолчанию — все формы, последовательно в одном воркере. `FIN_QA_STEP_TIMEOUT` и `FIN_QA_TIMEOUT` задаются в миллисекундах. `FIN_QA_READ_RETRIES` задаёт число повторов GET при ECONNRESET. Повторная отправка изменяющих команд при сбое отключена. `qualification.json` содержит результаты, контракты, категории, локали и диагностические записи по каждой форме. Браузерные трассы и снимки сохраняются локально в каталоге `--output` или в `frontend/test-results/artifacts` по умолчанию. Они не входят в коммит. По завершении стенда остановите его отдельный контейнер командой `docker stop fin-form-qa-r1-standalone`.

[Инвентаризация](FIN-FORM-QA-R1-INVENTORY.md) · [Поформенная матрица](FIN-FORM-QA-R1-TEST-MATRIX.md) · [Результаты JSON](FIN-FORM-QA-R1-RESULTS.json) · [Дефекты и ограничения](FIN-FORM-QA-R1-DEFECTS.md)
