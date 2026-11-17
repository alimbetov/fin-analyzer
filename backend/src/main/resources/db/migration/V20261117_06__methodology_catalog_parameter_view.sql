-- FIN HTAP MR-R1: complete, one-row-per-family catalogue with 259
-- nested PROPOSED/UNREVIEWED parameter names. The JSON array is descriptive,
-- not an executable method config and not an alternative to moderated
-- fin_calc.method_config_version(parameters JSONB).
-- Appending the column preserves all existing view columns and their order.
CREATE OR REPLACE VIEW fin_calc.methodology_catalog_view AS
SELECT f.method_code, f.method_group, f.title,
       COALESCE(e.implementation_status,f.implementation_status)::VARCHAR(80)
         AS implementation_status,
       f.catalog_version, f.release_qualified, f.auto_run_allowed,
       COALESCE(cfg.total_config_versions,0) AS total_config_versions,
       COALESCE(a.active_variant_count,0) AS active_variant_count,
       COALESCE(a.active_variants,'[]'::jsonb) AS active_variants,
       FALSE AS method_execution_permitted,
       COALESCE(last_config.latest_variants,'[]'::jsonb) AS latest_variant_configs,
       e.sequence_no AS implementation_evidence_sequence,
       COALESCE(p.parameter_candidates,'[]'::jsonb) AS parameter_candidates
FROM fin_calc.method_family f
LEFT JOIN LATERAL (
  SELECT ev.implementation_status,ev.sequence_no
  FROM fin_calc.method_implementation_evidence ev
  WHERE ev.method_code=f.method_code
  ORDER BY ev.sequence_no DESC LIMIT 1
) e ON TRUE
LEFT JOIN LATERAL (
  SELECT count(*) AS total_config_versions
  FROM fin_calc.method_config_version v WHERE v.method_code=f.method_code
) cfg ON TRUE
LEFT JOIN LATERAL (
  SELECT count(*) AS active_variant_count,
         jsonb_agg(jsonb_build_object(
           'variantCode',ac.variant_code,'configId',v.config_id,
           'versionNo',v.version_no,'algorithmVersion',v.algorithm_version,
           'parameters',v.parameters,'parametersSha256',v.params_sha256,
           'effectiveSha256',v.effective_sha256,
           'pointerVersion',ac.pointer_version)
           ORDER BY ac.variant_code) AS active_variants
  FROM fin_calc.method_active_config ac
  JOIN fin_calc.method_config_version v ON v.config_id=ac.config_id
  WHERE ac.method_code=f.method_code
) a ON TRUE
LEFT JOIN LATERAL (
  SELECT jsonb_agg(jsonb_build_object(
     'variantCode',v.variant_code,'configId',v.config_id,
     'versionNo',v.version_no,'effectiveSha256',v.effective_sha256,
     'parameters',v.parameters,
     'moderation',COALESCE(r.decision,'PENDING'))
     ORDER BY v.variant_code) AS latest_variants
  FROM (
    SELECT DISTINCT ON (q.variant_code)
      q.variant_code,q.config_id,q.version_no,q.effective_sha256,q.parameters
    FROM fin_calc.method_config_version q
    WHERE q.method_code=f.method_code
    ORDER BY q.variant_code,q.version_no DESC
  ) v
  LEFT JOIN fin_calc.method_config_review r ON r.config_id=v.config_id
) last_config ON TRUE
LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_build_object(
        'parameterCode', candidate.parameter_code,
        'proposedType', candidate.proposed_type,
        'reviewState', candidate.review_state,
        'sourceRegistry', candidate.source_registry)
        ORDER BY candidate.parameter_code) AS parameter_candidates
    FROM fin_calc.method_parameter_candidate candidate
    WHERE candidate.method_code = f.method_code
) p ON TRUE;
