--liquibase formatted sql
--changeset fin-calc:045a dbms:postgresql splitStatements:true
-- CALC-4D.5: separate mutable UAT dispatch control. Immutable inputs, plans
-- and results remain append-only. No changes to HYB-2B/prod admission.
CREATE TABLE fin_calc.calc4d_uat_job (
 id uuid PRIMARY KEY,
 plan_id uuid NOT NULL,
 organization_id uuid NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'READY'
   CHECK (state IN ('READY','RUNNING','RETRY','SUCCEEDED','FAILED')),
 attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
 max_attempts integer NOT NULL DEFAULT 4 CHECK (max_attempts BETWEEN 1 AND 10),
 fencing_token bigint NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
 lease_owner varchar(128),
 lease_until timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 last_error_code varchar(96),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT fk_calc4d_uat_job_plan
   FOREIGN KEY (plan_id,organization_id)
   REFERENCES fin_calc.calc4d_uat_plan(id,organization_id),
 CONSTRAINT uq_calc4d_uat_job_plan UNIQUE (plan_id),
 CONSTRAINT ck_calc4d_uat_job_owner_lease CHECK (
   (state='RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL
      AND btrim(lease_owner)<>'' AND fencing_token>0 AND attempts>0)
   OR (state<>'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)),
 CONSTRAINT ck_calc4d_uat_job_completed CHECK (
   state<>'SUCCEEDED' OR attempts>0)
);
CREATE INDEX idx_calc4d_uat_job_claim
 ON fin_calc.calc4d_uat_job(organization_id,state,next_attempt_at,created_at)
 WHERE state IN ('READY','RETRY','RUNNING');
CREATE INDEX idx_calc4d_uat_job_lease
 ON fin_calc.calc4d_uat_job(organization_id,lease_until)
 WHERE state='RUNNING';

--changeset fin-calc:045b dbms:postgresql splitStatements:true
-- An immutable audit witness for each committed claim/retry/completion.
CREATE TABLE fin_calc.calc4d_uat_job_receipt (
 id uuid PRIMARY KEY,
 job_id uuid NOT NULL REFERENCES fin_calc.calc4d_uat_job(id),
 organization_id uuid NOT NULL,
 fencing_token bigint NOT NULL CHECK(fencing_token>=0),
 event_code varchar(24) NOT NULL CHECK(event_code IN
   ('ENQUEUED','CLAIMED','RENEWED','RETRY','FAILED','SUCCEEDED','EXHAUSTED')),
 worker_id varchar(128),
 event_sha256 char(64) NOT NULL CHECK(event_sha256 ~ '^[0-9a-f]{64}$'),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT uq_calc4d_uat_job_receipt_event UNIQUE (job_id,event_sha256)
);
CREATE INDEX idx_calc4d_uat_job_receipt
 ON fin_calc.calc4d_uat_job_receipt(job_id,occurred_at);

--changeset fin-calc:045c dbms:postgresql splitStatements:false
-- Every result inserted for a queued plan MUST be written by the current
-- fenced lease owner in the same PostgreSQL transaction.
-- Transaction-local values are installed by Calc4DUatLeasedWorker.execute.
-- This UAT guard intentionally preserves legacy non-queued preview tests;
-- it is not a replacement for IAM-based production S2 admission.
CREATE FUNCTION fin_calc.calc4d_validate_uat_result_fence()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  j record;
  worker text;
  token text;
BEGIN
  SELECT id,state,fencing_token,lease_owner,lease_until
    INTO j
  FROM fin_calc.calc4d_uat_job
  WHERE plan_id=NEW.plan_id AND organization_id=NEW.organization_id
  FOR UPDATE;
  IF FOUND THEN
    worker:=current_setting('fin.calc4d.uat_worker',true);
    token:=current_setting('fin.calc4d.uat_fence',true);
    IF j.state IS DISTINCT FROM 'RUNNING'
       OR j.lease_owner IS DISTINCT FROM worker
       OR token IS DISTINCT FROM j.fencing_token::text
       OR j.lease_until IS NULL OR j.lease_until<=clock_timestamp()
    THEN
      RAISE EXCEPTION 'CALC4D_UAT_STALE_OR_UNOWNED_FENCE'
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

--changeset fin-calc:045d dbms:postgresql splitStatements:true
CREATE TRIGGER trg_calc4d_uat_result_fence
 BEFORE INSERT ON fin_calc.calc4d_uat_result
 FOR EACH ROW EXECUTE FUNCTION fin_calc.calc4d_validate_uat_result_fence();
CREATE TRIGGER trg_calc4d_uat_receipt_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.calc4d_uat_job_receipt FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc4d_uat_receipt_no_truncate
 BEFORE TRUNCATE ON fin_calc.calc4d_uat_job_receipt FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
