--liquibase formatted sql
--changeset fin-mg:063a dbms:postgresql splitStatements:false
-- Additive hardening after 062: the UAT plan's source must belong to
-- the exact organization/project/form/schema/period and source content.
-- A plan forged through direct SQL must be rejected, not just Java.
CREATE OR REPLACE FUNCTION fin_calc.mg_r04_verify_plan_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_row record;
DECLARE method_row record;
DECLARE schedule_row record;
DECLARE amendment_row record;
BEGIN
 SELECT i.organization_id,i.project_id,i.period_type,i.period_code,
   i.currency,i.reporting_basis,i.data_space,f.form_code,
   f.current_version_id,r.form_version_id,
   r.canonical_content_sha256,v.schema_sha256
 INTO source_row
 FROM fin_intake.lib_form_revision r
 JOIN fin_intake.lib_form_instance i ON i.id=r.instance_id
 JOIN fin_lib.lib_form_definition f ON f.id=i.form_id
 JOIN fin_lib.lib_form_version v ON v.id=r.form_version_id
 WHERE r.id=NEW.exact_source_revision_id;
 IF NOT FOUND
  OR source_row.organization_id IS DISTINCT FROM NEW.organization_id
  OR source_row.project_id IS DISTINCT FROM NEW.project_id
  OR source_row.period_type IS DISTINCT FROM NEW.period_type
  OR source_row.period_code IS DISTINCT FROM NEW.period_code
  OR source_row.currency IS DISTINCT FROM NEW.currency_code
  OR source_row.reporting_basis IS DISTINCT FROM NEW.reporting_basis
  OR source_row.data_space IS DISTINCT FROM NEW.data_space
  OR source_row.form_code IS DISTINCT FROM NEW.source_form_code
  OR source_row.canonical_content_sha256 IS DISTINCT FROM NEW.source_content_sha256
  OR source_row.schema_sha256 IS DISTINCT FROM NEW.source_schema_sha256
 THEN
   RAISE EXCEPTION 'MG_R04_EXACT_INTAKE_SOURCE_SCOPE_SHA_DRIFT' USING ERRCODE='23514';
 END IF;

 IF NEW.execution_family='F02_TYPED_DAG' THEN
   SELECT form_code,form_version_id,source_schema_sha256,review_state,
          output_metric_code,binding_sha256,formula_version
   INTO method_row FROM fin_calc.formula_contract_revision
   WHERE id=NEW.formula_revision_id;
   IF NOT FOUND OR method_row.form_code<>'F02'
     OR method_row.review_state<>'UAT_TEST_ONLY'
     OR method_row.formula_version<>'F02_TEST_ONLY_V1'
     OR method_row.output_metric_code IS DISTINCT FROM NEW.target_metric_code
     OR method_row.source_schema_sha256 IS DISTINCT FROM NEW.source_schema_sha256
     OR method_row.binding_sha256 IS DISTINCT FROM NEW.method_binding_sha256
     OR method_row.form_version_id IS DISTINCT FROM source_row.form_version_id
     OR method_row.form_version_id IS DISTINCT FROM source_row.current_version_id
   THEN
     RAISE EXCEPTION 'MG_R04_F02_FORMULA_VERSION_SHA_NOT_PINNED' USING ERRCODE='23514';
   END IF;
 ELSIF NEW.execution_family='A05_SCHEDULE' THEN
   SELECT organization_id,project_id,source_revision_id,source_content_sha256,
     app_contract_revision_id,result_sha256,engine_descriptor_sha256
   INTO schedule_row FROM fin_calc.a05_schedule_revision
   WHERE id=NEW.schedule_revision_id;
   IF NOT FOUND
     OR schedule_row.organization_id IS DISTINCT FROM NEW.organization_id
     OR schedule_row.project_id IS DISTINCT FROM NEW.project_id
     OR schedule_row.source_revision_id IS DISTINCT FROM NEW.exact_source_revision_id
     OR schedule_row.source_content_sha256 IS DISTINCT FROM NEW.source_content_sha256
     OR schedule_row.app_contract_revision_id IS DISTINCT FROM NEW.application_contract_revision_id
     OR schedule_row.result_sha256 IS DISTINCT FROM NEW.source_lineage_sha256
     OR schedule_row.engine_descriptor_sha256 IS DISTINCT FROM NEW.engine_sha256
     OR NEW.target_metric_code<>'CALC-A05.CONTRACT_CASH'
   THEN
     RAISE EXCEPTION 'MG_R04_A05_SCHEDULE_RESULT_PIN_MISMATCH' USING ERRCODE='23514';
   END IF;
 ELSIF NEW.execution_family='A05_AMENDMENT' THEN
   SELECT a.organization_id,a.project_id,a.source_revision_id,
     a.source_content_sha256,a.result_sha256,
     a.engine_descriptor_sha256,s.app_contract_revision_id
   INTO amendment_row
   FROM fin_calc.a05_amendment_revision a
   JOIN fin_calc.a05_schedule_revision s ON s.id=a.parent_schedule_revision_id
   WHERE a.id=NEW.amendment_revision_id;
   IF NOT FOUND
     OR amendment_row.organization_id IS DISTINCT FROM NEW.organization_id
     OR amendment_row.project_id IS DISTINCT FROM NEW.project_id
     OR amendment_row.source_revision_id IS DISTINCT FROM NEW.exact_source_revision_id
     OR amendment_row.source_content_sha256 IS DISTINCT FROM NEW.source_content_sha256
     OR amendment_row.app_contract_revision_id IS DISTINCT FROM NEW.application_contract_revision_id
     OR amendment_row.result_sha256 IS DISTINCT FROM NEW.source_lineage_sha256
     OR amendment_row.engine_descriptor_sha256 IS DISTINCT FROM NEW.engine_sha256
     OR NEW.target_metric_code<>'CALC-A05.AMENDED_CONTRACT_CASH'
   THEN
     RAISE EXCEPTION 'MG_R04_A05_AMENDMENT_RESULT_PIN_MISMATCH' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NEW;
END;
$$;

--changeset fin-mg:063b dbms:postgresql splitStatements:false
CREATE FUNCTION fin_calc.mg_r04_verify_result_and_receipt()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_row record;
BEGIN
 IF TG_TABLE_NAME='mg_r04_uat_result' THEN
   SELECT organization_id,project_id,target_metric_code,execution_family,
     source_lineage_sha256 INTO parent_row
   FROM fin_calc.mg_r04_uat_plan WHERE id=NEW.plan_id;
   IF NOT FOUND
     OR parent_row.organization_id IS DISTINCT FROM NEW.organization_id
     OR parent_row.project_id IS DISTINCT FROM NEW.project_id
     OR parent_row.target_metric_code IS DISTINCT FROM NEW.target_metric_code
     OR (parent_row.execution_family<>'F02_TYPED_DAG'
       AND parent_row.source_lineage_sha256 IS DISTINCT FROM NEW.upstream_result_sha256)
   THEN RAISE EXCEPTION 'MG_R04_RESULT_SCOPE_OR_UPSTREAM_DRIFT' USING ERRCODE='23514';
   END IF;
 ELSIF TG_TABLE_NAME='mg_r04_uat_command_receipt' THEN
   SELECT p.organization_id,r.plan_id,r.organization_id AS result_org
   INTO parent_row FROM fin_calc.mg_r04_uat_plan p
   JOIN fin_calc.mg_r04_uat_result r ON r.id=NEW.result_id
   WHERE p.id=NEW.plan_id;
   IF NOT FOUND
     OR parent_row.organization_id IS DISTINCT FROM NEW.organization_id
     OR parent_row.result_org IS DISTINCT FROM NEW.organization_id
     OR parent_row.plan_id IS DISTINCT FROM NEW.plan_id
   THEN RAISE EXCEPTION 'MG_R04_COMMAND_RECEIPT_CROSS_SCOPE_OR_RESULT' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NEW;
END;
$$;

--changeset fin-mg:063c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_mg_r04_result_scope
 BEFORE INSERT ON fin_calc.mg_r04_uat_result
 FOR EACH ROW EXECUTE FUNCTION fin_calc.mg_r04_verify_result_and_receipt();
CREATE TRIGGER trg_mg_r04_receipt_scope
 BEFORE INSERT ON fin_calc.mg_r04_uat_command_receipt
 FOR EACH ROW EXECUTE FUNCTION fin_calc.mg_r04_verify_result_and_receipt();
