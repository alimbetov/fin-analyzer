--liquibase formatted sql
--changeset fin-calc:036a dbms:postgresql splitStatements:true
-- CALC-3B: explicit unresolved per-form calculation obligation and
-- typed version-pinned input/dependency contracts. No finance S2/admission.
-- No Java-seeded catalog is read during this schema-only migration.

CREATE TABLE fin_calc.form_calculation_requirement_revision (
  id UUID PRIMARY KEY,
  form_code VARCHAR(128) NOT NULL,
  form_version_id UUID NOT NULL REFERENCES fin_lib.lib_form_version(id),
  source_schema_sha256 CHAR(64) NOT NULL
    CHECK(source_schema_sha256 ~ '^[0-9a-f]{64}$'),
  requirement_version VARCHAR(64) NOT NULL,
  mandatory_output_state VARCHAR(32) NOT NULL
    CHECK(mandatory_output_state='REVIEW_REQUIRED'),
  mandatory_outputs_jsonb JSONB
    CHECK(mandatory_outputs_jsonb IS NULL),
  obligation_blockers TEXT NOT NULL
    CHECK(position('MANDATORY_OUTPUT_SET_NOT_AUTHORED' IN obligation_blockers)>0
      AND position('FINANCE_S2' IN obligation_blockers)>0),
  approval_state VARCHAR(32) NOT NULL DEFAULT 'NOT_S2_APPROVED'
    CHECK(approval_state='NOT_S2_APPROVED'),
  source_definition_sha256 CHAR(64) NOT NULL
    CHECK(source_definition_sha256 ~ '^[0-9a-f]{64}$'),
  binding_sha256 CHAR(64) NOT NULL
    CHECK(binding_sha256 ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_calc3b_form_requirement UNIQUE(form_version_id,requirement_version)
);
CREATE INDEX idx_calc3b_form_requirement_code
  ON fin_calc.form_calculation_requirement_revision(form_code);

CREATE TABLE fin_calc.formula_typed_input_binding (
  formula_revision_id UUID NOT NULL
    REFERENCES fin_calc.formula_contract_revision(id),
  ordinal INT NOT NULL CHECK(ordinal BETWEEN 0 AND 15),
  source_metric_code VARCHAR(160) NOT NULL,
  source_kind VARCHAR(32) NOT NULL CHECK(source_kind IN
    ('PHYSICAL_NUMERIC_FIELD','FORMULA_CANDIDATE')),
  source_form_version_id UUID NOT NULL REFERENCES fin_lib.lib_form_version(id),
  source_schema_sha256 CHAR(64) NOT NULL
    CHECK(source_schema_sha256 ~ '^[0-9a-f]{64}$'),
  source_field_code VARCHAR(128),
  field_type VARCHAR(16) CHECK(field_type IN ('MONEY','DECIMAL','INTEGER')),
  source_formula_revision_id UUID
    REFERENCES fin_calc.formula_contract_revision(id),
  period_policy VARCHAR(32) NOT NULL CHECK(period_policy IN
    ('AS_OF_CANDIDATE','INTERVAL_CANDIDATE','INTERVAL_REQUIRED')),
  requiredness_state VARCHAR(32) NOT NULL
    CHECK(requiredness_state='REVIEW_REQUIRED'),
  binding_sha256 CHAR(64) NOT NULL CHECK(binding_sha256 ~ '^[0-9a-f]{64}$'),
  PRIMARY KEY(formula_revision_id,ordinal),
  CONSTRAINT uq_calc3b_formula_input UNIQUE(formula_revision_id,source_metric_code),
  CONSTRAINT fk_calc3b_input_physical_field FOREIGN KEY
    (source_form_version_id,source_field_code)
    REFERENCES fin_lib.lib_form_field(form_version_id,field_code),
  CONSTRAINT ck_calc3b_input_kind CHECK(
    (source_kind='PHYSICAL_NUMERIC_FIELD'
      AND source_field_code IS NOT NULL AND field_type IS NOT NULL
      AND source_formula_revision_id IS NULL)
    OR (source_kind='FORMULA_CANDIDATE'
      AND source_field_code IS NULL AND field_type IS NULL
      AND source_formula_revision_id IS NOT NULL))
);

-- A model's declared input-form list is a design dependency, NOT a
-- calculated form-to-form formula or a qualified model approval.
CREATE TABLE fin_calc.model_candidate_revision (
  id UUID PRIMARY KEY,
  model_code VARCHAR(32) NOT NULL,
  catalog_revision VARCHAR(64) NOT NULL,
  model_pack VARCHAR(64) NOT NULL,
  catalog_state VARCHAR(32) NOT NULL
    CHECK(catalog_state IN ('DESIGN_ONLY','CALC_PILOT')),
  formula_state VARCHAR(32) NOT NULL
    CHECK(formula_state='NOT_AUTHORED'),
  requirement_state VARCHAR(32) NOT NULL
    CHECK(requirement_state='REVIEW_REQUIRED'),
  financial_approval_state VARCHAR(32) NOT NULL DEFAULT 'NOT_S2_APPROVED'
    CHECK(financial_approval_state='NOT_S2_APPROVED'),
  definition_sha256 CHAR(64) NOT NULL CHECK(definition_sha256 ~ '^[0-9a-f]{64}$'),
  binding_sha256 CHAR(64) NOT NULL CHECK(binding_sha256 ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_calc3b_model_revision UNIQUE(model_code,catalog_revision)
);

CREATE TABLE fin_calc.model_candidate_input_form (
  model_revision_id UUID NOT NULL REFERENCES fin_calc.model_candidate_revision(id),
  ordinal INT NOT NULL CHECK(ordinal BETWEEN 0 AND 1023),
  source_form_code VARCHAR(128) NOT NULL,
  source_form_version_id UUID NOT NULL REFERENCES fin_lib.lib_form_version(id),
  source_schema_sha256 CHAR(64) NOT NULL CHECK(source_schema_sha256 ~ '^[0-9a-f]{64}$'),
  content_mode VARCHAR(32) NOT NULL
    CHECK(content_mode IN ('SINGLE_RECORD','ROWSET','EVIDENCE')),
  numeric_eligible BOOLEAN NOT NULL,
  dependency_state VARCHAR(32) NOT NULL
    CHECK(dependency_state='MODEL_INPUT_CANDIDATE'),
  approval_state VARCHAR(32) NOT NULL CHECK(approval_state='NOT_S2_APPROVED'),
  binding_sha256 CHAR(64) NOT NULL CHECK(binding_sha256 ~ '^[0-9a-f]{64}$'),
  PRIMARY KEY(model_revision_id,ordinal),
  CONSTRAINT uq_calc3b_model_input_form UNIQUE(model_revision_id,source_form_code),
  CONSTRAINT ck_calc3b_evidence_not_numeric CHECK
    (numeric_eligible=(content_mode IN ('SINGLE_RECORD','ROWSET')))
);

CREATE TABLE fin_calc.model_candidate_output_metric (
  model_revision_id UUID NOT NULL REFERENCES fin_calc.model_candidate_revision(id),
  ordinal INT NOT NULL CHECK(ordinal BETWEEN 0 AND 1023),
  output_metric_code VARCHAR(160) NOT NULL,
  formula_state VARCHAR(32) NOT NULL CHECK(formula_state='NOT_AUTHORED'),
  requiredness_state VARCHAR(32) NOT NULL CHECK(requiredness_state='REVIEW_REQUIRED'),
  approval_state VARCHAR(32) NOT NULL CHECK(approval_state='NOT_S2_APPROVED'),
  PRIMARY KEY(model_revision_id,ordinal),
  CONSTRAINT uq_calc3b_model_output UNIQUE(model_revision_id,output_metric_code)
);

--changeset fin-calc:036b dbms:postgresql splitStatements:true
-- All candidate requirement and lineage snapshots are strictly immutable.
CREATE TRIGGER trg_calc3b_requirement_immutable BEFORE UPDATE OR DELETE
 ON fin_calc.form_calculation_requirement_revision FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3b_requirement_no_truncate BEFORE TRUNCATE
 ON fin_calc.form_calculation_requirement_revision FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3b_input_immutable BEFORE UPDATE OR DELETE
 ON fin_calc.formula_typed_input_binding FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3b_input_no_truncate BEFORE TRUNCATE
 ON fin_calc.formula_typed_input_binding FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3b_model_immutable BEFORE UPDATE OR DELETE
 ON fin_calc.model_candidate_revision FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3b_model_no_truncate BEFORE TRUNCATE
 ON fin_calc.model_candidate_revision FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3b_model_input_immutable BEFORE UPDATE OR DELETE
 ON fin_calc.model_candidate_input_form FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3b_model_input_no_truncate BEFORE TRUNCATE
 ON fin_calc.model_candidate_input_form FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3b_model_output_immutable BEFORE UPDATE OR DELETE
 ON fin_calc.model_candidate_output_metric FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3b_model_output_no_truncate BEFORE TRUNCATE
 ON fin_calc.model_candidate_output_metric FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
