--liquibase formatted sql
--changeset fin-calc:028a dbms:postgresql splitStatements:false
-- HYB-2A fail-closed: NO retroactive executor binding for legacy F02 plans.
-- A future planner must mint an immutable DAG and fingerprint containing the
-- strategy + execution contract digest BEFORE any execution row is accepted.
CREATE FUNCTION fin_calc.validate_hybrid_execution_binding()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  pinned_strategy TEXT;
  pinned_contract TEXT;
  plan_mode TEXT;
  plan_artifact TEXT;
  plan_cfg TEXT;
  plan_params TEXT;
  plan_dag TEXT;
  method_state TEXT;
  method_artifact TEXT;
  method_cfg TEXT;
  method_params TEXT;
  method_dag TEXT;
BEGIN
  SELECT p.dag_jsonb->>'executionStrategy',
    p.dag_jsonb->>'executionContractSha256',
    p.qualification_mode,p.algorithm_artifact_sha256,
    p.config_sha256,p.params_sha256,p.dag_jsonb->>'dagSha256'
  INTO pinned_strategy,pinned_contract,plan_mode,
    plan_artifact,plan_cfg,plan_params,plan_dag
  FROM fin_calc.calc_plan p
  WHERE p.id=NEW.plan_id
    AND p.organization_id=NEW.organization_id
    AND p.scope_key=NEW.scope_key;

  SELECT r.registry_state,r.algorithm_artifact_sha256,
    r.config_sha256,r.params_sha256,r.dag_sha256
  INTO method_state,method_artifact,method_cfg,method_params,method_dag
  FROM fin_calc.method_revision r WHERE r.id=NEW.method_revision_id;

  IF plan_mode IS DISTINCT FROM 'TEST_QUALIFIED'
     OR method_state IS DISTINCT FROM 'TEST_ONLY_REFERENCE'
     OR pinned_strategy IS DISTINCT FROM NEW.strategy
     OR pinned_contract IS DISTINCT FROM NEW.execution_contract_sha256
     OR plan_artifact IS DISTINCT FROM method_artifact
     OR plan_cfg IS DISTINCT FROM method_cfg
     OR plan_params IS DISTINCT FROM method_params
     OR plan_dag IS DISTINCT FROM method_dag THEN
    RAISE EXCEPTION 'CALC_HYB_UNPINNED_EXECUTION_STRATEGY'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

--changeset fin-calc:028b dbms:postgresql splitStatements:true
CREATE TRIGGER trg_hyb_execution_admission
  BEFORE INSERT ON fin_calc.plan_execution_binding
  FOR EACH ROW EXECUTE FUNCTION fin_calc.validate_hybrid_execution_binding();
