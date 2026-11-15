# FIN — auto-indicators, sector coverage and analytical column contracts

**Status: initial Java calculation pilot. No production form ingestion / report renderer.**
The package is located in `backend/src/main/java/kz/fin/backend/analytics/AutoIndicatorEngine.java`.
The catalog is a *method candidate*, not verified mappings for 21 industries.

## Implemented operators

| Operator | Meaning | Restrictions |
| --- | --- | --- |
| `FLOW_SUM` | Sum non-overlapping flows across complete window | No stocks, no gaps, no unknown amounts, same tenant/scope/metric/currency/version |
| `MONTHLY_FLOW_MEAN` | Arithmetic mean of monthly flow totals | Exactly calendar months with full coverage; NOT weighted percent |
| `DAY_WEIGHTED_LEVEL_MEAN` | Sum(level × active days)/days | Continuous, non-overlapping level intervals; no monthly-balance arithmetic mean masquerading as a weighted average |
| `STOCK_CLOSING` | Stock at requested closing date | Requires AS_OF observation at exact date; no monthly stock summation |
| `ABS_CHANGE`, `PCT_CHANGE` | New minus old; percentage against positive prior | Previous zero/negative => percentage NOT_COMPUTABLE; absolute delta still available |
| `PERCENT_OF` | Typed ratio * 100 | Compatible reporting context, currency, unit and period |
| `SHARE_PCT` | Member / sum of **complete** explicitly enumerated population * 100 | No partial grouping, no negative member or zero total, no pre-aggregated TOTAL row |
| `MOVING_MEAN` | N-month arithmetic moving mean of monthly flow or closing stock | Exactly N complete calendar months, no estimates silently treated as ACTUAL |

Every operator returns `CALCULATED`, `ESTIMATED`, `INCOMPLETE` or `NOT_COMPUTABLE`, preserving a
reason for invalid/missing inputs. BigDecimal percentage or mean division uses **12 decimals
HALF_EVEN** (an explicit pilot policy), while exact SUM does not round; overflow is rejected.

The engine cannot infer whether `actualCost` means per-month costs or to-date costs.
`sector-auto-indicator-coverage.tsv` covers A-U with sample forms, source fields,
groups and proposed operators. All 21 rows are `DESIGN_ONLY`, and F/O/U are explicitly
marked `REVIEW_REQUIRED` because cumulative versus period semantics are unclear.
SaaS new MRR is never silently treated as accounting revenue. Sector activity codes
below sections require versioned classifier coverage and separate method qualification.

`auto-indicator-operators.tsv` lists nine implemented pure-Java operators plus
six future candidates. `auto-report-columns.tsv` proposes example report ordering.
**The pure Java AutoReportProjection now executes requested, strongly typed
report columns in deterministic numeric display order**, including sums, means,
period comparisons, moving averages, ratios and complete group shares. It
returns CALCULATED / INCOMPLETE / ESTIMATED / NOT_COMPUTABLE status with
explanatory reason. A report may not mix organizations, data spaces or fact-set
revisions. The supplied `auto-report-columns.tsv` remains DESIGN_ONLY:
**Columns are not yet rendered by a ReportRegistry**, therefore remain DESIGN_ONLY.
The Java engine accepts `SeriesKey` identity with factSetRevisionId, data space,
basis, unit, currency and dimensions. It does NOT verify approval, tenant authorization,
model ownership, history or data source fingerprints: those must be resolved by a
future trusted `FormRegistry → FactResolver → CalculationRun → ReportSnapshot` pipeline.

## Next required implementations (not included in this commit)

1. FIN-SECTOR-COVERAGE-R1: progressively qualified activity-level (OKED code + version)
   industry packs; review each field's event grain, source dates, currencies and fiscal calendar.
2. FIN-FORM-R1: FormRegistry, typed FieldCode → MetricCode mapping, revision approval,
   idempotent submission and audit.
3. FIN-IND-R1: versioned, authorized CalculationRun snapshots and server-created input
   fingerprints, IndicatorReadiness and stale-on-new-approval.
4. FIN-REPORT-R1: ReportRegistry/column definitions, deterministic order, RU/KK/EN
   titles and format, AST expression definitions, PDF/Excel policy and ABAC projection.
5. FIN-METHOD-R1: expert-reviewed group denominator policies, weighted ratios, YTD/TTM,
   seasonal/term windows, growth from negative base, financing/cost allocation and unit conversions.

Run `mvn -B -ntp -f backend/pom.xml verify` with JDK 21, Maven and Docker to confirm
existing financial, industry pilot and PostgreSQL Testcontainers regression suites.

NO frontend changes, NO merge, NO user financial data, NO claim of 21 industry qualification.
