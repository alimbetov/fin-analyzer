# FIN-BE-R1 — независимое финансовое S2: пакет рецензирования всех 166 форм

**Статус:** PREPARED FOR EXTERNAL FINANCIAL REVIEW / **0 independent approvals recorded**.  
**Рабочая ветка:** `feat/fin-r0-r1a`. **Разделение полномочий:** инженерный CI не утверждает бухгалтерскую семантику. Отчётные данные `ACTUAL` остаются под действующими source-review/method-release gates. Новые IAM/Keycloak/React задачи запрещены.

## 1. Подтверждённый backend baseline

Источники: `backend/src/main/resources/fin/form-compatibility.tsv` (166), `specialized-schema-candidates-r6.tsv` (32), `form-field-presentation-review-r6.tsv` (794 полевые записи).

| Контур | Форм | Поля | Статус финансового review |
|---|---:|---:|---|
| Typed draft DF-/SF- | 122 | 618 | PENDING, не approved finance fact source |
| Specialized C/F/P | 32 | 176 | SCHEMA_CANDIDATE_NOT_APPROVED, S2 PENDING |
| Document/evidence D-/T- | 12 | Не числовой field-map (7 полей описи) | Evidence semantics/issuer/content verification PENDING |
| **Итого** | **166** | **794 числовых/типизированных координат** | **Не допускаются blanket approvals** |

В `backend/src/test/resources/fin/intake/fin-be-r1-financial-s2-review-register-v1.tsv` — **ровно 166 уникальных formCode**, категория, число полей, предварительное правило, требуемое независимое доказательство и IDs обязательных positive/negative golden. Поля `PENDING_INDEPENDENT_FINANCIAL_REVIEW`, `NOT_ASSIGNED`, `NOT_PROVIDED` **намеренные**: ни один reviewer не назначен и никакое заключение не выдумывается.

## 2. Что должен утверждать независимый S2-рецензент для каждой формы

1. **Source identity и назначение:** регулируемый/управленческий/отраслевой источник, правовой и отчётный basis, форма/версия, scope, субъект, источник, издатель, дата и применимость.
2. **Field contract:** точное человеко-читаемое название и экономический смысл каждого fieldCode, row grain, dimension/counterparty, REQUIRED/OPTIONAL/NOT_APPLICABLE, stock/flow/rate/description, единицы (KZT, KZT thousands, count, %, days), signage и точность, коды справочников. Китайские/казахские/русские/английские метки нельзя объявлять утверждёнными по машинному переводу.
3. **Period и population:** month/quarter/year/as-of, consolidated vs separate, currency/scale, complete vs partial, duplicate/contra/returns handling, исключение двойного учёта.
4. **Business constraints:** согласование subtotal ↔ detail, balance identities, turnover/cash identity, zero vs missing, переклассификация и корректировки. `preliminaryInvariant` из специализированного TSV — **предложение**, не квалифицированная формула.
5. **Golden:** предоставить **минимум один независимо рассчитанный ненулевой positive и два negative** примера на форму; для денежных/остаточных полей — несколько разных сумм, отрицательная корректировка там, где разрешена, единицы и основание масштаба. Expected values должны быть подготовлены без вызова production Java calculator.
6. **Source evidence:** исходный файл (обезличенный либо специально созданный экспертами), hash SHA256, ревизия, версия формы, период; официальный/полученный от владельца документ при необходимости. Synthetic type test — только engineering evidence, не business authenticity.
7. **Review outcome:** `APPROVE`, `REJECT` или `REQUEST_CHANGES`, reviewer/role/дата, подписанный или независимо проверяемый evidence reference, semantic contract checksum, выписанные ограничения. Один и тот же разработчик не оформляет независимый S2 за себя.

**На время аудита ни один из 166 пунктов не получил реальное внешнее S2-решение.** Присутствие строки реестра, теста или предварительного правила — не approval.

## 3. Специальные 32 кандидата: приоритеты независимого анализа

| Приоритет | Коды | Что проверить экспертом |
|---|---|---|
| S2-A, бухгалтерское ядро | F01, F02, F03, F04, F06, F07, F08 | Balance/current liabilities/equity, P&L net/gross policy, cash flow rolls, account mapping, consolidation/elim, trial balance conventions |
| S2-B, кредитный анализ и долговая нагрузка | C01–C06, C10, C11, C13, C14, P04, P09, P10 | AR/AP ageing, nonnegative constraints, debt currency/rates, gross/net cash bank transfers, tax, contingencies and debt service/CFADS lineage |
| S2-C, операционная аналитика | C07–C09, C12, P05, P06 | Sales returns and gross/net, cost taxonomy, period comparability, share ratio denominators, working-capital drivers, capex |
| S2-D, прогноз/сценарии/раскрытия | F05, P01–P03, P07, P08 | Reference-only vs numeric values; no forecast treated as ACTUAL; scenario assumptions, stock/flow consistency, forecast cash and sensitivity |

**Не выполнять автоматически массовую квалификацию** всех 32 на основании того, что Java parser принимает предложенные типы. Методическое решение должно быть отдельно по каждой форме/версии и затем по каждому исполняемому variant.

## 4. Конкретный формат экспертных файлов

Для каждого formCode, например `C01`, рецензент передаёт:

- `S2-C01-source-manifest.json`: `formCode, formVersion, schemaHash, dataSpace, reportingBasis, period, org/entity/scope (обезличенные), currency, scale, sourceKind, sourceSha256, observedAt`.
- `S2-C01-field-contract.tsv`: `fieldCode, type, businessMeaning, signPolicy, requiredness, unit, stockFlow, grain, sourceCoord, reconciliation, reviewerNote`.
- `S2-C01-positive-fixture.tsv` и `S2-C01-negative-fixture.tsv`: **ввод и независимый ожидаемый результат** по формулам/инвариантам, с reason codes и source references.
- `S2-C01-review-decision.md`: `reviewer, reviewDate, decision, findings, unresolved limitations, semanticContractSha256, approvedFixtureSha256`.

Названия выше — **ожидаемые к предоставлению артефакты**. Они сейчас не находятся в репозитории; создавать искусственные доказательства, пустые approval-подписи и произвольные истины запрещено.

## 5. Независимая процедура квалификации

1. Lead engineer замораживает `formCode+schemaVersion+schemaHash` и вручает экспертам исходный маппинг/тестовый XLSX, реальные анонимизированные контрольные файлы, ограничения схемы.
2. Финансовый эксперт, **не реализовывавший методику**, рецензирует поля/периоды/допущения и готовит expected fixtures.
3. S2 сверяет согласованность источников, финансовые регистры, значения, отрицательные и граничные кейсы и возвращает подписанное решение.
4. Java JUnit/PostgreSQL независимо воспроизводит эти expected, не генерирует oracle из production parser.
5. В новой versioned approval ledger можно зафиксировать положительный verdict, привязанный к exact hashes и reviewer reference **только после фактического получения решения**. Изменение field/units/variant version обнуляет применимость прошлого approval.
6. Текущие release-gates продолжают блокировать `ACTUAL`; утверждение формы **не** автоматически утверждает всю диагностику/60 методов. Любой P0 mismatch — `S2_REJECTED`/HOLD.

## 6. Отдельная CI-квалификация (не путать с S2)

Последний кодовый baseline перед этим пакетом: `67721f2033d75effd9d5c79dcb6eb64e470dac57`.

- [Backend Domain](https://github.com/alimbetov/fin-analyzer/actions/runs/43914101423) — SUCCESS.
- [FIN CI](https://github.com/alimbetov/fin-analyzer/actions/runs/43914102256) — **FAILURE: `desktop-macos` отменён; acceptance-gate FAIL**, несмотря на SUCCESS `verify` и `backend-verification`. На том же SHA инициирован **один повторный запуск** отменённого macOS job; результат следует проверять по живым Actions, не считать успешным заранее.
- Локальный MacBook запуск и S2 — самостоятельные требования, GitHub Actions не исполняет работу на устройстве пользователя.

**Точный локальный backend smoke на MacBook:**
```bash
git switch feat/fin-r0-r1a
git pull --ff-only
node scripts/check-flyway-versions.mjs
mvn -B -ntp -f backend/pom.xml verify
python3 backend/scripts/verify_fin_be_r1_evidence.py
```
Требуется работающий Docker/PostgreSQL Testcontainers. Зафиксировать Mac model, OS, Java 21, Docker version, test XML и git rev-parse HEAD. Эти команды **не** являются утверждением о реально выполненном локальном тесте.

**FIN-BE-R1 conclusion:** `BACKEND_INGEST_ENGINEERING_GREEN / FIN_CI_FULL_ACCEPTANCE_PENDING / S2_FINANCIAL_HOLD`. Никакого обхода macOS gate, перевода 32 схем в `APPROVED` или `ACTUAL` без независимых заключений.
