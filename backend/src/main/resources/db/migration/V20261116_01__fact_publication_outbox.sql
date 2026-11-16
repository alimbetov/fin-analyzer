-- FIN P0-ENG-02: transactional outbox for an authoritative fact-set publication.
-- Events are appended in the same transaction as fin_fact.fact_set_publication.
-- Consumers are added separately only after lease/fencing and ABAC gates are qualified.
CREATE SCHEMA IF NOT EXISTS fin_exec;

CREATE TABLE fin_exec.fact_publication_outbox (
    event_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    economic_entity_id UUID NOT NULL,
    business_scope_id UUID NOT NULL,
    fact_set_revision_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL
        CHECK (event_type = 'FACT_SET_PUBLISHED'),
    reporting_basis VARCHAR(64) NOT NULL,
    data_space VARCHAR(64) NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL
        CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    current_version BIGINT NOT NULL CHECK (current_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_fact_publication_event UNIQUE (
        organization_id, fact_set_revision_id, event_type
    ),
    CONSTRAINT fk_fin_fact_publication_event
        FOREIGN KEY (fact_set_revision_id)
        REFERENCES fin_fact.fact_set_revision(id)
);

CREATE INDEX idx_fin_fact_publication_outbox_created
ON fin_exec.fact_publication_outbox (created_at, event_id);

-- Prevent accidental mutation; outbox delivery will use a separate
-- consumer delivery ledger and must never delete or rewrite these audit events.
CREATE OR REPLACE FUNCTION fin_exec.reject_published_event_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'IMMUTABLE_FACT_PUBLICATION_EVENT' USING ERRCODE = '23514';
END
$$;

CREATE TRIGGER trg_fin_outbox_immutable
BEFORE UPDATE OR DELETE ON fin_exec.fact_publication_outbox
FOR EACH ROW EXECUTE FUNCTION fin_exec.reject_published_event_change();
