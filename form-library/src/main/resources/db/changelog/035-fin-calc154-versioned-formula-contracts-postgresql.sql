--liquibase formatted sql
--changeset fin-calc:035a dbms:postgresql splitStatements:true
-- CALC-154 CALC-3: immutable, typed, version-pinned FORMULA CANDIDATE registry.
-- Liquibase must never depend on Java-seeded fin_lib catalog rows.
-- A contract SHA is integrity evidence, NOT financial S2 authorization.
CREATE TABLE fin_calc.formula_contract_revision (
  id UUID PRIMARY KEY,
  form_code VARCHAR(128) NOT NULL,
  form_version_id UUID NOT NULL REFERENCES fin_lib.lib_form_version(id),
  source_schema_sha256 CHAR(64) NOT NULL
    CHECK (source_schema_sha256 ~ '^[0-9a-f]{64}$'),
  output_metric_code VARCHAR(160) NOT NULL,
  formula_version VARCHAR(64) NOT NULL,
  operation_code VARCHAR(32) NOT NULL CHECK (operation_code IN
    ('RECONCILE','SUBTRACT','RATIO','ROLL_FORWARD','GROUP_BY','RATIO_OF_SUMS')),
  source_metrics_jsonb JSONB NOT NULL
    CHECK (jsonb_typeof(source_metrics_jsonb)='array'
      AND jsonb_array_length(source_metrics_jsonb) BETWEEN 1 AND 16),
  period_semantics VARCHAR(32) NOT NULL CHECK (period_semantics IN
    ('AS_OF_CANDIDATE','INTERVAL_CANDIDATE','INTERVAL_REQUIRED')),
  dimension_policy VARCHAR(32) NOT NULL CHECK (dimension_policy='UNREVIEWED'),
  currency_policy VARCHAR(32) NOT NULL CHECK (currency_policy='UNREVIEWED'),
  unit_policy VARCHAR(64) NOT NULL,
  rounding_policy VARCHAR(32) NOT NULL CHECK (rounding_policy='UNREVIEWED'),
  missing_policy VARCHAR(48) NOT NULL
    CHECK (missing_policy='REQUIRE_PRESENT_CANDIDATE'),
  zero_policy VARCHAR(32) NOT NULL
    CHECK (zero_policy IN ('NOT_APPLICABLE','INVALID_CANDIDATE')),
  review_state VARCHAR(32) NOT NULL CHECK (review_state IN
    ('CANDIDATE_UNQUALIFIED','UAT_TEST_ONLY')),
  financial_approval_state VARCHAR(40) NOT NULL DEFAULT 'NOT_S2_APPROVED'
    CHECK (financial_approval_state='NOT_S2_APPROVED'),
  blocker_codes TEXT NOT NULL CHECK (position('FINANCE_S2' IN blocker_codes)>0),
  source_evidence VARCHAR(160) NOT NULL,
  definition_sha256 CHAR(64) NOT NULL CHECK(definition_sha256 ~ '^[0-9a-f]{64}$'),
  binding_sha256 CHAR(64) NOT NULL CHECK(binding_sha256 ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_calc154_formula_version UNIQUE
    (form_version_id,output_metric_code,formula_version),
  CONSTRAINT uq_calc154_formula_definition UNIQUE
    (form_version_id,definition_sha256),
  CONSTRAINT ck_calc154_uat_reference CHECK
    (review_state<>'UAT_TEST_ONLY' OR
     (form_code='F02' AND formula_version='F02_TEST_ONLY_V1')),
  CONSTRAINT ck_calc154_ratio_zero CHECK
    ((operation_code IN ('RATIO','RATIO_OF_SUMS') AND zero_policy='INVALID_CANDIDATE')
     OR (operation_code NOT IN ('RATIO','RATIO_OF_SUMS')
       AND zero_policy='NOT_APPLICABLE'))
);
CREATE INDEX idx_calc154_formula_form_version
  ON fin_calc.formula_contract_revision(form_code,form_version_id);
CREATE INDEX idx_calc154_formula_status
  ON fin_calc.formula_contract_revision(review_state);

--changeset fin-calc:035b dbms:postgresql splitStatements:true
-- No version rewrite. New versions require a new INSERT + new reviewed
-- method/config/artifact and qualification; current candidates never auto-promote.
CREATE TRIGGER trg_calc154_formula_immutable
  BEFORE UPDATE OR DELETE ON fin_calc.formula_contract_revision
  FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc154_formula_no_truncate
  BEFORE TRUNCATE ON fin_calc.formula_contract_revision
  FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
