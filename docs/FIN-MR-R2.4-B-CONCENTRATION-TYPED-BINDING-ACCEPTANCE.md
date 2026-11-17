# FIN-MR-R2.4-B — CON-01/CON-02 typed binding and boundary acceptance

**Date:** 2026-11-17 · **Branch:** `feat/fin-r0-r1a` · **Status:** IMPLEMENTED CODE CHANGE / RUN EVIDENCE PENDING / S2 HOLD.

## Scope and fact-based parameters

The *pre-existing* typed profiles `CON01_PARETO_FIXED_V1` and `CON02_ABC_TYPED_V2` remain unchanged. Their SQL and Java validators are enforced in `V20261117_07` and `ConcentrationTypedParameters`. Existing immutable `V20261117_05` names have not been edited.

| Method | Original candidate key | Binding | Allowed configuration policy |
|---|---|---|---|
| CON-01 | populationMetric | CONFIG_TYPED | NET_SALES only |
| CON-01 | tieSortPolicy | CONFIG_TYPED | AMOUNT_DESC_SKU_CODE_ASC only |
| CON-01 | returnsPolicy | CONFIG_TYPED | REVIEWED_NET_RECONCILED only |
| CON-01 | reportCutoffPercent | UNSUPPORTED_REPORT_ONLY | Not allowed in executable config; no population pruning |
| CON-02 | aLimitPercent | CONFIG_TYPED | DECIMAL, 0 < A < B < 100 (percent points) |
| CON-02 | bLimitPercent | CONFIG_TYPED | DECIMAL, 0 < A < B < 100 (percent points) |
| CON-02 | boundaryCrossingPolicy | CONFIG_TYPED | CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED only |
| CON-02 | sourceParetoVersion | UPSTREAM_RESULT_LINEAGE | Only verified CON-01/PARETO_SALES upstream result; never client config |

These are **8 exact historical candidate codes**, not proposed aliases. 6 may be typed-config fields under the referenced schema; the other 2 must never appear in executable config. Other 58 family contracts remain as-before. `CON01_PARETO_V1` and `CON02_ABC_BOUNDARY_V1` are existing implementation versions; there is no new algorithm released here.

## Persistent enforcement and evidence

`V20261117_12__concentration_parameter_binding_r24b.sql` adds immutable-by-definition read-only view `fin_calc.concentration_parameter_binding_v1`, keyed by `methodCode/variantCode/candidateCode` and joined against the immutable seed. Migration-time checks fail on absent/mismapped candidates, incorrect 6/1/1 cardinality, unexpectedly expanded `configProperties`, weakened Pareto lineage or unsupported cutoff semantics, or changed candidate review state. New view's `approved_by_binding` and `executable_by_binding` are permanently **false**; view rows provide evidence, not an approval decision.

Existing config insertion validation remains `fin_calc.validate_method_config_insert`. Java `ConcentrationTypedParameters` remains a pure decoder. No new `method_config_version`/review/active pointer or S2 permit is inserted. Historic `params_sha256` and `effective_sha256` algorithms are not altered. R2.3 edited presets are still capped 1..10 and remain non-executable without independent source/config/S2 permission.

## Regression added

`ConcentrationTypedContractPostgresIntegrationTest` now includes:
- `r24bSemanticBindingRejectsFalseConfigAndStillGrantsNoPermit`: inspect 8 rows and their 6/1/1 split, deny cutoff injection and caller-controlled upstream version, assert no approval/permit.
- `r24bGoldenBoundariesUseExactContributionsAndNotRoundedDisplay`: synthetic Pareto input 79.999999999999, 15.000000000001, 5.000000000000 of 100.000000000000; existing crossing-item policy at A=80/B=95 gives A/A/C; at A=79.999999999998/B=95 gives A/B/C. Check invalid A=0,100,-0.1. The exact 95% boundary assigns following item to C, not B.

Existing `ConcentrationTypedContractPostgresIntegrationTest` cases also cover: typed schema rejection, zero/duplicate invalid ABC thresholds, algorithm compatibility, input lineage, config SHA changes, immutable history and no ACTUAL permission.

## Gates and remaining work

**Not established:** successful local Maven run, GitHub Actions GREEN for latest SHA, populated upgrade, independent S2, signed algorithm artifact, authenticated moderator identity, fully verified trusted upstream Pareto receipt, or runtime adapter permit. Do not promote `CON-01`/`CON-02` to RELEASE_QUALIFIED or ACTUAL.

**Next:** run PostgreSQL16 Testcontainers `ConcentrationTypedContractPostgresIntegrationTest`, `MethodParameterCoverageViewTest`, `MethodVariantEditionPresetPostgresIntegrationTest` on same SHA; prove clean and populated Flyway upgrades and capture CI evidence; then audit remaining three wired business variants with their actual module contracts. Avoid creating defaults for unimplemented 58 families.
