--liquibase formatted sql
--changeset fin-calc:047a dbms:postgresql splitStatements:true
-- A truthful per-form methodology completion ledger: input field metadata,
-- human-draft required/optional outputs, formula revisions, synthetic math
-- controls and unbound derived metrics. No inferred finance approval.
CREATE VIEW fin_calc.calc154_methodology_completion_v1 AS
SELECT
  w.form_code,w.form_version_id,w.schema_sha256,w.content_mode,
  w.direct_numeric_field_count,w.reference_only,
  COALESCE(fields.required_numeric_input_fields,0) AS required_numeric_input_fields,
  COALESCE(fields.optional_numeric_input_fields,0) AS optional_numeric_input_fields,
  COALESCE(fields.manual_numeric_input_fields,0) AS manual_numeric_input_fields,
  COALESCE(fields.ui_derived_numeric_fields,0) AS ui_derived_numeric_fields,
  w.formula_candidate_count,
  COALESCE(controls.mathematical_control_cases,0) AS mathematical_control_cases,
  COALESCE(controls.formulas_without_mathematical_control,0)
    AS formula_candidates_missing_math_control,
  COALESCE(controls.zero_division_negative_cases,0) AS zero_division_negative_cases,
  COALESCE(controls.f02_legacy_synthetic_examples,0)
    AS legacy_f02_control_examples,
  COALESCE(unbound.unbound_derived_metrics,0) AS unbound_derived_metrics,
  w.latest_review_draft_revision,w.latest_review_draft_binding_sha256,
  w.latest_output_decisions_jsonb,
  COALESCE(editorial.proposed_required_outputs,0) AS proposed_required_outputs,
  COALESCE(editorial.proposed_optional_outputs,0) AS proposed_optional_outputs,
  COALESCE(editorial.proposed_derived_outputs,0) AS proposed_derived_outputs,
  CASE
    WHEN w.latest_review_draft_revision IS NULL
      THEN 'REQUIREDNESS_REVIEW_NOT_STARTED'
    ELSE 'REQUIREDNESS_DRAFT_UNQUALIFIED'
  END::text AS requiredness_status,
  CASE
    WHEN w.formula_candidate_count=0
      THEN 'DERIVED_CALCULATION_SCOPE_UNDECIDED'
    WHEN COALESCE(controls.formulas_without_mathematical_control,0)>0
      THEN 'MATHEMATICAL_CONTROL_EXAMPLES_MISSING'
    ELSE 'SYNTHETIC_MATH_CHECKS_PRESENT_FINANCIAL_REVIEW_REQUIRED'
  END::text AS candidate_methodology_status,
  'PERIOD_CURRENCY_DIMENSION_ROUNDING_NOT_REVIEWED'::text
    AS policy_review_state,
  'INDEPENDENT_FINANCIAL_CONTROL_EXAMPLES_NOT_APPROVED'::text
    AS independent_control_state,
  'NOT_S2_APPROVED'::text AS financial_approval_state,
  false AS qualified_for_production,
  jsonb_build_array(
    'REQUIRED_AND_OPTIONAL_OUTPUT_DECISIONS_NEED_FINANCIAL_REVIEW',
    'FINANCIAL_FORMULA_SEMANTICS_AND_DEPENDENCIES_UNREVIEWED',
    'PERIOD_CURRENCY_UNIT_DIMENSIONS_AND_ROUNDING_UNAPPROVED',
    'INDEPENDENT_FINANCIAL_CONTROL_VECTORS_REQUIRED',
    'FINANCIAL_S2_SIGNATURE_AND_ADMISSION_MISSING'
  ) AS blocker_codes_jsonb
FROM fin_calc.calc4d_methodology_workqueue_v1 w
LEFT JOIN LATERAL (
  SELECT
    count(*) FILTER(WHERE ff.required)::integer AS required_numeric_input_fields,
    count(*) FILTER(WHERE NOT ff.required)::integer AS optional_numeric_input_fields,
    count(*) FILTER(WHERE ff.input_mode='MANUAL')::integer AS manual_numeric_input_fields,
    count(*) FILTER(WHERE ff.input_mode='DERIVED')::integer AS ui_derived_numeric_fields
  FROM fin_lib.lib_form_field ff
  WHERE ff.form_version_id=w.form_version_id
    AND ff.field_type IN ('MONEY','DECIMAL','INTEGER')
) fields ON true
LEFT JOIN LATERAL (
  SELECT count(*) FILTER(WHERE decision.value->>'requiredness'='REQUIRED')::integer
      AS proposed_required_outputs,
    count(*) FILTER(WHERE decision.value->>'requiredness'='OPTIONAL')::integer
      AS proposed_optional_outputs,
    count(*) FILTER(WHERE decision.value->>'sourceKind'='DERIVED')::integer
      AS proposed_derived_outputs
  FROM jsonb_array_elements(w.latest_output_decisions_jsonb) decision(value)
) editorial ON true
LEFT JOIN LATERAL (
  SELECT
    count(e.id)::integer AS mathematical_control_cases,
    count(DISTINCT c.id) FILTER(
      WHERE NOT EXISTS(
        SELECT 1 FROM fin_calc.calc154_synthetic_control_case x
        WHERE x.formula_revision_id=c.id
      ))::integer AS formulas_without_mathematical_control,
    count(e.id) FILTER(
      WHERE e.expected_state='INVALID_ZERO_DENOMINATOR')::integer
      AS zero_division_negative_cases,
    (SELECT count(*)::integer
       FROM fin_calc.financial_control_example_revision old
       JOIN fin_calc.formula_contract_revision f ON f.id=old.formula_revision_id
       WHERE f.form_version_id=w.form_version_id
         AND old.financial_approval_state='NOT_S2_APPROVED')
       AS f02_legacy_synthetic_examples
  FROM fin_calc.formula_contract_revision c
  LEFT JOIN fin_calc.calc154_synthetic_control_case e
    ON e.formula_revision_id=c.id
  WHERE c.form_version_id=w.form_version_id
    AND c.source_schema_sha256=w.schema_sha256
    AND c.financial_approval_state='NOT_S2_APPROVED'
) controls ON true
LEFT JOIN LATERAL (
  SELECT count(*)::integer AS unbound_derived_metrics
  FROM fin_lib.lib_metric_binding_exception e
  JOIN fin_lib.lib_metric_definition m ON m.metric_code=e.metric_code
  WHERE m.form_code=w.form_code
) unbound ON true;
