-- FIN HTAP MR-R1 — 60 Methodology entities -> 259 candidate parameter rows.
-- These 259 names came from the documented FIN HTAP methodology completeness matrix.
-- Candidate metadata is NOT a valid execution parameter contract or financial S2.
-- Do not change the existing moderated/config-version tables or historic migrations.
CREATE TABLE fin_calc.method_parameter_candidate (
    candidate_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    method_code VARCHAR(32) NOT NULL
        REFERENCES fin_calc.method_family(method_code),
    parameter_code VARCHAR(128) NOT NULL
        CHECK (parameter_code ~ '^[A-Za-z][A-Za-z0-9]{1,127}$'),
    source_registry VARCHAR(80) NOT NULL
        DEFAULT 'FIN_HTAP_PARAMETER_MATRIX_R1'
        CHECK (source_registry='FIN_HTAP_PARAMETER_MATRIX_R1'),
    proposed_type VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED'
        CHECK(proposed_type IN ('UNSPECIFIED','DECIMAL','INTEGER','BOOLEAN','ENUM','DATE','JSON','STRING')),
    review_state VARCHAR(32) NOT NULL DEFAULT 'PROPOSED_UNREVIEWED'
        CHECK(review_state='PROPOSED_UNREVIEWED'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_method_candidate UNIQUE(method_code,parameter_code)
);
CREATE INDEX idx_fin_method_candidate_method ON fin_calc.method_parameter_candidate(method_code,parameter_code);

-- A zero-configuration *catalogue* row is valid: no invented defaults,
-- no execution permit. Keep historical definition candidates immutable.
WITH candidate_source(method_code, parameter_csv) AS (
  VALUES
  ('FIN-01', 'comparisonMetric,comparisonMode,lagPeriods,zeroBaselinePolicy,periodAlignmentPolicy'),
  ('FIN-02', 'dimensionKey,denominatorMetric,populationPolicy,negativeReturnsPolicy,sharePrecision'),
  ('FIN-03', 'trendHorizonYears,trendEstimator,missingPeriodsPolicy,cagrBasePolicy'),
  ('FIN-04', 'ratioVariant,closingDatePolicy,zeroDenominatorPolicy,stockFlowPolicy'),
  ('FIN-05', 'factorModel,baselinePeriod,mixTreatment,interactionAllocationPolicy'),
  ('FIN-06', 'indexFormula,basePeriod,basketCompositionPolicy,weightingPolicy'),
  ('FIN-07', 'dupontVariant,averageBalancePolicy,equityDefinitionPolicy,netProfitPolicy'),
  ('FIN-08', 'cashFlowPresentation,cashCategoryMapVersion,openingClosingPolicy,reconciliationTolerance'),
  ('CON-01', 'populationMetric,tieSortPolicy,returnsPolicy,reportCutoffPercent'),
  ('CON-02', 'aLimitPercent,bLimitPercent,boundaryCrossingPolicy,sourceParetoVersion'),
  ('CON-03', 'historyMonths,cvDenominatorPolicy,xyzBoundaryPolicy,zeroMeanPolicy,missingPeriodsPolicy'),
  ('CON-04', 'abcConfigVersion,xyzConfigVersion,scopeReconciliationPolicy,matrixClassificationPolicy'),
  ('CON-05', 'marketPopulationPolicy,shareNormalizationPolicy,negativeAmountsPolicy,outputScale'),
  ('CON-06', 'populationDefinition,weightingPolicy,zeroPopulationPolicy,lorenzInterpolationPolicy'),
  ('PRO-01', 'costTaxonomyVersion,unitMeasurePolicy,relevantRangePolicy,fixedCostAllocationPolicy'),
  ('PRO-02', 'targetProfitPolicy,unitRoundingPolicy,capacityConstraintPolicy,fixedCostCoverage'),
  ('PRO-03', 'variableCostTaxonomyVersion,negativeRevenuePolicy,outputPrecision,returnsTreatmentPolicy'),
  ('PRO-04', 'unitOfAnalysis,costAllocationPolicy,returnsTreatmentPolicy,fixedCostTreatmentPolicy'),
  ('PRO-05', 'costPoolVersion,costDriverMapVersion,unallocatedCostPolicy,allocationPrecision'),
  ('PRO-06', 'varianceDecomposition,signConventionPolicy,standardCostVersion,materialityThreshold'),
  ('CASH-01', 'dayCountPolicy,averageBalancePolicy,purchasesSourcePolicy,annualPeriodPolicy'),
  ('CASH-02', 'agingAsOfDate,bucketBoundaries,overdueStartPolicy,partialPaymentPolicy,gracePeriodDays'),
  ('CASH-03', 'forecastHorizonWeeks,weekStartPolicy,collectionProbabilityPolicy,openingBalancePolicy,scenarioVersion'),
  ('CASH-04', 'workingCapitalDefinition,nonOperatingExclusions,nettingPolicy,balanceDatePolicy'),
  ('CASH-05', 'stressScenarioVersion,shockMagnitudePolicy,stressHorizon,liquidityFloorPolicy,recoveryPolicy'),
  ('CASH-06', 'timeBucketPolicy,liquidityAvailabilityPolicy,horizonPeriods,nettingPolicy'),
  ('BUD-01', 'budgetBaselineVersion,varianceSignPolicy,materialityThreshold,percentageBasePolicy'),
  ('BUD-02', 'volumeDriverMapVersion,fixedVariableSplitPolicy,flexFormulaVersion,reconciliationTolerance'),
  ('BUD-03', 'forecastHorizonPeriods,rollingStep,actualsCutoffPolicy,seasonalityPolicy'),
  ('BUD-04', 'driverModelVersion,driverLagPolicy,calibrationWindow,planningHorizon'),
  ('BUD-05', 'activityTaxonomyVersion,justificationPolicy,approvalWorkflowVersion,baselineExceptionPolicy'),
  ('BUD-06', 'costCenterHierarchyVersion,allocationDriverPolicy,residualAllocationPolicy,intercenterEliminationPolicy'),
  ('INV-01', 'discountRatePolicy,valuationDate,dayCountConvention,compoundingConvention,cashFlowTimingPolicy'),
  ('INV-02', 'rootFindingTolerance,rootSelectionPolicy,reinvestmentRate,financingRate,multipleRootsPolicy'),
  ('INV-03', 'cashFlowBasis,discountRateBasis,terminalValueModel,forecastHorizonYears,terminalGrowthPolicy'),
  ('INV-04', 'capitalWeightsPolicy,costOfEquityModel,taxShieldPolicy,debtCostPolicy'),
  ('INV-05', 'sensitivityVariables,shockRangePolicy,gridStepPolicy,oneAtATimePolicy'),
  ('INV-06', 'scenarioSetVersion,scenarioProbabilityPolicy,assumptionOverridePolicy,comparisonBasis'),
  ('INV-07', 'distributionCatalogVersion,randomSeed,simulationIterations,correlationPolicy,convergencePolicy'),
  ('INV-08', 'cfadsDefinitionVersion,debtServiceDefinition,dscrThreshold,measurementHorizon,covenantPolicy'),
  ('OPS-01', 'demandHorizon,orderCostPolicy,holdingCostPolicy,lotRoundingPolicy'),
  ('OPS-02', 'serviceLevelTarget,demandDistributionPolicy,leadTimePolicy,reviewPeriodPolicy'),
  ('OPS-03', 'plannedProductionTimePolicy,idealCycleTimeVersion,microStopTreatment,qualityUnitsPolicy'),
  ('OPS-04', 'bottleneckSelectionRule,throughputDefinition,constraintSetVersion,allocationPriority'),
  ('OPS-05', 'processStepSchemaVersion,timeUnit,touchVsWaitPolicy,humanSignoffPolicy'),
  ('OPS-06', 'yieldDenominatorPolicy,scrapTaxonomyVersion,reworkTreatmentPolicy,unitReconciliationPolicy'),
  ('QLT-01', 'causeTaxonomyVersion,evidenceMinimumPolicy,hypothesisStatusPolicy,moderationWorkflow'),
  ('QLT-02', 'investigationDepthPolicy,causalEvidencePolicy,reviewWorkflow,stoppingRulePolicy'),
  ('QLT-03', 'severityScaleVersion,occurrenceScaleVersion,detectionScaleVersion,riskPriorityPolicy,actionThresholdPolicy'),
  ('QLT-04', 'chartType,subgroupSize,controlLimitPolicy,signalRuleSetVersion,measurementCadence'),
  ('QLT-05', 'reviewCyclePolicy,baselineKpiPolicy,targetPolicy,effectWindowPolicy,approvalWorkflow'),
  ('QLT-06', 'peerCohortPolicy,normalizationPolicy,periodMatchingPolicy,confidentialityPolicy'),
  ('STR-01', 'evidenceTaxonomyVersion,assessmentHorizon,moderationWorkflow,qualitativeScoringPolicy'),
  ('STR-02', 'factorTaxonomyVersion,evidenceFreshnessPolicy,marketScopePolicy,scenarioHorizon'),
  ('STR-03', 'industryScopePolicy,forceEvidenceFramework,assessmentScalePolicy,reviewWorkflow'),
  ('STR-04', 'perspectivesVersion,kpiContractVersion,targetVersion,refreshCadence,ownerAccountabilityPolicy'),
  ('ADV-01', 'modelFamily,forecastHorizon,minTrainingPeriods,backtestFoldPolicy,predictionIntervalPolicy'),
  ('ADV-02', 'targetMetricCode,featureRegistryVersion,timeSplitPolicy,regularizationPolicy,collinearityThreshold'),
  ('ADV-03', 'objectiveVersion,constraintSetVersion,solverVersion,optimalityGapLimit,solverTimeoutPolicy'),
  ('ADV-04', 'criteriaCatalogVersion,weightNormalizationPolicy,decisionRuleVersion,sensitivityPolicy,missingAlternativePolicy')
)
INSERT INTO fin_calc.method_parameter_candidate
 (method_code, parameter_code)
SELECT source.method_code, btrim(piece.parameter_code)
FROM candidate_source source
CROSS JOIN LATERAL unnest(string_to_array(source.parameter_csv, ',')) AS piece(parameter_code);

-- Migration must abort if source mapping no longer matches the reviewed
-- 60-family / 259-parameter snapshot, rather than silently seed a partial set.
DO $$
BEGIN
  IF (SELECT count(*) FROM fin_calc.method_family) <> 60
     OR (SELECT count(*) FROM fin_calc.method_parameter_candidate) <> 259
     OR (SELECT count(DISTINCT method_code)
         FROM fin_calc.method_parameter_candidate) <> 60 THEN
    RAISE EXCEPTION 'FIN_METHOD_PARAMETER_SEED_COVERAGE_DRIFT';
  END IF;
END;
$$;

CREATE TRIGGER trg_fin_method_parameter_candidate_immutable
BEFORE UPDATE OR DELETE ON fin_calc.method_parameter_candidate
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- The approved parameter-contract table remains independent. No FK
-- from a proposal to an executable configuration, no automatic approval.
