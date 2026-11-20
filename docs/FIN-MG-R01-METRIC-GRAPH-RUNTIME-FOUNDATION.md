# FIN MG-R0.1 — Metric Graph Runtime Foundation

**Дата:** 2026-11-20  
**Ветка:** feat/fin-form-library-standalone  
**Статус:** READ_ONLY_SOURCE_RESOLUTION_IMPLEMENTED, не финансово квалифицированный executable graph  
**Проверенный SHA кода:** d39af2e4990e9f90e666a4486f3bedf7b89dbb86  
**Приёмка PostgreSQL 16:** https://github.com/alimbetov/fin-analyzer/actions/runs/44682901403 — 5/5 PASS, без ошибок и пропусков.

## 1. Архитектурное решение

Уже существующие 166 FIN форм, fin_lib.lib_metric_definition,
fin_calc.formula_contract_revision, fin_calc.formula_typed_input_binding
и Calc4DTypedDagCompiler — источники для определения зависимостей.
Навигатор из 7 задач, 42 моделей и 46 редакционных показателей остаётся
отдельной unqualified-моделью. Связь от показателя навигатора
к исполняемой формуле нельзя угадывать по совпадению текста или формы.

Новые SQL/Liquibase миграции, отдельный калькулятор, fin_fact writer,
CalcPlan и дублирующий реестр метрик не создавались.

## 2. Реализованные файлы

- form-library/src/main/java/kz/fin/formlib/calc/graph/MetricGraphResolver.java:
  read-only JDBC/REPEATABLE_READ; поиск точной версии формулы; транзитивное
  раскрытие зависимостей; вызов существующего DAG-компилятора; реальные
  поля/типы/версии/схемы источников. Ограничение: 1024 версии формул.
- form-library/src/main/java/kz/fin/formlib/api/MetricGraphController.java:
  REST API для read-only разрешения.
- form-library/src/test/java/kz/fin/formlib/calc/graph/MetricGraphResolverPostgresqlTest.java:
  5 реальных PostgreSQL 16 integration tests.
- .github/workflows/fin-mg-r01-metric-graph-pg16.yml:
  CI требует 5 тестов, 0 failures, 0 errors, 0 skipped.

### REST API

GET /api/v1/metric-graph/metrics/F02.grossMargin

Необязательные параметры:
- exactMethodRevisionId: UUID ранее зарегистрированной версии formula contract;
- goalCode: только при явном существующем совпадении business indicator
  с goal, без самостоятельного вывода соответствия финансовых терминов.

Возвращаются metricCode, metricKind, goalCodes, candidateInputForms,
methodRevisions, selectedMethodRevisionId, graphSha256,
orderedNodes, requiredPhysicalInputs, state, blockers,
graphExecutable=false, financiallyQualified=false.

PhysicalRequirement включает formCode, exact formVersionId, schemaSha256,
metricCode, fieldCode, fieldType, inputMode, requirednessState и SHA binding.

Пример:
- F02.grossMargin → F02.grossProfit → F02.netRevenue + F02.cogs;
  все исходные поля получены из актуальных связанных typed contracts.
- F02.netRevenue → PHYSICAL_SOURCE_ONLY с физическим полем;
  синтетическая формула не создаётся.
- BANK_FINANCING / NET_PROFIT → CATALOG_ONLY, candidateInputForms,
  MG_EDITORIAL_INDICATOR_FORMULA_LINK_NOT_AUTHORED, без ложной формулы.

### Статусы

| Состояние | Значение |
|---|---|
| DAG_COMPILED_PREVIEW_ONLY | Версии узлов и физические источники проверены, вычисление запрещено данным API |
| PHYSICAL_SOURCE_ONLY | Найдено существующее исходное поле, формула не требуется |
| CATALOG_ONLY | Известный показатель без точного formula binding |
| METHOD_SELECTION_REQUIRED | Несколько версий, требуется exact UUID |
| STALE_METHOD_REVISION | Ревизия формулы относится к устаревшей схеме |
| BLOCKED_DAG_CONTRACT | Цикл, потерянная зависимость или дрейф типизированного контракта |

## 3. Доказательства

GitHub Actions: https://github.com/alimbetov/fin-analyzer/actions/runs/44682901403

| Проверка | Результат |
|---|---|
| F02 grossMargin: транзитивная формула, два реальных поля и SHA | PASS |
| F02 operatingResult: три источника без дублей | PASS |
| NET_PROFIT как редакционный показатель: нет выдуманной формулы | PASS |
| Неподходящая версия, неизвестная метрика и незакреплённая связь goal | PASS |
| F02.netRevenue: прямое типизированное физическое поле | PASS |

Суммарно: 5 tests, 0 errors, 0 failures, 0 skipped. Проверенные
источники не создавали ни одной ревизии данных, расчётного плана или FactSet.

## 4. Ограничения и следующий технический рубеж

MG-R0.1 — resolver контракта и графа, но не универсальный исполнитель.
Предметные CALC-A05 и финансовые S2 правила здесь не реализовывались.

До универсального исполнения нужны:
1. Явные version-pinned связи goal/indicator ↔ metricCode.
2. Связь методологических вариантов, включая DCF_VALUATION и CALC-A05,
   с конкретными исполнимыми узлами; отсутствие связи = blocker.
3. Согласование requiredness, period, AS_OF, unit, dimension, FX,
   missing value policy, rounding и допустимых source grain.
4. Project/scenario aware resolver точных fin_intake source revision IDs;
   сейчас выявляются обязательные *контрактные поля*, а не заполненность
   значений конкретной организации.
5. Повторное использование existing UAT CalcPlan executor для расчёта
   значений по проверенному DAG, без нового калькулятора.

A05-R1.0 теперь можно проектировать на этом фундаменте: C05 loanBusinessKey,
LoanContractRevision, rate/calendar/fee rule revisions, атомарное сохранение
с fin_intake C05. Не создавать 167-ю форму или третий writer.

Отдельно НЕ РЕАЛИЗОВАНЫ: 38 A05 acceptance tests, график кредита, EIR,
ГЭСВ РК, A01 allocation, CFADS/DSCR по P09 и все 154 числовые формулы.
