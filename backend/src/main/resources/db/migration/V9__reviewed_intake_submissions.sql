-- FIN P0-A: immutable human review of individually versioned source submissions.
-- Data-space ACTUAL submission is not an ACTUAL diagnostic-method qualification.
CREATE SCHEMA IF NOT EXISTS fin_intake;

CREATE TABLE fin_intake.submission (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  economic_entity_id UUID NOT NULL,
  business_scope_id UUID NOT NULL,
  form_code VARCHAR(128) NOT NULL,
  form_version VARCHAR(64) NOT NULL CHECK (form_version='FIN-PILOT-V1'),
  reporting_basis VARCHAR(24) NOT NULL CHECK(reporting_basis IN ('SEPARATE','CONSOLIDATED','MANAGEMENT')),
  currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
  amount_scale INTEGER NOT NULL CHECK(amount_scale IN (1,1000)),
  intake_kind VARCHAR(16) NOT NULL CHECK(intake_kind IN ('MANUAL','XLSX')),
  source_document_sha CHAR(64) NOT NULL CHECK(source_document_sha ~ '^[a-f0-9]{64}$'),
  normalized_fingerprint CHAR(64) NOT NULL CHECK(normalized_fingerprint ~ '^[a-f0-9]{64}$'),
  cells JSONB NOT NULL CHECK(jsonb_typeof(cells)='array'
       AND jsonb_array_length(cells) BETWEEN 1 AND 512),
  expected_cell_count INTEGER NOT NULL CHECK(expected_cell_count BETWEEN 1 AND 512),
  submitted_by VARCHAR(200) NOT NULL CHECK(length(btrim(submitted_by))>0),
  submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_intake_cell_count CHECK(expected_cell_count=jsonb_array_length(cells)),
  CONSTRAINT uq_submission_tenant UNIQUE(id,organization_id),
  CONSTRAINT ck_valid_pilot_form CHECK(form_code IN ('F01','F02','F03','DF-CSH-01','DF-CSH-02'))
);
CREATE TRIGGER trg_intake_submission_immutable BEFORE UPDATE OR DELETE ON fin_intake.submission
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE TABLE fin_intake.review (
  submission_id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  decision VARCHAR(12) NOT NULL CHECK(decision IN ('APPROVED','REJECTED')),
  reviewed_by VARCHAR(200) NOT NULL CHECK(length(btrim(reviewed_by))>0),
  reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reason VARCHAR(1000) NOT NULL CHECK(length(btrim(reason))>0),
  FOREIGN KEY(submission_id,organization_id) REFERENCES fin_intake.submission(id,organization_id)
);
CREATE FUNCTION fin_intake.prevent_self_approval()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE submitter VARCHAR(200);
BEGIN
  SELECT submitted_by INTO submitter FROM fin_intake.submission
   WHERE id=NEW.submission_id AND organization_id=NEW.organization_id FOR UPDATE;
  IF submitter IS NULL THEN RAISE EXCEPTION 'INTAKE_SUBMISSION_OR_TENANT_NOT_FOUND'; END IF;
  IF submitter=NEW.reviewed_by THEN RAISE EXCEPTION 'INTAKE_SELF_REVIEW_FORBIDDEN'; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_intake_review_authorization BEFORE INSERT ON fin_intake.review
FOR EACH ROW EXECUTE FUNCTION fin_intake.prevent_self_approval();
CREATE TRIGGER trg_intake_review_immutable BEFORE UPDATE OR DELETE ON fin_intake.review
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- A reviewed source must originate from a genuine, matched review row; legacy
-- manually populated approved_source_revision entries remain valid for old fixtures,
-- but new P0 publishing selects ONLY intake-backed source revisions.
CREATE INDEX idx_intake_org_context ON fin_intake.submission
 (organization_id,economic_entity_id,business_scope_id,submitted_at DESC);
