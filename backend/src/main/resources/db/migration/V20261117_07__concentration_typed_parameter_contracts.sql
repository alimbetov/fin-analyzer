-- FIN-MR-R2.1. Additive typed contract profiles. No S2 or execution permit.
ALTER TABLE fin_calc.method_parameter_contract
  DROP CONSTRAINT IF EXISTS method_parameter_contract_validation_profile_check;
ALTER TABLE fin_calc.method_parameter_contract
  DROP CONSTRAINT IF EXISTS ck_fin_method_abc_contract;
ALTER TABLE fin_calc.method_parameter_contract
  ADD CONSTRAINT ck_fin_r21_validation_profile CHECK(validation_profile IN
    ('NOT_EXECUTABLE','CON02_ABC_BOUNDARIES_V1','CON01_PARETO_FIXED_V1','CON02_ABC_TYPED_V2')),
  ADD CONSTRAINT ck_fin_r21_validation_scope CHECK (
    (validation_profile NOT IN ('CON02_ABC_BOUNDARIES_V1','CON02_ABC_TYPED_V2')
      OR (method_code='CON-02' AND variant_code='ABC_SALES'))
    AND (validation_profile<>'CON01_PARETO_FIXED_V1'
      OR (method_code='CON-01' AND variant_code='PARETO_SALES'))
  );

-- V1 descriptors are grounded in the existing fixed Pareto engine and ABC
-- crossing-item classification. sourceParetoVersion is input lineage, not
-- a mutable runtime configuration value; report cutoff remains unimplemented.
CREATE FUNCTION fin_calc.method_typed_schema_r21(p_profile TEXT)
RETURNS JSONB LANGUAGE plpgsql IMMUTABLE AS $body$
BEGIN
  CASE p_profile
    WHEN 'CON01_PARETO_FIXED_V1' THEN RETURN
      '{"schemaVersion":"FIN-MR-R2.1-V1","methodCode":"CON-01",
         "variantCode":"PARETO_SALES","configProperties":{
         "tieSortPolicy":{"type":"ENUM","required":true,"allowedValues":["AMOUNT_DESC_SKU_CODE_ASC"]},
         "populationMetric":{"type":"ENUM","required":true,"allowedValues":["NET_SALES"]},
         "returnsPolicy":{"type":"ENUM","required":true,"allowedValues":["REVIEWED_NET_RECONCILED"]}},
         "candidateNotExecutable":{"reportCutoffPercent":{"type":"DECIMAL",
           "unit":"PERCENT_0_100","reason":"REPORT_CUTOFF_NOT_IMPLEMENTED"}},
         "resultAlgorithmVersion":"CON01_PARETO_V1"}'::jsonb;
    WHEN 'CON02_ABC_TYPED_V2' THEN RETURN
      '{"schemaVersion":"FIN-MR-R2.1-V1","methodCode":"CON-02",
         "variantCode":"ABC_SALES","configProperties":{
         "aLimitPercent":{"type":"DECIMAL","unit":"PERCENT_0_100","required":true,
           "exclusiveMinimum":"0","exclusiveMaximum":"100"},
         "bLimitPercent":{"type":"DECIMAL","unit":"PERCENT_0_100","required":true,
           "exclusiveMinimum":"0","exclusiveMaximum":"100"},
         "boundaryCrossingPolicy":{"type":"ENUM","required":true,
           "allowedValues":["CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED"]}},
         "crossFieldRules":["0 < aLimitPercent < bLimitPercent < 100"],
         "inputLineage":{"sourceParetoVersion":{"type":"STRING","required":true,
           "sourceMethod":"CON-01/PARETO_SALES","equals":"CON01_PARETO_V1",
           "scope":"TRUSTED_DEPENDENCY_RESULT_NOT_CONFIG"}},
         "resultAlgorithmVersion":"CON02_ABC_BOUNDARY_V1"}'::jsonb;
    ELSE RAISE EXCEPTION 'FIN_METHOD_TYPED_PROFILE_UNKNOWN';
  END CASE;
END;
$body$;

CREATE OR REPLACE FUNCTION fin_calc.method_contract_hash_before_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $body$
BEGIN
  IF NEW.validation_profile IN ('CON01_PARETO_FIXED_V1','CON02_ABC_TYPED_V2')
     AND NEW.parameter_schema IS DISTINCT FROM
          fin_calc.method_typed_schema_r21(NEW.validation_profile) THEN
    RAISE EXCEPTION 'FIN_METHOD_TYPED_SCHEMA_MISMATCH';
  END IF;
  NEW.schema_sha256 := fin_calc.method_contract_sha_v1(
    NEW.method_code,NEW.variant_code,NEW.contract_version,
    NEW.validation_profile,NEW.parameter_schema);
  RETURN NEW;
END;
$body$;

-- Historic two-limit CON02 profile continues to normalize exactly as in V04.
CREATE OR REPLACE FUNCTION fin_calc.validate_method_config_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $body$
DECLARE c fin_calc.method_parameter_contract%ROWTYPE;
DECLARE prior fin_calc.method_config_version%ROWTYPE;
DECLARE a NUMERIC;
DECLARE b NUMERIC;
DECLARE required_key_count INTEGER;
BEGIN
  SELECT * INTO c FROM fin_calc.method_parameter_contract
   WHERE contract_id=NEW.contract_id AND method_code=NEW.method_code
     AND variant_code=NEW.variant_code;
  IF NOT FOUND THEN RAISE EXCEPTION 'FIN_METHOD_CONTRACT_NOT_FOUND'; END IF;
  IF NEW.parent_config_id IS NOT NULL THEN
    SELECT * INTO prior FROM fin_calc.method_config_version
      WHERE config_id=NEW.parent_config_id;
    IF NOT FOUND OR prior.method_code<>NEW.method_code OR
       prior.variant_code<>NEW.variant_code OR prior.version_no>=NEW.version_no THEN
      RAISE EXCEPTION 'FIN_METHOD_PARENT_VERSION_INVALID';
    END IF;
  END IF;
  IF c.validation_profile IN ('CON02_ABC_BOUNDARIES_V1','CON02_ABC_TYPED_V2') THEN
    -- A SQL CASE inside a PL/pgSQL IF condition conflicts with its THEN parser.
    -- Resolve the versioned arity before parsing the remaining boolean check.
    required_key_count := 2;
    IF c.validation_profile='CON02_ABC_TYPED_V2' THEN
      required_key_count := 3;
    END IF;
    IF NOT (NEW.parameters ? 'aLimitPercent' AND NEW.parameters ? 'bLimitPercent')
       OR (SELECT count(*) FROM jsonb_object_keys(NEW.parameters)) <> required_key_count
       OR jsonb_typeof(NEW.parameters->'aLimitPercent') <> 'number'
       OR jsonb_typeof(NEW.parameters->'bLimitPercent') <> 'number' THEN
      RAISE EXCEPTION 'FIN_METHOD_ABC_PARAMETERS_INVALID';
    END IF;
    a := (NEW.parameters->>'aLimitPercent')::NUMERIC;
    b := (NEW.parameters->>'bLimitPercent')::NUMERIC;
    IF a<=0 OR a>=b OR b>=100 THEN
      RAISE EXCEPTION 'FIN_METHOD_ABC_THRESHOLDS_OUT_OF_RANGE';
    END IF;
    IF c.validation_profile='CON02_ABC_TYPED_V2' THEN
      IF NEW.engine<>'JAVA' OR NEW.algorithm_version<>'CON02_ABC_BOUNDARY_V1' THEN
        RAISE EXCEPTION 'FIN_METHOD_TYPED_ALGORITHM_INCOMPATIBLE';
      END IF;
      IF jsonb_typeof(NEW.parameters->'boundaryCrossingPolicy') IS DISTINCT FROM 'string'
        OR NEW.parameters->>'boundaryCrossingPolicy'
            IS DISTINCT FROM 'CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED' THEN
        RAISE EXCEPTION 'FIN_METHOD_ABC_BOUNDARY_POLICY_UNSUPPORTED';
      END IF;
      NEW.parameters := jsonb_build_object(
        'aLimitPercent',trim_scale(a),'bLimitPercent',trim_scale(b),
        'boundaryCrossingPolicy','CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED');
    ELSE
      NEW.parameters := jsonb_build_object(
        'aLimitPercent',trim_scale(a),'bLimitPercent',trim_scale(b));
    END IF;
  ELSIF c.validation_profile='CON01_PARETO_FIXED_V1' THEN
    IF (SELECT count(*) FROM jsonb_object_keys(NEW.parameters))<>3
       OR jsonb_typeof(NEW.parameters->'tieSortPolicy') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.parameters->'populationMetric') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.parameters->'returnsPolicy') IS DISTINCT FROM 'string' THEN
      RAISE EXCEPTION 'FIN_METHOD_PARETO_PARAMETERS_INVALID';
    END IF;
    IF NEW.engine<>'JAVA' OR NEW.algorithm_version<>'CON01_PARETO_V1' THEN
      RAISE EXCEPTION 'FIN_METHOD_TYPED_ALGORITHM_INCOMPATIBLE';
    END IF;
    IF NEW.parameters->>'tieSortPolicy' IS DISTINCT FROM 'AMOUNT_DESC_SKU_CODE_ASC'
       OR NEW.parameters->>'populationMetric' IS DISTINCT FROM 'NET_SALES'
       OR NEW.parameters->>'returnsPolicy' IS DISTINCT FROM 'REVIEWED_NET_RECONCILED' THEN
      RAISE EXCEPTION 'FIN_METHOD_PARETO_UNSUPPORTED_POLICY';
    END IF;
    NEW.parameters := jsonb_build_object(
      'tieSortPolicy','AMOUNT_DESC_SKU_CODE_ASC',
      'populationMetric','NET_SALES',
      'returnsPolicy','REVIEWED_NET_RECONCILED');
  END IF;
  NEW.params_sha256 := fin_calc.method_params_sha_v1(NEW.parameters);
  NEW.effective_sha256 := fin_calc.method_effective_sha_v1(
    NEW.method_code,NEW.variant_code,NEW.engine,NEW.algorithm_id,
    NEW.algorithm_version,NEW.algorithm_sha256,c.schema_sha256,
    NEW.params_sha256);
  RETURN NEW;
END;
$body$;

-- Deliberately no inserts of contracts, configs, moderator reviews or active
-- pointers. In particular, the 60 families and 259 candidates remain unchanged.
