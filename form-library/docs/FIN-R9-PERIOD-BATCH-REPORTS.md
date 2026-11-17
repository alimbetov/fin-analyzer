# FIN-R9 Period Batch Creation / Daily Weekend Highlighting

**Status:** standalone backend range planning and creation via existing ReportGridService; interactive HTML mock independent of backend.

## Requirements and API

- Monthly: `1..12` consecutive canonical months, including year transitions; daily: `1..31` consecutive Gregorian calendar days, including month/year and leap-day transitions. The maximum applies to this shortcut, not to the older explicit-period grid endpoint.
- `GET /api/v1/period-batches/preview?periodType=D&startPeriod=2026-11-01&count=31` -> immutable calculated range and canonical period list with weekday identifiers, Saturday/Sunday flags and design tokens.
- `GET /api/v1/templates/F02/preview?locale=ru&periodType=D&startPeriod=2026-11-01&periodCount=31&styleCode=EXCEL_GRID` -> read-only blank Excel-style matrix and `periodCalendar` for column or row highlighting. Either a `periodCodes` list OR the `startPeriod+periodCount` pair, never both.
- `POST /api/v1/report-grids/from-period-range` -> existing `ReportGridService.create` with resolved canonical periods, **no new fact writer**. JSON: `organizationId,reportCode,title,currencyCode,periodType,startPeriod,periodCount,metricCodes,timeAxis`. Subsequent edits use existing `POST /api/v1/report-grids/{id}/revisions`, preserving idempotency/SHA and exact period/metric codes.
- Style token `WEEKEND_SATURDAY` / `WEEKEND_SUNDAY` / `WEEKDAY` for daily periods, `PERIOD_STANDARD` for monthly. These are display metadata and never modify raw financial values. Saturday/Sunday are treated as calendar weekends independently of locale; public holidays and alternative workweek calendars are **not guessed**.
- A single range represents one multi-period report. Do not create 31 independent form instances; existing `FormDraftService` remains single-period. The ReportGrid pilot does **not** yet have `projectId` or trusted actor identity, so it is not the final unified FIN intake contract.

## GUI behavior

- New report workflow: select form/project (future trusted catalog and project selection), choose period mode `Month` or `Day`, set initial period and count, preview chronological columns/rows. Daily headers show weekday.
- Weekend palette must remain legible in light/dark themes and accessible without color (e.g. weekday label and tooltip). Saturday amber and Sunday red tint; ordinary row banding does not override weekend tint. In period-rows mode, highlight rows instead of columns.
- Preserve frozen column labels and narrow 36px centered row gutter, horizontal/vertical scrolling, and typed canonical metric codes. Display localization is separate from period identifiers.
- Manual cell input, pasted data or a range of periods must use the **same writer** in the existing report-grid pilot. In future unified `fin_intake`, the project and authenticated author are required.

## Acceptance

1. One and 12 M periods; one and 31 D periods; across new year and February 29.
2. Exactly four Saturdays and five Sundays for November 1–December 1 2026, no holiday inference.
3. Same raw metric+period bindings in orientations `ROWS` and `COLUMNS`; no arithmetic changes from highlighting.
4. Reject unsupported period types, non-positive/too-large counts, impossible dates, and ambiguous simultaneous explicit/range input.
5. Read-only preview does not write to either `fin_lib` or `fin_intake`. Creating range reports uses only the existing report-grid service.
6. Existing 32 tests stay green; additional unit and HTTP integration tests exercise these scenarios.
