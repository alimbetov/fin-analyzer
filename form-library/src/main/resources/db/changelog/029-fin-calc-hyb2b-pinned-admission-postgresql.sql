--liquibase formatted sql
--changeset fin-calc:029a dbms:postgresql splitStatements:false
-- HYB-2B: a strategy-aware plan can be accepted only when ALL declared
-- binding artifacts and the existing qualified F02 method are pinned.
-- This UAT gate is not an S2 financial approval.
CREATE FUNCTION fin_calc.hyb2b_validate_strategy_binding()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  p RECORD;
  cnt INTEGER;
BEGIN
  SELECT dag_jsonb,execution_fingerprint,factset_id,organization_id,scope_key
  INTO p FROM fin_calc.calc_plan WHERE id=NEW.plan_id;
  IF NOT FOUND
     OR p.dag_jsonb->>'hybridContract' IS DISTINCT FROM 'FIN-CALC-HYB2B-F02-TEST-V1'
     OR p.dag_jsonb->>'methodRevisionId' IS DISTINCT FROM NEW.method_revision_id::text
     OR p.dag_jsonb->>'executionFingerprint' IS DISTINCT FROM p.execution_fingerprint
     OR p.dag_jsonb->>'exactFactsetId' IS DISTINCT FROM p.factset_id::text
     OR p.dag_jsonb->>'executionStrategy' IS DISTINCT FROM NEW.strategy
     OR p.dag_jsonb->>'executionContractSha256' IS DISTINCT FROM NEW.execution_contract_sha256
     OR p.organization_id IS DISTINCT FROM NEW.organization_id
     OR p.scope_key IS DISTINCT FROM NEW.scope_key THEN
    RAISE EXCEPTION 'CALC_HYB2B_PLAN_BINDING_UNPINNED' USING ERRCODE='23514';
  END IF;
  SELECT count(*) INTO cnt FROM fin_calc.plan_factset_binding b
  WHERE b.plan_id=NEW.plan_id AND b.organization_id=NEW.organization_id
    AND b.plan_scope_key=NEW.scope_key AND b.ordinal=0
    AND b.source_factset_id=p.factset_id
    AND b.source_scope_key=NEW.scope_key;
  IF cnt<>1 THEN
    RAISE EXCEPTION 'CALC_HYB2B_SOURCE_BINDING_REQUIRED' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

--changeset fin-calc:029b dbms:postgresql splitStatements:true
CREATE TRIGGER trg_hyb2b_strategy_guard
 BEFORE INSERT ON fin_calc.plan_execution_binding FOR EACH ROW
 EXECUTE FUNCTION fin_calc.hyb2b_validate_strategy_binding();

--changeset fin-calc:029c dbms:postgresql splitStatements:false
-- Prevent legacy F02 workers (and rogue callers) from claiming a HYB plan
-- before strategy-aware worker + independent authority are accepted.
CREATE FUNCTION fin_calc.hyb2b_guard_blocked_job()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  c TEXT;
BEGIN
  SELECT dag_jsonb->>'hybridContract' INTO c
  FROM fin_calc.calc_plan WHERE id=NEW.plan_id;
  IF c='FIN-CALC-HYB2B-F02-TEST-V1' THEN
    IF NEW.state IS DISTINCT FROM 'BLOCKED'
       OR NEW.last_error_code IS DISTINCT FROM 'HYB_WORKER_NOT_ADMITTED'
       OR NEW.lease_owner IS NOT NULL OR NEW.lease_until IS NOT NULL THEN
      RAISE EXCEPTION 'CALC_HYB2B_JOB_EXECUTION_NOT_ADMITTED'
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

--changeset fin-calc:029d dbms:postgresql splitStatements:true
CREATE TRIGGER trg_hyb2b_block_job
 BEFORE INSERT OR UPDATE ON fin_calc.calc_job FOR EACH ROW
 EXECUTE FUNCTION fin_calc.hyb2b_guard_blocked_job();
