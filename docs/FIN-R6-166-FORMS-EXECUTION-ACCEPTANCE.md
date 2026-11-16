# FIN R6 — разработка всех 166 форм: техническое исполнение и критерии приёмки

Дата: 2026-11-16. Репозиторий: alimbetov/fin-analyzer. Ветка: feat/fin-r0-r1a, PR #2 Draft; main не изменять.

## 1. Подтверждённая инвентаризация

Источник: backend/src/main/resources/fin/form-compatibility.tsv. Ровно 166 уникальных кодов.

| Группа | Кол-во | Исходные сведения | Текущая реализация |
|---|---:|---|---|
| DF-* | 59 | Поля и типы TYPED_FIELD_HINTS | Единый локальный typed Excel/React и Java/TS валидатор |
| SF-* | 63 | Поля и типы TYPED_FIELD_HINTS | Единый локальный typed Excel/React и Java/TS валидатор |
| Структурные / composite с CATALOG_SUMMARY | 32 | Описание назначения без полного typed field schema | В общем R6 блокируются до schema review; отдельные старые F01/F02/F03 продолжают работать |
| DOCUMENT и EXTERNAL_TAX | 12 | Требования к документам и налоговым источникам | EVIDENCE_ONLY; защищённый документный ingestion ещё не реализован |
| Итого | **166** | **122 typed + 44 иных** | **166 исполняемых readiness checks; 122 typed capture формы** |

Все 166 — каталожные сущности, НЕ 166 квалифицированных финансовых моделей. Отметка DESIGN_ONLY/PILOT_UNQUALIFIED сохраняется.

## 2. Реализованные R6.0 компоненты

- src/catalog-intake/catalog-schema.ts: единый TSV registry, строгая квалификация 166 кодов, typed validator для 122 полей с десятичной точностью, DATE, PERIOD, BOOLEAN, INTEGER, ENUM/CODE/REF, ROW_ID, неизвестных кодов, 200-строчного бюджета, locale RU/KK/EN и currency/scale context.
- src/catalog-intake/catalog-excel.ts: ExcelJS template FIN_Catalog/Instructions/_FIN_Metadata для каждого из 122. Локализованы заголовки и инструкции RU/KK/EN, но коды отдельных полей остаются кодами до финансового перевода. Excel parser проверяет schema/manifest, контекст, ZIP budget, формулы, дополнительные строки/колонки, источник и исходную locale.
- src/catalog-intake/CatalogIntakeStudio.tsx: 166 кодов в выборе; для 122 типизированных — tabular React editor, добавление строк, download XLSX, upload, read-only preview, явный перенос значений, локальный JSON с UNQUALIFIED_DRAFT; для остальных — понятная ошибка отсутствия утверждённой формы.
- src/styles.css: стилизованные таблицы с центрированием текста, выравниванием чисел вправо, вертикальным центрированием и внутренней горизонтальной прокруткой.
- backend/src/main/java/kz/fin/backend/intake/CatalogCaptureRegistry.java: независимое Java-чтение того же ресурса и typed validation для каждого кода.
- src/catalog-intake/catalog-schema.test.ts: positive/negative каждый из 122 typed, fail-closed все 44; drift, duplicate, bad money/date/period.
- src/catalog-intake/catalog-excel.test.ts: отдельный Excel generator/parse per formCode для всех 122, три локали, формулы и подмена контекста.
- backend/src/test/java/kz/fin/backend/intake/CatalogCaptureRegistryTest.java: JUnit DynamicTest для всех 166, Java canonical checking typed entries и запрет выдуманного числового документа.
- e2e/catalog-intake-r6.spec.ts: Playwright сценарии DF-AST-01, SF-C01, 166 вариантов, локальный Excel roundtrip и защита от silent overwrite.
- .github/workflows/ci.yml: обязательная проверка CatalogCaptureRegistryTest на каждом SHA. Старые F02/Java/Postgres/security tests сохранены.

Результат любого общего R6 Preview: UNQUALIFIED_DRAFT и publishable=false. Это не approved financial source, FactSetRevision или диагностика.

## 3. Незакрытые до готовности всех 166

### R6.1 — специальные 32

Для каждой из 32 структурных или составных форм: отдельно утверждённая versioned schema с реальными полями, обязательностью, аналитическими осями, отчётным календарём, денежной валютой и доказательством; независимые контрольные fixtures и отрицательные тесты. Не выводить форму из свободного текста описания каталога. Существующие F01/F02/F03 перенести на единый контракт без регрессии старых mapper и review.

### R6.2 — документные 12

Для D01–D08 и T01–T04 построить отдельный immutable evidence upload: MIME, ограничения размеров, вредоносные файлы, raw SHA, versions, dates, owner/tenant, проверка документа и срок действия. Произвольный PDF/Excel и автоматическая AI трактовка не считаются утверждёнными источниками.

### R6.3 — квалификация 122 типизированных схем

Для каждого источника нужно утвердить обязательные/необязательные поля, reference-коды и enum, ограничения количества и единиц измерения, бизнес-смысл stock/flow, granular grain, знак денег, выбранную отрасль, правила аналитических связей и инварианты. Ввод строки, которая соответствует типам, НЕ равен утверждённой методике расчёта. Нужны независимые golden examples и negative scenarios по каждой методике.

### R6.4 — четыре языка

Нужны проверенные RU/KK/EN/zh-Hans title/field labels, ошибки, description, причины и provenance. Сейчас интерфейс R6 переводит управляющие заголовки на RU/KK/EN, но финансовые имена полей и китайский не квалифицированы. Нельзя выдавать машинные коды или автоматический перевод за готовую 4-язычную финансовую форму.

### R6.5 — отдельные два обязательных server acceptance gate

1. Server Excel V2 Apache POI, projectId, normalized values, immutable PreviewSnapshot и DraftRevision, tenant permissions, versioned raw source.
2. React авторизация → серверная загрузка/preview/confirmation → last CurrentFormView/history/auto-refresh в двух реальных браузерах. Эти процессы остаются NOT IMPLEMENTED.

## 4. Приёмка по каждому коду

- Структурная: код единственный; однозначные поля и типы; отказ по unknown и подменённому контракту.
- Numeric typed: manual positive/negative, Excel create→edit→parse, exact decimal, missing vs zero, duplicate row, source row and field integrity, formula/ZIP/extra cells reject.
- Document: immutable original, tenant access, source proof, checksum, user-controlled review, no auto-approval.
- Domain: утверждённый расчёт, golden reference figures, sign/period/currency/units, применимость, S2 review.
- Browser: user can enter, see styled preview, confirm to server only after independent approval; no data loss.
- CI: TypeScript + JUnit + real PostgreSQL + Linux Chromium + macOS Chromium/WebKit + same SHA acceptance gate, no invented PASS.

## 5. Локальная проверка на MacBook

    git switch feat/fin-r0-r1a
    git rev-parse HEAD
    npm ci --no-audit --no-fund
    npm run typecheck
    npx vitest run src/catalog-intake
    mvn -B -ntp -f backend/pom.xml -Dtest=CatalogCaptureRegistryTest test
    npm run test:e2e -- --project=chromium --project=webkit

Если на MacBook не запущен Docker, реальные JPA/PostgreSQL интеграции отмечать BLOCKED. Не отключать production IAM ради тестов.

Окончательный вывод: R6.0 создал исполняемую основу для 166 форм и работающий локальный typed intake по 122 кодам. Это начало поэтапной реализации, а не завершение всех 166 финансовых методик.
