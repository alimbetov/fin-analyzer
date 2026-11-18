--liquibase formatted sql
--changeset fin-form:017 splitStatements:true
-- R19: local-only immutable import profile versions and short-lived preview tickets.
-- No fact values are stored here. Actual writes remain exclusively in fin_intake.
CREATE TABLE fin_lib.lib_import_profile_version (
 id UUID PRIMARY KEY,
 form_version_id UUID NOT NULL,
 profile_code VARCHAR(64) NOT NULL,
 profile_version INT NOT NULL CHECK(profile_version > 0),
 file_format VARCHAR(16) NOT NULL CHECK(file_format IN ('XLSX')),
 binding_sha256 VARCHAR(64) NOT NULL CHECK(length(binding_sha256)=64),
 binding_json TEXT NOT NULL,
 lifecycle_state VARCHAR(32) NOT NULL
   CHECK(lifecycle_state IN ('LOCAL_TEST_ONLY')),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_import_profile_form_version FOREIGN KEY(form_version_id)
   REFERENCES fin_lib.lib_form_version(id),
 CONSTRAINT uq_import_profile_version UNIQUE(form_version_id,profile_code,profile_version)
);
CREATE INDEX idx_import_profile_lookup ON fin_lib.lib_import_profile_version(form_version_id,profile_code);

CREATE TABLE fin_ui.lib_import_preview_ticket (
 id UUID PRIMARY KEY,
 profile_id UUID NOT NULL,
 instance_id UUID NOT NULL,
 organization_id UUID NOT NULL,
 expected_revision INT NOT NULL CHECK(expected_revision > 0),
 original_content_sha256 VARCHAR(64) NOT NULL,
 upload_sha256 VARCHAR(64) NOT NULL,
 binding_sha256 VARCHAR(64) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_import_preview_profile FOREIGN KEY(profile_id)
   REFERENCES fin_lib.lib_import_profile_version(id),
 CONSTRAINT fk_import_preview_instance FOREIGN KEY(instance_id)
   REFERENCES fin_intake.lib_form_instance(id),
 CONSTRAINT ck_import_preview_ttl CHECK(expires_at>created_at)
);
CREATE INDEX idx_import_preview_expiry ON fin_ui.lib_import_preview_ticket(expires_at);
