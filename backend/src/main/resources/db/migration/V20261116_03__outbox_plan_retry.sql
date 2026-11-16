-- FIN P0-ENG-02 resilience: isolate poison planning events without
-- modifying immutable outbox evidence. More events can continue processing.
CREATE TABLE fin_exec.outbox_planning_failure (
    event_id UUID PRIMARY KEY REFERENCES fin_exec.fact_publication_outbox(event_id),
    attempt_count INT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    dead_letter BOOLEAN NOT NULL DEFAULT FALSE,
    last_error_code VARCHAR(64) NOT NULL DEFAULT 'PLANNING_FAILED',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fin_outbox_planning_retry
ON fin_exec.outbox_planning_failure (next_retry_at)
WHERE dead_letter=FALSE;
