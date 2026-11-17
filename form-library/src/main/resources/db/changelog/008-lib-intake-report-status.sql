--liquibase formatted sql
--changeset fin-form:008 splitStatements:true
-- Local draft workflow, deliberately separate from financial qualification / S2 approval.
ALTER TABLE fin_intake.lib_form_instance
 ADD COLUMN workflow_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE fin_intake.lib_form_instance
 ADD COLUMN status_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE fin_intake.lib_form_instance
 ADD CONSTRAINT ck_lib_form_workflow_status CHECK (workflow_status IN ('DRAFT','READY_FOR_REVIEW'));
ALTER TABLE fin_intake.lib_form_instance
 ADD CONSTRAINT ck_lib_form_status_version CHECK (status_version >= 0);

-- Append-only transition log. Author is an untrusted local actor label until IAM exists.
CREATE TABLE fin_intake.lib_form_status_event (
 id UUID PRIMARY KEY,
 instance_id UUID NOT NULL,
 from_status VARCHAR(32) NOT NULL,
 to_status VARCHAR(32) NOT NULL,
 status_version BIGINT NOT NULL CHECK (status_version > 0),
 revision_id UUID NOT NULL,
 actor_label VARCHAR(255) NOT NULL,
 reason VARCHAR(255) NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_lib_status_instance FOREIGN KEY(instance_id) REFERENCES fin_intake.lib_form_instance(id),
 CONSTRAINT fk_lib_status_revision FOREIGN KEY(revision_id) REFERENCES fin_intake.lib_form_revision(id),
 CONSTRAINT uq_lib_status_version UNIQUE(instance_id,status_version),
 CONSTRAINT ck_lib_status_transition CHECK(
   (from_status='DRAFT' AND to_status='READY_FOR_REVIEW')
   OR (from_status='READY_FOR_REVIEW' AND to_status='DRAFT'))
);
CREATE INDEX idx_lib_form_status_event_instance
 ON fin_intake.lib_form_status_event(instance_id,status_version);
