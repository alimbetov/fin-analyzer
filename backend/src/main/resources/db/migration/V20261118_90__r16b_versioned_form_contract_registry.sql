-- FIN R16.B: additive, fail-closed registry. No activation, financial S2 or ACTUAL permit.
CREATE SCHEMA IF NOT EXISTS fin_form;

CREATE TABLE fin_form.form_definition (
    form_code varchar(64) PRIMARY KEY,
    catalog_code varchar(32) NOT NULL,
    form_kind varchar(48) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT r16_form_code_not_blank CHECK (btrim(form_code) <> ''),
    CONSTRAINT r16_catalog_code_not_blank CHECK (btrim(catalog_code) <> ''),
    CONSTRAINT r16_form_kind_not_blank CHECK (btrim(form_kind) <> '')
);

CREATE TABLE fin_form.form_contract_version (
    contract_version_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    form_code varchar(64) NOT NULL REFERENCES fin_form.form_definition(form_code) ON DELETE RESTRICT,
    version_no integer NOT NULL CHECK (version_no > 0),
    manifest_format_version integer NOT NULL DEFAULT 1 CHECK (manifest_format_version > 0),
    source_descriptor_version varchar(128) NOT NULL,
    schema_sha256 char(64) NOT NULL,
    mapping_sha256 char(64) NOT NULL,
    presentation_sha256 char(64) NOT NULL,
    compatibility_class varchar(48) NOT NULL CHECK (compatibility_class IN
       ('UNCLASSIFIED','PRESENTATION_ONLY','BACKWARD_COMPATIBLE_NUMERIC','MAPPING_CHANGED','BREAKING','QUALIFICATION_CHANGED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(form_code, version_no),
    CONSTRAINT r16_schema_sha CHECK (schema_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT r16_mapping_sha CHECK (mapping_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT r16_presentation_sha CHECK (presentation_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT r16_source_descriptor CHECK (btrim(source_descriptor_version) <> '')
);

CREATE TABLE fin_form.form_field_definition (
    contract_version_id bigint NOT NULL REFERENCES fin_form.form_contract_version(contract_version_id) ON DELETE RESTRICT,
    field_code varchar(128) NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal > 0),
    value_type varchar(24) NOT NULL CHECK (value_type IN ('DECIMAL','INTEGER','BOOLEAN','DATE','TIMESTAMP','STRING','CODE','REFERENCE')),
    is_required boolean NOT NULL,
    min_occurs integer NOT NULL DEFAULT 0 CHECK (min_occurs >= 0),
    max_occurs integer CHECK (max_occurs IS NULL OR max_occurs > 0),
    numeric_precision integer,
    numeric_scale integer,
    unit_code varchar(32),
    rounding_mode varchar(32),
    missing_value_policy varchar(32) NOT NULL CHECK (missing_value_policy IN ('REJECT','ALLOW_MISSING','ALLOW_NULL','DISTINGUISH_STATES')),
    input_class varchar(16) NOT NULL CHECK (input_class IN ('INPUT','DERIVED','EVIDENCE')),
    aggregation_policy varchar(32) NOT NULL CHECK (aggregation_policy IN ('NONE','SUM','LAST_VALUE','WEIGHTED_AVERAGE','FINANCE_DEFINED')),
    semantic_definition text NOT NULL,
    PRIMARY KEY(contract_version_id, field_code),
    UNIQUE(contract_version_id, ordinal),
    CONSTRAINT r16_field_code_not_blank CHECK (btrim(field_code) <> ''),
    CONSTRAINT r16_field_semantic_not_blank CHECK (btrim(semantic_definition) <> ''),
    CONSTRAINT r16_cardinality CHECK (max_occurs IS NULL OR max_occurs >= min_occurs),
    CONSTRAINT r16_required_cardinality CHECK (NOT is_required OR min_occurs >= 1),
    CONSTRAINT r16_numeric_policy CHECK (
       (value_type IN ('DECIMAL','INTEGER') AND numeric_precision BETWEEN 1 AND 38
        AND numeric_scale BETWEEN 0 AND numeric_precision AND unit_code IS NOT NULL
        AND btrim(unit_code) <> '' AND rounding_mode IN ('HALF_EVEN','HALF_UP','DOWN','UP','UNNECESSARY'))
       OR
       (value_type NOT IN ('DECIMAL','INTEGER') AND numeric_precision IS NULL
        AND numeric_scale IS NULL AND rounding_mode IS NULL)
    ),
    CONSTRAINT r16_integer_scale CHECK (value_type <> 'INTEGER' OR numeric_scale = 0)
);

CREATE TABLE fin_form.form_period_rule (
    contract_version_id bigint NOT NULL REFERENCES fin_form.form_contract_version(contract_version_id) ON DELETE RESTRICT,
    period_type varchar(32) NOT NULL,
    calendar_code varchar(64) NOT NULL,
    reporting_basis varchar(64) NOT NULL,
    coverage_policy varchar(32) NOT NULL CHECK (coverage_policy IN ('FULL','AS_OF','INTERVAL','EVENT','EXPLICIT')),
    PRIMARY KEY(contract_version_id, period_type, calendar_code, reporting_basis),
    CONSTRAINT r16_period_not_blank CHECK (btrim(period_type) <> '' AND btrim(calendar_code) <> '' AND btrim(reporting_basis) <> '')
);

CREATE TABLE fin_form.form_dictionary_binding (
    contract_version_id bigint NOT NULL,
    field_code varchar(128) NOT NULL,
    dictionary_code varchar(128) NOT NULL,
    dictionary_version varchar(64) NOT NULL,
    PRIMARY KEY(contract_version_id, field_code),
    FOREIGN KEY(contract_version_id, field_code)
        REFERENCES fin_form.form_field_definition(contract_version_id, field_code) ON DELETE RESTRICT,
    CONSTRAINT r16_dict_nonblank CHECK (btrim(dictionary_code) <> '' AND btrim(dictionary_version) <> '')
);

CREATE TABLE fin_form.form_metric_binding (
    contract_version_id bigint NOT NULL,
    field_code varchar(128) NOT NULL,
    metric_code varchar(128) NOT NULL,
    metric_version varchar(64) NOT NULL,
    transformation_policy varchar(64) NOT NULL,
    data_space varchar(32) NOT NULL,
    PRIMARY KEY(contract_version_id, field_code),
    FOREIGN KEY(contract_version_id, field_code)
        REFERENCES fin_form.form_field_definition(contract_version_id, field_code) ON DELETE RESTRICT,
    CONSTRAINT r16_metric_nonblank CHECK (
        btrim(metric_code) <> '' AND btrim(metric_version) <> '' AND
        btrim(transformation_policy) <> '' AND btrim(data_space) <> '')
);

CREATE TABLE fin_form.form_validation_rule (
    contract_version_id bigint NOT NULL REFERENCES fin_form.form_contract_version(contract_version_id) ON DELETE RESTRICT,
    rule_code varchar(128) NOT NULL,
    rule_version integer NOT NULL CHECK (rule_version > 0),
    rule_type varchar(48) NOT NULL CHECK (rule_type IN ('REQUIRED','RANGE','CROSS_FIELD','BALANCE','DICTIONARY','PERIOD','CUSTOM_SERVER_VALIDATOR')),
    server_validator_key varchar(128) NOT NULL,
    semantic_definition text NOT NULL,
    PRIMARY KEY(contract_version_id, rule_code),
    CONSTRAINT r16_rule_nonblank CHECK (btrim(rule_code) <> '' AND btrim(server_validator_key) <> '' AND btrim(semantic_definition) <> '')
);

-- Versions and their subordinate definitions are append-only; moderation/activation
-- will live in separate tables in R16.D/E, never by rewriting historical definitions.
CREATE FUNCTION fin_form.reject_contract_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'FIN_R16_IMMUTABLE_CONTRACT' USING ERRCODE = '23514';
END $$;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
       'form_definition','form_contract_version','form_field_definition',
       'form_period_rule','form_dictionary_binding','form_metric_binding','form_validation_rule'
    ] LOOP
       EXECUTE format(
          'CREATE TRIGGER r16_append_only BEFORE UPDATE OR DELETE ON fin_form.%I FOR EACH ROW EXECUTE FUNCTION fin_form.reject_contract_mutation()',
          t);
    END LOOP;
END $$;
