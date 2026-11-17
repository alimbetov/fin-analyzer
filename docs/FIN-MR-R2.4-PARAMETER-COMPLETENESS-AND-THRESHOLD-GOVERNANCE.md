# FIN-MR-R2.4 — Полнота параметров, порогов и квалификация 60 методик

**Дата:** 2026-11-17 · **Статус:** NORMATIVE REQUIREMENTS / PARAMETER AUDIT SPECIFIED / NOT FINANCIALLY QUALIFIED.  
**Repository/branch:** `alimbetov/fin-analyzer` / `feat/fin-r0-r1a`.  
**Authorities:** [FIN HTAP consolidated](FIN-HTAP-CONSOLIDATED-TZ.md), [ADR-R2-12](ADR-R2-12-VERSIONED-METHODOLOGY-REGISTRY-MODERATION-AND-PARAMETERS.md), [R2.3 editorial presets](FIN-MR-R2.3-EDITORIAL-PRESETS-AND-METHODOLOGY-EDITIONS.md), [FIN-DIAG-60](FIN-DIAG-60-IMPLEMENTATION-TZ.md). This document **adds coverage requirements**; it neither supersedes trusted source/permit contracts nor changes an existing algorithm. Conflicts resolve fail-closed; history and successful migrations remain unchanged.

## 1. Baseline and hard distinctions

The existing database-backed foundation includes 60 `method_family` records, 259 **PROPOSED_UNREVIEWED** method parameter candidates (`MethodologyEntity` / `MethodParameterCandidateEntity`), immutable `method_parameter_contract` and `method_config_version`, moderation/active CAS, and an R2.3 mechanism for 1–10 frozen editorial choices. **These 259 are candidate names, not 259 typed, validated, financially approved or executable options.** CON-01/`PARETO_SALES` and CON-02/`ABC_SALES` have typed preliminary contracts; even these are not automatically qualified for ACTUAL execution. No new variant, preset, threshold default, ACTUAL permit, algorithm, SQL worker or scheduler is approved by this document.

Terminology: family (business intent) ≠ variant (precise formula/source binding) ≠ contract (typed legal parameter set) ≠ configuration (exact immutable values) ≠ edition option (editorial candidate) ≠ S2 approval ≠ execution permit. Candidate key names in the table below are **proposed semantic slots**, not claims of deployed column/JSON keys. Map each to an actual existing one of the 259 candidates by code/semantic identity; only add new candidates through reviewed forward-only migration. No silent renaming or backfill.

## 2. Mandatory parameter contract for each proposed slot

For each `methodCode/variantCode/contractVersion` maintain `parameterCode`, type (DECIMAL, INTEGER, BOOLEAN, ENUM, DATE, PERIOD, DURATION, STRING_CODE, REFERENCE), unit and basis, cardinality, required/nullable, allowlisted enum set, min/max/inclusivity, conditional constraints, dependency/exclusion rules, semantic description, source provenance, ownership, impact class (DIRECT, WINDOW_N, FY_ROLLUP, CROSS_FORM, REPORT_ONLY), sensitivity, calibration evidence, validation/golden fixtures, and change category. For money/percent use decimal strings and explicit units (percent 0..100 vs fraction 0..1), no float. Unitless percentages are invalid. Effective configuration must pin method/variant, contract SHA, code-backed algorithm artifact SHA, parameter SHA, applicable period/grain/calendar/reporting basis and upstream source identity in the result key.

Thresholds may belong to four **different categories**, never interchangeable: (a) mathematical domain bound, (b) classification boundary, (c) user scenario assumption, (d) external policy/covenant. A mathematical constraint (e.g. `0<A<B<100`) is enforced mechanically; illustrative classification limits (e.g. ABC 80/95) are **not** universally mandated; a lender covenant DSCR threshold is *not* a bank norm without exact signed policy/version; reporting cutoffs cannot change upstream populations. Every parameter must declare `defaultPolicy=NONE|EXPLICIT_APPROVED`. NONE is mandatory until independently approved.

## 3. Exhaustive 60-family candidate coverage matrix

The column lists **coverage to investigate and type**, not executable JSON or existing approved defaults. Each row requires a mapping to the current 259 proposals, an implementation capability decision and evidence. A candidate not supported by exact algorithm binding stays `UNSUPPORTED/PROPOSED_UNREVIEWED`.

| Family | Candidate parameters to type/map | Non-negotiable domain checks |
|---|---|---|
| `FIN-01` | `comparisonPeriodType,lag,comparisonMode,zeroBaselinePolicy,coveragePolicy` | matching interval, currency, basis; base zero -> NOT_COMPUTABLE |
| `FIN-02` | `denominatorPopulation,dimensionPolicy,signPolicy,roundingPolicy` | reconciled total; shares may be signed; no double count |
| `FIN-03` | `windowPeriods,minObservations,trendEstimator,baseYearPolicy,seasonalityPolicy` | comparable 3+ intervals; CAGR domain check |
| `FIN-04` | `ratioCode,balanceDatePolicy,averageBalancePolicy,denominatorPolicy,unitPolicy` | per-ratio contract, STOCK/AS_OF alignment |
| `FIN-05` | `decompositionMethod,factorOrder,interactionAllocation,priceVolumeBasis,mixPolicy` | sum effects equals delta within precision |
| `FIN-06` | `indexFormula,basePeriod,basketPolicy,weightPolicy,missingItemPolicy` | compatible price/quantity units and basket |
| `FIN-07` | `profitDefinition,assetAveraging,equityAveraging,leverageDefinition,denominatorPolicy` | DuPont identity with same scope/basis |
| `FIN-08` | `cashClassification,nettingPolicy,openingBalancePolicy,fxTranslationPolicy,roundingPolicy` | opening + net = closing; FX changes explicit |
| `CON-01` | `sortPolicy,populationPolicy,negativeContributionPolicy,tiePolicy,reportCutoffPolicy` | fixed deployed Pareto contract only; reportCutoff NOT executable |
| `CON-02` | `aLimitPercent,bLimitPercent,boundaryCrossingPolicy,populationPolicy,upstreamParetoReceipt` | A<B; upstream receipt is evidence, not config |
| `CON-03` | `observationGrain,minObservations,cvDefinition,zeroMeanPolicy,xLimitPercent,yLimitPercent` | same monthly cohort; sample/pop CV fixed |
| `CON-04` | `abcVariantRef,xyzVariantRef,joinKeyPolicy,missingClassPolicy,cohortAlignment` | same cohort and pinned ABC/XYZ receipts |
| `CON-05` | `marketDefinition,shareDenominator,entityAggregationPolicy,missingSharePolicy,scalePolicy` | normalized shares sum to 1; HHI in [0,1] |
| `CON-06` | `populationDefinition,weightPolicy,negativePolicy,zeroPopulationPolicy,inequalityAlgorithm` | nonnegative population and permutation invariance |
| `PRO-01` | `contributionModel,fixedCostPolicy,variableCostTaxonomy,priceMixPolicy,scenarioVolume` | units and cost allocation reconciled |
| `PRO-02` | `breakEvenBasis,unitContributionPolicy,discreteRounding,capacityLimit,mixPolicy` | positive contribution/unit; rounding for discrete units |
| `PRO-03` | `variableCostTaxonomyVersion,netSalesPolicy,costSignPolicy,marginDenominator,allocations` | CM = net revenue - variable cost |
| `PRO-04` | `unitDefinition,cohortPolicy,costAllocationVersion,refundPolicy,unitCurrency` | full cost and numerator denominator same cohort |
| `PRO-05` | `costPoolDefinition,allocationDriver,driverQuantityPolicy,unallocatedCostPolicy,precisionPolicy` | pool allocations reconcile exactly |
| `PRO-06` | `standardVersion,varianceFormula,quantityBasis,rateBasis,signConvention` | price+usage+mix bridge; planned vs actual comparable |
| `CASH-01` | `dayCountPolicy,balanceAveraging,receivablesPopulation,payablesPopulation,inventoryPopulation` | DSO+DIO-DPO on compatible income flow basis |
| `CASH-02` | `asOfDate,agingAnchor,bucketBoundaries,inclusionPolicy,paymentAllocationPolicy` | non-overlapping complete buckets |
| `CASH-03` | `forecastWeeks,weekCalendar,confidenceMode,commitmentPriority,openingCashPolicy` | 13 week candidate requires date reconciliation |
| `CASH-04` | `operatingCurrentAssetPolicy,operatingCurrentLiabilityPolicy,restrictedCashPolicy,nettingPolicy` | avoid balance-sheet double count |
| `CASH-05` | `shockSet,shockMagnitude,shockTiming,recoveryShape,liquidityFloor` | scenario only; preserve actual source |
| `CASH-06` | `timeBucket,availableLiquidityPolicy,flowNetting,facilityEligibility,gapAlertThreshold` | dated cash gap + funding exposure |
| `BUD-01` | `varianceSignPolicy,variancePercentDenominator,materialityAmount,materialityPercent,comparisonGrain` | approved budget version and actual basis |
| `BUD-02` | `flexDriver,volumeBasis,fixedCostTreatment,rateVersion,mixTreatment` | flexed budget at actual activity volume |
| `BUD-03` | `forecastHorizon,refreshCadence,actualCutoff,scenarioPolicy,forecastVersion` | strict as-of cutoff; no leakage |
| `BUD-04` | `driverSet,driverLag,elasticityVersion,aggregationPolicy,forecastHorizon` | lineage per driver and no circular references |
| `BUD-05` | `activityTaxonomy,justificationRequirements,approvalStages,priorityRubric,reviewCadence` | human approvals mandatory; no fabricated numeric score |
| `BUD-06` | `costCenterHierarchy,allocationKeyVersion,sharedCostPolicy,periodPolicy,roundingAllocation` | all center totals reconcile to ledger |
| `INV-01` | `discountCurve,dayCountConvention,timingConvention,capitalOutlayPolicy,terminalCashPolicy` | dated cash flows; 0-rate case; NPV/XNPV distinct |
| `INV-02` | `rootSearchBounds,multipleRootPolicy,reinvestmentRate,financeRate,irrTolerance` | sign-change constraints and multiple-root warning |
| `INV-03` | `cashflowType,discountRateType,terminalValueMethod,terminalGrowth,forecastHorizon` | FCFF/WACC or FCFE/Ke; g<r where applicable |
| `INV-04` | `capitalWeightBasis,costOfDebt,taxShieldPolicy,costOfEquity,weightNormalization` | weights=1; risk and tax basis explicit |
| `INV-05` | `shockVariable,shockRange,shockStep,interactionMode,outputMetric` | OAT vs multidimensional separate; ceteris paribus |
| `INV-06` | `scenarioSet,scenarioProbability,sharedBaseline,assumptionIsolation,decisionMetric` | separate immutable scenario inputs; probabilities only if used |
| `INV-07` | `distributionFamily,distributionParameters,correlationMatrix,seed,iterations` | PSD dependency, reproducibility, uncertainty intervals |
| `INV-08` | `cfadsDefinition,debtServiceScope,periodCoverage,covenantThreshold,gracePeriodPolicy` | debt service >0; covenant is lender-specific |
| `OPS-01` | `annualDemandPolicy,orderCost,holdingCost,minimumOrderQty,leadTimePolicy` | unit consistency; demand and costs positive |
| `OPS-02` | `serviceLevelMetric,serviceLevelTarget,demandDistribution,leadTimeDistribution,replenishmentPolicy` | cycle service != fill rate; lead-time demand model |
| `OPS-03` | `plannedTimePolicy,availabilityDefinition,performanceReference,qualityDefinition,downtimeTaxonomy` | component ratios 0..1 and shift reconciliation |
| `OPS-04` | `constraintCapacityPolicy,throughputDefinition,workInProcessLimit,optimizationHorizon,bottleneckEvidence` | validated limiting resource; no circular assumptions |
| `OPS-05` | `processBoundary,eventTaxonomy,cycleTimeDefinition,waitTimeDefinition,reviewerEvidence` | human-validated event map, no fabricated timing |
| `OPS-06` | `yieldDefinition,scrapDefinition,reworkTreatment,measurementUnit,processBoundary` | input = good + scrap + WIP/other accounted movements |
| `QLT-01` | `causeTaxonomy,evidenceMinimum,confidenceLabel,reviewStages,causalityPolicy` | qualitative hypotheses not proof of causation |
| `QLT-02` | `maxDepth,stopCriterion,evidenceRequirement,reviewWorkflow,rootCauseDecision` | number five not compulsory; reviewer decision |
| `QLT-03` | `severityScale,occurrenceScale,detectionScale,priorityMethod,actionThreshold` | RPN vs AP are different algorithm policies |
| `QLT-04` | `chartType,subgroupSize,controlLimitMethod,specialCauseRules,specificationLimits` | control limits not spec limits; capability prerequisites |
| `QLT-05` | `baselineWindow,actionOwner,evaluationWindow,successCriterion,approvalWorkflow` | baseline vs after evidence, provenance |
| `QLT-06` | `peerCohort,normalizationBasis,sectorDefinition,referencePeriod,outlierPolicy` | comparable cohort; no cross-basis comparison |
| `STR-01` | `evidenceRubric,quadrantTaxonomy,reviewWorkflow,sourceValidity,priorityPolicy` | reviewed qualitative evidence; scoring optional separate algorithm |
| `STR-02` | `factorTaxonomy,evidenceDatePolicy,sourceReliabilityRubric,reviewWorkflow,scenarioLinkage` | external source freshness and reviewer |
| `STR-03` | `forceRubric,evidenceThreshold,industryBoundary,reviewWorkflow,assessmentHorizon` | expert assessment; no fabricated precision |
| `STR-04` | `perspectiveTaxonomy,kpiDefinition,targetVersion,ownerPolicy,alertTolerance` | KPI trace and owner, target != actual |
| `ADV-01` | `forecastHorizon,trainingWindow,seasonalityPolicy,backtestFolds,errorMetric` | rolling-origin backtest; no look-ahead |
| `ADV-02` | `featureSet,lagPolicy,regularization,validationSplit,missingFeaturePolicy` | feature leakage, collinearity, calibration tests |
| `ADV-03` | `objectiveDefinition,constraintSet,solverTolerance,timeLimit,feasibilityPolicy` | satisfied bounds; optimality gap evidence |
| `ADV-04` | `criteriaScale,weightNormalization,aggregationMethod,missingCriterionPolicy,sensitivityRange` | weight sum=1; rank stability |

## 4. Coverage ledger and truth states

Create a generated evidence matrix keyed by `familyCode,variantCode,contractVersion,parameterCode` with fields: `existingCandidateId | proposedOnly`, `actualStoredCode`, `implementationBinding`, `typedSchemaPresent`, `domainValidatorPresent`, `scenarioCalibrationPresent`, `goldenCaseIds`, `negativeTestIds`, `sourceScopeBound`, `methodS2Evidence`, `status`, `gapReason`. Status states `PROPOSED_UNREVIEWED`, `MAPPED_UNTYPED`, `TYPED_UNIMPLEMENTED`, `IMPLEMENTED_UNQUALIFIED`, `TESTED_UNAPPROVED`, `S2_APPROVED`, `RELEASE_QUALIFIED`, `SUSPENDED`. Never infer a higher state from table existence or green unit tests. A parameter with economic significance cannot be marked `COMPLETE` until domain + data population + golden and negative tests + financial review are evidenced.

**Coverage measures (no fabricated percentages):** family coverage = families with at least one independently reviewed exact variant / 60; parameter coverage = mandatory slots validated and bound / mandatory slots in approved variant contract; variant coverage = reviewed config+algorithm+source binding / declared variants. Report numerator, denominator and unknowns, never collapse these into one headline percentage. Enforce all 60 exact family codes (8 FIN, 6 CON, 6 PRO, 6 CASH, 6 BUD, 8 INV, 6 OPS, 6 QLT, 4 STR, 4 ADV), no extra/missing code and no empty coverage row.

## 5. Version lifecycle and anti-tamper

Use current `fin_calc.method_family`, `method_parameter_contract`, `method_config_version`, `method_config_review`, `method_active_config`, `method_config_activation`, `method_variant_edition` / `option` / `freeze`. **Do not add a competing registry or modify historical Flyway migrations.** For candidate metadata improvements use additive versioned rows/forward-only migration and existing Java `MethodologyEntity` mapping. A newly proposed parameter name never changes an already published contract silently.

1. `DRAFT → VALIDATED → SUBMITTED → REVIEWED_APPROVED/REJECTED → ACTIVE → SUPERSEDED/REVOKED` is **target workflow**, not claimed current schema. R2.2 must bind real authenticated author and separate reviewer identities, prohibit self-review, define linear or branching parent version policy, and audit every decision/revocation with reason, evidence hash and effective interval. Absent these: no moderator write API and no auto-execution.
2. R2.3 edition: 1..10 options **only if supported by current exact contract and algorithm**; CON-01 exactly 1 fixed option, CON-02 up to 10 legal A/B choices with explicit base/step; other 58 no generated choices until typed implementation. Frozen edition immutable, no hidden default; one option does not automatically become selected config.
3. Exact config change: create new `method_config_version`, compute existing PG16 `params_sha256` and `effective_sha256` **server-side**, preserve old artifact and results; change algorithm semantics → independently reviewed algorithmVersion + algorithm SHA. **Do not change existing PG16 hash codec retroactively.** Cross-major portability is a separate codec and migration decision.
4. CAS activation and revocation must be independently authorized; concurrent stale updates reject. Activation is a versioned intent, not compute permission. Result reuse pins tenant/form/period/calendar/currency/source+algorithm+contract+effective config SHA; outbox + plan DAG + ExecutionPermit remain sole execution path.
5. Additive schema changes require fresh-install + populated-upgrade on PostgreSQL 16, stable old hashes, restricted DB grants, and no changes to `ACTUAL` release hold.

## 6. Required tests and release gates

| Test family | Mandatory evidence |
|---|---|
| MR24-01 catalog exhaustiveness | exactly 60 known family codes, compare existing seed and catalog JSON; 259 candidate IDs conserved; explicitly report unmatched requested slots |
| MR24-02 typed contract | no unknown key, untyped enum, wrong unit, 0/100 confusion, hidden default, invalid numeric precision or stale contract SHA |
| MR24-03 boundaries | boundary-1/equal/boundary+1, null/missing/explicit zero/negative, ties, empty cohort, CV zero mean, denominator zero, noncontiguous fiscal coverage |
| MR24-04 semantic constraints | mathematical vs policy threshold separation; CON-01 cutoff does not prune source; CON-02 strict A<B and pinned upstream receipt; invalid cross-variant references rejected |
| MR24-05 variants | R2.3 limits 1..10, CON-01 exactly one, CON-02 deterministic options, other 58 cannot obtain fake executable options |
| MR24-06 hash immutability | server digest invariant for reordered JSONB keys; one significant parameter changes effective SHA; changing algorithm changes result identity; historical config/results immutable |
| MR24-07 review and conflict | independently authenticated actors, no self-review, stale CAS, duplicate activation, revocation, concurrent selection, frozen edition modification rejected |
| MR24-08 execution safety | configuration review and active pointer alone never grant Method S2/ACTUAL; unqualified variant cannot run from HTTP, background worker, legacy publisher or HTAP workspace |
| MR24-09 data semantics | exact tenant/entity/scope/form/PeriodType/Period/Currency/dataSpace/reportingBasis, source mapping epoch, upstream receipts, no double count or cross-tenant cache |
| MR24-10 golden + metamorphic | each implemented variant reproducible under exact snapshot; permutation invariance where required, reconciled totals, sign and unit policies, no invalid financial inference |
| MR24-11 rollout | PostgreSQL Testcontainers fresh/upgrade, rollback plan via forward fix, evidence on one SHA, independent S2; no disabled tests, no fabricated CI GREEN |

**Acceptance policy:** R2.4 specification may be approved as engineering requirements after completeness review; family/variant production readiness stays HOLD until separate financial S2, trusted source, execution, CI and release gates. An unimplemented candidate should remain visibly **UNIMPLEMENTED**, not be given an invented default to reach coverage 100%.

## 7. Delivery sequence (single branch)

- **R2.4-A**: read 259 actual `method_parameter_candidate` rows; produce 60-row diff from section 3 with actual IDs/keys, duplicate/alias resolution, typed gap ledger. No application-code claims before this inventory.
- **R2.4-B**: prioritized validated parameter contracts for currently wired five business variants, starting with existing CON-01/CON-02 and exact input/algorithm provenance. Extend one variant at a time; no fake 60 executions.
- **R2.4-C**: independent S2 of economic meanings and ranges, sensitivity/golden fixtures; author/moderator identity and revocation hardening in R2.2.
- **R2.4-D**: persisted immutable configs/editions validated by existing hashes/DB constraints, plan-key integration through FIN-BE-R2 and FIN HTAP gates; no production switch until W8.
- **R2.4-E**: recurring automated contract-to-implementation drift report in CI, negative tests and published evidence on one exact SHA.

**Explicit non-goals:** 60 new SQL procedures, default 10 presets for every family, generic unrestricted formula DSL, unreviewed numeric norms, release of ACTUAL/production execution, frontend changes.

## 8. Implementation evidence (2026-11-17)

**R2.4-A first backend increment — IMPLEMENTED / CI NOT VERIFIED:** forward-only migration `backend/src/main/resources/db/migration/V20261117_11__method_parameter_coverage_read_model.sql` defines `fin_calc.method_parameter_coverage_view`, one method-family row with candidate and unreviewed counts, contract counts, typed-profile instance counts and two deliberately **false** authorization fields. Its migration-time assertion aborts if 60 family records, 259 unreviewed candidate records, or full family distribution drift. This is read-only observability, not a typed parameter validator or ACTUAL permit.

**Regression test added (execution not claimed):** `backend/src/test/java/kz/fin/methodology/MethodParameterCoverageViewTest.java` uses PostgreSQL 16 Testcontainers and Flyway fresh installation to check 60/259 counts, no implied S2/permit and family-specific information. R2.1 introduced **validation profiles without inserting contract instances**; the test explicitly does not assume two activated/selected typed contracts.

**Still OPEN:** exact 259-to-R2.4 semantic-slot reconciliation, approved typed contracts for remaining variants, additional mathematical boundary tests, moderator actor/revocation workflow, populated schema upgrade proof, same-SHA CI GREEN, independent S2 and all runtime planning. Do not mark R2.4-B/C/D complete.

