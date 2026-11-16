# FIN-BE-R3 — ТЗ: Доказуемый диагностический движок

**Приоритет:** P0/P1. **Статус:** SPECIFIED / FINANCE S2 PENDING. **Ветка:** `feat/fin-r0-r1a`.  
**Цель:** по каждому KPI и выводу восстановить исходный факт, формулу, версию методики, состояние применимости, ограничения и независимое числовое доказательство.

## 1. Существующие реализации и честные ограничения

- `BusinessDiagnosticEngine`: пилот observations/hypotheses/missing evidence; `TrustedBusinessDiagnosticService` пока SYNTHETIC only.
- `CrossFormDiagnosticEngine`/`TrustedCrossFormDiagnosticService`: source reconciliation, missing/not-computable, SYNTHETIC или отдельно выключенный ACTUAL provisional preview, **не qualified actual diagnosis**.
- `DiagnosticProofEngine`: synthetic decomposition, remainder, hypothesis; причинность не доказана.
- `AutoIndicatorEngine`, `OperatingFinanceMethodEngine`, `ConcentrationAnalysisEngine`, `CalculationDag` и `CompositeCoreReportPublisher`: вычислительные элементы, не blanket approval всей методики.
- `CatA31MethodReviewRegistry` — независимый methodological review ledger. Никакое S2 не выдаётся автоматически из CI.

## 2. R3-A — Independent method passport (P0)

**На каждый variant отдельно**, не на семейство: methodFamily, variantCode/version, financial meaning, exact formula, stock/flow/ratio, required metrics/forms, unit/precision/rounding policy, reportingBasis/period/calendar/coverage, supported source population, materiality thresholds, scope/currency, qualification status, expected output, rejection/missing semantics, assumption version, reviewed semantic contract hash, golden fixture hash и reviewer evidence.

Изменение финансового смысла обязательно меняет method version. Старые результаты с исходными данными/version остаются воспроизводимыми.

## 3. R3-B — Независимые эталоны и отрицательные кейсы

Expected results считать/рецензировать **вне** production implementation. Хранить под `backend/src/test/resources/fin/methods/`.

| ID | Вход | Проверяемый результат |
|---|---|---|
| BE-R3-01 | CURRENT_ASSETS=400; CURRENT_LIABILITIES=200 | Current ratio=2.0000 |
| BE-R3-02 | CURRENT_LIABILITIES=0/null/missing | NOT_COMPUTABLE/MISSING, не infinity/0 |
| BE-R3-03 | F02 REV_NET=1000, detail 600+400, sector=1000 | Canonical revenue=1000, не 3000 |
| BE-R3-04 | Assets=908, liabilities=601, equity=307 | Balance difference=0 |
| BE-R3-05 | Same, equity=306 | Explicit deviation 1 |
| BE-R3-06 | OpeningCash=100,CFO=35,CFI=-10,CFF=-5,ClosingCash=120 | Reconciled + FX caveat |
| BE-R3-07 | Missing previous month / unsupported stock TTM | MISSING_EVIDENCE/NOT_COMPUTABLE |
| BE-R3-08 | Mixed currency/basis/entity/period | Comparison blocked, not normalized silently |
| BE-R3-09 | Flow monthly total vs stock point-in-time | Correct aggregation; stock never summed 12× |
| BE-R3-10 | Revenue↑ AR↑ CFO↓ | Hypothesis, not proof of causality |
| BE-R3-11 | Restated source revision | New trace/hash, immutable old diagnostic |
| BE-R3-12 | Method lacks S2 qualification | No ACTUAL qualified execution or report |

Метаморфические проверки: дубликат одного source не удваивает выручку; порядок строк не влияет на numeric outcome; нерелевантный field не влияет на KPI; абсолютный масштаб суммы и ratio units не смешиваются; `missing != explicit zero`.

## 4. R3-C — Diagnostic evidence graph (P0)

Все выходные `Finding/Observation/Hypothesis` содержат:

- Input provenance: organization/entity/scope, factId, sourceRevisionId, original sourceHash, exact sourceRowId/sourceField, period/basis/currency, dataSpace.
- Calculation provenance: method/variant/version, formula or expression hash, source metric codes, dependency list, exact decimal input/output, unit, assumptions/policy version, build SHA, run ID.
- Explanation: `FACT / OBSERVATION / HYPOTHESIS / ESTIMATE`, reason, missing/conflict, materiality, alternative explanations, next evidence request. Никаких вымышленных confidence/probability.
- States: `CALCULATED / ESTIMATED / NOT_COMPUTABLE / MISSING_EVIDENCE / SOURCE_CONFLICT / METHOD_UNQUALIFIED`. На неполной основе не публиковать число "по умолчанию". Не выбирать последний timestamp как источник истины.
- Immutable digest and replay: одинаковые factSet+method+assumptions → повторный числовой результат и trace; новая revision не изменяет старый snapshot.

CORE readiness зависит от completeness требуемого analysis profile, а не количества выполненных задач; `PARTIAL` с двумя методами не называется полным диагнозом. Sealed report содержит reasons невычисленных методов. ML как ESTIMATED — только отдельный optional report revision.

## 5. R3-D — Независимый методический review (P0)

Первая когорта: `FIN-01/REV_NET_MOM`, `FIN-02/REV_CATEGORY_SHARE`, `FIN-04/CURRENT_RATIO`, `CON-01/PARETO`, `CON-02/ABC`. Каждому — отдельные паспорт, golden, отрицательные тесты, исходные source population tests и финансовая рецензия. Одобрение одной разновидности не продвигает 60 семейств.

Этапы: `CANDIDATE → CODED → INDEPENDENT_GOLDEN_REVIEWED → METHOD_S2_APPROVED → INTEGRATED_QUALIFIED`. Если независимая проверка не представлена — `METHOD_S2_HOLD`, ACTUAL blocked. Не менять security/IAM.

## 6. Acceptance/DoD

JUnit golden/property tests плюс Testcontainers source→FactSet→method→diagnostic→CORE; read-only historic replay, raw provenance, incompatible-source guards, math-context policy, missing cases. Опорные классы: `OperatingFinanceMethodEngineTest`, `ConcentrationAnalysisEngineTest`, `BusinessDiagnosticEngineTest`, `CrossFormDiagnosticEngineTest`, `R4ProofAndDecisionTest`, `TrustedCrossFormDiagnosticIntegrationTest` и `ReviewedSkuSingleExecutorIntegrationTest`.

**Выход:** versioned method passports + independent expected + machine-readable evidence trace + reasoned diagnostic snapshots + method S2 decision per variant. CI не заменяет финансовую рецензию.

**Не входит:** React, Keycloak/IAM, "все 60 qualified", автоматический кредитный рейтинг, universal health-score или причинный вывод без доказательств.

