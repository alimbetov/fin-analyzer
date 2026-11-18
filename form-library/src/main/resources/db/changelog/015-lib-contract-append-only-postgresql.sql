--liquibase formatted sql
--changeset fin-form:015a dbms:postgresql splitStatements:false
-- PostgreSQL-only defense in depth. Existing R7 presentation rows remain editable.
CREATE FUNCTION fin_lib.r16_reject_contract_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'FIN_R16_IMMUTABLE_CONTRACT' USING ERRCODE = '23514';
END;
$$;

--changeset fin-form:015b dbms:postgresql splitStatements:true
CREATE TRIGGER r16_policy_immutable BEFORE UPDATE OR DELETE
 ON fin_lib.lib_form_contract_policy FOR EACH ROW
 EXECUTE FUNCTION fin_lib.r16_reject_contract_mutation();
CREATE TRIGGER r16_field_policy_immutable BEFORE UPDATE OR DELETE
 ON fin_lib.lib_form_field_policy FOR EACH ROW
 EXECUTE FUNCTION fin_lib.r16_reject_contract_mutation();
CREATE TRIGGER r16_period_immutable BEFORE UPDATE OR DELETE
 ON fin_lib.lib_form_allowed_period FOR EACH ROW
 EXECUTE FUNCTION fin_lib.r16_reject_contract_mutation();
CREATE TRIGGER r16_dictionary_immutable BEFORE UPDATE OR DELETE
 ON fin_lib.lib_form_field_dictionary FOR EACH ROW
 EXECUTE FUNCTION fin_lib.r16_reject_contract_mutation();
CREATE TRIGGER r16_metric_binding_immutable BEFORE UPDATE OR DELETE
 ON fin_lib.lib_form_contract_metric_binding FOR EACH ROW
 EXECUTE FUNCTION fin_lib.r16_reject_contract_mutation();
