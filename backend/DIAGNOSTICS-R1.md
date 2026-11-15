# FIN-DIAG-R1 — deterministic business diagnosis and improvement playbooks

**Release status: EXPERIMENTAL / SYNTHETIC ONLY. No production economic advice.**
**Implementation:** `BusinessDiagnosticEngine`, `DecisionPlaybookRegistry`,
`TrustedBusinessDiagnosticService` and additive Flyway migration V4.

## What the pilot actually does

- Uses the selected assertions of a **published and immutable** PostgreSQL
  `FactSetRevision` (not arbitrary JSON, a React form payload, or unapproved facts).
- Reads 13 calendar months for four candidate metrics: monthly `REV_NET` and
  `COGS` (FLOW); end-of-month `CASH_END` and `AR_END` (STOCK).
  Source metric types and unit currency are verified against the immutable
  `metric_contract`. Dimensioned source lines are **rejected**, not
  silently aggregated or misrepresented as company totals.
- Builds per-metric current, MoM, YoY, TTM (FLOW only), and 3-month moving
  averages. The underlying AutoIndicatorEngine rejects unknown periods,
  incompatible stock/flow operations, zero percentage denominators, or
  partially covered moving windows.
- Applies three **directional, non-calibrated** rules:
  `REVENUE_YOY_DECLINE` (observation), `GROSS_MARGIN_YOY_COMPRESSION`
  (observation based on contemporaneous revenue and COGS), and
  `REVENUE_CASH_AR_DIVERGENCE` (observation plus a separate,
  explicitly noncausal collection-cycle *hypothesis*).
  Missing inputs result in `MISSING_EVIDENCE`, not invented zeroes.
- Includes three **proposed** decision playbooks with translation keys,
  review tasks, verification metrics and mandatory human approval. No
  automatic changes to prices, budgets, customers, suppliers, loan terms,
  actuals or projects, and **no invented economic benefits**.
- In one PostgreSQL transaction, saves a JSONB snapshot with the selected
  FactSet provenance, exact decimal values as strings, rule/method versions,
  evidence, reasons and action proposals. The database computes both input
  and snapshot SHA-256 hashes and **forbids UPDATE/DELETE**.
- Repeating the same calculation is idempotent, and an unchanged rule
  version with changed results or definition is rejected. New revisions
  create new snapshots; old snapshots become `STALE_SOURCE` relative to
  the current FactSet pointer without being overwritten.
- Diagnostic access is **not exposed as an HTTP endpoint**. Its
  `authorizedOrg` argument must only be supplied by a future verified
  OIDC/ABAC application service. No standalone tenant security is claimed.

## Strict limits

1. **Synthetic-only guard:** `SYNTHETIC` is required by
   `TrustedBusinessDiagnosticService`; `ACTUAL`, `PLAN`,
   `FORECAST` and `SCENARIO` are refused. This is intentional until
   independent economist/CFO/accountant review of rule semantics,
   time-series/sector calibration, methodology and outcomes.
2. These comparisons are **signals**, not audited diagnosis or causal
   proof. MoM signals may be seasonal, inflation-sensitive or incomparable
   because of business context. No universal red/yellow/green scoring.
3. Missing observed months and missing source metrics are explicit.
   No automatic seasonality correction, cohort matching, benchmarking,
   currency conversion, unit conversion, inflation adjustment, factor
   attribution or probability estimation.
4. `GROSS_MARGIN_YOY_COMPRESSION` compares gross profitability for a
   single month against the same month of the previous year, using exact
   cross-products rather than rounded percentages. Both denominators
   must be strictly positive.
5. A single approved FactSet revision is assumed to contain the required
   historical periods; no multi-revision historical reconciliation or
   fiscal calendars other than calendar months are supported.
6. `PILOT_RULES` and `PILOT_PLAYBOOKS` are Java-controlled, versioned,
   auditable candidates. There is no general script engine or dynamic
   unreviewed rules, user threshold editor, external notifications, or
   automated action executor.
7. This R1 pilot does **not** operationalize all 27 documented KPIs, all
   166 forms or all 21 economic sectors. It does not modify React.
8. Production requires validated source/form mappings, authorization,
   DB least privilege/RLS, independent methodology qualification,
   seasonal tests, review of assumptions and historical outcome quality,
   validated country/sector applicability, translated playbook content,
   and a human approval workflow for corrective actions.
9. Rule version strings, engine implementation version and playbooks
   participate in the fingerprint; code changes **must** increment
   `METHOD_VERSION` and an applicable ruleset version.

## Methodology acceptance

- `DIAG-01`: 13 complete months -> MoM, YoY, TTM and three-month mean.
- `DIAG-02`: missing/zero months -> incomplete or NOT_COMPUTABLE.
- `DIAG-03`: stocks not summed into TTM.
- `DIAG-04`: margin calculated from paired flows, never averaged monthly
  percentages.
- `DIAG-05`: cash/revenue/AR disagreement -> separate observation and
  review hypothesis; missing AR -> missing evidence.
- `DIAG-06`: unsupported currency, fact revision or dimensionality -> reject.
- `DIAG-07`: unqualified actual inputs -> reject.
- `DIAG-08`: old snapshot retains its values after historical revision;
  current pointer shows stale/current correctly.
- `DIAG-09`: duplicate attempts reuse immutable snapshot; SQL UPDATE/
  DELETE fails; separate tenant cannot load or publish.
- `DIAG-10`: decision playbooks propose human-approved verification tasks;
  no action automatically executes.

## Run

```sh
mvn -B -ntp -f backend/pom.xml verify
```

GitHub Actions runs Java 21 with PostgreSQL 16 Testcontainers.

**Next engineering slice:** server-owned Form/Metric/Rule registries with
qualification decisions; fiscal and seasonality policies; historical
revision reconciliation; trusted rule eligibility; persisted action
assignments and outcomes; scenario simulation using explicit assumptions.
Only then extend to operational use and Tribuo backtesting. Tribuo stays
`test-only` until a real dataset and out-of-time validation demonstrate
predictive benefit over simple baselines.
