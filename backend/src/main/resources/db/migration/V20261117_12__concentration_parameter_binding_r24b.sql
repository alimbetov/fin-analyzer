-- FIN-MR-R2.4-B: exact reconciliation for supported CON-01/CON-02 semantic keys.
-- Only registry lineage metadata. It never creates/approves executable configurations.
-- The original 259 immutable candidates and old hash profiles are untouched.
CREATE VIEW fin_calc.concentration_parameter_binding_v1 AS
WITH binding(method_code, variant_code, candidate_code, semantic_kind,
             runtime_key, typed_profile, validation_note) AS (
  VALUES
   ('CON-01','PARETO_SALES','populationMetric','CONFIG_TYPED','populationMetric',
    'CON01_PARETO_FIXED_V1','NET_SALES, fixed'),
   ('CON-01','PARETO_SALES','tieSortPolicy','CONFIG_TYPED','tieSortPolicy',
    'CON01_PARETO_FIXED_V1','AMOUNT_DESC_SKU_CODE_ASC, fixed'),
   ('CON-01','PARETO_SALES','returnsPolicy','CONFIG_TYPED','returnsPolicy',
    'CON01_PARETO_FIXED_V1','REVIEWED_NET_RECONCILED, fixed'),
   ('CON-01','PARETO_SALES','reportCutoffPercent','UNSUPPORTED_REPORT_ONLY',NULL,
    'CON01_PARETO_FIXED_V1','not implemented; cannot reduce the Pareto population'),
   ('CON-02','ABC_SALES','aLimitPercent','CONFIG_TYPED','aLimitPercent',
    'CON02_ABC_TYPED_V2','0 < A < B < 100; percent 0..100'),
   ('CON-02','ABC_SALES','bLimitPercent','CONFIG_TYPED','bLimitPercent',
    'CON02_ABC_TYPED_V2','0 < A < B < 100; percent 0..100'),
   ('CON-02','ABC_SALES','boundaryCrossingPolicy','CONFIG_TYPED',
    'boundaryCrossingPolicy','CON02_ABC_TYPED_V2',
    'CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED, fixed'),
   ('CON-02','ABC_SALES','sourceParetoVersion','UPSTREAM_RESULT_LINEAGE',NULL,
    'CON02_ABC_TYPED_V2','trusted CON-01 result receipt; not caller configuration')
)
SELECT b.method_code, b.variant_code, c.candidate_id,
       b.candidate_code, b.semantic_kind, b.runtime_key,
       b.typed_profile, b.validation_note, c.review_state,
       false AS approved_by_binding, false AS executable_by_binding
FROM binding b
JOIN fin_calc.method_parameter_candidate c
  ON c.method_code=b.method_code AND c.parameter_code=b.candidate_code;

COMMENT ON VIEW fin_calc.concentration_parameter_binding_v1 IS
 'R2.4-B versioned, read-only semantic map; 6 typed fields, 1 unsupported, 1 lineage; no approval or permit';

DO $guard$
DECLARE actual_count bigint;
DECLARE typed_count bigint;
DECLARE unsupported_count bigint;
DECLARE lineage_count bigint;
DECLARE p_schema jsonb;
DECLARE a_schema jsonb;
BEGIN
  SELECT count(*),
     count(*) FILTER (WHERE semantic_kind='CONFIG_TYPED'),
     count(*) FILTER (WHERE semantic_kind='UNSUPPORTED_REPORT_ONLY'),
     count(*) FILTER (WHERE semantic_kind='UPSTREAM_RESULT_LINEAGE')
  INTO actual_count,typed_count,unsupported_count,lineage_count
  FROM fin_calc.concentration_parameter_binding_v1;
  IF actual_count<>8 OR typed_count<>6 OR
     unsupported_count<>1 OR lineage_count<>1 THEN
    RAISE EXCEPTION 'FIN_MR_R24B_CONCENTRATION_BINDING_DRIFT';
  END IF;

  p_schema := fin_calc.method_typed_schema_r21('CON01_PARETO_FIXED_V1');
  a_schema := fin_calc.method_typed_schema_r21('CON02_ABC_TYPED_V2');
  IF (SELECT count(*) FROM jsonb_object_keys(p_schema->'configProperties'))<>3
     OR (SELECT count(*) FROM jsonb_object_keys(a_schema->'configProperties'))<>3
     OR EXISTS (
       SELECT 1 FROM fin_calc.concentration_parameter_binding_v1 b
       WHERE b.semantic_kind='CONFIG_TYPED'
         AND NOT (
           CASE WHEN b.method_code='CON-01'
             THEN (p_schema->'configProperties') ? b.runtime_key
             ELSE (a_schema->'configProperties') ? b.runtime_key END
         )
     )
     OR (p_schema->'configProperties') ? 'reportCutoffPercent'
     OR (a_schema->'configProperties') ? 'sourceParetoVersion'
     OR a_schema #>> '{inputLineage,sourceParetoVersion,scope}'
        IS DISTINCT FROM 'TRUSTED_DEPENDENCY_RESULT_NOT_CONFIG'
     OR p_schema #>> '{candidateNotExecutable,reportCutoffPercent,reason}'
        IS DISTINCT FROM 'REPORT_CUTOFF_NOT_IMPLEMENTED'
     OR (SELECT count(*) FROM fin_calc.concentration_parameter_binding_v1
          WHERE review_state='PROPOSED_UNREVIEWED')<>8 THEN
    RAISE EXCEPTION 'FIN_MR_R24B_TYPED_SCHEMA_LINEAGE_DRIFT';
  END IF;
END;
$guard$;
