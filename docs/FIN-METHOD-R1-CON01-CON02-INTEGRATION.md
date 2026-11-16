# FIN — CON-01/02 reviewed SKU population → SingleExecutor → CORE

**Date:** 2026-11-16  
**Branch:** `feat/fin-r0-r1a`  
**Status:** `CODED / JUNIT_AND_POSTGRESQL_TESTS_AUTHORED / CI_NOT_CONFIRMED / RELEASE_HOLD`

## Scope delivered

- `V20261116_07__reviewed_sku_population.sql`: a separate immutable, tenant-scoped submission, transaction-line and independent-review ledger under `fin_con`. Every manifest pins the exact published `FactSetRevision`, snapshot hash, organization/entity/scope, accounting basis, calendar month and currency. Each event has a unique source-line reference, SKU, kind (`SALE`, `RETURN`, `CANCELLATION`), original sale reference for reversals, positive source amount, posted date and source manifest document digest.
- **No duplicate line ref within a population.** Every RETURN/CANCELLATION must reference a SALE **in the same period and for the same SKU**. Aggregate refunds cannot exceed the original sale. CANCELLATION must equal the original gross amount, occur at most once, and cannot coexist with refunds. Full return/cancellation produces zero, not an invented positive SKU sale.
- Review requires **two distinct actors**. Its database trigger checks immutable source context, source rows/count/net sum, exact F02 `REV_NET` from a **separately approved** human-reviewed `FIN-PILOT-V1` F02 in the same published snapshot, and server-qualified `FLOW/MONEY` REV_NET metric contract. Failure of any requirement blocks the review. A manifest hash is calculated from sorted immutable transaction rows. Neither document-hash declaration nor a human review by itself authenticates underlying external invoices.
- `ReviewedSkuPopulationLoader`: only consumes precisely one `APPROVED` source-population certificate for the requested ACTUAL fact-set snapshot, within the same tenant, entity, scope and currency. It calculates per-SKU net sales as gross sales minus returns/cancellations, with amount/line-count re-verification.
- `QualifiedSkuParetoAdapter`: `CON-01/PARETO_SALES` depends on `SYS/VERIFY_PUBLISHED_FACT_SET`, calls existing pure `ConcentrationAnalysisEngine.pareto`. Emits source fingerprint, reviewed SKU certificate, month, currency and exact SKU shares/cumulative shares.
- `QualifiedSkuAbcAdapter`: `CON-02/ABC_SALES` depends on **CON-01** in the SingleExecutor DAG; reruns the same deterministic pure Pareto engine on the same sealed immutable evidence before applying version-pinned `CON02_ABC_BOUNDARY_V1` (80%/95% with exact unrounded boundary).
- Both adapters are `@Component` auto-discovered but are disabled by default using `fin.methods.con.enabled=false`. Overall `fin.execution.enabled=false` also stays the default. No blanket `releaseQualified=true` in 60-family registry.
- The existing `CompositeCoreReportPublisher` composes both calculated tasks into a separately sealed immutable `CORE_PILOT_V1` report. `coreReadiness=PARTIAL` and `mlReadiness=NOT_REQUESTED`; **not** a financial audit, bank opinion, or approved conclusion.

## Independent synthetic golden

A human-reviewed synthetic F02 revenue for February 2026 is **1,000 KZT** (approved MANAGEMENT basis). Its independently certified SKU ledger:

| Event | SKU | Type | Gross amount | Net effect |
|---|---|---|---:|---:|
| sale-a | A | SALE | 500 | +500 |
| sale-b | B | SALE | 300 | +300 |
| sale-c | C | SALE | 200 | +200 |
| return-c → sale-c | C | RETURN | 50 | -50 |
| sale-d | D | SALE | 50 | +50 |
| **Total** | | | | **1,000** |

Expected analytical result:

| SKU | Net sales | Pareto share | Cumulative | ABC |
|---|---:|---:|---:|---|
| A | 500 | 50% | 50% | A |
| B | 300 | 30% | 80% | A |
| C | 150 | 15% | 95% | B |
| D | 50 | 5% | 100% | C |

`ReviewedSkuSingleExecutorIntegrationTest` (PostgreSQL 16 Testcontainers) covers that `reviewed F02 → reviewed SKU ledger → Outbox → SingleExecutor DAG → two tasks → immutable CORE` path; replays/no duplicate logical tasks, tenant isolation and immutable report. Negative cases: unapproved/missing certificate, self-review, duplicate line IDs, orphan returns, overrefunds, full cancellation conflicting with return, dates outside reporting month, currency and document digest mismatch, incomplete declared totals and wrong published snapshot fingerprint. `QualifiedSkuConcentrationFeatureGateTest` protects default-off scheduling. Existing `ConcentrationAnalysisEngineTest` continues independent math goldens and threshold rounding cases.

## Known P0 product limitations

1. **External proof is not yet qualified**: the database holds a reviewer-attested manifest and document SHA, **not** verified original invoice documents or an audited ERP connector. The schema and review trigger are technical trust gates, not an independent statutory audit. A server-authorized, evidence-backed SKU ingestion UI/API with retained, validated original documents must be implemented before organizational release. Direct database writes in integration tests are not a user workflow.
2. **Late source review is explicitly rejected**: `SingleExecutorScheduler` plans only once per `FACT_SET_PUBLISHED` outbox event. The PostgreSQL review trigger serializes on that same immutable outbox row, so a successful review is guaranteed to precede planning; if planning has already consumed it, approval fails with `SKU_REVIEW_TOO_LATE_NEW_FACT_REVISION_REQUIRED` rather than silently omitting CON. A new approved fact revision or versioned supplemental analysis event/revision workflow is required. Do not conceal this timing dependency.
3. **No multi-period selector**: more than one approved SKU certificate for the same fact revision produces `NOT_APPLICABLE`. This avoids silently choosing one month in a report that lacks explicit reporting-period selection.
4. All returns/cancellations must refer to a sale in the **same reporting month**. Cross-month returns, tax treatment, discounts, multicurrency conversions, unit-of-measure pricing, bundles and split invoices require separately approved accounting policies.
5. Deduplication here is by stable **source_line_ref inside the sealed population**. It does not independently prove two different references represent different external transactions. Invoice-line uniqueness and full coverage must be verified against original evidence at source intake.
6. A single certified SKU population is permitted per fact-set revision and month; source resubmission requires a new reviewed/approved version, not in-place mutation.
7. `FIN-01/02/04`, `CON-01/02` and CORE families remain unqualified. No automated production activation until Java/PostgreSQL and frontend/browser gates on one SHA + independent S2 financial and security review.

## Required release gates

1. Java 21 + Maven + Docker: `mvn -B -ntp -f backend/pom.xml verify` with PostgreSQL Testcontainers. Check Flyway V20261116_07 migration, SQL review triggers, all concurrency/worker-recovery tests and immutable report assertions.
2. GitHub Actions: `verify`, `backend-verification`, `desktop-macos`, `acceptance-gate` GREEN **on one SHA**, not earlier push statuses.
3. Security S2: tenant isolation across SKU data, separate actor authorization for actual SKU submission/review, protection of original documents and client-provided source digests.
4. Accounting S2: decide whether SKUs cover **all** F02 net revenue, how discounts, VAT, cross-period returns, canceled orders and multicomponent sales are handled. Do not silently compare incomparable revenue bases.
5. Add a source-review-to-analysis replan strategy and immutable report revision semantics before exposing a user-facing approval workflow. Extend a versioned `AnalysisManifest` with both F02 fact-set hash and SKU population certificate hash.
6. Only after those qualification gates enable CON adapters, then implement PRO-03 and CASH-01 with independent numerical methods and verified input taxonomies.

**Current decision: `CODED_UNQUALIFIED` / production execution OFF.**
