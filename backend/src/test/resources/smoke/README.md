# FIN Backend — reproducible economic smoke data (SIMULATED)

This directory is **test-only**. All values, organizations, stores, sources, scenarios, and
expected outcomes are synthetic. They must not be used as client financial evidence or
be automatically approved/published. Frontend and existing Excel imports are unchanged.

## Golden fixture sets

| Fixture | Cases | What it proves |
| --- | ---: | --- |
| `calculation-cases.tsv` | 33 | 8 formula models: multi-step DAG, branches, shared dependencies, dimension-aware unit price, margins, negative profits, zero denominators, missing/NA/estimated values, plan vs forecast vs what-if |
| `period-cases.tsv` | 16 | Flow sum and AS_OF stock: complete Q1, missing months, explicit zero, estimated/missing inputs, overlap, outside window, half-month intervals, closing stock |
| `postgres-facts.tsv` | 16 assertions | 2 organizations, 2 stores, 3 months, 3 competing sources of January revenue, corrected revision, cash stock at date, and quantity without currency |

All expected values are authored as independent golden values. The smoke suite does not
derive expected values from the same formula implementation under test.

## Run

Requires Java 21, Maven, and Docker available to Testcontainers.

```bash
mvn -B -ntp -f backend/pom.xml verify
```

For the pure domain smoke tests only (no PostgreSQL):

```bash
mvn -B -ntp -f backend/pom.xml -Dtest=CalculationSmokeTest,PeriodSmokeTest test
```

CI workflow: `.github/workflows/fin-backend-domain.yml`, includes PostgreSQL 16.

## Tested behaviors and limitations

- Missing months are `INCOMPLETE`, overlapping intervals are invalid.
- Flow metrics sum only complete, comparable, gap-free periods.
- Stock metrics select the period closing-date observation instead of summing balances.
- Estimates propagate through CalculationDag as `ESTIMATED`, while period
  aggregation presently does **not** accept estimates as confirmed actuals.
- Independent versions of revenue from ERP, bank and restatement are kept separate.
  The smoke approval selections demonstrate immutable revision references only;
  **production resolution and approval workflow are not implemented**.
- `FactKey` carries economicEntity, scope, metric version, period, basis,
  dimensions, unit and currency, but CalculationDag currently accepts only a
  metricCode→value map. Therefore it is not yet qualified for arbitrary
  cross-currency calculations, multi-company consolidation or period-shifted
  recurrent DAG formulas.
- `form-compatibility.tsv` lists 166 forms as **design mappings**; it does not
  imply functional coverage of those forms.
- Planning, Forecast and Scenario tags in the smoke cases exercise the
  common arithmetic model, **not** persisted aggregate lifecycles or a
  forecasting/statistical engine.

## Follow-up engineering gates

Implement authoritative FactResolver and FactSetRevision persistence/approval,
strongly typed CalculationContext, formula versioning, source provenance,
period-aware DAG, timezone/fiscal-calendar rules, currency conversion,
materialized CalculationRun snapshots and tenant-scoped authorization.
