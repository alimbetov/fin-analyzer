--liquibase formatted sql
--changeset fin-calc:037a dbms:postgresql splitStatements:true
-- CALC-3C.1: source capability is based on actual typed fields of a pinned
-- form revision, NOT on ROWSET/SINGLE_RECORD presentation mode.
-- This additive schema preserves prior immutable candidate model revisions.
CREATE TABLE fin_calc.form_numeric_source_capability_revision (
  id UUID PRIMARY KEY,
  form_code VARCHAR(128) NOT NULL,
  form_version_id UUID NOT NULL REFERENCES fin_lib.lib_form_version(id),
  source_schema_sha256 CHAR(64) NOT NULL
    CHECK(source_schema_sha256 ~ '^[0-9a-f]{64}$'),
  capability_version VARCHAR(48) NOT NULL,
  content_mode VARCHAR(32) NOT NULL CHECK(content_mode IN
    ('ROWSET','SINGLE_RECORD','EVIDENCE')),
  source_field_count INTEGER NOT NULL CHECK(source_field_count>=0),
  direct_numeric_field_count INTEGER NOT NULL CHECK(
    direct_numeric_field_count>=0 AND direct_numeric_field_count<=source_field_count),
  has_direct_numeric_source BOOLEAN NOT NULL,
  reference_only BOOLEAN NOT NULL,
  evidence_only BOOLEAN NOT NULL,
  approval_state VARCHAR(32) NOT NULL DEFAULT 'NOT_S2_APPROVED'
    CHECK(approval_state='NOT_S2_APPROVED'),
  definition_sha256 CHAR(64) NOT NULL CHECK(definition_sha256 ~ '^[0-9a-f]{64}$'),
  binding_sha256 CHAR(64) NOT NULL CHECK(binding_sha256 ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_calc3c1_numeric_capability_version
    UNIQUE(form_version_id,capability_version),
  CONSTRAINT ck_calc3c1_true_numeric_from_fields CHECK
    (has_direct_numeric_source=(direct_numeric_field_count>0)),
  CONSTRAINT ck_calc3c1_reference_only CHECK
    (reference_only=(content_mode<>'EVIDENCE' AND direct_numeric_field_count=0)),
  CONSTRAINT ck_calc3c1_evidence_only CHECK(evidence_only=(content_mode='EVIDENCE'))
);
CREATE INDEX idx_calc3c1_numeric_capability_form
  ON fin_calc.form_numeric_source_capability_revision(form_code,capability_version);

--changeset fin-calc:037b dbms:postgresql splitStatements:true
CREATE TRIGGER trg_calc3c1_capability_immutable BEFORE UPDATE OR DELETE
  ON fin_calc.form_numeric_source_capability_revision
  FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3c1_capability_no_truncate BEFORE TRUNCATE
  ON fin_calc.form_numeric_source_capability_revision
  FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

--changeset fin-calc:037c dbms:postgresql splitStatements:true
-- Canonical v1 read projection. The previous numeric_eligible field is kept as
-- a historical, mode-based claim for provenance, not used for decisions.
-- Consumers MUST use source_has_direct_numeric_input and inspect mismatches.
CREATE VIEW fin_calc.model_input_source_capability_v1 AS
SELECT
  i.model_revision_id,i.ordinal,i.source_form_code,i.source_form_version_id,
  i.content_mode,
  i.numeric_eligible AS legacy_mode_numeric_eligible,
  c.has_direct_numeric_source AS source_has_direct_numeric_input,
  c.reference_only,c.evidence_only,
  c.direct_numeric_field_count,
  c.binding_sha256 AS numeric_capability_binding_sha256,
  (i.numeric_eligible IS DISTINCT FROM c.has_direct_numeric_source)
    AS legacy_numeric_claim_mismatch,
  i.approval_state
FROM fin_calc.model_candidate_input_form i
JOIN fin_calc.form_numeric_source_capability_revision c
  ON c.form_version_id=i.source_form_version_id
 AND c.source_schema_sha256=i.source_schema_sha256
 AND c.form_code=i.source_form_code
 AND c.capability_version='CALC3C1_NUMERIC_SOURCE_V1';
