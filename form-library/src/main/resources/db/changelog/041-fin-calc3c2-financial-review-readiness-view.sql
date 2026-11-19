--liquibase formatted sql
--changeset fin-calc:041a dbms:postgresql splitStatements:true
-- CALC-3C.2: read-only completeness/review status across all 154 data forms.
-- This deliberately does not mark any form complete on draft presence.
-- Independent Keycloak/IAM moderator verification and methodology-specific
-- signed S2 receipts are not yet installed: all forms stay FINANCE_HOLD.
CREATE VIEW fin_calc.calc3c2_financial_review_readiness_v1 AS
SELECT
  f.form_code,
  v.id AS form_version_id,
  v.schema_sha256,
  v.content_mode,
  c.direct_numeric_field_count,
  c.has_direct_numeric_source,
  c.reference_only,
  COALESCE(req.mandatory_output_state,'REVIEW_REQUIRED') AS mandatory_state,
  COALESCE(formulas.known_formula_count,0) AS formula_candidate_count,
  COALESCE(examples.synthetic_example_count,0) AS synthetic_control_example_count,
  latest.revision_number AS latest_review_draft_revision,
  latest.binding_sha256 AS latest_review_draft_binding_sha256,
  'NOT_S2_APPROVED'::varchar(32) AS financial_approval_state,
  false AS calc_plan_execution_admitted,
  'FINANCIAL_METHODOLOGY_S2_AND_REQUIREDNESS_HOLD'::text AS blocker
FROM fin_lib.lib_form_definition f
JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
JOIN fin_calc.form_numeric_source_capability_revision c
  ON c.form_version_id=v.id AND c.source_schema_sha256=v.schema_sha256
  AND c.form_code=f.form_code
  AND c.capability_version='CALC3C1_NUMERIC_SOURCE_V1'
LEFT JOIN fin_calc.form_calculation_requirement_revision req
  ON req.form_version_id=v.id
  AND req.requirement_version='CALC3B_REVIEW_V1'
LEFT JOIN LATERAL (
  SELECT count(*)::integer AS known_formula_count
  FROM fin_calc.formula_contract_revision r
  WHERE r.form_version_id=v.id AND r.source_schema_sha256=v.schema_sha256
    AND r.financial_approval_state='NOT_S2_APPROVED'
) formulas ON true
LEFT JOIN LATERAL (
  SELECT count(*)::integer AS synthetic_example_count
  FROM fin_calc.financial_control_example_revision e
  JOIN fin_calc.formula_contract_revision r ON r.id=e.formula_revision_id
  WHERE r.form_version_id=v.id AND e.reference_type='SYNTHETIC_UAT'
    AND e.financial_approval_state='NOT_S2_APPROVED'
) examples ON true
LEFT JOIN LATERAL (
  SELECT d.revision_number,d.binding_sha256
  FROM fin_calc.required_output_review_draft_revision d
  WHERE d.form_version_id=v.id AND d.source_schema_sha256=v.schema_sha256
    AND d.finance_approval='NOT_S2_APPROVED'
  ORDER BY d.revision_number DESC LIMIT 1
) latest ON true
WHERE v.content_mode IN ('ROWSET','SINGLE_RECORD');
