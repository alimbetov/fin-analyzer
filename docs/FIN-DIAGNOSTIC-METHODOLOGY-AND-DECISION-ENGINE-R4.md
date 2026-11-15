# FIN-DIAG-R4 — Explainable Financial Diagnostic & Decision Engine

**Status:** DESIGN / REVIEW REQUIRED. **Target branch:** `feat/fin-r0-r1a`, Draft PR #2. **Date:** 2026-11-16.  
**Production decision:** HOLD. This specification does not approve any financial method, interpret ACTUAL customer data, or authorize autonomous business decisions.

## 0. Evidence-based baseline

- The `main` branch is documentation-focused; executable diagnostics live in `feat/fin-r0-r1a`. A separate Draft PR #7 carries requirements plus the initial nine-form JS pilot; it is not merged.
- `BusinessDiagnosticEngine` implements three non-causal synthetic observation rules: YoY revenue decline, YoY gross-margin compression, and revenue/cash/AR divergence.
- `ScenarioEconomicsEngine` exposes sixteen synthetic economic scenario methods, plus an explicitly NOT_COMPUTABLE sector-qualification placeholder. `ScenarioFiveQuestionEngine` deliberately reports `HYPOTHESIS_ONLY`, `NOT_ASSESSED`, human-review actions and `SIMULATED_ONLY` effects.
- 166 top-level form codes are registered. 22 unique form codes have Java or JS numerical golden fixtures; this is **fixture coverage**, not methodological approval. Of 30 EX sheets, 10 participate in Java numeric fixtures and 20 have contract-only checks. Document/reference forms must not generate synthetic financial KPIs.
- Trusted synthetic PostgreSQL FactSet publication and immutable diagnostic snapshots exist; they do not provide a production OIDC/ABAC and authoritative FormRegistry `ACTUAL` endpoint.
- `ScenarioEconomicsEngine.approved()` filters only approved rows. Unless a complete, declared fact population is proven, a sum of approved rows may still represent an incomplete business period. Build an explicit completeness gate.
- PostgreSQL uses SHA-256 digests of snapshots and selected inputs. A digest is an integrity fingerprint, **not a digital signature** or external attestation.
- On SHA `50587b73d61b5bdcb42ef026f5f5f159b2edb977`, FIN CI failed in the Node smoke-matrix manifest preflight. Fixed in `d1175aeff51bf1168a948d11536a668ea89811e4`: TSV header previously compared literal backslash-t sequences, and an obsolete hard-coded unique-method count (14) was used for a 16-row manifest. Record exact latest SHA and FIN CI evidence separately.

## 1. Contract: what the system may claim

Each finding is separately typed as:
1. `MEASURED_FACT`: approved and published amount within an explicitly scoped and complete evidence population.
2. `DERIVED_METRIC`: reproducible deterministic formula over approved facts (or labelled estimates), with exact denominator, units, period, rounding and version.
3. `OBSERVATION`: documented temporal/comparative relationship. Correlation or co-movement is not a root-cause proof.
4. `CAUSAL_HYPOTHESIS`: an explanation candidate with counterevidence, competing hypotheses and tests that can confirm or reject it.
5. `RISK_ASSESSMENT`: approved materiality/context/covenant/operational deadline applied to verified exposure. If no rule exists, `NOT_ASSESSED`.
6. `ACTION_CANDIDATE`: human-review proposal with accountable owner, dependencies, constraints and requested evidence.
7. `SCENARIO_ESTIMATE`: independently reproducible consequence of **explicit** driver changes, timing, costs and assumptions. Not an observation or guarantee.
8. `OUTCOME_REVIEW`: actual post-implementation measurements compared with a frozen plan/baseline, with comparability rules.

**Prohibited transformations:** `MISSING → ZERO`; `PENDING → APPROVED`; `FORECAST → ACTUAL`; `OBSERVATION → PROVED_CAUSE`; `INPUT_SUBSTITUTION → FORECAST`; `DRAFT_METHOD → QUALIFIED`; `HASH → DIGITAL_SIGNATURE`.

## 2. End-to-end orchestration

| Stage | State/command | Required output | Fail-closed condition |
| --- | --- | --- | --- |
| 1. Context | `CreateEconomicCase` | organization/economic entity/business scope, S0–S4 maturity, business models, aim, currency, accounting basis, fiscal calendar, reporting horizon | contradictory scope; unknown accounting basis |
| 2. Diagnostic plan | `PlanEvidence` | method candidates, required fact codes and grains, form/sheet mapping, minimum questions, applicability explanations | missing qualified method; inapplicable sector |
| 3. Intake | `UploadTemplate` / `EnterForm` | immutable `SourceRevision`, import preview, field lineage, duplicate/conflict list | unsupported template version, hostile XLSX, mismatched period/scale |
| 4. Review | `ReconcileAndApprove` | validations, source-to-canonical mapping, completeness declarations, designated approver | unbalanced bridge, mixed cash/accrual, unapproved or ambiguous fact |
| 5. Publish | `PublishFactSet` | immutable `FactSetRevision`, accepted fact selection, stable fingerprint and revision pointers | unauthorized tenant/zone, stale optimistic revision, partial population |
| 6. Calculation | `EvaluateMetrics` | versioned financial metrics and invariant outcomes with source evidence paths | missing denominator, incompatible unit/currency/time, incomplete window |
| 7. Detect | `EvaluateRules` | quantitative signals, comparable baseline, explicit non-trigger results | unidentified or unapproved applicability threshold |
| 8. Investigate | `TestDrivers` | competing hypotheses, proposed source question, falsification checks | unsupported causal assertion |
| 9. Assess risk | `AssessMateriality` | cash exposure, deadline, contractual constraints and documented severity rule or NOT_ASSESSED | arbitrary universal financial health score |
| 10. Simulate | `EvaluateAlternatives` | do-nothing, baseline, adverse and action cases; incremental cash and sensitivities | no consistent horizon, double-counted benefits, missing action cost |
| 11. Govern | `ApproveActionAndMonitor` | signed-off decision record, owner, due date, outcome metric, monitoring snapshot | automatic action or disclosure without authorization |

The wizard asks only for evidence that unlocks a qualified next step. Users may choose versioned XLSX under each form or enter the same typed fields manually in React. Imported data stay pending until validated and approved. A saved report can be reopened, but restatement creates a new immutable run; historical outputs remain reproducible.

## 3. Canonical models and immutability

```text
EconomicCase(id, organization, entity, scope, businessModelVersion, accountingBasis,
             fiscalCalendarVersion, reportingCurrency, goal, horizon, policyVersion)
SourceRevision(id, formCode, formVersion, sourceHash, uploader, approvalStatus, approvedBy, approvedAt)
FactAssertion(id, sourceRevision, sourceCellOrRow, metricCode, metricVersion, dimensions,
              amount, currency, originalScale, unit, timeKind, period, eventDate,
              actualVsForecast, valueState, methodRef, sourceSemantics)
PopulationDeclaration(id, factSetRevision, metricCode, dimensions, period, expectedGrain,
                      recordCount, totalControl, sourceCoverage, completenessDecision, reviewer)
FormulaMethod(code, version, formula, requiredInputs, outputUnit, periodKind, dimensionRules,
              roundingPolicy, accountingPolicy, applicabilityPolicy, qualification)
AnalysisRun(id, factSetRevision, formulaRegistryVersion, ruleSetVersion, configHash, inputHash,
            analysisDate, scenarioVersion, runStatus, evidenceSnapshotHash)
Finding(id, type, ruleVersion, measuredDelta, evidenceIds, competingHypotheses, limitations,
        riskStatus, recommendedInput, proposedActionIds)
ActionCandidate(id, owner, constraints, oneTimeCost, recurringCost, leadTime,
                scenarioAssumptions, verificationMetric, approvalStatus)
ScenarioResult(id, baselineRun, driverChangeSet, projectionVersion, currencyBasis,
               incrementalCashFlows, riskSensitivities, comparisonStatus)
OutcomeReview(id, approvedActionId, baselineRun, actualRun, comparableDelta, reviewer)
```

Required proof boundary: `SourceRevision -> FactAssertion -> PopulationDeclaration -> FactSetRevision -> FormulaMethod -> AnalysisRun -> Finding -> ScenarioResult`. Every `ACTUAL` output must traverse that path and satisfy tenant/zone, period/field and action permissions. No user-supplied `APPROVED` flag is authoritative.

### Data-quality state machine

`MISSING`, `EXPLICIT_ZERO`, `PRESENT`, `ESTIMATED`, `NOT_APPLICABLE`, `PENDING_APPROVAL`, `INVALID`, `CONFLICT`, `STALE` must be distinct. Do not calculate a complete aggregated metric merely because some rows passed `APPROVED`; require an explicit completeness/control-total decision for expected population, or surface `INCOMPLETE`. Unknown population coverage is a quality warning/blocker, never silent success.

Dimension comparability includes not just organization, businessScope, period, currency and metric version but also consolidation perimeter, VAT/gross-net policy, accounting basis, cost capitalization method, unit conversion, stock-vs-flow semantics, seasonality/calendar and source restatements. Currency conversion needs an approved versioned FX rate series, date and conversion purpose.

## 4. Financial analysis tracks

### FIN-F1: accounting integrity and liquidity
- F01 assets = liabilities + equity for each closing date and consolidation scope.
- F03 opening cash + CFO + CFI + CFF + permitted FX adjustment = closing cash; reconcile to F01 cash and cash-equivalent definitions.
- `net income != operating cash flow`; accrual-to-cash bridge: noncash items, receivables, inventory, payables, other working-capital items and relevant classification adjustments.
- 13-week **dated** cash forecast with separately tracked restricted and unrestricted liquidity, overdue payments, tax dates and debt maturities. Reconcile successive weeks, show minimum cash and earliest negative cash date.
- Debt schedule: opening debt + drawdowns - principal repayments +/- other reconciled changes = closing debt. Classify interest and principal correctly.
- Current/quick/cash ratios only when both current assets/liabilities and classification rules are available; zero/negative denominator requires explicit qualified policy.
- DSO/DOH/DPO/CCC only with appropriate period sales/COGS/purchases, opening/closing (or documented) average stocks and defensible alignment. `CCC = DOH + DSO - DPO`.

### FIN-F2: profitability and financial performance
- Monthly, quarterly and 3–5-year historical trends: growth, YoY, QoQ, TTM, common-size analysis, margins, ROA/ROE and turnover with period-aligned flow numerators and average stock denominators.
- Gross profit = net revenue - COGS; gross margin = gross profit / net revenue (if positive denominator). Never aggregate margin percentages by simple average; recompute ratio from aggregate components.
- Distinguish `revenue - return - discount` and COGS policy to avoid double adjustments.
- Evidence-driven bridge for margin change: price, volume, mix, input price, consumption, scrap, freight, FX and accounting reclassification. A decomposition must reconcile exactly to total delta and state allocation convention; unexplained residual stays visible.
- Distinguish operating performance, financing cost, tax and non-recurring effects. EBITDA and adjusted EBITDA require an explicit approved definition rather than interchangeable labels.

### FIN-F3: operational/sector drivers
- Trade: SKU/channel contribution after returns, discounts, delivery, marketplace commissions and cost of inventory; stock ageing/turnover, markdown and supplier lead times.
- Services: billable utilization, paid hours, revenue/hour, job contribution, cancellations and collection timing.
- Manufacturing: BOM and cost version, output/yield, WIP rollforward, standard-vs-actual price/usage variance, overhead absorption, capacity and rework. Avoid double-counted inter-step transfers.
- Construction: BoQ measurement times approved rate; project-stage earned value, cash receipts vs certified work, variations/retention, cost-to-complete and scenario EAC.
- Other sectors: introduce specific methods only after an applicability matrix and independent golden cases. Sector codes themselves grant no financial qualification.

### FIN-F4: investment, credit and strategies
- DCF NPV and sensitivity with FCFF/WACC or FCFE/cost of equity matching; same nominal/real, before/after-tax and currency bases.
- Irregularly dated cashflows use date-aware discounting; do not label approximate floating-point calculations as fully precise financial obligations.
- DSCR requires documented lender-specific CFADS and debt-service definitions, dates and covenant test. Never assert loan approval.
- Compare `DO_NOTHING`, `BASE`, `DOWNSIDE`, `STRESS` and `ACTION` within the same horizon and scope. Forecast assumptions and scenario probabilities, if any, must be sourced or user-entered; do not invent probability distributions.

## 5. Evidence-based five-question mechanism

| Question | Output contract | Minimum evidence | Allowed fallback |
| --- | --- | --- | --- |
| WHAT | signed/absolute delta, reference window, source IDs, formula | approved complete compatible facts | `NOT_COMPUTABLE` + required facts |
| WHY | factor bridge or enumerated competing causal hypotheses | driver-level observations, matched periods, reconciliation residual | `HYPOTHESIS_ONLY`, testable Next Best Question |
| SERIOUSNESS | quantified exposure + verified threshold/covenant/deadline; risk version | contract, liquidity shortfall, baseline volatility or approved sector comparison | `NOT_ASSESSED`, not invented high/medium/low |
| ACTION | feasible ranked alternatives and named human reviewer | operating constraints, implementation cost, owner and timetable | `REVIEW_REQUIRED` only |
| EFFECT | incremental cash/NPV or other specified KPI across scenarios, sensitivity, monitoring metric | explicit changed drivers, constraints, time series, cost and baseline | `SIMULATED_ONLY` arithmetic, not predicted savings |

The word `caused` is prohibited for a co-movement alert. An observational factor bridge is an accounting decomposition, not necessarily a causal identification. Upgrading a hypothesis requires designed verification (e.g., invoice-level audit, segment comparison, controlled rollout) and reviewer decision.

### Next Best Question

Select a question with `applicable=true`, `authorized=true`, and `unlocks` an actual incomplete proof path. Order first by: critical blocked safety/cash deadlines, then by whether it unlocks a verified formula, then evidence reuse, user burden and estimated time. Until expected information gain is empirically calibrated, keep ordering explainable and avoid a fabricated 0–100 relevance score.

## 6. Independent synthetic goldens (illustrative, NOT qualified production data)

1. **Margin decline despite sales growth:** prior revenue=90,000 and COGS=54,000 => 40%; current revenue=100,000 and COGS=65,000 => 35%. Revenue rose 11.111...%, while gross margin fell **5 percentage points**. This proves the observation only, not whether price, mix or raw materials caused it.
2. **P&L vs cash:** revenues=100, expenses=80 (accrual profit=20); collections=70, operating payments=75 (net operating movement=-5 under deliberately simplified classification). Engine must never substitute 20 for -5 or explain the gap without additional debtor/noncash evidence.
3. **13-week cash arithmetic:** opening=100, total inflows=130, total outflows=150, closing=80. Restricted cash=20 => unrestricted=60. Weekly sequence and due-date controls remain mandatory; a year-end cash total cannot replace minimum weekly liquidity.
4. **CCC:** 90-day window: average inventory 30,000; COGS 180,000 => DOH 15 days; average AR 40,000; eligible credit sales 240,000 => DSO 15; average trade AP 20,000; purchases 200,000 => DPO 9; CCC=21 days. Definitions and applicable denominators must remain visible.
5. **Simple annual NPV:** t0=-100, t1=60, t2=60, rate=10%; NPV ≈ 4.1322314, *without* implementation or tax costs outside the supplied cash flows. Any change to rate basis, timing or scenario invalidates comparability.
6. **Missingness:** incomplete source population, unknown period currency, unapproved source, changed fiscal calendar, mixed ACTUAL/FORECAST, zero denominator and revised source must all yield explicit non-success statuses, never fabricated KPI.
7. **Golden cost benefit:** operational benefit 30, implementation outflow 40 at same date cannot be advertised as +30 net benefit. The engine must evaluate incremental cash, costs, timing and discounts within one scenario.
8. **Public preview:** external report/share of a result with field-level restrictions must not leak underlying source values, IDs, bank entries, unpublished records or draft assumptions.

All goldens require independent control calculation and negative variants (null/missing, zero denominator, duplicate, reversed sign, period mismatch, mixed tenant/currency, restatement). Financial example values here are synthetic and must never appear as actual customer data.

## 7. Materiality, risk and uncertainty

Risk output is a typed structure, not an arbitrary universal score:
- `exposure`: known KZT amount, cash balance shortfall, debt covenant gap, loss rate or profit delta.
- `horizon`: date and known hard deadline, not just a vague label.
- `threshold`: direct covenant value, board-approved policy or reviewed sector-specific benchmark; identify source and version.
- `uncertainty`: approved, estimated, incomplete, not-computable, hypothesis-only; note applicable stress assumptions.
- `priority`: initially deterministic rule hierarchy (legally binding/short-term cash breach > reconciled material loss > unexplained anomaly > optimization opportunity), with clear human override history.
- `seriousness`: assign a level only if a threshold is supplied and method is qualified; otherwise `NOT_ASSESSED`.

Do not use a mathematically seductive universal health score before validation on representative businesses, model segments and out-of-sample outcomes.

## 8. Method qualification and approval workflow

Every formula/rule has an explicit lifecycle:
`SPEC_ONLY -> CODED_SYNTHETIC -> NUMERIC_GOLDEN_VERIFIED -> INTEGRATION_VERIFIED -> METHOD_S2_APPROVED -> TRUSTED_ACTUAL_E2E_VERIFIED -> PRODUCTION_ELIGIBLE`.

Approval requires formula owner, accounting/regulatory policy basis, applicable business models, fiscal treatment, source mapping, unit and precision policy, independent golden values, minimum evidence, falsification/negative tests, version/hash and signed human approval record. Method additions require a new version; do not mutate historical run semantics.

## 9. Test and release matrix

**Unit/property/metamorphic:** invariant preservation, split-and-regroup of flows, closing stock not summed, recomputed weighted ratios, zero/missing/estimated distinct, monotonic and sign conventions, no duplicate returns or parent/detail totals, BigDecimal precision, date/FX/calendar guards, no historical revision drift.

**Independent controls:** a calculation workbook or separately coded reference oracle provides exact golden results; tests must not simply call the same production formula to calculate expected outputs.

**PostgreSQL/Testcontainers:** approved→published→calculate→snapshot, tenant and role/zone denial, source conflict/duplication, altered revision, race on publish, stock/flow mismatch, unchanged fingerprint/idempotency, immutable snapshots and source-manifest replay.

**React/Playwright:** user question → next best versioned XLSX/React form → import preview → validation failure → approval → report with provenance → next question → human-reviewed action → what-if → save/reopen. Desktop MacBook UAT remains a separate human acceptance.

**CI same SHA:** Node smoke + manifest/coverage, Java/JUnit, real PostgreSQL Testcontainers, browser E2E Linux, Chromium/WebKit macOS and a fail-closed acceptance gate. Record workflow run URL, SHA, job conclusions and artifacts. `CI GREEN != METHOD S2 APPROVED != PRODUCTION READY`.

## 10. Work breakdown, in strict order

| Milestone | Deliverable | Acceptance gate |
| --- | --- | --- |
| R4.0 | Repair evidence-gate failures on one SHA | green Node + Java/PostgreSQL + Linux/desktop browser + acceptance-gate; link run |
| R4.1 | PopulationCompleteness + authorized Source-to-Fact adapter | partial approved subsets rejected; disjoint approved/unapproved periods tested; source lineage to exact row |
| R4.2 | Qualified F01/F02/F03 + DF-CSH-01/02 end-to-end | 3-statement bridge, history, cash gaps, Golden and discrepancy tests using PostgreSQL ACTUAL only when approved |
| R4.3 | Unified Compass: financial question → minimal proof graph → next best input | no redundant form asks, correct industry route, missingness and method qualification shown in React |
| R4.4 | Proof-oriented finding/risk/playbook model | WHAT/WHY/SERIOUSNESS/ACTION/EFFECT labelled accurately; severity never invented; human approval and revisions |
| R4.5 | Incremental scenario simulation and 13-week cash forecasting | do-nothing vs action incl. costs/timing; cash minimum; uncertainty; matched source revisions |
| R4.6 | First methodology-approved sector packages | retail/services/manufacturing golden + UAT; then construction/logistics and remaining justified sectors |
| R4.7 | Extend numerical coverage of registered forms based on actual applicability | 144 backlog classified as numeric candidate vs supporting evidence vs not-applicable; do not force KPI onto documents |
| R4.8 | Independent finance/security S2 + production qualification | signed evidence, threshold approval, scoped Keycloak/ABAC E2E and human MacBook UAT |

**First profitable vertical:** an S2 trading company with one full month of sales and expenses, F01/F02/F03 where available, trade receivables, purchases and inventory; walk from declining margin + tightening cash through source verification, driver audit and an explicit liquidity improvement scenario. Then repeat the same path for S1 services and manufacturing.

## 11. External methodological references

- IFRS IAS 7: operating/investing/financing cash flows, cash reconciliation: https://www.ifrs.org/issued-standards/list-of-standards/ias-7-statement-of-cash-flows.html
- IFRS 15: revenue recognized on satisfaction of performance obligations, not directly identified with cash receipts: https://www.ifrs.org/issued-standards/list-of-standards/ifrs-15-revenue-from-contracts-with-customers/
- IFRS 18: reporting presentation changes effective for annual periods beginning on or after 2027-01-01; new methods must version their presentation basis: https://www.ifrs.org/issued-standards/list-of-standards/ifrs-18-presentation-and-disclosure-in-financial-statements/
- CFA Institute financial analysis techniques (2026): https://www.cfainstitute.org/insights/professional-learning/refresher-readings/2026/financial-analysis-techniques

**Do not merge this document or promote any method solely because it exists in GitHub.** The release gate remains explicit.
