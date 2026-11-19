--liquibase formatted sql
--changeset fin-calc:032a dbms:postgresql splitStatements:false
-- CALC-166: AS_OF stock selection, NEVER sum stock balances across dates.
-- Read-only UAT candidate for an EXACT pinned TEST_QUALIFIED FactSet.
-- Caller must have an independently approved methodology for using this operator.
CREATE FUNCTION fin_calc.candidate_last_asof(
    p_organization_id UUID,
    p_factset_id UUID,
    p_form_code VARCHAR,
    p_metric_code VARCHAR,
    p_asof DATE,
    p_dimension_digest CHAR(64),
    p_unit_code VARCHAR,
    p_currency_code CHAR(3))
RETURNS TABLE(observation_date DATE,observation_id UUID,numeric_value NUMERIC)
LANGUAGE plpgsql STABLE PARALLEL SAFE SECURITY INVOKER AS $$
DECLARE
  header fin_fact.fact_set%ROWTYPE;
  violations BIGINT;
  latest DATE;
  candidates BIGINT;
BEGIN
  IF p_organization_id IS NULL OR p_factset_id IS NULL
      OR NULLIF(trim(p_form_code),'') IS NULL
      OR NULLIF(trim(p_metric_code),'') IS NULL
      OR p_asof IS NULL
      OR p_dimension_digest IS NULL OR p_dimension_digest !~ '^[0-9a-f]{64}$'
      OR NULLIF(trim(p_unit_code),'') IS NULL
      OR p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$'
  THEN
    RAISE EXCEPTION 'CALC166_ASOF_ARGUMENT_INVALID' USING ERRCODE='22023';
  END IF;

  SELECT * INTO header FROM fin_fact.fact_set
    WHERE id=p_factset_id AND organization_id=p_organization_id
      AND qualification_mode='TEST_QUALIFIED';
  IF NOT FOUND THEN
    RAISE EXCEPTION 'CALC166_ASOF_FACTSET_NOT_FOUND' USING ERRCODE='22023';
  END IF;
  IF p_asof<header.target_period_start
      OR p_asof>=header.target_period_end_exclusive
      OR p_currency_code<>header.currency_code THEN
    RAISE EXCEPTION 'CALC166_ASOF_SCOPE_MISMATCH' USING ERRCODE='22023';
  END IF;

  -- For this metric and dimension, even a different row outside the requested
  -- AS_OF date may not silently introduce a mixed aggregation contract.
  SELECT count(*) FILTER (WHERE o.period_semantics<>'AS_OF'
             OR o.period_start<header.target_period_start
             OR o.period_start>=header.target_period_end_exclusive
             OR o.unit_code<>p_unit_code
             OR o.currency_code IS DISTINCT FROM p_currency_code
             OR o.value_kind<>'NUMERIC' OR o.value_state<>'PRESENT'),
         max(o.period_start) FILTER (WHERE o.period_start<=p_asof)
    INTO violations,latest
  FROM fin_fact.fact_observation o
  WHERE o.factset_id=p_factset_id
    AND o.source_form_code=p_form_code
    AND o.metric_code=p_metric_code
    AND o.dimension_digest=p_dimension_digest;
  IF violations<>0 THEN
    RAISE EXCEPTION 'CALC166_ASOF_MIXED_GRAIN_OR_UNIT' USING ERRCODE='22023';
  END IF;
  IF latest IS NULL THEN
    RAISE EXCEPTION 'CALC166_ASOF_SOURCE_MISSING' USING ERRCODE='22023';
  END IF;

  SELECT count(*) INTO candidates
  FROM fin_fact.fact_observation o
  WHERE o.factset_id=p_factset_id
    AND o.source_form_code=p_form_code
    AND o.metric_code=p_metric_code
    AND o.dimension_digest=p_dimension_digest
    AND o.period_start=latest;
  IF candidates<>1 THEN
    RAISE EXCEPTION 'CALC166_ASOF_AMBIGUOUS_LATEST' USING ERRCODE='22023';
  END IF;
  RETURN QUERY SELECT o.period_start,o.observation_id,o.numeric_value
  FROM fin_fact.fact_observation o
  WHERE o.factset_id=p_factset_id
    AND o.source_form_code=p_form_code
    AND o.metric_code=p_metric_code
    AND o.dimension_digest=p_dimension_digest
    AND o.period_start=latest;
END;
$$;
