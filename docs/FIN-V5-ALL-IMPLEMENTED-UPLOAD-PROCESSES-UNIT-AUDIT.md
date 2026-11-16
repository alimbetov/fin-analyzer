# FIN V5 — полный внутренний тест процессов финансового ввода и импорта

**Дата:** 2026-11-16  
**Ветка:** feat/fin-r0-r1a · PR #2 Draft · main untouched  
**Цель:** зафиксировать воспроизводимое unit/integration/E2E покрытие ВСЕХ **реализованных** путей загрузки, без выдуманного PASS для будущих форм и интеграций.  
**Статус:** кодовые тесты и hardening закоммичены; общий итоговый GREEN квалифицируется только по точному последнему SHA.

## 1. Реестр реально реализованных маршрутов

| ID | Процесс | Реальная реализация | Класс тестов |
|---|---|---|---|
| UL-01 | React F02 ручные значения | FormIntakeStudioV5, validateF02 | upload-validation-matrix.test.ts, form-v5.test.ts, form-intake-v5.spec.ts |
| UL-02 | Локальная выдача Excel V2 RU/KK/EN | excel-v5.ts | form-v5.test.ts, Playwright form-intake-v5.spec.ts |
| UL-03 | Локальный разбор Excel V2 F02 | excel-v5.ts + zip-budget | form-v5.test.ts, upload-validation-matrix.test.ts, zip-budget.test.ts |
| UL-04 | Preview F02/локальная история | FormPreviewTable, local-revision-v5.ts | local-revision-v5.test.ts + Playwright |
| UL-05 | Старый Balance XLSX | src/adapters/excel.ts | excel.test.ts и financial-smoke.spec.ts |
| UL-06 | 9 отраслевых Excel пилотов | src/sector/excel.ts | src/sector/excel.test.ts и sector-pilot.spec.ts |
| UL-07 | Java 5 пилотных XLSX FIN-PILOT-V1 | PilotXlsxTemplateService Apache POI | PilotXlsxIntakeIntegrationTest, PostgreSQL |
| UL-08 | Java F02 manual preview и неизменяемые ревизии | FormSchemaV5, FormDraftRevisionV5Service | FormSchemaV5Test, FormDraftRevisionV5IntegrationTest |
| UL-09 | Действующий reviewed intake → approval → FactSetRevision | ReviewedFinancialIntakeService, TrustedFinancialFormMapper | ReviewedFinancialIntakeIntegrationTest, TrustedFinancialFormMapperIntegrationTest и FactPublicationIntegrationTest |
| UL-10 | Доступ/отсутствие доступа к загрузке | FinanceAccessPolicy, FinancialApiSecurity | FinancialApiSecurityIntegrationTest, FinancialApiDisabledIntegrationTest, FormV5MultiUserRefreshIntegrationTest |

**НЕ реализованы и НЕ покрыты как работающие:** серверный Apache POI FIN-FORM-XLSX-V2, реальный React→server projectId→secured Preview/Confirm/History, автоматическое живое обновление React при втором пользователе, утверждённый 4-язычный DiagnosticPresentationService, V5 XLSX для F01/F03 и финансовых расшифровок, полный список из 166 каталоговых форм. CI GREEN существующих тестов не означает завершения этих процессов.

## 2. Негативная матрица и изменения этого аудита

1. Избыточная ячейка A900/дополнительные строки: в старом Balance и sector Excel код ранее проверял actualRowCount, который считает заполненные строки и не гарантирует контроль высокого номера. Исправлено: проверить row.number и column number каждой занятой строки; добавить негативный Vitest.
2. V5: Instructions и _FIN_Metadata ранее проверялись по actualRowCount и actualColumnCount. Исправлено: sparse-extra-row/column checks даже при A900; Vitest для обоих листов.
3. Excel Numeric precision: V5 уже имел запрет >15 Excel digits, но старый Balance принимал Finite numeric без проверки точности. Исправлено: reject числа с >15 десятичными цифрами либо scientific notation. В sector добавлена аналогичная оценка. Тесты на значимые десятичные разряды.
4. V5 шаблон zh-Hans: архитектурный locale был объявлен, но реальный перевод не готов. При прямом вызове generateV5 с недоступной локалью теперь немедленно TRANSLATION_NOT_READY, без неуправляемого TypeError. Тест.
5. Server manual F02 null/period null: раньше Java Objects.requireNonNull мог выдать непредсказуемый 500 вместо контролируемого исключения. Теперь FIN_V5_CELLS_REQUIRED / INVALID_PERIOD_INPUT (IllegalArgumentException), закреплено JUnit.
6. Canonical manual/XLSX: добавлен upload-validation-matrix.test.ts на явный ноль, пропуск, отрицательные/микро-суммы, 12 decimal places, 26 integer places, десятичную запятую RU/KK, неверный валютный/проектный/периодный контекст, дубликаты и неизвестные sourceRowId/fieldCode.
7. Java FIN-PILOT-V1: проверены пять форм F01/F02/F03/DF-CSH-01/DF-CSH-02 на генерацию/парсинг без автоматического утверждения, а также коррумпированный, пустой, sparse row, unsafe double и неверный decimal literal.
8. Java server V5: интеграционный тест проверяет, что invalid/manual/null data, 1e6, invalid locale/period не записывают Preview, не меняют DraftRevision и не продвигают currentRevision.

**Границы точности:** числа в качестве текстовых десятичных значений через BigDecimal/decimal string — предпочтительны для больших сумм. Excel numeric cell при большом числе цифр может уже потерять исходную точность до чтения; такие значения reject, пользователь должен вводить decimal-as-text.

## 3. Запуск на MacBook — короткий smoke и полный gate

Предусловия: Java 21, Maven, Node.js 22+, Docker Engine (PostgreSQL Testcontainers), браузеры Chromium + WebKit, настоящая ветка проекта.

~~~bash
git switch feat/fin-r0-r1a
git rev-parse HEAD
docker info
java -version
node -v
npm ci --no-audit --no-fund
npm run typecheck
npm test
npm run build
mvn -B -ntp -f backend/pom.xml verify
npx playwright install chromium webkit
npm run test:e2e -- --project=chromium --project=webkit
~~~

### Более быстрый выборочный запуск

~~~bash
npx vitest run src/intake-v5 src/adapters/excel.test.ts src/adapters/zip-budget.test.ts src/sector/excel.test.ts
mvn -B -ntp -f backend/pom.xml -Dtest=FormSchemaV5Test,FormDraftRevisionV5IntegrationTest,FormV5MultiUserRefreshIntegrationTest,PilotXlsxIntakeIntegrationTest,ReviewedFinancialIntakeIntegrationTest test
~~~

**Важно:** не подменять тесты с Testcontainers H2; для финансовых транзакций, tenant isolation и неизменяемых ревизий нужен именно PostgreSQL. Если нет Docker, эти проверки BLOCKED, а не PASS.

## 4. Критерии итоговой приёмки

- All unit tests и реальные PostgreSQL интеграции успешно проходят; фиксируется точный SHA.
- Excel manual parity на заявленных реализованных формах; no truncated decimals, missing != zero, no source context mix.
- Bad ZIP/foreign template/tampered manifest/formula/extra cells не попадают в данные.
- Запись invalid source не меняет уже подтверждённые ревизии и опубликованные FactSetRevision.
- Multiple authors: FIN_READ/FIN_SUBMIT, stale/conflict без silent overwrite; Web endpoints fail-closed.
- Chromium Linux + macOS Chromium/WebKit gates зелёные на этом же SHA.
- Настоящий серверный XLSX V2, настоящий браузерный org-workspace и Диагностика на 4 языках остаются OPEN.

## 5. Release decision

**FIN V5 upload unit and regression hardening может получить GREEN только после завершения единого CI на последнем HEAD.** Тесты не являются доказательством методологической квалификации всех финансовых форм, реального Keycloak login или production-готовности.
