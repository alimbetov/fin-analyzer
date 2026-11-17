# FIN-FORM-LIB-R5 — Output-only Derived Totals

**Status: Backend MVP, UNQUALIFIED_DRAFT.** Independent Java 21 / Spring Boot / Liquibase / H2 module. No React, no trusted finance publication.

## Decision

A total is a computed view field, **never an editable financial source observation**. The persisted source cells remain unchanged. No derived total is stored in `fin_intake.lib_report_revision_cell` or published to a FactSet. Formatting, orientation and totals are independent of `contentSha256`.

| `viewMode` | Total behavior |
|---|---|
| `INPUT` | Hidden. Only source values can be entered. |
| `PREVIEW` | Visible but derived/read-only |
| `VIEW` | Visible but derived/read-only |
| `EXPORT` | Visible in generated grid, suitable for a future export renderer; actual XLSX/PDF export is not implemented |

The first implemented operators are **SUM** and **LAST_VALUE** over one metric across selected periods. This is explicit opt-in, not a default applied to every metric. SUM adds all present values. LAST_VALUE selects the chronologically most recent one. Both currently require ALL selected periods to be PRESENT; a missing or N/A period produces `INCOMPLETE` with **no numeric total**. A true 0 is included. The period sort uses canonical start date, not UI column order.

The metric catalog does not yet have independently qualified additivity semantics across all 166 forms. An operator configured through this standalone API is **UNQUALIFIED_DRAFT** and must not be interpreted as approved financial methodology. In particular, do NOT automatically sum financial balances across time, stock levels, exchange rates, percentages, ratios, or already derived totals.

## Database and API

Liquibase `006-lib-report-derived-totals.sql` creates `fin_lib.lib_report_derived_rule`. Unique key: report + metric + derivedVersion. An independent `lib_report_definition.derived_version` CAS supports immutable policy history. The default version 1 has no derived rules, so output starts with no auto totals.

- `GET /api/v1/report-grids/{id}/derived-rules?organizationId=...&derivedVersion=2` — read current or historical rules.
- `PUT /api/v1/report-grids/{id}/derived-rules` — full replacement with body `{organizationId,expectedDerivedVersion,rules:[{metricCode,operator}]}`. Empty list disables future output totals through a new policy version.
- `GET /api/v1/report-grids/{id}/grid?organizationId=...&viewMode=INPUT|VIEW|PREVIEW|EXPORT&derivedVersion=2&timeAxis=ROWS|COLUMNS` — returns the same raw financial data, plus an output-only row/column when policies exist.

The extra axis uses reserved code `__TOTAL__`. The response marks output cells `derived=true` and a state of `PRESENT`, `INCOMPLETE` or `NOT_CONFIGURED`. The attempt to PUT/POST `__TOTAL__` as a period input is rejected. Original cells are `derived=false` and retain exact raw values. After configuration:
- With time axis COLUMNS, total appears as a rightmost **column**.
- With time axis ROWS, total appears as a bottom **row**.

A generated total must not be counted again in downstream aggregation. All output-only values are labelled provisional and are excluded from trusted HTAP.

## Test gates

1. Input view hides derived values and no policy means no totals.
2. Explicit SUM of 1200 and 800 yields 2000; explicit LAST_VALUE yields 800.
3. Output row and output column have the same source SHA and data revision.
4. One missing or N/A reporting period generates INCOMPLETE (not 0).
5. Rejected write to `__TOTAL__`, unknown metric, duplicate policy, unsupported operator, stale derived version.
6. Historical derived policy remains readable; changing policy does not rewrite the old source.

## Next extension (not implemented)

Calculated lines such as `grossProfit = netRevenue - cogs`, section subtotals, weighted averages, ratios, variances and year-to-date require typed, versioned expression contracts; DAG cycle detection, unit/currency consistency, declared missing-value policy and independent financial S2 approval. A naive generic SUM is prohibited. The display renderer must not become an alternative finance computation engine.

Backend is still local H2, no React, no authorization, no PostgreSQL production qualification.
