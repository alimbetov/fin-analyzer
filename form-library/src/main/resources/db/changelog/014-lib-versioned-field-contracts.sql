--liquibase formatted sql
--changeset fin-form:014 splitStatements:true
-- R16.B additive typed governance; existing lib_form_version / lib_form_field
-- and their semantic hashes remain authoritative. No S2 or fin_fact permission.
CREATE TABLE fin_lib.lib_form_contract_policy (
 form_version_id UUID PRIMARY KEY,
 canonicalization_version INT NOT NULL CHECK(canonicalization_version > 0),
 source_descriptor_version VARCHAR(128) NOT NULL CHECK(length(trim(source_descriptor_version)) > 0),
 compatibility_class VARCHAR(48) NOT NULL
   CHECK(compatibility_class IN ('UNCLASSIFIED','PRESENTATION_ONLY','BACKWARD_COMPATIBLE_NUMERIC',
     'MAPPING_CHANGED','BREAKING','QUALIFICATION_CHANGED')),
 governance_state VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE_UNQUALIFIED'
   CHECK(governance_state IN ('CANDIDATE_UNQUALIFIED')),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_r16_policy_version FOREIGN KEY(form_version_id) REFERENCES fin_lib.lib_form_version(id)
);
CREATE TABLE fin_lib.lib_form_field_policy (
 form_version_id UUID NOT NULL,
 field_code VARCHAR(128) NOT NULL,
 numeric_precision INT,
 numeric_scale INT,
 rounding_mode VARCHAR(32),
 missing_value_policy VARCHAR(32) NOT NULL
   CHECK(missing_value_policy IN ('REJECT','ALLOW_MISSING','ALLOW_NULL','DISTINGUISH_STATES')),
 min_occurs INT NOT NULL CHECK(min_occurs >= 0),
 max_occurs INT,
 semantic_definition VARCHAR(2000) NOT NULL CHECK(length(trim(semantic_definition)) > 0),
 aggregation_policy VARCHAR(32) NOT NULL
   CHECK(aggregation_policy IN ('NONE','SUM','LAST_VALUE','WEIGHTED_AVERAGE','FINANCE_DEFINED')),
 PRIMARY KEY(form_version_id,field_code),
 CONSTRAINT fk_r16_field_policy_field FOREIGN KEY(form_version_id,field_code)
   REFERENCES fin_lib.lib_form_field(form_version_id,field_code),
 CONSTRAINT ck_r16_field_cardinality CHECK(max_occurs IS NULL OR max_occurs >= min_occurs),
 CONSTRAINT ck_r16_field_numeric CHECK(
   (numeric_precision IS NULL AND numeric_scale IS NULL AND rounding_mode IS NULL)
   OR
   (numeric_precision BETWEEN 1 AND 38 AND numeric_scale BETWEEN 0 AND numeric_precision
    AND rounding_mode IN ('HALF_EVEN','HALF_UP','DOWN','UP','UNNECESSARY')))
);
CREATE TABLE fin_lib.lib_form_allowed_period (
 form_version_id UUID NOT NULL,
 period_type VARCHAR(8) NOT NULL,
 calendar_code VARCHAR(64) NOT NULL CHECK(length(trim(calendar_code)) > 0),
 reporting_basis VARCHAR(64) NOT NULL CHECK(length(trim(reporting_basis)) > 0),
 coverage_policy VARCHAR(32) NOT NULL
   CHECK(coverage_policy IN ('FULL','AS_OF','INTERVAL','EVENT','EXPLICIT')),
 PRIMARY KEY(form_version_id,period_type,calendar_code,reporting_basis),
 CONSTRAINT fk_r16_period_version FOREIGN KEY(form_version_id) REFERENCES fin_lib.lib_form_version(id),
 CONSTRAINT fk_r16_period_type FOREIGN KEY(period_type) REFERENCES fin_lib.lib_period_type(code)
);
CREATE TABLE fin_lib.lib_form_field_dictionary (
 form_version_id UUID NOT NULL,
 field_code VARCHAR(128) NOT NULL,
 dictionary_code VARCHAR(128) NOT NULL CHECK(length(trim(dictionary_code)) > 0),
 dictionary_version VARCHAR(64) NOT NULL CHECK(length(trim(dictionary_version)) > 0),
 PRIMARY KEY(form_version_id,field_code),
 CONSTRAINT fk_r16_dictionary_field FOREIGN KEY(form_version_id,field_code)
   REFERENCES fin_lib.lib_form_field(form_version_id,field_code)
);
CREATE TABLE fin_lib.lib_form_contract_metric_binding (
 form_version_id UUID NOT NULL,
 field_code VARCHAR(128) NOT NULL,
 metric_code VARCHAR(160) NOT NULL,
 metric_contract_version VARCHAR(64) NOT NULL CHECK(length(trim(metric_contract_version)) > 0),
 transformation_policy VARCHAR(64) NOT NULL CHECK(length(trim(transformation_policy)) > 0),
 PRIMARY KEY(form_version_id,field_code),
 CONSTRAINT fk_r16_mapping_field FOREIGN KEY(form_version_id,field_code)
   REFERENCES fin_lib.lib_form_field(form_version_id,field_code),
 CONSTRAINT fk_r16_mapping_metric FOREIGN KEY(metric_code)
   REFERENCES fin_lib.lib_metric_definition(metric_code)
);
-- These candidates are intentionally separate from existing unqualified mappings.
-- No default backfill: 166 legacy catalog identities remain unchanged.
