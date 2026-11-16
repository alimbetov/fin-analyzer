# FIN-BE-R1 — ТЗ: Backend загрузка финансовых форм

**Приоритет:** P0. **Статус:** SPECIFIED / NOT ACCEPTED. **Ветка:** `feat/fin-r0-r1a`.  
**Цель:** обеспечить серверную генерацию Excel-шаблонов и воспроизводимую обратную загрузку **для всех 166 форм каталога** с разделением на 154 структурированные и 12 документальных, с точным контекстом периода/валюты/масштаба и immutable lineage в PostgreSQL. Канонические финансовые факты публиковать только после существующей независимой проверки и квалификации.

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

### R1-D — Полный охват каталога 166: генерация и импорт (P0 после пилотной платформы)

Целевой охват **166/166 кодов**, не ограничиваться пятью пилотами. Этап делить на проверяемые порции: (1) существующие 5 Java-пилотов (их контракты не переписывать); (2) 122 формы `TYPED_DRAFT` из `form-compatibility.tsv`; (3) 32 `SCHEMA_CANDIDATE` из `specialized-schema-candidates-r6.tsv`; (4) 12 `EVIDENCE_ONLY` — отдельный реестр метаданных документов и приём оригинального вложения через внутренний backend service. Совпадающие коды пилота и каталога сохраняют разные versioned контракты. **Все формы обязаны иметь работоспособный целевой маршрут генерации и обратного импорта**, но 122/32 не получают автоматического права публикации `ACTUAL`, а 12 документальных не превращаются в числовые формы.

Поформенная baseline-матрица: [FIN-BE-R1-166-FORM-EXCEL-COVERAGE.tsv](FIN-BE-R1-166-FORM-EXCEL-COVERAGE.tsv). Она содержит ровно 166 строк, тип, источник схемы, количество полей, существующий legacy pilot, ожидаемые режимы генерации/импорта и уникальный test ID. Поле `TARGET_NOT_IMPLEMENTED` — **целевой незакрытый статус**, не отчёт о результатах теста. Матрица должна стать машинно проверяемым gate, автоматически сопоставляемым с обоими source registries.

**Не разрешается** заменить реальную загрузку тестом, который лишь проверяет существование кода формы в каталоге; все 166 должны пройти фактический Java-генератор/парсер, а structured forms — также сохранение/чтение draft в PostgreSQL. Для 12 документальных проверять и Excel-реестр, и исходное вложение как отдельные данные.

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


## 5. R1-E — Серверная генерация Excel-шаблонов для всех форм (P0)

### 5.1. Единый backend generator, не frontend fixture

Спроектировать внутренний Java/Apache POI \`ServerFormXlsxService\` (название рабочее) поверх **существующих** реестров, а не дублировать 166 схем в новом Java switch. Выход версии \`FIN-BE-CATALOG-XLSX-V1\` должен быть создан именно сервером и принят **серверным** парсером на тех же семантических координатах. Совместимость \`FIN-PILOT-V1\`, \`FIN-LIQUIDITY-V1\` и локального \`FIN-CATALOG-XLSX-2\` анализировать отдельно; не считать их взаимозаменяемыми по одному formCode.

Для **122 typed** таблица колонок следует исходным \`fieldContract\`; для **32 candidate** — \`SpecializedSchemaCandidateRegistry\` и qualification=SCHEMA_CANDIDATE_NOT_APPROVED. Учитывать все **794 пары formCode+fieldCode** из \`form-field-presentation-review-r6.tsv\`; их текстовые переводы и \`requiredness\` преимущественно PENDING и **не должны** выдаваться за утверждённые требования. Схема полей — authoritative для структуры, а presentation-ресурс — только для явно помеченных provisional подсказок, без выдуманных RU/KK/EN/ZH наименований. При отсутствии утверждённого перевода использовать fieldCode с явной пометкой \`UNREVIEWED_LABEL\`.

Для **12 DOCUMENT/EXTERNAL_TAX** генерировать XLSX **реестра/описи** (document reference, document type, date, version, issuer/reference, expected period, attachment reference и status) без попытки поместить доказательство PDF/DOCX/изображения в финансовые ячейки. Excel-реестр сам по себе **не подтверждает наличие и качество оригинала**: отдельный ingestion original bytes и связанные hashes обязательно проверяются отдельными тестами. При отсутствии существующего безопасного server API использовать internal Java service/Testcontainers; **новые IAM/Keycloak/HTTP endpoints не создавать**.

### 5.2. Контракт шаблона и паспорт

Каждый сгенерированный XLSX содержит: Instructions (как заполнять, статус draft/unqualified, supported grain, лимиты), FIN_Data либо табличный аналог, hidden \`_FIN_Metadata\` с immutable template version, formCode/formVersion, schema fingerprint, source catalog version, org/entity/businessScope/project (где применимо), reportingBasis, reportingPeriodType/code/coverage, currency, amountScale, typed fields, generatorVersion и issued-template manifest reference. Проверять серверное соответствие паспортных значений выдаче; XLSX metadata **не является подписью/доказательством внешнего происхождения**.

Строка заголовков, порядок/наименования полей, типы, допустимые значения и grain детерминированы. Пустые шаблонные строки не равны нулю; для multi-row форм зарезервировать повторяемые \`sourceRowId\`, для snapshot-stock форм явно задавать \`asOf\`. Даты и даты-периоды не заменять случайными Excel serials без проверенной canonical conversion. Типы MONEY/DECIMAL/INTEGER должны иметь exact parsing и проверку Excel numeric precision loss; текстовые числовые literals допустимы как каноническая безопасная форма.

Сгенерированные шаблоны должны открываться Apache POI, корректно отображать границы таблицы, frozen headers, формат вводимых ячеек, валидированные подсказки/списки где утверждены. Никаких macro, formulas, external links, скрытых подставленных числовых фактов или исполняемых workbook connections. Стабильный порядок форм/полей и digest canonical schema; бинарная идентичность двух XLSX необязательна из-за ZIP metadata, но logical structural fingerprint должен совпадать.

### 5.3. Уровни Excel-проверки

- \`TEMPLATE_CREATED\`: реально вызван Java generator, байты открыты независимым Apache POI reader, структура и версия соответствуют каталогу.
- \`TEMPLATE_EMPTY_ROUNDTRIP\`: свежий незаполненный файл распознан как корректный пустой шаблон и не создаёт fake data/facts.
- \`TEMPLATE_FILLED_ROUNDTRIP\`: тест заполняет **выданный генератором** workbook типизированными synthetic values для всех полей и нескольких исходных строк, сохраняет, парсит, сверяет каждое нормализованное значение.
- \`UPLOAD_PERSISTED\`: parsed draft либо document manifest + original attachment реально вставлены в PostgreSQL и прочитаны обратно, с scope/source hashes.
- \`NEGATIVE_UPLOAD_VERIFIED\`: испорченный/чужой/устаревший template отклонён с доказательством отсутствия частичного сохранения.
- \`FACT_QUALIFIED\`: только после отдельно доказанной канонической публикации, review и финансовой S2. **Не является обязательным для 166/166 Excel coverage**, поскольку существующие 122/32 ещё не одобрены методически.

## 6. R1-F — Настоящий серверный импорт всех 166: исполнение, а не schema-only test (P0)

1. **154 структурированные формы**: один versioned backend import SPI, который разбирает server-issued XLSX, сверяет schema/period/entity/currency/scale, normalizes canonical types, делает server preview и записывает immutable DRAFT; повторное чтение из PostgreSQL должно полностью совпасть с ожидаемой структурой и значениям. Различать 122 typed и 32 candidates; candidate только \`UNQUALIFIED_SCHEMA_DRAFT\`. F01/F02/F03 + DF-CSH-01/02 legacy review→FactSet проверяются отдельно по действующему пилотному контракту.
2. **12 документальных форм**: server-issued XLSX реестр, проверка обратно загруженных метаданных и **отдельно загрузка оригинального файла** (supported PDF/DOCX/XLSX/XML/PNG/JPG по фактической allowlist и сигнатурам) во внутреннее quarantine/draft хранилище с SHA256, size/MIME/type, immutable source reference. Блокировать попытку передать только имя файла или Excel-ссылку вместо оригинала. Не заявлять antivirus/attestation, пока их нет; не развивать новую систему безопасности, public endpoint остаётся закрыт до отдельного решения.
3. **Персистентность**: если текущие V5 draft tables не способны хранить multi-form/multi-row/attachment, проектировать только additive Flyway таблицы с \`sourceKind,templateVersion,formCode,period,revision,fieldMap,rawSha,manifestSha,qualStatus\` и индексы. Существующие \`fin_intake.submission\`, \`form_draft_revision_v5\`, \`fin_fact\` нельзя менять несовместимо либо засорять candidate numeric facts; сначала выполнить schema review.
4. **All-forms import evidence**: для каждого из 166 форм динамический JUnit тест обязан реально исполнить \`generator → populate source/manifest → serialize XLSX → server import → normalized compare → PostgreSQL persisted readback → negative case\`; для DOC отдельно приложить original. **Тесты, которые только вызывают \`CatalogCaptureRegistry.validate\` или \`SpecializedSchemaCandidateRegistry\`, не засчитываются как импорт**.
5. Дополнительно типизированные границы по каждому FieldType (MONEY, DECIMAL, INTEGER, DATE, PERIOD, REF, CODE, ENUM, TEXT, BOOLEAN): 0/NULL/blank, duplicate sourceRowId, extra column, requiredness only if approved, locale/decimal separator, out-of-range, x1/x1000, unsupported date/period/grain. Условия stock-vs-flow, currency и evidence-only обязательны; чужой issued context никогда не принимается.
6. Для массового smoke использовать синтетические данные и фиксированный seed. Не создавать 166 отдельных ручных Excel-файлов как источник истины: тест наполняет реальный шаблон по реестру и независимо подготовленному type fixture. Для finance-golden после этого требуются отдельные внешние expected values по каждому одобряемому варианту.

### R1-F приёмка по всему каталогу

| Целевой показатель | Число | Что действительно нужно проверить |
|---|---:|---|
| Реестр найден и без дубликатов | 166/166 | Server catalog + schema fingerprints + classification |
| Server XLSX template generation | 166/166 | 154 data templates + 12 document register templates |
| Server XLSX round-trip | 166/166 | **Каждый** реальный сгенерированный workbook и valid sample |
| PostgreSQL import/persist/readback | 166/166 | 154 draft values + 12 original evidence + register, без ложной публикации |
| Numeric draft schema coverage | 154/154 | 122 typed + 32 unqualified specialized |
| Документное доказательство загрузки | 12/12 | 12 реальных файлов (синтетические fixtures) + provenance |
| Field-level schema correspondence | 794/794 | Pair+type в двух backend source registries, без новых invented fields |
| Existing legacy pilot regression | 5/5 | Независимый FIN-PILOT-V1 совместимый generate→upload→review→fact |
| Financially qualified uploads | Отдельный счётчик | Только утверждённые source+mapping+method; **не приравнивать к 166** |

**Обязательные новые тестовые группы:** \`BE-R1-13\` — генерация 166; \`BE-R1-14\` — независимое чтение 166; \`BE-R1-15\` — positive typed round-trip всех 154; \`BE-R1-16\` — 12 evidence manifest round-trip + originals; \`BE-R1-17\` — PostgreSQL persistence/readback всех 166; \`BE-R1-18\` — negative matrix по типам полей; \`BE-R1-19\` — version/period/scope tamper; \`BE-R1-20\` — duplicate/reforged rows; \`BE-R1-21\` — historic legacy pilot compatibility; \`BE-R1-22\` — no approval/publishing from draft/candidate/evidence; \`BE-R1-23\` — ZIP/memory/time bounded import; \`BE-R1-24\` — matrix drift (166 codes, 794 exact fields, 122/32/12 modes).

## 7. Acceptance evidence, CI и правила завершения R1

- Расширить \`CatalogCaptureRegistryTest\` и \`SpecializedSchemaCandidateRegistryTest\` лишь как **структурные** тесты; добавить отдельные \`ServerExcelTemplateAllFormsTest\` и \`ServerExcelImportAllFormsIntegrationTest\` (имена проектные). \`@TestFactory\` должен создавать **166 отдельных именованных кейсов**, со связью с \`BE-R1-ALL-<formCode>\`, и проверять наличие actually generated XLSX and persisted source.
- JUnit test report и отдельный machine-readable \`backend/target/fin-evidence/fin-be-r1-form-upload-coverage.json\`: 166 строк с \`formCode,mode,schemaVersion,generated,poiOpened,filledRoundTrip,persisted,negativeTests,rawFileAttached,qualificationStatus,testCaseId,commitSha\`; marker \`SKIPPED/UNIMPLEMENTED\` никогда не считается PASS.
- Доказать, что каждое уникальное поле из \`794/794\` реально присутствует в шаблоне, в normalized preview и persisted draft (для structured 154); совпадает тип, без изобретённых approved labels/unit policy. Coverage считается по formCode **и отдельно** по fieldCode.
- Additive SQL migration проверять на fresh PostgreSQL 16 и pre-existing schema через Testcontainers, транзакционная атомарность и duplicate/conflict. Точный SHA, ссылки GitHub Actions и число пройденных/непройденных per-form тестов обязательны.
- Протокол готовности разделять на \`XLSX_GENERATION_READY\`, \`XLSX_IMPORT_READY\`, \`SERVER_DRAFT_PERSISTED\`, \`FINANCIAL_METHOD_QUALIFICATION_HOLD\` и документное \`ORIGINAL_EVIDENCE_AVAILABLE/NOT_VERIFIED\`. **166/166 генераций/загрузок = инженерная приёмка ввода**, но не 166 финансово утверждённых форм.
- **Нельзя** назвать FIN-BE-R1 полностью принятым, пока отсутствует хотя бы один реально работающий importer для любой из 166 форм, либо документный оригинал у evidence-only; если часть не готова — сообщать точные код/причину и R1=HOLD. Нельзя обманывать приёмку возвратом "unsupported" из uploader в качестве успешной загрузки.

**Уточнённый Definition of Done:** 166/166 реальных шаблонов Java, 166/166 независимых XLSX round-trips, 166/166 PostgreSQL source persistence/readback (с сохранением типовых границ 122/32/12), 794/794 typed field parity, 5/5 legacy pilot regression, негативная матрица, clean+upgrade Flyway, GREEN backend JUnit/Testcontainers на одном SHA. Подключение в аналитические методики — следующий FIN-BE-R2/R3 и отдельный методический approval. Frontend, Keycloak и разработка новой системы безопасности остаются STOP.

