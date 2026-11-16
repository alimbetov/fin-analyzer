# FIN V5 — техническое ревью реализации, исправления и контроль перехода на серверный Intake

**Дата:** 2026-11-16  
**Репозиторий:** alimbetov/fin-analyzer  
**Ветка:** feat/fin-r0-r1a (main не изменять)  
**Статус:** V5 LOCAL UX HARDENING; не production; S2 методики HOLD.  
**Предыдущий стабильный baseline:** SHA `6514a25de118fffb9f8889dffbf5c0f7e45c7f44` — полный FIN CI SUCCESS (GitHub run 43804755929).  
**Реальный статус последнего SHA/CI:** смотреть GitHub Actions, отсутствие GREEN не считать PASS.

## 1. Объём code review

Проверены `src/intake-v5/form-schema.ts`, `reporting-period.ts`, `excel-v5.ts`, `FormIntakeStudioV5.tsx`, Vitest/Playwright cases, `backend/src/main/java/kz/fin/backend/intake/{FormSchemaV5,ReportingPeriodV5}.java`, существующие Flyway migrations V9/V10, `src/adapters/zip-budget.ts`, `TrustedFinanceWorkspace.tsx`, `TrustedCrossFormDiagnosticService` и статус CI.

**Проверены именно два разных результата:** валидность локального Excel/React UI F02 и готовность его использовать как будущий источник server project-bound intake. Успех первого не означает готовности второго.

## 2. Подтверждённые дефекты и внесённые исправления

| Приоритет | Дефект до исправления | Изменённые файлы | Проверка |
|---|---|---|---|
| P0 | Загрузка XLSX асинхронна: пользователь мог переключить проект, язык или месяц до завершения `arrayBuffer()/xlsx.load`, а ответ присваивался текущему состоянию, нарушая контекст | `FormIntakeStudioV5.tsx`: inputGeneration/useRef guard, discarded stale result | Playwright тест с управляемой задержкой File.arrayBuffer и сменой проекта |
| P0 | Валидный шаблон на другом языке отвергался, несмотря на один машинный contract | `excel-v5.ts`: sourceLocale читается из manifest, сверяется с активными локалями; исходные заголовки/labels проверяются на языке файла, контекст данных — на выбранном проекте/периоде | Vitest RU workbook + EN display UI, Playwright EN workbook + RU UI |
| P0 | Excel upload сразу заменял заполненный React editor без явного подтверждения | `FormIntakeStudioV5.tsx`: из parse только Preview, кнопка «Перенести данные в редактор» как отдельное действие | Playwright: исходное значение редактора не меняется до нажатия |
| P1 | `Worksheet.actualRowCount` не максимальный номер строки; данные на далёком листе могли обойти простой подсчёт | `excel-v5.ts`: row.number и column index проверяются для каждой занятой строки/ячейки | Vitest: лишняя `FIN_Data!A900` отвергается |
| P1 | Instructions sheet мог быть подменён и показать неверные пользовательские инструкции без проверки | `excel-v5.ts`: ожидаемый текст/колонки/количество строк закреплены исходным языковым пакетом | Vitest: подмена инструкции reject |
| P1 | Ошибки общей F02 валидации имели только codes, без координат источника Excel и видимого перевода | `form-schema.ts`, `FormIntakeStudioV5.tsx`: локализованные сообщения + `FIN_Data!D#` по field ordinal | Vitest source cell mapping; UI renders human-readable error |
| P1 | RU/KK допускают запятую как десятичный разделитель, Java нормализовал только точку | `FormSchemaV5.java`: `canonicalUserDecimal(raw,sourceLocale)`, `normalizeF02(...,sourceLocale)` | JUnit RU/KK accepted, EN reject, exact values and invalid grouping |
| P1 | TS проверял только маску `YYYY.MM`, без общей границы поддерживаемых лет | `form-schema.ts`: диапазон 1900–9998, согласованный с Java `ReportingPeriodV5` | Vitest invalid year case |
| P1 | Excel показывал только 2 знака после запятой, хотя парсер сохранял до 12 — пользователь визуально не видел точную сумму | `excel-v5.ts`: numFmt с 12 дробными разрядами без принудительного округления | Vitest numFmt regression |
| P1 | Годовой переход в календаре был исправлен в предыдущей итерации | `reporting-period.ts`: year carry при month 12; Java `LocalDate` | Vitest/JUnit year rollover, ISO week W01/W53 |

Все исправления должны сохранять контроль старого `FIN-PILOT-V1` и React legacy. Ошибки, возникшие в промежуточных CI на SHA до исправлений, не скрывать; gate считать успешным **только по одному последнему SHA**.

## 3. Не устранено этим review — блокеры следующей серверной фазы

1. **Нет server-owned projectId** в V5 user flow; локальный `projectCode` — лишь label. Требуется `fin_project` с UUID, FK через organization, version, currency/calendar; ни spreadsheet metadata, ни имя компании не являются boundary.
2. **Нет server-authoritative PreviewSnapshot/confirmationDigest**, который сохраняет нормализованные rows в БД и привязывает их к выбранному проекту; текущий preview валидируется в браузере, статус `LOCAL_UNVERIFIED_DRAFT` обязателен.
3. **Нет согласованной схемы для F01/F03/DF-CSH-01/02** на новом универсальном типе ReportingPeriod, есть только F02 MONTH/KZT; V1 в backend остаётся отдельным, более узким форматом.
4. **Нет DiagnosticPresentationService**. `CrossFormDiagnosticEngine/TrustedCrossFormDiagnosticService` хранят immutable machine codes/numbers/provenance, но не проверенные RU/KK/EN/zh-Hans display bundle. Текст не должен менять `verdict`, raw values, `factSetRevisionId`, `sourceRevision`, `factId`, `snapshotHash` и S2 HOLD.
5. **ExcelJS в основном браузерном потоке**: ZIP central-directory budget — предварительная защита, не sandbox/timeout/cancellation. Hardened server upload требует собственные CPU/memory limits, строгий parser и безопасное хранение raw file.
6. **Excel manifest не криптографическое доказательство личности/авторства/подлинности**; future server сверяет координаты по БД, не использует veryHidden для access control.
7. **Полная языковая готовность zh-Hans не достигнута**; без утверждённых переводов на китайском отображать TRANSLATION_NOT_READY, не выдавать смешанный английский/китайский за готовый продукт.
8. **Различия бизнес-методик и типов покрытия периода** (`PERIOD_ONLY/YEAR_TO_DATE/AS_OF`) нельзя «легализовать» только фронтовым resolver; server должен version-pin semantic calendar, coverage, amount scale, currency.
9. **Долговечность черновиков**: текущий JSON скачать можно, но нет postgres idempotency, persisted revisions и автоматического сохранения изменений. Нельзя утверждать обратное.

## 4. Порядок и gates следующего этапа (после GREEN hardening)

**Server R0:** Flyway/pg identity project_id and scoped `project_context` + `project_draft` revision; RED tests tenant isolation, FK, unique revision, optimistic concurrency and immutable audit history.

**Server R1:** backend `FormSchemaV5` + localized F02 XLSX generator/parser via Apache POI; NOT reuse untrusted browser-provided normalization; exact canonical DTO, Java BigDecimal, reporting periods, currencies. Existing V1 routes untouched.

**Server R2:** validation → immutable `PreviewSnapshot` with canonical source hash/projectId/formVersion/month/money + error coordinates; explicit save/confirm with expectedRevision and idempotency. A malformed workbook must not change any prior draft.

**Server R3:** only after DB-driven source and preview proven: expand registry to F01 (AS_OF), F03 flows, cash ledgers and mixed period semantics with independently approved methodology.

**Language R0–R2:** separate immutable DiagnosticPresentationService to render *one* FactSetRevision and assessment snapshot in RU/KK/EN/zh-Hans using checked, versioned text packs. First tests pin unchanged `snapshotHash, methodVersion, rawDecimal, verdict, S2 qualification, missing, sourceRevision, factId`. Until translated finance copy is reviewed, Chinese remains not ready; no invented methodology.

**IAM/Keycloak:** not part of these development increments. Existing production HTTP endpoints remain DENY-ALL without configured identity; no anonymous bypass for E2E.

## 5. Acceptance condition

- Last HEAD has **verify, backend-verification, desktop-macos, acceptance-gate GREEN** all same commit SHA; read actual job results.
- Cross-language XLSX/manual canonical parity, missing vs 0, amountScale fidelity, tamper and negative-row rejection, project-change race, no editor-data overwrite.
- ProjectId/server Draft and diagnostic language rendering stay **NOT IMPLEMENTED** until own PostgreSQL/E2E and language coverage evidence; do not use current review as their qualification.
