-- FIN CALC-154 / CALC-2: read-only PostgreSQL 16 live-DB coverage audit.
-- Invoke AFTER startup bootstrap (CatalogBootstrap + FieldMetricContractBootstrap
-- + Calc154CandidateOperationBootstrap + Calc154FormulaContractBootstrap). This is NOT financial S2.
-- psql -v ON_ERROR_STOP=1 -f scripts/fin-calc154-postgresql-live-audit.sql
BEGIN TRANSACTION READ ONLY ISOLATION LEVEL REPEATABLE READ;

DO $audit$
DECLARE
  forms integer; data_forms integer; evidence integer;
  physical integer; source_fields integer; candidates integer;
  formulas integer; uat_only integer;
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

  SELECT count(*),count(*) FILTER (WHERE review_state='UAT_TEST_ONLY')
  INTO formulas,uat_only
  FROM fin_calc.formula_contract_revision;
  IF formulas<>10 OR uat_only<>3 THEN
    RAISE EXCEPTION 'CALC154_LIVE_FORMULA_CONTRACT_DRIFT: %/%',formulas,uat_only;
  END IF;

  IF EXISTS (
    SELECT 1 FROM fin_calc.formula_contract_revision c
    JOIN fin_lib.lib_form_definition f ON f.form_code=c.form_code
    JOIN fin_lib.lib_form_version v ON v.id=c.form_version_id
    WHERE v.id IS DISTINCT FROM f.current_version_id
       OR c.source_schema_sha256 IS DISTINCT FROM v.schema_sha256
       OR c.financial_approval_state<>'NOT_S2_APPROVED'
       OR c.definition_sha256 !~ '^[0-9a-f]{64}

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
  (
    SELECT count(*) FROM fin_calc.formula_contract_revision c
    WHERE c.form_code=f.form_code AND c.form_version_id=v.id
  ) AS "formulaContractCandidates",
  (
    SELECT count(*) FROM fin_calc.formula_contract_revision c
    WHERE c.form_code=f.form_code AND c.form_version_id=v.id
      AND c.review_state='UAT_TEST_ONLY'
  ) AS "uatOnlyOutputs",
  0::integer AS "financiallyQualifiedOutputs",
  'BLOCKED_FINANCIAL_CONTRACT_AND_E2E'::text AS "status"
FROM fin_lib.lib_form_definition f
JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
WHERE v.content_mode IN ('ROWSET','SINGLE_RECORD')
ORDER BY f.form_code;

COMMIT;

       OR c.binding_sha256 !~ '^[0-9a-f]{64}

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

  ) THEN
    RAISE EXCEPTION 'CALC154_LIVE_FORMULA_VERSION_OR_S2_DRIFT';
  END IF;

  IF EXISTS (
    SELECT 1 FROM fin_calc.candidate_metric_operation_binding op
    LEFT JOIN fin_calc.formula_contract_revision c
      ON c.form_code=op.form_code
     AND c.output_metric_code=op.form_code||'.'||op.output_code
     AND c.operation_code=op.operation_code
     AND c.source_metrics_jsonb->>0=op.numerator_metric_code
     AND (c.source_metrics_jsonb->>1) IS NOT DISTINCT FROM op.denominator_metric_code
    WHERE c.id IS NULL
  ) THEN
    RAISE EXCEPTION 'CALC154_OPERATION_FORMULA_REVISION_DRIFT';
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
