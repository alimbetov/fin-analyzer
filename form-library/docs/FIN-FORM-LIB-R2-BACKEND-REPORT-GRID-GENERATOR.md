# FIN-FORM-LIB-R2 — Backend report grid generator

**Status:** MVP engineering pilot (Java 21, Spring Boot, Liquibase and H2), financial approval HOLD. No React.

A cell is addressed by the canonical (reportId, metricCode, periodType, periodCode), not physical row/column positions. The generator pivots the identical source revision into two layouts:

| timeAxis | Row axis | Column axis |
|---|---|---|
| COLUMNS | One or more indicators | Selected reporting periods |
| ROWS | Selected reporting periods | One or more indicators |

The six additional tables are:
- fin_lib.lib_report_definition: report identity, timeAxis, layoutVersion and tenant.
- fin_lib.lib_report_metric: selected indicators and their display order.
- fin_lib.lib_report_period: canonical periods, boundaries and display order.
- fin_intake.lib_report_revision: immutable snapshot and SHA-256.
- fin_intake.lib_report_revision_cell: sparse values with PRESENT/NOT_APPLICABLE states.
- fin_intake.lib_report_command_receipt: idempotency keys and hashes.

MISSING is the absence of a stored cell. PRESENT with value 0 is not MISSING. This MVP supports numeric SOURCE_FIELD catalog indicators (MONEY, DECIMAL, INTEGER) and does not claim that any resulting value is financially approved. It accepts one period granularity per report (D/W/DEC/M/Q/H/Y), up to 40 metrics and 60 periods (2,400 cells), currency precision checking with no FX conversions. Four read locales: ru/kk/en/zh-Hans. Translation gaps are explicit.

API:
- POST /api/v1/report-grids — create a report; body: organizationId, reportCode, title, currencyCode, periodType, periodCodes[], metricCodes[], timeAxis.
- GET /api/v1/report-grids/{id}/grid?organizationId=...&locale=ru&timeAxis=COLUMNS — read a rectangular matrix with MISSING cells filled in.
- POST /api/v1/report-grids/{id}/revisions — Idempotency-Key header, body: organizationId, expectedRevision, cells[], createdBy, changeReason, sourceKind.
- GET /api/v1/report-grids/{id}/revisions/{number}/grid?organizationId=... — historical immutable revision.
- PUT /api/v1/report-grids/{id}/layout — organizationId, expectedLayoutVersion, timeAxis; changes presentation only.

Every Save creates a new *complete sparse snapshot*, not a PATCH operation. Omitted cells become MISSING in the new revision. Data SHA-256 is independent of axis orientation. An axis change only advances layoutVersion. Concurrent attempts with outdated data version return 409. No code path here publishes financial facts or executes HTAP.

Example create request:

~~~json
{"organizationId":"00000000-0000-0000-0000-000000000001","reportCode":"monthly-pnl","title":"Monthly P&L","currencyCode":"KZT","periodType":"M","periodCodes":["2026-01","2026-02","2026-03"],"metricCodes":["F02.netRevenue","F02.cogs","F02.operatingExpenses"],"timeAxis":"COLUMNS"}
~~~

Example revision request with Idempotency-Key header:

~~~json
{"organizationId":"00000000-0000-0000-0000-000000000001","expectedRevision":0,"sourceKind":"MANUAL","createdBy":"local-user","changeReason":"initial","cells":[{"metricCode":"F02.netRevenue","periodCode":"2026-01","state":"PRESENT","value":"1000.00"},{"metricCode":"F02.netRevenue","periodCode":"2026-02","state":"PRESENT","value":"1200.00"},{"metricCode":"F02.operatingExpenses","periodCode":"2026-02","state":"NOT_APPLICABLE","value":null}]}
~~~

**Not implemented:** React Excel clipboard, formulas, rowset transaction dimensions, trusted evidence documents, exchange rates or conversion, source approval and FIN HTAP ACTUAL. No Spring Security: the demo must remain bound to localhost. PostgreSQL qualification and auth are required before production.
