--liquibase formatted sql
--changeset fin-calc:031a dbms:postgresql splitStatements:false
-- CALC-166 P0: read-only candidate SUM for an EXACT pinned, test-only FactSet.
-- This is an arithmetic primitive. It does NOT infer SUM policy, produce
-- financial approval, write calc_result, select latest revisions or change S2.
-- No AS_OF/YTD/LTD mixing and no currency/unit or dimension mixing.
CREATE FUNCTION fin_calc.candidate_interval_sum(
    p_organization_id UUID,
    p_factset_id UUID,
    p_form_code VARCHAR,
    p_metric_code VARCHAR,
    p_period_start DATE,
    p_period_end_exclusive DATE,
    p_dimension_digest CHAR(64),
    p_unit_code VARCHAR,
    p_currency_code CHAR(3))
RETURNS TABLE(observation_count BIGINT,total_value NUMERIC)
LANGUAGE plpgsql STABLE PARALLEL SAFE SECURITY INVOKER AS $$
DECLARE
  header fin_fact.fact_set%ROWTYPE;
  n BIGINT;
  total NUMERIC;
  inconsistent BIGINT;
BEGIN
  IF p_organization_id IS NULL OR p_factset_id IS NULL
      OR NULLIF(trim(p_form_code),'') IS NULL
      OR NULLIF(trim(p_metric_code),'') IS NULL
      OR p_period_start IS NULL OR p_period_end_exclusive IS NULL
      OR p_period_start>=p_period_end_exclusive
      OR p_dimension_digest IS NULL OR length(trim(p_dimension_digest))<>64
      OR NULLIF(trim(p_unit_code),'') IS NULL
      OR p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$'
  THEN
    RAISE EXCEPTION 'CALC166_AGG_ARGUMENT_INVALID' USING ERRCODE='22023';
  END IF;

  SELECT * INTO header FROM fin_fact.fact_set
  WHERE id=p_factset_id AND organization_id=p_organization_id
    AND qualification_mode='TEST_QUALIFIED';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'CALC166_AGG_FACTSET_NOT_FOUND' USING ERRCODE='22023';
  END IF;
  IF header.target_period_start<>p_period_start
     OR header.target_period_end_exclusive<>p_period_end_exclusive
     OR header.currency_code<>p_currency_code THEN
    RAISE EXCEPTION 'CALC166_AGG_SCOPE_MISMATCH' USING ERRCODE='22023';
  END IF;

  -- Unlike a group-by of a mixed stream, this fails if any observation in
  -- the requested exact metric/dimension violates the pinned aggregation
  -- contract. Missing does not mean zero.
  SELECT count(*),
         count(*) FILTER (WHERE o.period_semantics<>'INTERVAL'
           OR o.period_start<>p_period_start
           OR o.period_end_exclusive<>p_period_end_exclusive
           OR o.unit_code<>p_unit_code
           OR o.currency_code IS DISTINCT FROM p_currency_code
           OR o.value_kind<>'NUMERIC' OR o.value_state<>'PRESENT')
       INTO n,inconsistent
  FROM fin_fact.fact_observation o
  WHERE o.factset_id=p_factset_id
    AND o.source_form_code=p_form_code
    AND o.metric_code=p_metric_code
    AND o.dimension_digest=p_dimension_digest;
  IF n=0 THEN
    RAISE EXCEPTION 'CALC166_AGG_SOURCE_MISSING' USING ERRCODE='22023';
  END IF;
  IF inconsistent<>0 THEN
    RAISE EXCEPTION 'CALC166_AGG_MIXED_GRAIN_OR_UNIT' USING ERRCODE='22023';
  END IF;

  SELECT count(*),sum(o.numeric_value) INTO observation_count,total_value
  FROM fin_fact.fact_observation o
  WHERE o.factset_id=p_factset_id
    AND o.source_form_code=p_form_code
    AND o.metric_code=p_metric_code
    AND o.dimension_digest=p_dimension_digest;
  RETURN NEXT;
END;
$$;
