--liquibase formatted sql
--changeset fin-calc:042a dbms:postgresql splitStatements:true
-- CALC-3C.3 A/B: immutable provenance of IAM-authenticated draft authors.
-- These tables can contain only PRE-S2 review evidence, NOT approvals.
CREATE TABLE fin_calc.financial_review_draft_author_attribution (
  draft_id uuid PRIMARY KEY
    REFERENCES fin_calc.required_output_review_draft_revision(id),
  form_version_id uuid NOT NULL REFERENCES fin_lib.lib_form_version(id),
  author_issuer varchar(512) NOT NULL CHECK(length(btrim(author_issuer))>0),
  author_subject varchar(256) NOT NULL CHECK(length(btrim(author_subject))>0),
  dedicated_audience varchar(256) NOT NULL
    CHECK(dedicated_audience='fin-form-library/financial-methodology-review/v1'),
  attribution_sha256 char(64) NOT NULL CHECK(attribution_sha256 ~ '^[0-9a-f]{64}$'),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE fin_calc.financial_methodology_review_request (
  id uuid PRIMARY KEY,
  draft_id uuid NOT NULL UNIQUE
    REFERENCES fin_calc.financial_review_draft_author_attribution(draft_id),
  form_version_id uuid NOT NULL REFERENCES fin_lib.lib_form_version(id),
  form_schema_sha256 char(64) NOT NULL
    CHECK(form_schema_sha256 ~ '^[0-9a-f]{64}$'),
  draft_binding_sha256 char(64) NOT NULL
    CHECK(draft_binding_sha256 ~ '^[0-9a-f]{64}$'),
  review_packet_sha256 char(64) NOT NULL
    CHECK(review_packet_sha256 ~ '^[0-9a-f]{64}$'),
  author_issuer varchar(512) NOT NULL,
  author_subject varchar(256) NOT NULL,
  reviewer_issuer varchar(512) NOT NULL,
  reviewer_subject varchar(256) NOT NULL,
  required_output_count integer NOT NULL CHECK(required_output_count>0),
  derived_output_count integer NOT NULL CHECK(derived_output_count>=0),
  blocker_codes_jsonb jsonb NOT NULL
    CHECK(jsonb_typeof(blocker_codes_jsonb)='array'
      AND blocker_codes_jsonb @> '["FINANCIAL_METHODOLOGY_S2_NOT_VERIFIED"]'::jsonb),
  review_status varchar(40) NOT NULL DEFAULT 'PRE_S2_REVIEW_ONLY'
    CHECK(review_status='PRE_S2_REVIEW_ONLY'),
  financial_approval_state varchar(32) NOT NULL DEFAULT 'NOT_S2_APPROVED'
    CHECK(financial_approval_state='NOT_S2_APPROVED'),
  review_request_sha256 char(64) NOT NULL CHECK(review_request_sha256 ~ '^[0-9a-f]{64}$'),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_calc3c3_separation_of_duties CHECK(
    author_issuer<>reviewer_issuer OR author_subject<>reviewer_subject)
);
CREATE INDEX idx_calc3c3_review_form_version
  ON fin_calc.financial_methodology_review_request(form_version_id);

--changeset fin-calc:042b dbms:postgresql splitStatements:false
-- Structural DB checks also apply to direct SQL writes. Crypto verification
-- stays at trusted resource server boundary; signed S2 remains absent.
CREATE FUNCTION fin_calc.guard_financial_review_request()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d RECORD; a RECORD;
BEGIN
  SELECT form_version_id,source_schema_sha256,binding_sha256,revision_number
    INTO d FROM fin_calc.required_output_review_draft_revision WHERE id=NEW.draft_id;
  SELECT form_version_id,author_issuer,author_subject
    INTO a FROM fin_calc.financial_review_draft_author_attribution
    WHERE draft_id=NEW.draft_id;
  IF NOT FOUND OR d.form_version_id IS DISTINCT FROM NEW.form_version_id
      OR a.form_version_id IS DISTINCT FROM NEW.form_version_id
      OR d.source_schema_sha256 IS DISTINCT FROM NEW.form_schema_sha256
      OR d.binding_sha256 IS DISTINCT FROM NEW.draft_binding_sha256
      OR a.author_issuer IS DISTINCT FROM NEW.author_issuer
      OR a.author_subject IS DISTINCT FROM NEW.author_subject
      OR EXISTS (
        SELECT 1 FROM fin_calc.required_output_review_draft_revision newer
        WHERE newer.form_version_id=d.form_version_id
          AND newer.revision_number>d.revision_number)
  THEN
    RAISE EXCEPTION 'CALC3C3_REVIEW_BINDING_OR_AUTHOR_INVALID'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

--changeset fin-calc:042c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_calc3c3_review_request_guard
  BEFORE INSERT ON fin_calc.financial_methodology_review_request
  FOR EACH ROW EXECUTE FUNCTION fin_calc.guard_financial_review_request();
CREATE TRIGGER trg_calc3c3_author_immutable
  BEFORE UPDATE OR DELETE ON fin_calc.financial_review_draft_author_attribution
  FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3c3_author_no_truncate
  BEFORE TRUNCATE ON fin_calc.financial_review_draft_author_attribution
  FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3c3_request_immutable
  BEFORE UPDATE OR DELETE ON fin_calc.financial_methodology_review_request
  FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3c3_request_no_truncate
  BEFORE TRUNCATE ON fin_calc.financial_methodology_review_request
  FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
