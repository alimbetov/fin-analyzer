--liquibase formatted sql
--changeset fin-calc:033a dbms:postgresql splitStatements:false
-- CALC166 GROUP BY candidate: read-only exact pinned FactSet, not a financial approval.
-- Grouping is by the persisted dimension digest AND its exact JSON representation.
-- Reject all mixed units, periods, or semantics for the requested metric.
CREATE FUNCTION fin_calc.candidate_interval_group_by(
 p_organization_id uuid,p_factset_id uuid,p_form_code varchar,p_metric_code varchar,
 p_period_start date,p_period_end_exclusive date,p_unit_code varchar,p_currency_code char(3))
RETURNS TABLE(dimension_digest char(64),dimensions_jsonb jsonb,observation_count bigint,total_value numeric)
LANGUAGE plpgsql STABLE PARALLEL SAFE SECURITY INVOKER AS $$
DECLARE h fin_fact.fact_set%ROWTYPE; n bigint; invalid_count bigint; inconsistent_dimensions bigint;
BEGIN
 IF p_organization_id IS NULL OR p_factset_id IS NULL
    OR NULLIF(btrim(p_form_code),'') IS NULL OR NULLIF(btrim(p_metric_code),'') IS NULL
    OR p_period_start IS NULL OR p_period_end_exclusive IS NULL
    OR p_period_start>=p_period_end_exclusive OR NULLIF(btrim(p_unit_code),'') IS NULL
    OR p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$'
 THEN RAISE EXCEPTION 'CALC166_GROUP_ARGUMENT_INVALID' USING ERRCODE='22023'; END IF;
 SELECT * INTO h FROM fin_fact.fact_set
   WHERE id=p_factset_id AND organization_id=p_organization_id
     AND qualification_mode='TEST_QUALIFIED';
 IF NOT FOUND THEN RAISE EXCEPTION 'CALC166_GROUP_FACTSET_NOT_FOUND' USING ERRCODE='22023'; END IF;
 IF h.target_period_start<>p_period_start OR h.target_period_end_exclusive<>p_period_end_exclusive
    OR h.currency_code<>p_currency_code
 THEN RAISE EXCEPTION 'CALC166_GROUP_SCOPE_MISMATCH' USING ERRCODE='22023'; END IF;
 SELECT count(*),
   count(*) FILTER (WHERE o.period_semantics<>'INTERVAL'
    OR o.period_start<>p_period_start OR o.period_end_exclusive<>p_period_end_exclusive
    OR o.unit_code<>p_unit_code OR o.currency_code IS DISTINCT FROM p_currency_code
    OR o.value_kind<>'NUMERIC' OR o.value_state<>'PRESENT')
 INTO n,invalid_count FROM fin_fact.fact_observation o
 WHERE o.factset_id=p_factset_id AND o.source_form_code=p_form_code
   AND o.metric_code=p_metric_code;
 IF n=0 THEN RAISE EXCEPTION 'CALC166_GROUP_SOURCE_MISSING' USING ERRCODE='22023'; END IF;
 IF invalid_count<>0 THEN
   RAISE EXCEPTION 'CALC166_GROUP_MIXED_GRAIN_OR_UNIT' USING ERRCODE='22023'; END IF;
 SELECT count(*) INTO inconsistent_dimensions FROM (
   SELECT o.dimension_digest FROM fin_fact.fact_observation o
   WHERE o.factset_id=p_factset_id AND o.source_form_code=p_form_code
     AND o.metric_code=p_metric_code
   GROUP BY o.dimension_digest HAVING count(DISTINCT o.dimensions_jsonb)>1
 ) conflicts;
 IF inconsistent_dimensions<>0 THEN
   RAISE EXCEPTION 'CALC166_GROUP_DIMENSION_DIGEST_COLLISION' USING ERRCODE='22023'; END IF;
 RETURN QUERY
 SELECT o.dimension_digest,o.dimensions_jsonb,count(*)::bigint,sum(o.numeric_value)
 FROM fin_fact.fact_observation o
 WHERE o.factset_id=p_factset_id AND o.source_form_code=p_form_code
   AND o.metric_code=p_metric_code
 GROUP BY o.dimension_digest,o.dimensions_jsonb
 ORDER BY o.dimension_digest,o.dimensions_jsonb::text;
END;
$$;

--changeset fin-calc:033b dbms:postgresql splitStatements:false
-- Ratio of two exact sums on the same FactSet/period/dimension. No avg-of-ratios.
-- Unqualified candidate; denominator zero is invalid, never silently NULL or 0.
CREATE FUNCTION fin_calc.candidate_ratio_of_sums(
 p_organization_id uuid,p_factset_id uuid,p_form_code varchar,
 p_numerator_metric varchar,p_denominator_metric varchar,
 p_period_start date,p_period_end_exclusive date,
 p_dimension_digest char(64),p_unit_code varchar,p_currency_code char(3))
RETURNS TABLE(numerator_sum numeric,denominator_sum numeric,ratio numeric)
LANGUAGE plpgsql STABLE PARALLEL SAFE SECURITY INVOKER AS $$
DECLARE a record; b record;
BEGIN
 IF p_numerator_metric IS NULL OR p_denominator_metric IS NULL
    OR p_numerator_metric=p_denominator_metric
 THEN RAISE EXCEPTION 'CALC166_RATIO_DISTINCT_METRICS_REQUIRED' USING ERRCODE='22023'; END IF;
 SELECT * INTO a FROM fin_calc.candidate_interval_sum(p_organization_id,p_factset_id,
   p_form_code,p_numerator_metric,p_period_start,p_period_end_exclusive,
   p_dimension_digest,p_unit_code,p_currency_code);
 SELECT * INTO b FROM fin_calc.candidate_interval_sum(p_organization_id,p_factset_id,
   p_form_code,p_denominator_metric,p_period_start,p_period_end_exclusive,
   p_dimension_digest,p_unit_code,p_currency_code);
 IF a.observation_count IS NULL OR b.observation_count IS NULL
 THEN RAISE EXCEPTION 'CALC166_RATIO_SOURCE_MISSING' USING ERRCODE='22023'; END IF;
 IF b.total_value=0 THEN
   RAISE EXCEPTION 'CALC166_RATIO_ZERO_DENOMINATOR' USING ERRCODE='22012'; END IF;
 numerator_sum:=a.total_value;
 denominator_sum:=b.total_value;
 ratio:=a.total_value/b.total_value;
 RETURN NEXT;
END;
$$;
