--liquibase formatted sql
--changeset fin-calc:048a dbms:postgresql splitStatements:true
-- CALC-4D.5 immutable result lineage: a queued result must retain the exact
-- UAT job and monotonically increasing token that authorized its insertion.
-- Legacy non-queued UAT preview results remain explicitly NULL/NULL.
ALTER TABLE fin_calc.calc4d_uat_result
 ADD COLUMN worker_job_id uuid REFERENCES fin_calc.calc4d_uat_job(id),
 ADD COLUMN worker_fencing_token bigint
   CHECK(worker_fencing_token IS NULL OR worker_fencing_token>0);
ALTER TABLE fin_calc.calc4d_uat_result
 ADD CONSTRAINT ck_calc4d_uat_result_fence_lineage_pair CHECK (
   (worker_job_id IS NULL AND worker_fencing_token IS NULL)
   OR (worker_job_id IS NOT NULL AND worker_fencing_token IS NOT NULL));

--changeset fin-calc:048b dbms:postgresql splitStatements:false
CREATE FUNCTION fin_calc.calc4d_stamp_uat_result_fencing_lineage()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
 j record;
 current_worker text;
 current_fence text;
BEGIN
  SELECT id,plan_id,organization_id,lease_owner,fencing_token,
    state,lease_until
  INTO j FROM fin_calc.calc4d_uat_job
  WHERE plan_id=NEW.plan_id AND organization_id=NEW.organization_id
  FOR UPDATE;
  IF NOT FOUND THEN
    IF NEW.worker_job_id IS NOT NULL OR NEW.worker_fencing_token IS NOT NULL
    THEN
      RAISE EXCEPTION 'CALC4D_UAT_UNQUEUED_RESULT_CANNOT_FORGE_WORKER'
        USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;

  current_worker:=current_setting('fin.calc4d.uat_worker',true);
  current_fence:=current_setting('fin.calc4d.uat_fence',true);
  IF j.state IS DISTINCT FROM 'RUNNING'
     OR j.lease_owner IS DISTINCT FROM current_worker
     OR current_fence IS DISTINCT FROM j.fencing_token::text
     OR j.lease_until IS NULL OR j.lease_until<=clock_timestamp()
     OR (NEW.worker_job_id IS NOT NULL
         AND NEW.worker_job_id IS DISTINCT FROM j.id)
     OR (NEW.worker_fencing_token IS NOT NULL
         AND NEW.worker_fencing_token IS DISTINCT FROM j.fencing_token)
  THEN
    RAISE EXCEPTION 'CALC4D_UAT_UNTRUSTED_RESULT_FENCE_LINEAGE'
      USING ERRCODE='23514';
  END IF;
  NEW.worker_job_id:=j.id;
  NEW.worker_fencing_token:=j.fencing_token;
  RETURN NEW;
END;
$$;

--changeset fin-calc:048c dbms:postgresql splitStatements:true
-- Same BEFORE INSERT timing as the earlier 045 guard; 'z_' ensures the
-- original guard executes first in PostgreSQL trigger-name order.
CREATE TRIGGER trg_calc4d_uat_result_z_stamp_fence_lineage
 BEFORE INSERT ON fin_calc.calc4d_uat_result FOR EACH ROW
 EXECUTE FUNCTION fin_calc.calc4d_stamp_uat_result_fencing_lineage();
