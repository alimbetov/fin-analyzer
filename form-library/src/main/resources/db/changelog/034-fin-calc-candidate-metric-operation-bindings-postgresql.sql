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

--changeset fin-calc:034b dbms:postgresql splitStatements:true
-- BOOT ORDER FIX: Liquibase runs BEFORE CatalogBootstrap and
-- FieldMetricContractBootstrap. Therefore no catalog-dependent check or
-- seeded MERGE can execute here. Validations run in the ordered Java bootstrap.
SELECT 1;

--changeset fin-calc:034c dbms:postgresql splitStatements:true
-- Candidate mappings are seeded post-catalog, not during schema migration.
SELECT 1;
