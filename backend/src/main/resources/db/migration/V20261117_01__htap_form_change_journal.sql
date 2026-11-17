-- FIN HTAP W1.F166: durable tenant-scoped *draft* change notifications.
-- The ONLY trusted compute trigger remains fin_exec.fact_publication_outbox,
-- which is restricted to FACT_SET_PUBLISHED. Do not modify historic Flyway.
--
-- A journal row is written in the SAME transaction as the immutable confirmed
-- revision and catalog slot head update. It represents UNQUALIFIED_DRAFT data;
-- neither its presence nor a SHA-256 hash qualifies financial ACTUAL execution.
CREATE TABLE fin_exec.form_change_journal (
    event_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    slot_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL
        CHECK (event_type = 'TENANT_FORM_REVISION_CONFIRMED'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_htap_form_change_revision UNIQUE(revision_id),
    CONSTRAINT fk_fin_htap_form_change_tenant_slot
        FOREIGN KEY(slot_id,organization_id)
        REFERENCES fin_intake.catalog_xlsx_slot(id,organization_id),
    CONSTRAINT fk_fin_htap_form_change_revision_slot
        FOREIGN KEY(revision_id,slot_id)
        REFERENCES fin_intake.catalog_xlsx_revision(id,slot_id)
);

CREATE INDEX idx_fin_htap_form_change_pending
    ON fin_exec.form_change_journal(recorded_at,event_id);
CREATE INDEX idx_fin_htap_form_change_tenant_slot
    ON fin_exec.form_change_journal(organization_id,slot_id,recorded_at DESC);

-- Immutable event history. Delivery, retry and impact status must be stored
-- separately (future gated W1.F166), not by updating the source journal.
CREATE FUNCTION fin_exec.reject_form_change_journal_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'FIN_HTAP_FORM_CHANGE_EVENT_IMMUTABLE'
        USING ERRCODE = '23514';
END
$$;

CREATE TRIGGER trg_fin_htap_form_change_immutable
BEFORE UPDATE OR DELETE ON fin_exec.form_change_journal
FOR EACH ROW EXECUTE FUNCTION fin_exec.reject_form_change_journal_mutation();
