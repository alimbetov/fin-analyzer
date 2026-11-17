<!-- FIN-HTAP-CONSOLIDATED-ENTRYPOINT-V1 -->
> **FIN HTAP consolidation (2026-11-17):** [FIN-HTAP-CONSOLIDATED-TZ.md](FIN-HTAP-CONSOLIDATED-TZ.md) is now the **single normative implementation specification** for tenant/form/PeriodType/Period/Currency financial slices, calculations, aggregation, workload admission, workspace physical partitions, generation switching and DETACH/DROP. [Technical audit](FIN-HTAP-CONSOLIDATION-TECHNICAL-AUDIT-2026-11-17.md) records identified P0/P1 design defects and test gates. Sections below are **historical rationale and preserved acceptance details**; conflicting execution/physical-design prescriptions are superseded. Stricter immutable-source, authorization, independent method-S2, ACTUAL HOLD and RELEASE_HOLD safeguards are **retained**. This header does **not** assert implementation, CI GREEN or financial qualification.

# ADR-R2-09 — FIN HTAP Derived Indicators: strict PeriodType × Currency calculus

**Decision:** ACCEPTED DESIGN CONTRACT / IMPLEMENTATION AND INDEPENDENT S2 PENDING
**Date:** 2026-11-17 · **Repository:** `alimbetov/fin-analyzer` · **Only working branch:** `feat/fin-r0-r1a`.
**Normative relationships:** [FIN-BE-R2-W](FIN-BE-R2-WORKSPACE-ORCHESTRATION-TZ.md), [ADR-R2-07](ADR-R2-07-VERSIONED-FINANCIAL-AGGREGATE-LAYER.md), [ADR-R2-08](ADR-R2-08-LIFECYCLE-PARTITION-RECALCULATION.md).
**Code-backed baseline:** `AutoIndicatorEngine.compare`, `movingAverage`, `aggregate`, `SeriesKey` and `Fin01RevenueMomSqlModule`. These are existing prototypes, **not** proof of SQL/Java parity or production qualification.
**Status:** specification and safety rules; no new financial model approval, DB migration, auto-run activation or release.

## 1. Mandatory business key and strict isolation

**Exactly the owner's five-axis logical financial partition** (in the stated order):

```
Tenant + FormCode + PeriodType + Period + Currency
```

This is the **business slice address**, not the complete uniqueness/provenance key of trusted data, and not one physical PostgreSQL child relation per combination. Verified full context additionally pins `organization_id` (= tenant v1), `economic_entity_id`, `business_scope_id`, `fact_set_revision_id`, `snapshot_hash`, `data_space`, `reporting_basis`, `form_version`, `source_revision_id`, `metric_code+metric_version`, `unit_code`, canonical dimension/population selector, `time_kind`, fiscal calendar and policy versions and exact implementation fingerprint. `FormCode` comes from approved source revision, not an invented column on `fact_assertion`.

**A financial point belongs to exactly one logical slice** (period is part of the address). **An indicator's calculation series** is a collection of *authorized slices differing only by `Period`*, while retaining the SAME tenant, form, PeriodType, Currency and all nonperiod comparability keys. A user cannot move a row to another series by using SQL WHERE alone; policy-aware series validation must independently reject heterogeneous sources.

Strict rule: **no calculation combines different tenant, form, PeriodType, currency, entity/scope, dataSpace, reporting basis, metric/version, unit or incomparable dimensions unless an independently versioned, explicitly declared transformation constructs an entirely NEW typed derived series**. The ordinary auto-indicator path never performs currency exchange, period resampling or form reconciliation implicitly. If a methodology needs multiple forms, it consumes separately sealed/authorized inputs in its own reviewed join contract; it is **not** allowed to turn a cross-form join into an ordinary single-series moving average.

## 2. PeriodType is an arithmetic parameter, not just a filter

**`PeriodType`** means `MONTH`, `QUARTER`, `YEAR`, `AS_OF_DATE`, or reviewed `FISCAL_PERIOD` in the first version. It is distinct from the source `time_kind=INTERVAL|AS_OF`. The former sets the grain, exact calendar adjacency, comparison offsets and eligible window size. The latter tells whether an observation is a flow over an interval or a dated stock/level value.

| Type | Valid previous comparison | Same-grain YoY | Example moving window | Hard safety rule |
|---|---|---|---|---|
| MONTH | previous calendar/fiscal month = MoM | same month, previous year (12 months) | SMA3/6/12 monthly points | 3 valid monthly points are necessary for SMA3; no gaps |
| QUARTER | immediately prior quarter = QoQ | same quarter, previous year (4 quarters) | SMA4 quarters / trailing 4-quarter FLOW sum | quarter boundary/fiscal calendar consistency |
| YEAR | immediately prior full year = YoY | same as preceding year | SMA3 full years / 3-year CAGR | partial year must not be called full-year |
| AS_OF_DATE | exact approved prior comparison date | only if policy pins date alignment | closing-value samples for explicit cadence | stock is NOT summed; no inferred equal-length interval |
| FISCAL_PERIOD | prior period via reviewed calendar sequence | only approved matched fiscal period | configured explicit N periods | fiscal calendar/version locked; 4-4-5, 53-week treatment reviewed |

**Core formulas (versioned)**, with `x_t` and `x_{t-k}` from identical financial series:
- `DELTA(t,k) = x_t - x_(t-k)`.
- `PCT_CHANGE(t,k) = 100 * (x_t - x_(t-k)) / x_(t-k)` **only when previous > 0** for existing FIN-01 behavior; zero/negative prior follow explicit reason codes. Alternative signed-base growth formula is a *different methodology version*, never silently substituted.
- `SMA_N(t) = sum(x_(t-i), i=0..N-1)/N` only with N complete consecutive eligible observations.
- `TTM_12`: sum last 12 **MONTH / FLOW** points under complete, compatible calendar coverage; for STOCK the analogous “12-month sum” is prohibited.
- `STDDEV_POP_N(t) = sqrt(sum((x_i - mean)^2)/N)`; `STDDEV_SAMP_N(t) = sqrt(sum((x_i - mean)^2)/(N-1))` for N>=2. Exact population/sample choice and rounding are descriptor parameters.
- `Z_SCORE(t) = (x_t - mean_prior) / stdev_prior` with **prior-only** baseline that EXCLUDES `x_t`, qualified minimum historical sample length, and explicit zero-variance status. Z-score is an observation, never an automatic financial conclusion.
- `CV(t) = stdev / abs(mean)` only when the mean denominator is allowed; zero mean is NOT_COMPUTABLE.
- `CAGR = (end/start)^(1/years) - 1` only for a reviewed, positive baseline and a meaningful positive end and complete period definition; algebra/precision policy is versioned.
- `ROLLING_MIN/MAX/MEDIAN`, seasonality YoY and weighted means: each requires typed metric policy and enough full same-grain samples. Do not average percentages blindly or sum closing balances.

The existing `AutoIndicatorEngine` has comparable typed Java `compare`, `movingAverage`, `FLOW_SUM`, `STOCK_CLOSING` and day-weighted level average. It currently does not constitute blanket approval for new SQL, volatility, rolling standard deviation, statistical anomaly detection or arbitrary fiscal-calendar rules.

## 3. Currency is a calculation constraint, not just a partition predicate

- For `unit_code=MONEY`, **non-null ISO 4217 currency** is mandatory; for non-money typed units currency is NULL and comparison must also match unit/denominator semantics.
- A monetary series is single-currency, including ALL input periods: **KZT data never join USD data** in DELTA, MoM/YoY, SMA, TTM, standard deviation, anomaly baseline or SUM.
- Monetary aggregation across multiple source currencies is **BLOCKED_CURRENCY_MISMATCH**, not an implicit exchange at latest market rate. A multiform or multicurrency method must separately construct a new versioned `FX_CONVERTED` series with approved rate source/revision, quote convention, fixing/valuation date policy, different FLOW vs STOCK conversion date, missing-rate handling, exact input amount and target reporting currency, rounding and lineage. Only after conversion/sealing may it feed ordinary derived indicators. Do not use current FX rates to revalue historical monetary series.
- Existing explicit currency tags, form/source and `data_space` must survive aggregate construction, workspace sealing, SQL query and immutable result receipt. An output percent or z-score has **dimensionless unit** but retains a `sourceCurrency`/lineage in provenance; a change of output unit to PCT does not authorize mixed input currencies.
- Strict equality of currency must be checked in Java **and** PostgreSQL; a `NULL` currency cannot be used as a wildcard for MONEY.

## 4. SQL contract: grouping and ordering have different keys

The physical lifecycle `RANGE(reclaim_epoch)` partitions may contain more than one financial business slice. They only support pruning and eventual safe bulk DROP; **they do not provide calculation isolation**. Every SQL input must first resolve a SEALED/authorized source and the exact business key. A window's `PARTITION BY` contains **all invariant series coordinates, but deliberately omits `Period`**; `Period` is the exact ordered coordinate of the series. Including `Period` in `PARTITION BY` would incorrectly give each row a one-point window.

Illustrative template (**not executable SQL migration or production SQL**):

```sql
WITH qualified_series AS (
  SELECT organization_id, form_code, form_version, period_type,
         period_start, period_end_exclusive, currency_code, unit_code,
         metric_code, metric_version, economic_entity_id, business_scope_id,
         fact_set_revision_id, data_space, reporting_basis,
         dimension_hash, calendar_version, numeric_value
  FROM fin_workspace.sealed_indicator_input  -- proposed future sealed view
  WHERE workspace_id = :workspace_id AND reclaim_epoch = :reclaim_epoch
    AND organization_id = :organization_id
    AND form_code = :form_code
    AND period_type = :period_type AND currency_code = :currency_code
    AND fact_set_revision_id = :fact_set_revision_id
    AND sealed_population_hash = :population_hash
),
windows AS (
  SELECT q.*,
         lag(numeric_value, 1) OVER w AS prior_value,
         lag(period_start, 1) OVER w AS prior_period,
         count(*) OVER (PARTITION BY organization_id, economic_entity_id,
                        business_scope_id, fact_set_revision_id, data_space,
                        reporting_basis, form_code, form_version, period_type,
                        currency_code, unit_code, metric_code, metric_version,
                        dimension_hash, calendar_version
                        ORDER BY period_start ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)
                        AS window_count,
         avg(numeric_value) OVER
             (PARTITION BY organization_id, economic_entity_id, business_scope_id,
              fact_set_revision_id, data_space, reporting_basis, form_code,
              form_version, period_type, currency_code, unit_code,
              metric_code, metric_version, dimension_hash, calendar_version
              ORDER BY period_start ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)
                        AS candidate_sma3
  FROM qualified_series q
  WINDOW w AS (PARTITION BY organization_id, economic_entity_id,
                        business_scope_id, fact_set_revision_id, data_space,
                        reporting_basis, form_code, form_version, period_type,
                        currency_code, unit_code, metric_code, metric_version,
                        dimension_hash, calendar_version
               ORDER BY period_start)
)
SELECT * FROM windows;
```

**IMPORTANT:** The above SQL produces **candidates only**. `ROWS 2 PRECEDING` and `lag(...,1)` **cannot verify calendar adjacency**, full 3-month coverage, missing or overlapping periods, duplicate periods, complete form populations, value states, approval or fiscal calendar on their own. A separate canonical period calendar/series generator and completeness validator must reject missing/misaligned/bad-state observations; only then emit `CALCULATED`. SQL `AVG` and `stddev_samp` have typed policy/rounding differences to reconcile with Java `BigDecimal` (SQL numeric result scale/rounding HALF_EVEN is NOT inferred from PostgreSQL default `round`). All SQL identifiers are server-owned, inputs are bound, no raw client SQL.

## 5. Derivation, versioning, execution and immutable outputs

**Input:** an already SEALED `fin_agg`/workspace population with `tenant + FormCode + PeriodType + Period + Currency` routing and complete SourceKey. **Prepared key** `SeriesKey` omits period from grouping but pins all remaining comparability dimensions. **Descriptor** declares indicator code/version, eligible PeriodType(s), value kind, currency rules, source validation, exact lag/window, completeness policy, rounding/precision and `SQL`/`JAVA` implementation fingerprint. A published revised FactSet or changed calendar, FX policy, method/SQL code or mathematical definition creates a **new** derived input/output identity and does not mutate old indicators.

**Calculation:** admitted via existing Java tenant scheduler resource classes SQL/JAVA and independent ExecutionPermit, no implicit ACTUAL, no auto-enabled indicator for all 60 methodology families. Series-building cannot bypass `PREPARE` resource quota. Computation and deterministic status/reason are saved under fenced, append-only receipts (including input currency, PeriodType, period selection, metric source and provenance). `CALCULATED` is not `METHOD_S2_APPROVED` and not automatically a diagnostic `RISK` or `CAUSE`.

**Recommended initial indicators:** `DELTA_PREVIOUS_V1`, `PCT_CHANGE_PREVIOUS_V1`, `SMA3_V1`, `SMA12_V1`, `TTM12_FLOW_V1`, `STDDEV_POP3_V1`, `STDDEV_SAMP3_V1` as separately versioned and testable. `Z_SCORE_PRIOR12_V1`, `CV_PRIOR12_V1`, seasonality and anomaly flags only after S2 and qualified observation, no guessed risk threshold.

## 6. Mandatory PostgreSQL/Java acceptance matrix (RED before GREEN)

| Case | Context | Expected |
|---|---|---|
| DI-ISO-01 | Tenant A/B, equal form, period grain, currency | No cross-tenant window, query or cache read |
| DI-ISO-02 | Same tenant, same form, KZT and USD rows | Separate series; direct mixed-currency delta/SMA/STDDEV forbidden |
| DI-ISO-03 | Same tenant, same currency, two form codes/versions | Separate series; cross-form comparison only via explicitly qualified multi-input method |
| DI-ISO-04 | `MONTH` and `QUARTER` inputs share start day | Separate series; no monthly/quarterly mixture or implicit rollup |
| DI-ISO-05 | Same five-axis key but different published revision, scope, basis or dataSpace | Separate immutable source context and result; no reuse without exact key |
| DI-TIME-01 | MONTH Jan/Feb/Mar 100/120/180, SMA3 and March vs Feb delta | SMA3 = 133.333333333333; DELTA=60; PCT=50, compare legacy Java and SQL with policy scale |
| DI-TIME-02 | Jan+Mar only and `ROWS 1 PRECEDING` sees Jan | Fail INCOMPLETE/MISSING_PERIODS, **not** “Feb previous” |
| DI-TIME-03 | Quarter Q1/Q2 prior, year boundary, mixed fiscal calendar versions | QoQ only adjacent validated fiscal quarters; mixed policy rejected |
| DI-TIME-04 | Two same-period rows, overlap, partial month/year | Reject duplicates/overlap, cannot emit complete result |
| DI-MONEY-01 | Money currency NULL, KZT + USD input, wrong output currency tag | Fail closed CURRENCY_REQUIRED/CURRENCY_MISMATCH |
| DI-MONEY-02 | Approved FX conversion candidate on earlier date but missing fixed source/rate policy | Block implicit conversion; no guessed rate |
| DI-STAT-01 | POP vs SAMP variance/STDDEV same 3-period data | Different denominators correct; deterministic precision and status |
| DI-STAT-02 | Z-score baseline includes current outlier or has zero variance | Must use **prior-only** historical window; zero variance NOT_COMPUTABLE |
| DI-STAT-03 | Missing, EXPLICIT_ZERO, ESTIMATED ACTUAL | Must distinguish states, no zero substitution, no unqualified ACTUAL estimate |
| DI-OPS-01 | Physical cohort mixes tenants/forms/grains/currencies but logical window uses full SeriesKey | Identical to isolated fixtures; physical pruning cannot weaken isolation |
| DI-OPS-02 | Old generation pinned, new revision sealed and activated | Old results reproducible, new result distinct, DROP cannot remove referenced generation |

**Gate:** each test in PostgreSQL 16 Testcontainers plus independent Java BigDecimal golden, exact method/version/status/value/provenance comparison and same-SHA CI. A passing sample only does not establish correctness for arbitrary fiscal calendars or method S2.

**Release:** this ADR is design only. W1 stage storage, W3 derived SQL engine and W6 load benchmark remain not implemented; `RELEASE_HOLD` persists.
