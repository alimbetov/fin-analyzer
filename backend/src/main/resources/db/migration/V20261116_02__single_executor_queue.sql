-- FIN P0-ENG-02/03. Durable at-least-once event intake and DAG task queue.
-- Outbox is immutable audit history (V20261116_01); delivery state lives here.
CREATE TABLE fin_exec.analysis_run (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE REFERENCES fin_exec.fact_publication_outbox(event_id),
    organization_id UUID NOT NULL,
    fact_set_revision_id UUID NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    profile_code VARCHAR(96) NOT NULL,
    run_status VARCHAR(24) NOT NULL DEFAULT 'QUEUED'
      CHECK (run_status IN ('QUEUED','PLANNING','RUNNING','COMPLETED','PARTIAL','FAILED','CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    CONSTRAINT fk_exec_run_factset FOREIGN KEY (fact_set_revision_id,organization_id)
      REFERENCES fin_fact.fact_set_publication(fact_set_revision_id,organization_id)
);
CREATE INDEX idx_exec_run_org ON fin_exec.analysis_run(organization_id,created_at DESC);

CREATE TABLE fin_exec.analysis_task (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES fin_exec.analysis_run(id),
    method_code VARCHAR(128) NOT NULL,
    task_status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
      CHECK (task_status IN ('PENDING','READY','RUNNING','COMPUTED',
                            'SKIPPED_NOT_APPLICABLE','BLOCKED_MISSING','BLOCKED_CONFLICT',
                            'DISABLED_POLICY','FAILED_RETRYABLE','FAILED_FINAL')),
    attempt INT NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    fencing_token BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    max_attempts INT NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 20),
    result_json JSONB,
    result_error VARCHAR(256),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_exec_run_method UNIQUE (run_id,method_code),
    CONSTRAINT ck_exec_result_when_computed CHECK (
       task_status <> 'COMPUTED' OR result_json IS NOT NULL)
);
CREATE INDEX idx_exec_tasks_runnable
ON fin_exec.analysis_task (next_attempt_at,lease_until,run_id)
WHERE task_status IN ('READY','RUNNING');

CREATE TABLE fin_exec.analysis_dependency (
    task_id UUID NOT NULL REFERENCES fin_exec.analysis_task(id),
    prerequisite_task_id UUID NOT NULL REFERENCES fin_exec.analysis_task(id),
    PRIMARY KEY (task_id,prerequisite_task_id),
    CONSTRAINT ck_exec_dependency_self CHECK (task_id <> prerequisite_task_id)
);

-- Once an event is consumed, its immutable source is left intact.
-- A failure to plan MUST roll back this row and all run/task writes together.
CREATE TABLE fin_exec.outbox_delivery (
    event_id UUID PRIMARY KEY REFERENCES fin_exec.fact_publication_outbox(event_id),
    analysis_run_id UUID NOT NULL UNIQUE REFERENCES fin_exec.analysis_run(id),
    planned_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
