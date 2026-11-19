--liquibase formatted sql
--changeset fin-fact:021a dbms:postgresql splitStatements:true
-- FACT-0.H: a calculated plan cannot bind a FactSet belonging to another
-- organization or business/reporting scope. Prior 019/020 remain immutable.
ALTER TABLE fin_calc.calc_plan DROP CONSTRAINT fk_calc_plan_factset;
ALTER TABLE fin_calc.calc_plan ADD CONSTRAINT fk_calc_plan_factset_scope
 FOREIGN KEY(factset_id,organization_id,scope_key)
 REFERENCES fin_fact.fact_set(id,organization_id,scope_key);

--changeset fin-fact:021b dbms:postgresql splitStatements:true
-- INGEST-2 hashes must be backed by append-only persisted source data.
-- Only current_revision_id/status on lib_form_instance remain editable.
CREATE TRIGGER trg_source_revision_append_only
 BEFORE UPDATE OR DELETE ON fin_intake.lib_form_revision
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_source_revision_row_append_only
 BEFORE UPDATE OR DELETE ON fin_intake.lib_form_revision_row
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_source_revision_evidence_append_only
 BEFORE UPDATE OR DELETE ON fin_intake.lib_form_revision_evidence
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

--changeset fin-fact:021c dbms:postgresql splitStatements:true
-- Catch accidental TRUNCATE against named immutable parent/source relations.
-- Does NOT replace owner/DDL privilege separation or partition-child privileges.
CREATE TRIGGER trg_fact_set_no_truncate BEFORE TRUNCATE
 ON fin_fact.fact_set FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_fact_observation_no_truncate BEFORE TRUNCATE
 ON fin_fact.fact_observation FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc_plan_no_truncate BEFORE TRUNCATE
 ON fin_calc.calc_plan FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc_result_no_truncate BEFORE TRUNCATE
 ON fin_calc.calc_result FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_source_revision_no_truncate BEFORE TRUNCATE
 ON fin_intake.lib_form_revision FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_source_revision_row_no_truncate BEFORE TRUNCATE
 ON fin_intake.lib_form_revision_row FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_source_revision_evidence_no_truncate BEFORE TRUNCATE
 ON fin_intake.lib_form_revision_evidence FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
