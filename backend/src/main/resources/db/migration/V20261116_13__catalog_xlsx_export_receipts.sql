-- FIN-BE-R1-X2: immutable XLSX export provenance; not a financial source approval.
-- Does not modify any previous Flyway version or the reviewed F02 pipeline.
CREATE TABLE fin_intake.catalog_xlsx_export_receipt (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    source_draft_id UUID NOT NULL,
    issued_template_id UUID NOT NULL UNIQUE,
    form_code VARCHAR(64) NOT NULL,
    source_normalized_sha CHAR(64) NOT NULL CHECK (source_normalized_sha ~ '^[a-f0-9]{64}$'),
    workbook_sha CHAR(64) NOT NULL CHECK (workbook_sha ~ '^[a-f0-9]{64}$'),
    source_input_locale VARCHAR(8) NOT NULL CHECK (source_input_locale IN ('ru','kk','en')),
    display_locale VARCHAR(12) NOT NULL CHECK (display_locale IN ('ru','kk','en','zh-Hans')),
    generated_by VARCHAR(200) NOT NULL CHECK (length(btrim(generated_by)) > 0),
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    state VARCHAR(12) NOT NULL DEFAULT 'GENERATED' CHECK (state = 'GENERATED'),
    CONSTRAINT fk_fin_x2_receipt_project FOREIGN KEY (project_id,organization_id)
        REFERENCES fin_intake.project_v5(id,organization_id),
    CONSTRAINT fk_fin_x2_receipt_issued FOREIGN KEY (issued_template_id,organization_id)
        REFERENCES fin_intake.catalog_xlsx_issued_template(id,organization_id),
    CONSTRAINT fk_fin_x2_receipt_revision FOREIGN KEY (source_revision_id)
        REFERENCES fin_intake.catalog_xlsx_revision(id),
    CONSTRAINT fk_fin_x2_receipt_draft FOREIGN KEY (source_draft_id,organization_id)
        REFERENCES fin_intake.catalog_xlsx_draft(id,organization_id)
);
CREATE INDEX idx_fin_x2_receipt_project
    ON fin_intake.catalog_xlsx_export_receipt
    (organization_id,project_id,form_code,generated_at DESC);
CREATE TRIGGER trg_fin_x2_export_receipt_immutable
    BEFORE UPDATE OR DELETE ON fin_intake.catalog_xlsx_export_receipt
    FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
