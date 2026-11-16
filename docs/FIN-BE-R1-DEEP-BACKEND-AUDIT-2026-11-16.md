# FIN-BE-R1 — глубокий Backend-аудит Excel intake, оригиналов и ревизий

**Дата:** 2026-11-16. **Ветка:** `feat/fin-r0-r1a`. **Аудируемый кодовый SHA:** `659f680572596a559ec87ec5a507bdbda9041ac0`.

**Решение:** **ENGINEERING GREEN / FINANCIAL RELEASE HOLD**. Новая Java/PostgreSQL цепочка для 166 кодов протестирована, но это **не** делает 166 форм юридически, бухгалтерски и методически квалифицированными. Ни frontend, ни Keycloak/IAM не изменялись. Выполнялись только backend, миграции, backend CI wiring и docs.

## 1. Воспроизводимая фактическая база

- Предыдущий baseline `9bf070dd0cd982e6fcb354fdc01600086e8b185a`: `CatalogXlsxRoundTripService`, `V20261116_10`, 166 `@TestFactory` кейсов, существующий 5-форменный `PilotXlsxTemplateService` отдельно.
- После аудита кодовый SHA `659f6805`: добавлены `CatalogDocumentPreflight`, `CatalogXlsxRevisionService`, `V20261116_11`, расширение `CatalogXlsxRoundTripIntegrationTest`, `backend/scripts/verify_fin_be_r1_evidence.py`, обязательные backend CI-gates и артефакты.
- [FIN Backend Domain 43894374541](https://github.com/alimbetov/fin-analyzer/actions/runs/43894374541): SUCCESS; Maven/JUnit/PostgreSQL Testcontainers GREEN.
- [FIN CI 43894374497](https://github.com/alimbetov/fin-analyzer/actions/runs/43894374497): `verify` SUCCESS, `backend-verification` SUCCESS; `desktop-macos` **QUEUED на момент аудита**. Полный workflow acceptance нельзя объявлять GREEN до того, как все jobs на одном SHA завершатся.
- Реальные логи: `CatalogXlsxRoundTripIntegrationTest` **173 tests / 0 failures / 0 errors / 0 skipped**; весь backend **848 tests / 0 failures / 0 errors / 0 skipped**. PostgreSQL 16 Testcontainers, Flyway `V20261116_10` + `V20261116_11`.
- Mandatory log: `FIN_BE_R1_JAVA_POSTGRES_EVIDENCE_GREEN sha=659f680572596a559ec87ec5a507bdbda9041ac0 cases=166 structured=154 original_documents=12 typed_fields=794 junit_tests=173`.
- Artifact `fin-be-r1-166-postgres-evidence-backend-verification` содержит `fin-be-r1-form-upload-coverage.json`: code/mode/field count, generated, POI-opened, normalized, persisted, negative context rejected, project-based revision confirmed, original attachment present for 12 documentary codes. Он генерируется **после** реального Java/PostgreSQL case; не является статическим `165/166` счётчиком из документации.

## 2. P0/P1 аудит: недостатки и действия

| ID | Severity | Найдено в baseline | Что изменено на SHA 659f | Оставшиеся ограничения |
|---|---|---|---|---|
| A1 | P0 | `FIN CI` допускал сборку без обязательного 166-строчного backend upload proof | `@AfterAll` manifest + независимый Python-контроль против TSV catalog и JUnit; gate в двух Java jobs | JUnit Surefire сериализует `DynamicTest` displayName иначе, чем Java; поштучные ID берутся из Java manifesto, общий статус — из Surefire suite |
| A2 | P0 | `catalog_xlsx_draft` был лишь immutable записью, без текущей ревизии и истории подтверждений | `catalog_xlsx_slot`, `catalog_xlsx_revision`, CAS `latest_revision`, row lock `FOR UPDATE`, parent links, stale fingerprint, concurrent-confirm test, SQL immutable trigger | Каталоговый confirmation = **подтверждение сохранённого черновика**, **НЕ** source approval, не FactSet |
| A3 | P0 | 12 evidence-only путей допускали только PDF и упрощённый `%PDF-` marker | Offline preflight PDF, DOCX, XLSX, XML, PNG, JPEG; parser/ZIP and bounded-resource checks; 6 real document format roundtrips, invalid file negative tests | Документный preflight **не** доказывает авторство, антивирусную чистоту или экономическое содержание; предел 2 MB, один оригинал на один документный draft |
| A4 | P0 | Невозможна связь каталога 166 с текущим `project_v5` при прямом использовании `form_slot_v5` | Confirmation сверяет immutable existing `project_v5` org/entity/scope/projectCode/currency, отдельная catalog slot; F02 V5 таблицы не изменяются | Публичного HTTP/React workflow нет и не планируется; `project_v5` по-прежнему используется как уже существующий идентификатор проекта |
| A5 | P0 | Риск переписать исторические Flyway и повредить approved FactSet | Только новая additive `V20261116_11`; `V20261116_10` не менялась, review/approval/outbox не затронуты; старые regression tests PASS | Нужна отдельная проверка миграционного клона реальной базы при релизе; Testcontainers имитирует upgrade, а не клиентскую БД |
| A6 | P1 | Ожидаемые значения для 794 полей в одном тесте выводятся через тот же Java canonicalizer | Выполняется реальный Apache POI serialization→Java import→PostgreSQL readback для каждого кода; типовое соответствие доказано | Для финансовых бизнес-правил нужны **независимые** golden expected и методический S2; текущее доказательство структурное |
| A7 | P1 | Workbook metadata можно повторить/перезаписать | При импорте сверяются exact scope/period/currency/scale и schema fingerprint, invalid workbook reject | Нет persisted issued-template registry: техническая совместимость XLSX **не** доказательство его внешнего происхождения; service не должен публиковаться напрямую |
| A8 | P1 | `SCHEMA_NOT_APPROVED` кандидаты имеют preliminary invariants, но не approved accounting semantics | Status не повышается: `UNQUALIFIED_DRAFT` и `CONFIRMED_UNQUALIFIED_DRAFT`; запрещён implicit ACTUAL promotion | Нет qualification 32 candidates и approved field requiredness/units 794 entries |
| A9 | P1 | Документные оригиналы складываются в PostgreSQL bytea | Проверяются type, size, checksum, immutable record; у 12 кодов attached PDF source | Для больших/многокомпонентных досье нужен versioned object store и policy жизненного цикла. Не делать это скрыто на этапе R1 |
| A10 | P1 | Parser использует strict Excel string literals для numeric/date fields | Lossless canonical conversion и обратное чтение подтверждены тестами | Клиентские числовые Excel cell types и локализованные UX-подсказки без standalone backend parser не квалифицированы; UI STOP |
| A11 | P1 | История нового каталога не является историей юридически проверенных источников | Отдельный immutable catalogue confirmation ledger; existing `ReviewedFinancialIntakeService` остаётся единственным текущим мостом в FACT | Конверсию нового catalogue draft в reviewed numeric source проектировать после независимого утверждения field mapping; нельзя обходить independent review |

## 3. Архитектурная трасса

**Проверенный внутренний путь:**

`CatalogCaptureRegistry / SpecializedSchemaCandidateRegistry (166)`
→ `CatalogXlsxRoundTripService.generate` (real POI XLSX)
→ `parse` (exact metadata / field normalization)
→ `saveDraft` (immutable original, JSONB, SHA256, state UNQUALIFIED_DRAFT)
→ `FormDraftRevisionV5Service.createProject` (existing project identity)
→ `CatalogXlsxRevisionService.confirm` (slot lock, expected fingerprint/revision, immutable append)
→ `current/history` and PostgreSQL roundtrip.

Для `EVIDENCE_ONLY`: original attachment separately validated by `CatalogDocumentPreflight`; content is NOT a numeric fact. Предыдущая approved-source→`FactSetPublicationService`→outbox цепочка **не модифицировалась** и не принимает `CONFIRMED_UNQUALIFIED_DRAFT` автоматически.

**Унификация с V5:** `form_slot_v5` в Flyway V11 legacy допускает только F02 / MANAGEMENT / MONTH. Расширять его с помощью breaking ALTER было бы ошибкой. Поэтому новая `catalog_xlsx_slot` имеет FK на тот же `project_v5`, но собственные form codes, basis/period/coverage и номер ревизии; ревизии подтверждаются через CAS.

## 4. Регрессионные сценарии, подтверждённые CI

1. **166/166:** новый XLSX действительно генерируется POI, читается, заполняется двумя строками (документ 1), парсится, сохраняется в Postgres; persisted JSON matches values; monthly context tampering rejected без частичных записей.
2. **122 typed / 32 specialized / 12 evidence-only**: distinct modes, unqualified status, 794 структурных полей, no fake ACTUAL.
3. **Revisions:** создание проекта, первая confirm, вторая revised copy, parent link/history/current; stale revision and stale fingerprint rejected, immutable SQL.
4. **Race:** два одновременных подтверждения по одному expectedRevision → ровно один winner.
5. **Documents:** 12 PDF originals по 12 кодам и самостоятельная шестёрка PDF, DOCX, XLSX, XML, PNG, JPEG с чтением исходных bytea, плюс invalid media/signature/EOF.
6. **Legacy:** старый 5-пилотный формат `FIN-PILOT-V1` и новая каталоговая версия **не** смешиваются; existing pipeline tests and Flyway recovery remain GREEN.
7. **CI:** Python validator сравнивает exact codes/field counts и mandatory flags с серверным TSV и JSON, не засчитывает `SKIPPED` как успех; файлы raw доказательств доступны в Actions artifacts.

## 5. Release gate и следующая работа

**R1 structural ingestion subsystem:** `ENGINEERING_GREEN` на SHA `659f6805`.  
**FIN-BE-R1 full approved finance scope:** `HOLD`.  
**166 forms financial qualification:** `NOT ESTABLISHED`.  
**Frontend/Keycloak/security development:** `OUT OF SCOPE`.  
**New public upload HTTP API:** `NOT ADDED`.  
**FIN CI whole-workflow:** `PENDING` до macOS + acceptance-gate на том же SHA.

**Следующая очередь:**
1. **R1-G (P0, backend only):** persisted server-issued Excel manifest + exact project/period binding, чтобы не полагаться на только переданный `Context`; при ошибке reject before insert. Без изменения IAM или публичного HTTP.
2. **R1-H (P0):** source→semantic mapping только для независимо финансово проверенных вариантов и реальная конверсия каталожной ревизии в старый four-eyes reviewed intake; до этого все 166 остаются черновиками.
3. **R1-I (P1):** независимые типизированные и денежные golden fixtures с ненулевыми значениями, отрицательные проверки по 794 полям/разным периодам, property tests и изменение `SCHEMA_FINGERPRINT` при изменении кандидатных semantics.
4. **R1-J (P1):** отдельное подтверждение формата оригиналов (PDF структура, multi-attachment policy), real-file limits и объективное тестирование CPU/heap; не приписывать preflight внешнее доверие.
5. Далее FIN-BE-R2: variant-level methodology registry, DAG и S2, не автоматическая квалификация всех 60 семейств.

**Команды воспроизведения:**

```bash
node scripts/check-flyway-versions.mjs
mvn -B -ntp -f backend/pom.xml verify
python3 backend/scripts/verify_fin_be_r1_evidence.py
```

Важно: запустить Python gate **после полного Maven verify** (он проверяет реальный JUnit XML и сгенерированный JSON). Никаких ложных `PASS` для отсутствующих тестов или реальной клиентской базы.
