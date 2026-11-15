# FIN ML-R0 — Tribuo 4.3.2 regression smoke (synthetic only)

**Status: experimental / test-only / not eligible for production financial decisions.**

This spike adds `tribuo-data` and `tribuo-regression-sgd` as Maven test-scoped
dependencies. No REST endpoint, application bean, model registry, production
training job or frontend change was introduced. No `ACTUAL` revision is
written by ML. Do not promote these results as proven forecasting utility.

## Data & split

- `retail_monthly_synthetic.tsv`: 72 generated monthly observations
  (January 2019 through December 2024), in **arbitrary million-unit scale**.
- First 60 months (2019–2023) are training; last 12 (2024) are an
  untouched chronological holdout.
- Information cutoff: 2024-01-05. Each monthly fact is marked known
  on the third day of the following month.
- Features use **calendar and elapsed time only**, known before prediction:
  `trend`, `season_sin`, `season_cos`. A target label never enters feature X.
- Baseline: seasonal naïve forecast using observed same-month revenue
  from one year earlier.
- Tribuo: regression linear SGD + AdaGrad, 450 epochs, RNG seed 1701.
- Diagnostics: MAE from a truly held-out year compared against baseline,
  with library `RegressionEvaluator` MAE cross-check. This checks code
  integration on an intentionally learnable synthetic process only.

## Gates

1. Test that Tribuo actually trains, produces finite held-out predictions,
   and beats baseline on **this synthetic dataset**.
2. Refuse training on observations from another tenant.
3. Refuse duplicated or missing monthly periods.
4. Refuse historical observations that became available after
   the training information-cutoff.
5. Generated predictions are explicitly `ESTIMATED` and never automatically
   published as facts.

## Commands

From repository root, with Java 21 and Maven:

```shell
mvn -B -ntp -f backend/pom.xml -Dtest=TribuoForecastSmokeTest test
mvn -B -ntp -f backend/pom.xml verify
```

## Next requirements before real use

- First complete Financial Correctness H1/H2 (source approval, tenant-safe
  `FactSetRevision`, Money/DecimalPolicy, CalculationContext).
- Build trusted feature extraction from time-stamped approved economic facts.
- Include seasonal-naive, moving-average and driver-based forecast baselines.
- Test rolling-origin multi-cutoff backtesting, concept drift, sparse history,
  missing values, tenant isolation, model versioning, reproducible provenance,
  input fingerprint, confidence intervals, validation and approval workflows.
- Tribuo uses `double` internally: do not use its predictions as precise
  ledger or settlement values. Any financial forecast must be surfaced as
  an estimate with an explicit rounding and units policy.
