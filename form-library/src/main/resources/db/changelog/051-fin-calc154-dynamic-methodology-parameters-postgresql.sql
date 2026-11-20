--liquibase formatted sql
--changeset fin-calc:051a dbms:postgresql splitStatements:true
-- Methodology selector foundation. Immutable, editorial/UAT only.
-- No connection to calc_job or production financial admission.
CREATE TABLE fin_calc.methodology_family (
 family_code varchar(64) PRIMARY KEY CHECK(family_code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
 domain_code varchar(64) NOT NULL,
 description text NOT NULL,
 family_state varchar(32) NOT NULL CHECK(family_state='DESIGN_ONLY'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE fin_calc.methodology_variant_revision (
 id uuid PRIMARY KEY,
 family_code varchar(64) NOT NULL REFERENCES fin_calc.methodology_family(family_code),
 variant_code varchar(64) NOT NULL CHECK(variant_code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
 revision_number integer NOT NULL CHECK(revision_number>0),
 input_basis varchar(64) NOT NULL,
 rate_basis varchar(64) NOT NULL,
 result_basis varchar(64) NOT NULL,
 variant_contract_sha256 char(64) NOT NULL
   CHECK(variant_contract_sha256 ~ '^[0-9a-f]{64}$'),
 registry_state varchar(32) NOT NULL DEFAULT 'DESIGN_ONLY'
   CHECK(registry_state='DESIGN_ONLY'),
 financial_s2_approved boolean NOT NULL DEFAULT false CHECK(NOT financial_s2_approved),
 production_admitted boolean NOT NULL DEFAULT false CHECK(NOT production_admitted),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE (family_code,variant_code,revision_number),
 UNIQUE (id,family_code),
 CONSTRAINT ck_dcf_valuation_compatibility CHECK(
    family_code<>'DCF_VALUATION'
    OR ((input_basis='FCFF' AND rate_basis='WACC'
      AND result_basis='ENTERPRISE_VALUE')
      OR (input_basis='FCFE' AND rate_basis='COST_OF_EQUITY'
      AND result_basis='EQUITY_VALUE'))
 )
);

-- Contract per family, NOT a free-form user-defined SQL expression.
CREATE TABLE fin_calc.methodology_parameter_definition (
 family_code varchar(64) NOT NULL REFERENCES fin_calc.methodology_family(family_code),
 parameter_code varchar(80) NOT NULL CHECK(parameter_code ~ '^[a-z][a-zA-Z0-9]{2,79}$'),
 value_type varchar(24) NOT NULL CHECK(value_type IN ('DECIMAL','DATE','UUID','ENUM')),
 required boolean NOT NULL,
 enum_options_jsonb jsonb,
 min_exclusive numeric,
 max_exclusive numeric,
 unit_code varchar(32),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(family_code,parameter_code),
 CHECK ((value_type='ENUM' AND enum_options_jsonb IS NOT NULL
    AND jsonb_typeof(enum_options_jsonb)='array'
    AND jsonb_array_length(enum_options_jsonb)>0)
    OR (value_type<>'ENUM' AND enum_options_jsonb IS NULL)),
 CHECK (value_type='DECIMAL'
   OR (min_exclusive IS NULL AND max_exclusive IS NULL)),
 CHECK (min_exclusive IS NULL OR max_exclusive IS NULL
   OR min_exclusive<max_exclusive)
);

-- User selection is an immutable SNAPSHOT for a concrete project/scenario.
-- A later plan must bind to its exact id + SHA, never "latest".
CREATE TABLE fin_calc.methodology_selection_revision (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 project_id uuid NOT NULL,
 scenario_id uuid NOT NULL,
 family_code varchar(64) NOT NULL REFERENCES fin_calc.methodology_family(family_code),
 variant_revision_id uuid NOT NULL,
 parameter_values_jsonb jsonb NOT NULL
   CHECK(jsonb_typeof(parameter_values_jsonb)='object'),
 params_sha256 char(64) NOT NULL CHECK(params_sha256 ~ '^[0-9a-f]{64}$'),
 selection_sha256 char(64) NOT NULL CHECK(selection_sha256 ~ '^[0-9a-f]{64}$'),
 selected_by varchar(128) NOT NULL CHECK(length(trim(selected_by))>0),
 review_state varchar(32) NOT NULL DEFAULT 'UAT_CANDIDATE'
   CHECK(review_state='UAT_CANDIDATE'),
 financial_s2_approved boolean NOT NULL DEFAULT false CHECK(NOT financial_s2_approved),
 production_admitted boolean NOT NULL DEFAULT false CHECK(NOT production_admitted),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT fk_msel_variant_family FOREIGN KEY(variant_revision_id,family_code)
   REFERENCES fin_calc.methodology_variant_revision(id,family_code),
 CONSTRAINT uq_msel_scope_content UNIQUE
   (organization_id,project_id,scenario_id,family_code,selection_sha256)
);
CREATE INDEX idx_msel_exact_scope
 ON fin_calc.methodology_selection_revision(
   organization_id,project_id,scenario_id,family_code,created_at DESC);

--changeset fin-calc:051b dbms:postgresql splitStatements:false
-- Validate typed JSON without executing user expressions.
CREATE FUNCTION fin_calc.validate_methodology_selection_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  p record;
  current_value jsonb;
  terminal text;
  rate numeric;
  growth numeric;
  selected_variant record;
BEGIN
  SELECT * INTO selected_variant
    FROM fin_calc.methodology_variant_revision
    WHERE id=NEW.variant_revision_id AND family_code=NEW.family_code
      AND registry_state='DESIGN_ONLY';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'METHOD_SELECTION_VARIANT_MISSING'
      USING ERRCODE='23514';
  END IF;

  IF EXISTS(
    SELECT 1 FROM jsonb_object_keys(NEW.parameter_values_jsonb) AS k(parameter)
    WHERE NOT EXISTS(
      SELECT 1 FROM fin_calc.methodology_parameter_definition d
      WHERE d.family_code=NEW.family_code AND d.parameter_code=k.parameter))
  THEN
    RAISE EXCEPTION 'METHOD_SELECTION_UNKNOWN_PARAMETER'
      USING ERRCODE='23514';
  END IF;

  FOR p IN SELECT * FROM fin_calc.methodology_parameter_definition
    WHERE family_code=NEW.family_code ORDER BY parameter_code
  LOOP
    current_value:=NEW.parameter_values_jsonb->p.parameter_code;
    IF current_value IS NULL THEN
      IF p.required THEN
        RAISE EXCEPTION 'METHOD_SELECTION_REQUIRED_PARAMETER:%',p.parameter_code
          USING ERRCODE='23514';
      END IF;
      CONTINUE;
    END IF;
    IF current_value='null'::jsonb THEN
      RAISE EXCEPTION 'METHOD_SELECTION_NULL_PARAMETER:%',p.parameter_code
        USING ERRCODE='23514';
    END IF;
    IF p.value_type='DECIMAL' THEN
      IF jsonb_typeof(current_value)<>'number' OR
        (p.min_exclusive IS NOT NULL AND
          (current_value::text)::numeric<=p.min_exclusive) OR
        (p.max_exclusive IS NOT NULL AND
          (current_value::text)::numeric>=p.max_exclusive)
      THEN
        RAISE EXCEPTION 'METHOD_SELECTION_DECIMAL_INVALID:%',p.parameter_code
          USING ERRCODE='23514';
      END IF;
    ELSIF p.value_type='ENUM' THEN
      IF jsonb_typeof(current_value)<>'string'
          OR NOT (p.enum_options_jsonb ? (NEW.parameter_values_jsonb->>p.parameter_code)) THEN
        RAISE EXCEPTION 'METHOD_SELECTION_ENUM_INVALID:%',p.parameter_code
          USING ERRCODE='23514';
      END IF;
    ELSIF p.value_type='UUID' THEN
      IF jsonb_typeof(current_value)<>'string'
          OR (NEW.parameter_values_jsonb->>p.parameter_code)
              !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
        RAISE EXCEPTION 'METHOD_SELECTION_UUID_INVALID:%',p.parameter_code
          USING ERRCODE='23514';
      END IF;
    ELSIF p.value_type='DATE' THEN
      IF jsonb_typeof(current_value)<>'string'
          OR (NEW.parameter_values_jsonb->>p.parameter_code)
              !~ '^\d{4}-\d{2}-\d{2}$' THEN
        RAISE EXCEPTION 'METHOD_SELECTION_DATE_INVALID:%',p.parameter_code
          USING ERRCODE='23514';
      END IF;
    END IF;
  END LOOP;
  IF NEW.family_code='DCF_VALUATION' THEN
    terminal:=NEW.parameter_values_jsonb->>'terminalMethod';
    rate:=(NEW.parameter_values_jsonb->>'discountRate')::numeric;
    IF terminal='NONE' THEN
      IF NEW.parameter_values_jsonb ? 'terminalGrowthRate'
         OR NEW.parameter_values_jsonb ? 'exitMultiple' THEN
        RAISE EXCEPTION 'DCF_TERMINAL_NONE_HAS_EXTRA_PARAMETERS' USING ERRCODE='23514';
      END IF;
    ELSIF terminal='GORDON_GROWTH' THEN
      IF NOT NEW.parameter_values_jsonb ? 'terminalGrowthRate'
         OR NEW.parameter_values_jsonb ? 'exitMultiple' THEN
        RAISE EXCEPTION 'DCF_GORDON_PARAMETERS_INVALID' USING ERRCODE='23514';
      END IF;
      growth:=(NEW.parameter_values_jsonb->>'terminalGrowthRate')::numeric;
      IF growth>=rate THEN
        RAISE EXCEPTION 'DCF_GROWTH_MUST_BE_LOWER_THAN_RATE' USING ERRCODE='23514';
      END IF;
    ELSIF terminal='EXIT_MULTIPLE' THEN
      IF NOT NEW.parameter_values_jsonb ? 'exitMultiple'
         OR NEW.parameter_values_jsonb ? 'terminalGrowthRate' THEN
        RAISE EXCEPTION 'DCF_EXIT_MULTIPLE_PARAMETERS_INVALID' USING ERRCODE='23514';
      END IF;
    END IF;
  END IF;
  -- PostgreSQL 16 owns SHA verification: callers cannot forge approval evidence.
  IF NEW.params_sha256 IS DISTINCT FROM
      encode(sha256(convert_to(
        'FIN-METHOD-PARAMS-V1|'||NEW.parameter_values_jsonb::text,'UTF8')),'hex') THEN
    RAISE EXCEPTION 'METHOD_SELECTION_PARAMS_SHA_DRIFT' USING ERRCODE='23514';
  END IF;
  IF NEW.selection_sha256 IS DISTINCT FROM
      encode(sha256(convert_to(
        'FIN-METHOD-SELECTION-V1|'||
        NEW.organization_id::text||'|'||NEW.project_id::text||'|'||
        NEW.scenario_id::text||'|'||NEW.family_code||'|'||
        NEW.variant_revision_id::text||'|'||selected_variant.variant_contract_sha256||
        '|'||NEW.params_sha256,'UTF8')),'hex') THEN
    RAISE EXCEPTION 'METHOD_SELECTION_SHA_DRIFT' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$;

--changeset fin-calc:051c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_methodology_selection_check
  BEFORE INSERT ON fin_calc.methodology_selection_revision
  FOR EACH ROW EXECUTE FUNCTION fin_calc.validate_methodology_selection_revision();

CREATE TRIGGER trg_methodology_family_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.methodology_family FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_methodology_family_no_truncate
 BEFORE TRUNCATE ON fin_calc.methodology_family FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_methodology_variant_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.methodology_variant_revision FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_methodology_variant_no_truncate
 BEFORE TRUNCATE ON fin_calc.methodology_variant_revision FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_methodology_parameter_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.methodology_parameter_definition FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_methodology_parameter_no_truncate
 BEFORE TRUNCATE ON fin_calc.methodology_parameter_definition FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_methodology_selection_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.methodology_selection_revision FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_methodology_selection_no_truncate
 BEFORE TRUNCATE ON fin_calc.methodology_selection_revision FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

--changeset fin-calc:051d dbms:postgresql splitStatements:true
INSERT INTO fin_calc.methodology_family(family_code,domain_code,description,family_state)
 VALUES ('DCF_VALUATION','CORPORATE_FINANCE',
 'Discounted cash flow valuation; FCFF and FCFE variants, UAT candidate only',
 'DESIGN_ONLY');

INSERT INTO fin_calc.methodology_variant_revision(
 id,family_code,variant_code,revision_number,
 input_basis,rate_basis,result_basis,variant_contract_sha256)
 VALUES
 ('78c32b48-791e-489a-9b8e-6f88cb1b4e01',
 'DCF_VALUATION','FCFF_WACC',1,'FCFF','WACC','ENTERPRISE_VALUE',
 '6c98fda5c20c4abde2da9570591e98d87fea3da0d86ac80c80096839913e9384'),
 ('78c32b48-791e-489a-9b8e-6f88cb1b4e02',
 'DCF_VALUATION','FCFE_KE',1,'FCFE','COST_OF_EQUITY','EQUITY_VALUE',
 'fe7ec9c5b0075889e1fbf267fa3ba85ac5e55d853328e7657e19c5b9ba96fcd9');

INSERT INTO fin_calc.methodology_parameter_definition(
 family_code,parameter_code,value_type,required,enum_options_jsonb,
 min_exclusive,unit_code) VALUES
 ('DCF_VALUATION','discountRate','DECIMAL',true,NULL,-1,'FRACTION'),
 ('DCF_VALUATION','cashflowRevisionId','UUID',true,NULL,NULL,'REVISION'),
 ('DCF_VALUATION','valuationDate','DATE',true,NULL,NULL,'DATE'),
 ('DCF_VALUATION','terminalMethod','ENUM',true,
  '["NONE","GORDON_GROWTH","EXIT_MULTIPLE"]'::jsonb,NULL,'ENUM'),
 ('DCF_VALUATION','terminalGrowthRate','DECIMAL',false,NULL,-1,'FRACTION'),
 ('DCF_VALUATION','exitMultiple','DECIMAL',false,NULL,0,'MULTIPLE'),
 ('DCF_VALUATION','rateSourceRevisionId','UUID',true,NULL,NULL,'REVISION'),
 ('DCF_VALUATION','equityBridgeRevisionId','UUID',false,NULL,NULL,'REVISION');

--changeset fin-calc:051e dbms:postgresql splitStatements:false
-- Rehash the immutable variant identity during each read and admission.
-- No invented contract SHA may make the DCF combination executable.
CREATE FUNCTION fin_calc.verify_method_variant_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.variant_contract_sha256 IS DISTINCT FROM
      encode(sha256(convert_to('FIN-METHOD-VARIANT-V1|'||
        NEW.family_code||'|'||NEW.variant_code||'|'||
        NEW.revision_number::text||'|'||NEW.input_basis||'|'||
        NEW.rate_basis||'|'||NEW.result_basis,'UTF8')),'hex')
  THEN
    RAISE EXCEPTION 'METHOD_VARIANT_SHA_DRIFT' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

--changeset fin-calc:051f dbms:postgresql splitStatements:true
CREATE TRIGGER trg_method_variant_rehash BEFORE INSERT
 ON fin_calc.methodology_variant_revision FOR EACH ROW
 EXECUTE FUNCTION fin_calc.verify_method_variant_revision();
