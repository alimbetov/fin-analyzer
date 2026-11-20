--liquibase formatted sql
--changeset fin-mg:062a dbms:postgresql splitStatements:true
-- MG-R0.4 immutable intake-first UAT plans. The existing CALC-4D FactSet
-- staging and production calc_plan/calc_job/calc_result are NOT modified.
-- plan >= formula+source+scope+algorithm+source-projection fingerprint.
CREATE TABLE fin_calc.mg_r04_uat_plan (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 project_id uuid NOT NULL,
 target_metric_code varchar(160) NOT NULL,
 execution_family varchar(32) NOT NULL
   CHECK(execution_family IN('F02_TYPED_DAG','A05_SCHEDULE','A05_AMENDMENT')),
 source_form_code varchar(3) NOT NULL CHECK(source_form_code IN('F02','C05')),
 exact_source_revision_id uuid NOT NULL REFERENCES fin_intake.lib_form_revision(id),
 formula_revision_id uuid REFERENCES fin_calc.formula_contract_revision(id),
 schedule_revision_id uuid REFERENCES fin_calc.a05_schedule_revision(id),
 amendment_revision_id uuid REFERENCES fin_calc.a05_amendment_revision(id),
 application_contract_revision_id uuid REFERENCES fin_calc.calc_application_contract_revision(id),
 source_content_sha256 char(64) NOT NULL CHECK(source_content_sha256 ~ '^[a-f0-9]{64}$'),
 source_schema_sha256 char(64) NOT NULL CHECK(source_schema_sha256 ~ '^[a-f0-9]{64}$'),
 source_projection_sha256 char(64) NOT NULL CHECK(source_projection_sha256 ~ '^[a-f0-9]{64}$'),
 method_binding_sha256 char(64) NOT NULL CHECK(method_binding_sha256 ~ '^[a-f0-9]{64}$'),
 graph_sha256 char(64) NOT NULL CHECK(graph_sha256 ~ '^[a-f0-9]{64}$'),
 engine_sha256 char(64) NOT NULL CHECK(engine_sha256 ~ '^[a-f0-9]{64}$'),
 plan_sha256 char(64) NOT NULL CHECK(plan_sha256 ~ '^[a-f0-9]{64}$'),
 period_type varchar(32) NOT NULL,
 period_code varchar(40) NOT NULL,
 currency_code char(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
 reporting_basis varchar(32) NOT NULL,
 data_space varchar(32) NOT NULL CHECK(data_space='SYNTHETIC'),
 source_lineage_sha256 char(64) NOT NULL CHECK(source_lineage_sha256 ~ '^[a-f0-9]{64}$'),
 state varchar(32) NOT NULL DEFAULT 'UAT_ONLY_NOT_S2'
   CHECK(state='UAT_ONLY_NOT_S2'),
 financially_approved boolean NOT NULL DEFAULT false CHECK(NOT financially_approved),
 production_admitted boolean NOT NULL DEFAULT false CHECK(NOT production_admitted),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(organization_id,plan_sha256),
 UNIQUE(id,organization_id,project_id),
 CHECK(
  (execution_family='F02_TYPED_DAG' AND source_form_code='F02'
   AND formula_revision_id IS NOT NULL AND schedule_revision_id IS NULL
   AND amendment_revision_id IS NULL AND application_contract_revision_id IS NULL)
  OR
  (execution_family='A05_SCHEDULE' AND source_form_code='C05'
   AND formula_revision_id IS NULL AND schedule_revision_id IS NOT NULL
   AND amendment_revision_id IS NULL AND application_contract_revision_id IS NOT NULL)
  OR
  (execution_family='A05_AMENDMENT' AND source_form_code='C05'
   AND formula_revision_id IS NULL AND schedule_revision_id IS NULL
   AND amendment_revision_id IS NOT NULL AND application_contract_revision_id IS NOT NULL)
 )
);
CREATE INDEX idx_mg_r04_intake_scope
 ON fin_calc.mg_r04_uat_plan(organization_id,project_id,exact_source_revision_id);

CREATE TABLE fin_calc.mg_r04_uat_result (
 id uuid PRIMARY KEY,
 plan_id uuid NOT NULL,
 organization_id uuid NOT NULL,
 project_id uuid NOT NULL,
 target_metric_code varchar(160) NOT NULL,
 value_state varchar(16) NOT NULL CHECK(value_state IN('PRESENT','INVALID')),
 value_unit varchar(16) NOT NULL,
 value_text varchar(80),
 error_code varchar(100),
 upstream_result_sha256 char(64) NOT NULL CHECK(upstream_result_sha256 ~ '^[a-f0-9]{64}$'),
 result_sha256 char(64) NOT NULL CHECK(result_sha256 ~ '^[a-f0-9]{64}$'),
 evidence_jsonb jsonb NOT NULL CHECK(jsonb_typeof(evidence_jsonb)='object'),
 state varchar(32) NOT NULL DEFAULT 'UAT_UNQUALIFIED'
   CHECK(state='UAT_UNQUALIFIED'),
 financially_approved boolean NOT NULL DEFAULT false CHECK(NOT financially_approved),
 production_admitted boolean NOT NULL DEFAULT false CHECK(NOT production_admitted),
 computed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(plan_id),
 FOREIGN KEY(plan_id,organization_id,project_id)
   REFERENCES fin_calc.mg_r04_uat_plan(id,organization_id,project_id),
 CHECK((value_state='PRESENT' AND value_text IS NOT NULL AND error_code IS NULL)
   OR (value_state='INVALID' AND value_text IS NULL AND error_code IS NOT NULL)),
 CHECK(value_text IS NULL OR value_text ~ '^-?[0-9]{1,38}(\.[0-9]{1,24})?$')
);

CREATE TABLE fin_calc.mg_r04_uat_command_receipt (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 idempotency_key varchar(200) NOT NULL,
 command_sha256 char(64) NOT NULL CHECK(command_sha256 ~ '^[a-f0-9]{64}$'),
 plan_id uuid NOT NULL REFERENCES fin_calc.mg_r04_uat_plan(id),
 result_id uuid NOT NULL REFERENCES fin_calc.mg_r04_uat_result(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(organization_id,idempotency_key)
);

--changeset fin-mg:062b dbms:postgresql splitStatements:false
-- Store only exact existing source and method identities, never a spoofed
-- UAT graph plan for an unrelated formula/source/A05 result.
CREATE FUNCTION fin_calc.mg_r04_verify_plan_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE f record;
DECLARE s record;
DECLARE a record;
BEGIN
 IF NEW.execution_family='F02_TYPED_DAG' THEN
   SELECT form_code,source_schema_sha256,review_state,output_metric_code
   INTO f FROM fin_calc.formula_contract_revision
   WHERE id=NEW.formula_revision_id;
   IF NOT FOUND OR f.form_code<>'F02' OR f.review_state<>'UAT_TEST_ONLY'
     OR f.output_metric_code IS DISTINCT FROM NEW.target_metric_code
     OR f.source_schema_sha256 IS DISTINCT FROM NEW.source_schema_sha256
   THEN
     RAISE EXCEPTION 'MG_R04_F02_METHOD_REVISION_NOT_PINNED' USING ERRCODE='23514';
   END IF;
 ELSIF NEW.execution_family='A05_SCHEDULE' THEN
   SELECT organization_id,project_id,source_revision_id,
     source_content_sha256,source_result_sha256
   INTO s FROM (
     SELECT organization_id,project_id,source_revision_id,
       source_content_sha256,result_sha256 AS source_result_sha256
     FROM fin_calc.a05_schedule_revision WHERE id=NEW.schedule_revision_id
   ) q;
   IF NOT FOUND OR s.organization_id IS DISTINCT FROM NEW.organization_id
     OR s.project_id IS DISTINCT FROM NEW.project_id
     OR s.source_revision_id IS DISTINCT FROM NEW.exact_source_revision_id
     OR s.source_content_sha256 IS DISTINCT FROM NEW.source_content_sha256
     OR s.source_result_sha256 IS DISTINCT FROM NEW.source_lineage_sha256
     OR NEW.target_metric_code<>'CALC-A05.CONTRACT_CASH'
   THEN
     RAISE EXCEPTION 'MG_R04_A05_PARENT_PIN_MISMATCH' USING ERRCODE='23514';
   END IF;
 ELSIF NEW.execution_family='A05_AMENDMENT' THEN
   SELECT organization_id,project_id,source_revision_id,
     source_content_sha256,result_sha256
   INTO a FROM fin_calc.a05_amendment_revision
   WHERE id=NEW.amendment_revision_id;
   IF NOT FOUND OR a.organization_id IS DISTINCT FROM NEW.organization_id
     OR a.project_id IS DISTINCT FROM NEW.project_id
     OR a.source_revision_id IS DISTINCT FROM NEW.exact_source_revision_id
     OR a.source_content_sha256 IS DISTINCT FROM NEW.source_content_sha256
     OR a.result_sha256 IS DISTINCT FROM NEW.source_lineage_sha256
     OR NEW.target_metric_code<>'CALC-A05.AMENDED_CONTRACT_CASH'
   THEN
     RAISE EXCEPTION 'MG_R04_A05_AMENDMENT_PIN_MISMATCH' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NEW;
END;
$$;

--changeset fin-mg:062c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_mg_r04_plan_source
 BEFORE INSERT ON fin_calc.mg_r04_uat_plan
 FOR EACH ROW EXECUTE FUNCTION fin_calc.mg_r04_verify_plan_source();

CREATE TRIGGER trg_mg_r04_plan_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.mg_r04_uat_plan
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_mg_r04_result_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.mg_r04_uat_result
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_mg_r04_receipt_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.mg_r04_uat_command_receipt
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_mg_r04_plan_no_truncate
 BEFORE TRUNCATE ON fin_calc.mg_r04_uat_plan
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_mg_r04_result_no_truncate
 BEFORE TRUNCATE ON fin_calc.mg_r04_uat_result
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_mg_r04_receipt_no_truncate
 BEFORE TRUNCATE ON fin_calc.mg_r04_uat_command_receipt
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
