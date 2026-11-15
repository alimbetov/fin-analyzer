# FIN — per-form diagnostic smoke matrix R1

**Branch:** `feat/fin-r0-r1a` · **Status:** SYNTHETIC TEST PILOT / DRAFT HOLD · **No production advice.**

## Scope and honest coverage

- Catalog: **166** top-level source forms, **30** extension sheets (separate layer).
- Existing sector calculator: **9** forms (G01–03, M01–02, S01, C01–03), emitting 10 candidate KPI values.
- Those 9 have individual synthetic input, golden expected output, explicit one-parameter or controlled multi-parameter what-if, incomplete-field smoke, and denominator checks where defined.
- Remaining **157** top-level forms are `NOT_EXECUTABLE_IN_SECTOR_KERNEL`, **not a PASS**. Several old forms have **separate** Java synthetic scenario methods; this matrix makes no false statement that they are totally absent or production-ready.
- All **30** EX sheets are marked `CONTRACT_ONLY_IN_THIS_SMOKE`; Java scenario tests for selected EX sheets are tracked separately, not silently counted as nine sector pilot forms.
- No forms have yet passed all five **production methodology** gates. Nine positive mathematical cases do **not** imply nine qualified financial diagnoses.

## Five questions — required gates for EVERY table/form

| Step | Acceptance when implemented | Current nine-form smoke |
|---|---|---|
| SMOKE-01 What? | Exact value, unit/period/grain, approved source lineage, complete comparable inputs | Synthetic calculations checked; server source authority **OPEN** |
| SMOKE-02 Why? | Root-cause candidates trace to verified factor decomposition, alternatives and disconfirming evidence; correlation not causation | `HYPOTHESIS_ONLY` |
| SMOKE-03 Seriousness? | Versioned sector/context-specific materiality + risk policy, calibration, denominator and coverage checks | `NOT_ASSESSED` |
| SMOKE-04 Action? | Verified rule → reviewable playbook, owner, approval, applicability and KPI after action | Three negative-result review candidates; `REVIEW_REQUIRED`, others `NO_QUALIFIED_TRIGGER` |
| SMOKE-05 Effect? | Recompute value under explicitly controlled assumptions, cost/time constraints, sensitivity, do-nothing comparison | `SIMULATED_ONLY` input-substitution arithmetic, **NOT** a forecast or guaranteed benefit |

The smoke has **three different notions of success**:
1. `TEST_PASS`: reproducible numeric case, with safe missing/zero semantics.
2. `METHOD_APPROVED`: signed-off accounting method, calibrated interpretation and independent S2 validation.
3. `E2E_ACCEPTED`: real server-authoritative approved facts → diagnostic → action → React, with auth and macOS manual UAT.

Only (1) can be claimed by current pilot once CI runs green. (2) and (3) are not fulfilled.

## Independent synthetic golden cases

| Form | Tested KPI | Baseline | Explicit hypothetical change | After | Difference |
|---|---|---:|---|---:|---:|
| SF-G01 | SKU gross profit | -100.00 | COGS: 1100 → 900 | 100.00 | +200.00 |
| SF-G02 | Stock turnover/window | 6.00 | Avg stock: 100 → 150 | 4.00 | -2.00 |
| SF-G03 | Landed unit cost | 14.00 | Freight: 20 → 10 | 13.00 | -1.00 |
| SF-M01 | Billable utilization (%) | 60.00 | Billable hours: 60 → 70 | 70.00 | +10.00 percentage points |
| SF-M02 | Engagement contribution | -100.00 | Other direct cost: 900 → 800 | 0.00 | +100.00 |
| SF-S01 | Service margin/unit | -10.00 | Material cost: 900 → 800 | 0.00 | +10.00 per job |
| SF-C01 | Good output yield (%) | 90.00 | Good units: 90 → 95; waste units: 5 → 0 | 95.00 | +5.00 percentage points |
| SF-C02 | Manufacturing cost/unit | 11.00 | Overhead: 100 → 0 | 10.00 | -1.00/unit |
| SF-C03 | Equipment utilization (%) | 80.00 | Run: 80 → 90; downtime: 20 → 10 | 90.00 | +10.00 percentage points |

Currencies/units are not interchangeable. Percentage deltas are **percentage points**, not relative growth. All examples are artificial arithmetic what-if tests; they contain **no implementation cost, uncertainty, price elasticity, expected ROI or real-world causal effect**.

Synthetic negative cases: 9 missing-field/coverage gates, 8 denominator-zero cases, 3 negative economic-result observations. Negative contribution rules are deliberately narrow: `SKU_GROSS_PROFIT`, `ENGAGEMENT_CONTRIBUTION`, `SERVICE_UNIT_MARGIN`. Positive ratios do **not** generate invented green ratings.

## Legacy Java diagnostics retained and hardened

`ScenarioEconomicsEngine` preserves existing F01/F03 balance and cash checks, F02 history, budget/actual, MFG WIP & standard/actual, construction BoQ/EVM, trade, investment, DSCR candidate and IP cash movement. Regression: **IP cash movement now emits exact input row/source revisions; any month lacking matching approved inflow or outflow is `MISSING_EVIDENCE` instead of disappearing.** Engine version incremented to `SCENARIO_ECONOMICS_V2`; Java regression in `ScenarioEconomicsEngineTest`. These remain `SYNTHETIC_PILOT_UNQUALIFIED`.

## Run and retain evidence

```bash
npm --prefix packages/sector-pilot test
mkdir -p test-results
node packages/sector-pilot/src/smoke-cli.mjs > test-results/fin-form-smoke-matrix.json
npm run typecheck
npm run build
npm run test:e2e -- --project=chromium
mvn -B -ntp -f backend/pom.xml verify
```

The FIN CI workflow also uploads `fin-diagnostic-smoke-matrix` on a specific run/commit SHA. Record a GREEN workflow link, completed job conclusions and the JSON artifact **before** stating CI PASS. An independent reviewer must sign off accounting mathematics, sector interpretation, scenario assumptions, missingness, tenant isolation and approved source mapping. Manual MacBook UAT is separate.

## Next deliveries after baseline smoke

1. Server-owned Form/Method Registry and canonical fact reconciler. Compare Java vs JS on one shared fixture across old/new forms.
2. Add a case per remaining table with `what`, `why`, `seriousness`, `action`, `effect` statuses; absence is always explicit.
3. Qualify human-reviewed causal hypotheses, thresholds, impact ranges and counterfactuals sector by sector.
4. End-to-end React source → approval → diagnosis → action → outcome against PostgreSQL and Access Center.
5. Preserve Draft/HOLD and do not merge `main` without S2 methodology review and manual UAT.

**Primary rule:** no fake PASS, no made-up recommendations, no user-confirmed browser value treated as legal/audited proof.
