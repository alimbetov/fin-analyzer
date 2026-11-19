# FIN CALC-166 — Technical Specification: Native PostgreSQL SQL + JDBC + Java

**Document ID:** FIN-CALC-166-HYBRID-TZ-V1  
**Status:** DESIGN DRAFT / NOT FINANCIALLY APPROVED / NOT IMPLEMENTED FOR 166 FORMS  
**Repository:** alimbetov/fin-analyzer  
**Only target branch:** feat/fin-form-library-standalone  
**Code baseline:** 9b8bacdc1486a407378f567988fdd54ce55d318e, 2026-11-19  
**Depends on:** R16.A physical inventory, R16.B typed/versioned mappings, R7.B semantic approval, qualified FactSet and independent S2; CALC-R0.2 F02 E2E, CALC-SQL-01 F02 SQL parity.  
**Tracker:** [CALC-166 #17](https://github.com/alimbetov/fin-analyzer/issues/17), [R16 #15](https://github.com/alimbetov/fin-analyzer/issues/15), [SQL pilot #18](https://github.com/alimbetov/fin-analyzer/issues/18).

## 1. Purpose, audited baseline and scope

Deliver one versioned and safe calculation platform for the current 166-form library, with a selected execution backend per immutable CalcPlan: Java, SQL, or SQL aggregate + Java final. Reuse existing FIN Form Library modules, not a parallel HTAP store or new all-purpose SQL interpreter.

Verified form inventory: **166 unique form codes** = 8 FINANCE + 10 PLANNING + 14 LEDGERS + 59 DETAIL + 63 SECTOR + 12 EVIDENCE. Runtime input modes: **3 SINGLE_RECORD** (F01/F02/F03), **151 ROWSET**, **12 EVIDENCE**. The current R16.A PostgreSQL snapshot reported 877 physical fields: 793 source fields plus 84 document metadata fields. These are **structural candidate contracts, not signed economic semantics**. The registered analytics-model catalogue contains 42 proposed models; CALC_PILOT or DESIGN_ONLY does not imply a running algorithm.

**Implemented as of baseline:** one F02 test-only qualified E2E chain (three derived outputs: grossProfit, grossMargin, operatingResult) with Java BigDecimal and optional SQL F02 arithmetic. F02 SQL/Java parity, 256 randomized values, PostgreSQL F02 worker and representative 10k/100k/1m benchmarks passed CI. The *actual methods for the other 153 data forms are not implemented*, and the 12 evidence forms must **not** be fabricated as numeric-result producers.

The current physical DDL in migrations 019–026 only accepts TEST_QUALIFIED plan and LOCAL_TEST_ONLY result; observed FactSet numeric values are PRESENT-only. Production execution must **not** be unlocked via a feature flag: future approved statuses, authority verification and privileges need additive migrations and separate release review.

Out of scope for this TZ: inventing 166 formulas, automatically approving 877 fields, accepting arbitrary SQL from users, executing financial S2 without an independent signer, bulk-importing historical unverified facts, and promising performance based on one CI runner.

## 2. Mandatory invariants

1. The sole source of calculation inputs is an **exact immutable, qualified FactSet** (one or more pinned factsets for multi-form methods), never "latest" `fin_intake` revision, a React payload or untrusted outbox JSON.
2. Each plan fixes: organization/project/entity/business scope, dataSpace, reporting basis, currency + FX policy, period semantics + calendar, dimensions/grain, source revision IDs + source SHA + FactSet SHA, contract/mapping versions, algorithm version and artifact SHA, config/parameter SHA, approved execution strategy, numeric policy and authority epoch. SHA-256 is an integrity identifier, **not** an S2 signature.
3. No widening of `TEST_QUALIFIED` into production `APPROVED` without independent decision verification. Author and approving reviewer must be distinct identities.
4. A completed job is not necessarily current; publish CURRENT with a fresh transaction/authority check and monotonic epoch. Historical `calc_result` stays immutable and can be inspected.
5. Missing / null / NOT_APPLICABLE / invalid / zero must be different states; zero is never a default for an absent source. Currency, unit, period, dimensions and coverage must be compatible before any computation.
6. Never aggregate `AS_OF` stock by SUM across dates; use validated as-of snapshot selection. `EVENT` vs `INTERVAL` vs YTD and lifetime measures have explicitly versioned semantics.
7. SQL only executes reviewed, allowlisted, versioned templates/functions. No SQL strings or table/column names provided by user-facing forms. All data arguments parameterized.
8. The selected strategy is part of the immutable execution fingerprint, even if arithmetic output hashes match across strategies. No transparent strategy switch in a running job.
9. Result equivalence is compared using canonical **node states, values, units, full lineage and SHA**; not floating-point tolerance or JSON string order.
10. A form is not released because its input UI renders. Production coverage counts **financially approved + executable + end-to-end tested** methods separately.

## 3. System architecture

~~~text
React / REST input
      |
      v
fin_intake (immutable revisions; editor save/reopen)
      |
      v
INGEST exact revision + SHA + versioned typed mappings
      |
      v
independently qualified fin_fact FactSet(s) / authority epoch
      |
      v
FACTSET_QUALIFIED outbox -> validated planner -> immutable CalcPlan
                                            |
                                            v
                                      calc_job (PostgreSQL lease/fencing)
                                            |
                                  +---------+----------+
                                  | Execution strategy |
                                  +---------+----------+
                      JAVA_ONLY   | SQL_ONLY | SQL_AGG_JAVA_FINAL
                                  |          |
                          typed SQL views / JDBC / Java operators
                                  |
                           [optional DUAL_RUN gate]
                                  |
                          ResultCanonicalizer + SHA
                                  |
                   TX-D immutable calc_result + job SUCCEEDED
                                  |
                   TX-E guarded calc_current_result publication
                                  |
                          Read-only report / status API
~~~

Spring Boot (Java 21) owns orchestration, identity, admission, lease/fencing, errors, lineage, SHA, transactions, publishing, review gates, audit and REST. PostgreSQL 16 owns set-oriented source filtering, grouping, ordered windows, temporal joins, quantities, explicit SQL scalar arithmetic and enforcement of referential constraints. JDBC bridges them via bounded server cursors or compact aggregate rows.

Do not replace ``fin_fact`` with raw user-editable form tables or copy the input to another unversioned analytic store.

## 4. Backend strategies and deterministic selection

| Strategy | PostgreSQL | Java | Conditions |
|---|---|---|---|
| JAVA_ONLY | validated, scoped source row retrieval | typed accumulation + all formulas | small set, specialized iterative algorithm, baseline oracle |
| SQL_ONLY | `SUM`/`GROUP BY`/windows plus approved scalar operations | validate returned typed values, canonicalize/SHA, persist | SQL can prove exact matching numeric policy |
| SQL_AGG_JAVA_FINAL | set aggregation and latest-as-of selection; small bounded output | complex business rules, formulas, canonical SHA | default *candidate* for most ROWSET, subject to benchmarks |
| DUAL_RUN | alternative independently evaluated path | golden-oracle comparison of full output | validation mode, not a free-standing production execution strategy |

A `MethodRevision` must declare supported strategies, query/result bound, peak memory estimate, numeric precision, independent parity corpus and selection policy. The planner chooses one strategy **before committing CalcPlan**. Runtime admission checks configuration hash, chosen strategy, SQL function manifest version, JAR artifact manifest and qualification epoch. Reject unknown combinations.

No global threshold like "100k rows => SQL" is sufficient. The selection matrix depends on row count, number/cardinality of dimensions, number of outputs, join plan, index coverage, cursor batching, cost and PostgreSQL capacity. Persist the chosen strategy and selection-rationale version in the plan; benchmark runs inform a versioned configuration decision, not an invisible adaptive switch.

**Mandatory resource boundaries:** maximum fact observations, unique group keys, requested months, recursion/iteration count, SQL query timeout, workload statement/work_mem budget, memory allocation, Java pool size, retries and concurrency per organization. Reject work outside the approved envelope or explicitly split into deterministic partitions.

## 5. Typed contract per form and per metric (R16.B/R7.B)

A form can be admitted only with a **versioned FormCalcContract** that references its exact `fin_lib.lib_form_version.id` and schema SHA and contains:

| Contract element | Required specification |
|---|---|
| source field | unique formCode, formVersionId, fieldCode, physical type, source revision ID, semantic metricCode and contract version |
| economic semantics | metric meaning, numerator/denominator convention, amount sign and accounting basis |
| data grain | entity, project, row/business key, date/time, dimension keys and permitted rollups |
| period | AS_OF, INTERVAL, EVENT, YTD, LTD and calendar; no implicit stock-to-flow conversion |
| unit/currency | money/quantity/rate and currency, decimal precision/scale; FX source, rate version and rounding stage |
| cardinality | required/optional, one/many, uniqueness, unknown and duplicate policy |
| missing states | PRESENT, MISSING, NOT_APPLICABLE, INVALID; documented propagation through each operation |
| aggregation | SUM, COUNT_DISTINCT, LAST_AS_OF, WEIGHTED_AVG, RATIO_OF_SUMS, ROLL_FORWARD, RECONCILE, NONE |
| lineage | exact source revision, row key, dimension digest, mapping SHA, FactSet SHA and contract SHA |
| governance | author, independent reviewer, S2 decision ID/signature or TEST_ONLY isolation, valid-from/to, revocation epoch |

The existing `lib_field_binding_contract` is a **draft structural candidate**, and `lib_form_contract_metric_binding` (migration 014) is a versioned candidate facility, not production approval. Any missing approval or incongruent periods/units must explicitly block the plan with a typed reason. Presentation-only R7.A must not change financial calculation semantics.

**Complete per-form approval** requires source-field mapping review *and* output methodology review. A source form can support several approved methods; absence of a numeric method is legitimate for evidence and narrative data.

## 6. Source and fact model extension (additive only)

Existing `fin_fact.fact_observation` is partitioned by period_start, has a NUMERIC(38,12) numeric_value, currently only PRESENT numeric observations; `fin_calc.calc_plan` references one primary FactSet and `calc_current_result` is keyed by organization/scope.

Design extensions with new Liquibase changesets **after 026**; never edit applied 001–026:

1. `fin_calc.method_revision` — immutable methodCode, semanticVersion, contractId, operatorDAG SHA, approved executor kinds, query entrypoint digest, SQL/JAR build artifact SHA, param/config schema and canonicalization version. No inline arbitrary SQL evaluated from DB rows.
2. `fin_calc.method_input_binding` — immutable per-method required metric/variant, field-to-metric contract version, units, time policy, grain, missing policy, aggregation and dimensional allowlist.
3. `fin_calc.method_approval_decision` and `method_revocation_control` — append-only signed independently issued decisions plus separate mutable current/revocation authority, distinct from the draft R16 field table.
4. `fin_calc.plan_factset_binding` — (planId, ordinal, exact FactSetID, organizationId, source scopeKey, epoch, SHA, mapping digest); FK to exact-scope FactSet, unique ordinal and no cross-tenant references. Current primary `calc_plan.factset_id` remains a compatibility anchor during migration.
5. `fin_calc.plan_execution_binding` — chosen execution kind, methodRevisionId, artifactBuildManifestSha, database function manifest, parameters SHA, precision policy, dataset query fingerprint; immutable with plan.
6. `fin_fact.fact_observation_state` or a separately versioned value-envelope contract for missing/NOT_APPLICABLE/INVALID without weakening 020's numeric PRESENT invariant. Never convert absent rows into fabricated zero-valued observations.
7. `fin_calc.calc_result_node` / typed output envelope as needed for scalable query; preserve the current immutable result JSONB + lineage contract and exact historical hashes. Output codes, units and states must have an unambiguous canonical encoding.
8. `fin_calc.current_method_result` keyed by organizationId, projectId, method/variant, output scope, period and dimension digest to avoid one generic scope pointer overwriting another method's CURRENT. Keep existing `calc_current_result` until consumers are migrated by an explicit compatibility test.
9. Immutable registry of approved SQL function IDs/hashes, deployment manifest and replayable golden-vector SHA. PostgreSQL function privileges: dedicated calculation role, no DDL/owner powers, no mutation of fact/source/results from the SQL arithmetic functions.
10. Indexes for `(factset_id,period_start,metric_code)` and approved dimension/grain lookups; targeted, evidence-backed indexes/partitions only, `EXPLAIN (ANALYZE, BUFFERS)` before approving each high-cardinality workload.

For tenant isolation, always join fact observations through verified `fact_set.organization_id`/`scope_key`/`project_id`. Physical partitions lack an organization_id; never use a raw unscoped scan as the final trusted query. Validate RLS/privilege behavior with real non-owner credentials in PostgreSQL tests.

## 7. Safe SQL/JDBC contract

**Allowed SQL** is static, reviewed and packaged in Git-versioned source migrations, stored views, parameterized SQL modules or approved procedures. A SQL method manifest binds function OID/name, signature, deployed body digest, required grants and dependency versions. A new function or changed semantic query must create a new method revision, a new execution fingerprint and new tests. No arbitrary expression interpreter or parameterized identifier injection.

Preferred SQL aggregation pattern:

~~~sql
-- ILLUSTRATIVE TEMPLATE ONLY: do not use until this form's typed
-- metrics, scope, currency, period and dimensions are approved.
SELECT
    o.metric_code,
    SUM(o.numeric_value) AS aggregate_value,
    COUNT(*) AS observation_count
FROM fin_fact.fact_observation o
JOIN fin_fact.fact_set f
  ON f.id = o.factset_id
WHERE f.organization_id = :organization_id
  AND f.project_id = :project_id
  AND f.scope_key = :exact_scope_key
  AND o.factset_id = :factset_id
  AND o.period_start >= :period_start
  AND o.period_start < :period_end
  AND o.metric_code IN (:allowlisted_metrics)
GROUP BY o.metric_code;
~~~

This is a flow-measure illustration; **not permitted for AS_OF stock fields, mixed currencies, duplicate keyed facts or dimensions requiring a GROUP BY**. The actual form-method SQL must be built from trusted reviewed descriptors, with explicit currency and grain checks, and must reject non-unique/missing observations rather than silently summing them.

Spring implementation guidelines:

- `NamedParameterJdbcTemplate` for parameterized set queries, `JdbcTemplate` for single-function scalar operations.
- For JAVA_ONLY source streaming use a real read-only transaction and server-side PostgreSQL cursors with configured fetchSize and bounded row limits; beware pgjdbc autocommit and transaction-pool semantics.
- For SQL_AGG_JAVA_FINAL transfer only approved compact aggregate rows; use typed Java records, `BigDecimal` and immutable input maps.
- No JPA entity hydration for millions of facts. JPA can remain for low-volume registry/workflow metadata.
- Set query and transaction timeouts; collect scanned/returned row counts; never hold the long CPU/JDBC SQL calculation inside TX-D result commit.
- SQL functions which perform only pure arithmetic may be `IMMUTABLE PARALLEL SAFE`; **any function reading tables is not immutable** by assertion. Avoid volatile side effects and security-definer escalation.
- Use strict exact JDBC NUMERIC mapping; do not use Java `double`/`float` for monetary computations, or text comparison of numeric formatting as the sole correctness proof.
- SQL execution returns value + state + unit + canonicalization version, not an unsigned claim that a result is financial approved.

## 8. Number handling, parity and reproducibility

The F02 pilot is the numerical reference: money scale=2, margin ratio scale=8, HALF_EVEN, positive cost magnitudes. PostgreSQL built-in `round(numeric,2)` is **not** equivalent to Java HALF_EVEN ties. Use reviewed exact numeric operations (CALC-SQL-01 functions in additive migrations 024–026, plus parity gate). Reject negative inputs when the method specifies positive magnitudes; negative **derived** operating profit is still possible.

A method's reproducibility fingerprint must bind all source hashes/versions, scalar and aggregation operator versions, rounding stage, denominator/missing policy, SQL function build SHA, Java build SHA, parameters and chosen execution strategy. Output SHA is the canonical typed economic result SHA, and may match across engines; execution identity must still differ when relevant executable artifacts differ.

A `DUAL_RUN` test compares the complete canonical graph/value/state/error/unit/dimension/lineage SHA. Initially, any mismatch blocks publication and emits a typed error including both execution hashes (never raw customer financial values). For SQL-only paths, Java may independently re-evaluate an approved sample or full output during qualification; production shadow rates require independent audit/approval.

Negative inputs/cases: ties 1.005/1.025, precision up to NUMERIC(38,12), huge numerators/denominators, recurring ratio decimals, division by zero, negative derived values, overflow, empty sets, duplicates, stale versions, cross-currency and incompatible periods.

## 9. Domain operator library (no invented methods)

Reusable **reviewed operators**: `SUM_FLOW`, `COUNT`, `COUNT_DISTINCT`, `LAST_AS_OF`, `SIGNED_ROLL_FORWARD`, `RATIO_OF_SUMS`, `WEIGHTED_AVERAGE`, `GROUP_BY`, `AGING`, `PERIOD_COMPARE`, `RECONCILIATION`, `BALANCE_CHECK`, `ALLOCATE`, `SCENARIO`, `NPV`, `IRR`, `WACC`.

Each operator requires specific inputs, units, null-state semantics, time aggregation, rounding stage, output code/version, negative/golden vectors and a named owning methodology. **A proposed operator is not an executable finance methodology.** Operators are shared across forms; do not generate 166 near-identical stored procedures. A form may have zero, one or multiple output methods.

Priority business examples **PROPOSED, not implemented or approved**:

| Form | Required source semantics | Candidate output methods | Gate |
|---|---|---|---|
| F01 balance | assets, liabilities, equity, exact AS_OF, sign convention | `balanceDifference = assets - liabilities - equity`; tolerance policy by methodology | R16.B + R7.B + S2 |
| F02 P&L | netRevenue, cogs, operatingExpenses | `grossProfit`, `grossMargin`, `operatingResult` | UAT E2E already; production S2 HOLD |
| F03 cash flow | cash opening/closing, CFO/CFI/CFF, cash definition | `closingCash - openingCash - netFlows`, reconciliation | current 5 physical fields must first be inspected; no invented input field |
| F06 trial balance | account, debit/credit, date, opening/closing classification | trial balance, account mapping into F01/F02/F03 | signed account/currency mapping |
| C01/C02 receivables/payables | row-level invoice, due date, payment, written-off/settled states | AR/AP aging and overdue exposure | no implicit SUM of mixed dates/status |
| C03 / DF-STK inventory | key SKU/warehouse, movement event and AS_OF stock | stock valuation, roll-forward, turnover | approved costing and quantity policy |
| P01/P02/P03 | versioned budget model and scenario, periods | forecast balance/P&L/cash | model-specific financial governance |
| P07/P08 | scenario, dated cash flows, rates, valuation horizon | NPV/IRR/sensitivity | explicit finance/discount policy |
| SF groups | measure quantities, units, reporting grain and locale | specialized sector KPIs | explicit sector method contracts |
| D01–D08 / T01–T04 | document hash, author, version, provenance, expiry | evidence integrity + supported-method lineage, **no required scalar result** | evidence acceptance, not fake numeric 0 |

## 10. Multi-form DAG, incremental execution and historical semantics

- Each DAG node declares exact source metric keys and upstream dependencies. E.g., an approved CFO model consumes F01/F02/F03 only via its versioned multi-source `plan_factset_binding`.
- Validate graph is acyclic; check compatible organizations/projects, entity identities, reporting basis and periods (not every source must have identical period if the method documents an explicit reconciliation horizon).
- Build `nodeFingerprint` only from consumed source values/versioned method details; reuse previously computed nodes only when exact canonical source/operation hashes match. A new calculation still gets a **new whole-result lineage and plan binding**.
- If a report revision changes while running: preserve prior immutable results, fail stale admission or return obsolete, never silently rebind to "current latest".
- Result conflict: same fingerprint with different value/state/lineage SHA is a `NON_DETERMINISTIC_CALCULATION` failure, not last-writer-wins.
- A result may be committed as historical after revocation races, but only a separately re-verified exact epoch/FactSet can be published CURRENT.
- Every displayed derived metric has click-through lineage to source field, row key, revision, form, method version, parameter SHA, SQL/JAR artifact and epoch.

## 11. Transaction and job lifecycle

Preserve the tested CALC-R0.2 transaction boundaries:

1. TX-Q exact qualified FactSet (same transaction for immutable facts, receipt, outbox) under independent authority.
2. TX-B consume qualified event idempotently; validate persisted receipt/source and atomically create immutable plan + READY job + outbox ACK.
3. TX-C claim `FOR UPDATE SKIP LOCKED`, set leaseOwner/leaseUntil/fencingToken/attempts, commit quickly. No database locks across long calculation.
4. Compute outside claim transaction using selected JDBC/SQL/Java engine; optionally dual-run with independent source verification.
5. TX-D lock job and validate unexpired lease, owner and fencing; immutable INSERT result with exact dedup; job -> SUCCEEDED in the **same transaction**, with rollback failpoints.
6. TX-E lock factset/approval authority and CURRENT pointer; compare exact epoch/approved method revision and active FactSet set; CAS publish if and only if still current.
7. Recovery: bounded transient retries with exponential backoff+jitter, fail-closed semantic BLOCKED, dead-letter/quarantine for poison events, expired lease reclaim and monitoring. No unbounded worker retry loop.

The existing F02 default remains unchanged; generalized 166-form dispatch is not enabled until method-by-method tests and S2 controls are implemented.

## 12. Error taxonomy, UI and API

Plan/job/result state is explicit. Minimum typed blockers:
`FORM_CONTRACT_UNQUALIFIED`, `METHOD_NOT_APPROVED`, `SOURCE_REVISION_STALE`, `MAPPING_SHA_MISMATCH`, `FACTSET_REVOKED`, `S2_EXPIRED`, `MISSING_SOURCE`, `NOT_APPLICABLE`, `DIVISION_BY_ZERO`, `UNIT_MISMATCH`, `PERIOD_INCOMPATIBLE`, `CURRENCY_MISMATCH`, `DUPLICATE_BUSINESS_KEY`, `DIMENSION_CARDINALITY_EXCEEDED`, `NUMERIC_OVERFLOW`, `SQL_JAVA_PARITY_MISMATCH`, `SQL_QUERY_TIMEOUT`, `FENCING_LOST`, `NON_DETERMINISTIC_CALCULATION`.

Proposed read-only APIs (not yet implemented):
- `GET /api/v1/calculation/forms/{formCode}/coverage` → exact formVersion, contracts, required method inputs, eligibility blockers, approval mode.
- `POST /api/v1/calculation/plans/preview` → no writes; exact candidate plan, strategy and dependencies; never production approval.
- `GET /api/v1/calculation/jobs/{jobId}` → tenant-scoped status/attempts/error.
- `GET /api/v1/calculation/results/{resultId}` → authorized immutable result, node provenance, artifact and method digest.
- `GET /api/v1/calculation/results/current?projectId=...&methodCode=...` → guarded current pointer; never query mutable latest source.
- `GET /api/v1/calculation/methods/{methodCode}/versions` → only reviewed descriptors for roles with authorization.

No client-supplied orgId/userId/header token can be treated as ownership. Production API requires authentic IAM subject, project-scoped ABAC and explicit sharing permission. Bank/investor share links are read-only snapshots with revocation and separate download authorization, not live FactSet mutation.

## 13. Security, approvals and SQL supply chain

- UAT synthetic qualification stays separate from real financial S2; method authors cannot approve their own method.
- Approved SQL/JAR artifacts need a reproducible signed deployment manifest (dependency lock, source/build SHA, function DDL digest, privileges, contract version). An arbitrary `sha256` field or digest of a synthetic descriptor is not an attested build.
- Do not allow production roles to `UPDATE/DELETE/TRUNCATE` immutable data or change deployed SQL functions. Enforce least privilege, separate migration owners and runtime calculation role, test partition-child access and alternate update paths.
- Use PostgreSQL prepared statements / explicit identifiers allowlist; reject SQL-injection attempts via form field, dimension, filter, method parameter or query templating.
- Enforce per-tenant quotas/timeouts, tenant cross-read/write negative tests, audit actor/authority/revision, no raw finance values in logs.
- Timeouts, cancellation and pool exhaustion are fail-closed; a cancelled query cannot publish a partial result.

## 14. Performance qualification and strategy policy

Historical **illustrative** PG16 Testcontainers benchmark on GitHub Actions, three execution modes and synthetic values:

| Rows | JAVA_ONLY | SQL_ONLY | SQL_AGG_JAVA_FINAL |
|---:|---:|---:|---:|
| 10,000 | 7.939 ms | 2.255 ms | 1.892 ms |
| 100,000 | 69.397 ms | 12.585 ms | 11.674 ms |
| 1,000,000 | 506.877 ms | 54.795 ms | 64.497 ms |

For 200 already-aggregated scalar F02 calls, 5.864 ms Java-only versus 59.639 ms SQL/JDBC in that run. **These are not production SLA or a universal winner.** These measurements exclude fixture creation, Liquibase startup, production concurrency and background worker scheduling.

Acceptance benchmark must run on representative form shapes (3-field F02; high-cardinality ROWSET; AS_OF snapshot; long cash event history; multi-form model; many dimensions, sparse/missing, large groups), sizes 10k/100k/1m plus ≥1k concurrent jobs where capacity permits, warm and cold cache, same pinned numeric input and hashes. Collect p50/p95/p99 end-to-end latency, RPS, CPU, allocations, DB temp files/work_mem, `EXPLAIN (ANALYZE, BUFFERS)`, locks, I/O, statement timeout rate, result/lineage SHA and resource contention.

Initially a performance decision is **per method revision**, not per form or a single global threshold. Record results and pin the preferred strategy only after correctness/operational stability is proven. If SQL loses against hybrid on actual form data, choose hybrid; if there are only 3 scalar inputs, keep Java unless another measured benefit exists.

## 15. Test matrix and release quality gates

For **each of 166 formCodes** (independent PostgreSQL test identity):
- physical current schema/field count/formVersion, candidate mapping and typed field details; source revision save → reopen; exact canonical SHA and historical revision replay;
- successful approved method E2E **only when** an actual method/approval exists; otherwise verified `BLOCKED_*` status, never imaginary PASS;
- for each of 154 data forms define source mapping/financial contract coverage and qualifying strategy; for each of 12 evidence forms verify attachments, provenance, expiry and linkage rather than requiring a scalar result;
- negative: unknown field, stale schema, wrong period/currency/unit, duplicate rowKey, missing vs zero, permissions, revoked approval, method unapproved, partition missing, replay, concurrent edits, SQL injection and excessive group cardinality.

For **each approved executable method revision**, additionally:
- golden numerical vectors and Java/SQL/hybrid parity (all outputs, states, SHA); tie rounding, extreme scale, zero denominator, negative derived values, nulls, empty group and overflow;
- PostgreSQL concurrent claims, lease expiry, stale fence, rollback before/after result, duplicate idempotence, revocation between compute and publish, old result cannot overwrite newer CURRENT;
- temporal and grain semantics: LAST_AS_OF not SUM, cutoff exactness, weighted-average vs average-of-averages, signed flow, denominator policy, same/foreign currency, cross-form plan lineage;
- clean install and legacy-to-current upgrade with preserved Liquibase checksums and immutable historical data; real PG16 (H2 does not qualify);
- no skipped dynamic form cases, no generated fake approval, all selected contract/UI smoke tests green on the **same exact commit SHA**;
- immutable result saved and read back, source-to-result drilldown, no unverified preview writes into fin_fact;
- performance measurement captured separately, not a substitute for correctness.

**Coverage reporting must separate** registered forms, physical fields, typed reviewed sources, approved method revisions, test-only executable methods, financially approved methods, end-to-end qualified forms, evidence-only verified forms. 166/166 structural checks ≠ 166/166 financial methods.

## 16. Work packages and dependencies

| Stage | Description | Must be GREEN before proceeding |
|---|---|---|
| HYB-0 | Preserve F02 reference, SQL parity/bench, exact rounding, immutable job/result | Current F02 CI + S2 boundary confirmed |
| HYB-1 | R16.B/R7.B approved per-form metric semantics, versioned field bindings, governance and independent S2 interface | First accepted semantic contracts; no auto approval |
| HYB-2 | Versioned method/operator registry, SQL artifact manifest, SQL/JDBC typed binding, support for non-PRESENT and multi-source | Negative PG16 migration/tenant/source and approval tests |
| HYB-3 | F01 balance and F03 cash review + full SQL/Java parity/E2E; do not infer unsupported cash sources | F01/F03 actual golden fixtures and signed independent method decisions |
| HYB-4 | General ROWSET source→fact adapter: rowKey, grain, dimensions, typed events, aggregates | 151 per-form source projections, duplicates/missing/period PG16 tests |
| HYB-5 | Reusable ledger/forecast/sector operator families; one method revision at a time | Audited methods, each with explicit code + golden SQL/Java/Hybrid E2E |
| HYB-6 | 12 evidence forms: hash/provenance/expiry/permission and supporting lineage | 12 evidence-specific PG16/browser tests |
| HYB-7 | Generalized DAG, method-qualified CURRENT by scope/period/dimensions, strategy pinning, incremental reuse | Competing-worker, revocation, replay and cross-method tests |
| HYB-8 | Performance tuning on real representative synthetic workloads and security hardening | p95/p99 resource budgets, DB privilege/RLS tests, build attestation |
| HYB-9 | Full 166-form release acceptance + independent financial S2 | 166 independent verifiable evidence records, production security/financial approval |

Recommended implementation batches (not calendar promises): start F02 parity oracle → F01/F03 → C01/C02/C03/C06/C07/C08/C09 → P01/P02/P03/P04/P07/P08 → remaining C/F/P and 59 DF → 63 SF → 12 D/T evidence. This is a **dependency order**, not 166 isolated SQL files. Each batch is a code+tests+methodology review unit.

## 17. Definition of Done

A form is READY_FOR_CALC only when its per-version method contract has valid semantic approval, every required metric is pinned to a qualified FactSet, a deterministic CalcPlan is built, the chosen engine passes reference/golden/negative E2E, resulting immutable `calc_result` matches lineage/SHA, CURRENT publication respects revocation and tenant privileges, historical results remain intact, and the exact commit CI is green. An evidence-only form is READY_FOR_EVIDENCE only when the evidence integrity/lineage gates pass; it must never be counted as an executable financial method.

**No numeric-result implementation may be declared "all 166 complete" while the actual 153 remaining data-form methods are absent.** Likewise no user-facing production approval is possible until independent financial S2 and IAM are present.

## Appendix A — authoritative 166-form inventory and *proposed* calculation family

This inventory is compiled from current source catalog editorial titles and the ingestion strategy TSV at the stated code SHA. The "family" column is **a classification hypothesis for method authoring**, NOT a reviewed formula, SQL function or approved output. Only F02 has a verified local UAT calculation. All other rows are BLOCKED for qualified finance execution until explicitly reviewed.

| # | Code | Title RU | Runtime mode | Candidate family / status |
|---:|---|---|---|---|
| 1 | C01 | Реестр дебиторской задолженности | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 2 | C02 | Реестр кредиторской задолженности | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 3 | C03 | Реестр запасов | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 4 | C04 | Реестр основных средств | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 5 | C05 | Кредитные договоры и остатки долга | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 6 | C06 | Банковские операции по счетам | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 7 | C07 | Реестр продаж и возвратов | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 8 | C08 | Реестр себестоимости и затрат | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 9 | C09 | Реестр операционных расходов | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 10 | C10 | Налоговые начисления и расчёты | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 11 | C11 | Реестр авансов и предоплат | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 12 | C12 | Портфель контрагентов | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 13 | C13 | Гарантии и условные обязательства | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 14 | C14 | Операции со связанными сторонами | ROWSET | LEDGER_AGG_OR_RECONCILIATION (проект) |
| 15 | D01 | Анкета банка для кредитной заявки | EVIDENCE | EVIDENCE_PROVENANCE (не числовой расчёт) |
| 16 | D02 | Реестр документов кредитного досье | EVIDENCE | EVIDENCE_PROVENANCE (не числовой расчёт) |
| 17 | D03 | Подтверждение целевого использования кредита | EVIDENCE | EVIDENCE_PROVENANCE (не числовой расчёт) |
| 18 | D04 | Документы и оценка залогового имущества | EVIDENCE | EVIDENCE_PROVENANCE (не числовой расчёт) |
| 19 | D05 | Договоры и гарантии по обязательствам | EVIDENCE | EVIDENCE_PROVENANCE (не числовой расчёт) |
| 20 | D06 | Банковские справки и подтверждения | EVIDENCE | EVIDENCE_PROVENANCE (не числовой расчёт) |
| 21 | D07 | Корпоративное согласие на финансирование | EVIDENCE | EVIDENCE_PROVENANCE (не числовой расчёт) |
| 22 | D08 | Аудиторское заключение | EVIDENCE | EVIDENCE_PROVENANCE (не числовой расчёт) |
| 23 | F01 | Бухгалтерский баланс | SINGLE_RECORD | BALANCE_CHECK (проект, review требуется) |
| 24 | F02 | Отчёт о прибылях и убытках | SINGLE_RECORD | F02_PNL_3_NODE (UAT E2E) |
| 25 | F03 | Отчёт о движении денежных средств | SINGLE_RECORD | CASH_ROLL_FORWARD (проект, review требуется) |
| 26 | F04 | Отчёт об изменениях капитала | ROWSET | EQUITY_ROLL_FORWARD (проект) |
| 27 | F05 | Пояснения к финансовой отчётности | ROWSET | NOTES_RECONCILIATION_OR_NONE (review) |
| 28 | F06 | Оборотно-сальдовая ведомость | ROWSET | TRIAL_BALANCE (проект) |
| 29 | F07 | Управленческая финансовая отчётность | ROWSET | MANAGEMENT_KPI (проект) |
| 30 | F08 | Консолидированная отчётность группы | ROWSET | CONSOLIDATION (проект) |
| 31 | P01 | Прогнозный баланс | ROWSET | FORECAST_OR_SCHEDULE (проект) |
| 32 | P02 | Прогноз доходов и расходов | ROWSET | FORECAST_OR_SCHEDULE (проект) |
| 33 | P03 | Прогноз движения денежных средств | ROWSET | FORECAST_OR_SCHEDULE (проект) |
| 34 | P04 | Прогноз погашения кредитов | ROWSET | FORECAST_OR_SCHEDULE (проект) |
| 35 | P05 | План капитальных вложений | ROWSET | FORECAST_OR_SCHEDULE (проект) |
| 36 | P06 | План оборотного капитала | ROWSET | FORECAST_OR_SCHEDULE (проект) |
| 37 | P07 | Инвестиционная финансовая модель | ROWSET | INVESTMENT_SCENARIO (проект) |
| 38 | P08 | Сценарии и стресс-тестирование | ROWSET | INVESTMENT_SCENARIO (проект) |
| 39 | P09 | Прогноз обслуживания долга | ROWSET | FORECAST_OR_SCHEDULE (проект) |
| 40 | P10 | План источников финансирования | ROWSET | FORECAST_OR_SCHEDULE (проект) |
| 41 | T01 | Налоговая декларация — подтверждающий документ | EVIDENCE | EVIDENCE_PROVENANCE (не числовой расчёт) |
| 42 | T02 | Налоговые подтверждения и агрегаты | EVIDENCE | EVIDENCE_PROVENANCE (не числовой расчёт) |
| 43 | T03 | Подтверждение оборота и НДС | EVIDENCE | EVIDENCE_PROVENANCE (не числовой расчёт) |
| 44 | T04 | Документы применимого налогового режима | EVIDENCE | EVIDENCE_PROVENANCE (не числовой расчёт) |
| 45 | DF-AST-01 | Реестр основных средств | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 46 | DF-AST-02 | Ремонты и простои оборудования | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 47 | DF-AST-03 | Недвижимость и аренда | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 48 | DF-AST-04 | Использование ресурсов и мощностей | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 49 | DF-CSH-01 | Журнал денежных операций | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 50 | DF-CSH-02 | Реестр платежей по обязательствам | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 51 | DF-CSH-03 | Остатки денежных средств | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 52 | DF-CSH-04 | Счета к получению и инкассация | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 53 | DF-CSH-05 | Счета поставщиков и оплата | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 54 | DF-CSH-06 | График платежей по долгу | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 55 | DF-CST-01 | Детализация затрат | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 56 | DF-CST-02 | Себестоимость проданных товаров | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 57 | DF-CST-03 | Распределение косвенных затрат | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 58 | DF-CST-04 | Движение капитала собственников | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 59 | DF-CST-05 | Налоговые начисления и платежи | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 60 | DF-CTX-01 | Профиль и правовая форма бизнеса | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 61 | DF-CTX-02 | Филиалы и сегменты бизнеса | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 62 | DF-CTX-03 | Параметры отчётности и валюты | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 63 | DF-CTX-04 | Цели и финансовые ограничения | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 64 | DF-CTX-05 | Структура владения и связанные организации | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 65 | DF-CUS-01 | Клиентские сегменты и выручка | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 66 | DF-CUS-02 | Удержание и отток клиентов | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 67 | DF-CUS-03 | Обращения и жалобы клиентов | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 68 | DF-CUS-04 | Клиентские договоры | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 69 | DF-GOV-01 | Участники и доли в капитале | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 70 | DF-GOV-02 | Корпоративные решения и полномочия | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 71 | DF-GOV-03 | Дивиденды и распределения | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 72 | DF-GOV-04 | Обязательства по решениям органов управления | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 73 | DF-HR-01 | Численность и фонд оплаты труда | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 74 | DF-HR-02 | Рабочее время и производительность | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 75 | DF-HR-03 | План численности и оплаты | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 76 | DF-HR-04 | Текучесть и вакансии | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 77 | DF-MKT-01 | Маркетинговые кампании и расходы | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 78 | DF-MKT-02 | Лиды и этапы продаж | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 79 | DF-MKT-03 | Атрибуция рекламных расходов | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 80 | DF-MKT-04 | Гипотезы и исследование рынка | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 81 | DF-OPS-01 | Производственные мощности и выпуск | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 82 | DF-OPS-02 | Спецификации материалов и комплектующих | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 83 | DF-OPS-03 | Брак, переделки и простои | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 84 | DF-OPS-04 | Исполнение сервисных заказов | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 85 | DF-OPS-05 | Рейсы, топливо и логистические затраты | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 86 | DF-PRJ-01 | Цели и варианты бизнес-проекта | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 87 | DF-PRJ-02 | Инвестиционный бюджет проекта | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 88 | DF-PRJ-03 | Сценарии продаж и затрат проекта | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 89 | DF-PRJ-04 | Источники и условия финансирования проекта | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 90 | DF-PRJ-05 | Предпосылки и чувствительность модели | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 91 | DF-PRJ-06 | Этапы и контрольные точки проекта | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 92 | DF-PRJ-07 | Партнёры и ресурсы проекта | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 93 | DF-SAL-01 | Журнал продаж по товарам | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 94 | DF-SAL-02 | Заказы и отгрузки | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 95 | DF-SAL-03 | Возвраты и компенсации | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 96 | DF-SAL-04 | Прайс-листы и скидки | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 97 | DF-SAL-05 | Продажи по каналам и регионам | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 98 | DF-SAL-06 | Выручка и рабочие часы смен | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 99 | DF-STK-01 | Складские остатки по товарам | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 100 | DF-STK-02 | Закупки и поступления товара | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 101 | DF-STK-03 | Складские движения и списания | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 102 | DF-STK-04 | Условия поставщиков и сроки поставок | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 103 | DF-STK-05 | Планирование потребности в запасах | ROWSET | GROUPED_DETAIL_OR_REFERENCE_CHECK (проект) |
| 104 | SF-A01 | Посевные площади и урожайность | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 105 | SF-A02 | Поголовье, надои и корма | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 106 | SF-A03 | Сезонные денежные потоки растениеводства | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 107 | SF-B01 | Добыча и переработка полезных ископаемых | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 108 | SF-B02 | Энергопотребление и затраты добычи | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 109 | SF-B03 | Простои и восстановление горных объектов | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 110 | SF-C01 | Выпуск и качество продукции | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 111 | SF-C02 | Производственная себестоимость | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 112 | SF-C03 | Загрузка и простои оборудования | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 113 | SF-D01 | Выработка и отпуск электроэнергии | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 114 | SF-D02 | Топливо и эффективность генерации | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 115 | SF-D03 | Доступность энергооборудования | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 116 | SF-E01 | Водоснабжение и потери в сетях | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 117 | SF-E02 | Очистка воды и эксплуатационные затраты | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 118 | SF-E03 | Переработка и утилизация отходов | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 119 | SF-F01 | Выполнение строительных работ | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 120 | SF-F02 | Бюджет и фактические затраты строительства | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 121 | SF-F03 | Поступления и удержания по строительству | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 122 | SF-G01 | Продажи и маржа розничной торговли | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 123 | SF-G02 | Оборачиваемость торговых запасов | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 124 | SF-G03 | Закупочные цены и условия поставок | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 125 | SF-H01 | Экономика транспортных рейсов | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 126 | SF-H02 | Своевременность и качество доставки | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 127 | SF-H03 | Эффективность складских операций | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 128 | SF-I01 | Продажи и себестоимость меню | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 129 | SF-I02 | Пищевые потери и работа кухни | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 130 | SF-I03 | Загрузка гостиничных номеров | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 131 | SF-J01 | Динамика регулярной выручки MRR | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 132 | SF-J02 | Бюджет и трудозатраты IT-проектов | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 133 | SF-J03 | Доступность программных сервисов | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 134 | SF-K01 | Кредитные риски портфеля | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 135 | SF-K02 | Страховые премии и выплаты | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 136 | SF-K03 | Себестоимость финансовых операций | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 137 | SF-L01 | Арендная площадь и занятость помещений | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 138 | SF-L02 | Доходность эксплуатации недвижимости | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 139 | SF-L03 | Продажи объектов девелоперского проекта | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 140 | SF-M01 | Оплачиваемые часы специалистов | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 141 | SF-M02 | Доходность консалтинговых договоров | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 142 | SF-M03 | Этапы и затраты профессиональных проектов | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 143 | SF-N01 | Часы и себестоимость аутсорсинговых контрактов | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 144 | SF-N02 | Загрузка арендного оборудования | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 145 | SF-N03 | Выполнение сервисных контрактов | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 146 | SF-O01 | Бюджет и расходы государственных программ | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 147 | SF-O02 | Обработка обращений в государственных сервисах | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 148 | SF-O03 | Охват и результаты государственных программ | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 149 | SF-P01 | Обучающиеся и посещаемость | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 150 | SF-P02 | Учебные часы и затраты преподавателей | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 151 | SF-P03 | Завершение обучения и затраты программ | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 152 | SF-Q01 | Приёмы пациентов и неявки | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 153 | SF-Q02 | Медицинские процедуры и себестоимость | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 154 | SF-Q03 | Загрузка кабинетов и оборудования | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 155 | SF-R01 | Продажа билетов и посещаемость событий | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 156 | SF-R02 | Доходы и затраты мероприятий | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 157 | SF-R03 | Удержание участников клуба | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 158 | SF-S01 | Доходность услуг и расходные материалы | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 159 | SF-S02 | Бронирования и повторные клиенты | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 160 | SF-S03 | Членские взносы и задолженность | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 161 | SF-T01 | Доходы и расходы домохозяйства | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 162 | SF-T02 | Трудозатраты и зарплата домохозяйства | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 163 | SF-T03 | Сезонное производство домохозяйства | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 164 | SF-U01 | Бюджет международной программы | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 165 | SF-U02 | Результаты и целевые показатели программы | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |
| 166 | SF-U03 | Обязательства и поступления финансирования | ROWSET | SECTOR_KPI_OR_RECONCILIATION (проект) |

## Appendix B — auditable source files

- `form-library/src/main/resources/catalog/fin-fact-ingestion-strategy-r0.tsv` (166 codes + input modes)
- `form-library/src/main/resources/catalog/editorial-titles-finance-r15.tsv`
- `form-library/src/main/resources/catalog/editorial-titles-details-r15.tsv`
- `form-library/src/main/resources/catalog/editorial-titles-sector-r15.tsv`
- `form-library/src/main/resources/db/changelog/012-lib-field-metric-contract.sql`
- `form-library/src/main/resources/db/changelog/014-lib-versioned-field-contracts.sql`
- `form-library/src/main/resources/db/changelog/019-fin-calc-r0-postgresql.sql` through `026-fin-calc-f02-exact-decimal-output-postgresql.sql`
- `form-library/src/main/java/kz/fin/formlib/calc/` (FactSet, plan, worker, F02 Java/SQL kernels)
- `form-library/src/test/java/kz/fin/formlib/calc/` (F02 E2E, SQL parity, benchmark, 166-form readiness)
- `docs/FIN-CALC-R0-INCREMENTAL-CALCULATION-ARCHITECTURE-TZ.md` — historical architecture; older implementation status inside that document is not current.

**Governance:** any revision to approved financial semantics, numeric policy, SQL implementation digest or input mapping creates a new version and independently reviewed decision. Changes to presentation-only R7.A must not alter the financial hash. All future commits restricted to feat/fin-form-library-standalone until explicit release authorization.
