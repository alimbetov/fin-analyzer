# FIN DATA-CALC-R2 — Cross-Form Calculation-First Roadmap

**Дата:** 2026-11-20  
**Ветка:** `feat/fin-form-library-standalone` — единственный scope изменений.  
**Статус:** REPLANNED UAT ROADMAP / NOT IMPLEMENTATION EVIDENCE.  
**Основание:** [FIN DATA-CALC-R1](./FIN-DATA-CALC-R1-CALCULATION-FIRST-UAT-TZ.md) (327 строк требований), [архитектурный аудит R2](./audits/FIN-DATA-CALC-R2-CROSS-FORM-ARCHITECTURE-AUDIT-2026-11-20.md), [6 приложений](./FIN-CALC-APPS-R0-SIX-APPLICATION-CONTRACTS-AND-ROADMAP.md), [10 финансовых решений](./FIN-CALC154-P0-P1-EDITORIAL-POLICIES-AND-IMPLEMENTATION-V1.md).  
**Ключевой принцип:** first executable user-entered value, then reconciliation, then qualified method variants, then scale to 154; **не** methodology catalog first.

> **Implementation update, 2026-11-20: A05-R1.0 + MG-R0.2.** A05 typed C05 contract, rate/calendar/fee and one drawdown revisions are written atomically through existing `fin_intake`: PostgreSQL 16 [10/10 PASS](https://github.com/alimbetov/fin-analyzer/actions/runs/44687823013); real React/Chromium [1/1 PASS](https://github.com/alimbetov/fin-analyzer/actions/runs/44687393695). [A05 R1.0 QA](./qa/FIN-CALC-A05-R10-LOAN-CONTRACT-INPUT-ACCEPTANCE.md). MG-R0.2 explicit editorial indicator binding [5/5 PG16 PASS](https://github.com/alimbetov/fin-analyzer/actions/runs/44687267435); [MG-R0.2 design](./FIN-MG-R02-EXPLICIT-INDICATOR-BINDINGS.md). `NET_PROFIT` correctly remains derived-method-unresolved, not a physical F02 input. **Next: A05-R1.1 dated schedules** and MG source availability; no automatic 154-form promotion or new standalone writer.


> **MG-R0.1 Implementation — 2026-11-20:** теперь реализован read-only Metric Graph Resolver (fin_lib metric → exact formula revision → existing Calc4DTypedDagCompiler → versioned physical fields). PostgreSQL 16 gate 5/5 PASS на [run 44682901403](https://github.com/alimbetov/fin-analyzer/actions/runs/44682901403), code SHA d39af2e4990e9f90e666a4486f3bedf7b89dbb86. Документация: [FIN-MG-R01-METRIC-GRAPH-RUNTIME-FOUNDATION.md](./FIN-MG-R01-METRIC-GRAPH-RUNTIME-FOUNDATION.md). Это НЕ graph execution: editorial goal indicator ↔ formula mapping и project-specific input availability ещё не утверждены. Следующий предметный этап — A05-R1.0 typed C05 loan terms/fees, с параллельной разработкой MG-R0.2 для явных связей бизнес-показателей с исполнимыми метриками.


> **Implementation evidence update — 2026-11-20 / DC-R2.5:** shared `UatCalculationPanel` is now mounted in the real React editor for F02/C01/C02. Verified GitHub Actions [R2.5 Chromium + PostgreSQL 3/3 PASS](https://github.com/alimbetov/fin-analyzer/actions/runs/44680085762) on code SHA `f5fee543ac8a865ff81e013ad5824e265024a1f6`. Exact source, dirty-state guard and historical comparison were exercised without mocked calculations. Detailed bounded acceptance: [FIN-DATA-CALC-R25-UNIFIED-BROWSER-ACCEPTANCE.md](./qa/FIN-DATA-CALC-R25-UNIFIED-BROWSER-ACCEPTANCE.md). This closes the **R2.5 browser UAT gate only**, not a persistent qualified calculation result, A05 or all-154 coverage. Next engineering priority: MG-R0.1 read-only metric/method/dependency resolver, then A05-R1.0 typed C05 loan terms/fees.


**CALC-A05 dependency / implementation specification:** [FIN CALC-A05-R1 — Loan Contract & Payment Schedule Studio TZ](./FIN-CALC-A05-R1-LOAN-CONTRACT-PAYMENT-SCHEDULE-STUDIO-TZ.md). This is a `SPEC_PROPOSED_FOR_APPROVAL`, not an implemented loan engine. The detailed tasks A05-R1.0…R1.5 are subordinate to DC-R2.8 (loan schedules) and DC-R2.9 (cash bridges/covenants); A05-R1.4 requires A01 actual-payment allocation. Begin only after `DC-R2.4 + DC-R2.5` real PG16 and Chromium E2E gates. IFRS9 EIR, contractual cash and KZ_GESV are independent calculation types. Never add a 167th top-level form, a new financial engine or a third independent source writer.

## 1. Product outcome and non-negotiable limits

Пользователь вводит/импортирует данные в **существующую форму**, сохраняет immutable ревизию, выбирает параметризованную методику, запускает UAT, получает расчёт с кодом метрики, периодом, валютой, единицей, статусом, точными исходными ревизиями, формулой, параметрами и межформенным контролем, затем изменяет данные и сравнивает 2 неизменяемых результата.

Не создавать 167-ю форму или второй FIN calculation engine ради A01–A06; это typed дочерние источники/проекции и вычислительные вкладки для существующих 166 форм. **Ни source metadata, ни наличие расчётной формулы, ни GitHub CI по одному Java-kernel не равны работающему intake E2E.** Отдельные рубежи: `REGISTERED`, `MATH_TESTED`, `UAT_EXECUTABLE`, `INTAKE_E2E_PASS`, `CROSS_FORM_RECONCILED`, `FINANCIALLY_S2_QUALIFIED`.

Не изменять historical Liquibase checksums. Любые новые таблицы только additive после `055`, без автоматической финансовой S2 / production admission.

## 2. Architecture target: one calculation domain, two controlled reporting bases

```text
React: 166 C/F/P/DF/SF/D/T identity + standard editor + selected scenario
      | input, import, save, exact revision readback
      v
FormDraftService (fin_intake) + ReportGridService (legacy fin_lib)
      | READ adapters; no third writer or implicit cross-writer merge
      v
FIN_INTAKE_UAT immutable input snapshot
  [org, entity, project, scenario, businessScope, basis, time, currency]
  [exact source revs, SHA, stable keys, completeness, typed MONEY/DECIMAL/DATE]
      |
      v
Typed metric binding + dependency DAG + policy resolution
  [metric version, grain, stock/flow, dimension, unit, sign, FX, rounding]
  [CALC-A01...A06 contract rev and selected method/param SHA]
      |
      v
UAT immutable Calculation Plan [pinned source + method + policy + algorithm]
      |
      +-- PostgreSQL16: prepared bounded query/SUM/latest/joins/window/aggregation
      +-- Java21: BigDecimal, EIR, cash-flow waterfall, scenario, invariants
      +-- parity/checkpoints/golden examples (only where both routes exist)
      |
      v
UAT result [typed values/states + intermediate nodes + SHA + diagnostics]
      |                           |
      v                           v
React Calculate/Explain/Compare    Cross-form reconciliation graph
      |                           |
      +------ Financial/economic KPI view (derived only) ------+
```

Production `fin_fact` and `calc_plan` keep their separate admission gates. The current `Calc4DUatPlanStore` / C01C02 AS_OF executor require **synthetic TEST_QUALIFIED** FactSets. **Do not** bypass these checks. R2 will introduce/adapt a proven isolated `FIN_INTAKE_UAT` source/plan path with identical canonical semantics and shared math kernels; only after exact source SHA re-verification. Determine feasibility at DC-R2.2 before creating new migrations.

## 3. Typed financial contracts: common across all form calculations

| Contract | Required invariants | Failure / output |
|---|---|---|
| `InputObservation` | organizationId, projectId, formCode, formVersionId, sourceRevisionId, stable business/row key, metricCode, raw ValueState, effective/transaction date, reporting basis, currency and SHA | Missing vs zero vs N/A vs invalid distinct; reject duplicate business keys |
| `MetricDefinitionVersion` | metricCode, unit (MONEY/PERCENT/RATIO/DAYS/COUNT), stock/flow kind, time semantics (AS_OF/INTERVAL/YTD), sign convention, grain/dimensions and currency policy | never SUM different asOf stocks, FX without policy or mixing row grains |
| `MethodologyBinding` | family/variant/contract rev, parameter schema+SHA, effective policy rev, evidence state, golden vector references, math artifact SHA | incompatible variant fails closed |
| `PolicyResolution` | scope chain and exact revision: SYSTEM → ORGANIZATION → PROJECT → SCENARIO; class/contract/asset/inventory item policies as explicit domain-specific dimensions; strict constraints checked before overrides | no silent fallback for unspecified thresholds or business judgment |
| `FxAndRoundingPolicy` | source/date/quote/functional/reporting currency, decimal precision and rounding stage, cash/non-cash FX classification | value cannot be compared or aggregated until converted under pinned policy |
| `UatCalcPlan` | explicit source hashes, algorithm SHA, policy SHA, scenario and exact input revision pins, idempotency fingerprint and revocation/stale checks | never `latest` resolution during execution |
| `CalcResultNode` | raw unrounded BigDecimal, unit, presentation rounded value, COMPLETE/PARTIAL/UNDEFINED/MISSING/INVALID, lineage and diagnostics | do not fabricate zero or unrelated intermediate results |
| `ReconciliationContract` | source/target metric codes, compatible time basis/dimensions, transform expression and tolerance source revision | PASS/DIFFERENCE/NOT_COMPARABLE/MISSING_SOURCE as distinct statuses |

`reportingBasis` is pinned for both IFRS and MANAGEMENT. Management adjustments are versioned transformations, not silent edits to primary source. `FinIntakeUatSourceAdapter` must not assume all `ReportGridService` data are already in `fin_intake`. Decide canonical ownership and read-only bridge per source kind.

## 4. Explicit financial dependency DAG

```text
DF-CSH-04/05 + DF-CSH-02 + amendments
  -> A01 obligations/payments -> C01/C02 overdue, aging, gross
      -> A02 ECL -> C01 net / F01 allowance
      -> P06 DSO/DPO ---------+
                             |
DF-STK-02/03 -> A03 FIFO/WAC/NRV -> C03 inventory / F02 COGS -> P06 DIO
                             |                               |
DF-SAL-01/03 -> recognized revenue policy -> C07/F02 ----------+
                             |                                |
DF-AST-01 -> A04 depreciation -> C04/F01/F02 -> P05 CAPEX ----+
                                                              |
C05 + loan cash events -> A05 cash schedule/EIR -> P04 -> P09 DSCR
                                |                  |
                                +--> F03/P03 cash  |
                                +--> F01/F02 EIR --+
                                                    |
F01/F02/F03 + P01/P02/P03/P05/P06 + policy/FX ------+
    -> forecast scenario cashflow revision -> A06 FCFF/WACC or FCFE/Ke
    -> P07 EV/Equity Value; P08 sensitivity; P09 covenant only from A05
```

The DAG depicts **method/data dependencies**, not a claim that every edge or metric is currently executable.

### Required cross-form control set

| Check ID | Source / output | Meaning |
|---|---|---|
| REC-01 | F01 | assets − liabilities − equity = 0 under pinned basis/time/currency |
| REC-02 | F03 / C06 | opening cash + CFO + CFI + CFF + correctly classified FX/noncash movements = closing cash; noncash not cash flow |
| REC-03 | C01 + A01 + F03/P06 | received payments reconcile to settled outstanding; overdue ⊆ unpaid gross; working-capital bridge |
| REC-04 | C02 + A01 + F03/P06 | AP settlements/obligation movement vs cash and other adjustments |
| REC-05 | C03/A03 + F02 + P06 | opening inventory + receipts − consumption and adjustments = closing; COGS consistency |
| REC-06 | C04/A04 + F01/F02/P05 | component depreciation, capital changes and impairment roll-forward |
| REC-07 | C07/DF-SAL + F02/F03 | recognized revenue vs orders/returns vs cash receipts, with accrual bridge |
| REC-08 | C05/A05 + P04/P09 | cash interest + principal cash due, covenant CFADS by contract, EIR expense separately |
| REC-09 | P01/P02/P03/P05/P06 | forecast balance, cash, working-capital and financing inputs agree by scenario |
| REC-10 | P07/P08 | FCFF–WACC–EV or FCFE–Ke–Equity, terminal rate constraints, scenario/source SHA |
| REC-11 | A06/P07 + C05/P04 | EV-to-Equity bridge consistent for value comparisons; net debt/cash and minorities sourced |
| REC-12 | ALL | error budgets/tolerance, FX, evidence, period/aggregation, reporting basis and missingness |

**Financial/economic outputs** are **derived views**: CCC = DSO+DIO−DPO with matching days; leverage, liquidity, ECL coverage, margin, DSCR, DCF sensitivity, cash runway. Do not display EBITDA without a qualified depreciation/amortization adjustment source. Do not give a covenant PASS on an analytical DSCR.

## 5. Replanned stages (dependency-driven; crosswalk to approved DC-R1)

| New gate | Priority | Corresponding R1 | Deliverable | Strict GO criteria |
|---|---|---|---|---|
| DC-R2.0 — Compile/DB baseline | P0 | R1.0 | Resolve compiler blocker `79c6e851`, PG16 migration 001–055, React and backend smoke; evidence report | Exact HEAD, build+Liquibase GREEN, signed commands/results, NO skipped mandatory checks |
| DC-R2.1 — Canonical intake read | P0 | R1.1 | Both save paths inventoried; exact form/ROWSET/DF/ReportGrid immutable revision adapter | Save→restart→read exact pinned values/rows/SHA, business identity and no leakage |
| DC-R2.2 — Bounded intake-UAT bridge | P0 | R1.2 | Real user-entered input snapshot, typed lines, isolated plan, versioned method and source SHA | No synthetic fixture substitution, no `fin_fact` prod write, replay/stale/missing/FX negative PG tests |
| DC-R2.3 — F02 first result | P0 | R1.3 | Three numerical outputs `grossProfit/grossMargin/operatingResult` from saved F02 | Real Spring+PG16+React; two revisions; zero denominator UNDEFINED; output codes preserved |
| DC-R2.4 — C01/C02 snapshot | P0 | R1.4 | Gross-before-allowance and outstanding AS_OF; optional overdue ratios | ROWSET 2–3 rows and exact cutoff; no duplicate money/double payment; missing vs zero |
| DC-R2.5 — React Calculate/Explain/History | P0 | R1.5 | Reusable Calculate/Explain DTO; compare two revisions; typed failures | Playwright real backend/browser and both F02/C01/C02 accepted |
| DC-R2.6 — Typed dependency & policy foundation | P0 | new R2 gate | Versioned MetricDimensionPolicy + currency/period controls; `051/054/055` linked to UAT plan | Wrong IFRS/management basis, time, unit, policy, FX or variant fails closed; per-output explain trace |
| DC-R2.7 — A01 settlement events | P1 | R1.6 | Stable obligation ID, amendments, allocation, corrections atop DF-CSH | 2 partial payments, amended due date, refunds/duplicate/replay, exact aging and C01/C02 reconcile |
| DC-R2.8 — A05 debt / dual schedules | P1 | R1.7 | Contractual dated cash schedule + accounting EIR; C05/P04/P09 same source | Early repayment/fee/day-count example, manual+Java+SQL parity, no EIR cash mixing |
| DC-R2.9 — Cash bridges & covenant | P1 | expanded R1.7/1.8 | F03/P03 direct/indirect CFO, financing liability movement, contractual and analytical DSCR split | CFO direct=indirect with noncash/FX bridge; covenant PASS only when defined |
| DC-R2.10 — A03 inventory | P1 | R1.8 | FIFO/WAC/specific ID item class, NRV and C03→COGS→P06 DIO | Exact lot movements, returns, units, missing NRV and reconciliation golden E2E |
| DC-R2.11 — A04 assets | P1 | R1.8 | Component depreciation, revaluation/impairment events, C04→F01/F02/P05 | Component roll-forward vs asset total, disposal and partial-period cases |
| DC-R2.12 — C07/F02 revenue | P1 | R1.8 | Sales, returns, discounts, recognition adjustments | Recognized vs billed vs cash revenue reconciles, contract evidence when needed |
| DC-R2.13 — Unified forecasting & accounting | P1 | expanded R1.8 | F01/F02/F03, P01–P06 forecast identity/working-capital and financial policy | Forecast balance/cash/CCC scenarios reproducible, same period/basis/currency |
| DC-R2.14 — A06 DCF + scenarios | P1 | R1.8 | Dated FCFF/FCFE, WACC/Ke, terminal value, NPV/IRR, P07/P08 output | No mismatched FCFF/Ke or FCFE/WACC; EV equity bridge, two scenarios, IRR ambiguity handling |
| DC-R2.15 — A02 ECL | P1 | new R2 gate | Simplified provision matrix where eligible, segmented forward assumptions, PD/LGD/EAD later | Basket aging, 12m/lifetime eligibility, re-estimation, no auto writeoff, F01 reconciliation |
| DC-R2.16 — 154 outputs by evidence | P2 | R1.9 | Coverage register for every numeric form and separately each required output; 12 EVIDENCE docs remain supporting | Each output has method, policy, typed inputs, golden, PG16 test, browser E2E, business review state |

**Gate ordering detail:** R2.6 minimum compatibility safeguards (unit/currency/basis/period) begin in parallel with R2.1–R2.5; full hierarchical configurable policy engine follows F02/C01/C02 E2E, not before first number. A02 can start in parallel after A01/typed instrument classification, rather than delaying all P07 work. A03/A04/C07 may proceed as independent post-R2.6 workstreams once the baseline is stable. This is a dependency graph, not enforced serial calendar work.

## 6. What constitutes readiness for each output

`docs/FIN-DATA-CALC-R2-ALL154-FORM-READINESS.tsv` tracks all 154 catalog forms **without inventing readiness**. Separately `docs/FIN-DATA-CALC-R2-CROSS-FORM-OUTPUT-MATRIX.tsv` tracks domain-specific outputs and reconciliation edges; these are candidate specifications, not evidence that the outputs were executed.

Minimum stages in order for every output:
```text
REGISTERED
 -> INPUT_CONTRACT_PINNED
 -> METHODOLOGY_VARIANT_AND_POLICY_PINNED
 -> MATH_GOLDEN_GREEN
 -> SQL_JAVA_PARITY_GREEN (if dual strategy)
 -> INTAKE_UAT_E2E_GREEN
 -> CROSS_FORM_RECONCILED (when dependencies exist)
 -> FINANCIAL_S2_QUALIFIED (separate workflow)
```
Cannot skip evidence just by setting a database status. Store HEAD SHA, PG16 and browser run ID, test data revision hashes, expected/actual values, policy and method SHA; latest check state is not inherited across code commits.

## 7. Technical strategy and safe optimization

**Backend:** modular existing Spring Boot/Java21, PostgreSQL16 and JDBC. SQL should perform typed filtering, dedup keyed joins, interval grouping, aggregate SUM and window calculations; Java BigDecimal should handle financial policy/EIR/NPV/IRR/compound schedules, high-precision rounding rules and explainability, with parity where explicitly dual implemented. Never let client provide executable SQL or arbitrary expressions.

**Data normalization:** use typed relational child event rows for debt payments, loan schedules, inventory lots, assets and direct/indirect cash bridge. JSONB only for bounded/versioned parameter choices, evidence and metadata. Do not copy financial source totals into independent child-form inputs.

**Numerical fidelity:** no float/double. Raw full precision; explicit report rounding and FX conversion stage. Missing/invalid states must not become zero. Users may select only supported variants; registered `methodology_research_candidate` text is **never evaluated**.

**Trace:** each output includes `source revision IDs/hashes + method variant SHA + policy revision SHA + calculation engine build SHA + exact dimension/time/currency + formula DAG + reconciliation receipts`. Recalculation after source edit produces new result without mutation of previous snapshots. Current source revocation/staleness is checked by exact pinned identity.

**Security boundary:** UAT only, localhost/isolated test tenants, bounded inputs, tenant/project authorization at any exposed read/write endpoint, no production financial approval, no plan promotion. IAM/Keycloak expansion, shares, payments, HTAP revamp and 5k load tests are not R2 MVP prerequisites. Do not omit basic access isolation and prepared queries.

## 8. Acceptance and vertical fixtures

| Fixture | Minimal proof | Blockers |
|---|---|---|
| F02 | netRevenue=200, cogs=100, opex=50 → GP=100, margin=0.5, operating=50; edit to second revision and compare | Correct writable source field binding, F02 synthetic-only current assumptions |
| C01 | two receivables, gross−allowance, overdue subset, zero denominator state | asOf date + snapshot currency + exact row keys |
| C02 | two supplier obligations, overdue subset of outstanding, no automatic advance offset | valid dueAt and positive-stock UAT policy |
| A01 | invoice + two partial settlements + due-date amendment, no duplicate allocation | business key, event ordering, effective dates |
| A05/P04/P09 | one dated loan incl. fees and early payment; cash vs EIR separate | cash schedule revision, covenant source |
| F03/P03 | same cash events, two CFO constructions and FX/noncash bridge | complete events and opening/closing cash |
| A03 | one interchangeable SKU (FIFO/WAC) + one specific asset, NRV | lot movements, units, policy |
| A04 | one asset, two significant components, depreciation and impairment | in-service date, useful life and evidence |
| C07/F02 | sales, return/discount, recognition vs cash | contract recognition basis |
| A06 | FCFF/WACC and FCFE/Ke separately, scenario comparability | dated cashflows and capital/terminal assumptions |
| A02 | three loss buckets and forward adjustment, review blockers | data/evidence and eligibility |

For each: exact source input revision, expected numbers independently prepared, output node lineage, PG16 Testcontainers, React Playwright end-to-end and error fixtures. No E2E claims from Java unit tests or catalog batch counters.

## 9. Stop / GO / parallel work
- **STOP P0:** BUILD_RED; Liquibase migration failure; input revision not re-readable; user values silently replaced by fixtures; method variant incompatible; financial basis/period/currency drift; displayed total not reproducible.
- **GO after R2.5:** F02+C01+C02 are user-visible end-to-end and historic replays are immutable. This is first genuine demonstrable release.
- **GO for each A0X:** domain-specific typed inputs + variant + manual golden example + E2E, independent of registration state.
- **GO for 154 completion:** output-centric tests; partial successes are explicit, 12 evidence forms document-only.
- **Parallel:** UI read-only affordances, policy docs, research and golden vectors may progress while the core source bridge is built. Do not ship more unsupported math kernels during BUILD_RED.

## 10. Implementation handoff for Codex
Start exclusively in `feat/fin-form-library-standalone`. Confirm newest HEAD and restore mandatory backend/PG16/React workflows first. Then inspect `FormDraftService`, `ReportGridService`, `FormFactSourceReader`, `FormFactCandidateExtractor`, `Calc4DExactFactSetInputResolver`, `Calc4DUatPlanStore`, F02, C01/C02 and React route before introducing APIs or migrations. Do not create a third data writer, new parallel calculation engine, extra 167+ forms, or fictitious financial S2. Implement R2.1 → R2.5 **as vertical user-facing demos**. Each PR must attach:
- exact HEAD and diff/migrations;
- both positive and negative PG16 runs and expected/actual source hashes;
- actual React browser E2E;
- updated per-output and per-form readiness rows;
- explicit statuses `PASS/FAIL/NOT_RUN`, including why an output is blocked.

The R1 document remains the **historical approved objective**; R2 supersedes its **implementation sequencing**, preserving Calculation-First, UAT-first and its not-yet-proven acceptance boundaries.
