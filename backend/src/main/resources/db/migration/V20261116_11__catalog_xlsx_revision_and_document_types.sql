-- FIN-BE-R1 audit P0: extend only new catalog intake; keep V10 checksums intact.
-- Document originals are unqualified evidence, never source approval or ACTUAL.
ALTER TABLE fin_intake.catalog_xlsx_draft
    DROP CONSTRAINT ck_fin_catalog_xlsx_document_presence;
ALTER TABLE fin_intake.catalog_xlsx_draft
    ADD CONSTRAINT ck_fin_catalog_xlsx_document_presence CHECK (
        (capture_mode='EVIDENCE_ONLY'
          AND document_original IS NOT NULL AND document_sha IS NOT NULL
          AND document_media_type IN (
            'application/pdf',
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            'application/xml','image/png','image/jpeg'))
        OR (capture_mode<>'EVIDENCE_ONLY'
          AND document_original IS NULL AND document_sha IS NULL
          AND document_media_type IS NULL)
    );
ALTER TABLE fin_intake.catalog_xlsx_draft
    ADD CONSTRAINT uq_fin_catalog_xlsx_draft_id_org UNIQUE(id,organization_id);

-- Associate a confirmed catalog draft with an existing FIN V5 project while
-- leaving the established F02 form_slot_v5 / draft_revision_v5 path untouched.
CREATE TABLE fin_intake.catalog_xlsx_slot (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    form_code VARCHAR(64) NOT NULL,
    reporting_basis VARCHAR(24) NOT NULL,
    period_type VARCHAR(16) NOT NULL,
    period_code VARCHAR(20) NOT NULL,
    coverage VARCHAR(24) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_scale INTEGER NOT NULL,
    latest_revision INTEGER NOT NULL DEFAULT 0 CHECK(latest_revision>=0),
    CONSTRAINT fk_fin_catalog_slot_project
      FOREIGN KEY(project_id,organization_id)
      REFERENCES fin_intake.project_v5(id,organization_id),
    CONSTRAINT uq_fin_catalog_slot_identity UNIQUE(
      project_id,form_code,reporting_basis,period_type,period_code,coverage,
      currency,amount_scale),
    CONSTRAINT uq_fin_catalog_slot_id_org UNIQUE(id,organization_id)
);

CREATE TABLE fin_intake.catalog_xlsx_revision (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    slot_id UUID NOT NULL,
    draft_id UUID NOT NULL,
    revision_number INTEGER NOT NULL CHECK(revision_number>0),
    parent_revision_id UUID,
    normalized_sha CHAR(64) NOT NULL CHECK(normalized_sha ~ '^[a-f0-9]{64}$'),
    confirmed_by VARCHAR(200) NOT NULL CHECK(length(btrim(confirmed_by))>0),
    change_reason VARCHAR(1000) NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    state VARCHAR(40) NOT NULL DEFAULT 'CONFIRMED_UNQUALIFIED_DRAFT'
      CHECK(state='CONFIRMED_UNQUALIFIED_DRAFT'),
    CONSTRAINT fk_fin_catalog_revision_slot FOREIGN KEY(slot_id,organization_id)
      REFERENCES fin_intake.catalog_xlsx_slot(id,organization_id),
    CONSTRAINT fk_fin_catalog_revision_draft FOREIGN KEY(draft_id,organization_id)
      REFERENCES fin_intake.catalog_xlsx_draft(id,organization_id),
    CONSTRAINT uq_fin_catalog_revision_one_confirm_per_draft UNIQUE(draft_id),
    CONSTRAINT uq_fin_catalog_revision_slot_number UNIQUE(slot_id,revision_number),
    CONSTRAINT uq_fin_catalog_revision_id_slot UNIQUE(id,slot_id),
    CONSTRAINT fk_fin_catalog_revision_parent FOREIGN KEY(parent_revision_id,slot_id)
      REFERENCES fin_intake.catalog_xlsx_revision(id,slot_id)
);
CREATE INDEX idx_fin_catalog_revision_history
  ON fin_intake.catalog_xlsx_revision(slot_id,revision_number DESC);

CREATE TRIGGER trg_fin_catalog_revision_immutable
BEFORE UPDATE OR DELETE ON fin_intake.catalog_xlsx_revision
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- Do not allow changing the identity coordinates of a slot: only revision
-- head advances. No access-control/IAM schema or existing V5 table is changed.
CREATE FUNCTION fin_intake.catalog_slot_identity_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'FIN_CATALOG_SLOT_DELETE_FORBIDDEN';
    END IF;
    IF (ROW(OLD.id,OLD.organization_id,OLD.project_id,OLD.form_code,
            OLD.reporting_basis,OLD.period_type,OLD.period_code,
            OLD.coverage,OLD.currency,OLD.amount_scale)
       IS DISTINCT FROM
        ROW(NEW.id,NEW.organization_id,NEW.project_id,NEW.form_code,
            NEW.reporting_basis,NEW.period_type,NEW.period_code,
            NEW.coverage,NEW.currency,NEW.amount_scale))
    THEN
        RAISE EXCEPTION 'FIN_CATALOG_SLOT_IDENTITY_IMMUTABLE';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_fin_catalog_slot_identity_guard
BEFORE UPDATE OR DELETE ON fin_intake.catalog_xlsx_slot
FOR EACH ROW EXECUTE FUNCTION fin_intake.catalog_slot_identity_guard();
