-- FIN-R1 PostgreSQL 16 READ-ONLY contract verification.
-- Run against the actual FIN database. No writes, DDL or approvals.
-- Source CSV audits are static; this script verifies live fin_lib state.

-- A. Summary and R16.B policy population.
SELECT 'current_forms' AS indicator, count(*) AS count_value
  FROM fin_lib.lib_form_definition
UNION ALL SELECT 'current_physical_fields', count(*)
  FROM fin_lib.lib_form_field ff
  JOIN fin_lib.lib_form_definition f ON f.current_version_id=ff.form_version_id
UNION ALL SELECT 'binding_contracts', count(*) FROM fin_lib.lib_field_binding_contract
UNION ALL SELECT 'all_metric_definitions', count(*) FROM fin_lib.lib_metric_definition
UNION ALL SELECT 'analytical_metric_outputs', count(*) FROM fin_lib.lib_metric_definition
  WHERE metric_kind='ANALYTICAL_OUTPUT'
UNION ALL SELECT 'form_version_contract_policies', count(*)
  FROM fin_lib.lib_form_contract_policy
UNION ALL SELECT 'field_type_policies', count(*)
  FROM fin_lib.lib_form_field_policy
UNION ALL SELECT 'approved_current_versions', count(*)
  FROM fin_lib.lib_form_version v
  JOIN fin_lib.lib_form_definition f ON f.current_version_id=v.id
  WHERE v.qualification_state IN ('APPROVED','S2_APPROVED')
ORDER BY indicator;

-- B. Every form, its version and counts of physical/metric/policy gaps.
SELECT f.form_code, f.catalog, f.legacy_capture_mode,
       v.id AS exact_form_version_id, v.version_label, v.content_mode,
       v.grain, v.qualification_state, v.schema_sha256,
       count(DISTINCT ff.field_code) AS physical_field_count,
       count(DISTINCT CASE WHEN b.field_code IS NOT NULL THEN ff.field_code END)
         AS structural_binding_count,
       count(DISTINCT CASE WHEN b.semantic_role='SOURCE_FIELD' THEN ff.field_code END)
         AS bound_source_metric_count,
       count(DISTINCT fp.field_code) AS r16b_field_policy_count,
       count(DISTINCT mp.field_code) AS versioned_metric_policy_count,
       count(DISTINCT pt.period_type) AS allowed_period_policy_count,
       max(CASE WHEN cp.form_version_id IS NOT NULL THEN 1 ELSE 0 END)
         AS has_version_contract_policy
FROM fin_lib.lib_form_definition f
JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
LEFT JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
LEFT JOIN fin_lib.lib_field_binding_contract b
       ON b.form_code=f.form_code AND b.field_code=ff.field_code
       AND b.source_schema_sha256=v.schema_sha256
LEFT JOIN fin_lib.lib_form_field_policy fp
       ON fp.form_version_id=v.id AND fp.field_code=ff.field_code
LEFT JOIN fin_lib.lib_form_contract_metric_binding mp
       ON mp.form_version_id=v.id AND mp.field_code=ff.field_code
LEFT JOIN fin_lib.lib_form_allowed_period pt ON pt.form_version_id=v.id
LEFT JOIN fin_lib.lib_form_contract_policy cp ON cp.form_version_id=v.id
GROUP BY f.form_code,f.catalog,f.legacy_capture_mode,
         v.id,v.version_label,v.content_mode,v.grain,
         v.qualification_state,v.schema_sha256
ORDER BY f.form_code;

-- C. Exactly one row per CURRENT physical field (unless duplicate translations
-- are present, which are prevented by the translation unique constraint).
SELECT f.form_code, v.version_label, v.id AS form_version_id,
       v.content_mode, v.qualification_state,
       ff.field_code, ff.field_type, ff.input_mode, ff.required,
       b.semantic_role, b.metric_code, b.unit_policy AS binding_unit_policy,
       b.review_state AS binding_review_state, b.contract_sha256,
       m.metric_kind, m.qualification_state AS metric_qualification,
       fp.numeric_precision,fp.numeric_scale,fp.rounding_mode,
       fp.missing_value_policy,fp.min_occurs,fp.max_occurs,
       fp.semantic_definition,fp.aggregation_policy,
       mp.metric_code AS versioned_metric_code,
       mp.metric_contract_version,mp.transformation_policy,
       tr_ru.title AS title_ru,tr_ru.review_status AS ru_review,
       tr_kk.title AS title_kk,tr_kk.review_status AS kk_review,
       tr_en.title AS title_en,tr_en.review_status AS en_review,
       tr_zh.title AS title_zh,tr_zh.review_status AS zh_review
FROM fin_lib.lib_form_definition f
JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
LEFT JOIN fin_lib.lib_field_binding_contract b
       ON b.form_code=f.form_code AND b.field_code=ff.field_code
       AND b.source_schema_sha256=v.schema_sha256
LEFT JOIN fin_lib.lib_metric_definition m ON m.metric_code=b.metric_code
LEFT JOIN fin_lib.lib_form_field_policy fp
       ON fp.form_version_id=v.id AND fp.field_code=ff.field_code
LEFT JOIN fin_lib.lib_form_contract_metric_binding mp
       ON mp.form_version_id=v.id AND mp.field_code=ff.field_code
LEFT JOIN fin_lib.lib_form_field_translation tr_ru
       ON tr_ru.form_version_id=v.id AND tr_ru.subject_type='FIELD'
       AND tr_ru.subject_code=ff.field_code AND tr_ru.locale='ru'
LEFT JOIN fin_lib.lib_form_field_translation tr_kk
       ON tr_kk.form_version_id=v.id AND tr_kk.subject_type='FIELD'
       AND tr_kk.subject_code=ff.field_code AND tr_kk.locale='kk'
LEFT JOIN fin_lib.lib_form_field_translation tr_en
       ON tr_en.form_version_id=v.id AND tr_en.subject_type='FIELD'
       AND tr_en.subject_code=ff.field_code AND tr_en.locale='en'
LEFT JOIN fin_lib.lib_form_field_translation tr_zh
       ON tr_zh.form_version_id=v.id AND tr_zh.subject_type='FIELD'
       AND tr_zh.subject_code=ff.field_code AND tr_zh.locale='zh-Hans'
ORDER BY f.form_code,ff.display_order,ff.field_code;

-- D. All 840 source and analytical metric catalog identities.
SELECT metric_code,metric_kind,form_code,source_field_code,unit_policy,
       qualification_state
FROM fin_lib.lib_metric_definition
ORDER BY metric_kind,form_code,metric_code;
