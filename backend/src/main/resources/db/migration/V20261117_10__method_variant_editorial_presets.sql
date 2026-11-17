-- FIN-MR-R2.3 FOUNDATION: bounded, immutable editorial parameter choices.
-- An edition is a draft parameter-design snapshot, NOT a financial approval,
-- S2 review, active configuration or execution authorization.
-- Server-owned typed contracts are the only allowed parameter schemas.
CREATE TABLE fin_calc.method_variant_edition (
    edition_id UUID PRIMARY KEY,
    method_code VARCHAR(32) NOT NULL REFERENCES fin_calc.method_family(method_code),
    variant_code VARCHAR(128) NOT NULL,
    edition_no INTEGER NOT NULL CHECK (edition_no > 0),
    contract_id UUID NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    generation_policy JSONB NOT NULL CHECK (jsonb_typeof(generation_policy)='object'),
    description VARCHAR(1000) NOT NULL CHECK (length(btrim(description))>0),
    authored_by VARCHAR(200) NOT NULL CHECK (length(btrim(authored_by))>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_method_edition_contract FOREIGN KEY(method_code,variant_code,contract_id)
      REFERENCES fin_calc.method_parameter_contract(method_code,variant_code,contract_id),
    CONSTRAINT uq_method_variant_edition_no UNIQUE(method_code,variant_code,edition_no)
);

-- For a fixed CON-01 algorithm there is only ONE candidate. For CON-02,
-- explicitly input base and step (percentage points); NO financial default.
CREATE FUNCTION fin_calc.guard_method_variant_edition_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $body$
DECLARE profile TEXT;
DECLARE step NUMERIC;
DECLARE a NUMERIC;
DECLARE b NUMERIC;
BEGIN
    SELECT validation_profile INTO profile FROM fin_calc.method_parameter_contract
      WHERE contract_id=NEW.contract_id AND method_code=NEW.method_code
        AND variant_code=NEW.variant_code;
    IF profile='CON01_PARETO_FIXED_V1' THEN
        IF NEW.algorithm_version<>'CON01_PARETO_V1' OR
           NEW.generation_policy <> '{"mode":"FIXED_ONE","generatorVersion":"CON01_FIXED_ONE_V1"}'::jsonb THEN
            RAISE EXCEPTION 'FIN_METHOD_EDITION_FIXED_POLICY_REQUIRED';
        END IF;
    ELSIF profile='CON02_ABC_TYPED_V2' THEN
        IF NEW.algorithm_version<>'CON02_ABC_BOUNDARY_V1' OR
           (SELECT count(*) FROM jsonb_object_keys(NEW.generation_policy))<>5 OR
           NEW.generation_policy->>'mode' IS DISTINCT FROM 'GRID_NEAR_BASE' OR
           NEW.generation_policy->>'generatorVersion' IS DISTINCT FROM 'CON02_GRID_NEAR_BASE_V1' OR
           jsonb_typeof(NEW.generation_policy->'stepPercentPoints') IS DISTINCT FROM 'number' OR
           jsonb_typeof(NEW.generation_policy->'baseA') IS DISTINCT FROM 'number' OR
           jsonb_typeof(NEW.generation_policy->'baseB') IS DISTINCT FROM 'number' THEN
            RAISE EXCEPTION 'FIN_METHOD_EDITION_GRID_POLICY_INVALID';
        END IF;
        step := (NEW.generation_policy->>'stepPercentPoints')::numeric;
        a := (NEW.generation_policy->>'baseA')::numeric;
        b := (NEW.generation_policy->>'baseB')::numeric;
        IF step<=0 OR step>10 OR a<=0 OR a>=b OR b>=100 THEN
            RAISE EXCEPTION 'FIN_METHOD_EDITION_GRID_OUT_OF_RANGE';
        END IF;
    ELSE
        RAISE EXCEPTION 'FIN_METHOD_EDITION_UNVALIDATED_CONTRACT';
    END IF;
    RETURN NEW;
END;
$body$;
CREATE TRIGGER trg_method_edition_profile_insert BEFORE INSERT
ON fin_calc.method_variant_edition FOR EACH ROW
EXECUTE FUNCTION fin_calc.guard_method_variant_edition_insert();

CREATE TABLE fin_calc.method_variant_edition_option (
    edition_id UUID NOT NULL REFERENCES fin_calc.method_variant_edition(edition_id),
    option_no SMALLINT NOT NULL CHECK (option_no BETWEEN 1 AND 10),
    parameters JSONB NOT NULL CHECK (jsonb_typeof(parameters)='object'),
    params_sha256 CHAR(64) NOT NULL CHECK (params_sha256 ~ '^[0-9a-f]{64}$'),
    option_sha256 CHAR(64) NOT NULL CHECK (option_sha256 ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(edition_id,option_no),
    CONSTRAINT uq_edition_option_params UNIQUE(edition_id,params_sha256)
);

-- Keep output hashes PostgreSQL-owned and match R2.1 normalization rules.
-- Lock the parent to serialize option insertion against edition freezing.
CREATE FUNCTION fin_calc.guard_method_edition_option_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $body$
DECLARE ed fin_calc.method_variant_edition%ROWTYPE;
DECLARE profile TEXT;
DECLARE a NUMERIC;
DECLARE b NUMERIC;
DECLARE step NUMERIC;
BEGIN
    SELECT * INTO ed FROM fin_calc.method_variant_edition
     WHERE edition_id=NEW.edition_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'FIN_METHOD_EDITION_NOT_FOUND'; END IF;
    IF EXISTS (SELECT 1 FROM fin_calc.method_variant_edition_freeze
               WHERE edition_id=NEW.edition_id) THEN
        RAISE EXCEPTION 'FIN_METHOD_EDITION_ALREADY_FROZEN';
    END IF;
    SELECT validation_profile INTO profile FROM fin_calc.method_parameter_contract
      WHERE contract_id=ed.contract_id;
    IF profile='CON01_PARETO_FIXED_V1' THEN
        IF NEW.option_no<>1 OR
           NEW.parameters <> '{"tieSortPolicy":"AMOUNT_DESC_SKU_CODE_ASC",
             "populationMetric":"NET_SALES","returnsPolicy":"REVIEWED_NET_RECONCILED"}'::jsonb THEN
            RAISE EXCEPTION 'FIN_METHOD_EDITION_FIXED_PARAMETERS_REQUIRED';
        END IF;
    ELSIF profile='CON02_ABC_TYPED_V2' THEN
        IF (SELECT count(*) FROM jsonb_object_keys(NEW.parameters))<>3 OR
           jsonb_typeof(NEW.parameters->'aLimitPercent') IS DISTINCT FROM 'number' OR
           jsonb_typeof(NEW.parameters->'bLimitPercent') IS DISTINCT FROM 'number' OR
           NEW.parameters->>'boundaryCrossingPolicy' IS DISTINCT FROM
               'CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED' THEN
            RAISE EXCEPTION 'FIN_METHOD_EDITION_ABC_PARAMETERS_INVALID';
        END IF;
        a := (NEW.parameters->>'aLimitPercent')::numeric;
        b := (NEW.parameters->>'bLimitPercent')::numeric;
        step := (ed.generation_policy->>'stepPercentPoints')::numeric;
        IF a<=0 OR a>=b OR b>=100 OR
           mod(a-(ed.generation_policy->>'baseA')::numeric,step)<>0 OR
           mod(b-(ed.generation_policy->>'baseB')::numeric,step)<>0 OR
           abs(a-(ed.generation_policy->>'baseA')::numeric)>3*step OR
           abs(b-(ed.generation_policy->>'baseB')::numeric)>3*step THEN
            RAISE EXCEPTION 'FIN_METHOD_EDITION_ABC_GRID_VIOLATION';
        END IF;
        NEW.parameters := jsonb_build_object(
          'aLimitPercent', trim_scale(a),
          'bLimitPercent', trim_scale(b),
          'boundaryCrossingPolicy','CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED');
    ELSE
        RAISE EXCEPTION 'FIN_METHOD_EDITION_UNVALIDATED_CONTRACT';
    END IF;
    NEW.params_sha256 := fin_calc.method_params_sha_v1(NEW.parameters);
    NEW.option_sha256 := encode(sha256(convert_to(
       'FIN:METHOD:EDITION:OPTION:PG16-V1|'||NEW.edition_id::text||'|'||
       lpad(NEW.option_no::text,2,'0')||'|'||NEW.params_sha256::text,'UTF8')),'hex');
    RETURN NEW;
END;
$body$;

CREATE TABLE fin_calc.method_variant_edition_freeze (
    edition_id UUID PRIMARY KEY REFERENCES fin_calc.method_variant_edition(edition_id),
    option_count SMALLINT NOT NULL CHECK (option_count BETWEEN 1 AND 10),
    options_sha256 CHAR(64) NOT NULL CHECK (options_sha256 ~ '^[0-9a-f]{64}$'),
    frozen_by VARCHAR(200) NOT NULL CHECK (length(btrim(frozen_by))>0),
    frozen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_method_edition_option_insert BEFORE INSERT
ON fin_calc.method_variant_edition_option FOR EACH ROW
EXECUTE FUNCTION fin_calc.guard_method_edition_option_insert();

CREATE FUNCTION fin_calc.guard_method_edition_freeze_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $body$
DECLARE ed fin_calc.method_variant_edition%ROWTYPE;
DECLARE count_options INTEGER;
DECLARE first_ordinal INTEGER;
DECLARE last_ordinal INTEGER;
DECLARE digest_items TEXT;
BEGIN
    SELECT * INTO ed FROM fin_calc.method_variant_edition
      WHERE edition_id=NEW.edition_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'FIN_METHOD_EDITION_NOT_FOUND'; END IF;
    SELECT count(*), min(option_no), max(option_no),
           string_agg(lpad(option_no::text,2,'0')||':'||params_sha256::text,
                      '|' ORDER BY option_no)
      INTO count_options,first_ordinal,last_ordinal,digest_items
      FROM fin_calc.method_variant_edition_option
     WHERE edition_id=NEW.edition_id;
    IF count_options<1 OR count_options>10 OR first_ordinal<>1 OR
       last_ordinal<>count_options THEN
        RAISE EXCEPTION 'FIN_METHOD_EDITION_OPTION_COUNT_INVALID';
    END IF;
    IF ed.method_code='CON-01' AND count_options<>1 THEN
        RAISE EXCEPTION 'FIN_METHOD_EDITION_FIXED_OPTION_COUNT';
    END IF;
    NEW.option_count:=count_options;
    NEW.options_sha256:=encode(sha256(convert_to(
        'FIN:METHOD:EDITION:SET:PG16-V1|'||ed.edition_id::text||'|'||
        ed.method_code||'|'||ed.variant_code||'|'||ed.edition_no::text||'|'||
        ed.contract_id::text||'|'||ed.algorithm_version||'|'||
        ed.generation_policy::text||'|'||digest_items,
        'UTF8')),'hex');
    RETURN NEW;
END;
$body$;
CREATE TRIGGER trg_method_edition_freeze_insert BEFORE INSERT
ON fin_calc.method_variant_edition_freeze FOR EACH ROW
EXECUTE FUNCTION fin_calc.guard_method_edition_freeze_insert();

-- Once frozen, choices cannot be appended; even before freezing no existing
-- edition/option can be altered or removed. Never silently overwrite history.
CREATE TRIGGER trg_method_edition_immutable BEFORE UPDATE OR DELETE
ON fin_calc.method_variant_edition FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_method_edition_option_immutable BEFORE UPDATE OR DELETE
ON fin_calc.method_variant_edition_option FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_method_edition_freeze_immutable BEFORE UPDATE OR DELETE
ON fin_calc.method_variant_edition_freeze FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- This is a proposal/read-only index, never an executable method catalog.
CREATE VIEW fin_calc.method_variant_preset_choices AS
SELECT e.edition_id,e.method_code,e.variant_code,e.edition_no,
       e.contract_id,c.contract_version,c.schema_sha256,
       e.algorithm_version,e.generation_policy,
       f.option_count,f.options_sha256,
       o.option_no,o.parameters,o.params_sha256,o.option_sha256,
       FALSE AS method_s2_qualified, FALSE AS execution_permitted
FROM fin_calc.method_variant_edition e
JOIN fin_calc.method_parameter_contract c ON c.contract_id=e.contract_id
JOIN fin_calc.method_variant_edition_freeze f ON f.edition_id=e.edition_id
JOIN fin_calc.method_variant_edition_option o ON o.edition_id=e.edition_id;
