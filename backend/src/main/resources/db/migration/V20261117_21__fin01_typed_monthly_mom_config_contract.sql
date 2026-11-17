-- HTAP-V1.3B: narrowly typed FIN-01 monthly MoM parameter configuration.
-- An approved editor configuration is NOT an execution permit, independent S2,
-- signed artifact attestation, nor production ACTUAL authorization.
-- No seed config/review/active pointer: those require distinct real actors.

ALTER TABLE fin_calc.method_parameter_contract
  DROP CONSTRAINT ck_fin_r21_validation_profile;
ALTER TABLE fin_calc.method_parameter_contract
  ADD CONSTRAINT ck_fin_r21_validation_profile CHECK(validation_profile IN
    ('NOT_EXECUTABLE','CON02_ABC_BOUNDARIES_V1','CON01_PARETO_FIXED_V1',
     'CON02_ABC_TYPED_V2','FIN01_MONTHLY_MOM_TYPED_V1'));
ALTER TABLE fin_calc.method_parameter_contract
  DROP CONSTRAINT ck_fin_r21_validation_scope;
ALTER TABLE fin_calc.method_parameter_contract
  ADD CONSTRAINT ck_fin_r21_validation_scope CHECK (
    (validation_profile NOT IN ('CON02_ABC_BOUNDARIES_V1','CON02_ABC_TYPED_V2')
      OR (method_code='CON-02' AND variant_code='ABC_SALES'))
    AND (validation_profile<>'CON01_PARETO_FIXED_V1'
      OR (method_code='CON-01' AND variant_code='PARETO_SALES'))
    AND (validation_profile<>'FIN01_MONTHLY_MOM_TYPED_V1'
      OR (method_code='FIN-01' AND variant_code='REV_NET_MOM'))
  );

CREATE FUNCTION fin_calc.fin01_monthly_mom_typed_schema_v1()
RETURNS JSONB LANGUAGE SQL IMMUTABLE AS $body$
  SELECT '{
    "schemaVersion":"HTAP-V1.3B-1",
    "methodCode":"FIN-01",
    "variantCode":"REV_NET_MOM",
    "inputMetric":"REV_NET",
    "inputMetricVersion":"1.0.0",
    "dataSpace":"SYNTHETIC",
    "periodPolicy":"ISO_MONTH_FLOW_PERIOD_ONLY_V1",
    "resultUnit":"PCT",
    "resultAlgorithmVersion":"R2_SQL_PILOT_1",
    "configProperties":{
      "baselinePolicy":{"type":"ENUM","required":true,"allowedValues":["POSITIVE_ONLY"]},
      "roundingMode":{"type":"ENUM","required":true,"allowedValues":["HALF_EVEN"]},
      "percentageScale":{"type":"INTEGER","required":true,"allowedValues":[12]},
      "periodPolicy":{"type":"ENUM","required":true,"allowedValues":["ISO_MONTH_FLOW_PERIOD_ONLY_V1"]}
    }
  }'::jsonb;
$body$;

-- Separate trigger deliberately composes with the existing R2.1 hashing
-- trigger: contract arity/profile is validated before the DB computes SHA.
CREATE FUNCTION fin_calc.fin01_contract_guard_v1()
RETURNS TRIGGER LANGUAGE plpgsql AS $body$
BEGIN
  IF NEW.validation_profile='FIN01_MONTHLY_MOM_TYPED_V1' THEN
    IF NEW.method_code<>'FIN-01' OR NEW.variant_code<>'REV_NET_MOM'
       OR NEW.parameter_schema IS DISTINCT FROM fin_calc.fin01_monthly_mom_typed_schema_v1()
    THEN
      RAISE EXCEPTION 'FIN01_TYPED_SCHEMA_OR_SCOPE_INVALID';
    END IF;
  END IF;
  RETURN NEW;
END;
$body$;
CREATE TRIGGER trg_fin01_contract_guard
BEFORE INSERT ON fin_calc.method_parameter_contract
FOR EACH ROW EXECUTE FUNCTION fin_calc.fin01_contract_guard_v1();

CREATE FUNCTION fin_calc.fin01_config_guard_v1()
RETURNS TRIGGER LANGUAGE plpgsql AS $body$
DECLARE profile TEXT;
BEGIN
  SELECT c.validation_profile INTO profile
  FROM fin_calc.method_parameter_contract c
  WHERE c.contract_id=NEW.contract_id
    AND c.method_code=NEW.method_code
    AND c.variant_code=NEW.variant_code;
  IF profile='FIN01_MONTHLY_MOM_TYPED_V1' THEN
    IF NEW.method_code<>'FIN-01' OR NEW.variant_code<>'REV_NET_MOM'
       OR NEW.engine<>'SQL' OR NEW.algorithm_id<>'FIN-01/REV_NET_MOM'
       OR NEW.algorithm_version<>'R2_SQL_PILOT_1'
       OR (SELECT count(*) FROM jsonb_object_keys(NEW.parameters))<>4
       OR jsonb_typeof(NEW.parameters->'baselinePolicy') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.parameters->'roundingMode') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.parameters->'percentageScale') IS DISTINCT FROM 'number'
       OR jsonb_typeof(NEW.parameters->'periodPolicy') IS DISTINCT FROM 'string'
       OR NEW.parameters->>'baselinePolicy' IS DISTINCT FROM 'POSITIVE_ONLY'
       OR NEW.parameters->>'roundingMode' IS DISTINCT FROM 'HALF_EVEN'
       OR NEW.parameters->'percentageScale' IS DISTINCT FROM '12'::jsonb
       OR NEW.parameters->>'periodPolicy' IS DISTINCT FROM 'ISO_MONTH_FLOW_PERIOD_ONLY_V1'
    THEN
      RAISE EXCEPTION 'FIN01_TYPED_CONFIG_OR_ALGORITHM_INCOMPATIBLE';
    END IF;
    NEW.parameters:=jsonb_build_object(
      'baselinePolicy','POSITIVE_ONLY',
      'roundingMode','HALF_EVEN',
      'percentageScale',12,
      'periodPolicy','ISO_MONTH_FLOW_PERIOD_ONLY_V1');
  END IF;
  RETURN NEW;
END;
$body$;
CREATE TRIGGER trg_fin01_config_guard
BEFORE INSERT ON fin_calc.method_config_version
FOR EACH ROW EXECUTE FUNCTION fin_calc.fin01_config_guard_v1();

REVOKE ALL ON FUNCTION fin_calc.fin01_monthly_mom_typed_schema_v1() FROM PUBLIC;
REVOKE ALL ON FUNCTION fin_calc.fin01_contract_guard_v1() FROM PUBLIC;
REVOKE ALL ON FUNCTION fin_calc.fin01_config_guard_v1() FROM PUBLIC;

COMMENT ON FUNCTION fin_calc.fin01_monthly_mom_typed_schema_v1()
IS 'FIN-01 strictly typed SYNTHETIC monthly MoM config schema; not method S2 or execution authority';
