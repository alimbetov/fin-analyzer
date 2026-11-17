--liquibase formatted sql
--changeset fin-form:012 splitStatements:true
-- R16.A explicit draft-only physical binding; no edits to immutable form versions,
-- source metric IDs, methodology digests or fin_intake report values.
CREATE TABLE fin_lib.lib_field_binding_contract (
 form_code VARCHAR(128) NOT NULL,
 field_code VARCHAR(128) NOT NULL,
 source_schema_sha256 VARCHAR(64) NOT NULL,
 field_type VARCHAR(32) NOT NULL,
 input_mode VARCHAR(32) NOT NULL,
 required BOOLEAN NOT NULL,
 unit_policy VARCHAR(64) NOT NULL,
 semantic_role VARCHAR(32) NOT NULL
   CHECK(semantic_role IN ('SOURCE_FIELD','EVIDENCE_METADATA')),
 metric_code VARCHAR(160),
 contract_sha256 VARCHAR(64) NOT NULL,
 review_state VARCHAR(48) NOT NULL DEFAULT 'CANDIDATE_UNQUALIFIED'
   CHECK(review_state IN ('CANDIDATE_UNQUALIFIED')),
 PRIMARY KEY(form_code,field_code),
 CONSTRAINT fk_field_contract_form FOREIGN KEY(form_code)
   REFERENCES fin_lib.lib_form_definition(form_code),
 CONSTRAINT fk_field_contract_metric FOREIGN KEY(metric_code)
   REFERENCES fin_lib.lib_metric_definition(metric_code),
 CONSTRAINT ck_field_contract_binding CHECK(
   (semantic_role='SOURCE_FIELD' AND metric_code IS NOT NULL)
   OR (semantic_role='EVIDENCE_METADATA' AND metric_code IS NULL))
);
CREATE UNIQUE INDEX uq_field_contract_metric ON fin_lib.lib_field_binding_contract(metric_code);
CREATE TABLE fin_lib.lib_metric_binding_exception (
 metric_code VARCHAR(160) PRIMARY KEY,
 semantic_role VARCHAR(40) NOT NULL
   CHECK(semantic_role IN ('FORM_DERIVED_CANDIDATE')),
 formula_expression VARCHAR(512) NOT NULL,
 review_state VARCHAR(48) NOT NULL
   CHECK(review_state IN ('CANDIDATE_UNQUALIFIED')),
 CONSTRAINT fk_metric_binding_exception FOREIGN KEY(metric_code)
   REFERENCES fin_lib.lib_metric_definition(metric_code)
);
CREATE TABLE fin_lib.lib_field_contract_seed_manifest (
 seed_code VARCHAR(64) PRIMARY KEY,
 source_sha256 VARCHAR(64) NOT NULL,
 seeded_at TIMESTAMP WITH TIME ZONE NOT NULL
);
