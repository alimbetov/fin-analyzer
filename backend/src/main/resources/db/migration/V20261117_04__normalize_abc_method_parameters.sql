-- MR-R0.2: versioned semantic normalization of the reviewed CON-02 ABC
-- boundary parameters. 80, 80.0 and 80.00 must have one effective hash.
-- Existing V20261117_02 function remains recorded by Flyway; this is an
-- additive replacement BEFORE any approved configuration is activated.
CREATE OR REPLACE FUNCTION fin_calc.validate_method_config_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE c fin_calc.method_parameter_contract%ROWTYPE;
DECLARE prior fin_calc.method_config_version%ROWTYPE;
DECLARE a NUMERIC;
DECLARE b NUMERIC;
BEGIN
  SELECT * INTO c FROM fin_calc.method_parameter_contract
  WHERE contract_id=NEW.contract_id AND method_code=NEW.method_code
        AND variant_code=NEW.variant_code;
  IF NOT FOUND THEN RAISE EXCEPTION 'FIN_METHOD_CONTRACT_NOT_FOUND'; END IF;
  IF NEW.parent_config_id IS NOT NULL THEN
    SELECT * INTO prior FROM fin_calc.method_config_version WHERE config_id=NEW.parent_config_id;
    IF NOT FOUND OR prior.method_code<>NEW.method_code OR
       prior.variant_code<>NEW.variant_code OR prior.version_no>=NEW.version_no THEN
      RAISE EXCEPTION 'FIN_METHOD_PARENT_VERSION_INVALID'; END IF;
  END IF;
  IF c.validation_profile='CON02_ABC_BOUNDARIES_V1' THEN
    IF NOT (NEW.parameters ? 'aLimitPercent' AND NEW.parameters ? 'bLimitPercent')
        OR (SELECT count(*) FROM jsonb_object_keys(NEW.parameters)) <> 2
        OR jsonb_typeof(NEW.parameters->'aLimitPercent') <> 'number'
        OR jsonb_typeof(NEW.parameters->'bLimitPercent') <> 'number' THEN
      RAISE EXCEPTION 'FIN_METHOD_ABC_PARAMETERS_INVALID'; END IF;
    a := (NEW.parameters->>'aLimitPercent')::NUMERIC;
    b := (NEW.parameters->>'bLimitPercent')::NUMERIC;
    IF a<=0 OR a>=b OR b>=100 THEN
      RAISE EXCEPTION 'FIN_METHOD_ABC_THRESHOLDS_OUT_OF_RANGE'; END IF;
    -- Use numeric value, not JSON input formatting or locale, to determine
    -- financial semantics. Key order is JSONB-normalized independently.
    NEW.parameters := jsonb_build_object(
      'aLimitPercent',trim_scale(a),'bLimitPercent',trim_scale(b));
  END IF;
  NEW.params_sha256 := fin_calc.method_params_sha_v1(NEW.parameters);
  NEW.effective_sha256 := fin_calc.method_effective_sha_v1(
    NEW.method_code,NEW.variant_code,NEW.engine,NEW.algorithm_id,
    NEW.algorithm_version,NEW.algorithm_sha256,c.schema_sha256,
    NEW.params_sha256);
  RETURN NEW;
END;
$$;
