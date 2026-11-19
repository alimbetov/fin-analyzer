-- FIN CALC-3B / PostgreSQL 16: read-only qualification backlog proof.
-- Execute after the Spring Boot post-catalog ApplicationRunner bootstraps.
-- psql -v ON_ERROR_STOP=1 -f scripts/fin-calc3b-postgresql-live-audit.sql
-- Does not grant financial S2 or authorize CalcPlan execution.
BEGIN TRANSACTION READ ONLY ISOLATION LEVEL REPEATABLE READ;

DO $audit$
DECLARE
  numeric_forms integer;
  mandatory_review integer;
  typed_total integer;
  typed_physical integer;
  typed_derived integer;
  model_count integer;
  model_inputs integer;
  model_outputs integer;
  evidence_count integer;
  invalid integer;
BEGIN
  SELECT count(*) INTO numeric_forms
  FROM fin_lib.lib_form_definition f
  JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
  WHERE v.content_mode IN ('SINGLE_RECORD','ROWSET');

  SELECT count(*), count(*) FILTER (
    WHERE r.mandatory_output_state='REVIEW_REQUIRED'
      AND r.mandatory_outputs_jsonb IS NULL
      AND r.approval_state='NOT_S2_APPROVED')
  INTO mandatory_review,invalid
  FROM fin_calc.form_calculation_requirement_revision r;
  IF numeric_forms<>154 OR mandatory_review<>154 OR invalid<>154 THEN
    RAISE EXCEPTION 'CALC3B_REQUIREDNESS_COVERAGE_DRIFT: %/%/%',
      numeric_forms,mandatory_review,invalid;
  END IF;

  IF EXISTS(
    SELECT 1 FROM fin_calc.form_calculation_requirement_revision r
    JOIN fin_lib.lib_form_definition f ON f.form_code=r.form_code
    JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
    WHERE r.form_version_id IS DISTINCT FROM v.id
       OR r.source_schema_sha256 IS DISTINCT FROM v.schema_sha256
       OR r.requirement_version<>'CALC3B_REVIEW_V1'
  ) THEN
    RAISE EXCEPTION 'CALC3B_REQUIREDNESS_SOURCE_VERSION_DRIFT';
  END IF;

  SELECT count(*),
     count(*) FILTER (WHERE source_kind='PHYSICAL_NUMERIC_FIELD'),
     count(*) FILTER (WHERE source_kind='FORMULA_CANDIDATE')
  INTO typed_total,typed_physical,typed_derived
  FROM fin_calc.formula_typed_input_binding;
  IF (typed_total,typed_physical,typed_derived) IS DISTINCT FROM (21,19,2) THEN
    RAISE EXCEPTION 'CALC3B_TYPED_INPUT_COVERAGE_DRIFT: %/%/%',
      typed_total,typed_physical,typed_derived;
  END IF;

  IF EXISTS(
    SELECT 1 FROM fin_calc.formula_typed_input_binding i
    JOIN fin_calc.formula_contract_revision c ON c.id=i.formula_revision_id
    JOIN fin_lib.lib_form_version v ON v.id=i.source_form_version_id
    WHERE i.source_form_version_id IS DISTINCT FROM c.form_version_id
       OR i.source_schema_sha256 IS DISTINCT FROM c.source_schema_sha256
       OR v.schema_sha256 IS DISTINCT FROM i.source_schema_sha256
       OR i.requiredness_state<>'REVIEW_REQUIRED'
  ) THEN
    RAISE EXCEPTION 'CALC3B_TYPED_INPUT_VERSION_DRIFT';
  END IF;

  SELECT count(*) INTO model_count FROM fin_calc.model_candidate_revision;
  SELECT count(*),count(*) FILTER (WHERE content_mode='EVIDENCE')
    INTO model_inputs,evidence_count FROM fin_calc.model_candidate_input_form;
  SELECT count(*) INTO model_outputs FROM fin_calc.model_candidate_output_metric;
  IF (model_count,model_inputs,model_outputs,evidence_count)
      IS DISTINCT FROM (42,97,46,1) THEN
    RAISE EXCEPTION 'CALC3B_MODEL_COVERAGE_DRIFT: %/%/%/%',
      model_count,model_inputs,model_outputs,evidence_count;
  END IF;

  IF EXISTS(
    SELECT 1 FROM fin_calc.model_candidate_input_form i
    JOIN fin_lib.lib_form_definition f ON f.form_code=i.source_form_code
    JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
    WHERE i.source_form_version_id IS DISTINCT FROM v.id
       OR i.source_schema_sha256 IS DISTINCT FROM v.schema_sha256
       OR i.content_mode IS DISTINCT FROM v.content_mode
       OR i.numeric_eligible IS DISTINCT FROM
          (v.content_mode IN ('SINGLE_RECORD','ROWSET'))
       OR i.approval_state<>'NOT_S2_APPROVED'
  ) THEN
    RAISE EXCEPTION 'CALC3B_MODEL_INPUT_VERSION_OR_EVIDENCE_DRIFT';
  END IF;

  IF EXISTS(
    SELECT 1 FROM fin_calc.model_candidate_revision m
    WHERE m.formula_state<>'NOT_AUTHORED'
       OR m.requirement_state<>'REVIEW_REQUIRED'
       OR m.financial_approval_state<>'NOT_S2_APPROVED'
  ) OR EXISTS(
    SELECT 1 FROM fin_calc.model_candidate_output_metric o
    WHERE o.formula_state<>'NOT_AUTHORED'
       OR o.requiredness_state<>'REVIEW_REQUIRED'
       OR o.approval_state<>'NOT_S2_APPROVED'
  ) THEN
    RAISE EXCEPTION 'CALC3B_UNAUTHORIZED_FINANCIAL_PROMOTION';
  END IF;
END;
$audit$;

-- One row per current numeric-capable form; UNKNOWN is different from 0.
SELECT
  f.form_code AS "formCode",
  v.content_mode AS "contentMode",
  r.mandatory_output_state AS "mandatoryOutputStatus",
  r.mandatory_outputs_jsonb AS "mandatoryOutputsUnknown",
  r.source_schema_sha256 AS "sourceSchemaSha256",
  r.binding_sha256 AS "requirementBindingSha256",
  (
    SELECT count(*) FROM fin_calc.formula_contract_revision c
    WHERE c.form_version_id=v.id
  ) AS "knownFormulaCandidates",
  (
    SELECT count(*) FROM fin_calc.model_candidate_input_form mi
    WHERE mi.source_form_version_id=v.id
  ) AS "modelDependencyCandidates",
  'NOT_S2_APPROVED'::text AS "financialApproval"
FROM fin_lib.lib_form_definition f
JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
JOIN fin_calc.form_calculation_requirement_revision r
  ON r.form_code=f.form_code AND r.form_version_id=v.id
WHERE v.content_mode IN ('SINGLE_RECORD','ROWSET')
ORDER BY f.form_code;

COMMIT;
