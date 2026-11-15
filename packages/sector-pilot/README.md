# FIN Project Marketplace sector pilot

**Implemented in this isolated package:** 9 sector input forms and deterministic monthly, quarterly, half-year and annual aggregates for trade (G01–G03), professional/consumer services (M01, M02, S01), manufacturing (C01–C03). **Not implemented:** React screens, spreadsheet import, persistence, Spring Boot BFF, Keycloak, legal/security workflows and general SectorPack registry.

Requires Node 22+, no external runtime dependencies. From this directory run:

    npm test
    npm run demo

The demo uses synthetic example data in examples/pilot.json and prints structured results. The first retail months yield 10%/50% gross margin and 46% for complete Q1: the rate is recalculated from the combined numerator and denominator.

Input rows are MONTH snapshots. Amounts and quantities must be decimal **strings** with at most 6 fractional digits; null is missing, never zero. The source approval flag is a **test contract**, not real authorization. Duplicate source IDs or multiple monthly revisions for the same business/form/dimension are rejected for staging/reconciliation. Incomplete periods output status INCOMPLETE and null metrics, not invented zeros. Zero denominators give NOT_COMPUTABLE. Scoped results are separated by business, dimension, form and currency.

SF-G02 uses the approved monthly average stock value and a day-weighted average over the window; turnover is over the selected time window, **not annualized**. SF-M02 computes direct hours × rate separately for each month, and only then sums labor costs. All numerical results use fixed-point BigInt arithmetic; display precision is 2 decimals rounded half away from zero.

**Qualification:** numerical RED/GREEN tests are supplied. The nine candidates are engineering implementations, NOT yet independently methodology-approved or accepted by users. Full RU/KK/EN KPI dictionary entries, production data provenance, domain migrations, React and backend integration, import hardening and independent S2 remain pending. Other 54 sector forms remain specified only.

See docs/FIN-SECTOR-PILOTS-GMC.md for the acceptance criteria and status.
