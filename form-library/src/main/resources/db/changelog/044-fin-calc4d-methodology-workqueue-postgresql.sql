--liquibase formatted sql
--changeset fin-calc:044a dbms:postgresql splitStatements:true
-- Full 154-form AUTHORING WORKQUEUE based on REAL registered field and formula
-- contracts. It never fabricates a financial method or marks output REQUIRED.
-- The view is dynamically refreshed as append-only formula revisions land.
CREATE VIEW fin_calc.calc4d_methodology_workqueue_v1 AS
SELECT
  base.form_code,
  base.form_version_id,
  base.schema_sha256,
  base.content_mode,
  base.direct_numeric_field_count,
  base.reference_only,
  COALESCE(physical.numeric_sources_jsonb,'[]'::jsonb) AS typed_numeric_sources_jsonb,
  COALESCE(candidate.formulas_jsonb,'[]'::jsonb) AS formula_candidates_jsonb,
  base.formula_candidate_count,
  base.latest_review_draft_revision,
  base.latest_review_draft_binding_sha256,
  COALESCE(editorial.output_decisions_jsonb,'[]'::jsonb)
    AS latest_output_decisions_jsonb,
  CASE
    WHEN editorial.id IS NULL THEN 'MANDATORY_OUTPUTS_NOT_AUTHORED'
    ELSE 'MANDATORY_OUTPUTS_DRAFT_UNQUALIFIED'
  END::text AS mandatory_output_work_state,
  'PERIOD_CURRENCY_DIMENSION_ROUNDING_NOT_APPROVED'::text
    AS calculation_policy_state,
  'FINANCIAL_METHOD_S2_NOT_APPROVED'::text
    AS financial_approval_state,
  false AS executable,
  jsonb_build_array(
    'MISSING_FINANCIAL_REQUIRED_OUTPUT_DECISION',
    'CANDIDATE_METHODS_NOT_FINANCIALLY_APPROVED',
    'TIME_DIMENSION_UNIT_CURRENCY_ROUNDING_POLICY_UNREVIEWED',
    'INDEPENDENT_CONTROL_EXAMPLES_REQUIRED',
    'FINANCIAL_S2_NOT_VERIFIED') AS blocker_codes_jsonb
FROM fin_calc.calc3c2_financial_review_readiness_v1 base
LEFT JOIN LATERAL (
  SELECT jsonb_agg(jsonb_build_object(
    'metricCode',m.metric_code,
    'fieldCode',ff.field_code,
    'fieldType',ff.field_type,
    'inputMode',ff.input_mode,
    'timeSemanticsState','UNREVIEWED',
    'aggregationPolicyState','UNREVIEWED'
    ) ORDER BY m.metric_code) AS numeric_sources_jsonb
  FROM fin_lib.lib_form_field ff
  JOIN fin_lib.lib_metric_definition m
    ON m.form_code=base.form_code AND m.source_field_code=ff.field_code
    AND m.metric_kind='SOURCE_FIELD'
  JOIN fin_lib.lib_field_binding_contract b
    ON b.form_code=base.form_code AND b.metric_code=m.metric_code
    AND b.field_code=ff.field_code AND b.source_schema_sha256=base.schema_sha256
    AND b.review_state='CANDIDATE_UNQUALIFIED'
  WHERE ff.form_version_id=base.form_version_id
    AND ff.field_type IN ('MONEY','DECIMAL','INTEGER')
) physical ON true
LEFT JOIN LATERAL (
  SELECT jsonb_agg(jsonb_build_object(
    'formulaRevisionId',c.id::text,
    'outputMetricCode',c.output_metric_code,
    'formulaVersion',c.formula_version,
    'operationCode',c.operation_code,
    'sourceMetricCodes',c.source_metrics_jsonb,
    'definitionSha256',c.definition_sha256,
    'bindingSha256',c.binding_sha256,
    'reviewState',c.review_state,
    'financialApprovalState',c.financial_approval_state
    ) ORDER BY c.output_metric_code,c.formula_version)
      AS formulas_jsonb
  FROM fin_calc.formula_contract_revision c
  WHERE c.form_version_id=base.form_version_id
    AND c.source_schema_sha256=base.schema_sha256
    AND c.financial_approval_state='NOT_S2_APPROVED'
) candidate ON true
LEFT JOIN LATERAL (
  SELECT d.id,d.output_decisions_jsonb
  FROM fin_calc.required_output_review_draft_revision d
  WHERE d.form_version_id=base.form_version_id
    AND d.source_schema_sha256=base.schema_sha256
    AND d.finance_approval='NOT_S2_APPROVED'
  ORDER BY d.revision_number DESC LIMIT 1
) editorial ON true;
