--liquibase formatted sql
--changeset fin-calc:043a dbms:postgresql splitStatements:true
-- CALC-4D.3/4: strictly isolated UAT-only calculation staging.
-- No permissions to fin_calc.calc_plan / calc_job / calc_result / calc_current_result;
-- the HYB-2B admission and financial S2 gates remain unchanged.
CREATE TABLE fin_calc.calc4d_uat_plan (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  project_id uuid NOT NULL,
  execution_fingerprint char(64) NOT NULL
    CHECK (execution_fingerprint ~ '^[0-9a-f]{64}$'),
  dag_sha256 char(64) NOT NULL CHECK (dag_sha256 ~ '^[0-9a-f]{64}$'),
  source_manifest_sha256 char(64) NOT NULL
    CHECK (source_manifest_sha256 ~ '^[0-9a-f]{64}$'),
  input_plan_sha256 char(64) NOT NULL
    CHECK (input_plan_sha256 ~ '^[0-9a-f]{64}$'),
  formula_revision_ids_jsonb jsonb NOT NULL
    CHECK (jsonb_typeof(formula_revision_ids_jsonb)='array'
      AND jsonb_array_length(formula_revision_ids_jsonb)>0),
  source_factsets_jsonb jsonb NOT NULL
    CHECK (jsonb_typeof(source_factsets_jsonb)='array'
      AND jsonb_array_length(source_factsets_jsonb)>0),
  currency_code char(3) NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
  period_type varchar(32) NOT NULL,
  period_start date NOT NULL,
  period_end_exclusive date NOT NULL,
  admission_state varchar(48) NOT NULL DEFAULT 'UAT_ONLY_NOT_S2_APPROVED'
    CHECK (admission_state='UAT_ONLY_NOT_S2_APPROVED'),
  qualification_mode varchar(32) NOT NULL DEFAULT 'SYNTHETIC_TEST_ONLY'
    CHECK (qualification_mode='SYNTHETIC_TEST_ONLY'),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_calc4d_uat_period CHECK (period_start<period_end_exclusive),
  CONSTRAINT uq_calc4d_uat_plan UNIQUE(organization_id,execution_fingerprint),
  CONSTRAINT uq_calc4d_uat_plan_org UNIQUE(id,organization_id)
);
CREATE INDEX idx_calc4d_uat_plan_project
  ON fin_calc.calc4d_uat_plan(organization_id,project_id,created_at);

CREATE TABLE fin_calc.calc4d_uat_result (
  id uuid PRIMARY KEY,
  plan_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  execution_fingerprint char(64) NOT NULL
    CHECK (execution_fingerprint ~ '^[0-9a-f]{64}$'),
  input_observations_sha256 char(64) NOT NULL
    CHECK (input_observations_sha256 ~ '^[0-9a-f]{64}$'),
  output_values_sha256 char(64) NOT NULL
    CHECK (output_values_sha256 ~ '^[0-9a-f]{64}$'),
  result_sha256 char(64) NOT NULL
    CHECK (result_sha256 ~ '^[0-9a-f]{64}$'),
  output_jsonb jsonb NOT NULL CHECK (jsonb_typeof(output_jsonb)='array'),
  result_state varchar(40) NOT NULL DEFAULT 'UAT_SYNTHETIC_ONLY'
    CHECK (result_state='UAT_SYNTHETIC_ONLY'),
  financially_approved boolean NOT NULL DEFAULT false
    CHECK (financially_approved=false),
  computed_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT fk_calc4d_uat_result_plan
    FOREIGN KEY(plan_id,organization_id)
    REFERENCES fin_calc.calc4d_uat_plan(id,organization_id),
  CONSTRAINT uq_calc4d_uat_result_plan UNIQUE(plan_id)
);
CREATE INDEX idx_calc4d_uat_result_org ON fin_calc.calc4d_uat_result(organization_id,computed_at);

--changeset fin-calc:043b dbms:postgresql splitStatements:true
-- Historical staging is append-only. A synthetic result never becomes a
-- production current result and cannot be changed into an APPROVED state.
CREATE TRIGGER trg_calc4d_uat_plan_immutable
  BEFORE UPDATE OR DELETE ON fin_calc.calc4d_uat_plan FOR EACH ROW
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc4d_uat_plan_no_truncate
  BEFORE TRUNCATE ON fin_calc.calc4d_uat_plan FOR EACH STATEMENT
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc4d_uat_result_immutable
  BEFORE UPDATE OR DELETE ON fin_calc.calc4d_uat_result FOR EACH ROW
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc4d_uat_result_no_truncate
  BEFORE TRUNCATE ON fin_calc.calc4d_uat_result FOR EACH STATEMENT
  EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
