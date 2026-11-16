# FIN R6.1–R6.4 — полный охват каталога 166: исполнение, финансовая квалификация, Excel и документы

Дата: 2026-11-16. Репозиторий alimbetov/fin-analyzer, branch feat/fin-r0-r1a, PR #2 Draft. Не менять main.

## 1. Целевой контракт «полностью готово»

Полностью готовая форма должна иметь: (a) утверждённые поля и семантику, (b) корректные RU/KK/EN/zh-Hans названия, инструкции и сообщения ошибок, (c) бизнес-правила и независимые контрольные данные, (d) удобный Excel и React, (e) защищённую серверную загрузку projectId и организационный ABAC, (f) неизменяемые версии raw и normalized data, (g) server PreviewSnapshot/CurrentFormView с provenance, (h) подходящую методологию анализа с S2 review, (i) тесты на одном SHA. Для документальных форм финансовые KPI не выдумывать: оригинал, срок действия, подтверждение и права обязательны, автоматическая интерпретация AI не является утверждённым фактом.

## 2. Исполняемая R6.1 инвентаризация

| Формы | Кол-во | После R6.1 | Ограничения |
|---|---:|---|---|
| 59 DF + 63 SF | 122 | Локальный Excel, импорт с проверкой, React-редактор и Preview, TS + независимая Java schema validation | Нет независимой методической квалификации всех 122 |
| C01–C14, F01–F08, P01–P10 | 32 | Версионированные schema candidates, локальный React/Excel, предварительная типизация и точные формальные инварианты | Кандидат, а НЕ утверждённая схема; нет независимого S2 и server domain API |
| D01–D08, T01–T04 | 12 | Локальная инспекция файла, MIME/signature checks и SHA-256, видимый результат | Нет защищённого server ingestion, antivirus, immutable raw storage, approval |
| **Всего** | **166** | **166 кодов имеют специализированный локальный структурный/документный маршрут** | **0 новых форм нельзя объявлять production ready по одному коду каталога** |

Существующие F01/F02/F03 остаются отдельными пилотными серверными контрактами. Кандидат F02 в общем R6 имеет другой уровень доверия, не должен мигрировать в FACT без явного reviewed conversion. Старая validated F02 не заменена.

## 3. Реальный код

- backend/src/main/resources/fin/specialized-schema-candidates-r6.tsv: 32 явно перечисленных специализированных кандидата; поля и типы взяты как контролируемые проекты на основе catalog summary, qualification=SCHEMA_CANDIDATE_NOT_APPROVED; нельзя выдавать за нормативные методики.
- src/catalog-intake/specialized-contracts.ts: ровно 32 кода, нет unknown/duplicate; BigInt × 10^12 без JS Number для предварительных равенств F01/P01 (баланс), F03 (движение денежных средств), F04 (движение капитала), P03 (план денег), P04 (погашение тела долга); отрицательные и независимые golden cases. F06 trial balance требует нормальной стороны счета — универсальное равенство пока НЕ применяется. Композитные формы F05/F08/P07/P09 работают через ссылки на источники, а не изобретённые денежные метрики.
- src/catalog-intake/catalog-excel.ts: FIN-CATALOG-XLSX-2, отдельный отчётный паспорт в Instructions (status, organization unbound, form, project, period, currency, scale, version, locale), field help/units, visible pilot field labels, проверяемая schema signature; предшествующий FIN-CATALOG-CAPTURE-1 импортируется без потери совместимости; Excel draft для 122 typed и 32 candidate.
- src/catalog-intake/CatalogIntakeStudio.tsx: 122+32 локальные табличные формы и 12 document local-only file preflight; никакого анонимного POST, никакой фиктивной публикации.
- src/catalog-intake/evidence-contract-r6.ts: локальный контентный SHA-256 и первичная проверка PDF/DOCX/XLSX/XML/PNG/JPG для 12 документных форм; ни содержимое, ни метаданные не помещаются в PostgreSQL и не выдаются за просканированный/подтверждённый документ.
- backend/src/main/resources/fin/form-field-presentation-review-r6.tsv: 794 уникальные пары formCode + fieldCode. RU, KK, EN, zh-Hans, review status, unit policy и requiredness. Только 9 PROVISIONAL_NOT_S2 с явными предварительными текстами (6 DF-AST-01 × 4 языка, 3 F02 × RU/KK/EN). Остальные 785 PENDING_TRANSLATION. Обязательность у всех 794 полей PENDING_METHOD_REVIEW: её нельзя вывести из типа данных.
- src/catalog-intake/field-presentation-r6.ts: fail-closed parser на 794 записей, запрещает APPROVED и несуществующие provisional labels. Технический код показывается только там, где подтверждённого/предварительного человеко-понятного текста ещё нет. Наличие перевода даже с предварительным текстом не доказывает лингвистическую сертификацию.
- Tests: specialized-contracts.test.ts (32 per-code typed/negative + independent exact amounts), catalog-excel.test.ts (122+32 XLSX roundtrip and legacy compatibility), evidence-contract-r6.test.ts (12 source codes and security preflight), field-presentation-r6.test.ts (794 exact associations); e2e/catalog-intake-r6.spec.ts (F04, D01, existing DF/SF).

## 4. Что НЕ готово и не должно подменяться зелёными структурными тестами

**R6.1 economics:** независимые формулы, source sign conventions, cut-off, closing vs movement, дебет/кредит нормально-пассивных счетов, currency conversion, account and counterparty dictionaries, stock vs flow. Кандидат с корректной арифметикой не равен финансово квалифицированной форме.

**R6.2 evidence:** safe raw object store, virus/quarantine, secure server MIME/ZIP validation, period and organization binding, owner/reviewer roles and access lifespan, immutable source versions, issuer authenticity, audit trail. File hash in browser is not server evidence.

**R6.3 all 122:** field-level unit catalogs, requiredness, valid ranges, enum dictionaries, methodology, row-level referential integrity, sector-specific formula/golden fixtures, independent S2 finance sign-off.

**R6.4 four languages:** professional reviews of all 794 form-field translation slots, form names, help, instructions, validation findings, numeric diagnostic explanations and provenance. F02 RU/KK/EN legacy labels not Chinese completeness. Do not construct Chinese or Kazakh by automatic fallback.

**R6.5 server:** Apache POI FIN-CATALOG-XLSX-2 and specialized schema parser, protected projectId/JWT organization-scoped Preview/Confirm/current/revisions, two-user React auto-refresh, immutable raw plus normalized and source review; old FactSetRevision must never silently update.

## 5. Acceptance matrix / release decision

Do not change PR #2 Draft or release from HOLD until all:
1. 166/166 per-code code + data contract; document vs numeric type respected.
2. 794/794 field semantic review, including requiredness, unit/scale, negative fixture and four validated languages.
3. 32 specialized financial models get approved independent golden fixtures + S2.
4. 12 documents get secure file store, MIME/AV, sha256, signed access, version chain, origin review.
5. No publishing from UNQUALIFIED_DRAFT, PROVISIONAL_METHOD or LOCAL_UNVERIFIED_EVIDENCE.
6. PostgreSQL + Java/JUnit + TypeScript/Vitest + Linux Chromium + macOS Chromium/WebKit + authenticated two-user smoke all GREEN on **one commit SHA**.

Pending complete qualification, current implementation is demonstrable LOCAL STRUCTURAL DRAFT COVERAGE, NOT production financial assessment across all 166.

## 6. Local review commands

    git switch feat/fin-r0-r1a
    git rev-parse HEAD
    npm ci --no-audit --no-fund
    npm run typecheck
    npx vitest run src/catalog-intake
    mvn -B -ntp -f backend/pom.xml test
    npm run test:e2e -- --project=chromium --project=webkit

Spring/Keycloak and live server Excel require independent tests and should never be bypassed through anonymous APIs.
