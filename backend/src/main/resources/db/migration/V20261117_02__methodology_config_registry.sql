-- FIN HTAP MR-R0: all 60 families in one read view, immutable configuration versions,
-- human moderation and independent active-pointer audit. No method execution.
-- Existing R2 catalog/7 adapters and Cat-A3 S2 ledger remain authoritative;
-- no automatic financial ACTUAL permission is conferred.
CREATE TABLE fin_calc.method_family (
  method_code VARCHAR(32) PRIMARY KEY
    CHECK(method_code ~ '^(FIN|CON|PRO|CASH|BUD|INV|OPS|QLT|STR|ADV)-[0-9]{2}$'),
  method_group VARCHAR(12) NOT NULL,
  title TEXT NOT NULL CHECK(length(btrim(title))>0),
  implementation_status VARCHAR(80) NOT NULL
    CHECK(implementation_status IN (
      'ONE_VARIANT_WIRED_UNQUALIFIED','PURE_ENGINE_GOLDENS_AUTHORED_NO_TRUSTED_ADAPTER',
      'CODE_CANDIDATE_NOT_QUALIFIED','NOT_YET_AUDITED')),
  catalog_version VARCHAR(64) NOT NULL,
  release_qualified BOOLEAN NOT NULL DEFAULT FALSE CHECK(NOT release_qualified),
  auto_run_allowed BOOLEAN NOT NULL DEFAULT FALSE CHECK(NOT auto_run_allowed),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK(method_code LIKE method_group || '-%')
);
INSERT INTO fin_calc.method_family(method_code,method_group,title,implementation_status,catalog_version)
VALUES
  ('FIN-01', 'FIN', 'Horizontal Analysis', 'ONE_VARIANT_WIRED_UNQUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('FIN-02', 'FIN', 'Vertical Analysis', 'ONE_VARIANT_WIRED_UNQUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('FIN-03', 'FIN', 'Trend Analysis', 'CODE_CANDIDATE_NOT_QUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('FIN-04', 'FIN', 'Financial Ratios', 'ONE_VARIANT_WIRED_UNQUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('FIN-05', 'FIN', 'Factor Decomposition', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('FIN-06', 'FIN', 'Index Analysis', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('FIN-07', 'FIN', 'DuPont', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('FIN-08', 'FIN', 'Cash Flow Analysis', 'CODE_CANDIDATE_NOT_QUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('CON-01', 'CON', 'Pareto', 'ONE_VARIANT_WIRED_UNQUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('CON-02', 'CON', 'ABC Classification', 'ONE_VARIANT_WIRED_UNQUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('CON-03', 'CON', 'XYZ Classification', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('CON-04', 'CON', 'ABC-XYZ', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('CON-05', 'CON', 'HHI', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('CON-06', 'CON', 'Gini/Lorenz', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('PRO-01', 'PRO', 'CVP', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('PRO-02', 'PRO', 'Break-Even', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('PRO-03', 'PRO', 'Contribution Margin', 'PURE_ENGINE_GOLDENS_AUTHORED_NO_TRUSTED_ADAPTER', 'METHOD-R1-PREQUALIFICATION-1'),
  ('PRO-04', 'PRO', 'Unit Economics', 'CODE_CANDIDATE_NOT_QUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('PRO-05', 'PRO', 'Activity-Based Costing', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('PRO-06', 'PRO', 'Standard Cost Variance', 'CODE_CANDIDATE_NOT_QUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('CASH-01', 'CASH', 'Cash Conversion Cycle', 'PURE_ENGINE_GOLDENS_AUTHORED_NO_TRUSTED_ADAPTER', 'METHOD-R1-PREQUALIFICATION-1'),
  ('CASH-02', 'CASH', 'Aging', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('CASH-03', 'CASH', '13-week Cash Forecast', 'CODE_CANDIDATE_NOT_QUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('CASH-04', 'CASH', 'Working Capital', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('CASH-05', 'CASH', 'Cash Stress Testing', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('CASH-06', 'CASH', 'Liquidity Gap', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('BUD-01', 'BUD', 'Budget vs Actual', 'CODE_CANDIDATE_NOT_QUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('BUD-02', 'BUD', 'Flexible Budget', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('BUD-03', 'BUD', 'Rolling Forecast', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('BUD-04', 'BUD', 'Driver-Based Planning', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('BUD-05', 'BUD', 'Zero-Based Budgeting', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('BUD-06', 'BUD', 'Responsibility Centers', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('INV-01', 'INV', 'NPV/XNPV', 'CODE_CANDIDATE_NOT_QUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('INV-02', 'INV', 'IRR/MIRR', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('INV-03', 'INV', 'DCF', 'CODE_CANDIDATE_NOT_QUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('INV-04', 'INV', 'WACC/Cost of Equity', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('INV-05', 'INV', 'Sensitivity', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('INV-06', 'INV', 'Scenarios', 'CODE_CANDIDATE_NOT_QUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('INV-07', 'INV', 'Monte Carlo', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('INV-08', 'INV', 'DSCR/Debt Capacity', 'CODE_CANDIDATE_NOT_QUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('OPS-01', 'OPS', 'EOQ', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('OPS-02', 'OPS', 'Safety Stock/Reorder Point', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('OPS-03', 'OPS', 'OEE', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('OPS-04', 'OPS', 'Theory of Constraints', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('OPS-05', 'OPS', 'Value Stream Map', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('OPS-06', 'OPS', 'Yield/Scrap', 'CODE_CANDIDATE_NOT_QUALIFIED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('QLT-01', 'QLT', 'Ishikawa', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('QLT-02', 'QLT', 'Five Whys', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('QLT-03', 'QLT', 'FMEA', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('QLT-04', 'QLT', 'SPC', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('QLT-05', 'QLT', 'PDCA', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('QLT-06', 'QLT', 'Benchmarking', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('STR-01', 'STR', 'SWOT', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('STR-02', 'STR', 'PESTEL', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('STR-03', 'STR', 'Porter Five Forces', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('STR-04', 'STR', 'Balanced Scorecard', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('ADV-01', 'ADV', 'Time-Series Forecast', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('ADV-02', 'ADV', 'Regression/Drivers', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('ADV-03', 'ADV', 'Constraint Optimization', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1'),
  ('ADV-04', 'ADV', 'Multi-Criteria Decision', 'NOT_YET_AUDITED', 'METHOD-R1-PREQUALIFICATION-1');

-- A contract belongs to one named *variant* of a family and is independent
-- of the method's implementation SHA. No executable code is stored here.
CREATE TABLE fin_calc.method_parameter_contract (
  contract_id UUID PRIMARY KEY,
  method_code VARCHAR(32) NOT NULL REFERENCES fin_calc.method_family(method_code),
  variant_code VARCHAR(128) NOT NULL
    CHECK(variant_code ~ '^[A-Z][A-Z0-9_]{1,127}$'),
  contract_version VARCHAR(64) NOT NULL CHECK(length(btrim(contract_version))>0),
  validation_profile VARCHAR(96) NOT NULL
    CHECK(validation_profile IN ('CON02_ABC_BOUNDARIES_V1','NOT_EXECUTABLE')),
  parameter_schema JSONB NOT NULL CHECK(jsonb_typeof(parameter_schema)='object'),
  schema_sha256 CHAR(64) NOT NULL CHECK(schema_sha256 ~ '^[0-9a-f]{64}$'),
  authored_by VARCHAR(200) NOT NULL CHECK(length(btrim(authored_by))>0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_fin_method_param_contract UNIQUE(method_code,variant_code,contract_version),
  CONSTRAINT uq_fin_method_param_scope UNIQUE(method_code,variant_code,contract_id),
  CONSTRAINT ck_fin_method_abc_contract CHECK(
    validation_profile <> 'CON02_ABC_BOUNDARIES_V1'
    OR (method_code='CON-02' AND variant_code='ABC_SALES'))
);
CREATE FUNCTION fin_calc.method_contract_sha_v1(p_method TEXT,p_variant TEXT,
                                                 p_version TEXT,p_profile TEXT,
                                                 p_schema JSONB)
RETURNS TEXT LANGUAGE SQL IMMUTABLE AS $$
  SELECT encode(sha256(convert_to(
      'FIN:METHOD:CONTRACT:PG16-V1|' || p_method || '|' || p_variant || '|' ||
      p_version || '|' || p_profile || '|' || p_schema::text, 'UTF8')),'hex');
$$;
CREATE FUNCTION fin_calc.method_contract_hash_before_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  NEW.schema_sha256 := fin_calc.method_contract_sha_v1(
    NEW.method_code,NEW.variant_code,NEW.contract_version,
    NEW.validation_profile,NEW.parameter_schema);
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_fin_method_contract_hash BEFORE INSERT
ON fin_calc.method_parameter_contract FOR EACH ROW
EXECUTE FUNCTION fin_calc.method_contract_hash_before_insert();

CREATE TABLE fin_calc.method_config_version (
  config_id UUID PRIMARY KEY,
  method_code VARCHAR(32) NOT NULL REFERENCES fin_calc.method_family(method_code),
  variant_code VARCHAR(128) NOT NULL
    CHECK(variant_code ~ '^[A-Z][A-Z0-9_]{1,127}$'),
  version_no INTEGER NOT NULL CHECK(version_no > 0),
  parent_config_id UUID REFERENCES fin_calc.method_config_version(config_id),
  contract_id UUID NOT NULL,
  engine VARCHAR(8) NOT NULL CHECK(engine IN('JAVA','SQL')),
  algorithm_id VARCHAR(128) NOT NULL
    CHECK(algorithm_id ~ '^[A-Z][A-Z0-9-]{1,30}/[A-Z][A-Z0-9_]{1,100}$'),
  algorithm_version VARCHAR(64) NOT NULL CHECK(length(btrim(algorithm_version))>0),
  algorithm_sha256 CHAR(64) NOT NULL CHECK(algorithm_sha256 ~ '^[0-9a-f]{64}$'),
  parameters JSONB NOT NULL CHECK(jsonb_typeof(parameters)='object'),
  params_sha256 CHAR(64) NOT NULL CHECK(params_sha256 ~ '^[0-9a-f]{64}$'),
  effective_sha256 CHAR(64) NOT NULL CHECK(effective_sha256 ~ '^[0-9a-f]{64}$'),
  hash_contract_version VARCHAR(32) NOT NULL DEFAULT 'PG16-MCONFIG-V1'
    CHECK(hash_contract_version='PG16-MCONFIG-V1'),
  created_by VARCHAR(200) NOT NULL CHECK(length(btrim(created_by))>0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_fin_method_config_contract
    FOREIGN KEY(method_code,variant_code,contract_id)
    REFERENCES fin_calc.method_parameter_contract(method_code,variant_code,contract_id),
  CONSTRAINT uq_fin_method_config_version UNIQUE(method_code,variant_code,version_no),
  CONSTRAINT uq_fin_method_config_hash UNIQUE(method_code,variant_code,effective_sha256),
  CONSTRAINT uq_fin_method_config_scope UNIQUE(method_code,variant_code,config_id),
  CONSTRAINT ck_fin_method_config_parent CHECK(
    (version_no=1 AND parent_config_id IS NULL) OR
    (version_no>1 AND parent_config_id IS NOT NULL)),
  CONSTRAINT ck_fin_method_config_algorithm CHECK(
    algorithm_id=method_code || '/' || variant_code)
);
CREATE INDEX idx_fin_method_config_history
  ON fin_calc.method_config_version(method_code,variant_code,created_at DESC);

-- DB-calculated SHA-256: no client-supplied digest can falsely stand for
-- a parameter change. Canonicalization is *explicitly PostgreSQL 16 JSONB
-- textual serialization* for this V1 hash and must be independently pinned
-- and migrated before a future major PostgreSQL version.
CREATE FUNCTION fin_calc.method_params_sha_v1(p_params JSONB)
RETURNS TEXT LANGUAGE SQL IMMUTABLE AS $$
  SELECT encode(sha256(convert_to('FIN:METHOD:PARAMS:PG16-V1|' ||
                                 p_params::text,'UTF8')),'hex');
$$;
CREATE FUNCTION fin_calc.method_effective_sha_v1(p_method TEXT,p_variant TEXT,
    p_engine TEXT,p_algorithm_id TEXT,p_algorithm_version TEXT,
    p_algorithm_sha TEXT,p_schema_sha TEXT,p_params_sha TEXT)
RETURNS TEXT LANGUAGE SQL IMMUTABLE AS $$
  SELECT encode(sha256(convert_to(
    'FIN:METHOD:CONFIG:PG16-V1|' || p_method || '|' || p_variant || '|' ||
    p_engine || '|' || p_algorithm_id || '|' || p_algorithm_version || '|' ||
    p_algorithm_sha || '|' || p_schema_sha || '|' || p_params_sha,'UTF8')),'hex');
$$;

CREATE FUNCTION fin_calc.validate_method_config_insert()
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
  END IF;
  NEW.params_sha256 := fin_calc.method_params_sha_v1(NEW.parameters);
  NEW.effective_sha256 := fin_calc.method_effective_sha_v1(
    NEW.method_code, NEW.variant_code,NEW.engine,NEW.algorithm_id,
    NEW.algorithm_version,NEW.algorithm_sha256,c.schema_sha256,
    NEW.params_sha256);
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_fin_method_config_hash BEFORE INSERT
ON fin_calc.method_config_version FOR EACH ROW
EXECUTE FUNCTION fin_calc.validate_method_config_insert();

-- Config moderation approves only a *parameter configuration*. It never
-- approves financial methodology/S2 or releases an ACTUAL job.
CREATE TABLE fin_calc.method_config_review (
  review_id UUID PRIMARY KEY,
  config_id UUID NOT NULL UNIQUE REFERENCES fin_calc.method_config_version(config_id),
  submitted_by VARCHAR(200) NOT NULL CHECK(length(btrim(submitted_by))>0),
  reviewed_by VARCHAR(200) NOT NULL CHECK(length(btrim(reviewed_by))>0),
  decision VARCHAR(12) NOT NULL CHECK(decision IN('APPROVED','REJECTED')),
  evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
  review_reason VARCHAR(2000) NOT NULL CHECK(length(btrim(review_reason))>0),
  reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_fin_method_config_independent_review CHECK(submitted_by<>reviewed_by)
);
CREATE FUNCTION fin_calc.guard_method_config_review()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_author VARCHAR(200);
DECLARE v_profile VARCHAR(96);
BEGIN
  SELECT v.created_by,c.validation_profile INTO v_author,v_profile
  FROM fin_calc.method_config_version v
  JOIN fin_calc.method_parameter_contract c ON c.contract_id=v.contract_id
  WHERE v.config_id=NEW.config_id;
  IF v_author IS NULL OR v_author<>NEW.submitted_by THEN
    RAISE EXCEPTION 'FIN_METHOD_CONFIG_SUBMITTER_MISMATCH'; END IF;
  IF NEW.decision='APPROVED' AND v_profile='NOT_EXECUTABLE' THEN
    RAISE EXCEPTION 'FIN_METHOD_PARAMETERS_NOT_VALIDATED'; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_fin_method_config_review_guard BEFORE INSERT
ON fin_calc.method_config_review FOR EACH ROW
EXECUTE FUNCTION fin_calc.guard_method_config_review();

CREATE TABLE fin_calc.method_active_config (
  method_code VARCHAR(32) NOT NULL,
  variant_code VARCHAR(128) NOT NULL,
  config_id UUID NOT NULL,
  pointer_version BIGINT NOT NULL DEFAULT 1 CHECK(pointer_version>0),
  activated_by VARCHAR(200) NOT NULL CHECK(length(btrim(activated_by))>0),
  activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(method_code,variant_code),
  CONSTRAINT fk_fin_method_active_config
    FOREIGN KEY(method_code,variant_code,config_id)
    REFERENCES fin_calc.method_config_version(method_code,variant_code,config_id)
);
CREATE TABLE fin_calc.method_config_activation (
  activation_id UUID PRIMARY KEY,
  method_code VARCHAR(32) NOT NULL,
  variant_code VARCHAR(128) NOT NULL,
  prior_config_id UUID,
  new_config_id UUID NOT NULL REFERENCES fin_calc.method_config_version(config_id),
  pointer_version BIGINT NOT NULL,
  activated_by VARCHAR(200) NOT NULL,
  activated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_fin_method_activation_history
  ON fin_calc.method_config_activation(method_code,variant_code,activated_at DESC);
CREATE FUNCTION fin_calc.guard_and_audit_method_activation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE review_decision VARCHAR(12);
BEGIN
  SELECT decision INTO review_decision FROM fin_calc.method_config_review
  WHERE config_id=NEW.config_id;
  IF review_decision IS DISTINCT FROM 'APPROVED' THEN
    RAISE EXCEPTION 'FIN_METHOD_CONFIG_NOT_MODERATED'; END IF;
  IF TG_OP='INSERT' THEN
    IF NEW.pointer_version<>1 THEN
      RAISE EXCEPTION 'FIN_METHOD_ACTIVE_POINTER_VERSION_INVALID'; END IF;
  ELSE
    IF NEW.pointer_version<>OLD.pointer_version+1 OR
       NEW.config_id=OLD.config_id THEN
      RAISE EXCEPTION 'FIN_METHOD_ACTIVE_POINTER_CAS_REQUIRED'; END IF;
  END IF;
  IF NEW.activated_by IS NULL OR length(btrim(NEW.activated_by))=0 THEN
    RAISE EXCEPTION 'FIN_METHOD_ACTIVATOR_REQUIRED'; END IF;
  NEW.activated_at := clock_timestamp();
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_fin_method_activate_guard BEFORE INSERT OR UPDATE
ON fin_calc.method_active_config FOR EACH ROW
EXECUTE FUNCTION fin_calc.guard_and_audit_method_activation();
CREATE FUNCTION fin_calc.audit_method_activation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO fin_calc.method_config_activation
  (activation_id,method_code,variant_code,prior_config_id,new_config_id,
   pointer_version,activated_by,activated_at)
  VALUES(gen_random_uuid(),NEW.method_code,NEW.variant_code,
         CASE WHEN TG_OP='INSERT' THEN NULL ELSE OLD.config_id END,
         NEW.config_id,NEW.pointer_version,NEW.activated_by,NEW.activated_at);
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_fin_method_activation_audit AFTER INSERT OR UPDATE
ON fin_calc.method_active_config FOR EACH ROW
EXECUTE FUNCTION fin_calc.audit_method_activation();

-- Store durable history; only the active pointer is mutable. Do not mutate
-- original methodology catalog, version, contract, moderation or audit rows.
CREATE TRIGGER trg_fin_method_family_immutable BEFORE UPDATE OR DELETE
ON fin_calc.method_family FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_fin_method_contract_immutable BEFORE UPDATE OR DELETE
ON fin_calc.method_parameter_contract FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_fin_method_version_immutable BEFORE UPDATE OR DELETE
ON fin_calc.method_config_version FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_fin_method_review_immutable BEFORE UPDATE OR DELETE
ON fin_calc.method_config_review FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_fin_method_activation_immutable BEFORE UPDATE OR DELETE
ON fin_calc.method_config_activation FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- One stable display/table for all 60 families, with active versions as
-- nested JSONB; no cartesian multiplication if one family has many variants.
CREATE VIEW fin_calc.methodology_catalog_view AS
SELECT f.method_code, f.method_group, f.title, f.implementation_status,
       f.catalog_version, f.release_qualified, f.auto_run_allowed,
       COALESCE(cfg.total_config_versions,0) AS total_config_versions,
       COALESCE(a.active_variant_count,0) AS active_variant_count,
       COALESCE(a.active_variants,'[]'::jsonb) AS active_variants,
       FALSE AS method_execution_permitted
FROM fin_calc.method_family f
LEFT JOIN LATERAL (
  SELECT count(*) AS total_config_versions
  FROM fin_calc.method_config_version v WHERE v.method_code=f.method_code
) cfg ON TRUE
LEFT JOIN LATERAL (
  SELECT count(*) AS active_variant_count,
         jsonb_agg(jsonb_build_object(
           'variantCode',ac.variant_code,
           'configId',v.config_id,
           'versionNo',v.version_no,
           'algorithmVersion',v.algorithm_version,
           'parameters',v.parameters,
           'parametersSha256',v.params_sha256,
           'effectiveSha256',v.effective_sha256,
           'pointerVersion',ac.pointer_version)
           ORDER BY ac.variant_code) AS active_variants
  FROM fin_calc.method_active_config ac
  JOIN fin_calc.method_config_version v ON v.config_id=ac.config_id
  WHERE ac.method_code=f.method_code
) a ON TRUE;

-- Explicit no runtime integration: none of these moderation tables may grant
-- METHOD_S2_APPROVED, ACTUAL, or fin.execution.mode automatically.
