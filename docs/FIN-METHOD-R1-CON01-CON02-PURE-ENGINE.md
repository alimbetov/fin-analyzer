# FIN METHOD-R1 — CON-01 Pareto and CON-02 ABC pure-engine qualification slice

**Date:** 2026-11-16 · **Branch:** `feat/fin-r0-r1a`  
**Status:** `CODED / GOLDENS_AUTHORED / NO_REAL_SKU_ADAPTER / CI_NOT_VERIFIED / RELEASE_HOLD`.

## What the code does

`ConcentrationAnalysisEngine` is a **pure, deterministic** calculation module. It cannot read organization data, claim an approved source revision, publish a report, or run in `SingleExecutor`. Such integration is intentionally disabled until a trusted, full SKU-sales population adapter exists.

- CON-01 `pareto`: requires a server-verified expected population total, 1–10,000 uniquely identified SKU codes, nonnegative exact monetary amounts, and **exact** total-to-population reconciliation.
- Sort by value descending, with stable machine-code ascending tie break; calculate contribution (%) and cumulative contribution (%) independently from the sum, using `BigDecimal`, 12 decimal places and HALF_EVEN.
- CON-02 `abc`: versioned `CON02_ABC_BOUNDARY_V1` policy, 80%/95%. A boundary-crossing SKU remains in the preceding class; following SKU moves to the next class. **Boundary selection uses unrounded cumulative amounts**, not displayed shares, preventing drift just below the cutoffs.
- Zero total, negative amounts, duplicate SKU, unreconciled/partial population and malformed IDs are rejected. `Pareto`/`AbcResult` return immutable rows.

## Independent synthetic goldens

`ConcentrationAnalysisEngineTest` asserts:

| SKU | Amount | Contribution | Cumulative | ABC |
|---|---:|---:|---:|:---:|
| A | 500 | 50% | 50% | A |
| B | 300 | 30% | 80% | A |
| C | 150 | 15% | 95% | B |
| D | 50 | 5% | 100% | C |

Additional tests: unsorted input, equal-value tie stability, threshold-crossing semantics, exact-third nonterminating percentages, zero/negative/duplicate/unreconciled cases, immutability, and a near-80% regression where displayed 80.000000000000% must not incorrectly shift ABC class.

## What remains before CON-01/02 can run

1. Implement **trust-qualified SKU economic facts**, including sales/returns cancellations, period, currency, UOM, unit prices, region and SKU identity, approved invoices and source-to-fact lineage.
2. Build a versioned server-side population validator proving completeness and no transaction-level double count. A manually supplied total matching a partial subset is not sufficient proof.
3. Implement a gated `MethodTaskAdapter` depending on `SYS/VERIFY_PUBLISHED_FACT_SET`, `FIN_METHODS_CON_ENABLED=false` default, a version-pinned `AnalysisManifest` and depend CON-02 on CON-01.
4. Independent PostgreSQL Testcontainers golden/negative proof for two tenants, mixed periods/currencies, returns, ties, threshold cases and crash/retry fencing.
5. Extend immutable CORE with provenance and applicability, not just numbers. Do not claim causality or auto-execute unqualified rule interpretations.
6. Verify Node/React, Java/PostgreSQL and macOS browser CI on **one new SHA**, then independent economic S2 review.

**Release decision:** `CON-01/02 CODE CANDIDATE`, `autoRunAllowed=false`, `releaseQualified=false`.
