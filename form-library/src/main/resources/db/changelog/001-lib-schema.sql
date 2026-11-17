--liquibase formatted sql
--changeset fin-form:001 splitStatements:true
CREATE SCHEMA IF NOT EXISTS fin_lib;
CREATE SCHEMA IF NOT EXISTS fin_intake;

CREATE TABLE fin_lib.lib_form_definition (
 id UUID PRIMARY KEY,
 form_code VARCHAR(128) NOT NULL UNIQUE,
 catalog VARCHAR(64) NOT NULL,
 legacy_capture_mode VARCHAR(64) NOT NULL,
 current_version_id UUID
);
CREATE TABLE fin_lib.lib_form_version (
 id UUID PRIMARY KEY,
 form_id UUID NOT NULL,
 version_label VARCHAR(128) NOT NULL,
 content_mode VARCHAR(32) NOT NULL CHECK (content_mode IN ('SINGLE_RECORD','ROWSET','EVIDENCE')),
 grain VARCHAR(128) NOT NULL,
 qualification_state VARCHAR(64) NOT NULL,
 schema_sha256 VARCHAR(64) NOT NULL,
 presentation_sha256 VARCHAR(64) NOT NULL,
 mapping_sha256 VARCHAR(64) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_lib_version_form FOREIGN KEY (form_id) REFERENCES fin_lib.lib_form_definition(id),
 CONSTRAINT uq_lib_form_version UNIQUE(form_id,version_label)
);
ALTER TABLE fin_lib.lib_form_definition ADD CONSTRAINT fk_lib_current_version
 FOREIGN KEY (current_version_id) REFERENCES fin_lib.lib_form_version(id);

CREATE TABLE fin_lib.lib_form_section (
 id UUID PRIMARY KEY,
 form_version_id UUID NOT NULL,
 section_code VARCHAR(128) NOT NULL,
 section_order INT NOT NULL CHECK(section_order > 0),
 parent_section_id UUID,
 layout_kind VARCHAR(64) NOT NULL,
 CONSTRAINT fk_lib_section_version FOREIGN KEY(form_version_id) REFERENCES fin_lib.lib_form_version(id),
 CONSTRAINT fk_lib_section_parent FOREIGN KEY(parent_section_id) REFERENCES fin_lib.lib_form_section(id),
 CONSTRAINT uq_lib_section_code UNIQUE(form_version_id,section_code)
);
CREATE INDEX idx_lib_section_order ON fin_lib.lib_form_section(form_version_id,parent_section_id,section_order);

CREATE TABLE fin_lib.lib_form_field (
 id UUID PRIMARY KEY,
 form_version_id UUID NOT NULL,
 section_id UUID NOT NULL,
 field_code VARCHAR(128) NOT NULL,
 display_order INT NOT NULL CHECK(display_order > 0),
 field_type VARCHAR(32) NOT NULL,
 input_mode VARCHAR(32) NOT NULL CHECK(input_mode IN ('MANUAL','DERIVED','READ_ONLY')),
 required BOOLEAN NOT NULL,
 formula_code VARCHAR(128),
 unit_code VARCHAR(64),
 CONSTRAINT fk_lib_field_version FOREIGN KEY(form_version_id) REFERENCES fin_lib.lib_form_version(id),
 CONSTRAINT fk_lib_field_section FOREIGN KEY(section_id) REFERENCES fin_lib.lib_form_section(id),
 CONSTRAINT uq_lib_field_code UNIQUE(form_version_id,field_code),
 CONSTRAINT uq_lib_field_order UNIQUE(section_id,display_order)
);
CREATE INDEX idx_lib_field_position ON fin_lib.lib_form_field(section_id,display_order,field_code);

CREATE TABLE fin_lib.lib_form_field_translation (
 id UUID PRIMARY KEY,
 form_version_id UUID NOT NULL,
 subject_type VARCHAR(32) NOT NULL CHECK(subject_type IN ('FORM','SECTION','FIELD')),
 subject_code VARCHAR(128) NOT NULL,
 locale VARCHAR(16) NOT NULL,
 title VARCHAR(255) NOT NULL,
 description VARCHAR(255),
 CONSTRAINT fk_lib_translation_version FOREIGN KEY(form_version_id) REFERENCES fin_lib.lib_form_version(id),
 CONSTRAINT uq_lib_translation UNIQUE(form_version_id,subject_type,subject_code,locale)
);
CREATE TABLE fin_lib.lib_form_validation_rule (
 id UUID PRIMARY KEY,
 form_version_id UUID NOT NULL,
 rule_code VARCHAR(128) NOT NULL,
 rule_version VARCHAR(64) NOT NULL,
 rule_kind VARCHAR(64) NOT NULL,
 typed_params CLOB,
 severity VARCHAR(32) NOT NULL,
 CONSTRAINT fk_lib_rule_version FOREIGN KEY(form_version_id) REFERENCES fin_lib.lib_form_version(id),
 CONSTRAINT uq_lib_rule UNIQUE(form_version_id,rule_code,rule_version)
);
CREATE TABLE fin_lib.lib_form_metric_mapping_candidate (
 id UUID PRIMARY KEY,
 form_version_id UUID NOT NULL,
 field_code VARCHAR(128) NOT NULL,
 metric_code VARCHAR(128) NOT NULL,
 metric_version VARCHAR(64) NOT NULL,
 mapping_sha256 VARCHAR(64) NOT NULL,
 review_state VARCHAR(64) NOT NULL,
 CONSTRAINT fk_lib_candidate_version FOREIGN KEY(form_version_id) REFERENCES fin_lib.lib_form_version(id),
 CONSTRAINT uq_lib_candidate UNIQUE(form_version_id,field_code,metric_code,metric_version)
);

CREATE TABLE fin_intake.lib_form_instance (
 id UUID PRIMARY KEY,
 organization_id UUID NOT NULL,
 economic_entity_id UUID NOT NULL,
 business_scope_id UUID NOT NULL,
 project_id UUID NOT NULL,
 form_id UUID NOT NULL,
 period_type VARCHAR(32) NOT NULL,
 period_code VARCHAR(64) NOT NULL,
 coverage VARCHAR(32) NOT NULL,
 currency VARCHAR(3) NOT NULL,
 reporting_basis VARCHAR(64) NOT NULL,
 data_space VARCHAR(32) NOT NULL,
 current_revision_id UUID,
 latest_revision_no INT NOT NULL DEFAULT 0,
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_lib_instance_form FOREIGN KEY(form_id) REFERENCES fin_lib.lib_form_definition(id),
 CONSTRAINT uq_lib_instance_scope UNIQUE (organization_id,economic_entity_id,business_scope_id,project_id,form_id,period_type,period_code,coverage,currency,reporting_basis,data_space)
);
CREATE INDEX idx_lib_instance_lookup ON fin_intake.lib_form_instance(organization_id,form_id,period_type,period_code,currency);
CREATE TABLE fin_intake.lib_form_revision (
 id UUID PRIMARY KEY,
 instance_id UUID NOT NULL,
 revision_no INT NOT NULL CHECK (revision_no > 0),
 form_version_id UUID NOT NULL,
 content_mode VARCHAR(32) NOT NULL CHECK (content_mode IN ('SINGLE_RECORD','ROWSET','EVIDENCE')),
 values_json CLOB,
 canonical_content_sha256 VARCHAR(64) NOT NULL,
 row_order_sha256 VARCHAR(64) NOT NULL,
 source_kind VARCHAR(64) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created_by VARCHAR(255) NOT NULL,
 change_reason VARCHAR(255) NOT NULL,
 CONSTRAINT fk_lib_revision_instance FOREIGN KEY(instance_id) REFERENCES fin_intake.lib_form_instance(id),
 CONSTRAINT fk_lib_revision_version FOREIGN KEY(form_version_id) REFERENCES fin_lib.lib_form_version(id),
 CONSTRAINT uq_lib_revision_no UNIQUE(instance_id,revision_no)
);
ALTER TABLE fin_intake.lib_form_instance ADD CONSTRAINT fk_lib_current_revision
 FOREIGN KEY(current_revision_id) REFERENCES fin_intake.lib_form_revision(id);
CREATE TABLE fin_intake.lib_form_revision_row (
 id UUID PRIMARY KEY,
 revision_id UUID NOT NULL,
 row_id UUID NOT NULL,
 row_position INT NOT NULL CHECK(row_position > 0),
 values_json CLOB,
 CONSTRAINT fk_lib_revision_row FOREIGN KEY(revision_id) REFERENCES fin_intake.lib_form_revision(id),
 CONSTRAINT uq_lib_row_id UNIQUE(revision_id,row_id),
 CONSTRAINT uq_lib_row_position UNIQUE(revision_id,row_position)
);
CREATE INDEX idx_lib_revision_row_order ON fin_intake.lib_form_revision_row(revision_id,row_position,row_id);
CREATE TABLE fin_intake.lib_form_revision_evidence (
 id UUID PRIMARY KEY,
 revision_id UUID NOT NULL,
 evidence_id UUID NOT NULL,
 document_ref VARCHAR(255) NOT NULL,
 raw_sha256 VARCHAR(64) NOT NULL,
 media_type VARCHAR(255) NOT NULL,
 size_bytes BIGINT NOT NULL CHECK(size_bytes >= 0),
 metadata_json CLOB,
 CONSTRAINT fk_lib_revision_evidence FOREIGN KEY(revision_id) REFERENCES fin_intake.lib_form_revision(id),
 CONSTRAINT uq_lib_evidence UNIQUE(revision_id,evidence_id)
);
CREATE TABLE fin_intake.lib_form_command_receipt (
 id UUID PRIMARY KEY,
 organization_id UUID NOT NULL,
 idempotency_key VARCHAR(255) NOT NULL,
 request_sha256 VARCHAR(64) NOT NULL,
 revision_id UUID NOT NULL,
 result_code VARCHAR(64) NOT NULL,
 CONSTRAINT fk_lib_receipt_revision FOREIGN KEY(revision_id) REFERENCES fin_intake.lib_form_revision(id),
 CONSTRAINT uq_lib_idempotency UNIQUE(organization_id,idempotency_key)
);
