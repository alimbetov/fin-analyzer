--liquibase formatted sql
--changeset fin-calc:040a dbms:postgresql splitStatements:true
-- CALC-3C.2: immutable candidate control examples, NOT independent S2 approval.
-- Each example is tied to one exact formula contract revision and original
-- form schema. Generated/entered expected values are evidence only; PostgreSQL
-- arithmetic and a separate independent reviewer must validate them.
CREATE TABLE fin_calc.financial_control_example_revision (
  id uuid PRIMARY KEY,
  formula_revision_id uuid NOT NULL REFERENCES fin_calc.formula_contract_revision(id),
  example_code varchar(128) NOT NULL,
  form_version_id uuid NOT NULL REFERENCES fin_lib.lib_form_version(id),
  source_schema_sha256 char(64) NOT NULL
    CHECK(source_schema_sha256 ~ '^[0-9a-f]{64}$'),
  input_values_jsonb jsonb NOT NULL CHECK(jsonb_typeof(input_values_jsonb)='object'),
  expected_value numeric NOT NULL,
  expected_state varchar(32) NOT NULL CHECK(expected_state='PRESENT'),
  expected_scale integer NOT NULL CHECK(expected_scale BETWEEN 0 AND 12),
  rounding_mode varchar(16) NOT NULL CHECK(rounding_mode='HALF_EVEN'),
  reference_type varchar(32) NOT NULL CHECK(reference_type='SYNTHETIC_UAT'),
  review_state varchar(32) NOT NULL CHECK(review_state='UNREVIEWED'),
  financial_approval_state varchar(32) NOT NULL DEFAULT 'NOT_S2_APPROVED'
    CHECK(financial_approval_state='NOT_S2_APPROVED'),
  definition_sha256 char(64) NOT NULL CHECK(definition_sha256 ~ '^[0-9a-f]{64}$'),
  binding_sha256 char(64) NOT NULL CHECK(binding_sha256 ~ '^[0-9a-f]{64}$'),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_calc3c2_example_key UNIQUE(formula_revision_id,example_code),
  CONSTRAINT uq_calc3c2_example_binding UNIQUE(formula_revision_id,binding_sha256),
  CONSTRAINT ck_calc3c2_example_payload CHECK(
    jsonb_object_length(input_values_jsonb)>0)
);
CREATE INDEX idx_calc3c2_example_formula
  ON fin_calc.financial_control_example_revision(formula_revision_id);

--changeset fin-calc:040b dbms:postgresql splitStatements:false
CREATE FUNCTION fin_calc.validate_financial_control_example()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  f RECORD;
  field_name text;
  raw jsonb;
BEGIN
  SELECT form_version_id,source_schema_sha256,review_state,formula_version,
    output_metric_code INTO f
  FROM fin_calc.formula_contract_revision
  WHERE id=NEW.formula_revision_id;
  IF NOT FOUND OR NEW.form_version_id IS DISTINCT FROM f.form_version_id
     OR NEW.source_schema_sha256 IS DISTINCT FROM f.source_schema_sha256
     OR f.review_state<>'UAT_TEST_ONLY'
     OR f.formula_version<>'F02_TEST_ONLY_V1'
     OR f.output_metric_code NOT IN
       ('F02.grossProfit','F02.grossMargin','F02.operatingResult')
  THEN
    RAISE EXCEPTION 'CALC3C2_CONTROL_EXAMPLE_NOT_TEST_PINNED' USING ERRCODE='23514';
  END IF;
  -- Pilot schema: exactly three physical money inputs, all exact NUMERIC.
  IF (SELECT count(*) FROM jsonb_object_keys(NEW.input_values_jsonb))<>3 THEN
    RAISE EXCEPTION 'CALC3C2_CONTROL_INPUT_COUNT' USING ERRCODE='23514';
  END IF;
  FOR field_name,raw IN SELECT key,value FROM jsonb_each(NEW.input_values_jsonb)
  LOOP
    IF field_name NOT IN ('netRevenue','cogs','operatingExpenses')
       OR jsonb_typeof(raw)<>'number'
       OR (raw #>> '{}')::numeric<0
    THEN
      RAISE EXCEPTION 'CALC3C2_CONTROL_INPUT_UNTYPED' USING ERRCODE='23514';
    END IF;
  END LOOP;
  RETURN NEW;
END;
$$;

--changeset fin-calc:040c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_calc3c2_control_example_validate BEFORE INSERT
  ON fin_calc.financial_control_example_revision
  FOR EACH ROW EXECUTE FUNCTION fin_calc.validate_financial_control_example();
CREATE TRIGGER trg_calc3c2_control_example_immutable BEFORE UPDATE OR DELETE
  ON fin_calc.financial_control_example_revision
  FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3c2_control_example_no_truncate BEFORE TRUNCATE
  ON fin_calc.financial_control_example_revision
  FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
