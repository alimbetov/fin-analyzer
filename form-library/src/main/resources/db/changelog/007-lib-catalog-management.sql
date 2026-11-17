--liquibase formatted sql
--changeset fin-form:007 splitStatements:true
-- Catalogue presentation drafting. This is never FIN S2 financial qualification.
ALTER TABLE fin_lib.lib_form_definition ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE fin_lib.lib_form_edit_session (
 id UUID PRIMARY KEY,
 form_id UUID NOT NULL,
 base_version_id UUID NOT NULL,
 draft_version_id UUID NOT NULL,
 status VARCHAR(24) NOT NULL CHECK(status IN ('DRAFT','PUBLISHED','DISCARDED')),
 edit_revision INT NOT NULL CHECK(edit_revision >= 0),
 row_version BIGINT NOT NULL DEFAULT 0,
 created_by VARCHAR(160) NOT NULL,
 updated_by VARCHAR(160) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 published_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT fk_lib_edit_form FOREIGN KEY(form_id) REFERENCES fin_lib.lib_form_definition(id),
 CONSTRAINT fk_lib_edit_base FOREIGN KEY(base_version_id) REFERENCES fin_lib.lib_form_version(id),
 CONSTRAINT fk_lib_edit_draft FOREIGN KEY(draft_version_id) REFERENCES fin_lib.lib_form_version(id),
 CONSTRAINT uq_lib_edit_draft_version UNIQUE(draft_version_id)
);
CREATE INDEX idx_lib_edit_form_status ON fin_lib.lib_form_edit_session(form_id,status);
CREATE TABLE fin_lib.lib_form_edit_event (
 id UUID PRIMARY KEY,
 edit_session_id UUID NOT NULL,
 edit_revision INT NOT NULL CHECK(edit_revision >= 0),
 event_type VARCHAR(40) NOT NULL,
 actor VARCHAR(160) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 content_sha256 VARCHAR(64) NOT NULL,
 CONSTRAINT fk_lib_edit_event_session FOREIGN KEY(edit_session_id) REFERENCES fin_lib.lib_form_edit_session(id),
 CONSTRAINT uq_lib_edit_event_revision UNIQUE(edit_session_id,edit_revision)
);
