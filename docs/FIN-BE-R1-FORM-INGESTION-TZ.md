# FIN-BE-R1 — ТЗ: Backend загрузка финансовых форм

**Приоритет:** P0. **Статус:** SPECIFIED / NOT ACCEPTED. **Ветка:** `feat/fin-r0-r1a`.  
**Цель:** загрузить и проверить форму с точным периодом, валютой, масштабом, организацией, источником и полями, сохранить immutable lineage в PostgreSQL и выпустить канонические факты только после действующей независимой проверки.

## 1. Текущий код и ограничения

- `PilotXlsxTemplateService`: Java Apache POI XLSX issuer/parser для **F01, F02, F03, DF-CSH-01, DF-CSH-02**, `FIN-PILOT-V1`, расширение F01 `FIN-LIQUIDITY-V1`; bounded 2 MB / 512 rows / 16 MB expanded ZIP; checks формул, схемы листов и metadata.
- `ReviewedFinancialIntakeService`: manual/XLSX submission, raw SHA256, reviewer ledger и вызов `FactSetPublicationService`. `TrustedFinancialFormMapper`: pilot source-field→fact.
- `FormSchemaV5` + `FormDraftRevisionV5Service`: F02 draft/preview/confirmation/history; **draft ≠ ACTUAL**.
- `CatalogCaptureRegistry`: 166 описаний: 122 typed draft, 32 specialized schema candidates, 12 evidence-only. Существование в реестре не означает, что Java может импортировать 166 Excel-шаблонов или публиковать их numeric values.
- `ReportingPeriodV5` поддерживает WEEK/MONTH/QUARTER/HALF_YEAR/YEAR/AS_OF и coverage; XLSX pilot использует `PERIOD_YYYY_MM`.
- **Подтверждённый пробел P0:** `PilotXlsxTemplateService.Context`/`_FIN_Metadata` не хранят ожидаемый отчётный месяц выдачи шаблона; нельзя считать произвольный месяц внутри строки подтверждением запрошенного периода.

## 2. Work packages

### R1-A — Реестр возможностей server-side (P0)

Составить машинно проверяемую матрицу `formCode,formVersion,sourceKind,periodGrain,sourceSchema,parser,mapper,reviewGate,testId,status`. Статусы `SUPPORTED_PILOT / DRAFT_ONLY / SCHEMA_CANDIDATE / EVIDENCE_ONLY / UNSUPPORTED`. Фальшивые numeric поля для evidence-only не создавать. Не подключать React/Keycloak.

### R1-B — Versioned identity/period-bound XLSX (P0)

Новый versioned template/issued-manifest должен связать server-side issued context и **expected period** вместе с `organizationId/entityId/businessScopeId/projectId (если применимо), form/version, basis, currency, scale`. Изменение `2026-10` на `2026-11` при той же выдаче → reject **до записи БД**. Подделка metadata сама по себе не доказывает аутентичность; token или signature не изобретать. Не переписывать legacy `FIN-PILOT-V1` — сохранить парсинг и задать для legacy explicit compatibility/approval policy.

Проверки: worksheet metadata, обязательные row IDs и source fields, дубликаты координат, sparse extras (A900), extra sheets/cells, formulas/error cells/external links, ZIP bomb, big/negative decimal и precision/scale, unsupported locale/currency, mixed periods, blank vs explicit zero, date/as-of, repeat upload. Нет silent normalization и частично сохранённых сущностей.

### R1-C — Preview/confirm/source revisions (P0)

- Java preview = результат **той же** нормализации и правил, что persistence; immutable digest/field delta/period metadata. Два конкурирующих подтверждения → только один новый current revision через CAS/unique constraint; stale digest/revision rejected.
- XLSX raw bytes сохранять с серверным SHA256; ручной ввод получает отдельный input-manifest digest (не объявлять external source attestation). Provenance: sourceSubmissionId, rawSha, sourceRevision, form/field/row/period, normalizationVersion → factId.
- Действующие этапы: `DRAFT → SUBMITTED → REVIEWED → APPROVED_SOURCE → PUBLISHED_FACT_SET`; различие сохранять в БД/DTO и запретах. Новая версия данных не переписывает предыдущую.
- Дублирующаяся F02 revenue из summary/detail/sector sources должна подвергаться reconciliation, а не складываться трижды.

### R1-D — Расширение каталога после пилота (P1)

Сначала закончить 5 XLSX форм и F02 V5, затем переводить **122 typed** в Java draft intake по явным семантическим схемам. **32 specialized** остаются unqualified до field/financial review; **12 evidence-only** получают только отдельный документальный тип, не numeric import. Заявлять количество реализованных форм только по независимым рабочим backend tests.

## 3. Критерии приёмки

| Test ID | Синтетический сценарий | Ожидаемое доказательство |
|---|---|---|
| BE-R1-01 | Корректные F01/F02/F03/DF-CSH-01/02 | Exact cells/period/scale survive XLSX → PostgreSQL |
| BE-R1-02 | File from 2026-10 submitted for 2026-11 | PERIOD_CONTEXT_MISMATCH, zero INSERT |
| BE-R1-03 | Foreign org/entity/basis, USD→KZT, x1→x1000 | Reject, no source mutation |
| BE-R1-04 | Formula, error, ZIP expansion, extra sheet/column, sparse extra row | Controlled rejection + bounded CPU/memory |
| BE-R1-05 | Duplicate field, wrong row-grain, unknown field, missing cell | Explicit validation, never default 0 |
| BE-R1-06 | Reupload identical/changed source | Documented dedup/immutable new revision, old preserved |
| BE-R1-07 | Two concurrent confirms of same expected revision | Exactly one current revision, other conflict |
| BE-R1-08 | Crash in upload/review or DB constraint violation | Atomic rollback, no orphan raw source/facts |
| BE-R1-09 | Legacy v1 and versioned v2 | Old parser compatible, new period-bound gate enforced |
| BE-R1-10 | Unqualified specialized/evidence-only | Cannot create trusted numeric ACTUAL |
| BE-R1-11 | Revenue detail 600+400, headline F02=1000 | Verified canonical revenue=1000, not 2000 |
| BE-R1-12 | Missing vs explicit zero, decimal 38/12 | Distinct states and exact BigDecimal handling |

## 4. Test suite и артефакты

Расширить `PilotXlsxIntakeIntegrationTest`, `PilotXlsxStructuralTransferTest`, `ReviewedFinancialIntakeIntegrationTest`, `FormDraftRevisionV5IntegrationTest`, `TrustedFinancialFormMapperIntegrationTest`, `PostgresInvariantTest` и `FactPublicationIntegrationTest`. Golden исходники под `backend/src/test/resources/fin/intake/`; expected сохранять независимо от parser. Flyway только additive, проверить fresh и existing database через Testcontainers. Приёмка — живой backend path `XLSX→submit→review→factSet` для каждого поддерживаемого пилота, матрица unsupported, exact SHA+CI.

**Не входит:** frontend, IAM/Keycloak, все 166 как approved numeric формы, автоматическое S2 финансовых методик, внешние интеграции ERP.

**DoD:** документированы реальные supported/unsupported контракты; каждая принятая форма проходит JUnit/PostgreSQL; тесты отрицательных файлов выполняются; старые источники/версии не ломаются; ACTUAL разрешён только согласно существующему review и qualification gate.

