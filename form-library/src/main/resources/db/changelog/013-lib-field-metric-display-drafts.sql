--liquibase formatted sql
--changeset fin-form:013 splitStatements:true
-- R16.B separate unreviewed field/metric display candidates, never mutate
-- immutable versioned lib_form_field_translation or lib_metric_translation.
CREATE TABLE fin_lib.lib_field_display_candidate (
 form_code VARCHAR(128) NOT NULL,
 field_code VARCHAR(128) NOT NULL,
 locale VARCHAR(12) NOT NULL CHECK(locale IN ('ru','kk','en','zh-Hans')),
 title VARCHAR(255) NOT NULL CHECK(length(trim(title))>0),
 review_status VARCHAR(48) NOT NULL
   CHECK(review_status IN ('AUTO_DRAFT_REVIEW_REQUIRED','PARTIAL_DRAFT')),
 source_contract_sha256 VARCHAR(64) NOT NULL,
 PRIMARY KEY(form_code,field_code,locale),
 CONSTRAINT fk_display_field_binding FOREIGN KEY(form_code,field_code)
   REFERENCES fin_lib.lib_field_binding_contract(form_code,field_code)
);
CREATE INDEX idx_field_display_locale ON fin_lib.lib_field_display_candidate(locale,form_code);
CREATE TABLE fin_lib.lib_metric_display_candidate (
 metric_code VARCHAR(160) NOT NULL,
 locale VARCHAR(12) NOT NULL CHECK(locale IN ('ru','kk','en','zh-Hans')),
 title VARCHAR(255) NOT NULL CHECK(length(trim(title))>0),
 review_status VARCHAR(48) NOT NULL
   CHECK(review_status IN ('AUTO_DRAFT_REVIEW_REQUIRED','PARTIAL_DRAFT')),
 PRIMARY KEY(metric_code,locale),
 CONSTRAINT fk_display_metric_code FOREIGN KEY(metric_code)
   REFERENCES fin_lib.lib_metric_definition(metric_code)
);
CREATE INDEX idx_metric_display_locale ON fin_lib.lib_metric_display_candidate(locale,metric_code);
CREATE TABLE fin_lib.lib_field_display_seed_manifest (
 seed_code VARCHAR(64) PRIMARY KEY,
 source_sha256 VARCHAR(64) NOT NULL,
 seeded_at TIMESTAMP WITH TIME ZONE NOT NULL
);
