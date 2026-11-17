--liquibase formatted sql
--changeset fin-form:011 splitStatements:true
-- Provisional localized display catalogue. No change to immutable form versions
-- or fin_intake user reports. Do NOT confuse DRAFT with editorial approval.
CREATE TABLE fin_lib.lib_form_editorial_draft (
 form_code VARCHAR(128) NOT NULL,
 locale VARCHAR(12) NOT NULL CHECK(locale IN ('ru','kk','en','zh-Hans')),
 title VARCHAR(255) NOT NULL,
 purpose VARCHAR(1000) NOT NULL,
 description VARCHAR(1500) NOT NULL,
 review_status VARCHAR(48) NOT NULL
   CHECK (review_status IN ('AUTO_DRAFT_REVIEW_REQUIRED')),
 source_contract_sha256 VARCHAR(64) NOT NULL,
 PRIMARY KEY (form_code,locale),
 CONSTRAINT fk_lib_editorial_form FOREIGN KEY(form_code)
   REFERENCES fin_lib.lib_form_definition(form_code),
 CONSTRAINT ck_lib_editorial_title_not_blank CHECK (length(trim(title))>0),
 CONSTRAINT ck_lib_editorial_purpose_not_blank CHECK (length(trim(purpose))>0),
 CONSTRAINT ck_lib_editorial_description_not_blank CHECK (length(trim(description))>0)
);
CREATE INDEX idx_lib_editorial_locale ON fin_lib.lib_form_editorial_draft(locale,form_code);
CREATE TABLE fin_lib.lib_form_editorial_seed (
 seed_code VARCHAR(64) PRIMARY KEY,
 source_sha256 VARCHAR(64) NOT NULL,
 seeded_at TIMESTAMP WITH TIME ZONE NOT NULL
);
