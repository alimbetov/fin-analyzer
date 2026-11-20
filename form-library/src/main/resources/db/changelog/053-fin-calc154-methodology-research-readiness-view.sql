--liquibase formatted sql
--changeset fin-calc:053a dbms:postgresql splitStatements:true
-- Make user research candidates visible to the existing 154-form authoring workqueue.
-- This view neither reclassifies a draft formula as S2 nor allows execution.
CREATE VIEW fin_calc.calc154_methodology_research_readiness_v1 AS
SELECT
 w.form_code,
 w.form_version_id,
 w.schema_sha256,
 w.content_mode,
 w.direct_numeric_field_count,
 w.formula_candidate_count AS previously_authored_formula_count,
 coalesce(r.research_candidate_count,0) AS source_research_candidate_count,
 coalesce(r.research_candidates_jsonb,'[]'::jsonb) AS research_candidates_jsonb,
 CASE WHEN coalesce(r.research_candidate_count,0)>0
      THEN 'SOURCE_RESEARCH_PENDING_POLICY_AND_INPUTS'
      ELSE 'SOURCE_RESEARCH_NOT_AUTHORED' END::text AS research_state,
 'INDEPENDENT_FINANCIAL_S2_NOT_APPROVED'::text AS approval_state,
 false AS executable
FROM fin_calc.calc4d_methodology_workqueue_v1 w
LEFT JOIN LATERAL (
 SELECT count(*)::integer AS research_candidate_count,
        jsonb_agg(jsonb_build_object(
          'candidateCode',c.candidate_code,
          'operationFamily',c.operation_family,
          'formulaText',c.formula_text,
          'reviewStatus',c.review_status,
          'blockers',c.blockers_text,
          'sourceSha256',c.uploaded_source_sha256)
          ORDER BY c.candidate_code) AS research_candidates_jsonb
 FROM fin_calc.methodology_research_candidate c
 WHERE c.owner_form_code=w.form_code
) r ON true;
