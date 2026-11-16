-- EXEC-R3 pilot: immutable CORE composite report, not a bank/investor approval.
-- Even on fully completed SYSTEM tasks, readiness is PARTIAL unless the
-- required business-method profile has independently qualified executions.
CREATE TABLE fin_exec.core_report_snapshot (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE REFERENCES fin_exec.analysis_run(id),
    organization_id UUID NOT NULL,
    fact_set_revision_id UUID NOT NULL,
    report_mode VARCHAR(16) NOT NULL DEFAULT 'CORE' CHECK (report_mode='CORE'),
    core_readiness VARCHAR(16) NOT NULL DEFAULT 'PARTIAL'
      CHECK (core_readiness IN ('PARTIAL','BLOCKED')),
    ml_readiness VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUESTED'
      CHECK (ml_readiness='NOT_REQUESTED'),
    schema_version VARCHAR(32) NOT NULL DEFAULT 'CORE_PILOT_V1',
    snapshot JSONB NOT NULL,
    snapshot_hash CHAR(64) NOT NULL DEFAULT repeat('0',64),
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_core_snapshot_trusted_source FOREIGN KEY (fact_set_revision_id,organization_id)
      REFERENCES fin_fact.fact_set_publication(fact_set_revision_id,organization_id),
    CONSTRAINT uq_core_report_org_run UNIQUE(id,organization_id),
    CONSTRAINT ck_core_snapshot_object CHECK (jsonb_typeof(snapshot)='object')
);
CREATE OR REPLACE FUNCTION fin_exec.trusted_core_snapshot_hash()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
      SELECT 1 FROM fin_exec.analysis_run r
      WHERE r.id=NEW.run_id AND r.organization_id=NEW.organization_id
        AND r.fact_set_revision_id=NEW.fact_set_revision_id
        AND r.run_status IN ('COMPLETED','PARTIAL','FAILED')
    ) THEN RAISE EXCEPTION 'CORE_REPORT_RUN_NOT_TERMINAL_OR_FOREIGN'; END IF;
    NEW.snapshot_hash=encode(sha256(convert_to(NEW.snapshot::text,'UTF8')),'hex');
    RETURN NEW;
END $$;
CREATE TRIGGER trg_core_snapshot_integrity BEFORE INSERT
ON fin_exec.core_report_snapshot FOR EACH ROW
EXECUTE FUNCTION fin_exec.trusted_core_snapshot_hash();
CREATE TRIGGER trg_core_snapshot_immutable BEFORE UPDATE OR DELETE
ON fin_exec.core_report_snapshot FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE INDEX idx_core_snapshot_org ON fin_exec.core_report_snapshot
(organization_id,published_at DESC);
