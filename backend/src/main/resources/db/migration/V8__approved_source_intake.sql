-- FIN R4.2 verified source intake contract for five FIN-PILOT-V1 form families.
-- A referenced fingerprint is accepted only after trusted, independently reviewed
-- template extraction. Without that upstream step it is not evidence of source authenticity.
CREATE TABLE fin_fact.approved_source_intake (
    organization_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    normalized_cell_fingerprint CHAR(64) NOT NULL
        CHECK(normalized_cell_fingerprint ~ '^[0-9a-f]{64}$'),
    source_document_hash CHAR(64) NOT NULL
        CHECK(source_document_hash ~ '^[0-9a-f]{64}$'),
    input_currency CHAR(3) NOT NULL CHECK(input_currency ~ '^[A-Z]{3}$'),
    amount_scale INTEGER NOT NULL CHECK(amount_scale IN (1,1000)),
    expected_cell_count INTEGER NOT NULL CHECK(expected_cell_count > 0),
    reviewed_by VARCHAR(128) NOT NULL CHECK(length(btrim(reviewed_by))>0),
    reviewed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(organization_id,source_revision_id),
    FOREIGN KEY(organization_id,source_revision_id)
        REFERENCES fin_fact.approved_source_revision(organization_id,source_revision_id)
);
CREATE TRIGGER trg_source_intake_immutable BEFORE UPDATE OR DELETE
 ON fin_fact.approved_source_intake FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
