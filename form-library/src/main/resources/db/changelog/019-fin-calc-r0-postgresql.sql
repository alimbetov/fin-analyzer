--liquibase formatted sql
--changeset fin-calc:019a dbms:postgresql splitStatements:true
-- FACT-0 prerequisite: storage contracts only. No dispatch, S2 or calculation executes.
-- Historical 001-018 checksums are left intact.
CREATE SCHEMA IF NOT EXISTS fin_calc;

CREATE TABLE fin_calc.calc_outbox_event (
 id UUID PRIMARY KEY,
 organization_id UUID NOT NULL,
 scope_key CHAR(64) NOT NULL,
 producer VARCHAR(80) NOT NULL,
 producer_event_id VARCHAR(160) NOT NULL,
 event_type VARCHAR(64) NOT NULL CHECK (event_type IN
  ('DRAFT_REVISION_SAVED','FACTSET_QUALIFIED','FACTSET_REVOKED')),
 payload_jsonb JSONB NOT NULL CHECK(jsonb_typeof(payload_jsonb)='object'),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 processed_at TIMESTAMPTZ,
 attempts INT NOT NULL DEFAULT 0 CHECK(attempts >= 0),
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 last_error_code VARCHAR(80),
 CONSTRAINT uq_calc_outbox_producer_event UNIQUE(producer,producer_event_id)
);
CREATE INDEX idx_calc_outbox_ready
 ON fin_calc.calc_outbox_event(next_attempt_at,created_at)
 WHERE processed_at IS NULL;

CREATE TABLE fin_calc.calc_plan (
 id UUID PRIMARY KEY,
 organization_id UUID NOT NULL,
 scope_key CHAR(64) NOT NULL,
 factset_id UUID NOT NULL,
 execution_fingerprint CHAR(64) NOT NULL,
 qualified_binding_sha256 CHAR(64) NOT NULL,
 qualification_epoch BIGINT NOT NULL CHECK(qualification_epoch >= 0),
 algorithm_artifact_sha256 CHAR(64) NOT NULL,
 config_sha256 CHAR(64) NOT NULL,
 params_sha256 CHAR(64) NOT NULL,
 dag_jsonb JSONB NOT NULL CHECK (jsonb_typeof(dag_jsonb)='object'),
 qualification_mode VARCHAR(32) NOT NULL CHECK(qualification_mode='TEST_QUALIFIED'),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CONSTRAINT uq_calc_plan_scope_execution UNIQUE(organization_id,scope_key,execution_fingerprint),
 CONSTRAINT uq_calc_plan_scope_identity UNIQUE(id,organization_id,scope_key)
);
CREATE INDEX idx_calc_plan_factset ON fin_calc.calc_plan(factset_id,created_at);

CREATE TABLE fin_calc.calc_job (
 id UUID PRIMARY KEY,
 plan_id UUID NOT NULL,
 organization_id UUID NOT NULL,
 scope_key CHAR(64) NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'READY' CHECK(state IN
  ('READY','RUNNING','RETRY','SUCCEEDED','BLOCKED','FAILED')),
 attempts INT NOT NULL DEFAULT 0 CHECK(attempts >= 0),
 fencing_token BIGINT NOT NULL DEFAULT 0 CHECK(fencing_token >= 0),
 lease_owner VARCHAR(160),
 lease_until TIMESTAMPTZ,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 last_error_code VARCHAR(80),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CONSTRAINT fk_calc_job_plan_scope FOREIGN KEY(plan_id,organization_id,scope_key)
  REFERENCES fin_calc.calc_plan(id,organization_id,scope_key),
 CONSTRAINT uq_calc_job_plan UNIQUE(plan_id),
 CONSTRAINT ck_calc_job_lease_pair CHECK
  ((lease_owner IS NULL AND lease_until IS NULL)
   OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX idx_calc_job_claim ON fin_calc.calc_job(state,next_attempt_at,created_at)
 WHERE state IN ('READY','RETRY');
CREATE INDEX idx_calc_job_reclaim ON fin_calc.calc_job(lease_until)
 WHERE state='RUNNING';

CREATE TABLE fin_calc.calc_result (
 id UUID PRIMARY KEY,
 plan_id UUID NOT NULL,
 organization_id UUID NOT NULL,
 scope_key CHAR(64) NOT NULL,
 execution_fingerprint CHAR(64) NOT NULL,
 output_values_sha256 CHAR(64) NOT NULL,
 result_sha256 CHAR(64) NOT NULL,
 result_jsonb JSONB NOT NULL CHECK(jsonb_typeof(result_jsonb)='object'),
 lineage_jsonb JSONB NOT NULL CHECK(jsonb_typeof(lineage_jsonb)='object'),
 qualification_mode VARCHAR(32) NOT NULL CHECK(qualification_mode='LOCAL_TEST_ONLY'),
 computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CONSTRAINT fk_calc_result_plan_scope FOREIGN KEY(plan_id,organization_id,scope_key)
  REFERENCES fin_calc.calc_plan(id,organization_id,scope_key),
 CONSTRAINT uq_calc_result_exact_execution UNIQUE(organization_id,scope_key,execution_fingerprint),
 CONSTRAINT uq_calc_result_scope_identity UNIQUE(id,organization_id,scope_key)
);
CREATE INDEX idx_calc_result_plan ON fin_calc.calc_result(plan_id);

CREATE TABLE fin_calc.calc_current_result (
 id UUID PRIMARY KEY,
 organization_id UUID NOT NULL,
 scope_key CHAR(64) NOT NULL,
 result_id UUID,
 qualification_epoch BIGINT NOT NULL DEFAULT 0 CHECK(qualification_epoch >= 0),
 row_version BIGINT NOT NULL DEFAULT 0 CHECK(row_version >= 0),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CONSTRAINT uq_calc_current_scope UNIQUE(organization_id,scope_key),
 CONSTRAINT fk_calc_current_same_scope FOREIGN KEY(result_id,organization_id,scope_key)
  REFERENCES fin_calc.calc_result(id,organization_id,scope_key)
);
