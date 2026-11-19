-- FIN CALC-154 / CALC-2: read-only PostgreSQL 16 live-DB coverage audit.
-- Invoke AFTER startup bootstrap (CatalogBootstrap + FieldMetricContractBootstrap
-- + Calc154CandidateOperationBootstrap). This is NOT financial S2.
-- psql -v ON_ERROR_STOP=1 -f scripts/fin-calc154-postgresql-live-audit.sql
BEGIN TRANSACTION READ ONLY ISOLATION LEVEL REPEATABLE READ;

DO $audit$
DECLARE
  forms integer; data_forms integer; evidence integer;
  physical integer; source_fields integer; candidates integer;
BEGIN
  SELECT count(*),count(*) FILTER (WHERE v.content_mode<>'EVIDENCE'),
         count(*) FILTER (WHERE v.content_mode='EVIDENCE')
  INTO forms,data_forms,evidence
  FROM fin_lib.lib_form_definition f
  JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id;
  IF (forms,data_forms,evidence) IS DISTINCT FROM (166,154,12) THEN
    RAISE EXCEPTION 'CALC154_LIVE_FORM_COVERAGE_DRIFT %/%/%',forms,data_forms,evidence;
  END IF;

  SELECT count(*), count(*) FILTER (WHERE c.semantic_role='SOURCE_FIELD')
  INTO physical,source_fields
  FROM fin_lib.lib_field_binding_contract c;
  IF physical<>877 OR source_fields<>793 THEN
    RAISE EXCEPTION 'CALC154_LIVE_FIELD_CONTRACT_DRIFT %/%',physical,source_fields;
  END IF;

  SELECT count(*) INTO candidates
  FROM fin_calc.candidate_metric_operation_binding
  WHERE review_state='CANDIDATE_UNQUALIFIED';
  IF candidates<>5 THEN
    RAISE EXCEPTION 'CALC154_LIVE_OPERATION_CANDIDATES_DRIFT: %',candidates;
  END IF;

  IF EXISTS (
    SELECT 1
    FROM fin_calc.candidate_metric_operation_binding a
    LEFT JOIN fin_lib.lib_metric_definition n
      ON n.metric_code=a.numerator_metric_code AND n.form_code=a.form_code
      AND n.metric_kind='SOURCE_FIELD'
    LEFT JOIN fin_lib.lib_metric_definition d
      ON d.metric_code=a.denominator_metric_code AND d.form_code=a.form_code
      AND d.metric_kind='SOURCE_FIELD'
    WHERE n.metric_code IS NULL
      OR (a.denominator_metric_code IS NOT NULL AND d.metric_code IS NULL)
  ) THEN
    RAISE EXCEPTION 'CALC154_LIVE_OPERATION_BINDING_INVALID';
  END IF;
END;
$audit$;

-- Exactly one row for each of the 154 current data forms.
SELECT
  f.form_code AS "formCode",
  v.content_mode AS "contentMode",
  v.version_label AS "formVersion",
  v.qualification_state AS "formQualification",
  v.schema_sha256 AS "sourceSchemaSha256",
  (
    SELECT count(*) FROM fin_lib.lib_field_binding_contract c
    WHERE c.form_code=f.form_code AND c.semantic_role='SOURCE_FIELD'
  ) AS "physicalSourceFields",
  (
    SELECT count(*) FROM fin_calc.candidate_metric_operation_binding c
    WHERE c.form_code=f.form_code
  ) AS "candidateOperations",
  0::integer AS "financiallyQualifiedOutputs",
  'BLOCKED_FINANCIAL_CONTRACT_AND_E2E'::text AS "status"
FROM fin_lib.lib_form_definition f
JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
WHERE v.content_mode IN ('ROWSET','SINGLE_RECORD')
ORDER BY f.form_code;

COMMIT;
