--liquibase formatted sql
--changeset fin-calc:046a dbms:postgresql splitStatements:true
-- CALC-154: GENERAL mathematical operator UAT controls for the ten existing
-- formula candidates. These are not financial ground truth or independent S2.
-- Do not modify the narrower historical F02-only controls from migration 040.
CREATE TABLE fin_calc.calc154_synthetic_control_case (
 id uuid PRIMARY KEY,
 formula_revision_id uuid NOT NULL REFERENCES fin_calc.formula_contract_revision(id),
 form_version_id uuid NOT NULL REFERENCES fin_lib.lib_form_version(id),
 source_schema_sha256 char(64) NOT NULL
   CHECK (source_schema_sha256 ~ '^[0-9a-f]{64}$'),
 formula_binding_sha256 char(64) NOT NULL
   CHECK (formula_binding_sha256 ~ '^[0-9a-f]{64}$'),
 case_code varchar(128) NOT NULL,
 source_inputs_jsonb jsonb NOT NULL
   CHECK (jsonb_typeof(source_inputs_jsonb)='object'
     AND source_inputs_jsonb <> '{}'::jsonb),
 expected_state varchar(40) NOT NULL
   CHECK (expected_state IN ('PRESENT','INVALID_ZERO_DENOMINATOR')),
 expected_numeric numeric,
 source_evidence varchar(160) NOT NULL,
 reference_type varchar(48) NOT NULL DEFAULT 'SYNTHETIC_OPERATOR_MATH_ONLY'
   CHECK (reference_type='SYNTHETIC_OPERATOR_MATH_ONLY'),
 review_state varchar(32) NOT NULL DEFAULT 'UNREVIEWED'
   CHECK (review_state='UNREVIEWED'),
 financial_approval_state varchar(32) NOT NULL DEFAULT 'NOT_S2_APPROVED'
   CHECK (financial_approval_state='NOT_S2_APPROVED'),
 definition_sha256 char(64) NOT NULL
   CHECK (definition_sha256 ~ '^[0-9a-f]{64}$'),
 binding_sha256 char(64) NOT NULL
   CHECK (binding_sha256 ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT uq_calc154_synthetic_control_formula_case
   UNIQUE(formula_revision_id,case_code),
 CONSTRAINT ck_calc154_synthetic_control_expected_state CHECK (
   (expected_state='PRESENT' AND expected_numeric IS NOT NULL)
   OR (expected_state='INVALID_ZERO_DENOMINATOR' AND expected_numeric IS NULL))
);
CREATE INDEX idx_calc154_synthetic_control_formula
 ON fin_calc.calc154_synthetic_control_case(formula_revision_id,created_at);

--changeset fin-calc:046b dbms:postgresql splitStatements:false
-- DB-level pinning rejects forged formula version or fake S2 approval even
-- if someone bypasses the internal Java registrar.
CREATE FUNCTION fin_calc.validate_calc154_synthetic_control_case()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  c record;
BEGIN
  SELECT form_version_id,source_schema_sha256,binding_sha256,
    financial_approval_state,review_state
  INTO c FROM fin_calc.formula_contract_revision
  WHERE id=NEW.formula_revision_id;
  IF NOT FOUND
     OR c.form_version_id IS DISTINCT FROM NEW.form_version_id
     OR c.source_schema_sha256 IS DISTINCT FROM NEW.source_schema_sha256
     OR c.binding_sha256 IS DISTINCT FROM NEW.formula_binding_sha256
     OR c.financial_approval_state IS DISTINCT FROM 'NOT_S2_APPROVED'
     OR c.review_state NOT IN ('CANDIDATE_UNQUALIFIED','UAT_TEST_ONLY')
  THEN
    RAISE EXCEPTION 'CALC154_CONTROL_FORMULA_BINDING_INVALID'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

--changeset fin-calc:046c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_calc154_synthetic_control_validate
 BEFORE INSERT ON fin_calc.calc154_synthetic_control_case FOR EACH ROW
 EXECUTE FUNCTION fin_calc.validate_calc154_synthetic_control_case();
CREATE TRIGGER trg_calc154_synthetic_control_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.calc154_synthetic_control_case FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc154_synthetic_control_no_truncate
 BEFORE TRUNCATE ON fin_calc.calc154_synthetic_control_case FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
