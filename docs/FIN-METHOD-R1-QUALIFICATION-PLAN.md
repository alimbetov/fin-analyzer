# FIN METHOD-R1 — следующий квалифицируемый пакет методов (SPEC LOCK)

**Дата:** 2026-11-16. **Ветка:** `feat/fin-r0-r1a`. **Статус:** `METHOD_SPECIFIED / IMPLEMENTATION_AND_GREEN_PENDING`.  
**Связанные артефакты:** [исходное ТЗ](FIN-DIAG-60-IMPLEMENTATION-TZ.md), [P0-контракт](FIN-EXECUTION-CONTRACT-V1.md), [реестр 60 методов](../backend/src/main/resources/fin/methodology/methodology-catalog-r1.json), [технический аудит](FIN-EXEC-R2-R3-TECH-REVIEW.md).

## 1. Правила допуска

До первого подтверждённого `GREEN` на одном SHA для Maven/JUnit/PostgreSQL Testcontainers, React и acceptance-gate **никакая новая методика не получает статус `RELEASE_QUALIFIED` или `autoRunAllowed=true`**. Текущий семейный реестр fail-closed: наличие семейства не означает существование числового расчётчика. `FIN-01/REV_NET_MOM` подключён как отдельный вариант с проверяемым договорным `QUALIFIED` входом; это не квалификация всего FIN-01.

Метод вправе выполняться только по server-verified `FactSetRevision`, `AnalysisManifest`, `AnalysisProfile` и pinned `MethodologyVariantVersion`. Excel/React черновики не становятся ACTUAL через client-supplied `approved`. Нулевое, отсутствующее, оценочное, неактуальное, разноразмерное и недостоверное значение — разные состояния. При неточном `FormField→EconomicFact` mapping метод должен возвращать `NOT_COMPUTABLE`/blocked, а не угадывать.

## 2. Первая очередь из шести направлений

| Family/variant | Строгая формула, synthetic golden | Входы и ограничения | Reuse candidate | Блокирующие кейсы |
|---|---|---|---|---|
| FIN-02 / REV_SHARE | `revenueCategory=200`, `totalRevenue=1000`, доля `20.0000%` | Утверждённые однопериодные P&L/DF-SAL строки **из одной популяции**, валюта и dimensions сопоставимы | `AutoReportProjection.PercentOf`, `ShareOfTotal` | denominator=0, conflicting F02/DF, negative returns policy, unrelated scope |
| FIN-04 / CURRENT_RATIO | `currentAssets=400`, `currentLiabilities=200` → `2.0000` | Только approved F01 as-of на одну дату, юридическое лицо, reporting basis и currency | `AutoIndicatorEngine`/typed ratio primitive; новую формулу не плодить | liabilities=0, stock-vs-flow, mixed currency/period, subtotal double count |
| CON-01 / PARETO_SALES | SKU A=500,B=300,C=150,D=50; sum=1000; cumulative=50%,80%,95%,100% | Доверенная SKU-популяция; stable tie sort; returns policy; all items accounted | только после аудита `ScenarioEconomicsEngine` / существующего sector-pilot | negative/net returns, zero sum, missing SKU, duplicate transaction |
| CON-02 / ABC_SALES | при `ABC_BOUNDARY_V1`: A/A/B/C на той же популяции | Зависимость от CON-01, пороги являются **версионированной политикой**, а не общепринятым нормативом | validated Pareto result; no duplicate grouping | ordering ties, boundary equality, changing policy/version |
| PRO-03 / CONTRIBUTION_MARGIN | revenue=1000, **approved variable cost**=600 → CM=400 (40% revenue) | Variable cost **не равен автоматически всей COGS**; один period/scope/currency; explicit signed cost semantics | `ScenarioEconomicsEngine` candidate, independent economic approval | allocated fixed cost counted variable, returns, missing cost taxonomy |
| CASH-01 / CCC | FIXED_360: AR 120/revenue720→DSO60; inventory90/COGS540→DIO60; AP60/purchases540→DPO40; **CCC=80d** | AR/inventory/AP — утверждённые **средние остатки**; year=360 as explicit policy; purchases denominator assumption documented | existing temporal aggregation/typed metrics, independent review | missing balances, revenue/COGS=0, mismatched years, purchase-vs-COGS inconsistency |

`FIN-01` остаётся в первоочередном наборе приёмки, но его текущий вариант `REV_NET_MOM` не заменяет полного горизонтального анализа. Варианты этих шести направлений могут потребовать дополнительной отраслевой версии, нельзя считать их family-wide acceptance.

## 3. Обязательная экономическая контрольная база

Fixtures `M-R1-001..006`, все `SYNTHETIC`, независимые `expected.json`:
- фиксировать `currency=KZT`, `reportingBasis=SEPARATE`, `unit=KZT`, `sourceRevision`, `approvedTestRevision`, временной диапазон и источник;
- `M-R1-001`: категории 200/800 одного total=1000 → 20%, 80%;
- `M-R1-002`: баланс 400/200 → 2; 400/0 → `NOT_COMPUTABLE` с причиной;
- `M-R1-003`: Pareto SKU 500,300,150,50 → [50,30,15,5]% и [50,80,95,100]%;
- `M-R1-004`: ABC A,A,B,C по `ABC_BOUNDARY_V1`;
- `M-R1-005`: gross variable contribution 1000-600=400; margin=40%; при отсутствии approved variable-cost taxonomy — `INSUFFICIENT_DATA`;
- `M-R1-006`: 360-day CCC=80d; если `purchases` не подтверждены, явное допущение либо `NOT_COMPUTABLE`, не silent fallback.

Для всех: independent expected result, negative cases, metamorphic tests, real PostgreSQL isolation and full `Source→Fact→Variant→Report` trace, RunId, MethodVersion, SnapshotHash, duplicate source reconciliation. React/Excel/JSON должны давать одинаковое расчётное значение, сохраняя локализованный формат отображения.

## 4. Cross-method diagnostic rules (после квалификации входов)

- `XDIAG-001`: revenue YoY ↑ + operating cash ↓ + AR ↑ → **гипотеза о качестве поступлений**; подтверждать cash/AR data and period matching, не объявлять причинность.
- `XDIAG-002`: sales ↑ and inventory ↑ faster → **риск увеличения длительности запасов**; требуются averages, seasonality, sales mix.
- `XDIAG-003`: positive investment NPV and negative 13-week minimum liquidity → **конфликт долгосрочной экономической эффективности и краткосрочной платёжеспособности**; разные горизонты, явно раздельные выводы.
- `XDIAG-004`: Pareto A + XYZ Z → **концентрация при нестабильном спросе**; необходимо 12+ months with complete SKU series.

У каждого правила `ruleVersion, observedMetrics, unit/period comparability, evidenceReferences, alternativeHypotheses, dataQuality, materialityPolicy, nextEvidenceRequests`. Никаких причинных выводов или универсального health score.

## 5. Совокупное CORE-заключение — профиль `BUSINESS_HEALTH_V1`

Минимальные секции:
1. Идентичность организации, ревизии и отчётного периода, степень доверия и ограничения.
2. Executive summary **только по квалифицированным данным**; если mandatory scope неполон, явно `PARTIAL` / `BLOCKED`.
3. Фактические показатели — даты, валюты, единицы, формулы, методические версии.
4. Финансовые тренды / ликвидность / прибыльность / денежные потоки по применимости.
5. Отраслевые метрики, структура, концентрация и оборотный капитал по проверенной применимости.
6. Межметодные наблюдения и гипотезы с provenance, не «доказанные причины».
7. Сценарии с отдельной маркировкой assumptions и исходных фактов.
8. Недостающие данные, запрещённые расчёты, методические ограничения и следующий ввод формы.
9. Подпись источников/расчётов, Review Status, контролируемый экспорт RU/KK/EN/ZH.

`CORE_WITH_ML` остаётся отдельным optional later revision. `MODEL_UNQUALIFIED` не может блокировать получение CORE. Внешняя публикация банку/инвестору требует отдельно авторизованного владельцем grant.

## 6. CI/rollout hard gate

- **Текущий код без подтверждённого CI не считается GREEN**. Должны быть фактические URLs успешных GitHub Actions для одного SHA.
- Первые внедрения после GREEN: FIN-02 → FIN-04 → CON-01/02 → PRO-03 → CASH-01.
- Каждый variant публикуется только после независимого методического golden, отрицательных сценариев, PostgreSQL Testcontainers, React E2E и S2 reviewer.
- После них выполнить `60-family` coverage audit, приоритетный backlog и расширить 60 по отраслям. Нельзя считать 166 форм и 30 EX-листов равноценными готовым KPI.

**Decision:** `METHOD-R1 DESIGN READY / IMPLEMENTATION HOLD UNTIL GREEN`.
