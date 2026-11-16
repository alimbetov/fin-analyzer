-- FIN-BE-R1-G: server-issued template manifests, independent of review/FactSet.
-- Additive; V1..V11 migration checksums must remain unchanged.
CREATE TABLE fin_intake.catalog_xlsx_issued_template (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    economic_entity_id UUID NOT NULL,
    business_scope_id UUID NOT NULL,
    project_code VARCHAR(50) NOT NULL,
    form_code VARCHAR(64) NOT NULL,
    reporting_basis VARCHAR(24) NOT NULL,
    period_type VARCHAR(16) NOT NULL,
    period_code VARCHAR(20) NOT NULL,
    coverage VARCHAR(24) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_scale INTEGER NOT NULL CHECK (amount_scale IN (1, 1000, 1000000)),
    input_locale VARCHAR(8) NOT NULL CHECK (input_locale IN ('ru','kk','en')),
    format_version VARCHAR(48) NOT NULL
        CHECK (format_version='FIN-BE-CATALOG-XLSX-V2'),
    schema_fingerprint CHAR(64) NOT NULL
        CHECK (schema_fingerprint ~ '^[a-f0-9]{64}$'),
    original_template_sha CHAR(64) NOT NULL
        CHECK (original_template_sha ~ '^[a-f0-9]{64}$'),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_catalog_issued_project FOREIGN KEY (project_id, organization_id)
        REFERENCES fin_intake.project_v5(id, organization_id),
    CONSTRAINT uq_catalog_issued_id_org UNIQUE (id, organization_id)
);
CREATE INDEX idx_catalog_issued_project
    ON fin_intake.catalog_xlsx_issued_template
    (organization_id, project_id, form_code, period_code, issued_at DESC);
CREATE TRIGGER trg_catalog_issued_immutable
    BEFORE UPDATE OR DELETE ON fin_intake.catalog_xlsx_issued_template
    FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

ALTER TABLE fin_intake.catalog_xlsx_draft
    ADD COLUMN issued_template_id UUID;
ALTER TABLE fin_intake.catalog_xlsx_draft
    ADD CONSTRAINT fk_catalog_draft_issued
    FOREIGN KEY (issued_template_id, organization_id)
    REFERENCES fin_intake.catalog_xlsx_issued_template(id, organization_id);

-- The V11 catalog slot guard blocked identity mutation, but did not enforce
-- advance-by-one or the existence of the committed revision referenced by head.
-- Close that data-consistency gap without touching the existing F02 V5 tables.
CREATE OR REPLACE FUNCTION fin_intake.catalog_slot_identity_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'FIN_CATALOG_SLOT_DELETE_FORBIDDEN';
    END IF;
    IF ROW(OLD.id, OLD.organization_id, OLD.project_id, OLD.form_code,
           OLD.reporting_basis, OLD.period_type, OLD.period_code,
           OLD.coverage, OLD.currency, OLD.amount_scale)
       IS DISTINCT FROM
       ROW(NEW.id, NEW.organization_id, NEW.project_id, NEW.form_code,
           NEW.reporting_basis, NEW.period_type, NEW.period_code,
           NEW.coverage, NEW.currency, NEW.amount_scale)
    THEN
        RAISE EXCEPTION 'FIN_CATALOG_SLOT_IDENTITY_IMMUTABLE';
    END IF;
    IF NEW.latest_revision <> OLD.latest_revision + 1 OR NOT EXISTS (
        SELECT 1 FROM fin_intake.catalog_xlsx_revision r
         WHERE r.slot_id=OLD.id AND r.organization_id=OLD.organization_id
           AND r.revision_number=NEW.latest_revision
    ) THEN
        RAISE EXCEPTION 'FIN_CATALOG_SLOT_REVISION_CHAIN_INVALID';
    END IF;
    RETURN NEW;
END $$;
