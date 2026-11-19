# FIN FACT — Technical S1 Review of INGEST-0 / INGEST-1 and 166-Form Mock Unit Tests

**Дата:** 2026-11-19  
**Репозиторий:** `alimbetov/fin-analyzer`  
**Ветка:** `feat/fin-form-library-standalone`  
**Кодовый SHA, прошедший CI:** `3401bbda096c0f34ddea66f7145878cfdef5752e`  
**Ревью:** код и каталог + проверка реальных GitHub Actions логов; это **не** независимый финансовый S2.

## 1. Решение S1

- **INGEST-0 — UNIT/CATALOG GREEN**: 166/166 зарегистрированных форм, 3 SINGLE_RECORD + 151 ROWSET + 12 EVIDENCE, запрет неизвестных кодов/неподходящего contentMode, SHA реестра.
- **INGEST-1 — UNQUALIFIED MOCK EXTRACTION GREEN**: 166/166 реальных схем физических полей из catalog seed contract проверены синтетическими снимками; 665 новых тестовых кейсов (4 на форму + 1 инвариант каталога) GREEN. Выход: `UNQUALIFIED_SOURCE_PROJECTION`, `SOURCE_SHA_NOT_REVERIFIED`. НЕТ присвоения квалифицированного metricCode.
- **PRODUCTION fin_fact / S2 — HOLD**: новый слой НЕ пересчитывает исходный FIN-LIB-CONTENT-V1, не создаёт factset, не пишет partitioned fact observations, не утверждает поля/методики; полный E2E ещё отсутствует.

**Подтверждение на точном SHA:**
- [FIN Form Library Java 21 H2 — GREEN](https://github.com/alimbetov/fin-analyzer/actions/runs/44467526181). В логах: `FormFact166CatalogMocksTest: 665 run, 0 failures, 0 errors, 0 skipped`; полный модуль `Tests run: 863, Failures: 0, Errors: 0, Skipped: 10`, `BUILD SUCCESS`.
- [FIN Form Studio React/Backend — GREEN](https://github.com/alimbetov/fin-analyzer/actions/runs/44467526183): также GREEN в отдельном `PostgreSQL 16 / fin_intake REST qualification` job, Spring Boot / H2 regression и React typecheck/build.
- [FIN UX-R1.1 Chromium browser — GREEN](https://github.com/alimbetov/fin-analyzer/actions/runs/44467526160) — terminal workflow **SUCCESS** on the same code SHA. Это подтверждает пользовательскую регрессию, но не новый qualified fact pipeline.

## 2. Исходные данные тестов — не выдуманные произвольные поля

Новый `FormFact166CatalogMocksTest` использует **те же исходные каталоги**, по которым `CatalogBootstrap` создаёт физические поля в текущей версии:
1. `form-compatibility.tsv` — 166 форм и capture types.
2. `specialized-schema-candidates-r6.tsv` — отдельные специализированные поля.
3. `intake-fields-v5.tsv` — три реальных input-поля F02.
4. `form-field-presentation-review-r6.tsv` — 794 кандидата; все 793 соответствующих физических source-поля валидированы по коду+типу, `F02.netProfit` проверен как единственный orphan derived candidate.
5. Реальные evidence-поля (7 для каждой из 12 DOCUMENT/EXTERNAL_TAX форм) точно по `CatalogBootstrap`: `documentRef, documentType, documentDate, version, issuerRef, attachmentRef, status`.

**Проверенные инварианты:** 877 физических полей = **793 SOURCE_FIELD + 84 EVIDENCE_METADATA**; 794 R6 кандидата = 793 source + 1 `F02.netProfit` derived. Это разная численность и её нельзя механически складывать.

## 3. Структура mock-кейсов — 166 × 4 + 1 = 665

| Проверка | Форма/объём | Ожидание |
|---|---|---|
| P1 — Positive full-field mock | Все 166 | Все реальные fieldCode/types -> typed projection; exact revision/formVersion/scope; numeric count+attribute count; no S2 |
| P2 — Wrong mode | Все 166 | `SOURCE_MODE_CONTRACT_MISMATCH`; не переходить в чужой extractor |
| P3 — Unknown source field | Все 166 | `UNKNOWN_SOURCE_FIELD`; не импортировать чужой/unreviewed key |
| P4 — Bad source payload | Все 166 | Для числовых форм invalid exponent; для ненумерических ROWSET duplicate row UUID; для EVIDENCE bad attachment SHA; для single nonnumeric DATE/null |
| Catalog integrity | 1 | Exact seed schema 877 fields, R6 794 descriptions, 166 profiles, F02 derived exclusion |

Дополнительно существующие тесты: `FormFactCandidateExtractorTest` — 9; `FormFactSourceReaderTest` — 5; `FormFact166ModeRoutingTest` — 1. Всего в этих четырёх test classes — **680** unit cases (665+9+5+1). Остальные 183 из 863 системного Java/H2 цикла — предшествующая регрессия, часть с пропущенными проверками.

**Mock value policy:** deterministic UUIDs; MONEY=123.45 / 0; DECIMAL=12.5 / 0; INTEGER=42 / 0; DATE=2026-10-15/16; BOOLEAN=true/false; REF/CODE/TEXT/ENUM synthetic literals. ROWSET has two independent row UUIDs and values; EVIDENCE has seven metadata fields + one SHA-addressed doc ref. Этот seed проверяет типизацию и структуру, **не финансовую семантику**, периоды или реальные клиентские данные.

## 4. Обнаруженные дефекты и исправления

### S1-I01 — P1: прежний тест скрывал непроверенные поля

`FormFact166ModeRoutingTest` создавал один generic `fixtureAmount` для каждой формы; passing не означал обработку actual fields. **RESOLVED IN TESTS**: новый per-form mock contract из физических seed catalogs, строгий cross-check кодов и типов, отдельные кейсы по каждому profile.

### S1-I02 — P1: неверное предположение о порядке ROWSET

Первый прогон нового теста на `10524f6c3e` дал **81 FAILURE из 665**: тест ожидал порядок `row-1,row-2`, а реализация правильно сортирует кандидаты по **UUID lexical order**. Фактическая ошибка была в самом test assertion, не в алгоритме; поле+rowId должны сопоставляться по идентичности, не по индексу. **RESOLVED** в `3401bbda...`: сравнение через map<UUID,BigDecimal> и проверку значений по ключу; 665/665 PASS.

### S1-I03 — P0 production gate: канонический SOURCE SHA не проверяется

`FormFactSourceReader` возвращает stored `canonicalContentSha256`, а `FormFactCandidateExtractor` проверяет **формат** SHA, не пересчитывает `FIN-LIB-CONTENT-V1` из оригинального scope/form version/rows/evidence. Результат намеренно маркирован `SOURCE_SHA_NOT_REVERIFIED`. **OPEN → INGEST-2**: переиспользовать точную каноникализацию из `FormDraftService.save`, включая row-order SHA и attachment metadata/name, proof на PostgreSQL 16.

### S1-I04 — P0 financial gate: 166 metric mappings не утверждены

Распознавание type+fieldCode не создаёт финансовый metric identity: нет утверждённых granular dimensions, единиц, sign/aggregation policy, timeSemantics, idempotent qualified fact membership и real S2. **OPEN**: per-formVersion field/metric contracts, не использовать candidate mapping как S2. Никакой SQL MERGE/INSERT в `fin_fact` в INGEST-1.

### S1-I05 — P1 access control: reader использует лишь org+project

`FormFactSourceReader.readExact` сверяет `organizationId` и `projectId`, но эти UUID — параметры вызова, не JWT/ABAC proof; `economicEntityId/businessScopeId` пока присутствуют в возвращённом scope, однако не фиксируются в отдельном request-authority contract. **OPEN**: before public API/production caller require a trusted full-scope principal/ABAC, no client-controlled org override. Сервис сейчас внутренний.

### S1-I06 — P1 source normalization and evidence gaps

Нечисловые `REF/CODE/ENUM/PERIOD` в кандидате сохраняются как строковые атрибуты; domain semantic validation и источники ref ещё не прошли S2. Evidence hashes/references представлены как метаданные, но сами байты документа не перепроверяются в INGEST-1. ROWSET сохраняет row UUID, однако positional/order SHA отсутствуют в projection output. **OPEN → INGEST-2/3**: complete pinned source digest, dimension and temporal policy; don't promote to fact until resolved.

### S1-I07 — P1 test scope ambiguity

665 tests are **synthetic full-schema unit tests**, not 665 independently qualified financial calculation cases or 166 PostgreSQL FactSets. 10 skipped out of 863 overall Java/H2 tests must be reported transparently; separate existing PostgreSQL 16 `fin_intake` job GREEN is **not** new `fin_fact` E2E. **DOCUMENTATION FIXED** in this review.

## 5. Required hard gates for next implementation

**INGEST-2 — Source SHA Reverification**:
- Extract exact shared `FIN-LIB-CONTENT-V1` canonical serializer from `FormDraftService` with no changed historical contract.
- Recompute from pinned `lib_form_revision` and `lib_form_revision_row` and evidence ref bytes/metadata where required; validate source/row order hashes.
- PostgreSQL 16 Testcontainers real saved revisions with SINGLE_RECORD/ROWSET/EVIDENCE, tampering, old revision vs latest, wrong formVersion, scope collision, evidence mismatch.
- Only change source integrity status from NOT_REVERIFIED after **actual** rehash PASS; do not change financial qualificationState.
- Add negative source mutation and concurrent save+reader tests to CI.

**FACT-0 / FACT-1** remain next physical and qualified work: Liquibase 019/020, monthly partitions, approved exact test-only F02 mapping, transactionally materialized immutable facts, trusted outbox and guarded workers. Until then **FIN HTAP qualified pipeline HOLD**.

## 6. S1 decision / evidence discipline

| Layer | Decision | Evidence |
|---|---|---|
| INGEST-0 registry | **GREEN** | Catalog and physical R6 bootstrap 166/877 consistency |
| INGEST-1 Java candidate extraction | **GREEN for unqualified mock mode** | Exact SHA `3401bbda...`, 665/665 synthetic tests, overall Maven GREEN |
| Existing application regression | **GREEN on code SHA** | Backend/H2/PostgreSQL fin_intake workflow success |
| Source verified candidate | **HOLD** | Source hash not recomputed |
| Financial metric qualification | **HOLD** | Unreviewed mappings and S2 |
| Persisted partitioned fin_fact | **HOLD** | Not implemented in this branch |
| FIN CALC immutable qualified E2E | **HOLD** | FactSet, queue and result pipeline not implemented |

**Conclusion:** 166-form mock data unit-test objective achieved at read-only candidate-extraction layer. No false claims about financial method coverage, S2 or partitioned PostgreSQL output. The right next slice is **INGEST-2 source-content SHA verification**; do not deploy synthetic candidate results as financial facts.
