--liquibase formatted sql
--changeset fin-form:009 splitStatements:true
-- Distinct four-language form purpose; existing title and description stay version-bound.
-- Nullable, never populated with fabricated content; editorial review is independent.
ALTER TABLE fin_lib.lib_form_translation
 ADD COLUMN purpose VARCHAR(1000);
ALTER TABLE fin_lib.lib_form_translation
 ADD COLUMN purpose_review_status VARCHAR(48) NOT NULL DEFAULT 'PENDING_TRANSLATION';
ALTER TABLE fin_lib.lib_form_translation
 ADD CONSTRAINT ck_lib_form_purpose_review_status
 CHECK (purpose_review_status IN ('PENDING_TRANSLATION','DRAFT_UNREVIEWED'));
