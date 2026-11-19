--liquibase formatted sql
--changeset fin-calc:034a dbms:postgresql splitStatements:true
-- CALC-166: explicit candidate bindings to ACTUAL fields. Deliberately not
-- fin_calc.method_revision, not qualification, and not an executable admission.
CREATE TABLE fin_calc.candidate_metric_operation_binding (
  form_code varchar(128) NOT NULL,
  operation_code varchar(32) NOT NULL
    CHECK(operation_code IN ('GROUP_BY','RATIO_OF_SUMS')),
  output_code varchar(128) NOT NULL,
  numerator_metric_code varchar(160) NOT NULL
    REFERENCES fin_lib.lib_metric_definition(metric_code),
  denominator_metric_code varchar(160)
    REFERENCES fin_lib.lib_metric_definition(metric_code),
  review_state varchar(40) NOT NULL DEFAULT 'CANDIDATE_UNQUALIFIED'
    CHECK(review_state='CANDIDATE_UNQUALIFIED'),
  PRIMARY KEY(form_code,operation_code,output_code),
  CHECK ((operation_code='GROUP_BY' AND denominator_metric_code IS NULL)
      OR (operation_code='RATIO_OF_SUMS' AND denominator_metric_code IS NOT NULL
          AND numerator_metric_code<>denominator_metric_code))
);

--changeset fin-calc:034b dbms:postgresql splitStatements:false
-- Bind only real SOURCE_FIELD metrics in the matching current form version.
-- The table is editorial proposal inventory; ingestion/time/unit policy review
-- remains outstanding, so neither SQL functions nor JDBC auto-activate them.
DO $$
DECLARE rejected integer;
BEGIN
  WITH proposals(form_code,operation_code,output_code,num_metric,den_metric) AS (
    VALUES
      ('F02','RATIO_OF_SUMS','cogsToRevenueCandidate','F02.cogs','F02.netRevenue'),
      ('C07','GROUP_BY','grossSalesGroupedCandidate','C07.grossSales',NULL),
      ('C07','GROUP_BY','returnsGroupedCandidate','C07.returns',NULL),
      ('C07','RATIO_OF_SUMS','returnsShareCandidate','C07.returns','C07.grossSales'),
      ('C09','GROUP_BY','expenseAmountGroupedCandidate','C09.amount',NULL)
  )
  SELECT count(*) INTO rejected FROM proposals p
  LEFT JOIN fin_lib.lib_form_definition f ON f.form_code=p.form_code
  LEFT JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
  LEFT JOIN fin_lib.lib_metric_definition numerator
    ON numerator.metric_code=p.num_metric AND numerator.form_code=p.form_code
     AND numerator.metric_kind='SOURCE_FIELD'
  LEFT JOIN fin_lib.lib_form_field nf
    ON nf.form_version_id=v.id AND nf.field_code=numerator.source_field_code
  LEFT JOIN fin_lib.lib_metric_definition denominator
    ON denominator.metric_code=p.den_metric AND denominator.form_code=p.form_code
     AND denominator.metric_kind='SOURCE_FIELD'
  LEFT JOIN fin_lib.lib_form_field df
    ON df.form_version_id=v.id AND df.field_code=denominator.source_field_code
  WHERE nf.field_code IS NULL OR (p.den_metric IS NOT NULL AND df.field_code IS NULL);
  IF rejected<>0 THEN
    RAISE EXCEPTION 'CALC166_CANDIDATE_METRIC_MAPPING_DRIFT: %',rejected
      USING ERRCODE='23514';
  END IF;
END;
$$;

--changeset fin-calc:034c dbms:postgresql splitStatements:true
MERGE INTO fin_calc.candidate_metric_operation_binding AS target
USING (VALUES
 ('F02','RATIO_OF_SUMS','cogsToRevenueCandidate','F02.cogs','F02.netRevenue'),
 ('C07','GROUP_BY','grossSalesGroupedCandidate','C07.grossSales',NULL),
 ('C07','GROUP_BY','returnsGroupedCandidate','C07.returns',NULL),
 ('C07','RATIO_OF_SUMS','returnsShareCandidate','C07.returns','C07.grossSales'),
 ('C09','GROUP_BY','expenseAmountGroupedCandidate','C09.amount',NULL)
) AS source(form_code,operation_code,output_code,numerator_metric_code,denominator_metric_code)
ON target.form_code=source.form_code
 AND target.operation_code=source.operation_code
 AND target.output_code=source.output_code
WHEN MATCHED AND
 (target.numerator_metric_code IS DISTINCT FROM source.numerator_metric_code
  OR target.denominator_metric_code IS DISTINCT FROM source.denominator_metric_code)
THEN UPDATE SET numerator_metric_code=source.numerator_metric_code,
 denominator_metric_code=source.denominator_metric_code
WHEN NOT MATCHED THEN
 INSERT(form_code,operation_code,output_code,numerator_metric_code,denominator_metric_code)
 VALUES(source.form_code,source.operation_code,source.output_code,
        source.numerator_metric_code,source.denominator_metric_code);
