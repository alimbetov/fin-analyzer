# FIN-BE-R4 — ТЗ: доказательная ML-квалификация

**Приоритет:** P1. **Статус:** SPECIFIED / ML_PROOF_NOT_ESTABLISHED. **Ветка:** `feat/fin-r0-r1a`.  
**Цель:** построить воспроизводимый forecasting pipeline и доказать, что он даёт полезный out-of-time результат лучше простой модели, **без утечки будущей информации** и без подмены финансовых фактов.

## 1. Что действительно реализовано

`backend/pom.xml` содержит Tribuo 4.3.2 dependencies **test-scope**. `TribuoForecastSmokeTest`: 72 **синтетических** месяца 2019–2024; train 60 / holdout 12; deterministic seed 1701, SGD/AdaGrad, calendar/trend features, seasonal naive baseline, проверки duplicate/gap/tenant/future-known-at. Это smoke технологического прототипа: production service, model registry, trusted point-in-time feature extraction **нет**. ML не имеет права писать ACTUAL.

## 2. R4-A — Dataset/feature contract (P0)

- Сначала источник из R1: independently reviewed immutable `FactSet`; fields `org/entity/scope/period/metric/measure/basis/currency/unit/sourceRevisionId/sourceHash/factSetRevision`.
- Ввести `knownAt` отдельно для **feature и label**, cutoff на каждый training origin. Рестейтмент после cutoff не может попасть в прошлое.
- Для пилота target = месячный `REV_NET` (horizon 1 и 3 отдельно). Не смешивать разные масштабы, валюты, организации, stock и flow; SKU demand/anomaly detection — отдельные models/targets.
- Дубликат месяца, пропуск, неполный coverage, post-hoc update, missing value, negative/zero revenue — explicable reject/fallback, а не silent fill.
- На первых шагах synthetic-only; реальные обезличенные datasets — только после подтверждения их происхождения.

## 3. R4-B — Baselines, rolling evaluation и порог (P0)

- Baselines: last-observed naive, seasonal-naive 12-month, trailing 3-month average/median (все вычисляются из данных, известных до cutoff).
- Отдельные expanding/rolling-origin temporal folds: ≥3 cutoffs, final untouched holdout, не рандомный train/test. Для сезонной monthly модели initial history **≥36 непрерывных месяцев**; недостаток → `INSUFFICIENT_HISTORY`.
- Метрики: MAE, MASE, WAPE только при nonzero denominator, signed bias, per-fold horizon, а не средняя без детализации. Поддержать regime-change, sparse/new business, partial history и extreme cases.
- **Предварительный go/no-go для пилотного target (зафиксировать до benchmark):** средняя MAE на ≥3 временных folds минимум на **5% лучше сильнейшего baseline**, на final holdout не хуже лучшего baseline. Если эффект не воспроизводится, `MODEL_NOT_PROVEN`. Реальный методический допуск требует дополнительно достаточности выборки и экспертной оценки значимости.
- Prediction intervals показывать только после калибровки coverage на out-of-time errors; запрещено объявлять произвольные "95%". No hyperparameter tuning на final holdout, no future leakage through normalizing all data.
- Точное бухгалтерское значение в BigDecimal, модельный output double помечать `ESTIMATED` и применять отдельную версионированную rounding policy.

## 4. R4-C — Model life cycle (P1)

Immutable `ModelRunManifest`: model/algorithm/version, dataset and feature-schema fingerprints, training cutoff, source FactSet hash, feature/label knownAt policy, seed, hyperparameters, Java/Tribuo version, Git SHA, time folds, baselines and metrics, predicted horizon, reviewer approval. Ретренинг изменяет модельную ревизию, не старый прогноз.

`SHADOW / MODEL_NOT_PROVEN / QUALIFICATION_PENDING / QUALIFIED / DISABLED` — разные состояния. Не переносить Tribuo из test-scope в runtime без отдельного решения после доказательств. Нет нового публичного ML endpoint. Optional ML error/restart не должен трогать immutable CORE; `CORE_WITH_ML` — новая связанная ревизия, не update CORE.

## 5. Acceptance matrix

| ID | Сценарий | Expected |
|---|---|---|
| BE-R4-01 | Existing 72-month synthetic smoke | Reproducible engineering spike only |
| BE-R4-02 | Feature/label known only after forecast cutoff | FUTURE_LEAKAGE, no training |
| BE-R4-03 | Mixed tenant/entity/currency/basis | Dataset rejected |
| BE-R4-04 | Duplicate, gap, unreviewed restatement | Reject or explicit fallback |
| BE-R4-05 | 3+ rolling time folds, final untouched holdout | Per-fold MAE/MASE/WAPE and baseline |
| BE-R4-06 | Naive forecast beats ML | MODEL_NOT_PROVEN, cannot promote |
| BE-R4-07 | Same seed+data+config repeated | Same input digest and tolerance-bound outputs |
| BE-R4-08 | Changed source revision or model config | New immutable model manifest |
| BE-R4-09 | History <36 months / unstable regime | Explicit insufficient-data status |
| BE-R4-10 | ML failure/time limit | CORE unchanged; optional ML unavailable |
| BE-R4-11 | Forecast value in reporting | ESTIMATED, horizon, baseline, data cutoff |
| BE-R4-12 | Missing empirical interval coverage | No invented prediction interval |

Test-only independent fixtures under `backend/src/test/resources/ml/`, and JUnit `TribuoForecastSmokeTest`/new backtest tests. Model cards must document task benefit, available input data, bias/regime risks, best baseline, scores and resource cost.

**DoD:** trusted feature lineage, rolling backtest, model+baseline reports, reproducibility & leakage tests, explicit HOLD on failure, same-SHA JUnit evidence. Positive synthetic smoke **не** закрывает финансовую/реальную ML квалификацию.

**Не входит:** auto investing/lending, frontend, Keycloak/security changes, online model API, замена ledger values ML-предсказаниями.

