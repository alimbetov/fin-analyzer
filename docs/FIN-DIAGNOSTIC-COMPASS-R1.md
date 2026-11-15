# FIN Diagnostic Compass R1 — the next best evidence, not another form list

**Status:** `ENGINEERING_PILOT` for pure Java form guidance, **not** a
trusted organization-facing service, and **not** a licensed/qualified
financial opinion. Code runs in `backend/src/main/java/.../compass`.
No REST, React widget, customer data pipeline or Keycloak integration
is claimed. `ACTUAL` economic recommendations remain `HOLD`.

## 1. Product mission: from a report catalog to an evidence compass

The user should **not browse 166 forms**. They identify a business question
(or an existing verified diagnostic finding), and FIN answers:

1. What can already be evaluated from existing approved data?
2. What is missing or stale, and which *minimum* form needs completion?
3. Why is that form relevant, what question can it help answer,
   and which calculation or hypothesis is blocked without it?
4. Is the form actually available in the software, or only designed?
5. What additional evidence would distinguish observation from causality?
6. After the period closes, did the corrective plan improve the KPI?

**Never confuse a completed form, a mathematically computable KPI, and a
methodologically qualified business recommendation.** These are separate
readiness dimensions.

## 2. R1 implementation

Two immutable, bundled policy tables:

- `compass-trait-policy-v1.tsv` — 40 feature gates for conditional forms.
  Examples: a project without debt does not require P04/C05; an initial
  construction estimate does not require completed-work certificates;
  manufacturing without owned equipment does not require SF-C03.
- `compass-evidence-policy-v1.tsv` — 15 explicit links from the three
  **existing** diagnostic rule codes to forms worth checking.
  Example: `REVENUE_CASH_AR_DIVERGENCE` -> `C01`, `DF-CSH-04`,
  `DF-CSH-01`, `DF-CSH-03`, and (only in G) `SF-G02`.
  These are **investigations**, not asserted economic causes.

`CompassCatalog` loads the existing 166-form registry, ten scenario
definitions and 21 section/64 business-model registry, validates all
codes/references/statuses and rejects unknown rules, traits or forms.
`DiagnosticCompass` then makes a deterministic suggestion list.

The output includes `Readiness`, prioritized `Recommendation` with
reason key/unlocks code, existing source state, catalog qualification
(`DESIGN_ONLY` vs `PILOT_UNQUALIFIED`), contextual questions, reused
approved form codes and blocking reasons. Ordering is stable, and every
output collection is immutable. The rank measures **workflow priority**,
not projected monetary benefit or evidence confidence.

Input source states are `APPROVED`, `PENDING_APPROVAL`, `INVALID`,
`STALE`, `NOT_APPLICABLE`. An approved form needs a source revision.
Missing approved data generates `PROVIDE_FORM`, existing draft gets
`APPROVE_EXISTING`, invalid gets `CORRECT_SOURCE` and stale gets
`REFRESH_SOURCE`. Known approved sources are reused rather than
re-uploaded or counted twice.

`Depth.ESSENTIAL` recommends core minimum plus triggered evidence and
confirmed conditional traits. `Depth.DEEP` also offers additional
relevant forms and asks for unresolved business traits, but never silently
asserts them. The future UI must phrase questions using localized
templates, not raw enum codes.

### Deliberate corrections to the prior scenario requirements

- `SC-LLC-FINANCE`: F06 moved to optional/evidence upgrade;
  F01/F02/F03 remain core.
- `SC-HISTORY-3Y5Y`: **3–5 common consecutive annual fiscal years OR
  36+ common consecutive monthly periods**, rather than requiring all
  businesses to have 36 monthly files. No monthly seasonality claims
  on annual-only sources.
- `SC-MFG-ECONOMY`: plant equipment utilization and BOM are
  conditional on actual operating mode.
- `SC-CONSTRUCTION-ESTIMATE`: certificate SF-F03 is conditional on
  completed work.
- `SC-INVESTMENT-APPRAISAL`: funding schedule P04 is conditional on debt.

The R1 historical readiness gate checks common **period coverage only**.
It does NOT yet certify fiscal calendars, restatements, currencies,
accounting methods, consolidated scope, materiality, detailed metric
grain or the mathematical feasibility of all named KPI. These must
be checked by `HistoricalFactResolver` and `MetricReadiness` before
any real diagnostic verdict. For other scenarios, a source marked
APPROVED is not proof of quantitative adequacy.

### Strict trust and access boundaries

- The current pure engine accepts a caller-supplied evidence manifest
  to test recommendation decisions. **Never pass client-supplied
  APPROVED states or finding codes into a live API.**
- In R2, an authenticated server-side application service must resolve
  source ownership, `organizationId`, entity/scope, field and period
  rights through Keycloak/ABAC. Approved evidence must be fetched from
  persisted authoritative revisions; diagnostic findings from trusted
  signed `fin_diag.assessment_run` snapshots.
- Diagnostic findings from old or STALE snapshots cannot be treated
  as current without explicit restatement policy.
- The selection engine never imports XLSX, certifies form methods,
  calculates accounting profit, makes causal assertions, grants credit,
  or automatically changes budgets, prices and legal contracts.
- In the future, a metric must point back to formula, source period,
  form version and exact field, with explicit `MISSING_EVIDENCE` and
  `NOT_COMPUTABLE` where appropriate.

## 3. The 12 highest-value product features

| Priority | Feature | Concrete user-visible outcome | Proof gate |
| --- | --- | --- | --- |
| P0 | **Next Best Form** | One clear next step instead of 166 choices | No irrelevant form in top 5; no requirement inflation |
| P0 | **Evidence Reuse** | Existing approved rows reused across reports | No duplicate money or repeated re-entry |
| P0 | **Why this form?** | Each request names blocked KPI and needed source | Form → metric → finding provenance |
| P0 | **Missingness Radar** | Visible distinction between missing, stale, invalid and not applicable | No silent zero-filling or false readiness |
| P0 | **Historical Time Machine** | Annual 3–5-year report, current-vs-restated perspective | Reproduce before and after corrections |
| P0 | **Business Health Compass** | Observations vs hypotheses vs missing evidence | Human review and exact calculation lineage |
| P1 | **13-week Cash Radar** | Detect forecast liquidity gaps and required decision points | Bank/current balance roll-forward and approval |
| P1 | **Profit Leak Map** | Decompose loss of margin by SKU, price, volume and cost | Verified reconciliation of variance decomposition |
| P1 | **Scenario Lab** | Compare cash, margins and obligations under actions | Explicit assumptions; feasible constraints |
| P1 | **Action & Outcome Loop** | Action owner, due date, KPI target, measured after-effect | Immutable baseline and outcome snapshot |
| P2 | **Sector Cockpit** | Show models for the actual operating activities only | Sector and subcode applicability, independent review |
| P2 | **Advisor/Lender Data Room** | Scoped period/field-limited grants, audit & documents | OIDC/ABAC tests, zero cross-tenant access |

Machine-learning forecasts, natural-language narratives and optimizer
results should be additions **after** the deterministic workflow passes
historical and sector evidence gates. A fluent AI explanation is not
a source of accounting truth.

## 4. User journeys and minimal evidence

| User | First intent | Minimal route | Additional evidence when indicated |
| --- | --- | --- | --- |
| IP owner | “Where is the cash going?” | `SC-IP-HEALTH` → DF-CTX, sales, cash, costs | C01/DF-CSH-04 for collections, C02 for payables |
| CEO/CFO | “Why did profitability fall?” | `SC-LLC-FINANCE` F01/F02/F03 | Unit costs, sales mix, price changes, C08 |
| Analyst | “What changed over 3–5 years?” | `SC-HISTORY-3Y5Y` | Restatements, FX, consolidated perimeter |
| Finance controller | “Which budget line is deviating?” | `SC-BUDGET-FPA` | Cost centres, commitments, 13-week cash |
| Plant manager | “Why is unit cost above standard?” | `SC-MFG-ECONOMY` | WIP, BOM, defects, routing, overhead |
| Estimator | “What will completion cost?” | `SC-CONSTRUCTION-ESTIMATE` | Certified work only if already underway |
| Investor | “Which alternative creates value?” | `SC-INVESTMENT-APPRAISAL` | Debt schedule only for leveraged project |
| Industry analyst | “Which sector KPI applies?” | `SC-SECTOR-ROUTED` | Only selected A–U section and business models |

## 5. Engineering path to an actual useful product

**COMPASS-R1 (current):** bundled scenario and diagnostic evidence
policies, deterministic recommendations, rank, minimum inputs, annual/
monthly history gate, 21-sector filtering and unit tests. No public UI.

**COMPASS-R2:** persist `BusinessProfile` and `FormSubmission`
(including `businessModel`, source revision, schema and period),
server-owned `FormRegistry`, `FormApplicability`, authenticated
`GET /scenarios`, `GET /organizations/{id}/compass` and
`POST /form-submissions` behind Keycloak/ABAC; no untrusted state.
Use transactional approval with immutable revisions and audit trail.

**COMPASS-R3:** first **fully realized** journey: owner chooses
`SC-IP-HEALTH`, downloads or completes validated Excel/React forms,
server verifies and publishes economic facts, calculates 12-month
finance indicators, runs qualified diagnostic rules and persists
`BusinessHealthSnapshot` with evidence-linked next-form cards.

**COMPASS-R4:** 3–5-year/history comparisons + 13-week cash flow +
`CorrectiveActionPlan` with target and actual follow-up. Only then
expand to manufacturing, construction and investment workspaces.

### Mandatory acceptance set

- All 166 registered form codes remain intact; 10 scenarios/21
  sections/64 business models and rule links are verified.
- Empty IP -> 6 core forms, not a 166-form checklist.
- Approved sources disappear from action list, but remain attributable.
- A draft creates `APPROVE_EXISTING`, not `PROVIDE_FORM`.
- 3 consecutive **annual** years accepted; two years or different
  year sets blocked; 36 months accepted; a missing month blocked.
- Unlevered investment does not require P04; preconstruction does not
  require SF-F03; manufacturing without equipment does not request SF-C03.
- Two selected sectors show only their own forms, never all 21.
- A cash/AR finding directs to dated receivables and actual receipts;
  no causal attribution is asserted.
- Unknown trait/form/model, conflicting traits and stale sources fail
  closed; recommendation results are immutable and stable.
- All diagnostics on `ACTUAL` and autonomous decisions remain disabled
  until financial and information-security qualification.

## 6. Product scorecard before release

Track `firstUsefulDiagnosisCompletion`, median `formCountToInsight`,
share of approved records reused, percentage of recommendations with
verified evidence, false positive/negative rate for findings, missing
periods and stale lineage, decision adoption with monitored outcomes,
and time required for first Business Health report.

Do **not** confuse 174 passing existing backend tests, 166 documented
forms, 52 `SPEC_ONLY` cases, or 6 156 synthetic observations with a
customer-ready diagnostic platform. These are different types of proof.
