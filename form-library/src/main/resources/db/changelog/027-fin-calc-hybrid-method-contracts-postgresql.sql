--liquibase formatted sql
--changeset fin-calc:027a dbms:postgresql splitStatements:true
-- HYB-2A: append-only *candidate* method contracts. No financial S2.
-- No backfill, no production approval and no modification of 001..026.
CREATE TABLE fin_calc.method_revision (
  id UUID PRIMARY KEY,
  method_code VARCHAR(128) NOT NULL
    CHECK (method_code ~ '^[A-Z][A-Z0-9_-]{1,127}$'),
  method_version VARCHAR(64) NOT NULL CHECK (length(trim(method_version))>0),
  registry_state VARCHAR(32) NOT NULL
    CHECK (registry_state IN ('CANDIDATE_UNQUALIFIED','TEST_ONLY_REFERENCE')),
  source_contract_sha256 CHAR(64) NOT NULL CHECK (source_contract_sha256 ~ '^[0-9a-f]{64}$'),
  output_contract_sha256 CHAR(64) NOT NULL CHECK (output_contract_sha256 ~ '^[0-9a-f]{64}$'),
  dag_sha256 CHAR(64) NOT NULL CHECK (dag_sha256 ~ '^[0-9a-f]{64}$'),
  algorithm_artifact_sha256 CHAR(64) NOT NULL
    CHECK (algorithm_artifact_sha256 ~ '^[0-9a-f]{64}$'),
  config_sha256 CHAR(64) NOT NULL CHECK (config_sha256 ~ '^[0-9a-f]{64}$'),
  params_sha256 CHAR(64) NOT NULL CHECK (params_sha256 ~ '^[0-9a-f]{64}$'),
  sql_artifact_sha256 CHAR(64) CHECK (sql_artifact_sha256 IS NULL
    OR sql_artifact_sha256 ~ '^[0-9a-f]{64}$'),
  artifact_attested BOOLEAN NOT NULL DEFAULT false CHECK (NOT artifact_attested),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_hyb_method_revision UNIQUE(method_code,method_version)
);

CREATE TABLE fin_calc.method_input_binding (
  method_revision_id UUID NOT NULL REFERENCES fin_calc.method_revision(id),
  ordinal INT NOT NULL CHECK (ordinal BETWEEN 0 AND 1023),
  form_version_id UUID NOT NULL,
  field_code VARCHAR(128) NOT NULL,
  metric_code VARCHAR(160) NOT NULL REFERENCES fin_lib.lib_metric_definition(metric_code),
  source_schema_sha256 CHAR(64) NOT NULL CHECK (source_schema_sha256 ~ '^[0-9a-f]{64}$'),
  unit_policy VARCHAR(64) NOT NULL CHECK (length(trim(unit_policy))>0),
  period_semantics VARCHAR(32) NOT NULL CHECK (period_semantics IN
    ('INTERVAL','AS_OF','EVENT','YEAR_TO_DATE','LIFE_TO_DATE')),
  aggregation_policy VARCHAR(32) NOT NULL CHECK (aggregation_policy IN
    ('NONE','SUM_FLOW','LAST_AS_OF','RATIO_OF_SUMS','WEIGHTED_AVERAGE',
     'COUNT_DISTINCT','ROLL_FORWARD','RECONCILE')),
  missing_policy VARCHAR(32) NOT NULL CHECK (missing_policy IN
    ('REQUIRE_PRESENT','DISTINGUISH_STATES')),
  PRIMARY KEY(method_revision_id,ordinal),
  CONSTRAINT uq_hyb_method_input_key UNIQUE
    (method_revision_id,form_version_id,field_code),
  CONSTRAINT fk_hyb_input_field FOREIGN KEY (form_version_id,field_code)
    REFERENCES fin_lib.lib_form_field(form_version_id,field_code)
);

CREATE TABLE fin_calc.method_output_binding (
  method_revision_id UUID NOT NULL REFERENCES fin_calc.method_revision(id),
  ordinal INT NOT NULL CHECK(ordinal BETWEEN 0 AND 1023),
  output_code VARCHAR(128) NOT NULL CHECK(length(trim(output_code))>0),
  unit_code VARCHAR(64) NOT NULL CHECK(length(trim(unit_code))>0),
  numeric_scale INT NOT NULL CHECK(numeric_scale BETWEEN 0 AND 12),
  missing_policy VARCHAR(32) NOT NULL CHECK(missing_policy IN
    ('PROPAGATE','INVALID','BLOCK')),
  PRIMARY KEY(method_revision_id,ordinal),
  CONSTRAINT uq_hyb_method_output_code UNIQUE(method_revision_id,output_code)
);

-- Future multi-form plan scope; FK prevents cross-organization binding,
-- while trigger also prevents cross-project and falsified FactSet SHA.
CREATE TABLE fin_calc.plan_factset_binding (
  plan_id UUID NOT NULL,
  organization_id UUID NOT NULL,
  plan_scope_key CHAR(64) NOT NULL,
  ordinal INT NOT NULL CHECK (ordinal BETWEEN 0 AND 1023),
  source_factset_id UUID NOT NULL,
  source_scope_key CHAR(64) NOT NULL,
  source_factset_sha256 CHAR(64) NOT NULL
    CHECK (source_factset_sha256 ~ '^[0-9a-f]{64}$'),
  PRIMARY KEY(plan_id,ordinal),
  CONSTRAINT uq_hyb_plan_factset UNIQUE(plan_id,source_factset_id),
  CONSTRAINT fk_hyb_binding_plan FOREIGN KEY
    (plan_id,organization_id,plan_scope_key)
    REFERENCES fin_calc.calc_plan(id,organization_id,scope_key),
  CONSTRAINT fk_hyb_binding_factset FOREIGN KEY
    (source_factset_id,organization_id,source_scope_key)
    REFERENCES fin_fact.fact_set(id,organization_id,scope_key)
);

-- This table is NOT a mutable toggle: it must be written atomically with
-- a future strategy-aware plan whose fingerprint contains this binding.
-- Existing F02 plans intentionally have NO execution binding backfill.
CREATE TABLE fin_calc.plan_execution_binding (
  plan_id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  scope_key CHAR(64) NOT NULL,
  method_revision_id UUID NOT NULL REFERENCES fin_calc.method_revision(id),
  strategy VARCHAR(32) NOT NULL CHECK (strategy IN
    ('JAVA_ONLY','SQL_ONLY','SQL_AGG_JAVA_FINAL')),
  execution_contract_sha256 CHAR(64) NOT NULL
    CHECK (execution_contract_sha256 ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_hyb_execution_plan FOREIGN KEY
    (plan_id,organization_id,scope_key)
    REFERENCES fin_calc.calc_plan(id,organization_id,scope_key)
);

--changeset fin-calc:027b dbms:postgresql splitStatements:false
-- Fail closed on a plausible same-org / wrong-project or mismatched SHA.
-- Future contracts may allow multi-project sources only via a separate
-- reviewed policy; this pilot does not.
CREATE FUNCTION fin_calc.validate_plan_factset_binding()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  p_project UUID;
  p_entity UUID;
  p_business UUID;
  f_project UUID;
  f_entity UUID;
  f_business UUID;
  saved_sha CHAR(64);
BEGIN
  SELECT f.project_id,f.economic_entity_id,f.business_scope_id
  INTO p_project,p_entity,p_business
  FROM fin_calc.calc_plan p
  JOIN fin_fact.fact_set f ON f.id=p.factset_id
  WHERE p.id=NEW.plan_id AND p.organization_id=NEW.organization_id
    AND p.scope_key=NEW.plan_scope_key;
  SELECT f.project_id,f.economic_entity_id,f.business_scope_id,f.factset_sha256
  INTO f_project,f_entity,f_business,saved_sha
  FROM fin_fact.fact_set f
  WHERE f.id=NEW.source_factset_id
    AND f.organization_id=NEW.organization_id
    AND f.scope_key=NEW.source_scope_key;
  IF p_project IS NULL OR f_project IS NULL OR p_project<>f_project
      OR p_entity<>f_entity OR p_business<>f_business
      OR saved_sha<>NEW.source_factset_sha256 THEN
    RAISE EXCEPTION 'CALC_HYB_SOURCE_BINDING_MISMATCH' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

--changeset fin-calc:027c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_hyb_factset_binding_guard
  BEFORE INSERT ON fin_calc.plan_factset_binding
  FOR EACH ROW EXECUTE FUNCTION fin_calc.validate_plan_factset_binding();

-- Registry, method inputs, outputs and execution bindings are immutable.
-- No defaults or candidate labels produce a financial approval.
CREATE TRIGGER trg_hyb_revision_immutable BEFORE UPDATE OR DELETE
  ON fin_calc.method_revision FOR EACH ROW
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_hyb_input_immutable BEFORE UPDATE OR DELETE
  ON fin_calc.method_input_binding FOR EACH ROW
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_hyb_output_immutable BEFORE UPDATE OR DELETE
  ON fin_calc.method_output_binding FOR EACH ROW
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_hyb_plan_source_immutable BEFORE UPDATE OR DELETE
  ON fin_calc.plan_factset_binding FOR EACH ROW
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_hyb_plan_execution_immutable BEFORE UPDATE OR DELETE
  ON fin_calc.plan_execution_binding FOR EACH ROW
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_hyb_revision_no_truncate BEFORE TRUNCATE
  ON fin_calc.method_revision FOR EACH STATEMENT
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_hyb_input_no_truncate BEFORE TRUNCATE
  ON fin_calc.method_input_binding FOR EACH STATEMENT
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_hyb_output_no_truncate BEFORE TRUNCATE
  ON fin_calc.method_output_binding FOR EACH STATEMENT
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_hyb_plan_source_no_truncate BEFORE TRUNCATE
  ON fin_calc.plan_factset_binding FOR EACH STATEMENT
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_hyb_plan_execution_no_truncate BEFORE TRUNCATE
  ON fin_calc.plan_execution_binding FOR EACH STATEMENT
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE INDEX idx_hyb_input_version_field
  ON fin_calc.method_input_binding(form_version_id,field_code);
CREATE INDEX idx_hyb_plan_source_factset
  ON fin_calc.plan_factset_binding(source_factset_id);
