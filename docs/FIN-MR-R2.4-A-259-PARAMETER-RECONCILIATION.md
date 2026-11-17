# FIN-MR-R2.4-A — Actual Seed vs Proposed Semantic Slots (exact-key reconciliation)

**Date:** 2026-11-17 · **Status:** MACHINE-COMPUTED LEXICAL INVENTORY / SEMANTIC REVIEW PENDING.  
**Only branch:** `feat/fin-r0-r1a`. **Inputs:** immutable `V20261117_05__method_parameter_candidates.sql` and `FIN-MR-R2.4-PARAMETER-COMPLETENESS-AND-THRESHOLD-GOVERNANCE.md`. This is **not** a typed-contract or financial S2 approval.

There are **60** existing seeded method families with **259** proposed parameter names; the new R2.4 scope has **299 candidate semantic slots**. Exactly **29** R2.4 slots have same-name keys in the historical seed; the remaining **270** are **not exact name matches**, which may include semantic aliases rather than missing functionality. Manual one-to-one economic mapping is required before introducing any new candidate, changing the type or adding a default. An exact name match does not prove a valid contract or runtime support.

| Method | Existing V05 parameter codes | R2.4 proposed slots without exact match | Exact same-name keys |
|---|---|---|---|
| `FIN-01` | `comparisonMetric`, `comparisonMode`, `lagPeriods`, `zeroBaselinePolicy`, `periodAlignmentPolicy` | `comparisonPeriodType`, `lag`, `coveragePolicy` | `comparisonMode`, `zeroBaselinePolicy` |
| `FIN-02` | `dimensionKey`, `denominatorMetric`, `populationPolicy`, `negativeReturnsPolicy`, `sharePrecision` | `denominatorPopulation`, `dimensionPolicy`, `signPolicy`, `roundingPolicy` | — |
| `FIN-03` | `trendHorizonYears`, `trendEstimator`, `missingPeriodsPolicy`, `cagrBasePolicy` | `windowPeriods`, `minObservations`, `baseYearPolicy`, `seasonalityPolicy` | `trendEstimator` |
| `FIN-04` | `ratioVariant`, `closingDatePolicy`, `zeroDenominatorPolicy`, `stockFlowPolicy` | `ratioCode`, `balanceDatePolicy`, `averageBalancePolicy`, `denominatorPolicy`, `unitPolicy` | — |
| `FIN-05` | `factorModel`, `baselinePeriod`, `mixTreatment`, `interactionAllocationPolicy` | `decompositionMethod`, `factorOrder`, `interactionAllocation`, `priceVolumeBasis`, `mixPolicy` | — |
| `FIN-06` | `indexFormula`, `basePeriod`, `basketCompositionPolicy`, `weightingPolicy` | `basketPolicy`, `weightPolicy`, `missingItemPolicy` | `indexFormula`, `basePeriod` |
| `FIN-07` | `dupontVariant`, `averageBalancePolicy`, `equityDefinitionPolicy`, `netProfitPolicy` | `profitDefinition`, `assetAveraging`, `equityAveraging`, `leverageDefinition`, `denominatorPolicy` | — |
| `FIN-08` | `cashFlowPresentation`, `cashCategoryMapVersion`, `openingClosingPolicy`, `reconciliationTolerance` | `cashClassification`, `nettingPolicy`, `openingBalancePolicy`, `fxTranslationPolicy`, `roundingPolicy` | — |
| `CON-01` | `populationMetric`, `tieSortPolicy`, `returnsPolicy`, `reportCutoffPercent` | `sortPolicy`, `populationPolicy`, `negativeContributionPolicy`, `tiePolicy`, `reportCutoffPolicy` | — |
| `CON-02` | `aLimitPercent`, `bLimitPercent`, `boundaryCrossingPolicy`, `sourceParetoVersion` | `populationPolicy`, `upstreamParetoReceipt` | `aLimitPercent`, `bLimitPercent`, `boundaryCrossingPolicy` |
| `CON-03` | `historyMonths`, `cvDenominatorPolicy`, `xyzBoundaryPolicy`, `zeroMeanPolicy`, `missingPeriodsPolicy` | `observationGrain`, `minObservations`, `cvDefinition`, `xLimitPercent`, `yLimitPercent` | `zeroMeanPolicy` |
| `CON-04` | `abcConfigVersion`, `xyzConfigVersion`, `scopeReconciliationPolicy`, `matrixClassificationPolicy` | `abcVariantRef`, `xyzVariantRef`, `joinKeyPolicy`, `missingClassPolicy`, `cohortAlignment` | — |
| `CON-05` | `marketPopulationPolicy`, `shareNormalizationPolicy`, `negativeAmountsPolicy`, `outputScale` | `marketDefinition`, `shareDenominator`, `entityAggregationPolicy`, `missingSharePolicy`, `scalePolicy` | — |
| `CON-06` | `populationDefinition`, `weightingPolicy`, `zeroPopulationPolicy`, `lorenzInterpolationPolicy` | `weightPolicy`, `negativePolicy`, `inequalityAlgorithm` | `populationDefinition`, `zeroPopulationPolicy` |
| `PRO-01` | `costTaxonomyVersion`, `unitMeasurePolicy`, `relevantRangePolicy`, `fixedCostAllocationPolicy` | `contributionModel`, `fixedCostPolicy`, `variableCostTaxonomy`, `priceMixPolicy`, `scenarioVolume` | — |
| `PRO-02` | `targetProfitPolicy`, `unitRoundingPolicy`, `capacityConstraintPolicy`, `fixedCostCoverage` | `breakEvenBasis`, `unitContributionPolicy`, `discreteRounding`, `capacityLimit`, `mixPolicy` | — |
| `PRO-03` | `variableCostTaxonomyVersion`, `negativeRevenuePolicy`, `outputPrecision`, `returnsTreatmentPolicy` | `netSalesPolicy`, `costSignPolicy`, `marginDenominator`, `allocations` | `variableCostTaxonomyVersion` |
| `PRO-04` | `unitOfAnalysis`, `costAllocationPolicy`, `returnsTreatmentPolicy`, `fixedCostTreatmentPolicy` | `unitDefinition`, `cohortPolicy`, `costAllocationVersion`, `refundPolicy`, `unitCurrency` | — |
| `PRO-05` | `costPoolVersion`, `costDriverMapVersion`, `unallocatedCostPolicy`, `allocationPrecision` | `costPoolDefinition`, `allocationDriver`, `driverQuantityPolicy`, `precisionPolicy` | `unallocatedCostPolicy` |
| `PRO-06` | `varianceDecomposition`, `signConventionPolicy`, `standardCostVersion`, `materialityThreshold` | `standardVersion`, `varianceFormula`, `quantityBasis`, `rateBasis`, `signConvention` | — |
| `CASH-01` | `dayCountPolicy`, `averageBalancePolicy`, `purchasesSourcePolicy`, `annualPeriodPolicy` | `balanceAveraging`, `receivablesPopulation`, `payablesPopulation`, `inventoryPopulation` | `dayCountPolicy` |
| `CASH-02` | `agingAsOfDate`, `bucketBoundaries`, `overdueStartPolicy`, `partialPaymentPolicy`, `gracePeriodDays` | `asOfDate`, `agingAnchor`, `inclusionPolicy`, `paymentAllocationPolicy` | `bucketBoundaries` |
| `CASH-03` | `forecastHorizonWeeks`, `weekStartPolicy`, `collectionProbabilityPolicy`, `openingBalancePolicy`, `scenarioVersion` | `forecastWeeks`, `weekCalendar`, `confidenceMode`, `commitmentPriority`, `openingCashPolicy` | — |
| `CASH-04` | `workingCapitalDefinition`, `nonOperatingExclusions`, `nettingPolicy`, `balanceDatePolicy` | `operatingCurrentAssetPolicy`, `operatingCurrentLiabilityPolicy`, `restrictedCashPolicy` | `nettingPolicy` |
| `CASH-05` | `stressScenarioVersion`, `shockMagnitudePolicy`, `stressHorizon`, `liquidityFloorPolicy`, `recoveryPolicy` | `shockSet`, `shockMagnitude`, `shockTiming`, `recoveryShape`, `liquidityFloor` | — |
| `CASH-06` | `timeBucketPolicy`, `liquidityAvailabilityPolicy`, `horizonPeriods`, `nettingPolicy` | `timeBucket`, `availableLiquidityPolicy`, `flowNetting`, `facilityEligibility`, `gapAlertThreshold` | — |
| `BUD-01` | `budgetBaselineVersion`, `varianceSignPolicy`, `materialityThreshold`, `percentageBasePolicy` | `variancePercentDenominator`, `materialityAmount`, `materialityPercent`, `comparisonGrain` | `varianceSignPolicy` |
| `BUD-02` | `volumeDriverMapVersion`, `fixedVariableSplitPolicy`, `flexFormulaVersion`, `reconciliationTolerance` | `flexDriver`, `volumeBasis`, `fixedCostTreatment`, `rateVersion`, `mixTreatment` | — |
| `BUD-03` | `forecastHorizonPeriods`, `rollingStep`, `actualsCutoffPolicy`, `seasonalityPolicy` | `forecastHorizon`, `refreshCadence`, `actualCutoff`, `scenarioPolicy`, `forecastVersion` | — |
| `BUD-04` | `driverModelVersion`, `driverLagPolicy`, `calibrationWindow`, `planningHorizon` | `driverSet`, `driverLag`, `elasticityVersion`, `aggregationPolicy`, `forecastHorizon` | — |
| `BUD-05` | `activityTaxonomyVersion`, `justificationPolicy`, `approvalWorkflowVersion`, `baselineExceptionPolicy` | `activityTaxonomy`, `justificationRequirements`, `approvalStages`, `priorityRubric`, `reviewCadence` | — |
| `BUD-06` | `costCenterHierarchyVersion`, `allocationDriverPolicy`, `residualAllocationPolicy`, `intercenterEliminationPolicy` | `costCenterHierarchy`, `allocationKeyVersion`, `sharedCostPolicy`, `periodPolicy`, `roundingAllocation` | — |
| `INV-01` | `discountRatePolicy`, `valuationDate`, `dayCountConvention`, `compoundingConvention`, `cashFlowTimingPolicy` | `discountCurve`, `timingConvention`, `capitalOutlayPolicy`, `terminalCashPolicy` | `dayCountConvention` |
| `INV-02` | `rootFindingTolerance`, `rootSelectionPolicy`, `reinvestmentRate`, `financingRate`, `multipleRootsPolicy` | `rootSearchBounds`, `multipleRootPolicy`, `financeRate`, `irrTolerance` | `reinvestmentRate` |
| `INV-03` | `cashFlowBasis`, `discountRateBasis`, `terminalValueModel`, `forecastHorizonYears`, `terminalGrowthPolicy` | `cashflowType`, `discountRateType`, `terminalValueMethod`, `terminalGrowth`, `forecastHorizon` | — |
| `INV-04` | `capitalWeightsPolicy`, `costOfEquityModel`, `taxShieldPolicy`, `debtCostPolicy` | `capitalWeightBasis`, `costOfDebt`, `costOfEquity`, `weightNormalization` | `taxShieldPolicy` |
| `INV-05` | `sensitivityVariables`, `shockRangePolicy`, `gridStepPolicy`, `oneAtATimePolicy` | `shockVariable`, `shockRange`, `shockStep`, `interactionMode`, `outputMetric` | — |
| `INV-06` | `scenarioSetVersion`, `scenarioProbabilityPolicy`, `assumptionOverridePolicy`, `comparisonBasis` | `scenarioSet`, `scenarioProbability`, `sharedBaseline`, `assumptionIsolation`, `decisionMetric` | — |
| `INV-07` | `distributionCatalogVersion`, `randomSeed`, `simulationIterations`, `correlationPolicy`, `convergencePolicy` | `distributionFamily`, `distributionParameters`, `correlationMatrix`, `seed`, `iterations` | — |
| `INV-08` | `cfadsDefinitionVersion`, `debtServiceDefinition`, `dscrThreshold`, `measurementHorizon`, `covenantPolicy` | `cfadsDefinition`, `debtServiceScope`, `periodCoverage`, `covenantThreshold`, `gracePeriodPolicy` | — |
| `OPS-01` | `demandHorizon`, `orderCostPolicy`, `holdingCostPolicy`, `lotRoundingPolicy` | `annualDemandPolicy`, `orderCost`, `holdingCost`, `minimumOrderQty`, `leadTimePolicy` | — |
| `OPS-02` | `serviceLevelTarget`, `demandDistributionPolicy`, `leadTimePolicy`, `reviewPeriodPolicy` | `serviceLevelMetric`, `demandDistribution`, `leadTimeDistribution`, `replenishmentPolicy` | `serviceLevelTarget` |
| `OPS-03` | `plannedProductionTimePolicy`, `idealCycleTimeVersion`, `microStopTreatment`, `qualityUnitsPolicy` | `plannedTimePolicy`, `availabilityDefinition`, `performanceReference`, `qualityDefinition`, `downtimeTaxonomy` | — |
| `OPS-04` | `bottleneckSelectionRule`, `throughputDefinition`, `constraintSetVersion`, `allocationPriority` | `constraintCapacityPolicy`, `workInProcessLimit`, `optimizationHorizon`, `bottleneckEvidence` | `throughputDefinition` |
| `OPS-05` | `processStepSchemaVersion`, `timeUnit`, `touchVsWaitPolicy`, `humanSignoffPolicy` | `processBoundary`, `eventTaxonomy`, `cycleTimeDefinition`, `waitTimeDefinition`, `reviewerEvidence` | — |
| `OPS-06` | `yieldDenominatorPolicy`, `scrapTaxonomyVersion`, `reworkTreatmentPolicy`, `unitReconciliationPolicy` | `yieldDefinition`, `scrapDefinition`, `reworkTreatment`, `measurementUnit`, `processBoundary` | — |
| `QLT-01` | `causeTaxonomyVersion`, `evidenceMinimumPolicy`, `hypothesisStatusPolicy`, `moderationWorkflow` | `causeTaxonomy`, `evidenceMinimum`, `confidenceLabel`, `reviewStages`, `causalityPolicy` | — |
| `QLT-02` | `investigationDepthPolicy`, `causalEvidencePolicy`, `reviewWorkflow`, `stoppingRulePolicy` | `maxDepth`, `stopCriterion`, `evidenceRequirement`, `rootCauseDecision` | `reviewWorkflow` |
| `QLT-03` | `severityScaleVersion`, `occurrenceScaleVersion`, `detectionScaleVersion`, `riskPriorityPolicy`, `actionThresholdPolicy` | `severityScale`, `occurrenceScale`, `detectionScale`, `priorityMethod`, `actionThreshold` | — |
| `QLT-04` | `chartType`, `subgroupSize`, `controlLimitPolicy`, `signalRuleSetVersion`, `measurementCadence` | `controlLimitMethod`, `specialCauseRules`, `specificationLimits` | `chartType`, `subgroupSize` |
| `QLT-05` | `reviewCyclePolicy`, `baselineKpiPolicy`, `targetPolicy`, `effectWindowPolicy`, `approvalWorkflow` | `baselineWindow`, `actionOwner`, `evaluationWindow`, `successCriterion` | `approvalWorkflow` |
| `QLT-06` | `peerCohortPolicy`, `normalizationPolicy`, `periodMatchingPolicy`, `confidentialityPolicy` | `peerCohort`, `normalizationBasis`, `sectorDefinition`, `referencePeriod`, `outlierPolicy` | — |
| `STR-01` | `evidenceTaxonomyVersion`, `assessmentHorizon`, `moderationWorkflow`, `qualitativeScoringPolicy` | `evidenceRubric`, `quadrantTaxonomy`, `reviewWorkflow`, `sourceValidity`, `priorityPolicy` | — |
| `STR-02` | `factorTaxonomyVersion`, `evidenceFreshnessPolicy`, `marketScopePolicy`, `scenarioHorizon` | `factorTaxonomy`, `evidenceDatePolicy`, `sourceReliabilityRubric`, `reviewWorkflow`, `scenarioLinkage` | — |
| `STR-03` | `industryScopePolicy`, `forceEvidenceFramework`, `assessmentScalePolicy`, `reviewWorkflow` | `forceRubric`, `evidenceThreshold`, `industryBoundary`, `assessmentHorizon` | `reviewWorkflow` |
| `STR-04` | `perspectivesVersion`, `kpiContractVersion`, `targetVersion`, `refreshCadence`, `ownerAccountabilityPolicy` | `perspectiveTaxonomy`, `kpiDefinition`, `ownerPolicy`, `alertTolerance` | `targetVersion` |
| `ADV-01` | `modelFamily`, `forecastHorizon`, `minTrainingPeriods`, `backtestFoldPolicy`, `predictionIntervalPolicy` | `trainingWindow`, `seasonalityPolicy`, `backtestFolds`, `errorMetric` | `forecastHorizon` |
| `ADV-02` | `targetMetricCode`, `featureRegistryVersion`, `timeSplitPolicy`, `regularizationPolicy`, `collinearityThreshold` | `featureSet`, `lagPolicy`, `regularization`, `validationSplit`, `missingFeaturePolicy` | — |
| `ADV-03` | `objectiveVersion`, `constraintSetVersion`, `solverVersion`, `optimalityGapLimit`, `solverTimeoutPolicy` | `objectiveDefinition`, `constraintSet`, `solverTolerance`, `timeLimit`, `feasibilityPolicy` | — |
| `ADV-04` | `criteriaCatalogVersion`, `weightNormalizationPolicy`, `decisionRuleVersion`, `sensitivityPolicy`, `missingAlternativePolicy` | `criteriaScale`, `weightNormalization`, `aggregationMethod`, `missingCriterionPolicy`, `sensitivityRange` | — |

## Required next action and proof

For every unmatched proposed semantic slot, categorize as `ALIAS_OF_EXISTING` (with actual candidate ID, semantics and reviewer), `NEW_CANDIDATE_REQUIRED` (forward-only additive migration), `IMPLEMENTATION_OWNED_NOT_CONFIGURABLE` (algorithm version / lineage instead of user setting), or `REJECTED` (with rationale). Mark every exact name match `LEXICAL_ONLY` until typed validation + true runtime binding + negative/golden tests. Do not update immutable 259 proposal rows or seed unknown values. The R2.3 bound of 1–10 presets remains only for qualified, typed algorithms.
