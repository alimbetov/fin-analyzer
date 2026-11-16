-- FIN V5 SERVER R0: immutable, tenant-scoped revision ledger for financial form drafts.
-- No DRAFT here is an approved source, FactSetRevision, or published diagnostic.
CREATE TABLE fin_intake.project_v5 (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  economic_entity_id UUID NOT NULL,
  business_scope_id UUID NOT NULL,
  project_code VARCHAR(50) NOT NULL CHECK(length(btrim(project_code)) BETWEEN 1 AND 50),
  accounting_currency CHAR(3) NOT NULL CHECK(accounting_currency ~ '^[A-Z]{3}$'),
  calendar_id VARCHAR(32) NOT NULL DEFAULT 'ISO_CALENDAR'
    CHECK(calendar_id='ISO_CALENDAR'),
  context_version INTEGER NOT NULL DEFAULT 1 CHECK(context_version=1),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by VARCHAR(200) NOT NULL CHECK(length(btrim(created_by))>0),
  CONSTRAINT uq_fin_v5_project_org_code UNIQUE(organization_id,project_code),
  CONSTRAINT uq_fin_v5_project_id_org UNIQUE(id,organization_id)
);
CREATE TRIGGER trg_fin_v5_project_immutable BEFORE UPDATE OR DELETE ON fin_intake.project_v5
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE TABLE fin_intake.form_slot_v5 (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  project_id UUID NOT NULL,
  form_code VARCHAR(64) NOT NULL CHECK(form_code='F02'),
  reporting_basis VARCHAR(24) NOT NULL CHECK(reporting_basis='MANAGEMENT'),
  period_type VARCHAR(16) NOT NULL CHECK(period_type='MONTH'),
  period_code VARCHAR(16) NOT NULL CHECK(period_code ~ '^[0-9]{4}\.(0[1-9]|1[0-2])$'),
  value_coverage VARCHAR(24) NOT NULL CHECK(value_coverage='PERIOD_ONLY'),
  report_currency CHAR(3) NOT NULL CHECK(report_currency ~ '^[A-Z]{3}$'),
  amount_scale INTEGER NOT NULL CHECK(amount_scale IN (1,1000,1000000)),
  latest_revision INTEGER NOT NULL DEFAULT 0 CHECK(latest_revision>=0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_fin_v5_slot_project FOREIGN KEY(project_id,organization_id)
    REFERENCES fin_intake.project_v5(id,organization_id),
  CONSTRAINT uq_fin_v5_slot_identity UNIQUE
    (project_id,form_code,reporting_basis,period_type,period_code,value_coverage),
  CONSTRAINT uq_fin_v5_slot_id_org UNIQUE(id,organization_id)
);
CREATE FUNCTION fin_intake.guard_form_slot_v5_update()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'FIN_V5_SLOT_DELETE_FORBIDDEN'; END IF;
  IF NEW.id IS DISTINCT FROM OLD.id
    OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
    OR NEW.project_id IS DISTINCT FROM OLD.project_id
    OR NEW.form_code IS DISTINCT FROM OLD.form_code
    OR NEW.reporting_basis IS DISTINCT FROM OLD.reporting_basis
    OR NEW.period_type IS DISTINCT FROM OLD.period_type
    OR NEW.period_code IS DISTINCT FROM OLD.period_code
    OR NEW.value_coverage IS DISTINCT FROM OLD.value_coverage
    OR NEW.report_currency IS DISTINCT FROM OLD.report_currency
    OR NEW.amount_scale IS DISTINCT FROM OLD.amount_scale
    OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR NEW.latest_revision <> OLD.latest_revision+1
  THEN RAISE EXCEPTION 'FIN_V5_SLOT_IDENTITY_OR_VERSION_IMMUTABLE'; END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER trg_fin_v5_slot_update BEFORE UPDATE OR DELETE ON fin_intake.form_slot_v5
FOR EACH ROW EXECUTE FUNCTION fin_intake.guard_form_slot_v5_update();

-- Each validation attempt is a retained source event, even when its content is
-- identical to the active revision. This records actual upload time separately
-- from the confirmation/save timestamp.
CREATE TABLE fin_intake.preview_v5 (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  slot_id UUID NOT NULL,
  expected_revision INTEGER NOT NULL CHECK(expected_revision>=0),
  source_kind VARCHAR(12) NOT NULL CHECK(source_kind IN ('MANUAL','XLSX')),
  input_locale VARCHAR(16) NOT NULL CHECK(input_locale IN ('ru','kk','en')),
  form_version VARCHAR(40) NOT NULL CHECK(form_version='FIN-FORM-V2'),
  uploaded_by VARCHAR(200) NOT NULL CHECK(length(btrim(uploaded_by))>0),
  uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  normalized_fingerprint CHAR(64) NOT NULL CHECK(normalized_fingerprint ~ '^[a-f0-9]{64}$'),
  confirmation_digest CHAR(64) NOT NULL CHECK(confirmation_digest ~ '^[a-f0-9]{64}$'),
  cells JSONB NOT NULL CHECK(jsonb_typeof(cells)='array'
    AND jsonb_array_length(cells) BETWEEN 1 AND 512),
  CONSTRAINT fk_fin_v5_preview_slot FOREIGN KEY(slot_id,organization_id)
    REFERENCES fin_intake.form_slot_v5(id,organization_id),
  CONSTRAINT uq_fin_v5_preview_id_slot UNIQUE(id,slot_id)
);
CREATE INDEX idx_fin_v5_previews_slot_time ON fin_intake.preview_v5
  (slot_id,uploaded_at DESC);
CREATE TRIGGER trg_fin_v5_preview_immutable BEFORE UPDATE OR DELETE ON fin_intake.preview_v5
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE TABLE fin_intake.draft_revision_v5 (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  slot_id UUID NOT NULL,
  revision_number INTEGER NOT NULL CHECK(revision_number>0),
  preview_id UUID NOT NULL UNIQUE,
  parent_revision_id UUID,
  normalized_fingerprint CHAR(64) NOT NULL CHECK(normalized_fingerprint ~ '^[a-f0-9]{64}$'),
  cells JSONB NOT NULL CHECK(jsonb_typeof(cells)='array' AND jsonb_array_length(cells) BETWEEN 1 AND 512),
  uploaded_by VARCHAR(200) NOT NULL CHECK(length(btrim(uploaded_by))>0),
  uploaded_at TIMESTAMPTZ NOT NULL,
  saved_by VARCHAR(200) NOT NULL CHECK(length(btrim(saved_by))>0),
  saved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  change_reason VARCHAR(1000) NOT NULL DEFAULT '',
  state VARCHAR(12) NOT NULL DEFAULT 'DRAFT' CHECK(state='DRAFT'),
  CONSTRAINT fk_fin_v5_draft_slot FOREIGN KEY(slot_id,organization_id)
    REFERENCES fin_intake.form_slot_v5(id,organization_id),
  CONSTRAINT fk_fin_v5_draft_preview FOREIGN KEY(preview_id,slot_id)
    REFERENCES fin_intake.preview_v5(id,slot_id),
  CONSTRAINT fk_fin_v5_draft_parent FOREIGN KEY(parent_revision_id,slot_id)
    REFERENCES fin_intake.draft_revision_v5(id,slot_id),
  CONSTRAINT uq_fin_v5_draft_slot_number UNIQUE(slot_id,revision_number),
  CONSTRAINT uq_fin_v5_draft_id_slot UNIQUE(id,slot_id),
  CONSTRAINT ck_fin_v5_reason_for_changes CHECK(revision_number=1 OR length(btrim(change_reason))>0)
);
CREATE INDEX idx_fin_v5_draft_history ON fin_intake.draft_revision_v5
  (slot_id,revision_number DESC);
CREATE TRIGGER trg_fin_v5_draft_immutable BEFORE UPDATE OR DELETE ON fin_intake.draft_revision_v5
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
